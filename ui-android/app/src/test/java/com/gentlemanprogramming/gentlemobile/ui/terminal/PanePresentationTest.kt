package com.gentlemanprogramming.gentlemobile.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pane-presentation rule (S31, T26). Measured on the real host and read
 * from the daemon's own source: a client-shell resize only reshapes the
 * projection — the daemon reflows the tab's real layout (and with it the
 * pane's PTY) **only** for the client that activated its surface
 * (`client_shell.surface.set {active}`) and thereby holds the tab's geometry
 * control.
 *
 * The rule, per T26:
 * * the ask is always **exactly the frame the app paints** ([CardSurface.derive])
 *   — the painted width and the pane width are the same number;
 * * the claiming activation is sent **once per activation**: an inactive
 *   surface (fresh session, or one released on back) gets one claiming
 *   activation, then routine size updates only. An already-active surface
 *   never re-activates on every reflow;
 * * a released pane re-claims when the human returns, because the release
 *   cleared the asked bookkeeping.
 *
 * Pure decisions, unit-tested without a UI runtime (PanePresentationTest).
 */
class PanePresentationTest {

    // --- the frame painted and the pane width asked are the same value ------

    @Test
    fun `a pane wider than the derived frame is reflowed to exactly what the app paints`() {
        // The measured defect: the pane sits at the desktop's 186 columns,
        // the card derives 42. The command must ask the pane for 42 — the
        // same number of columns the frame paints — never paint 42 over
        // the pane's 110–186.
        val request = PanePresentation.reflow(
            surfaceActive = true,
            lastCols = 186, lastRows = 43,
            derivedCols = 42, derivedRows = 39,
        )
        assertEquals(42, request!!.cols)
        assertEquals(39, request.rows)
    }

    @Test
    fun `a resize must never carry a width different from the painted frame`() {
        for (paneCols in listOf(110, 186, 42)) {
            val request = PanePresentation.reflow(
                surfaceActive = true,
                lastCols = paneCols, lastRows = 43,
                derivedCols = 42, derivedRows = 39,
            )
            assertEquals("asked cols must equal the painted cols", 42, request!!.cols)
            assertEquals(39, request.rows)
        }
    }

    // --- one claiming activation per activation ------------------------------

    @Test
    fun `an inactive surface gets one claiming activation`() {
        // A fresh session (or a pane released on back) holds no geometry:
        // the claim sends `surface.set {active}` once, then the size.
        val request = PanePresentation.reflow(
            surfaceActive = false,
            lastCols = null, lastRows = null,
            derivedCols = 42, derivedRows = 39,
        )
        assertTrue(request!!.activateSurface)
        assertEquals(42, request.cols)
        assertEquals(39, request.rows)
    }

    @Test
    fun `a released pane re-claims even when the size already matches`() {
        // The desktop reflowed the pane back to its own geometry after the
        // release — and if its width happens to equal the phone's, the
        // activation alone must still go out: without it the phone holds no
        // geometry control and no later resize can reflow the pane.
        val request = PanePresentation.reflow(
            surfaceActive = false,
            lastCols = 42, lastRows = 39,
            derivedCols = 42, derivedRows = 39,
        )
        assertTrue(request!!.activateSurface)
        assertEquals(42, request.cols)
        assertEquals(39, request.rows)
    }

    @Test
    fun `an already-active surface never re-activates on a reflow`() {
        // T26 rule 2: the old model sent `setSurfaceActive(true)` on EVERY
        // reflow, which kept the claim churning and let a stale activation
        // race a newer handoff. An active surface reflows by size alone.
        val request = PanePresentation.reflow(
            surfaceActive = true,
            lastCols = 57, lastRows = 17,
            derivedCols = 42, derivedRows = 39,
        )
        assertFalse(request!!.activateSurface)
        assertEquals(42, request.cols)
        assertEquals(39, request.rows)
    }

    // --- no churn -------------------------------------------------------------

    @Test
    fun `a pane already asked for the derived frame is left alone`() {
        val request = PanePresentation.reflow(
            surfaceActive = true,
            lastCols = 42, lastRows = 39,
            derivedCols = 42, derivedRows = 39,
        )
        assertNull(request)
    }

    // --- card changes re-ask ----------------------------------------------------

    @Test
    fun `a keyboard-open card re-asks with the new frame`() {
        val request = PanePresentation.reflow(
            surfaceActive = true,
            lastCols = 42, lastRows = 39,
            derivedCols = 42, derivedRows = 17,
        )
        assertEquals(42, request!!.cols)
        assertEquals(17, request.rows)
    }

    @Test
    fun `a fresh session asks before any pane answer exists`() {
        val request = PanePresentation.reflow(
            surfaceActive = false,
            lastCols = null, lastRows = null,
            derivedCols = 42, derivedRows = 39,
        )
        assertEquals(42, request!!.cols)
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
                surfaceActive = true,
                lastCols = 42, lastRows = 39,
                derivedCols = 42, derivedRows = 39,
            ),
        )
    }
}
