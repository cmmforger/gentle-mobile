package com.gentlemanprogramming.gentlemobile.ui.terminal

import kotlin.math.floor
import kotlin.math.max

/**
 * The whole-line bottom edge (T39): whatever the terminal card shows must
 * never rest with a line straddling its bottom edge — the last visible
 * line fits fully or is not drawn. The photo the human marked shows a
 * wrapped line cut in half at the card's edge, because the card's height
 * was not a multiple of the line pitch and no scroll position put the edge
 * back on a boundary.
 *
 * Pure arithmetic so the rule is unit-tested without a UI runtime
 * (LineGuardTest).
 */
object LineGuard {

    /**
     * The height that holds only WHOLE lines of `lineHeightPx` starting at
     * the card's top: a partial line is never allocated. A zero or
     * degenerate line pitch degrades to the height itself.
     */
    fun wholeLinesHeightPx(availablePx: Float, lineHeightPx: Float): Float {
        if (lineHeightPx <= 0f) return availablePx.coerceAtLeast(0f)
        return floor(availablePx.coerceAtLeast(0f) / lineHeightPx) * lineHeightPx
    }

    /**
     * A scroll offset snapped to the nearest line boundary, so a settled
     * view never leaves a half-drawn line at the card's bottom edge. The
     * result never goes past [maxOffsetPx] — a clipped tail (when the
     * scrollable extent itself is not a whole number of lines) is the one
     * case the clamp accepts, and at the bottom the last line is whole.
     */
    fun snapOffsetPx(rawOffsetPx: Float, lineHeightPx: Float, maxOffsetPx: Float): Float {
        if (lineHeightPx <= 0f) return rawOffsetPx.coerceIn(0f, maxOffsetPx.coerceAtLeast(0f))
        val snapped = floor(rawOffsetPx / lineHeightPx + 0.5f) * lineHeightPx
        return snapped.coerceIn(0f, max(0f, maxOffsetPx))
    }
}
