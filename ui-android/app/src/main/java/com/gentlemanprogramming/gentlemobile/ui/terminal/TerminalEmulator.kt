package com.gentlemanprogramming.gentlemobile.ui.terminal

/**
 * A pure-JVM VT terminal emulator (T42): consumes the PTY's byte stream and
 * produces a cell grid — the same shape the app already renders. This is the
 * engine choice: a bounded ANSI/VT100 subset written and owned here, plus the
 * proven cell renderer, instead of vendoring Termux's `terminal-emulator`
 * (its C++/ndkBuild module is a fork we would have to keep compiling forever)
 * or a WebView bridge. Correct 16/256/truecolour, wide characters, cursor,
 * scrollback and attributes are all local and unit-testable.
 */

/** Attribute flag bits; the renderer reads the same values. */
object TermFlags {
    const val BOLD: Int = 1
    const val DIM: Int = 2
    const val ITALIC: Int = 4
    const val UNDERLINE: Int = 8
    const val REVERSED: Int = 16
    const val HIDDEN: Int = 32
    const val STRUCK: Int = 64
}

/** One cell: the rendered glyph plus its colour and attribute state. */
class TermCell {
    var text: String = ""
    var fg: Int = 0
    var bg: Int = 0
    var flags: Int = 0
    /** Lead slot of a double-width glyph. */
    var wide: Boolean = false
    /** Follower slot of a double-width glyph (background only). */
    var cont: Boolean = false

    fun reset(fg: Int, bg: Int, flags: Int) {
        text = ""
        this.fg = fg
        this.bg = bg
        this.flags = flags
        wide = false
        cont = false
    }
}

typealias TermLine = Array<TermCell>

/**
 * The terminal's 16 colours plus its default foreground/background — always
 * from the active theme; the chrome never repaints it.
 */
class TermColors(
    val colors: List<Int>,
    val background: Int,
    val foreground: Int,
)

/** Visible cell width: 0 combining, 1 narrow, 2 wide (a wcwidth subset). */
fun terminalCharWidth(cp: Int): Int = when {
    cp == 0 -> 0
    cp in 0x0300..0x036F || cp in 0x1AB0..0x1AFF || cp in 0x1DC0..0x1DFF ||
        cp in 0x20D0..0x20FF || cp in 0xFE00..0xFE0F || cp in 0xFE20..0xFE2F -> 0
    cp in 0x1100..0x115F || cp in 0x2329..0x232A || cp in 0x2E80..0x303E ||
        cp in 0x3041..0x33FF || cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF ||
        cp in 0xA000..0xA4CF || cp in 0xAC00..0xD7A3 || cp in 0xF900..0xFAFF ||
        cp in 0xFE10..0xFE19 || cp in 0xFE30..0xFE6F || cp in 0xFF00..0xFF60 ||
        cp in 0xFFE0..0xFFE6 || cp in 0x1F300..0x1FAFF || cp in 0x20000..0x2FFFD ||
        cp in 0x30000..0x3FFFD -> 2
    else -> 1
}

/** Incremental UTF-8 → code-point decoding; a split multibyte char survives. */
class Utf8Decoder {
    private val buf = ByteArray(4)
    private var filled = 0
    private var remaining = 0

    fun push(byte: Byte, out: (Int) -> Unit) {
        if (remaining == 0) {
            when {
                byte >= 0 -> {
                    out(byte.toInt())
                    return
                }
                (byte.toInt() and 0xE0) == 0xC0 -> remaining = 2
                (byte.toInt() and 0xF0) == 0xE0 -> remaining = 3
                (byte.toInt() and 0xF8) == 0xF0 -> remaining = 4
                else -> return
            }
            buf[0] = byte
            filled = 1
        } else {
            buf[filled++] = byte
        }
        if (filled == remaining) {
            out(String(buf, 0, filled, Charsets.UTF_8).codePointAt(0))
            filled = 0
            remaining = 0
        }
    }
}

private const val MAX_SCROLLBACK = 1000

/** The standard xterm 256-colour cube + grey ramp. */
fun xterm256(index: Int, palette: List<Int>): Int {
    if (index < 16) return palette.getOrElse(index) { palette[0] }
    if (index in 16..231) {
        val v = index - 16
        val levels = intArrayOf(0, 95, 135, 175, 215, 255)
        val r = levels[v / 36]
        val g = levels[(v / 6) % 6]
        val b = levels[v % 6]
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    val level = ((index - 232) * 10 + 8).coerceIn(0, 255)
    return (0xFF shl 24) or (level shl 16) or (level shl 8) or level
}

class TerminalEmulator(
    initialCols: Int,
    initialRows: Int,
    private val palette: TermColors,
) {
    var cols: Int = initialCols.coerceAtLeast(2)
        private set
    var rows: Int = initialRows.coerceAtLeast(1)
        private set

    private val screen = ArrayDeque<TermLine>()
    private val scrollback = ArrayDeque<TermLine>()

    var cursorX: Int = 0
        private set
    var cursorY: Int = 0
        private set
    var cursorVisible: Boolean = true
        private set
    var title: String? = null
        private set

    private var scrollTop = 0
    private var scrollBottom = rows - 1

    private var curFg = palette.foreground
    private var curBg = palette.background
    private var curFlags = 0

    private val saved = IntArray(2)

    private val decoder = Utf8Decoder()

    // Parser state.
    private enum class State { GROUND, ESC, CSI, OSC, OSC_ESC, IGNORE_ONE }
    private var state = State.GROUND
    private val csiParams = StringBuilder()
    private val oscBuffer = StringBuilder()

    init {
        for (i in 0 until rows) screen.addLast(blankLine())
    }

    private fun blankLine(): TermLine =
        Array(cols) { TermCell().apply { reset(curFg, curBg, curFlags) } }

    private fun eraseCell(line: TermLine, column: Int) {
        line[column].reset(curFg, curBg, curFlags)
    }

    private fun visibleLines(): List<TermLine> = screen.toList()

    /** The scrollback lines, oldest first. */
    fun scrollbackLines(): List<TermLine> = scrollback.toList()

    /** The visible screen lines, top to bottom. */
    fun screenLines(): List<TermLine> = visibleLines()

    /** Feed raw bytes from the host; multibyte UTF-8 is handled incrementally. */
    fun feed(data: ByteArray) {
        for (byte in data) {
            decoder.push(byte) { cp -> handleCodePoint(cp) }
        }
    }

    private fun handleCodePoint(cp: Int) {
        when (state) {
            State.IGNORE_ONE -> state = State.GROUND
            State.GROUND -> ground(cp)
            State.ESC -> esc(cp)
            State.CSI -> csi(cp)
            State.OSC -> osc(cp)
            State.OSC_ESC -> oscEsc(cp)
        }
    }

    private fun ground(cp: Int) {
        when {
            cp == 0x1B -> state = State.ESC
            cp == 0x08 -> { if (cursorX > 0) cursorX-- }
            cp == 0x09 -> cursorX = ((cursorX / 8) + 1) * 8
            cp == 0x0A || cp == 0x0B || cp == 0x0C -> lineFeed()
            cp == 0x0D -> cursorX = 0
            cp == 0x0E || cp == 0x0F -> Unit
            cp == 0x07 -> Unit
            cp < 0x20 -> Unit
            else -> putChar(cp)
        }
    }

    private fun esc(cp: Int) {
        when (cp) {
            '['.code -> { csiParams.setLength(0); state = State.CSI }
            ']'.code -> { oscBuffer.setLength(0); state = State.OSC }
            '('.code, ')'.code, '#'.code, '*'.code, '+'.code, '-'.code, '.'.code, '/'.code,
            '%'.code, '$'.code -> state = State.IGNORE_ONE
            '7'.code -> { saved[0] = cursorX; saved[1] = cursorY; state = State.GROUND }
            '8'.code -> {
                cursorX = saved[0].coerceIn(0, cols - 1)
                cursorY = saved[1].coerceIn(0, rows - 1)
                state = State.GROUND
            }
            'D'.code -> { lineFeed(); state = State.GROUND }
            'E'.code -> { cursorX = 0; lineFeed(); state = State.GROUND }
            'M'.code -> { reverseLineFeed(); state = State.GROUND }
            'c'.code -> { reset(); state = State.GROUND }
            '='.code, '>'.code, 'H'.code, 'Z'.code -> state = State.GROUND
            else -> state = State.GROUND
        }
    }

    private fun csi(cp: Int) {
        when {
            cp in 0x30..0x3F -> csiParams.append(cp.toChar())
            cp in 0x20..0x2F -> Unit // intermediates: none matter for the subset
            cp in 0x40..0x7E -> {
                executeCsi(cp)
                state = State.GROUND
            }
        }
    }

    private fun osc(cp: Int) {
        when {
            cp == 0x07 -> { finishOsc(); state = State.GROUND }
            cp == 0x1B -> state = State.OSC_ESC
            else -> oscBuffer.append(cp.toChar())
        }
    }

    private fun oscEsc(cp: Int) {
        if (cp == '\\'.code) {
            finishOsc()
            state = State.GROUND
        } else {
            state = State.OSC
        }
    }

    private fun finishOsc() {
        val content = oscBuffer.toString()
        when {
            content.startsWith("0;") -> title = content.substring(2)
            content.startsWith("2;") -> title = content.substring(2)
            content == "0" || content == "2" -> title = null
        }
    }

    private fun csiParam(index: Int, default: Int): Int {
        val parts = csiParams.toString().split(';')
        val raw = parts.getOrNull(index)?.trim()
        return raw?.toIntOrNull() ?: default
    }

    private fun executeCsi(final: Int) {
        val marker = csiParams.firstOrNull()
        val isPrivate = marker == '?' || marker == '>' || marker == '!' || marker == '='
        val body = if (isPrivate) csiParams.substring(1) else csiParams.toString()
        fun p(index: Int, default: Int): Int {
            val parts = body.split(';')
            val raw = parts.getOrNull(index)?.trim()
            return raw?.toIntOrNull() ?: default
        }
        when (final) {
            'A'.code -> cursorUp(p(0, 1))
            'B'.code -> cursorDown(p(0, 1))
            'C'.code -> cursorRight(p(0, 1))
            'D'.code -> cursorLeft(p(0, 1))
            'E'.code -> nextLine(p(0, 1))
            'F'.code -> previousLine(p(0, 1))
            'G'.code -> cursorX = (p(0, 1) - 1).coerceIn(0, cols - 1)
            'H'.code, 'f'.code -> cursorPosition(p(0, 1), p(1, 1))
            'd'.code -> cursorY = (p(0, 1) - 1).coerceIn(0, rows - 1)
            '`'.code -> cursorX = (p(0, 1) - 1).coerceIn(0, cols - 1)
            'J'.code -> eraseDisplay(p(0, 0))
            'K'.code -> eraseLine(p(0, 0))
            'X'.code -> eraseChars(p(0, 1))
            '@'.code -> insertChars(p(0, 1))
            'P'.code -> deleteChars(p(0, 1))
            'L'.code -> insertLines(p(0, 1))
            'M'.code -> deleteLines(p(0, 1))
            'S'.code -> scrollUp(p(0, 1))
            'T'.code -> scrollDown(p(0, 1))
            'r'.code -> setScrollRegion(p(0, 1), p(1, rows))
            'm'.code -> sgr(body)
            'n'.code -> Unit // DSR — no cursor-position reply needed for a shell
            'h'.code -> setMode(body, true)
            'l'.code -> setMode(body, false)
            's'.code -> { saved[0] = cursorX; saved[1] = cursorY }
            'u'.code -> {
                cursorX = saved[0].coerceIn(0, cols - 1)
                cursorY = saved[1].coerceIn(0, rows - 1)
            }
            else -> Unit
        }
    }

    private fun cursorUp(n: Int) { cursorY = (cursorY - n).coerceAtLeast(0) }
    private fun cursorDown(n: Int) { cursorY = (cursorY + n).coerceAtMost(rows - 1) }
    private fun cursorRight(n: Int) { cursorX = (cursorX + n).coerceAtMost(cols - 1) }
    private fun cursorLeft(n: Int) { cursorX = (cursorX - n).coerceAtLeast(0) }
    private fun nextLine(n: Int) { cursorX = 0; repeat(n) { lineFeed() } }
    private fun previousLine(n: Int) { cursorX = 0; repeat(n) { cursorUp(1) } }

    private fun cursorPosition(row: Int, column: Int) {
        cursorY = (row - 1).coerceIn(0, rows - 1)
        cursorX = (column - 1).coerceIn(0, cols - 1)
    }

    private fun setScrollRegion(top: Int, bottom: Int) {
        scrollTop = (top - 1).coerceIn(0, rows - 1)
        scrollBottom = (bottom - 1).coerceIn(scrollTop, rows - 1)
        cursorX = 0
        cursorY = 0
    }

    private fun eraseLine(mode: Int) {
        val line = screen[cursorY]
        when (mode) {
            0 -> for (c in cursorX until cols) eraseCell(line, c)
            1 -> for (c in 0..cursorX) eraseCell(line, c)
            2 -> for (c in 0 until cols) eraseCell(line, c)
        }
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseLine(0)
                for (y in (cursorY + 1) until rows) for (c in 0 until cols) eraseCell(screen[y], c)
            }
            1 -> {
                eraseLine(1)
                for (y in 0 until cursorY) for (c in 0 until cols) eraseCell(screen[y], c)
            }
            2 -> for (y in 0 until rows) for (c in 0 until cols) eraseCell(screen[y], c)
            3 -> scrollback.clear()
        }
    }

    private fun eraseChars(n: Int) {
        val line = screen[cursorY]
        for (c in cursorX until (cursorX + n).coerceAtMost(cols)) eraseCell(line, c)
    }

    private fun insertChars(n: Int) {
        val line = screen[cursorY]
        val count = n.coerceAtMost(cols - cursorX)
        for (c in (cols - 1) downTo (cursorX + count)) {
            line[c] = line[c - count]
        }
        for (c in cursorX until (cursorX + count)) eraseCell(line, c)
    }

    private fun deleteChars(n: Int) {
        val line = screen[cursorY]
        val count = n.coerceAtMost(cols - cursorX)
        for (c in cursorX until (cols - count)) {
            line[c] = line[c + count]
        }
        for (c in (cols - count) until cols) eraseCell(line, c)
    }

    private fun insertLines(n: Int) {
        repeat(n.coerceAtLeast(1)) { scrollDownWithinRegion(1) }
    }

    private fun deleteLines(n: Int) {
        repeat(n.coerceAtLeast(1)) { scrollUpWithinRegion(1) }
    }

    private fun scrollUp(n: Int) {
        repeat(n.coerceAtLeast(1)) { scrollUpWithinRegion(1) }
    }

    private fun scrollDown(n: Int) {
        repeat(n.coerceAtLeast(1)) { scrollDownWithinRegion(1) }
    }

    private fun isFullScreenRegion(): Boolean = scrollTop == 0 && scrollBottom == rows - 1

    private fun scrollUpWithinRegion(count: Int) {
        repeat(count) {
            val out = screen.removeAt(scrollTop)
            if (isFullScreenRegion()) {
                scrollback.addLast(out)
                if (scrollback.size > MAX_SCROLLBACK) scrollback.removeFirst()
            }
            screen.add(scrollBottom, blankLine())
        }
    }

    private fun scrollDownWithinRegion(count: Int) {
        repeat(count) {
            screen.removeAt(scrollBottom)
            screen.add(scrollTop, blankLine())
        }
    }

    private fun lineFeed() {
        if (cursorY == scrollBottom) {
            scrollUpWithinRegion(1)
        } else {
            cursorY++
        }
    }

    private fun reverseLineFeed() {
        if (cursorY == scrollTop) {
            scrollDownWithinRegion(1)
        } else {
            cursorY--
        }
    }

    private fun putChar(cp: Int) {
        val width = terminalCharWidth(cp)
        if (width == 0) {
            if (cursorX > 0) {
                val cell = screen[cursorY][cursorX - 1]
                if (cell.text.isNotEmpty()) cell.text += String(Character.toChars(cp))
            }
            return
        }
        if (width == 2) {
            if (cursorX >= cols - 1) {
                wrapLine()
            }
            val line = screen[cursorY]
            line[cursorX].apply {
                text = String(Character.toChars(cp))
                fg = curFg
                bg = curBg
                flags = curFlags
                wide = true
                cont = false
            }
            line[cursorX + 1].apply {
                text = ""
                fg = curFg
                bg = curBg
                flags = curFlags
                wide = false
                cont = true
            }
            cursorX += 2
        } else {
            screen[cursorY][cursorX].apply {
                text = String(Character.toChars(cp))
                fg = curFg
                bg = curBg
                flags = curFlags
                wide = false
                cont = false
            }
            cursorX++
        }
        if (cursorX >= cols) {
            wrapLine()
        }
    }

    private fun wrapLine() {
        cursorX = 0
        lineFeed()
    }

    private fun setMode(body: String, enabled: Boolean) {
        when (body) {
            "25" -> cursorVisible = enabled
            else -> Unit
        }
    }

    private fun sgr(body: String) {
        if (body.isEmpty()) {
            resetAttributes()
            return
        }
        val parts = body.split(';')
        var i = 0
        while (i < parts.size) {
            val code = parts[i].trim().toIntOrNull() ?: 0
            when {
                code == 0 -> resetAttributes()
                code == 1 -> curFlags = curFlags or TermFlags.BOLD
                code == 2 -> curFlags = curFlags or TermFlags.DIM
                code == 3 -> curFlags = curFlags or TermFlags.ITALIC
                code == 4 -> curFlags = curFlags or TermFlags.UNDERLINE
                code == 7 -> curFlags = curFlags or TermFlags.REVERSED
                code == 8 -> curFlags = curFlags or TermFlags.HIDDEN
                code == 9 -> curFlags = curFlags or TermFlags.STRUCK
                code == 22 -> curFlags = curFlags and (TermFlags.BOLD or TermFlags.DIM).inv()
                code == 23 -> curFlags = curFlags and TermFlags.ITALIC.inv()
                code == 24 -> curFlags = curFlags and TermFlags.UNDERLINE.inv()
                code == 27 -> curFlags = curFlags and TermFlags.REVERSED.inv()
                code == 28 -> curFlags = curFlags and TermFlags.HIDDEN.inv()
                code == 29 -> curFlags = curFlags and TermFlags.STRUCK.inv()
                code in 30..37 -> curFg = palette.colors[code - 30]
                code == 38 -> {
                    val ext = parseExtendedColour(parts, i + 1)
                    ext.colour?.let { curFg = it }
                    i += ext.consumed
                }
                code == 39 -> curFg = palette.foreground
                code in 40..47 -> curBg = palette.colors[code - 40]
                code == 48 -> {
                    val ext = parseExtendedColour(parts, i + 1)
                    ext.colour?.let { curBg = it }
                    i += ext.consumed
                }
                code == 49 -> curBg = palette.background
                code in 90..97 -> curFg = palette.colors[8 + (code - 90)]
                code in 100..107 -> curBg = palette.colors[8 + (code - 100)]
            }
            i++
        }
    }

    private class ExtendedColour(val colour: Int?, val consumed: Int)

    private fun parseExtendedColour(parts: List<String>, start: Int): ExtendedColour {
        val mode = parts.getOrNull(start)?.trim()?.toIntOrNull()
        return when (mode) {
            5 -> {
                val index = parts.getOrNull(start + 1)?.trim()?.toIntOrNull()
                if (index != null) ExtendedColour(xterm256(index, palette.colors), 2)
                else ExtendedColour(null, 1)
            }
            2 -> {
                val r = parts.getOrNull(start + 1)?.trim()?.toIntOrNull()
                val g = parts.getOrNull(start + 2)?.trim()?.toIntOrNull()
                val b = parts.getOrNull(start + 3)?.trim()?.toIntOrNull()
                if (r != null && g != null && b != null) {
                    ExtendedColour((0xFF shl 24) or (r shl 16) or (g shl 8) or b, 4)
                } else {
                    ExtendedColour(null, 1)
                }
            }
            else -> ExtendedColour(null, 1)
        }
    }

    private fun resetAttributes() {
        curFg = palette.foreground
        curBg = palette.background
        curFlags = 0
    }

    private fun reset() {
        cursorX = 0
        cursorY = 0
        scrollTop = 0
        scrollBottom = rows - 1
        cursorVisible = true
        resetAttributes()
        screen.clear()
        for (i in 0 until rows) screen.addLast(blankLine())
    }

    /**
     * Reshape the grid. A rows-only change keeps every line and colour — the
     * visible window simply grows or shrinks (the keyboard case). A width
     * change re-flows the text to the new columns; colours on re-flowed lines
     * fall back to the defaults (rotation, rare on a portrait phone).
     */
    fun resize(newCols: Int, newRows: Int) {
        val nc = newCols.coerceAtLeast(2)
        val nr = newRows.coerceAtLeast(1)
        if (nc == cols && nr == rows) return
        if (nc != cols) {
            reflow(nc, nr)
        } else {
            while (screen.size > nr) {
                scrollback.addLast(screen.removeFirst())
                if (scrollback.size > MAX_SCROLLBACK) scrollback.removeFirst()
            }
            while (screen.size < nr) {
                val line = scrollback.removeLastOrNull() ?: blankLine()
                screen.addFirst(line)
            }
            rows = nr
            scrollTop = 0
            scrollBottom = rows - 1
            cursorY = cursorY.coerceAtMost(rows - 1)
            cursorX = cursorX.coerceAtMost(cols - 1)
        }
    }

    private fun reflow(nc: Int, nr: Int) {
        val allLines = (scrollback.toList() + screen.toList()).map { line -> lineText(line) }
        cols = nc
        rows = nr
        scrollback.clear()
        screen.clear()
        val reflowed = mutableListOf<String>()
        for (text in allLines) {
            reflowed.addAll(wrapText(text, nc))
        }
        val visible = reflowed.takeLast(nr)
        val older = reflowed.dropLast(nr).takeLast(MAX_SCROLLBACK)
        for (text in older) scrollback.addLast(lineFromText(text))
        for (text in visible) screen.addLast(lineFromText(text))
        while (screen.size < nr) screen.addLast(blankLine())
        cursorX = 0
        cursorY = 0
        scrollTop = 0
        scrollBottom = rows - 1
    }

    private fun lineText(line: TermLine): String {
        val builder = StringBuilder()
        for (cell in line) {
            if (cell.cont) continue
            if (cell.text.isEmpty()) builder.append(' ')
            else builder.append(cell.text)
        }
        return builder.toString().trimEnd()
    }

    private fun wrapText(text: String, width: Int): List<String> {
        if (text.length <= width) return listOf(text)
        return text.chunked(width)
    }

    private fun lineFromText(text: String): TermLine {
        val line = blankLine()
        var column = 0
        var offset = 0
        while (offset < text.length && column < cols) {
            val cp = text.codePointAt(offset)
            val width = terminalCharWidth(cp)
            if (width == 2 && column >= cols - 1) break
            line[column].text = String(Character.toChars(cp))
            line[column].wide = width == 2
            column++
            if (width == 2) {
                line[column].cont = true
                column++
            }
            offset += Character.charCount(cp)
        }
        return line
    }
}
