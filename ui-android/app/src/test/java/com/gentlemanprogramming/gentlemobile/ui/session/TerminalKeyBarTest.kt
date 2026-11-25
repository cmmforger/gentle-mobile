package com.gentlemanprogramming.gentlemobile.ui.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T59 (the key bar): the bar's eight items in order, the pad's fixed cross,
 *  the attachment sheet's three rows, and the mode transitions — pure, pinned
 *  here, never read from the composable. */
class TerminalKeyBarTest {

    @Test
    fun `the bar shows Ctrl Esc Tab then the five icons in order`() {
        assertEquals(
            listOf(
                TerminalKeyBar.Item.CTRL,
                TerminalKeyBar.Item.ESC,
                TerminalKeyBar.Item.TAB,
                TerminalKeyBar.Item.ARROWS,
                TerminalKeyBar.Item.LINES,
                TerminalKeyBar.Item.CLIPBOARD,
                TerminalKeyBar.Item.BUBBLE,
                TerminalKeyBar.Item.KEYBOARD,
            ),
            TerminalKeyBar.barOrder(),
        )
    }

    @Test
    fun `the bar's quick chips are Ctrl Esc Tab`() {
        val chips = TerminalKeyBar.barChips()
        assertEquals(listOf("Ctrl", "Esc", "Tab"), chips.map { it.label })
        assertEquals(
            listOf("ctrl", "esc", "tab"),
            chips.map { (it.action as ShortcutAction.Key).name },
        )
    }

    @Test
    fun `the pad's cross is backspace up clear left Enter right down`() {
        val keys = TerminalKeyBar.padKeys()
        assertEquals(
            listOf(
                "pad_backspace",
                "pad_up",
                "pad_clear",
                "pad_left",
                "pad_enter",
                "pad_right",
                "pad_down",
            ),
            keys.map { it.id },
        )
        assertEquals(listOf("\u232B", "\u2191", "\u2327", "\u2190", "\u21B5", "\u2192", "\u2193"), keys.map { it.label })
    }

    @Test
    fun `backspace and clear are control bytes, the rest are named keys`() {
        val keys = TerminalKeyBar.padKeys().associateBy { it.id }
        assertEquals(ShortcutAction.ControlByte(0x7F), keys["pad_backspace"]?.action)
        assertEquals(ShortcutAction.ControlByte(0x0C), keys["pad_clear"]?.action)
        assertEquals(ShortcutAction.Key("up"), keys["pad_up"]?.action)
        assertEquals(ShortcutAction.Key("left"), keys["pad_left"]?.action)
        assertEquals(ShortcutAction.Key("enter"), keys["pad_enter"]?.action)
        assertEquals(ShortcutAction.Key("right"), keys["pad_right"]?.action)
        assertEquals(ShortcutAction.Key("down"), keys["pad_down"]?.action)
    }

    @Test
    fun `the inline row is the shortcut pad's own keys in their order`() {
        val pad = ShortcutPad(
            keys = listOf(
                ShortcutKey("esc", "Esc", ShortcutAction.Key("esc")),
                ShortcutKey("hidden", "Hidden", ShortcutAction.Text("x", false), visible = false),
                ShortcutKey("tab", "Tab", ShortcutAction.Key("tab")),
                ShortcutKey("up", "\u2191", ShortcutAction.Key("up")),
            ),
        )
        assertEquals(listOf("esc", "tab", "up"), TerminalKeyBar.inlineKeys(pad).map { it.id })
    }

    @Test
    fun `the attachment sheet has camera, gallery and file in that order`() {
        assertEquals(
            listOf(
                TerminalKeyBar.AttachmentKind.CAMERA,
                TerminalKeyBar.AttachmentKind.GALLERY,
                TerminalKeyBar.AttachmentKind.FILE,
            ),
            TerminalKeyBar.attachmentRows(),
        )
    }

    @Test
    fun `the composer field shows only in composer mode`() {
        assertTrue(TerminalKeyBar.showsComposerField(InputMode.COMPOSER))
        assertFalse(TerminalKeyBar.showsComposerField(InputMode.DIRECT))
    }
}

/** T59: the mode transitions and the pad's lock/drag state. */
class KeyBarStateTest {

    @Test
    fun `the surfaces shown for each state follow the three toggles`() {
        assertEquals(
            setOf(KeyBarState.Surface.FIELD, KeyBarState.Surface.PAD, KeyBarState.Surface.INLINE),
            KeyBarState(useComposer = true, padOpen = true, linesOpen = true).visibleSurfaces(),
        )
        assertEquals(
            setOf(KeyBarState.Surface.FIELD),
            KeyBarState(useComposer = true).visibleSurfaces(),
        )
        assertEquals(
            setOf(KeyBarState.Surface.PAD),
            KeyBarState(padOpen = true).visibleSurfaces(),
        )
        assertEquals(
            setOf(KeyBarState.Surface.INLINE),
            KeyBarState(linesOpen = true).visibleSurfaces(),
        )
        assertTrue(KeyBarState().visibleSurfaces().isEmpty())
    }

    @Test
    fun `the bubble toggles the field`() {
        assertTrue(KeyBarState(useComposer = false).tapBubble().useComposer)
        assertFalse(KeyBarState(useComposer = true).tapBubble().useComposer)
    }

    @Test
    fun `the keyboard closes the field for direct typing and leaves the pad alone`() {
        val after = KeyBarState(useComposer = true, padOpen = true).tapKeyboard()
        assertFalse(after.useComposer)
        assertTrue(after.padOpen)
    }

    @Test
    fun `the arrows toggle the floating pad`() {
        assertTrue(KeyBarState().tapArrows().padOpen)
        assertFalse(KeyBarState(padOpen = true).tapArrows().padOpen)
    }

    @Test
    fun `the lines toggle the inline row`() {
        assertTrue(KeyBarState().tapLines().linesOpen)
        assertFalse(KeyBarState(linesOpen = true).tapLines().linesOpen)
    }

    @Test
    fun `the lock toggle flips the pad between locked and unlocked`() {
        assertTrue(KeyBarState().tapLock().padLocked)
        assertFalse(KeyBarState(padLocked = true).tapLock().padLocked)
    }

    @Test
    fun `dragging the pad away dismisses it only when unlocked`() {
        assertFalse(KeyBarState(padOpen = true, padLocked = false).dragAway().padOpen)
        assertTrue(KeyBarState(padOpen = true, padLocked = true).dragAway().padOpen)
    }
}
