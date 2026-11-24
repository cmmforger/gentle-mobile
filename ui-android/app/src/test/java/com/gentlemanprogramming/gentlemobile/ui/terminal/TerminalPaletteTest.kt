package com.gentlemanprogramming.gentlemobile.ui.terminal

import com.gentlemanprogramming.gentlemobile.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalPaletteTest {

    /** WCAG relative luminance of an ARGB int (ignores alpha). */
    private fun luminance(argb: Int): Double {
        fun channel(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel((argb ushr 16) and 0xFF) +
            0.7152 * channel((argb ushr 8) and 0xFF) +
            0.0722 * channel(argb and 0xFF)
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun paper_is_a_light_terminal_and_the_dark_themes_stay_dark() {
        val paper = TerminalPalette.forTheme(AppTheme.PAPER)
        assertTrue("Paper background must be light", luminance(paper.background) > 0.5)
        assertTrue("Paper foreground must be dark", luminance(paper.foreground) < 0.2)
        for (dark in listOf(AppTheme.CUTE, AppTheme.SEXY, AppTheme.GENTLE)) {
            val p = TerminalPalette.forTheme(dark)
            assertTrue("$dark background must be dark", luminance(p.background) < 0.1)
            assertTrue("$dark foreground must be light", luminance(p.foreground) > 0.8)
        }
    }

    @Test
    fun paper_background_and_foreground_come_from_its_own_tokens() {
        val paper = TerminalPalette.forTheme(AppTheme.PAPER)
        assertEquals(TerminalPalette.hex("#ffffff"), paper.background)
        assertEquals(TerminalPalette.hex("#36242c"), paper.foreground)
    }

    @Test
    fun every_theme_has_sixteen_colours() {
        for (theme in AppTheme.entries) {
            assertEquals("$theme has 16 colours", 16, TerminalPalette.forTheme(theme).colors.size)
        }
    }

    @Test
    fun normal_text_contrast_exceeds_4_5_in_every_theme() {
        for (theme in AppTheme.entries) {
            val p = TerminalPalette.forTheme(theme)
            val c = contrast(p.foreground, p.background)
            assertTrue("$theme normal text contrast $c must exceed 4.5", c >= 4.5)
        }
    }

    @Test
    fun paper_sixteen_colours_are_distinct_and_readable_on_its_background() {
        val paper = TerminalPalette.forTheme(AppTheme.PAPER)
        assertEquals("the 16 ANSI colours must be distinct", 16, paper.colors.toSet().size)
        for ((index, colour) in paper.colors.withIndex()) {
            val c = contrast(colour, paper.background)
            assertTrue("Paper colour $index (contrast $c) must be readable on its background", c >= 4.5)
        }
    }

    @Test
    fun dark_themes_keep_their_current_values() {
        val cute = TerminalPalette.forTheme(AppTheme.CUTE)
        assertEquals(TerminalPalette.hex("#100a0f"), cute.background)
        assertEquals(TerminalPalette.hex("#f6eff3"), cute.foreground)
        assertEquals(TerminalPalette.hex("#ff718f"), cute.colors[1])
        assertEquals(TerminalPalette.hex("#b4e7c7"), cute.colors[2])
        assertEquals(TerminalPalette.hex("#e0c27a"), cute.colors[3])
        assertEquals(TerminalPalette.hex("#a9c7ee"), cute.colors[6])
        assertEquals(TerminalPalette.hex("#563040"), cute.colors[8])
        assertEquals(TerminalPalette.hex("#100a0f"), TerminalPalette.sexy.background)
        assertEquals(TerminalPalette.hex("#06080f"), TerminalPalette.gentle.background)
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
