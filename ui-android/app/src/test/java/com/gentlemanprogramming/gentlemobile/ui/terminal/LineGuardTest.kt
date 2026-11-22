package com.gentlemanprogramming.gentlemobile.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/** The whole-line bottom edge (T39): the card's height holds whole lines
 *  only, and a settled scroll offset lands on a line boundary. */
class LineGuardTest {

    @Test
    fun `the height keeps only the lines that fit whole`() {
        // 150 px of card at a 40 px line: three lines fit, 20 px of a
        // fourth is never allocated.
        assertEquals(120f, LineGuard.wholeLinesHeightPx(150f, 40f), 0.001f)
    }

    @Test
    fun `a height that divides exactly is kept`() {
        assertEquals(120f, LineGuard.wholeLinesHeightPx(120f, 40f), 0.001f)
    }

    @Test
    fun `a degenerate line pitch leaves the height unreduced`() {
        assertEquals(120f, LineGuard.wholeLinesHeightPx(120f, 0f), 0.001f)
        assertEquals(0f, LineGuard.wholeLinesHeightPx(-5f, 40f), 0.001f)
    }

    @Test
    fun `an offset snaps to the nearest line boundary`() {
        // 45 px of drag over a 40 px line rests at 40, not mid-line.
        assertEquals(40f, LineGuard.snapOffsetPx(45f, 40f, 400f), 0.001f)
        // 31 px of drag over a 40 px line rests at 40 — the nearest
        // boundary — not mid-line.
        assertEquals(40f, LineGuard.snapOffsetPx(31f, 40f, 400f), 0.001f)
        // 15 px is nearer to 0 than to 40.
        assertEquals(0f, LineGuard.snapOffsetPx(15f, 40f, 400f), 0.001f)
    }

    @Test
    fun `a snap never passes the scrollable extent`() {
        // Extent 105 (not a whole number of lines): the nearest boundary
        // for 100 raw is 120, clamped back to 105.
        assertEquals(105f, LineGuard.snapOffsetPx(100f, 40f, 105f), 0.001f)
        // A raw offset already past the extent clamps instead of growing.
        assertEquals(105f, LineGuard.snapOffsetPx(300f, 40f, 105f), 0.001f)
    }

    @Test
    fun `a zero extent always rests at the top`() {
        assertEquals(0f, LineGuard.snapOffsetPx(25f, 40f, 0f), 0.001f)
    }

    @Test
    fun `a degenerate line pitch returns the raw value clamped`() {
        assertEquals(25f, LineGuard.snapOffsetPx(25f, 0f, 100f), 0.001f)
        assertEquals(50f, LineGuard.snapOffsetPx(500f, 0f, 50f), 0.001f)
    }
}
