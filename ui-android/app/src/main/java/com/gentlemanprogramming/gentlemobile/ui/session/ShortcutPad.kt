package com.gentlemanprogramming.gentlemobile.ui.session

import com.gentlemanprogramming.gentlemobile.ui.terminal.TerminalKeys

/** What one key of the pad does when it is pressed. */
sealed interface ShortcutAction {
    /** A named terminal key: `esc`, `tab`, `enter`, the arrows, or the
     *  sticky modifiers `ctrl` and `alt`. */
    data class Key(val name: String) : ShortcutAction

    /** An explicit control byte — Ctrl+C is 0x03, Ctrl+V is 0x16. */
    data class ControlByte(val byte: Int) : ShortcutAction

    /** Arbitrary text, optionally followed by Enter (one CR, 0x0D). */
    data class Text(val text: String, val sendEnter: Boolean) : ShortcutAction
}

/** One key of the pad: its identity, the label the chip paints, what it
 *  sends, and whether it shows. Hidden keys keep their place in the list
 *  so re-enabling returns them to the same spot. */
data class ShortcutKey(
    val id: String,
    val label: String,
    val action: ShortcutAction,
    val visible: Boolean = true,
)

/**
 * The shortcut pad (T52): the single source of truth for the terminal's key
 * row. An ordered list of keys plus the joystick cluster's on/off. Everything
 * the terminal draws comes from here — order, visibility, joystick — so the
 * settings screen and the terminal can never disagree.
 */
data class ShortcutPad(
    val keys: List<ShortcutKey> = emptyList(),
    val joystickVisible: Boolean = false,
) {
    /** The keys the pad actually shows, in their stored order. */
    val visibleKeys: List<ShortcutKey> get() = keys.filter { it.visible }

    fun withJoystick(visible: Boolean): ShortcutPad = copy(joystickVisible = visible)

    fun setKeyVisible(id: String, visible: Boolean): ShortcutPad =
        copy(keys = keys.map { if (it.id == id) it.copy(visible = visible) else it })

    /** Move the key [id] to [toIndex] in the full list; the other keys keep
     *  their relative order and out-of-range indices clamp to the ends. */
    fun moveKey(id: String, toIndex: Int): ShortcutPad {
        val from = keys.indexOfFirst { it.id == id }
        if (from < 0) return this
        val mover = keys[from]
        val rest = keys.toMutableList().apply { removeAt(from) }
        rest.add(toIndex.coerceIn(0, rest.size), mover)
        return copy(keys = rest)
    }

    fun addKey(key: ShortcutKey): ShortcutPad = copy(keys = keys + key)

    fun removeKey(id: String): ShortcutPad = copy(keys = keys.filterNot { it.id == id })

    /** Swap the places of two keys in the full list — the drag handle's
     *  reorder step between two visible neighbours. */
    fun swapWith(id: String, otherId: String): ShortcutPad {
        val a = keys.indexOfFirst { it.id == id }
        val b = keys.indexOfFirst { it.id == otherId }
        if (a < 0 || b < 0 || a == b) return this
        val reordered = keys.toMutableList()
        val tmp = reordered[a]
        reordered[a] = reordered[b]
        reordered[b] = tmp
        return copy(keys = reordered)
    }

    /** Whether the key is a sticky modifier — Ctrl or Alt arms the next
     *  key borrow, everything else is a one-shot action. */
    fun isSticky(key: ShortcutKey): Boolean {
        val name = (key.action as? ShortcutAction.Key)?.name
        return name == "ctrl" || name == "alt"
    }

    companion object {
        /** The built-in keys, present from first launch. */
        val BUILT_IN_IDS: Set<String> = setOf("esc", "tab", "ctrl", "alt", "up", "down")

        /** The defaults keep today's row — Esc, Tab, Ctrl, Alt, up, down —
         *  in today's order, with the joystick off, so nothing changes for
         *  someone who never opens the screen. */
        val DEFAULT: ShortcutPad = ShortcutPad(
            keys = listOf(
                ShortcutKey("esc", "Esc", ShortcutAction.Key("esc")),
                ShortcutKey("tab", "Tab", ShortcutAction.Key("tab")),
                ShortcutKey("ctrl", "Ctrl", ShortcutAction.Key("ctrl")),
                ShortcutKey("alt", "Alt", ShortcutAction.Key("alt")),
                ShortcutKey("up", "\u2191", ShortcutAction.Key("up")),
                ShortcutKey("down", "\u2193", ShortcutAction.Key("down")),
            ),
            joystickVisible = false,
        )

        /** The joystick's fixed cluster: up, left, Enter, right, down. */
        val JOYSTICK: List<ShortcutKey> = listOf(
            ShortcutKey("joy_up", "\u2191", ShortcutAction.Key("up")),
            ShortcutKey("joy_left", "\u2190", ShortcutAction.Key("left")),
            ShortcutKey("joy_enter", "\u21B5", ShortcutAction.Key("enter")),
            ShortcutKey("joy_right", "\u2192", ShortcutAction.Key("right")),
            ShortcutKey("joy_down", "\u2193", ShortcutAction.Key("down")),
        )
    }
}

/** The bytes one key's action sends. Named keys go through the key table
 *  (where the armed modifiers compose); a control byte and text are whole
 *  actions of their own and ignore the modifiers. */
fun ShortcutKey.bytes(ctrl: Boolean, alt: Boolean): ByteArray = when (val a = action) {
    is ShortcutAction.Text -> {
        val body = a.text.toByteArray(Charsets.UTF_8)
        if (a.sendEnter) body + byteArrayOf(0x0D) else body
    }
    is ShortcutAction.ControlByte -> byteArrayOf(a.byte.toByte())
    is ShortcutAction.Key -> TerminalKeys.bytesFor(a.name, ctrl, alt)
}