package com.gentlemanprogramming.gentlemobile.ui.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEmulatorTest {

    private fun emulator(cols: Int = 80, rows: Int = 24) =
        TerminalEmulator(cols, rows, TerminalPalette.cute)

    @Test
    fun a_prompt_and_newline_place_text_and_move_the_cursor() {
        val e = emulator()
        e.feed("hi\r\n".toByteArray())
        assertEquals("h", e.screenLines()[0][0].text)
        assertEquals("i", e.screenLines()[0][1].text)
        assertEquals(0, e.cursorX)
        assertEquals(1, e.cursorY)
    }

    @Test
    fun sgr_sets_the_foreground_from_the_palette() {
        val e = emulator()
        e.feed("\u001B[31mred\u001B[0m".toByteArray())
        assertEquals(TerminalPalette.cute.colors[1], e.screenLines()[0][0].fg)
    }

    @Test
    fun bright_colour_maps_to_the_bright_entry() {
        val e = emulator()
        e.feed("\u001B[91mX".toByteArray())
        assertEquals(TerminalPalette.cute.colors[9], e.screenLines()[0][0].fg)
    }

    @Test
    fun truecolour_and_256_colour_resolve_to_argb() {
        val e = emulator()
        e.feed("\u001B[38;2;255;0;0mA\u001B[38;5;196mB".toByteArray())
        assertEquals(0xFFFF0000.toInt(), e.screenLines()[0][0].fg)
        assertEquals(0xFFFF0000.toInt(), e.screenLines()[0][1].fg)
    }

    @Test
    fun clear_homes_the_cursor_and_erases() {
        val e = emulator()
        // `clear` sends home + erase-in-display; ED alone does not home.
        e.feed("abc\u001B[H\u001B[2J".toByteArray())
        assertEquals(0, e.cursorX)
        assertEquals(0, e.cursorY)
        assertTrue(e.screenLines().all { line -> line.all { it.text.isEmpty() } })
    }

    @Test
    fun a_wide_character_occupies_two_columns() {
        val e = emulator()
        e.feed("界".toByteArray())
        assertTrue(e.screenLines()[0][0].wide)
        assertTrue(e.screenLines()[0][1].cont)
        assertEquals("界", e.screenLines()[0][0].text)
    }

    @Test
    fun cursor_position_is_one_based() {
        val e = emulator()
        e.feed("\u001B[5;10H".toByteArray())
        assertEquals(9, e.cursorX)
        assertEquals(4, e.cursorY)
    }

    @Test
    fun rows_only_resize_keeps_lines_and_colours() {
        val e = emulator(80, 3)
        e.feed("\u001B[31mone\r\ntwo\r\nthree".toByteArray())
        e.resize(80, 2)
        assertEquals(80, e.cols)
        assertEquals(2, e.rows)
        assertEquals(2, e.screenLines().size)
        // The oldest visible line scrolled into the scrollback, colours intact.
        val back = e.scrollbackLines()
        assertEquals(1, back.size)
        assertEquals("o", back.first()[0].text)
        assertEquals(TerminalPalette.cute.colors[1], back.first()[0].fg)
        assertEquals("t", e.screenLines()[0][0].text)
        assertEquals("t", e.screenLines()[1][0].text)
    }

    @Test
    fun reverse_video_swaps_foreground_and_background() {
        val e = emulator()
        e.feed("\u001B[7mX".toByteArray())
        val cell = e.screenLines()[0][0]
        assertTrue(cell.flags and TermFlags.REVERSED != 0)
    }

    @Test
    fun a_split_multibyte_sequence_is_decoded_across_feeds() {
        val e = emulator()
        val bytes = "界".toByteArray(Charsets.UTF_8)
        e.feed(byteArrayOf(bytes[0]))
        e.feed(byteArrayOf(bytes[1], bytes[2]))
        assertEquals("界", e.screenLines()[0][0].text)
    }

    @Test
    fun tab_advances_to_the_next_eight_column() {
        val e = emulator()
        e.feed("\t".toByteArray())
        assertEquals(8, e.cursorX)
    }

    @Test
    fun a_long_line_wraps_into_scrollback_and_stays_readable() {
        val e = emulator(10, 2)
        e.feed("abcdefghijklmnopqrstuvwxyz".toByteArray())
        // The full text survives across the visible screen and scrollback.
        val all = (e.scrollbackLines() + e.screenLines())
            .flatMap { line -> line.filterNot { it.cont }.map { it.text } }
            .joinToString("")
        assertEquals("abcdefghijklmnopqrstuvwxyz", all)
    }

    @Test
    fun the_cursor_can_be_hidden_and_shown() {
        val e = emulator()
        e.feed("\u001B[?25l".toByteArray())
        assertFalse(e.cursorVisible)
        e.feed("\u001B[?25h".toByteArray())
        assertTrue(e.cursorVisible)
    }

    @Test
    fun a_primary_device_attribute_query_is_answered() {
        val e = emulator()
        e.feed("\u001B[c".toByteArray())
        assertArrayEquals("\u001B[?1;2c".toByteArray(), e.drainReplies())
    }

    @Test
    fun a_secondary_device_attribute_query_is_answered() {
        val e = emulator()
        e.feed("\u001B[>c".toByteArray())
        assertArrayEquals("\u001B[>0;0;0c".toByteArray(), e.drainReplies())
    }

    @Test
    fun a_cursor_position_report_answers_with_the_position() {
        val e = emulator()
        e.feed("abc\u001B[6n".toByteArray())
        assertArrayEquals("\u001B[1;4R".toByteArray(), e.drainReplies())
    }

    @Test
    fun wide_char_width_classification_is_stable() {
        assertEquals(1, terminalCharWidth('a'.code))
        assertEquals(2, terminalCharWidth(0x754C)) // 界
        assertEquals(0, terminalCharWidth(0x0301)) // combining acute
    }
}
