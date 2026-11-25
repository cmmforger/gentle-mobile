package com.gentlemanprogramming.gentlemobile.ui.session

/** The two input modes (T57): where typed characters go — the composer
 *  buffers and sends on tap, the direct mode types straight into the PTY. */
enum class InputMode { COMPOSER, DIRECT }

/**
 * The terminal's bottom bar (T57): the fixed quick chips, the keys panel
 * contents derived from the pad, and the input-mode mapping. Pure so the bar
 * and panel are pinned by a test, never read from the composable.
 */
object TerminalKeyBar {

    /** The always-visible bar chips: Ctrl (sticky), Esc, Tab — Moshi's row. */
    fun barChips(): List<ShortcutKey> = listOf(
        ShortcutKey("ctrl", "Ctrl", ShortcutAction.Key("ctrl")),
        ShortcutKey("esc", "Esc", ShortcutAction.Key("esc")),
        ShortcutKey("tab", "Tab", ShortcutAction.Key("tab")),
    )

    /** The joystick cross the keys icon opens: Enter + the four arrows,
     *  never a permanent cross on the screen. */
    fun joystickKeys(): List<ShortcutKey> = ShortcutPad.JOYSTICK

    /** The extra keys the panel always carries: Delete and Clear. */
    fun extraKeys(): List<ShortcutKey> = listOf(
        ShortcutKey("delete", "Delete", ShortcutAction.ControlByte(0x08)),
        ShortcutKey("clear", "Clear", ShortcutAction.ControlByte(0x0C)),
    )

    /** The pad's keys shown in the panel: the human's enabled keys, in their
     *  order, minus what the bar and the fixed clusters already provide. */
    fun padKeys(pad: ShortcutPad): List<ShortcutKey> {
        val provided = (barChips() + joystickKeys())
            .mapNotNull { (it.action as? ShortcutAction.Key)?.name }
            .toSet()
        return pad.visibleKeys.filterNot { key ->
            (key.action as? ShortcutAction.Key)?.name in provided
        }
    }

    /** The whole panel, in paint order: the cross, Delete and Clear, then the
     *  pad's own keys. */
    fun panelKeys(pad: ShortcutPad): List<ShortcutKey> =
        joystickKeys() + extraKeys() + padKeys(pad)

    /** The persisted `useComposer` setting is the input mode: on → composer,
     *  off → direct. */
    fun inputMode(useComposer: Boolean): InputMode =
        if (useComposer) InputMode.COMPOSER else InputMode.DIRECT

    fun useComposer(mode: InputMode): Boolean = mode == InputMode.COMPOSER
}
