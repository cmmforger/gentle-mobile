package com.gentlemanprogramming.gentlemobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * One theme applied through Material 3 so every screen follows it: a theme
 * swap rewrites the backing values and never changes layout (S9). Values come
 * from `design/system.md` via [Tokens]; the mapping follows the
 * "Semantic → theme mapping" table: `surface` ← surface, `surfaceRaised` ←
 * raised chrome, `outline` ← the hairline `line` colour.
 */
fun materialScheme(p: Palette) = darkColorScheme(
    primary = p.accent,
    onPrimary = p.bg,
    primaryContainer = p.accentPressed,
    onPrimaryContainer = p.accentEdge,
    secondary = p.accentEdge,
    onSecondary = p.bg,
    background = p.bg,
    onBackground = p.text,
    surface = p.surface,
    onSurface = p.text,
    surfaceVariant = p.surfaceRaised,
    onSurfaceVariant = p.muted,
    outline = p.line,
    outlineVariant = p.line,
    error = p.error,
    onError = p.bg,
    inverseSurface = p.surfaceRaised,
    inverseOnSurface = p.text,
)

@Composable
fun GentleMobileTheme(theme: AppTheme = AppTheme.default, content: @Composable () -> Unit) {
    val palette = paletteOf(theme)
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colorScheme = materialScheme(palette),
            content = content,
        )
    }
}
