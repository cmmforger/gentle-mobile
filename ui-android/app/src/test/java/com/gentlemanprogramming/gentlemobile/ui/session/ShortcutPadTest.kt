package com.gentlemanprogramming.gentlemobile.ui.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shortcut pad (T52): one source of truth for the terminal key row. The
 * defaults keep today's behaviour, order and visibility are plain list
 * operations, every action encodes to the exact bytes, and "nothing shown"
 * (all keys hidden, or only the joystick) is a valid state.
 */
class ShortcutPadTest {

    @Test
    fun `defaults match today's row with the joystick off`() {
        val pad = ShortcutPad.DEFAULT
        assertEquals(
            listOf("esc", "tab", "ctrl", "alt", "up", "down"),
            pad.visibleKeys.map { it.id },
        )
        assertFalse(pad.joystickVisible)
        assertTrue(pad.visibleKeys.all { it.visible })
    }

    @Test
    fun `moving a key changes the pad's order`() {
        val moved = ShortcutPad.DEFAULT.moveKey("tab", 0)
        assertEquals(
            listOf("tab", "esc", "ctrl", "alt", "up", "down"),
            moved.visibleKeys.map { it.id },
        )
    }

    @Test
    fun `hiding a key keeps its place in the list but removes it from the pad`() {
        val hidden = ShortcutPad.DEFAULT.setKeyVisible("ctrl", false)
        assertEquals(
            listOf("esc", "tab", "alt", "up", "down"),
            hidden.visibleKeys.map { it.id },
        )
        // The config still holds all six, in their stored order, so a
        // re-enabled key returns to its own spot rather than the end.
        assertEquals(
            listOf("esc", "tab", "ctrl", "alt", "up", "down"),
            hidden.keys.map { it.id },
        )
    }

    @Test
    fun `a control byte action sends exactly that byte`() {
        val ctrlC = ShortcutKey("k1", "Ctrl+C", ShortcutAction.ControlByte(0x03))
        val ctrlV = ShortcutKey("k2", "Ctrl+V", ShortcutAction.ControlByte(0x16))
        assertArrayEquals(byteArrayOf(0x03), ctrlC.bytes(false, false))
        assertArrayEquals(byteArrayOf(0x16), ctrlV.bytes(false, false))
    }

    @Test
    fun `text followed by enter appends a carriage return`() {
        val key = ShortcutKey("k1", "hola", ShortcutAction.Text("echo hola", true))
        assertArrayEquals("echo hola\r".toByteArray(Charsets.UTF_8), key.bytes(false, false))
    }

    @Test
    fun `text without enter sends the text alone`() {
        val key = ShortcutKey("k1", "hi", ShortcutAction.Text("echo hi", false))
        assertArrayEquals("echo hi".toByteArray(Charsets.UTF_8), key.bytes(false, false))
    }

    @Test
    fun `a named key action encodes through the key table`() {
        assertArrayEquals(
            byteArrayOf(0x0D),
            ShortcutKey("k1", "Enter", ShortcutAction.Key("enter")).bytes(false, false),
        )
        assertArrayEquals(
            "\u001B[D".toByteArray(Charsets.UTF_8),
            ShortcutKey("k1", "", ShortcutAction.Key("left")).bytes(false, false),
        )
        assertArrayEquals(
            "\u001B[C".toByteArray(Charsets.UTF_8),
            ShortcutKey("k1", "", ShortcutAction.Key("right")).bytes(false, false),
        )
    }

    @Test
    fun `all hidden is a valid state with nothing shown`() {
        var pad = ShortcutPad.DEFAULT
        pad = pad.keys.fold(pad) { acc, key -> acc.setKeyVisible(key.id, false) }
        assertTrue(pad.visibleKeys.isEmpty())
        assertFalse(pad.joystickVisible)
    }

    @Test
    fun `only the joystick is a valid state`() {
        var pad = ShortcutPad.DEFAULT
        pad = pad.keys.fold(pad) { acc, key -> acc.setKeyVisible(key.id, false) }
        pad = pad.withJoystick(true)
        assertTrue(pad.visibleKeys.isEmpty())
        assertTrue(pad.joystickVisible)
    }

    @Test
    fun `only ctrl and alt are sticky keys`() {
        assertTrue(ShortcutPad.DEFAULT.isSticky(ShortcutPad.DEFAULT.keys[2]))
        assertTrue(ShortcutPad.DEFAULT.isSticky(ShortcutPad.DEFAULT.keys[3]))
        assertFalse(ShortcutPad.DEFAULT.isSticky(ShortcutPad.DEFAULT.keys[0]))
        assertFalse(ShortcutPad.DEFAULT.isSticky(ShortcutKey("k", "x", ShortcutAction.ControlByte(0x03))))
    }

    @Test
    fun `swapping two visible neighbours reorders the visible pad`() {
        val swapped = ShortcutPad.DEFAULT.swapWith("tab", "esc")
        assertEquals(
            listOf("tab", "esc", "ctrl", "alt", "up", "down"),
            swapped.visibleKeys.map { it.id },
        )
        assertEquals(ShortcutPad.DEFAULT.keys.size, swapped.keys.size)
    }
}