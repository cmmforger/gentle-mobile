package com.gentlemanprogramming.gentlemobile.ui.terminal

import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.res.ResourcesCompat
import com.gentlemanprogramming.gentlemobile.R
import com.gentlemanprogramming.gentlemobile.ui.theme.AppTypeface
import com.gentlemanprogramming.gentlemobile.ui.theme.terminalFaceFor

/** The four bundled typefaces, resolved once (null → system fallback). */
class TerminalTypefaces(
    val regular: Typeface?,
    val bold: Typeface?,
    val italic: Typeface?,
    val boldItalic: Typeface?,
)

/** The terminal's own face (T44/T47): always the bundled IosevkaTerm Nerd
 *  Font, resolved through [terminalFaceFor] so the app typeface never leaks
 *  a proportional face (or a mono face without Nerd glyphs) into the grid. */
@Composable
fun rememberTerminalTypefaces(choice: AppTypeface): TerminalTypefaces {
    val context = LocalContext.current
    return remember(choice) {
        fun font(id: Int): Typeface? = runCatching { ResourcesCompat.getFont(context, id) }.getOrNull()
        val face = terminalFaceFor(choice)
        TerminalTypefaces(
            regular = font(face.regular),
            bold = face.bold?.let { font(it) },
            italic = face.italic?.let { font(it) },
            boldItalic = face.boldItalic?.let { font(it) },
        )
    }
}
