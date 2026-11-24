package com.gentlemanprogramming.gentlemobile.ui.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T51 (Advanced → Cursor picker): the picker's preview shows the shape
 *  with motion — it always blinks, on the same 50/50 duty cycle the terminal
 *  cursor uses, independent of the stored blink setting. */
class CursorPreviewTest {

    @Test
    fun `the preview always blinks on the cursor duty cycle`() {
        assertTrue(CursorPreview.blink(0))
        assertTrue(CursorPreview.blink(529))
        assertFalse(CursorPreview.blink(530))
        assertFalse(CursorPreview.blink(1059))
        assertTrue(CursorPreview.blink(1060))
    }

    @Test
    fun `the preview shows motion even with a custom period`() {
        assertTrue(CursorPreview.blink(0, periodMs = 1000))
        assertFalse(CursorPreview.blink(1000, periodMs = 1000))
        assertTrue(CursorPreview.blink(2000, periodMs = 1000))
    }
}
