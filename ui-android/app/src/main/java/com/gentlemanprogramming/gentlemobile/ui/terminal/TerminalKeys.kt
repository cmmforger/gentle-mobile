package com.gentlemanprogramming.gentlemobile.ui.terminal

/**
 * The byte mapping for the real terminal's input (T46): Esc, Tab and the
 * arrows are VT escape sequences; Ctrl/Alt are sticky and modify the next
 * action — a key-row chip or a character typed on the phone's keyboard.
 */
object TerminalKeys {

    fun bytesFor(key: String, ctrl: Boolean, alt: Boolean): ByteArray = when (key) {
        "esc" -> byteArrayOf(0x1B)
        "tab" -> byteArrayOf(0x09)
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

    /** The control byte for a Ctrl-composed ASCII character, or null when
     *  the character has no Ctrl form (a digit, for example). */
    fun ctrlControlByte(cp: Int): Byte? = when (cp) {
        0x20 -> 0x00
        0x3F -> 0x7F
        in 0x40..0x5F -> (cp and 0x1F).toByte()
        in 0x61..0x7A -> (cp and 0x1F).toByte()
        else -> null
    }

    /** One typed character under the armed modifiers: Ctrl composes the
     *  control byte, Alt prefixes ESC, otherwise the character is verbatim. */
    fun typedCharBytes(cp: Int, ctrl: Boolean, alt: Boolean): ByteArray {
        val ch = String(Character.toChars(cp))
        return when {
            ctrl -> ctrlControlByte(cp)?.let { byteArrayOf(it) } ?: ch.toByteArray(Charsets.UTF_8)
            alt -> byteArrayOf(0x1B) + ch.toByteArray(Charsets.UTF_8)
            else -> ch.toByteArray(Charsets.UTF_8)
        }
    }

    /** The modifiers apply to the first character of appended text; the
     *  rest (a paste) travels verbatim. */
    fun typedWithModifiers(appended: String, ctrl: Boolean, alt: Boolean): ByteArray {
        if (appended.isEmpty()) return ByteArray(0)
        if (!ctrl && !alt) return appended.toByteArray(Charsets.UTF_8)
        val first = appended.codePointAt(0)
        val head = typedCharBytes(first, ctrl, alt)
        val tail = appended.substring(Character.charCount(first)).toByteArray(Charsets.UTF_8)
        return head + tail
    }

    /** The byte delta from one input-field value to the next, so keystrokes
     *  reach the PTY the moment they are typed: appended text is sent as-is
     *  (a newline mapped to CR), deletions send one backspace each, and an
     *  unchanged value sends nothing. */
    fun forwardInput(old: String, new: String): ByteArray = when {
        new == old -> ByteArray(0)
        new.length > old.length && new.startsWith(old) -> encode(new.substring(old.length))
        old.length > new.length && old.startsWith(new) -> ByteArray(old.length - new.length) { 0x08 }
        else -> ByteArray(old.length) { 0x08 } + encode(new)
    }

    private fun encode(text: String): ByteArray =
        text.replace("\n", "\r").toByteArray(Charsets.UTF_8)
}

/**
 * The composer owns the terminal's submission (T46): the field's cumulative
 * value plus the armed Ctrl/Alt modifiers. Only the delta between two field
 * values ever reaches the PTY, so a committed string is never sent again;
 * Enter is a separate [submit] that sends exactly one CR and clears. A
 * typed character, like a composed key-row chip, disarms the modifiers.
 */
class TerminalComposer {
    private var last = ""

    var ctrlSticky = false
        private set
    var altSticky = false
        private set

    /** The text the field should display. A consumed Ctrl/Alt character is
     *  a control action, not text, so it is never mirrored. */
    val fieldText: String get() = last

    /** Arming one modifier disarms the other. */
    fun toggleCtrl() {
        ctrlSticky = !ctrlSticky
        if (ctrlSticky) altSticky = false
    }

    fun toggleAlt() {
        altSticky = !altSticky
        if (altSticky) ctrlSticky = false
    }

    fun disarm() {
        ctrlSticky = false
        altSticky = false
    }

    /** The bytes for one field-value change: appended text is typed (the
     *  armed modifiers apply to its first character and disarm), deletions
     *  and replacements send backspaces plus the new text. */
    fun commit(new: String): ByteArray {
        val appended = new.length > last.length && new.startsWith(last)
        val armed = ctrlSticky || altSticky
        val bytes = if (appended) {
            TerminalKeys.typedWithModifiers(new.substring(last.length), ctrlSticky, altSticky)
        } else {
            TerminalKeys.forwardInput(last, new)
        }
        if (appended && armed && bytes.isNotEmpty()) {
            disarm()
            last = ""
        } else {
            last = new
        }
        return bytes
    }

    /** Enter (the send button or the keyboard's send action): one CR, then
     *  the field is cleared. */
    fun submit(): ByteArray {
        disarm()
        last = ""
        return byteArrayOf(0x0D)
    }
}
