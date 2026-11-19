package com.gentlemanprogramming.gentlemobile.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pane-presentation rule (S28-3, third round). Measured on the real
 * host and read from the daemon's own source: a client-shell resize only
 * reshapes the client's projection — the daemon reflows the tab's real
 * layout (and with it the pane's PTY) **only** for the client that
 * activated its surface (`client_shell.surface.set {active}`) and thereby
 * holds the tab's geometry control. The first two rounds painted a 57-wide
 * frame over a 110–186-wide pane: whole words and whole sentences were
 * cropped away.
 *
 * The rule: every reflow carries the surface activation and asks for
 * **exactly the frame the app paints** — the painted width and the pane
 * width are the same number, so no line can lose text. A pane already
 * asked for the derived frame is left alone (no churn), and a card change
 * (keyboard, rotation) re-asks with the new frame.
 *
 * Pure decisions so the rule is unit-tested without a UI runtime.
 */
class PanePresentationTest {

    // --- the frame painted and the pane width asked are the same value ------

    @Test
    fun `a pane wider than the derived frame is reflowed to exactly what the app paints`() {
        // The measured defect: the pane sits at the desktop's 186 columns,
        // the card derives 57. The command must ask the pane for 57 — the
        // same number of columns the frame paints — never paint 57 over
        // the pane's 110–186.
        val request = PanePresentation.reflow(
            lastCols = 186, lastRows = 43,
            derivedCols = 57, derivedRows = 39,
        )
        assertEquals(57, request!!.cols)
        assertEquals(39, request.rows)
        assertTrue(request.activateSurface)
    }

    @Test
    fun `a resize must never carry a width different from the painted frame`() {
        // The invariant the first two rounds broke: whatever the pane
        // reports, the asked size equals the derived frame — the values
        // TerminalFit paints — never the old desktop surface, never the
        // pane's own current size.
        for (paneCols in listOf(110, 186, 57)) {
            val request = PanePresentation.reflow(
                lastCols = paneCols, lastRows = 43,
                derivedCols = 57, derivedRows = 39,
            )
            assertEquals("asked cols must equal the painted cols", 57, request!!.cols)
            assertEquals(39, request.rows)
        }
    }

    // --- the surface activation rides every reflow ---------------------------

    @Test
    fun `every reflow carries the surface activation`() {
        // Read from the daemon's source: without an activated surface the
        // resize only reshapes the projection and the PTY keeps the
        // desktop's width — the exact truncation this fixes.
        val request = PanePresentation.reflow(
            lastCols = 57, lastRows = 17,
            derivedCols = 57, derivedRows = 39,
        )
        assertTrue(request!!.activateSurface)
    }

    // --- no churn -------------------------------------------------------------

    @Test
    fun `a pane already asked for the derived frame is left alone`() {
        val request = PanePresentation.reflow(
            lastCols = 57, lastRows = 39,
            derivedCols = 57, derivedRows = 39,
        )
        assertNull(request)
    }

    // --- card changes re-ask ----------------------------------------------------

    @Test
    fun `a keyboard-open card re-asks with the new frame`() {
        val request = PanePresentation.reflow(
            lastCols = 57, lastRows = 39,
            derivedCols = 57, derivedRows = 17,
        )
        assertEquals(57, request!!.cols)
        assertEquals(17, request.rows)
    }

    @Test
    fun `a fresh session asks before any pane answer exists`() {
        val request = PanePresentation.reflow(
            lastCols = null, lastRows = null,
            derivedCols = 57, derivedRows = 39,
        )
        assertEquals(57, request!!.cols)
        assertEquals(39, request.rows)
    }

    // --- a tab rebind re-asks: the new tab still sits at the desktop size -----

    @Test
    fun `returning to a size already asked after a rebind is answered by the asked bookkeeping`() {
        // After switching tabs the app re-fires the effect keyed on the
        // agent; whether a request goes out is decided by what was ASKED
        // for that binding, not by the card alone. The model only answers
        // from the asked bookkeeping — a rebind resets it in the UI layer.
        assertNull(
            PanePresentation.reflow(
                lastCols = 57, lastRows = 39,
                derivedCols = 57, derivedRows = 39,
            ),
        )
    }
}
