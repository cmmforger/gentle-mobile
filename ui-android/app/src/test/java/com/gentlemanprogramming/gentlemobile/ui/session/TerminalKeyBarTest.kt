package com.gentlemanprogramming.gentlemobile.ui.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T57 (the bottom bar): the bar's quick chips, the keys panel derived from
 *  the pad, and the input-mode mapping are pure — pinned here, never read
 *  from the composable. */
class TerminalKeyBarTest {

    @Test
    fun `the bar shows Ctrl Esc Tab as its fixed chips`() {
        val chips = TerminalKeyBar.barChips()
        assertEquals(listOf("Ctrl", "Esc", "Tab"), chips.map { it.label })
        assertEquals(
            listOf("ctrl", "esc", "tab"),
            chips.map { (it.action as ShortcutAction.Key).name },
        )
    }

    @Test
    fun `the keys panel is the joystick cross then Delete and Clear then the pad's keys`() {
        val panel = TerminalKeyBar.panelKeys(ShortcutPad.DEFAULT)
        assertEquals(listOf("joy_up", "joy_left", "joy_enter", "joy_right", "joy_down"), panel.subList(0, 5).map { it.id })
        assertEquals(listOf("delete", "clear"), panel.subList(5, 7).map { it.id })
        // The default pad's alt survives; esc/tab/ctrl live in the bar and
        // up/down already live in the cross, so they are not repeated.
        assertEquals(listOf("alt"), panel.subList(7, 8).map { it.id })
        assertEquals(8, panel.size)
    }

    @Test
    fun `the pad's keys keep the human's order and skip what the bar or cross already provide`() {
        val pad = ShortcutPad(
            keys = listOf(
                ShortcutKey("up", "\u2191", ShortcutAction.Key("up")),
                ShortcutKey("custom", "Hi", ShortcutAction.Text("hi", sendEnter = false)),
                ShortcutKey("alt", "Alt", ShortcutAction.Key("alt")),
                ShortcutKey("esc", "Esc", ShortcutAction.Key("esc")),
            ),
        )
        assertEquals(listOf("custom", "alt"), TerminalKeyBar.padKeys(pad).map { it.id })
    }

    @Test
    fun `the joystick and extras are always present regardless of the pad`() {
        val emptyPad = ShortcutPad(keys = emptyList())
        val panel = TerminalKeyBar.panelKeys(emptyPad)
        assertEquals(7, panel.size)
        assertEquals(listOf("joy_up", "joy_left", "joy_enter", "joy_right", "joy_down", "delete", "clear"), panel.map { it.id })
    }

    @Test
    fun `use the field is composer mode, off is direct mode`() {
        assertEquals(InputMode.COMPOSER, TerminalKeyBar.inputMode(useComposer = true))
        assertEquals(InputMode.DIRECT, TerminalKeyBar.inputMode(useComposer = false))
        assertTrue(TerminalKeyBar.useComposer(InputMode.COMPOSER))
        assertFalse(TerminalKeyBar.useComposer(InputMode.DIRECT))
    }
}
