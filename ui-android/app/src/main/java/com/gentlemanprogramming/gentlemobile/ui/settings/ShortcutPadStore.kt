package com.gentlemanprogramming.gentlemobile.ui.settings

import com.gentlemanprogramming.gentlemobile.ui.session.ShortcutAction
import com.gentlemanprogramming.gentlemobile.ui.session.ShortcutKey
import com.gentlemanprogramming.gentlemobile.ui.session.ShortcutPad
import com.gentlemanprogramming.gentlemobile.ui.theme.KeyValueStore

/**
 * The persisted shortcut pad (T52). The pad lives as one compact string over
 * the same [KeyValueStore] every preference uses; the joystick flag is a
 * separate key. A missing or garbage value falls back to today's defaults.
 */
class ShortcutPadStore(private val store: KeyValueStore) {

    fun load(): ShortcutPad {
        val joystick = store.getString(KEY_JOYSTICK) == "true"
        val encoded = store.getString(KEY_PAD) ?: return ShortcutPad.DEFAULT
        return try {
            decode(encoded, joystick)
        } catch (_: Exception) {
            ShortcutPad.DEFAULT
        }
    }

    fun save(pad: ShortcutPad) {
        store.putString(KEY_JOYSTICK, pad.joystickVisible.toString())
        store.putString(KEY_PAD, encode(pad))
    }

    companion object {
        const val KEY_PAD = "shortcut_pad"
        const val KEY_JOYSTICK = "shortcut_joystick"

        fun encode(pad: ShortcutPad): String = pad.keys.joinToString("\n") { encodeKey(it) }

        fun decode(encoded: String, joystickVisible: Boolean): ShortcutPad = ShortcutPad(
            keys = encoded.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { decodeKey(it) },
            joystickVisible = joystickVisible,
        )

        private fun encodeKey(key: ShortcutKey): String {
            val (type, arg1, arg2) = when (val action = key.action) {
                is ShortcutAction.Key -> Triple("K", action.name, "")
                is ShortcutAction.ControlByte -> Triple("C", action.byte.toString(), "")
                is ShortcutAction.Text -> Triple("T", action.text, if (action.sendEnter) "1" else "0")
            }
            return listOf(
                escape(key.id),
                escape(key.label),
                type,
                escape(arg1),
                escape(arg2),
                if (key.visible) "1" else "0",
            ).joinToString("|")
        }

        private fun decodeKey(line: String): ShortcutKey {
            val parts = splitUnescaped(line)
            require(parts.size == 6) { "bad pad key: $line" }
            val id = unescape(parts[0])
            val label = unescape(parts[1])
            val visible = parts[5] == "1"
            val action = when (parts[2]) {
                "K" -> ShortcutAction.Key(unescape(parts[3]))
                "C" -> ShortcutAction.ControlByte(parts[3].toInt())
                "T" -> ShortcutAction.Text(unescape(parts[3]), parts[4] == "1")
                else -> throw IllegalArgumentException("unknown pad action: ${parts[2]}")
            }
            return ShortcutKey(id, label, action, visible)
        }

        private fun escape(value: String): String = value
            .replace("\\", "\\\\")
            .replace("|", "\\|")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

        private fun unescape(value: String): String {
            val out = StringBuilder(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '\\' && i + 1 < value.length) {
                    when (value[i + 1]) {
                        '\\' -> out.append('\\')
                        '|' -> out.append('|')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        else -> out.append(c).append(value[i + 1])
                    }
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }

        /** Splits a stored key line on `|` that is not escaped. */
        private fun splitUnescaped(line: String): List<String> {
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            var i = 0
            while (i < line.length) {
                val c = line[i]
                if (c == '\\' && i + 1 < line.length) {
                    current.append(c).append(line[i + 1])
                    i += 2
                } else if (c == '|') {
                    parts.add(current.toString())
                    current.setLength(0)
                    i++
                } else {
                    current.append(c)
                    i++
                }
            }
            parts.add(current.toString())
            return parts
        }
    }
}