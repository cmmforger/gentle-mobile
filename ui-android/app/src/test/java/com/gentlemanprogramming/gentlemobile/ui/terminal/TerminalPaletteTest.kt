package com.gentlemanprogramming.gentlemobile.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalPaletteTest {

    @Test
    fun paper_terminal_keeps_the_dark_palette_identical_to_cute() {
        val cute = TerminalPalette.forTheme(com.gentlemanprogramming.gentlemobile.ui.theme.AppTheme.CUTE)
        val paper = TerminalPalette.forTheme(com.gentlemanprogramming.gentlemobile.ui.theme.AppTheme.PAPER)
        assertEquals(cute.colors, paper.colors)
        assertEquals(cute.background, paper.background)
        assertEquals(cute.foreground, paper.foreground)
    }

    @Test
    fun every_theme_has_sixteen_colours_and_a_distinct_background() {
        for (theme in com.gentlemanprogramming.gentlemobile.ui.theme.AppTheme.entries) {
            val palette = TerminalPalette.forTheme(theme)
            assertEquals("$theme has 16 colours", 16, palette.colors.size)
        }
        val gentle = TerminalPalette.forTheme(com.gentlemanprogramming.gentlemobile.ui.theme.AppTheme.GENTLE)
        val cute = TerminalPalette.forTheme(com.gentlemanprogramming.gentlemobile.ui.theme.AppTheme.CUTE)
        assertNotEquals(cute.background, gentle.background)
    }

    @Test
    fun base_colours_come_from_the_captured_herdr_tokens() {
        val cute = TerminalPalette.cute
        assertEquals(TerminalPalette.hex("#100a0f"), cute.background)
        assertEquals(TerminalPalette.hex("#f6eff3"), cute.foreground)
        assertEquals(TerminalPalette.hex("#ff718f"), cute.colors[1])
        assertEquals(TerminalPalette.hex("#b4e7c7"), cute.colors[2])
        assertEquals(TerminalPalette.hex("#e0c27a"), cute.colors[3])
        assertEquals(TerminalPalette.hex("#a9c7ee"), cute.colors[6])
    }

    @Test
    fun lighten_moves_a_colour_toward_white() {
        val black = TerminalPalette.hex("#100a0f")
        val lighter = TerminalPalette.lighten(black, 0.30f)
        assertNotEquals(black, lighter)
        val r = (lighter ushr 16) and 0xFF
        assertTrue("lightened red channel is brighter", r > 0x10)
    }

    @Test
    fun bright_black_is_the_dim_surface_not_a_lightened_base() {
        assertEquals(TerminalPalette.hex("#563040"), TerminalPalette.cute.colors[8])
    }
}
