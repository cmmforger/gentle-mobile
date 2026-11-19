package com.gentlemanprogramming.gentlemobile.ui.terminal

import com.gentlemanprogramming.gentlemobile.core.CellDto
import com.gentlemanprogramming.gentlemobile.core.CursorDto
import com.gentlemanprogramming.gentlemobile.core.SurfaceDto
import kotlin.math.abs

/**
 * The desktop-composer crop (S29). The frame the phone paints includes pi's
 * own composer — the `✿ waiting for input ─── [ Panel ] INSERT ─` bar and the
 * `› type, or / for commands` prompt row under it. The phone writes through
 * its own composer, so that box is unusable chrome: this transform finds the
 * composer rows in the frame's row grid, removes them, shifts every row below
 * them up, and pads the freed space at the top of the grid with blank rows so
 * the grid keeps its dimensions and the metrics lines stay at the bottom.
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
        /** The transformed frame: same dimensions, composer rows replaced by
         *  blank padding at the top, rows below shifted up into their place. */
        val surface: SurfaceDto,
        /** How many rows were removed (0 = the transform declined to act). */
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

    /**
     * The pane row count to ask for (S28-3 derivation + S29 allowance): the
     * derived rows **plus the full allowance** — never the measured removed
     * rows, which are 0 on the first pass, so the freed rows would never
     * come back and the card kept its empty area (measured defect, T44).
     * The host keeps painting the composer, the phone crops at most
     * [MAX_REMOVE] rows, so the cropped frame still holds at least the
     * derived rows and still fills the card that derived them.
     */
    fun requestRows(derivedRows: Int): Int = derivedRows + MAX_REMOVE

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
     * Remove the block's rows, shift the rows below up, and pad the freed
     * space at the top of the grid with blank rows: the grid keeps its
     * dimensions, the metrics stay at the bottom, and rows above the composer
     * shift down with the pad. A cursor inside the removed block is dropped
     * (it lived in the composer's input); a cursor above shifts with the pad;
     * a cursor below keeps its row.
     */
    private fun transform(surface: SurfaceDto, block: IntRange): SurfaceDto {
        val width = surface.width.toInt()
        val height = surface.height.toInt()
        val removed = block.last - block.first + 1
        val cells = ArrayList<CellDto>(surface.cells.size)
        repeat(removed) { cells.addAll(blankRow(width)) }
        for (row in 0 until height) {
            if (row in block) continue
            cells.addAll(surface.cells.subList(row * width, (row + 1) * width))
        }
        val cursor = surface.cursor?.let { shiftCursor(it, block, removed) }
        return SurfaceDto(
            width = surface.width,
            height = surface.height,
            revision = surface.revision,
            cells = cells,
            cursor = cursor,
            popupPresent = surface.popupPresent,
        )
    }

    private fun shiftCursor(cursor: CursorDto, block: IntRange, removed: Int): CursorDto? {
        val y = cursor.y.toInt()
        return when {
            y in block -> null
            y < block.first -> cursor.copy(y = (y + removed).toUInt())
            else -> cursor
        }
    }

    private fun blankRow(width: Int): List<CellDto> = List(width) {
        CellDto(symbol = "", fg = 0u, bg = 0u, flags = 0u, wide = false, skip = false)
    }
}
