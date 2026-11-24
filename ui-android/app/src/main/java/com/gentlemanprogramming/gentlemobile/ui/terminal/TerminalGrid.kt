package com.gentlemanprogramming.gentlemobile.ui.terminal

import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.res.ResourcesCompat
import com.gentlemanprogramming.gentlemobile.ui.theme.TerminalFace

/** The four bundled typefaces, resolved once (null → system fallback). */
class TerminalTypefaces(
    val regular: Typeface?,
    val bold: Typeface?,
    val italic: Typeface?,
    val boldItalic: Typeface?,
)

/** The terminal's own face (T44/T47/T51): the chosen [TerminalFace], always
 *  a monospaced Nerd Font, resolved so a proportional app face never leaks
 *  into the grid. The grid re-derives from this face's cell size, so a
 *  change re-measures the grid and the PTY gets a window-change. */
@Composable
fun rememberTerminalTypefaces(face: TerminalFace): TerminalTypefaces {
    val context = LocalContext.current
    return remember(face) {
        fun font(id: Int): Typeface? = runCatching { ResourcesCompat.getFont(context, id) }.getOrNull()
        TerminalTypefaces(
            regular = font(face.regular),
            bold = font(face.bold),
            italic = null,
            boldItalic = null,
        )
    }
}
