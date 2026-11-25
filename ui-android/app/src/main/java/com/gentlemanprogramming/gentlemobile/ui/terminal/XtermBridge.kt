package com.gentlemanprogramming.gentlemobile.ui.terminal

import java.util.Base64

/**
 * The pure half of the xterm.js bridge (T61): the theme → xterm JSON
 * mapping, the byte encoding, the message framing and the resize decision.
 * Everything here is a deterministic function of its inputs, so the JVM
 * tests pin the exact strings the WebView reads — the Android glue that owns
 * the WebView instance lives in [TerminalWebView].
 */

/** The selection highlight is the foreground at a fixed alpha, so it reads
 *  on both the dark themes and Paper. */
private const val SELECTION_ALPHA = "0.4"

/** The xterm.js theme shape: 16 ANSI colours + foreground/background + the
 *  cursor and selection surfaces, all `#rrggbb` (selection as `rgba`). */
data class XtermTheme(
    val background: String,
    val foreground: String,
    val cursor: String,
    val cursorAccent: String,
    val selectionBackground: String,
    val black: String,
    val red: String,
    val green: String,
    val yellow: String,
    val blue: String,
    val magenta: String,
    val cyan: String,
    val white: String,
    val brightBlack: String,
    val brightRed: String,
    val brightGreen: String,
    val brightYellow: String,
    val brightBlue: String,
    val brightMagenta: String,
    val brightCyan: String,
    val brightWhite: String,
) {
    /** The theme object as a JSON string for xterm.js's `theme` option. */
    fun toJson(): String = buildString {
        append("{")
        append("\"background\":\"$background\",")
        append("\"foreground\":\"$foreground\",")
        append("\"cursor\":\"$cursor\",")
        append("\"cursorAccent\":\"$cursorAccent\",")
        append("\"selectionBackground\":\"$selectionBackground\",")
        append("\"black\":\"$black\",")
        append("\"red\":\"$red\",")
        append("\"green\":\"$green\",")
        append("\"yellow\":\"$yellow\",")
        append("\"blue\":\"$blue\",")
        append("\"magenta\":\"$magenta\",")
        append("\"cyan\":\"$cyan\",")
        append("\"white\":\"$white\",")
        append("\"brightBlack\":\"$brightBlack\",")
        append("\"brightRed\":\"$brightRed\",")
        append("\"brightGreen\":\"$brightGreen\",")
        append("\"brightYellow\":\"$brightYellow\",")
        append("\"brightBlue\":\"$brightBlue\",")
        append("\"brightMagenta\":\"$brightMagenta\",")
        append("\"brightCyan\":\"$brightCyan\",")
        append("\"brightWhite\":\"$brightWhite\"")
        append("}")
    }

    companion object {
        /** The active theme's [TermColors] under the keys xterm reads. */
        fun from(colors: TermColors): XtermTheme {
            val hexColors = colors.colors.map(::hex)
            return XtermTheme(
                background = hex(colors.background),
                foreground = hex(colors.foreground),
                cursor = hex(colors.foreground),
                cursorAccent = hex(colors.background),
                selectionBackground = rgba(colors.foreground, SELECTION_ALPHA),
                black = hexColors[0],
                red = hexColors[1],
                green = hexColors[2],
                yellow = hexColors[3],
                blue = hexColors[4],
                magenta = hexColors[5],
                cyan = hexColors[6],
                white = hexColors[7],
                brightBlack = hexColors[8],
                brightRed = hexColors[9],
                brightGreen = hexColors[10],
                brightYellow = hexColors[11],
                brightBlue = hexColors[12],
                brightMagenta = hexColors[13],
                brightCyan = hexColors[14],
                brightWhite = hexColors[15],
            )
        }
    }
}

private fun hexByte(value: Int): String {
    val s = Integer.toHexString(value and 0xFF)
    return if (s.length == 1) "0$s" else s
}

private fun hex(argb: Int): String =
    "#${hexByte((argb ushr 16) and 0xFF)}${hexByte((argb ushr 8) and 0xFF)}${hexByte(argb and 0xFF)}"

private fun rgba(argb: Int, alpha: String): String =
    "rgba(${(argb ushr 16) and 0xFF},${(argb ushr 8) and 0xFF},${argb and 0xFF},$alpha)"

/** Byte encoding: the PTY's raw bytes and the WebView's keystrokes travel as
 *  base64 inside JSON, so no byte can collide with the framing. */
object XtermCodec {
    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)
}

/** The grid the fit addon measured; both axes are terminal cells. */
data class XtermGrid(val cols: Int, val rows: Int)

/** The PTY resize decision (T61): the WebView reports the grid xterm really
 *  painted; the PTY is re-asked only when that grid changed and is a real
 *  terminal size. */
object XtermResize {
    const val MIN_COLS = 2
    const val MIN_ROWS = 1

    fun decide(
        currentCols: Int,
        currentRows: Int,
        reportedCols: Int,
        reportedRows: Int,
    ): XtermGrid? {
        val cols = reportedCols.coerceAtLeast(MIN_COLS)
        val rows = reportedRows.coerceAtLeast(MIN_ROWS)
        return if (cols == currentCols && rows == currentRows) null else XtermGrid(cols, rows)
    }
}

/** The Kotlin → JS messages, as exact JSON strings. */
object XtermMessages {
    fun write(base64: String): String = """{"type":"write","data":"$base64"}"""

    fun theme(themeJson: String): String = """{"type":"theme","theme":$themeJson}"""

    fun cursor(mode: String, blink: Boolean): String =
        """{"type":"cursor","mode":"$mode","blink":$blink}"""

    fun focus(): String = """{"type":"focus"}"""

    fun init(themeJson: String, fontCss: String, mode: String, blink: Boolean): String =
        """{"type":"init","theme":$themeJson,""" +
            """"font":${jsonQuote(fontCss)},"cursor":"$mode","blink":$blink}"""

    /** The JSON-string escaping for the embedded font CSS. */
    fun jsonEscape(text: String): String = buildString {
        for (ch in text) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') {
                    append("\\u").append(String.format("%04x", ch.code))
                } else {
                    append(ch)
                }
            }
        }
    }

    fun jsonQuote(text: String): String = "\"${jsonEscape(text)}\""
}

/** The bundled mono face as a CSS @font-face pair, injected at init so xterm
 *  measures its cells with the chosen face. */
object XtermFont {
    const val FAMILY = "gentle-terminal"

    fun faceCss(regularBase64: String, boldBase64: String): String =
        "@font-face { font-family: '$FAMILY'; " +
            "src: url('data:font/ttf;base64,$regularBase64') format('truetype'); " +
            "font-weight: normal; font-style: normal; } " +
            "@font-face { font-family: '$FAMILY'; " +
            "src: url('data:font/ttf;base64,$boldBase64') format('truetype'); " +
            "font-weight: bold; font-style: normal; }"
}

/** The Advanced → Cursor choice under xterm's own style names. */
fun CursorMode.xtermStyle(): String = when (this) {
    CursorMode.BLOCK -> "block"
    CursorMode.UNDERLINE -> "underline"
    CursorMode.BAR -> "bar"
}
