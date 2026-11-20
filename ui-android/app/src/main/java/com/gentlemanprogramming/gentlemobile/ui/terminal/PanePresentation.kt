package com.gentlemanprogramming.gentlemobile.ui.terminal

import java.util.concurrent.atomic.AtomicInteger

/**
 * The pane-presentation decision (S31, T26). Measured on the real host and
 * read from the daemon's own source: a client-shell resize only reshapes the
 * client's projection — the daemon reflows the tab's real layout (and with
 * it the pane's PTY, so lines wrap at the new width) **only** for the client
 * that activated its surface (`client_shell.surface.set {active}`) and
 * thereby holds the tab's geometry control.
 *
 * The rule this model encodes:
 * * the ask is always **exactly the frame the app paints**
 *   ([CardSurface.derive]) — the painted width and the pane width are the
 *   same number, so no line can lose text;
 * * the claiming activation goes out **once per activation**: an inactive
 *   surface — a fresh session, or one the app released on back — gets one
 *   claiming activation, then routine size updates only. An active surface
 *   never re-activates on every reflow;
 * * a released pane re-claims when the human returns, because the release
 *   clears the asked bookkeeping in the session state;
 * * a card change (keyboard, rotation) re-asks with the new frame.
 *
 * The caller keys the effect on the agent binding too: after a tab switch
 * the new tab still sits at the desktop's size, and a fresh SessionUi has
 * no asked bookkeeping, so the rule asks again.
 *
 * Pure decisions, unit-tested without a UI runtime (PanePresentationTest).
 */
object PanePresentation {

    /** The presentation command: a claiming activation first when
     *  [activateSurface] is set, then the size the pane must reflow to. */
    data class Request(
        val activateSurface: Boolean,
        val cols: Int,
        val rows: Int,
    )

    /**
     * The command for one derived frame, given the pane's presentation
     * state: `surfaceActive` is whether this client still holds the tab's
     * geometry control, and `lastCols`/`lastRows` is what this binding last
     * asked the pane for (`null` = never asked — a fresh session or a fresh
     * rebind). `null` result: the pane is already at the derived frame and
     * the surface is active; nothing to send.
     */
    fun reflow(
        surfaceActive: Boolean,
        lastCols: Int?,
        lastRows: Int?,
        derivedCols: Int,
        derivedRows: Int,
    ): Request? {
        val asked = lastCols == derivedCols && lastRows == derivedRows
        if (!surfaceActive) {
            // One claiming activation per activation (T26 rule 2) — and the
            // size rides it, so the reflow follows the claim in wire order.
            return Request(
                activateSurface = true,
                cols = derivedCols,
                rows = derivedRows,
            )
        }
        if (asked) return null
        return Request(
            activateSurface = false,
            cols = derivedCols,
            rows = derivedRows,
        )
    }
}

/**
 * The claim single-flight (T26 rule 5). Activation and resize are two wire
 * calls that effect firings (keyboard open, keyboard close) can overlap:
 * the single claim worker writes them in decision order, and this gate makes
 * a claim confirmed after a newer decision unable to overwrite its
 * bookkeeping. Decide on the UI thread; apply only the newest confirmation.
 */
class ClaimFlight {
    private val decided = AtomicInteger(0)
    private val applied = AtomicInteger(0)

    /** Reserve the next decision sequence. Called on the UI thread, so the
     *  sequence number is the decision order itself. */
    fun decide(): Int = decided.incrementAndGet()

    /** Apply one claim's confirmed bookkeeping only when no newer decision
     *  superseded it. Idempotent for the same sequence. */
    fun apply(seq: Int): Boolean = seq >= applied.updateAndGet { current -> maxOf(current, seq) }
}
