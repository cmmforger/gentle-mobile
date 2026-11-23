package com.gentlemanprogramming.gentlemobile.ui.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalKeysTest {

    @Test
    fun esc_and_tab_are_the_control_bytes() {
        assertArrayEquals(byteArrayOf(0x1B), TerminalKeys.bytesFor("esc", false, false))
        assertArrayEquals(byteArrayOf(0x09), TerminalKeys.bytesFor("tab", false, false))
    }

    @Test
    fun arrows_are_vt_sequences() {
        assertArrayEquals("\u001B[A".toByteArray(), TerminalKeys.bytesFor("up", false, false))
        assertArrayEquals("\u001B[B".toByteArray(), TerminalKeys.bytesFor("down", false, false))
    }

    @Test
    fun ctrl_and_alt_add_the_modifier() {
        assertArrayEquals("\u001B[1;5A".toByteArray(), TerminalKeys.bytesFor("up", true, false))
        assertArrayEquals("\u001B[1;3B".toByteArray(), TerminalKeys.bytesFor("down", false, true))
    }

    @Test
    fun an_unknown_key_sends_nothing() {
        assertTrue(TerminalKeys.bytesFor("nope", false, false).isEmpty())
    }

    @Test
    fun forward_input_sends_append_delete_and_enter() {
        assertArrayEquals("a".toByteArray(), TerminalKeys.forwardInput("", "a"))
        assertArrayEquals(byteArrayOf(0x08), TerminalKeys.forwardInput("ab", "a"))
        assertArrayEquals(byteArrayOf(0x0D), TerminalKeys.forwardInput("a", "a\n"))
        assertArrayEquals(byteArrayOf(0x08, 0x08) + "x".toByteArray(), TerminalKeys.forwardInput("ab", "x"))
    }
}
