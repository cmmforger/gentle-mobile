package com.gentlemanprogramming.gentlemobile.ui.terminal

/**
 * The key row's byte mapping for the real terminal (T42): Esc, Tab and the
 * arrows are VT escape sequences; Ctrl/Alt stick and modify the next
 * momentary key. The phone's soft keyboard sends typed characters directly,
 * so this row only carries what a screen keyboard cannot.
 */
object TerminalKeys {

    fun bytesFor(key: String, ctrl: Boolean, alt: Boolean): ByteArray = when (key) {
        "esc" -> byteArrayOf(0x1B)
        "tab" -> byteArrayOf(0x09)
        "c" -> if (ctrl) byteArrayOf(0x03) else byteArrayOf(0x63)
        "up" -> arrow('A', ctrl, alt)
        "down" -> arrow('B', ctrl, alt)
        else -> byteArrayOf()
    }

    private fun arrow(final: Char, ctrl: Boolean, alt: Boolean): ByteArray {
        val modifier = when {
            ctrl -> "1;5"
            alt -> "1;3"
            else -> ""
        }
        val seq = if (modifier.isEmpty()) {
            "\u001B[$final"
        } else {
            "\u001B[$modifier$final"
        }
        return seq.toByteArray(Charsets.UTF_8)
    }

    /** The byte delta from one input-field value to the next, so keystrokes
     *  reach the PTY the moment they are typed: appended text is sent as-is
     *  (Enter mapped to CR), deletions send one backspace each. */
    fun forwardInput(old: String, new: String): ByteArray = when {
        new.length > old.length && new.startsWith(old) -> encode(new.substring(old.length))
        old.length > new.length && old.startsWith(new) -> ByteArray(old.length - new.length) { 0x08 }
        else -> ByteArray(old.length) { 0x08 } + encode(new)
    }

    private fun encode(text: String): ByteArray =
        text.replace("\n", "\r").toByteArray(Charsets.UTF_8)
}

/**
 * The composer's commit tracking (T42): each IME commit is a cumulative
 * value, so only the delta since the last commit may reach the PTY — never
 * the whole string again. Enter (a newline) sends CR and clears, so the next
 * command starts fresh.
 */
class TerminalComposer {
    private var last = ""

    fun commit(new: String): ByteArray {
        val delta = TerminalKeys.forwardInput(last, new)
        last = if (new.contains('\n')) "" else new
        return delta
    }
}
