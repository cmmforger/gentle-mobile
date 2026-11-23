package com.gentlemanprogramming.gentlemobile.ui.terminal

import com.gentlemanprogramming.gentlemobile.ui.theme.AppTheme

/**
 * The terminal's 16 ANSI colours + default foreground/background, from the
 * active theme (S9/S33). Values come from `design/system.md` "Terminal
 * palette (Herdr export)" for Cute/Sexy, its Gentle derivation, and the
 * rule that Paper keeps Herdr's dark palette (terminal `#100A0F`). The 8
 * base colours are the theme's named keys; the 8 bright ones are the base
 * lightened toward white (deterministic), except bright black = the theme's
 * dim surface. The app chrome never repaints these.
 */
object TerminalPalette {

    fun lighten(argb: Int, factor: Float): Int {
        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        fun mix(c: Int): Int = (c + (255 - c) * factor).toInt().coerceIn(0, 255)
        return (a shl 24) or (mix(r) shl 16) or (mix(g) shl 8) or mix(b)
    }

    fun hex(value: String): Int {
        val body = value.removePrefix("#")
        val rgb = body.toLong(16).toInt()
        return (0xFF shl 24) or rgb
    }

    private const val BRIGHTEN = 0.30f

    private fun palette(
        black: String,
        red: String,
        green: String,
        yellow: String,
        blue: String,
        magenta: String,
        cyan: String,
        white: String,
        brightBlack: String,
    ): TermColors {
        val base = listOf(black, red, green, yellow, blue, magenta, cyan, white).map(::hex)
        val bright = base.map { lighten(it, BRIGHTEN) }.toMutableList()
        bright[0] = hex(brightBlack)
        return TermColors(
            colors = base + bright,
            background = hex(black),
            foreground = hex(white),
        )
    }

    /** Cute (and Paper, whose terminal keeps Herdr's dark palette). */
    val cute = palette(
        black = "#100a0f", // surface0
        red = "#ff718f",
        green = "#b4e7c7",
        yellow = "#e0c27a",
        blue = "#f095c8", // "types" — the brand accent
        magenta = "#f095c8", // mauve "keywords"
        cyan = "#a9c7ee", // teal "parameters"
        white = "#f6eff3", // text
        brightBlack = "#563040", // surface_dim
    )

    val sexy = palette(
        black = "#100a0f",
        red = "#ff718f",
        green = "#d2cbd0", // pearl
        yellow = "#e0c27a",
        blue = "#f43888",
        magenta = "#f43888",
        cyan = "#a9c7ee",
        white = "#f6eff3",
        brightBlack = "#563040",
    )

    /** Gentle has no Herdr export yet; derived from its own vars (T16). */
    val gentle = palette(
        black = "#06080f", // bg
        red = "#cb7c94",
        green = "#b7cc85",
        yellow = "#deba87", // warning
        blue = "#7fb4ca",
        magenta = "#a3b5d6", // secondary
        cyan = "#7fb4ca", // blue (no teal token)
        white = "#f3f6f9",
        brightBlack = "#5c6170", // muted (dim)
    )

    fun forTheme(theme: AppTheme): TermColors = when (theme) {
        AppTheme.CUTE, AppTheme.PAPER -> cute
        AppTheme.SEXY -> sexy
        AppTheme.GENTLE -> gentle
    }
}
