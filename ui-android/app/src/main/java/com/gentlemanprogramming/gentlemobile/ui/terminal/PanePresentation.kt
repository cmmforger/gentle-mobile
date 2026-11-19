package com.gentlemanprogramming.gentlemobile.ui.terminal

/**
 * The pane-presentation decision (S28-3, third round). Measured on the
 * real host and read from the daemon's own source: a client-shell resize
 * only reshapes the client's projection — the daemon reflows the tab's
 * real layout (and with it the pane's PTY, so lines wrap at the new
 * width) **only** for the client that activated its surface
 * (`client_shell.surface.set {active}`) and thereby holds the tab's
 * geometry control. Without the activation, the resize paints a narrow
 * frame over the desktop-sized pane and whole words disappear.
 *
 * The rule this model encodes:
 * * every reflow carries the surface activation and asks for **exactly
 *   the frame the app paints** ([CardSurface.derive]) — the painted
 *   width and the pane width are the same number, so no line can lose
 *   text;
 * * a pane already asked for the derived frame is left alone (no churn
 *   while the session just sits there);
 * * a card change (keyboard, rotation) re-asks with the new frame.
 *
 * The caller keys the effect on the agent binding too: after a tab
 * switch the new tab still sits at the desktop's size, and clearing the
 * asked bookkeeping on rebind makes the rule ask again.
 *
 * Pure decisions, unit-tested without a UI runtime (PanePresentationTest).
 */
object PanePresentation {

    /** The presentation command: activate the surface (claim the tab's
     *  geometry) and reflow the pane to the painted frame. */
    data class Request(
        val activateSurface: Boolean,
        val cols: Int,
        val rows: Int,
    )

    /**
     * The command for one derived frame, given what this binding last
     * asked the pane for (`null` = never asked — a fresh session or a
     * fresh rebind). `null` result: the pane is already at the derived
     * frame; nothing to send.
     */
    fun reflow(
        lastCols: Int?,
        lastRows: Int?,
        derivedCols: Int,
        derivedRows: Int,
    ): Request? {
        if (lastCols == derivedCols && lastRows == derivedRows) return null
        return Request(
            activateSurface = true,
            cols = derivedCols,
            rows = derivedRows,
        )
    }
}
