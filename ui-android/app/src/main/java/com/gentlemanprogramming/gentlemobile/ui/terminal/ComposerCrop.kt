package com.gentlemanprogramming.gentlemobile.ui.terminal

import com.gentlemanprogramming.gentlemobile.core.CellDto
import com.gentlemanprogramming.gentlemobile.core.SurfaceDto
import kotlin.math.abs

/**
 * The desktop-composer crop (S29, reshaped by T26/S31b). The frame the phone
 * paints includes pi's own composer — the `✿ waiting for input ─── [ Panel ]
 * INSERT ─` bar and the `› type, or / for commands` prompt row under it. The
 * phone writes through its own composer, so that box is unusable chrome:
 * this transform finds the composer rows in the frame's row grid and blanks
 * them **in place** — the grid keeps every row at its own position, so no
 * card height is spent on blank padding, the frame still fills the card in
 * both directions (S31b), and the metrics lines keep their own rows at the
 * bottom. The pane is asked for exactly the card's derived rows; the old
 * `+4` allowance and the pad-at-top shift are gone.
 *
 * Detection is deliberately conservative, and the **prompt row is the
 * primary evidence** (measured defect, T44: while an agent works the bar
 * reads `─ ✿ working ─────`, not the idle label, and the crop declined with
 * the composer still painted). Only the last [WINDOW] rows are inspected;
 * the prompt row must start with the prompt glyph (`›` or `>`) followed
 * directly (spaces only) by pi's exact hint (`PROMPT_HINT` is a constant —
 * pi emits no localized variant); the bar above it is **optional** — its
 * label varies (`waiting for input`, `working`, `thinking`), it may show
 * only the panel, or it may be absent — so it only widens the block when it
 * sits within one row of a confirmed prompt row, and it is recognised by
 * structure (the `✿` petal, the `[ Panel ]` tag, or a label riding a rule
 * of dashes), never by a bare word; and a row that carries the metrics
 * (`tok/s`, `files ·`, `+N −M`) is never removed. If the prompt row is not
 * clearly there, nothing is removed — showing a redundant box is better
 * than hiding an agent's line.
 *
 * Pure transform so the rule is unit-tested without a UI runtime
 * (ComposerCropTest).
 */
object ComposerCrop {

    /** How many trailing rows the detector inspects. */
    const val WINDOW = 6

    /** Never remove more than this many rows. */
    const val MAX_REMOVE = 4

    /** pi's prompt hint, verbatim (`PROMPT_HINT` in gentle-pi's shell-prompt). */
    const val PROMPT_HINT = "type, or / for commands"

    /** pi's idle panel label (`stateLabel` fallback in gentle-pi's shell-prompt). */
    const val BAR_MARKER = "waiting for input"

    /** The composer bar's other labels (measured while an agent streams). */
    private val BAR_LABELS = listOf(BAR_MARKER, "working", "thinking")

    /** The bar's structural markers: the petal and the panel tag. */
    private const val PETAL = "✿"
    private const val PANEL = "[ Panel ]"
    private const val RULE = "──"

    /** The metrics markers (S29): a row carrying any of these is never removed. */
    private const val METRICS_TPS = "tok/s"
    private const val METRICS_FILES = "files ·"
    private val METRICS_DIFF = Regex("""\+\d+\s*[−-]\s*\d+""")

    class Result(
        /** The transformed frame: same dimensions, the composer's rows blank
         *  in place, every other row at its own position. */
        val surface: SurfaceDto,
        /** How many rows were blanked (0 = the transform declined to act). */
        val removedRows: Int,
    )

    /** Apply the crop to one decoded frame. The input frame is never mutated. */
    fun crop(surface: SurfaceDto): Result {
        val height = surface.height.toInt()
        val width = surface.width.toInt()
        if (height <= 0 || width <= 0 || surface.cells.size < height * width) {
            return Result(surface, 0)
        }
        val block = detectBlock(surface) ?: return Result(surface, 0)
        return Result(transform(surface, block), block.last - block.first + 1)
    }

    // --- detection ------------------------------------------------------------

    /** The composer's row span, or `null` when the prompt row is not clearly
     *  there. The bar is optional: a confirmed prompt row alone crops. */
    private fun detectBlock(surface: SurfaceDto): IntRange? {
        val height = surface.height.toInt()
        val windowStart = maxOf(0, height - WINDOW)
        // Scan from the bottom up: the composer sits at the frame's bottom,
        // so the bottom-most match is the real one.
        var prompt = -1
        var bar = -1
        for (row in height - 1 downTo windowStart) {
            val text = rowText(surface, row)
            if (prompt < 0 && isPromptRow(text)) prompt = row
            if (bar < 0 && isBarRow(text)) bar = row
        }
        if (prompt < 0) return null
        val block = if (bar >= 0 && abs(prompt - bar) <= 1) {
            minOf(prompt, bar)..maxOf(prompt, bar)
        } else {
            prompt..prompt
        }
        if (block.last - block.first + 1 > MAX_REMOVE) return null
        for (row in block) {
            if (looksLikeMetrics(rowText(surface, row))) return null
        }
        return block
    }

    /**
     * The prompt row: starts with the prompt glyph and pi's hint comes
     * directly after it (spaces only). A line that merely *contains* the
     * hint — an agent quoting the prompt, a paragraph carrying it — is not
     * the composer.
     */
    private fun isPromptRow(text: String): Boolean {
        val trimmed = text.trimStart()
        val body = when {
            trimmed.startsWith("›") -> trimmed.removePrefix("›")
            trimmed.startsWith(">") -> trimmed.removePrefix(">")
            else -> return false
        }
        return body.trimStart().startsWith(PROMPT_HINT)
    }

    /**
     * The panel bar, by structure: the `✿` petal, the `[ Panel ]` tag, or
     * one of pi's state labels riding a rule of dashes. The label alone is
     * never enough — `working` is a word prose uses too.
     */
    private fun isBarRow(text: String): Boolean =
        text.contains(PANEL) || text.contains(PETAL) ||
            (text.contains(RULE) && BAR_LABELS.any { text.contains(it) })

    /** A metrics row is never a composer row (S29). */
    private fun looksLikeMetrics(text: String): Boolean =
        text.contains(METRICS_TPS) || text.contains(METRICS_FILES) || METRICS_DIFF.containsMatchIn(text)

    // --- the row-grid transform -------------------------------------------------

    /** One row's visible text: the cells' glyphs joined, right-trimmed. */
    internal fun rowText(surface: SurfaceDto, row: Int): String {
        val width = surface.width.toInt()
        val start = row * width
        return surface.cells.subList(start, start + width)
            .joinToString("") { it.symbol.ifEmpty { " " } }
            .trimEnd()
    }

    /**
     * Blank the block's rows in place: the grid keeps its dimensions and
     * every surviving row keeps its position — no shift, no padding (S31b).
     * A cursor inside the blanked block is dropped (it lived in the
     * composer's input); every other cursor keeps its row.
     */
    private fun transform(surface: SurfaceDto, block: IntRange): SurfaceDto {
        val width = surface.width.toInt()
        val height = surface.height.toInt()
        val cells = ArrayList<CellDto>(surface.cells.size)
        for (row in 0 until height) {
            if (row in block) {
                cells.addAll(blankRow(width))
            } else {
                cells.addAll(surface.cells.subList(row * width, (row + 1) * width))
            }
        }
        val cursor = surface.cursor?.takeIf { it.y.toInt() !in block }
        return SurfaceDto(
            width = surface.width,
            height = surface.height,
            revision = surface.revision,
            cells = cells,
            cursor = cursor,
            popupPresent = surface.popupPresent,
        )
    }

    private fun blankRow(width: Int): List<CellDto> = List(width) {
        CellDto(symbol = "", fg = 0u, bg = 0u, flags = 0u, wide = false, skip = false)
    }
}
