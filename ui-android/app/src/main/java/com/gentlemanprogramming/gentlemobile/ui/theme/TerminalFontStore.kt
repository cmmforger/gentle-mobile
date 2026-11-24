package com.gentlemanprogramming.gentlemobile.ui.theme

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import com.gentlemanprogramming.gentlemobile.R

/**
 * The terminal's face (T44): IosevkaTerm Nerd Font by default, JetBrains Mono
 * as the alternative. The choice governs the terminal grid — cell advance and
 * line height — and the PTY is resized when it changes. The interface typeface
 * [AppTypeface] is separate: this is the terminal, never the chrome.
 */
enum class TerminalFont(val label: String) {
    IOSEVKA_TERM("IosevkaTerm Nerd Font"),
    JETBRAINS_MONO("JetBrains Mono"),
    ;

    companion object {
        val default = IOSEVKA_TERM

        /** Storage form of one choice name; unknown strings fall back to [default]. */
        fun fromStored(stored: String): TerminalFont? =
            entries.firstOrNull { it.name == stored }
    }
}

/** The persisted terminal font choice (same contract as [TypefaceStore]). */
class TerminalFontStore(private val store: KeyValueStore) {

    /** The persisted choice, or IosevkaTerm when nothing valid was stored. */
    fun selected(): TerminalFont =
        store.getString(KEY)?.let { TerminalFont.fromStored(it) } ?: TerminalFont.default

    fun select(choice: TerminalFont) {
        store.putString(KEY, choice.name)
    }

    companion object {
        const val KEY = "terminal_font"
    }
}

/** The bundled font resources behind a terminal choice. */
data class TerminalFontFace(
    val regular: Int,
    val bold: Int?,
    val italic: Int?,
    val boldItalic: Int?,
)

/** The bundled terminal face behind a choice. IosevkaTerm ships regular and
 *  bold (no italic); JetBrains Mono ships all four. */
fun bundledTerminalFace(choice: TerminalFont): TerminalFontFace = when (choice) {
    TerminalFont.IOSEVKA_TERM -> TerminalFontFace(
        regular = R.font.iosevkaterm_regular,
        bold = R.font.iosevkaterm_bold,
        italic = null,
        boldItalic = null,
    )
    TerminalFont.JETBRAINS_MONO -> TerminalFontFace(
        regular = R.font.jetbrainsmono_regular,
        bold = R.font.jetbrainsmono_bold,
        italic = R.font.jetbrainsmono_italic,
        boldItalic = R.font.jetbrainsmono_bolditalic,
    )
}

/** The chosen terminal face and the way to change it, provided beside
 *  [LocalAppTypeface] so the settings screen and the terminal read it without
 *  threading a parameter through the connect flow. */
val LocalTerminalFont = staticCompositionLocalOf { TerminalFont.default }
val LocalOnSetTerminalFont: ProvidableCompositionLocal<(TerminalFont) -> Unit> =
    staticCompositionLocalOf { {} }
