package com.gentlemanprogramming.gentlemobile.ui.session

/**
 * The key row's label decision (T39). Every chip reads clearly: `Esc` and
 * `Tab` keep their words, `Ctrl` and `Alt` are the sticky toggles and say
 * so, the arrows are their glyphs (`↑`, `↓`). Enter has no chip: the phone's
 * own keyboard has one, and the row's Enter read as a mystery symbol next to
 * the composer's send action. No chip carries a word the row's width ever has
 * to truncate — the old row asked for `Enter` and the phone drew `E`.
 */
object ExtraKeyRowModel {

    /** A momentary key sends once per tap; a sticky key toggles and the
     *  next key composes with it. */
    enum class Kind { MOMENTARY, STICKY }

    /** One chip of the key row, in the row's own order. */
    data class Chip(val id: String, val label: String, val kind: Kind)

    /** The row's chips: sticky toggles keep their word, momentary keys
     *  either keep their short word (`Esc`, `Tab`) or their glyph. */
    fun chips(): List<Chip> = listOf(
        Chip("esc", "Esc", Kind.MOMENTARY),
        Chip("tab", "Tab", Kind.MOMENTARY),
        Chip("ctrl", "Ctrl", Kind.STICKY),
        Chip("alt", "Alt", Kind.STICKY),
        Chip("up", "↑", Kind.MOMENTARY),
        Chip("down", "↓", Kind.MOMENTARY),
    )
}
