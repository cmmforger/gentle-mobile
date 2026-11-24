package com.gentlemanprogramming.gentlemobile.ui.hosts

/** T51 (swipe-to-delete): the reveal state of one host row. */
enum class SwipeReveal { CLOSED, OPEN }

/** The pure swipe decisions: where a completed drag settles, and the offset
 *  the row rests at. The reveal is a narrow icon at the trailing edge, not a
 *  full-width block — the row slides by the reveal width, never away. */
object HostRowSwipe {

    /** The settle target after a drag ends at [offsetPx] (0 = closed, the
     *  reveal width = open): past the half-way point it opens, otherwise it
     *  closes — a small drag in either direction settles back where it was. */
    fun settle(offsetPx: Float, thresholdPx: Float): SwipeReveal =
        if (offsetPx < -thresholdPx) SwipeReveal.OPEN else SwipeReveal.CLOSED

    /** The row's horizontal offset at [state]: the reveal width when open,
     *  zero when closed. */
    fun offset(state: SwipeReveal, revealPx: Float): Float =
        if (state == SwipeReveal.OPEN) -revealPx else 0f
}
