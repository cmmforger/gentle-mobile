package com.gentlemanprogramming.gentlemobile.ui.theme

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.gentlemanprogramming.gentlemobile.R

/**
 * The terminal's face (T51): independent of the app typeface, always
 * monospaced and always carrying Nerd glyphs — a proportional face is never
 * offered here, because icon and column integrity is the rule. Each face
 * ships regular and bold; the renderer's bold cells use the real bold face.
 */
enum class TerminalFace(
    val label: String,
    val regular: Int,
    val bold: Int,
) {
    IOSEVKA_TERM("IosevkaTerm Nerd Font", R.font.iosevkaterm_regular, R.font.iosevkaterm_bold),
    JETBRAINS_MONO("JetBrainsMono Nerd Font", R.font.jetbrainsmono_nf_regular, R.font.jetbrainsmono_nf_bold),
    FIRA_CODE("FiraCode Nerd Font", R.font.firacode_nf_regular, R.font.firacode_nf_bold),
    HACK("Hack Nerd Font", R.font.hack_nf_regular, R.font.hack_nf_bold),
    ;

    /** Every face is monospaced — the invariant, not a per-face fact. */
    val monospaced: Boolean get() = true

    /** Every face carries Nerd glyphs — the invariant, not a per-face fact. */
    val nerdGlyphs: Boolean get() = true

    companion object {
        val default = IOSEVKA_TERM

        /** Storage form of one choice name; unknown strings fall back to [default]. */
        fun fromStored(stored: String): TerminalFace? =
            entries.firstOrNull { it.name == stored }
    }
}

/** The picker's own preview family: each face drawn in itself. */
fun terminalFontFamily(face: TerminalFace): FontFamily =
    FontFamily(Font(face.regular), Font(face.bold, FontWeight.Bold))

/** The persisted terminal-face choice (T51), over the same [KeyValueStore]
 *  every preference uses. */
class TerminalFaceStore(private val store: KeyValueStore) {

    fun selected(): TerminalFace =
        store.getString(KEY)?.let { TerminalFace.fromStored(it) } ?: TerminalFace.default

    fun select(face: TerminalFace) {
        store.putString(KEY, face.name)
    }

    companion object {
        const val KEY = "terminal_face"
    }
}

/** The chosen terminal face and the way to change it, provided beside
 *  [LocalAppTypeface] so the terminal and the settings screen read it
 *  without threading a parameter through the connect flow. */
val LocalTerminalFace = staticCompositionLocalOf { TerminalFace.default }
val LocalOnSetTerminalFace: ProvidableCompositionLocal<(TerminalFace) -> Unit> =
    staticCompositionLocalOf { {} }
