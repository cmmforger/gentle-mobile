package com.gentlemanprogramming.gentlemobile.ui.session

/** The two input modes (T59): where typed characters go — the bubble opens
 *  the composer (a field that sends on tap), the keyboard types each
 *  keystroke straight into the PTY. */
enum class InputMode { COMPOSER, DIRECT }

/**
 * The key bar (T59): one fixed row always above the keyboard, never a text
 *  field. `Ctrl · Esc · Tab · [arrows] · [lines] · [clipboard] · [bubble] ·
 *  [keyboard]`, each a 44 dp target. Pure so the bar, the pad and the mode
 *  transitions are pinned by a test, never read from the composable.
 */
object TerminalKeyBar {

    /** The bar's eight fixed items, in their left-to-right order. */
    enum class Item { CTRL, ESC, TAB, ARROWS, LINES, CLIPBOARD, BUBBLE, KEYBOARD }

    fun barOrder(): List<Item> = listOf(
        Item.CTRL,
        Item.ESC,
        Item.TAB,
        Item.ARROWS,
        Item.LINES,
        Item.CLIPBOARD,
        Item.BUBBLE,
        Item.KEYBOARD,
    )

    /** The bar's three quick chips: Ctrl (sticky), Esc, Tab. */
    fun barChips(): List<ShortcutKey> = listOf(
        ShortcutKey("ctrl", "Ctrl", ShortcutAction.Key("ctrl")),
        ShortcutKey("esc", "Esc", ShortcutAction.Key("esc")),
        ShortcutKey("tab", "Tab", ShortcutAction.Key("tab")),
    )

    /** The floating pad's fixed cross, in paint order (row-major): backspace,
     *  up, clear, left, Enter, right, down — the seven big keys, never the
     *  pad configuration. */
    fun padKeys(): List<ShortcutKey> = listOf(
        ShortcutKey("pad_backspace", "\u232B", ShortcutAction.ControlByte(0x7F)),
        ShortcutKey("pad_up", "\u2191", ShortcutAction.Key("up")),
        ShortcutKey("pad_clear", "\u2327", ShortcutAction.ControlByte(0x0C)),
        ShortcutKey("pad_left", "\u2190", ShortcutAction.Key("left")),
        ShortcutKey("pad_enter", "\u21B5", ShortcutAction.Key("enter")),
        ShortcutKey("pad_right", "\u2192", ShortcutAction.Key("right")),
        ShortcutKey("pad_down", "\u2193", ShortcutAction.Key("down")),
    )

    /** The inline row's keys: the shortcut pad's own keys, in their stored
     *  order and visibility — the row that existed before the bar grew. */
    fun inlineKeys(pad: ShortcutPad): List<ShortcutKey> = pad.visibleKeys

    /** The attachment sheet's three rows, in order (T59). */
    enum class AttachmentKind { CAMERA, GALLERY, FILE }

    fun attachmentRows(): List<AttachmentKind> = listOf(
        AttachmentKind.CAMERA,
        AttachmentKind.GALLERY,
        AttachmentKind.FILE,
    )

    /** The composer field is visible only in composer mode (the bubble);
     *  direct mode (the keyboard) types straight into the PTY with no field. */
    fun showsComposerField(mode: InputMode): Boolean = mode == InputMode.COMPOSER
}

/**
 * The key bar's runtime surfaces and the pad's lock/drag state (T59).
 * `useComposer` is the persisted input-mode default the bubble/keyboard flip;
 * `padOpen` and `linesOpen` are the two key surfaces the arrows and lines
 * icons toggle; `padLocked` keeps the pad in place or lets it be dragged
 * away and dismissed.
 */
data class KeyBarState(
    val useComposer: Boolean = false,
    val padOpen: Boolean = false,
    val linesOpen: Boolean = false,
    val padLocked: Boolean = false,
) {
    /** The surfaces the state paints: the field, the floating pad, the
     *  inline row — any of them, none, or several at once. */
    enum class Surface { FIELD, PAD, INLINE }

    fun visibleSurfaces(): Set<Surface> = buildSet {
        if (useComposer) add(Surface.FIELD)
        if (padOpen) add(Surface.PAD)
        if (linesOpen) add(Surface.INLINE)
    }

    /** The bubble: show the composer field, or hide it again. */
    fun tapBubble(): KeyBarState = copy(useComposer = !useComposer)

    /** The keyboard: direct mode — the field closes and the phone keyboard
     *  takes over the typing. */
    fun tapKeyboard(): KeyBarState = copy(useComposer = false)

    /** The four-arrow icon: open or close the floating pad. */
    fun tapArrows(): KeyBarState = copy(padOpen = !padOpen)

    /** The lines icon: toggle the inline row of the pad's own keys. */
    fun tapLines(): KeyBarState = copy(linesOpen = !linesOpen)

    /** The pad's lock toggle: locked stays, unlocked can be dragged. */
    fun tapLock(): KeyBarState = copy(padLocked = !padLocked)

    /** Dragging the pad's handle away to dismiss it: an unlocked pad closes,
     *  a locked pad stays exactly where it is. */
    fun dragAway(): KeyBarState = if (padLocked) this else copy(padOpen = false)
}
