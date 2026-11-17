package com.gentlemanprogramming.gentlecompanion.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Design tokens for the Gentle Companion app.
 *
 * Every value is read from `design/system.md` ("App palette — Cute follows the
 * brand site"), which itself was captured from the real token sources
 * (`gentle-pi/themes/Gentleman-Cute.json` and the gentlemanprogramming.com site
 * CSS variables). Screens must use these tokens, never hardcoded hex values.
 *
 * The in-app terminal keeps its own Herdr palette (`--terminal #100A0F`); the
 * app chrome never repaints it.
 */
object Tokens {
    // App background — site `--st-base`
    val bg = Color(0xFF1A1218)
    // Cards, sheets, rails — site `--st-surface0`
    val surface = Color(0xFF20161E)
    // Composer, tabs, key bar, switch tracks — site `--st-surface1`
    val surfaceRaised = Color(0xFF241822)
    // Dividers, focus rings; also the app-level selection background
    val line = Color(0xFF342230)
    // Primary action, links — site `--gentlepi-rose`
    val accent = Color(0xFFFF76C3)
    // Pressed accent — site `--gentlepi-logo-dim`
    val accentPressed = Color(0xFF5F1E3C)
    // Accent borders, focus accent — site `--gentlepi-logo-fresh`
    val accentEdge = Color(0xFFFF8ACE)
    // Primary text — site `--st-text`
    val text = Color(0xFFF6EFF3)
    // Secondary text — site `--st-subtext1`
    val muted = Color(0xFFA78E9B)
    // Error, diff removed — site `--st-red`
    val error = Color(0xFFFF718F)
    // In-app terminal background — Herdr `[theme.custom]`, never the brand palette
    val terminal = Color(0xFF100A0F)
}
