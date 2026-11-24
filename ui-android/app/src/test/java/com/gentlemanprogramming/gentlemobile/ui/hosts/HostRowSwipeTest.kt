package com.gentlemanprogramming.gentlemobile.ui.hosts

import org.junit.Assert.assertEquals
import org.junit.Test

/** T51 (swipe-to-delete): the reveal's open/closed decision is pure — the
 *  row opens only when the drag ends past the half-way point, closes
 *  otherwise (a small drag in either direction settles back) — and the
 *  offset exposes the row at the reveal width only when open. */
class HostRowSwipeTest {

    @Test
    fun `a drag past the threshold opens`() {
        assertEquals(SwipeReveal.OPEN, HostRowSwipe.settle(offsetPx = 49f, thresholdPx = 48f))
    }

    @Test
    fun `a small drag settles back closed`() {
        assertEquals(SwipeReveal.CLOSED, HostRowSwipe.settle(offsetPx = 47f, thresholdPx = 48f))
        assertEquals(SwipeReveal.CLOSED, HostRowSwipe.settle(offsetPx = 0f, thresholdPx = 48f))
    }

    @Test
    fun `a drag back toward the edge closes`() {
        assertEquals(SwipeReveal.CLOSED, HostRowSwipe.settle(offsetPx = -10f, thresholdPx = 48f))
    }

    @Test
    fun `the row is offset by the reveal width only when open`() {
        assertEquals(0f, HostRowSwipe.offset(SwipeReveal.CLOSED, revealPx = 72f), 0f)
        assertEquals(72f, HostRowSwipe.offset(SwipeReveal.OPEN, revealPx = 72f), 0f)
    }
}
