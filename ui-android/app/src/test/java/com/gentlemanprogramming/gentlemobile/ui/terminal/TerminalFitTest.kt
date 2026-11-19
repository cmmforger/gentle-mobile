package com.gentlemanprogramming.gentlemobile.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fit rule from the third phone test (S27-1): the WHOLE 80x24 frame
 * must be visible on the card — width first, and if the height then
 * overflows the visible card, the height fits too. One font size drives
 * both dimensions, so the cell grid's aspect is kept by construction and
 * box-drawing characters still line up. Nothing may be cropped and no
 * horizontal scrolling may be needed to read a line.
 *
 * Pure arithmetic over the bundled font's measured ratios (the same
 * `rememberTerminalCellMetrics` measurement the renderer slots cells
 * with), so a UI runtime is never needed to pin it.
 */
class TerminalFitTest {

    // The bundled JetBrains Mono's measured ratios (documented in
    // TerminalGrid.kt): 0.6em advance, ~1.17em line span. The tests use
    // round values; the fit rule must not depend on the exact decimals.
    private val advance = 0.6f
    private val lineHeight = 1.2f

    /** A Pixel 6-sized terminal card in pixels (1080 px wide minus the
     *  card's hair of padding; the height is whatever the composer leaves). */
    private val pixel6CardWidth = 1052f
    private val pixel6CardHeight = 1400f

    // --- width first (S27-1) ---------------------------------------------------

    @Test
    fun `an 80x24 frame on a Pixel 6 card is bound by the width`() {
        val fit = TerminalFit.fit(80, 24, pixel6CardWidth, pixel6CardHeight, advance, lineHeight)
        val widthBound = pixel6CardWidth / (80 * advance)
        val heightBound = pixel6CardHeight / (24 * lineHeight)
        assertEquals(widthBound, fit.fontPx, 0.001f)
        assertTrue("width bound ($widthBound) < height bound ($heightBound)", widthBound < heightBound)
    }

    @Test
    fun `the resulting cell size on a Pixel 6 keeps the whole 80x24 frame visible`() {
        val fit = TerminalFit.fit(80, 24, pixel6CardWidth, pixel6CardHeight, advance, lineHeight)
        assertTrue("all 80 columns must fit: ${fit.gridWidthPx} <= $pixel6CardWidth",
            fit.gridWidthPx <= pixel6CardWidth + 0.01f)
        assertTrue("all 24 rows must fit: ${fit.gridHeightPx} <= $pixel6CardHeight",
            fit.gridHeightPx <= pixel6CardHeight + 0.01f)
    }

    // --- then the height (S27-1: fit both, like Moshi) --------------------------

    @Test
    fun `a frame taller than the card is bound by the height, not cropped`() {
        // 60x80 on a card where the height is the tight side.
        val fit = TerminalFit.fit(60, 80, 600f, 900f, advance, lineHeight)
        val widthBound = 600f / (60 * advance)
        val heightBound = 900f / (80 * lineHeight)
        assertTrue("height must be the binding side", heightBound < widthBound)
        assertEquals(heightBound, fit.fontPx, 0.001f)
        assertTrue(fit.gridWidthPx <= 600f + 0.01f)
        assertTrue(fit.gridHeightPx <= 900f + 0.01f)
    }

    // --- aspect kept (S27-1: box-drawing characters still line up) --------------

    @Test
    fun `one font size drives both dimensions so the cell aspect never changes`() {
        val fit = TerminalFit.fit(80, 24, pixel6CardWidth, pixel6CardHeight, advance, lineHeight)
        assertEquals(fit.fontPx, fit.cellWidthPx / advance, 0.001f)
        assertEquals(fit.fontPx, fit.cellHeightPx / lineHeight, 0.001f)
    }

    // --- never cropped, in any shape of frame and card ---------------------------

    @Test
    fun `no frame shape ever overflows its card`() {
        val cards = listOf(300f to 500f, 1080f to 600f, 200f to 2000f)
        val frames = listOf(80 to 24, 120 to 40, 40 to 60, 200 to 50)
        for ((w, h) in cards) {
            for ((c, r) in frames) {
                val fit = TerminalFit.fit(c, r, w, h, advance, lineHeight)
                assertTrue(
                    "$c x $r in ${w.toInt()}x${h.toInt()}: width ${fit.gridWidthPx}",
                    fit.gridWidthPx <= w + 0.01f,
                )
                assertTrue(
                    "$c x $r in ${w.toInt()}x${h.toInt()}: height ${fit.gridHeightPx}",
                    fit.gridHeightPx <= h + 0.01f,
                )
            }
        }
    }

    // --- degenerate inputs never crash or crop ----------------------------------

    @Test
    fun `an unmeasured card still returns a positive font, never a crop or a crash`() {
        val fit = TerminalFit.fit(80, 24, 0f, 0f, advance, lineHeight)
        assertTrue(fit.fontPx > 0f)
    }

    @Test
    fun `a one-cell frame is still a frame`() {
        val fit = TerminalFit.fit(0, 0, pixel6CardWidth, pixel6CardHeight, advance, lineHeight)
        assertEquals(1, fit.cols)
        assertEquals(1, fit.rows)
        assertTrue(fit.gridWidthPx <= pixel6CardWidth + 0.01f)
        assertTrue(fit.gridHeightPx <= pixel6CardHeight + 0.01f)
    }
}
