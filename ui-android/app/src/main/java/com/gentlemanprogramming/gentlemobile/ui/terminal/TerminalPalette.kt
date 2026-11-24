package com.gentlemanprogramming.gentlemobile.ui.terminal

import com.gentlemanprogramming.gentlemobile.ui.theme.AppTheme

/**
 * The terminal's 16 ANSI colours + default foreground/background, from the
 * active theme (S9/S33). Cute, Sexy and Gentle keep their dark terminal;
 * Paper is a real light terminal (S33/T44): light background, dark text and
 * its own 16 colours recalibrated for a light surface. The 8 base colours
 * are the theme's named keys; the 8 bright ones are the base lightened
 * toward white (deterministic), except bright black = the theme's dim
 * surface. The app chrome never repaints these.
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

    /** Cute keeps its dark terminal. */
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

    /** Paper is light: the surface is the background, the theme's text is the
     *  foreground, and the 16 ANSI colours are dark shades of the brand family
     *  so each reads on white. The bright slots are darker variants of their
     *  base — the light-background inverse of the dark themes' lightening. */
    val paper = TermColors(
        colors = listOf(
            "#36242c", // black → the theme's text
            "#b3122e", // red → a dark rose (diff removed)
            "#2f6b45", // green → success
            "#8a5a00", // yellow → a dark amber
            "#29537a", // blue
            "#7a2352", // magenta
            "#1e5e6e", // cyan
            "#5a4048", // white → a dark mauve
            "#8a6675", // bright black → muted
            "#8a0f22", // bright red
            "#1c4d31", // bright green
            "#6b4500", // bright yellow
            "#1a3c5c", // bright blue
            "#57183a", // bright magenta
            "#14434f", // bright cyan
            "#6b5360", // bright white
        ).map(::hex),
        background = hex("#ffffff"),
        foreground = hex("#36242c"),
    )

    fun forTheme(theme: AppTheme): TermColors = when (theme) {
        AppTheme.CUTE -> cute
        AppTheme.SEXY -> sexy
        AppTheme.GENTLE -> gentle
        AppTheme.PAPER -> paper
    }
}
