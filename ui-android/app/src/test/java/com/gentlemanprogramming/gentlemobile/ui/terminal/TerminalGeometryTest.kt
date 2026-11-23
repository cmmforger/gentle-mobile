package com.gentlemanprogramming.gentlemobile.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalGeometryTest {

    @Test
    fun derives_columns_and_rows_from_the_available_area() {
        val grid = TerminalGeometry.derive(800f, 600f, 16f, 24f)
        assertEquals(50, grid.cols)
        assertEquals(25, grid.rows)
    }

    @Test
    fun clamps_to_a_minimum_grid() {
        val grid = TerminalGeometry.derive(10f, 10f, 16f, 24f)
        assertEquals(TerminalGeometry.MIN_COLS, grid.cols)
        assertEquals(TerminalGeometry.MIN_ROWS, grid.rows)
    }

    @Test
    fun the_keyboard_that_opens_changes_rows_not_the_decision() {
        // Same width, less height (keyboard up): rows shrink, cols hold.
        val decision = TerminalGeometry.resizeDecision(800f, 300f, 16f, 24f, 50, 25)
        assertEquals(TerminalGeometry.Decision.Resize(50, 12), decision)
    }

    @Test
    fun an_unchanged_grid_is_no_change() {
        assertEquals(
            TerminalGeometry.Decision.NoChange,
            TerminalGeometry.resizeDecision(800f, 600f, 16f, 24f, 50, 25),
        )
    }

    @Test
    fun closing_the_keyboard_returns_the_grid() {
        val decision = TerminalGeometry.resizeDecision(800f, 600f, 16f, 24f, 50, 12)
        assertEquals(TerminalGeometry.Decision.Resize(50, 25), decision)
    }
}
