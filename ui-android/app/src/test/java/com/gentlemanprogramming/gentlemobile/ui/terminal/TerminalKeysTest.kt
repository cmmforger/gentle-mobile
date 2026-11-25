package com.gentlemanprogramming.gentlemobile.ui.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
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
    fun the_composer_sends_deltas_not_cumulative_text() {
        val composer = TerminalComposer()
        assertArrayEquals("e".toByteArray(), composer.commit("e"))
        assertArrayEquals("c".toByteArray(), composer.commit("ec"))
        assertArrayEquals("h".toByteArray(), composer.commit("ech"))
        assertArrayEquals("o".toByteArray(), composer.commit("echo"))
    }

    @Test
    fun submit_sends_one_cr_and_clears_the_composer() {
        val composer = TerminalComposer()
        composer.commit("echo")
        assertArrayEquals(byteArrayOf(0x0D), composer.submit())
        assertArrayEquals("x".toByteArray(), composer.commit("x"))
    }

    @Test
    fun forward_input_sends_append_delete_and_enter() {
        assertArrayEquals("a".toByteArray(), TerminalKeys.forwardInput("", "a"))
        assertArrayEquals(byteArrayOf(0x7F), TerminalKeys.forwardInput("ab", "a"))
        assertArrayEquals(byteArrayOf(0x0D), TerminalKeys.forwardInput("a", "a\n"))
        assertArrayEquals(byteArrayOf(0x7F, 0x7F) + "x".toByteArray(), TerminalKeys.forwardInput("ab", "x"))
    }

    @Test
    fun a_deletion_in_direct_mode_sends_the_delete_byte_not_backspace() {
        // T60: backspace held on the phone keyboard must erase on the host,
        // and the PTY's erase character is DEL (0x7F), never 0x08.
        assertArrayEquals(byteArrayOf(0x7F), TerminalKeys.forwardInput("echo", "ech"))
        assertArrayEquals(byteArrayOf(0x7F, 0x7F, 0x7F), TerminalKeys.forwardInput("abc", ""))
    }

    @Test
    fun forward_input_of_the_same_value_sends_nothing() {
        assertArrayEquals(ByteArray(0), TerminalKeys.forwardInput("ls", "ls"))
    }

    @Test
    fun typing_ls_then_enter_sends_exactly_one_command() {
        val composer = TerminalComposer()
        assertArrayEquals("l".toByteArray(), composer.commit("l"))
        assertArrayEquals("s".toByteArray(), composer.commit("ls"))
        assertArrayEquals(byteArrayOf(0x0D), composer.submit())
        // The cleared field, delivered again, sends nothing.
        assertArrayEquals(ByteArray(0), composer.commit(""))
    }

    @Test
    fun a_committed_string_is_never_sent_again() {
        val composer = TerminalComposer()
        composer.commit("ls")
        assertArrayEquals(ByteArray(0), composer.commit("ls"))
    }

    @Test
    fun sticky_ctrl_maps_the_next_typed_character_and_disarms() {
        val composer = TerminalComposer()
        composer.toggleCtrl()
        assertTrue(composer.ctrlSticky)
        assertArrayEquals(byteArrayOf(0x03), composer.commit("c"))
        assertFalse(composer.ctrlSticky)
        // A control character is not text: the field does not mirror it.
        assertTrue(composer.fieldText.isEmpty())
        // The next character is ordinary again, from an empty field.
        assertArrayEquals("d".toByteArray(), composer.commit("d"))
    }

    @Test
    fun sticky_alt_prefixes_esc_and_disarms() {
        val composer = TerminalComposer()
        composer.toggleAlt()
        assertTrue(composer.altSticky)
        assertArrayEquals(byteArrayOf(0x1B) + "x".toByteArray(), composer.commit("x"))
        assertFalse(composer.altSticky)
        assertTrue(composer.fieldText.isEmpty())
    }

    @Test
    fun ctrl_on_an_unmapped_character_sends_it_unchanged() {
        val composer = TerminalComposer()
        composer.toggleCtrl()
        assertArrayEquals("1".toByteArray(), composer.commit("1"))
        assertFalse(composer.ctrlSticky)
        assertTrue(composer.fieldText.isEmpty())
    }

    @Test
    fun arming_one_modifier_disarms_the_other() {
        val composer = TerminalComposer()
        composer.toggleCtrl()
        composer.toggleAlt()
        assertTrue(composer.altSticky)
        assertFalse(composer.ctrlSticky)
        composer.toggleCtrl()
        assertTrue(composer.ctrlSticky)
        assertFalse(composer.altSticky)
    }

    @Test
    fun typed_control_letters_map_to_their_control_byte() {
        assertArrayEquals(byteArrayOf(0x01), TerminalKeys.typedCharBytes('a'.code, true, false))
        assertArrayEquals(byteArrayOf(0x1A), TerminalKeys.typedCharBytes('z'.code, true, false))
        assertArrayEquals(byteArrayOf(0x01), TerminalKeys.typedCharBytes('A'.code, true, false))
        assertArrayEquals(byteArrayOf(0x1B), TerminalKeys.typedCharBytes('['.code, true, false))
        assertArrayEquals(byteArrayOf(0x00), TerminalKeys.typedCharBytes(' '.code, true, false))
        assertArrayEquals(byteArrayOf(0x7F), TerminalKeys.typedCharBytes('?'.code, true, false))
    }

    @Test
    fun typed_alt_prefixes_esc() {
        assertArrayEquals(byteArrayOf(0x1B) + "c".toByteArray(), TerminalKeys.typedCharBytes('c'.code, false, true))
    }
}
