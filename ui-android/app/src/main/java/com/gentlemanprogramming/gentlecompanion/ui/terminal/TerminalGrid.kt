package com.gentlemanprogramming.gentlecompanion.ui.terminal

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.res.ResourcesCompat
import com.gentlemanprogramming.gentlecompanion.R
import com.gentlemanprogramming.gentlecompanion.core.CellDto
import com.gentlemanprogramming.gentlecompanion.core.SurfaceDto

/**
 * The terminal grid: a real cell renderer over the core's decoded surface
 * DTOs (`core/src/surface.rs`) — background per cell, glyphs in the resolved
 * foreground colour, bold/italic/underline/strike where the wire carries
 * them, wide glyphs spanning two columns, and the cursor.
 *
 * BUNDLED FONT: JetBrains Mono 2.304 (Regular/Bold/Italic/BoldItalic),
 * licensed under the SIL Open Font License 1.1 — the full licence text ships
 * beside this source set as `ui-android/JETBRAINS-MONO-OFL.txt`. It is
 * bundled rather than using the system default so the cell advance is
 * predictable: one "M" measured at the paint's text size is exactly one
 * column, identical on every device.
 *
 * Positioning model: the wire buffer is one cell per column slot, so cell
 * `index` paints at row `index / cols`, column `index % cols`. A wide glyph
 * draws across both of its slots; the follower slot (skip=true) paints its
 * own background but no glyph. This is the same rule the core tests pin in
 * `surface.rs` (wide cells advance two columns, the skipped follower none).
 *
 * Terminal palette: glyph and cell colours come from the frame itself
 * (resolved by the core against the host's mostaccin palette); the canvas
 * behind the grid uses the frame's default background, so the terminal area
 * stays on the terminal palette while the app chrome keeps its own tokens.
 */

/** Attribute flag bits decoded by the core; keep in step with `FLAG_*` there. */
object CellFlags {
    const val BOLD: Int = 1
    const val DIM: Int = 2
    const val ITALIC: Int = 4
    const val UNDERLINE: Int = 8
    const val REVERSED: Int = 16
    const val HIDDEN: Int = 32
    const val STRUCK: Int = 64
}

/** The core's `DEFAULT_BG` (host Ghostty mostaccin theme), as a Compose colour. */
val TerminalDefaultBackground = Color(0xFF0A0609)

/**
 * Column advance of the bundled JetBrains Mono as a fraction of the font
 * size (600/1000 units per em): the constant that makes fit-to-width
 * arithmetic exact without measuring a paint first.
 */
const val TERMINAL_FONT_RATIO = 0.6f

/** Readability floor for the terminal font, in sp: when fitting all the
 * grid's columns would shrink the font below this, the font stops
 * shrinking and the grid scrolls horizontally instead — the right edge is
 * never silently clipped. */
val TerminalFontMinSp = 10.sp

/** Pinch zoom ceiling, in sp. */
val TerminalFontMaxSp = 28.sp

/**
 * Slot metrics of the bundled font as fractions of the font size, measured
 * with the bundled regular face — never the system fallback. The fit rule
 * and the renderer must agree on these, or slots and glyphs drift apart
 * (the renderer measures its own paint per frame, with the same typeface).
 */
class TerminalCellMetrics(val advanceRatio: Float, val lineHeightRatio: Float)

/** Measure the bundled font once. */
@Composable
fun rememberTerminalCellMetrics(): TerminalCellMetrics {
    val typefaces = rememberTypefaces()
    return remember {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.typeface = typefaces.regular ?: paint.typeface
        paint.textSize = 100f
        val fm = paint.fontMetrics
        TerminalCellMetrics(
            advanceRatio = paint.measureText("M") / 100f,
            lineHeightRatio = (fm.descent - fm.ascent) / 100f,
        )
    }
}
/** The core's `DEFAULT_FG`, used when a cursor cell has no cell data. */
private const val TERMINAL_DEFAULT_FG: Int = 0xFFF6EFF3.toInt()

/** Wire cursor shapes: 0 default (block), 1/2 block, 3/4 underline, 5/6 bar. */
private fun cursorKind(shape: Int): String = when (shape) {
    3, 4 -> "underline"
    5, 6 -> "bar"
    else -> "block"
}

/** Paintable facts about one surface, computed once per revision. */
data class SurfaceStats(
    val cols: Int,
    val rows: Int,
    val drawnCells: Int,
    val distinctColours: Int,
    val wideSymbol: String?,
    val cursorVisible: Boolean,
)

/** Compute the stats the meta line and the verification report need.
 * "Drawn" means non-blank: a cell whose glyph carries visible ink — a
 * space-only cell is blank, however wide its padding. */
fun surfaceStats(surface: SurfaceDto): SurfaceStats {
    val colours = HashSet<Int>(64)
    var drawn = 0
    var wide: String? = null
    for (cell in surface.cells) {
        colours.add(cell.fg.toInt())
        colours.add(cell.bg.toInt())
        if (!cell.skip && cell.symbol.isNotBlank()) {
            drawn++
            if (wide == null && cell.wide) wide = cell.symbol
        }
    }
    return SurfaceStats(
        cols = surface.width.toInt(),
        rows = surface.height.toInt(),
        drawnCells = drawn,
        distinctColours = colours.size,
        wideSymbol = wide,
        cursorVisible = surface.cursor?.visible == true,
    )
}

/** The four bundled typefaces, resolved once (null → system fallback). */
private class TerminalTypefaces(
    val regular: Typeface?,
    val bold: Typeface?,
    val italic: Typeface?,
    val boldItalic: Typeface?,
)

@Composable
private fun rememberTypefaces(): TerminalTypefaces {
    val context = LocalContext.current
    return remember {
        fun font(id: Int): Typeface? = runCatching { ResourcesCompat.getFont(context, id) }.getOrNull()
        TerminalTypefaces(
            regular = font(R.font.jetbrainsmono_regular),
            bold = font(R.font.jetbrainsmono_bold),
            italic = font(R.font.jetbrainsmono_italic),
            boldItalic = font(R.font.jetbrainsmono_bolditalic),
        )
    }
}

/** One reusable android.graphics.Paint: property writes are cheap, allocation is not. */
private class TerminalPainter(val typefaces: TerminalTypefaces) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun typefaceFor(flags: Int): Typeface? = when {
        flags and CellFlags.BOLD != 0 && flags and CellFlags.ITALIC != 0 -> typefaces.boldItalic
        flags and CellFlags.BOLD != 0 -> typefaces.bold
        flags and CellFlags.ITALIC != 0 -> typefaces.italic
        else -> typefaces.regular
    }
}

/**
 * Draw the grid at the given font size (pixels; the caller computes the
 * fit-to-width / pinch-scaled size). One paint, two passes: backgrounds
 * (run-merged), then glyphs, then the cursor on top.
 */
@Composable
fun TerminalSurface(
    surface: SurfaceDto?,
    fontSizePx: Float,
    modifier: Modifier,
) {
    val typefaces = rememberTypefaces()
    val painter = remember { TerminalPainter(typefaces) }
    Canvas(modifier = modifier) {
        if (surface == null || surface.cells.isEmpty()) return@Canvas
        drawGrid(surface, painter, fontSizePx)
    }
}

private fun DrawScope.drawGrid(
    surface: SurfaceDto,
    painter: TerminalPainter,
    fontSizePx: Float,
) {
    val canvas = drawContext.canvas.nativeCanvas
    val paint = painter.paint
    paint.textSize = fontSizePx
    val cols = surface.width.toInt().coerceAtLeast(1)
    val rows = surface.height.toInt()

    // Cell metrics from the bundled font: the "M" advance IS the column
    // width; the cell height is the full ascent+descent span. Measure with
    // the typeface the regular glyph pass will use — measuring before the
    // first typeface assignment left whatever the paint carried (the system
    // Roboto on the first frame), whose advance is not the bundled font's
    // 0.6em, so slots and glyphs disagreed.
    paint.typeface = painter.typefaceFor(0)
    val metrics = paint.fontMetrics
    val cellWidth = paint.measureText("M")
    val cellHeight = metrics.descent - metrics.ascent
    val baseline = -metrics.ascent

    fun cellX(column: Int): Float = column * cellWidth
    fun cellY(row: Int): Float = row * cellHeight

    fun bgColor(cell: CellDto): Int {
        val flags = cell.flags.toInt()
        return ((if (flags and CellFlags.REVERSED != 0) cell.fg else cell.bg).toInt())
    }

    fun glyphColor(cell: CellDto): Int {
        val flags = cell.flags.toInt()
        return (if (flags and CellFlags.REVERSED != 0) cell.bg else cell.fg).toInt()
    }

    // Terminal canvas: the frame's default background first.
    drawRect(TerminalDefaultBackground, Offset.Zero, Size(size.width, size.height))

    // Background pass: each cell paints its own slot; consecutive slots with
    // the same bg merge into one rect (flushed on colour change or row end).
    var runStart = -1
    var runColor = 0
    var runRow = -1
    fun flushRun(endColumn: Int) {
        if (runStart >= 0 && runRow in 0 until rows) {
            drawRect(
                Color(runColor),
                Offset(cellX(runStart), cellY(runRow)),
                Size(cellX(endColumn) - cellX(runStart), cellHeight),
            )
        }
        runStart = -1
    }
    for ((index, cell) in surface.cells.withIndex()) {
        val row = index / cols
        val column = index % cols
        val bg = bgColor(cell)
        if (row >= rows) break
        if (row != runRow) {
            // The previous run ends at the end of its own row.
            flushRun(cols)
            runRow = row
            runColor = bg
            runStart = column
        } else if (bg != runColor) {
            flushRun(column)
            runColor = bg
            runStart = column
        }
    }
    flushRun(cols)

    // Glyph pass.
    for ((index, cell) in surface.cells.withIndex()) {
        val row = index / cols
        if (row >= rows) break
        val column = index % cols
        if (cell.skip || cell.symbol.isEmpty()) continue
        val flags = cell.flags.toInt()
        if (flags and CellFlags.HIDDEN != 0) continue
        val alpha = if (flags and CellFlags.DIM != 0) 0.62f else 1f
        paint.color = glyphColor(cell)
        paint.alpha = ((alpha) * 255f).toInt().coerceIn(0, 255)
        paint.typeface = painter.typefaceFor(flags)
        paint.isUnderlineText = flags and CellFlags.UNDERLINE != 0
        paint.isStrikeThruText = flags and CellFlags.STRUCK != 0
        canvas.drawText(cell.symbol, cellX(column), cellY(row) + baseline, paint)
    }
    paint.alpha = 255

    // Cursor: drawn last, on top. A block inverts its cell; the glyph under
    // it redraws in the cell's background colour so it stays readable.
    val cursor = surface.cursor
    if (cursor != null && cursor.visible) {
        val cx = cursor.x.toInt()
        val cy = cursor.y.toInt()
        if (cx < cols && cy < rows) {
            val cell = surface.cells.getOrNull(cy * cols + cx)
            val fgInt = cell?.fg?.toInt() ?: 0xFFF6EFF3.toInt()
            paint.isUnderlineText = false
            paint.isStrikeThruText = false
            paint.typeface = painter.typefaceFor((cell?.flags ?: 0u).toInt())
            when (cursorKind(cursor.shape.toInt())) {
                "underline" -> {
                    paint.color = fgInt
                    drawRect(Color(paint.color), Offset(cellX(cx), cellY(cy) + cellHeight * 0.8f), Size(cellWidth, cellHeight * 0.2f))
                }
                "bar" -> {
                    paint.color = fgInt
                    drawRect(Color(paint.color), Offset(cellX(cx), cellY(cy)), Size(cellWidth * 0.16f, cellHeight))
                }
                else -> {
                    paint.color = fgInt
                    drawRect(Color(paint.color), Offset(cellX(cx), cellY(cy)), Size(cellWidth, cellHeight))
                    if (cell != null && cell.symbol.isNotEmpty()) {
                        paint.color = bgColor(cell)
                        canvas.drawText(cell.symbol, cellX(cx), cellY(cy) + baseline, paint)
                    }
                }
            }
        }
    }
}
