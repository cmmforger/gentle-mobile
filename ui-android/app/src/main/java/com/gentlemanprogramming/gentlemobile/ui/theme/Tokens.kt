package com.gentlemanprogramming.gentlemobile.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The app-level palettes, one per selectable theme (S9). Every value is read
 * from `design/system.md` — "App palette — Cute follows the brand site" and
 * the semantic mapping tables — which itself was captured from the real token
 * sources (the gentle-pi theme JSON files and the gentlemanprogramming.com site CSS
 * variables). Screens must use these tokens, never hardcoded hex values.
 *
 * The in-app terminal keeps its own Herdr palette (`terminal`); the app chrome
 * never repaints it. Hierarchy always reads upward: bg < surface < surfaceRaised.
 */
data class Palette(
    // App background
    val bg: Color,
    // Cards, sheets, rails, inputs, list rows
    val surface: Color,
    // Composer, tabs, key bar, switch tracks
    val surfaceRaised: Color,
    // Selected row / lane highlight (app-level selection)
    val selection: Color,
    // Dividers, focus rings
    val line: Color,
    // Primary action, links
    val accent: Color,
    // Pressed accent
    val accentPressed: Color,
    // Accent borders, focus accent
    val accentEdge: Color,
    // Primary text
    val text: Color,
    // Secondary text
    val muted: Color,
    // Disabled text
    val textDisabled: Color,
    val success: Color,
    val warning: Color,
    // Error, diff removed
    val error: Color,
    // In-app terminal background — Herdr `[theme.custom]`, never the brand palette
    val terminal: Color,
)

/** Which theme the app renders with. The default is Cute (S9). */
enum class AppTheme(val label: String) {
    CUTE("Gentleman-Cute"),
    SEXY("Gentleman-Sexy"),
    GENTLE("Gentle"),
    ;

    companion object {
        val default = CUTE

        /** Storage form of one theme name; unknown strings fall back to [default]. */
        fun fromStored(stored: String): AppTheme? =
            entries.firstOrNull { it.name == stored }
    }
}

object Tokens {
    // Gentleman-Cute (default): the brand palette of gentlemanprogramming.com,
    // read from the site's own CSS custom properties (design/system.md).
    val cute = Palette(
        bg = Color(0xFF1A1218), // --st-base
        surface = Color(0xFF20161E), // --st-surface0
        surfaceRaised = Color(0xFF241822), // --st-surface1
        selection = Color(0xFF342230), // --st-overlay0
        line = Color(0xFF342230), // --st-overlay0 / --st-surface2
        accent = Color(0xFFFF76C3), // --gentlepi-rose
        accentPressed = Color(0xFF5F1E3C), // --gentlepi-logo-dim
        accentEdge = Color(0xFFFF8ACE), // --gentlepi-logo-fresh
        text = Color(0xFFF6EFF3), // --st-text
        muted = Color(0xFFA78E9B), // --st-subtext1
        textDisabled = Color(0xFF76616B), // Cute `dim`
        success = Color(0xFFB4E7C7), // mint
        warning = Color(0xFFF2B86D), // peach
        error = Color(0xFFFF718F), // --st-red
        terminal = Color(0xFF100A0F), // Herdr surface0
    )

    // Gentleman-Sexy: its captured palette, no extension layer.
    val sexy = Palette(
        bg = Color(0xFF060407),
        surface = Color(0xFF100A0F), // bgPanel
        surfaceRaised = Color(0xFF2A1720), // borderSubtle chrome slot
        selection = Color(0xFF2E0F1F), // userMessageBg
        line = Color(0xFF563040), // border
        accent = Color(0xFFF43888),
        accentPressed = Color(0xFFBF0F50), // deepPink
        accentEdge = Color(0xFFFF4F9A), // activePink
        text = Color(0xFFF6EFF3),
        muted = Color(0xFFA78E9B),
        textDisabled = Color(0xFF76616B), // dim
        success = Color(0xFFD2CBD0), // pearl
        warning = Color(0xFFF2B86D),
        error = Color(0xFFFF718F),
        terminal = Color(0xFF100A0F),
    )

    // Gentle: the cool/neutral "no-brand" reading theme. Secondary text uses
    // `secondary #A3B5D6` for essential labels (contrast rule); `muted #5C6170`
    // measures 3.1:1 and stays disabled-only.
    val gentle = Palette(
        bg = Color(0xFF06080F),
        surface = Color(0xFF0D0F14), // bgSubtle
        surfaceRaised = Color(0xFF161D2E), // userMessageBg chrome slot
        selection = Color(0xFF232A40), // borderSubtle selection
        line = Color(0xFF313342), // border
        accent = Color(0xFFE0C15A), // gold
        accentPressed = Color(0xFFB5B2D0), // secondary
        accentEdge = Color(0xFF7FB4CA), // blue
        text = Color(0xFFF3F6F9),
        muted = Color(0xFFA3B5D6), // secondary — essential secondary text
        textDisabled = Color(0xFF5C6170), // muted — decorative/disabled only
        success = Color(0xFFB7CC85), // green
        warning = Color(0xFFDEBA87),
        error = Color(0xFFCB7C94), // red
        terminal = Color(0xFF06080F),
    )
}

/** The palette backing the theme that is currently applied. */
fun paletteOf(theme: AppTheme): Palette = when (theme) {
    AppTheme.CUTE -> Tokens.cute
    AppTheme.SEXY -> Tokens.sexy
    AppTheme.GENTLE -> Tokens.gentle
}

/** Provided by [GentleMobileTheme]; screens read it, never a hardcoded token. */
val LocalPalette = staticCompositionLocalOf { Tokens.cute }
