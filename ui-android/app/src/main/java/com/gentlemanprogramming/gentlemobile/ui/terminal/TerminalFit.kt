package com.gentlemanprogramming.gentlemobile.ui.terminal

/**
 * The fit rule from the third phone test (S27-1): the whole frame must be
 * visible on the card — width first, and if the height then overflows the
 * visible card, the height fits too (fit both, like Moshi). Nothing is
 * cropped and no horizontal scrolling is needed to read a line.
 *
 * One font size drives both dimensions, so the cell grid's aspect is kept
 * by construction: cell width is `fontPx * advanceRatio` and cell height is
 * `fontPx * lineHeightRatio` — the same ratios the renderer measures from
 * the bundled font ([rememberTerminalCellMetrics]), which is what makes
 * the fit arithmetic and the drawn grid agree exactly.
 *
 * Pure arithmetic so the rule is unit-tested without a UI runtime
 * (TerminalFitTest).
 */
object TerminalFit {

    /** The fit for one frame in one card: the font size in pixels, the cell
     *  size it produces, and the frame's own grid size. */
    class Result(
        val cols: Int,
        val rows: Int,
        val fontPx: Float,
        val cellWidthPx: Float,
        val cellHeightPx: Float,
    ) {
        /** The whole grid's on-screen size — the size [TerminalSurface] is
         *  given, so everything the host rendered is visible at once. */
        val gridWidthPx: Float get() = cols * cellWidthPx
        val gridHeightPx: Float get() = rows * cellHeightPx
    }

    /** Never smaller than a pixel: a zero- or negative-sized card (measured
     *  before layout) still yields a drawable, positive font instead of a
     *  crash or an invisible grid. */
    private const val MIN_FONT_PX = 1f

    /** Safety margin, in pixels, kept on each side of the card: the drawn
     *  grid must never touch the card's own edge. Measured defect: 80
     *  columns summed 976 px against a 974 px card — the last column fell
     *  off the edge. Eight pixels: one was not enough, the glyph's
     *  antialiasing still read as cut at the edge. */
    private const val SAFETY_PX = 8f

    /**
     * The font size that fits the whole `cols x rows` frame into the card.
     * Width and height are both honoured: the binding side (the smaller of
     * the two bounds) wins, so a tall frame on a wide card shrinks to the
     * height and a wide frame on a short card shrinks to the width.
     *
     * `paintedAdvanceRatio` — when the caller has MEASURED what the font
     * really paints (a run of `M`s through the text measurer), pass it here:
     * the nominal ratio is a font-file constant, and at small sizes hinting
     * paints a wider advance (measured: 0.6 nominal vs 0.62 painted at 13 px
     * — the header line lost its tail past the card edge). The larger of the
     * two governs, so the painted reality can never overflow the card.
     *
     * The result is floored to whole pixels and computed against the card
     * minus a safety margin per side.
     */
    fun fit(
        cols: Int,
        rows: Int,
        cardWidthPx: Float,
        cardHeightPx: Float,
        advanceRatio: Float,
        lineHeightRatio: Float,
        paintedAdvanceRatio: Float? = null,
    ): Result {
        val c = cols.coerceAtLeast(1)
        val r = rows.coerceAtLeast(1)
        val effectiveAdvance = maxOf(advanceRatio, paintedAdvanceRatio ?: advanceRatio)
        val usableWidth = (cardWidthPx - 2f * SAFETY_PX).coerceAtLeast(0f)
        val usableHeight = (cardHeightPx - 2f * SAFETY_PX).coerceAtLeast(0f)
        val widthBound = if (usableWidth > 0f) usableWidth / (c * effectiveAdvance) else Float.MAX_VALUE
        val heightBound = if (usableHeight > 0f) usableHeight / (r * lineHeightRatio) else Float.MAX_VALUE
        val fontPx = kotlin.math.floor(minOf(widthBound, heightBound)).coerceAtLeast(MIN_FONT_PX)
        return Result(
            cols = c,
            rows = r,
            fontPx = fontPx,
            cellWidthPx = fontPx * effectiveAdvance,
            cellHeightPx = fontPx * lineHeightRatio,
        )
    }
}
