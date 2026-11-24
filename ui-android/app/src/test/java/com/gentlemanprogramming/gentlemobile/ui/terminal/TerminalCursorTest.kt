package com.gentlemanprogramming.gentlemobile.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T50 (Advanced → Cursor): the cursor's shape and blink are pure rules —
 *  a fraction of the measured cell, never a fixed pixel, and a 50/50 duty
 *  cycle when blink is on. The renderer scales the fraction by the cell it
 *  measured, so a font change can never break the cursor. */
class TerminalCursorTest {

    @Test
    fun block_fills_the_whole_cell() {
        val rect = TerminalCursor.rect(CursorMode.BLOCK)
        assertEquals(0f, rect.left, 0f)
        assertEquals(0f, rect.top, 0f)
        assertEquals(1f, rect.right, 0f)
        assertEquals(1f, rect.bottom, 0f)
    }

    @Test
    fun underline_is_the_bottom_stripe() {
        val rect = TerminalCursor.rect(CursorMode.UNDERLINE)
        assertEquals(0f, rect.left, 0f)
        assertEquals(1f, rect.right, 0f)
        assertEquals(1f, rect.bottom, 0f)
        assertTrue("the stripe sits at the bottom", rect.top > 0f && rect.top < rect.bottom)
    }

    @Test
    fun bar_is_the_leading_stripe() {
        val rect = TerminalCursor.rect(CursorMode.BAR)
        assertEquals(0f, rect.top, 0f)
        assertEquals(1f, rect.bottom, 0f)
        assertTrue("the stripe sits at the left", rect.right > 0f && rect.right < 1f)
    }

    @Test
    fun a_hidden_cursor_never_paints_regardless_of_blink() {
        assertFalse(TerminalCursor.visible(cursorOn = false, blink = false, elapsedMs = 0))
        assertFalse(TerminalCursor.visible(cursorOn = false, blink = true, elapsedMs = 0))
        assertFalse(TerminalCursor.visible(cursorOn = false, blink = true, elapsedMs = 530))
    }

    @Test
    fun blink_off_means_always_solid() {
        assertTrue(TerminalCursor.visible(cursorOn = true, blink = false, elapsedMs = 0))
        assertTrue(TerminalCursor.visible(cursorOn = true, blink = false, elapsedMs = 999_999))
    }

    @Test
    fun blink_on_is_a_50_50_duty_cycle() {
        assertTrue(TerminalCursor.visible(cursorOn = true, blink = true, elapsedMs = 0))
        assertTrue(TerminalCursor.visible(cursorOn = true, blink = true, elapsedMs = 529))
        assertFalse(TerminalCursor.visible(cursorOn = true, blink = true, elapsedMs = 530))
        assertFalse(TerminalCursor.visible(cursorOn = true, blink = true, elapsedMs = 1059))
        assertTrue(TerminalCursor.visible(cursorOn = true, blink = true, elapsedMs = 1060))
    }
}
