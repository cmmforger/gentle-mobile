package com.gentlemanprogramming.gentlemobile.ui.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The key row's label decision (T39): no chip carries a word the row can
 *  truncate, the sticky toggles and the momentary keys keep their kinds. */
class ExtraKeyRowModelTest {

    @Test
    fun `the row is the seven keys in the board's order`() {
        assertEquals(
            listOf("esc", "tab", "ctrl", "alt", "up", "down"),
            ExtraKeyRowModel.chips().map { it.id },
        )
    }

    @Test
    fun `ctrl and alt are the only sticky chips`() {
        val sticky = ExtraKeyRowModel.chips().filter { it.kind == ExtraKeyRowModel.Kind.STICKY }
        assertEquals(listOf("ctrl", "alt"), sticky.map { it.id })
    }

    @Test
    fun `no label is a truncated word`() {
        ExtraKeyRowModel.chips().forEach { chip ->
            val isGlyph = chip.label in setOf("↑", "↓")
            if (!isGlyph) {
                assertTrue(
                    "label of ${chip.id} must be a word of at least two characters, was '${chip.label}'",
                    chip.label.length >= 2,
                )
            }
        }
    }

    @Test
    fun `enter has no chip, the phone keyboard already has one`() {
        assertTrue(ExtraKeyRowModel.chips().none { it.id == "enter" })
    }

    @Test
    fun `labels stay short enough for a one-row phone`() {
        ExtraKeyRowModel.chips().forEach { chip ->
            assertTrue("label '${chip.label}' is too long for one row", chip.label.length <= 4)
        }
    }
}
