package com.gentlemanprogramming.gentlemobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Gentleman-Cute (default theme, S9) mapped onto Material 3.
 *
 * Values come from `design/system.md` via [Tokens]; nothing is invented here.
 * The mapping follows the "Semantic → theme mapping" table: `surface` ← surface,
 * `surfaceRaised` ← raised chrome, `outline` ← the hairline `line` colour.
 */
private val GentleCuteColors = darkColorScheme(
    primary = Tokens.accent,
    onPrimary = Tokens.bg,
    primaryContainer = Tokens.accentPressed,
    onPrimaryContainer = Tokens.accentEdge,
    secondary = Tokens.accentEdge,
    onSecondary = Tokens.bg,
    background = Tokens.bg,
    onBackground = Tokens.text,
    surface = Tokens.surface,
    onSurface = Tokens.text,
    surfaceVariant = Tokens.surfaceRaised,
    onSurfaceVariant = Tokens.muted,
    outline = Tokens.line,
    outlineVariant = Tokens.line,
    error = Tokens.error,
    onError = Tokens.bg,
    inverseSurface = Tokens.surfaceRaised,
    inverseOnSurface = Tokens.text,
)

@Composable
fun GentleMobileTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GentleCuteColors,
        content = content,
    )
}
