package com.gentlemanprogramming.gentlemobile.ui.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T57 (Advanced → Cursor picker): the picker's preview follows the blink
 *  switch — on, the shape blinks on the same 50/50 duty cycle the terminal
 *  cursor uses; off, it is steady. The preview never blinks when the switch
 *  the human toggled is off, which is what made the switch look broken. */
class CursorPreviewTest {

    @Test
    fun `with blink off the preview is steady`() {
        assertTrue(CursorPreview.visible(blink = false, elapsedMs = 0))
        assertTrue(CursorPreview.visible(blink = false, elapsedMs = 529))
        assertTrue(CursorPreview.visible(blink = false, elapsedMs = 530))
        assertTrue(CursorPreview.visible(blink = false, elapsedMs = 999_999))
    }

    @Test
    fun `with blink on the preview follows the cursor duty cycle`() {
        assertTrue(CursorPreview.visible(blink = true, elapsedMs = 0))
        assertTrue(CursorPreview.visible(blink = true, elapsedMs = 529))
        assertFalse(CursorPreview.visible(blink = true, elapsedMs = 530))
        assertFalse(CursorPreview.visible(blink = true, elapsedMs = 1059))
        assertTrue(CursorPreview.visible(blink = true, elapsedMs = 1060))
    }

    @Test
    fun `blink follows a custom period`() {
        assertTrue(CursorPreview.visible(blink = true, elapsedMs = 0, periodMs = 1000))
        assertFalse(CursorPreview.visible(blink = true, elapsedMs = 1000, periodMs = 1000))
        assertTrue(CursorPreview.visible(blink = true, elapsedMs = 2000, periodMs = 1000))
        assertTrue(CursorPreview.visible(blink = false, elapsedMs = 1500, periodMs = 1000))
    }
}
