package com.gentlemanprogramming.gentlemobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

/**
 * Every screen draws with the typeface the app holds: the family is
 * handed to `MaterialTheme` once, so a switch applies everywhere without
 * touching composables.
 */
fun fontFamilyFor(choice: AppTypeface): FontFamily {
    val face = bundledFace(choice) ?: return FontFamily.Default
    val regular = Font(face.regular)
    return face.bold?.let { FontFamily(regular, Font(it, FontWeight.Bold)) }
        ?: FontFamily(regular)
}

private fun typographyOf(family: FontFamily): Typography {
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = family),
        displayMedium = base.displayMedium.copy(fontFamily = family),
        displaySmall = base.displaySmall.copy(fontFamily = family),
        headlineLarge = base.headlineLarge.copy(fontFamily = family),
        headlineMedium = base.headlineMedium.copy(fontFamily = family),
        headlineSmall = base.headlineSmall.copy(fontFamily = family),
        titleLarge = base.titleLarge.copy(fontFamily = family),
        titleMedium = base.titleMedium.copy(fontFamily = family),
        titleSmall = base.titleSmall.copy(fontFamily = family),
        bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family),
        labelMedium = base.labelMedium.copy(fontFamily = family),
        labelSmall = base.labelSmall.copy(fontFamily = family),
    )
}

@Composable
fun GentleMobileTheme(
    theme: AppTheme = AppTheme.default,
    typeface: AppTypeface = AppTypeface.default,
    onSetTypeface: (AppTypeface) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val palette = paletteOf(theme)
    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalAppTypeface provides typeface,
        LocalOnSetTypeface provides onSetTypeface,
    ) {
        MaterialTheme(
            colorScheme = materialScheme(palette),
            typography = typographyOf(fontFamilyFor(typeface)),
            content = content,
        )
    }
}
