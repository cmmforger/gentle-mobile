package com.gentlemanprogramming.gentlemobile.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The theme engine's contract is `design/system.md`: every palette must resolve
 * to exactly the documented values — nothing invented, nothing approximated.
 * A wrong value here would repaint the whole app off-contract, so each theme's
 * chrome tokens are pinned numerically.
 */
class ThemePaletteTest {

    /** Format a token as #RRGGBB so assertion failures read like the spec. */
    private fun hex(color: Color): String =
        String.format("#%06X", color.toArgb() and 0xFFFFFF)

    private fun assertToken(name: String, expected: String, actual: Color) {
        assertEquals("$name must be $expected (design/system.md)", expected, hex(actual))
    }

    @Test
    fun `cute resolves the brand palette from the gentlemanprogramming site`() {
        val cute = Tokens.cute
        assertToken("bg", "#1A1218", cute.bg)
        assertToken("surface", "#20161E", cute.surface)
        assertToken("surfaceRaised", "#241822", cute.surfaceRaised)
        assertToken("selection", "#342230", cute.selection)
        assertToken("line", "#342230", cute.line)
        assertToken("accent", "#FF76C3", cute.accent)
        assertToken("accentPressed", "#5F1E3C", cute.accentPressed)
        assertToken("accentEdge", "#FF8ACE", cute.accentEdge)
        assertToken("text", "#F6EFF3", cute.text)
        assertToken("muted", "#A78E9B", cute.muted)
        assertToken("textDisabled", "#76616B", cute.textDisabled)
        assertToken("error", "#FF718F", cute.error)
        assertToken("terminal", "#100A0F", cute.terminal)
    }

    @Test
    fun `sexy resolves the captured palette`() {
        val sexy = Tokens.sexy
        assertToken("bg", "#060407", sexy.bg)
        assertToken("surface", "#100A0F", sexy.surface)
        assertToken("surfaceRaised", "#2A1720", sexy.surfaceRaised)
        assertToken("selection", "#2E0F1F", sexy.selection)
        assertToken("line", "#563040", sexy.line)
        assertToken("accent", "#F43888", sexy.accent)
        assertToken("accentPressed", "#BF0F50", sexy.accentPressed)
        assertToken("accentEdge", "#FF4F9A", sexy.accentEdge)
        assertToken("text", "#F6EFF3", sexy.text)
        assertToken("muted", "#A78E9B", sexy.muted)
        assertToken("textDisabled", "#76616B", sexy.textDisabled)
        assertToken("error", "#FF718F", sexy.error)
        assertToken("terminal", "#100A0F", sexy.terminal)
    }

    @Test
    fun `gentle resolves the captured cool palette`() {
        val gentle = Tokens.gentle
        assertToken("bg", "#06080F", gentle.bg)
        assertToken("surface", "#0D0F14", gentle.surface)
        assertToken("surfaceRaised", "#161D2E", gentle.surfaceRaised)
        assertToken("selection", "#232A40", gentle.selection)
        assertToken("line", "#313342", gentle.line)
        assertToken("accent", "#E0C15A", gentle.accent)
        assertToken("accentPressed", "#B5B2D0", gentle.accentPressed)
        assertToken("accentEdge", "#7FB4CA", gentle.accentEdge)
        // The contrast rule: essential secondary text uses `secondary`, not the
        // 3.1:1 `muted #5C6170`, which stays reserved for disabled text.
        assertToken("textSecondary", "#A3B5D6", gentle.muted)
        assertToken("textDisabled", "#5C6170", gentle.textDisabled)
        assertToken("error", "#CB7C94", gentle.error)
        assertToken("terminal", "#06080F", gentle.terminal)
    }

    @Test
    fun `paletteOf maps every theme to its palette`() {
        assertEquals(Tokens.cute, paletteOf(AppTheme.CUTE))
        assertEquals(Tokens.sexy, paletteOf(AppTheme.SEXY))
        assertEquals(Tokens.gentle, paletteOf(AppTheme.GENTLE))
    }

    @Test
    fun `material scheme follows the palette so every screen swaps with it`() {
        for (theme in AppTheme.entries) {
            val palette = paletteOf(theme)
            val scheme = materialScheme(palette)
            assertEquals("background", palette.bg, scheme.background)
            assertEquals("onBackground", palette.text, scheme.onBackground)
            assertEquals("surface", palette.surface, scheme.surface)
            assertEquals("surfaceVariant", palette.surfaceRaised, scheme.surfaceVariant)
            assertEquals("primary", palette.accent, scheme.primary)
            assertEquals("outline", palette.line, scheme.outline)
            assertEquals("error", palette.error, scheme.error)
        }
    }

    @Test
    fun `cute is the documented default theme`() {
        assertEquals(AppTheme.CUTE, AppTheme.default)
    }

    @Test
    fun `paper resolves the designed light palette exactly as the board pins it`() {
        val paper = Tokens.paper
        assertToken("bg", "#FAF5F6", paper.bg)
        assertToken("surface", "#FFFFFF", paper.surface)
        assertToken("surfaceRaised", "#FBEFF4", paper.surfaceRaised)
        assertToken("selection", "#FBE4EF", paper.selection)
        assertToken("line", "#E3CBD6", paper.line)
        assertToken("accent", "#C2417F", paper.accent)
        assertToken("accentPressed", "#A63269", paper.accentPressed)
        assertToken("accentEdge", "#E0479F", paper.accentEdge)
        assertToken("text", "#36242C", paper.text)
        assertToken("muted", "#8A6675", paper.muted)
        assertToken("textDisabled", "#B39CA7", paper.textDisabled)
        assertToken("success", "#2F6B45", paper.success)
        assertToken("warning", "#B26B1F", paper.warning)
        assertToken("error", "#D6365B", paper.error)
        // The terminal is never painted by the chrome: Paper keeps the dark
        // Herdr surface, the same in every theme.
        assertToken("terminal", "#100A0F", paper.terminal)
    }

    @Test
    fun `follow the phone maps dark to cute and light to paper, keeping the pinned theme when off`() {
        assertEquals("dark with follow on", AppTheme.CUTE, resolvedTheme(AppTheme.SEXY, followThePhone = true, systemDark = true))
        assertEquals("light with follow on", AppTheme.PAPER, resolvedTheme(AppTheme.SEXY, followThePhone = true, systemDark = false))
        assertEquals("follow off keeps the pinned theme", AppTheme.SEXY, resolvedTheme(AppTheme.SEXY, followThePhone = false, systemDark = false))
    }

    @Test
    fun `follow the phone is off by default and persists`() {
        val store = object : KeyValueStore {
            val map = LinkedHashMap<String, String>()
            override fun getString(key: String): String? = map[key]
            override fun putString(key: String, value: String) {
                map[key] = value
            }
        }
        val themeStore = ThemeStore(store)
        assertEquals(false, themeStore.followThePhone())
        themeStore.setFollowThePhone(true)
        assertEquals(true, ThemeStore(store).followThePhone())
        themeStore.setFollowThePhone(false)
        assertEquals(false, ThemeStore(store).followThePhone())
    }
}
