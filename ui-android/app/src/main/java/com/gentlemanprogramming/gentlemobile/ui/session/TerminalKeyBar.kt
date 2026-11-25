package com.gentlemanprogramming.gentlemobile.ui.session

/** The two input modes (T59): where typed characters go — the bubble opens
 *  the composer (a field that sends on tap), the keyboard types each
 *  keystroke straight into the PTY. */
enum class InputMode { COMPOSER, DIRECT }

/**
 * The key bar (T59): one fixed row always above the keyboard, never a text
 *  field. `Ctrl · Esc · Tab · [arrows] · [branches] · [clipboard] · [bubble] ·
 *  [keyboard]`, each a 44 dp target. Pure so the bar, the pad and the mode
 *  transitions are pinned by a test, never read from the composable.
 */
object TerminalKeyBar {

    /** The bar's eight fixed items, in their left-to-right order. */
    enum class Item { CTRL, ESC, TAB, ARROWS, BRANCHES, CLIPBOARD, BUBBLE, KEYBOARD }

    fun barOrder(): List<Item> = listOf(
        Item.CTRL,
        Item.ESC,
        Item.TAB,
        Item.ARROWS,
        Item.BRANCHES,
        Item.CLIPBOARD,
        Item.BUBBLE,
        Item.KEYBOARD,
    )

    /** The six icon shapes the reference names (T60): the bar's five plus
     *  the floating pad's brush — each a filled vector in the theme's
     *  colours, a 44 dp target, legible at 20 dp. */
    enum class Icon { ARROWS, BRANCHES, CLIPBOARD, BUBBLE, KEYBOARD, BRUSH }

    /** The bar's five icons in their left-to-right order. */
    fun barIcons(): List<Icon> = listOf(
        Icon.ARROWS,
        Icon.BRANCHES,
        Icon.CLIPBOARD,
        Icon.BUBBLE,
        Icon.KEYBOARD,
    )

    /** The full icon set, bar icons first then the pad's brush. */
    fun iconSet(): List<Icon> = barIcons() + Icon.BRUSH

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

    /** The field's reserved height in dp (T60): the composer's field is 44 dp
     *  tall; direct mode reserves nothing — its invisible field is zero
     *  height, so no blank band remains under the bar. */
    fun fieldReservedDp(mode: InputMode): Int = if (mode == InputMode.COMPOSER) 44 else 0
}

/**
 * The key bar's runtime surfaces and the pad's lock/drag state (T59/T60).
 * `useComposer` is the persisted input-mode default the bubble/keyboard flip;
 * `padOpen` and `sidebarOpen` are the two key surfaces the arrows and
 * branches icons toggle; `padLocked` keeps the pad pinned or lets it be
 * dragged away and dismissed; `padAnchor` is where the pad floats — just
 * above the bar by default, never the top of the screen.
 */
data class KeyBarState(
    val useComposer: Boolean = false,
    val padOpen: Boolean = false,
    val sidebarOpen: Boolean = false,
    val padLocked: Boolean = false,
    val padAnchor: PadAnchor = PadAnchor.ABOVE_BAR,
) {
    /** The surfaces the state paints: the field, the floating pad, the
     *  sidebar — any of them, none, or several at once. */
    enum class Surface { FIELD, PAD, SIDEBAR }

    /** Where the floating pad anchors over the terminal. */
    enum class PadAnchor { TOP, ABOVE_BAR }

    fun visibleSurfaces(): Set<Surface> = buildSet {
        if (useComposer) add(Surface.FIELD)
        if (padOpen) add(Surface.PAD)
        if (sidebarOpen) add(Surface.SIDEBAR)
    }

    /** The bubble: show the composer field, or hide it again. */
    fun tapBubble(): KeyBarState = copy(useComposer = !useComposer)

    /** The keyboard: direct mode — the field closes and the phone keyboard
     *  takes over the typing. */
    fun tapKeyboard(): KeyBarState = copy(useComposer = false)

    /** The four-arrow icon: open or close the floating pad. */
    fun tapArrows(): KeyBarState = copy(padOpen = !padOpen)

    /** The branches icon: open or close the workspaces sidebar. */
    fun tapBranches(): KeyBarState = copy(sidebarOpen = !sidebarOpen)

    /** The pad's lock toggle: locked stays, unlocked can be dragged. */
    fun tapLock(): KeyBarState = copy(padLocked = !padLocked)

    /** Dragging the pad's handle away to dismiss it: an unlocked pad closes,
     *  a locked pad stays exactly where it is. */
    fun dragAway(): KeyBarState = if (padLocked) this else copy(padOpen = false)
}
