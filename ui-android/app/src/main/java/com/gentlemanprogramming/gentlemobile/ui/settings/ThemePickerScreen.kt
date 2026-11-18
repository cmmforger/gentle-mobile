package com.gentlemanprogramming.gentlemobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gentlemanprogramming.gentlemobile.ui.theme.AppTheme
import com.gentlemanprogramming.gentlemobile.ui.theme.LocalPalette
import com.gentlemanprogramming.gentlemobile.ui.theme.paletteOf

/**
 * The theme picker (S21, board `06-themes.html`): one card per theme, the
 * current one marked, a live mini terminal plus a wordless colour strip of
 * the app chrome, and immediate application on tap. The card is the control —
 * no per-card buttons. The layout never changes with the theme.
 */
@Composable
fun ThemePickerScreen(
    current: AppTheme,
    onSelect: (AppTheme) -> Unit,
    onBack: () -> Unit,
) {
    val p = LocalPalette.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Text("‹", color = p.text, fontSize = 18.sp)
            }
            Spacer(Modifier.width(8.dp))
            Text(text = "Theme", color = p.muted, fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Theme",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 22.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Same layout, new colours — the terminal and the app chrome switch together.",
            color = p.muted,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(12.dp))

        AppTheme.entries.forEach { theme ->
            ThemeCard(
                theme = theme,
                selected = theme == current,
                onSelect = { onSelect(theme) },
            )
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun hex(color: Color): String =
    String.format("#%06X", color.toArgb() and 0xFFFFFF)

@Composable
private fun ThemeCard(theme: AppTheme, selected: Boolean, onSelect: () -> Unit) {
    val chrome = LocalPalette.current
    val palette = paletteOf(theme)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .border(
                width = 1.dp,
                color = if (selected) chrome.accentEdge else chrome.surfaceRaised,
                shape = RoundedCornerShape(16.dp),
            )
            .background(if (selected) chrome.selection else Color.Transparent)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The radio: a 44dp target wrapping the 20dp mark. Selected reads
            // filled with the accent edge and a selection-colour core.
            Box(
                modifier = Modifier.size(44.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .border(
                            width = 2.dp,
                            color = if (selected) chrome.accentEdge else chrome.muted,
                            shape = CircleShape,
                        )
                        .background(
                            if (selected) chrome.accentEdge else Color.Transparent,
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .background(chrome.selection, CircleShape),
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = theme.label,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "accent ${hex(palette.accent)}",
                    color = chrome.muted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
            if (theme == AppTheme.CUTE) {
                Text(
                    text = "DEFAULT",
                    color = chrome.muted,
                    fontSize = 11.sp,
                    letterSpacing = 0.6.sp,
                    modifier = Modifier
                        .border(1.dp, chrome.line, RoundedCornerShape(999.dp))
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
        }

        MiniTerminal(theme)

        // The wordless strip: surface · raised · accent · text.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(palette.surface, palette.surfaceRaised, palette.accent, palette.text).forEach { swatch ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(14.dp)
                        .background(swatch, RoundedCornerShape(4.dp)),
                )
            }
        }
    }
}

/** The card's live preview: the terminal's own palette, never the chrome's. */
@Composable
private fun MiniTerminal(theme: AppTheme) {
    val palette = paletteOf(theme)
    val term = TerminalPreviewColors.of(theme)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, term.border, RoundedCornerShape(8.dp))
            .background(palette.terminal, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row {
            Text("❯ ", color = term.accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text("pytest tests/payments", color = term.text, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = term.passed)) { append("PASSED") }
                append(" 14 · ")
                withStyle(SpanStyle(color = term.skipped)) { append("skipped 2") }
            },
            color = term.text,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 16.sp,
        )
        Text(
            text = "▌ agent: approve staging migration?",
            color = term.agent,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 16.sp,
        )
        Row {
            Text("❯ ", color = term.accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text("▌", color = term.cursor, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

/**
 * The mini terminal renders the theme's captured terminal colours (Herdr
 * `[theme.custom]` for Cute/Sexy, Gentle's own vars — design/system.md,
 * "Terminal palette"), never the app chrome.
 */
private class TerminalPreviewColors(
    val accent: Color,
    val text: Color,
    val passed: Color,
    val skipped: Color,
    val agent: Color,
    val cursor: Color,
    val border: Color,
) {
    companion object {
        fun of(theme: AppTheme) = when (theme) {
            AppTheme.SEXY -> TerminalPreviewColors(
                accent = Color(0xFFF43888),
                text = Color(0xFFF6EFF3),
                passed = Color(0xFFD2CBD0), // green
                skipped = Color(0xFFE0C27A), // yellow
                agent = Color(0xFFF43888),
                cursor = Color(0xFFF6EFF3),
                border = Color(0xFF2A1720), // surface_dim
            )
            AppTheme.CUTE -> TerminalPreviewColors(
                accent = Color(0xFFF095C8),
                text = Color(0xFFF6EFF3),
                passed = Color(0xFFB4E7C7), // mint
                skipped = Color(0xFFE0C27A), // champagne
                agent = Color(0xFFD7A0B8), // softRose
                cursor = Color(0xFFD7A0B8),
                border = Color(0xFF2A1720), // surface_dim
            )
            // No Herdr export yet: derived from Gentle's own vars (T16 note).
            AppTheme.GENTLE -> TerminalPreviewColors(
                accent = Color(0xFFE0C15A),
                text = Color(0xFFF3F6F9),
                passed = Color(0xFFB7CC85), // green
                skipped = Color(0xFFDEBA87), // warning
                agent = Color(0xFFA3B5D6), // secondary
                cursor = Color(0xFF7FB4CA), // blue
                border = Color(0xFF313342), // border
            )
        }
    }
}
