package com.gentlemanprogramming.gentlemobile.ui.terminal

import com.gentlemanprogramming.gentlemobile.ui.locale.AppLanguage
import kotlin.math.max

/**
 * The honest crop (S31c, T29). Measured on the real host and recorded in
 * the daemon's own behaviour (L22): while the human's desktop TUI is
 * attached, the daemon confirms the phone's geometry claim and renders
 * frames at the phone's grid without reflowing the pane's PTY — the pane
 * stays at the desktop width, and long lines arrive already cut.
 *
 * The rules:
 * * **detect, once per confirmed claim** (never per frame): the pane's
 *   visible text is read and its widest line measured in the grid's own
 *   units — wide characters are two cells, exactly the way the daemon's
 *   grid counts them ([widestLineCells]); a widest line wider than the
 *   granted columns proves the view is cropped ([cropped]);
 * * **say it in one line**: one short sentence in the app's language
 *   ([CropVoice]), never a paragraph, never a dialog;
 * * **offer the whole pane**: one explicit action that switches the
 *   session to showing the whole pane scaled down — the fallback the human
 *   chose, never the default. It asks the pane's own width
 *   ([targetGrid] with [widestLineCells]'s measurement as the honest
 *   source) and the fit renders the whole grid into the card; leaving the
 *   session or the desktop letting go returns to 1:1 with no typing
 *   ([returnsToFlatPanel]);
 * * **anchor every notice**: the slot the notices share is always
 *   reserved ([cardHeightForGrid] subtracts it unconditionally), so a
 *   notice can never change the card's height — and never re-fire the
 *   claim it measured (the observed 38x24↔38x26 oscillation).
 *
 * Pure decisions, unit-tested without a UI runtime (CropGuardTest).
 */
object CropGuard {

    /**
     * The widest visible line of a pane's text, in grid cells. Newlines
     * split the measurement; a carriage return or a tab is not a drawn
     * cell. Wide characters count as the grid counts them — two cells for
     * the East-Asian-wide and fullwidth ranges below, zero for the
     * zero-width ones, one for everything else.
     */
    fun widestLineCells(text: String): Int {
        var widest = 0
        var line = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            when (codePoint) {
                '\n'.code -> {
                    if (line > widest) widest = line
                    line = 0
                }
                '\r'.code, '\t'.code -> {}
                else -> line += cellWidth(codePoint)
            }
            index += Character.charCount(codePoint)
        }
        return max(line, widest)
    }

    /** True when the granted grid crops wider pane content: the phone is
     *  looking at a cut view and must say so. */
    fun cropped(widestCells: Int, grantedCols: Int): Boolean =
        grantedCols > 0 && widestCells > grantedCols

    /** The grid the claim effect asks for: the phone's own derived frame in
     *  1:1, the pane's own width in the whole-pane fallback. `mirrorCols`
     *  is the widest measured line — the honest source for the pane's
     *  width; zero means no measurement landed yet, so the fallback is not
     *  claimable and the phone frame stands. */
    fun targetGrid(wholePane: Boolean, mirrorCols: Int, phoneCols: Int, phoneRows: Int): Grid =
        if (wholePane && mirrorCols > 0) {
            Grid(mirrorCols, phoneRows)
        } else {
            Grid(phoneCols, phoneRows)
        }

    /** True when the whole-pane fallback is no longer needed: the widest
     *  visible line sits inside the phone's grid, so 1:1 shows the pane
     *  whole again, with no typing. */
    fun returnsToFlatPanel(widestCells: Int, phoneCols: Int): Boolean =
        phoneCols > 0 && widestCells in 1..phoneCols

    /**
     * The height the terminal card's grid derives from: the notice slot is
     * subtracted ALWAYS — notice showing or not — so the derived grid can
     * never depend on whether a notice is showing, and a notice can never
     * re-fire the claim it came from. Callers pass this instead of the raw
     * card height to [CardSurface.derive] and [TerminalFit.fit].
     */
    fun cardHeightForGrid(totalHeightPx: Float, density: Float, reserveNotice: Boolean = true): Float =
        if (reserveNotice) (totalHeightPx - NOTICE_SLOT_DP * density).coerceAtLeast(0f) else totalHeightPx

    /** The one decision the session screen's claim effect makes. */
    data class Grid(val cols: Int, val rows: Int)

    /** The always-reserved notice slot, in dp: one error line plus one
     *  notice line with its action. Never zero, never conditional. */
    const val NOTICE_SLOT_DP = 64

    /**
     * The East-Asian-wide and fullwidth ranges, condensed to the ranges a
     * pane's real content draws (the daemon's grid gives two cells to
     * these). Zero-width joins and variation selectors draw no cell.
     * Public so the wrap mode (T30) counts cells with the exact same
     * measure the crop detection uses — one grid, one counting rule.
     */
    fun cellWidth(codePoint: Int): Int = when (codePoint) {
        in 0x0300..0x036F, in 0x200B..0x200F, in 0xFE00..0xFE0F, in 0xE0100..0xE01EF -> 0
        in 0x1100..0x115F,
        in 0x2329..0x232A,
        in 0x2E80..0x303E,
        in 0x3041..0x33FF,
        in 0x3400..0x4DBF,
        in 0x4E00..0x9FFF,
        in 0xA000..0xA4CF,
        in 0xA960..0xA97F,
        in 0xAC00..0xD7A3,
        in 0xF900..0xFAFF,
        in 0xFE10..0xFE19,
        in 0xFE30..0xFE6F,
        in 0xFF00..0xFF60,
        in 0xFFE0..0xFFE6,
        in 0x1F300..0x1F64F,
        in 0x1F900..0x1F9FF,
        in 0x20000..0x3FFFD,
        -> 2
        else -> 1
    }
}

/**
 * The crop sentences and the fallback's action labels (S31c rules 2–3, S32a
 * T30): one short line each, no paragraph, no jargon, in the app's two
 * languages, rioplatense voseo in Spanish. Living beside the decision as
 * app copy chosen by [AppLanguage] — the session screen reads it at render
 * time.
 */
object CropVoice {

    /** The terminal's four states the reserved slot can name: the flat 1:1
     *  view whose lines arrive cut, the whole-pane scaled view, the wrap
     *  mode (S32a), and a pane read that cannot be wrapped honestly. */
    enum class State { FLAT, WHOLE, WRAP, LIMITED }

    /**
     * The longest sentence the slot's notice line fits without an ellipsis
     * (T29b): two 12 sp lines beside the action measured the English
     * sentence (57 characters) whole and ellipsized the 80-character
     * Spanish one. Every sentence must stay under this cap — pinned by
     * CropGuardTest.
     */
    const val SLOT_MAX_CHARS = 60

    /** The one-line sentence for the session's state: the flat mode says
     *  the lines arrive cut; the whole-pane fallback says the pane is
     *  shown whole; wrap mode says it is re-wrapped; a limited read owns
     *  the honesty line. The sentence must be true for the state it
     *  labels. */
    fun notice(language: AppLanguage, state: State): String = when (language) {
        AppLanguage.ENGLISH -> when (state) {
            State.FLAT -> "The desktop is using this pane, so long lines arrive cut."
            State.WHOLE -> "The desktop is using this pane — showing it whole."
            State.WRAP -> "Wrapped to this phone's width — nothing is cut."
            State.LIMITED -> "The pane cannot be read whole right now."
        }
        AppLanguage.SPANISH -> when (state) {
            State.FLAT -> "El escritorio usa este panel: las líneas llegan cortadas."
            State.WHOLE -> "El escritorio usa este panel: se muestra entero."
            State.WRAP -> "Ajustado al ancho de este teléfono — no se corta nada."
            State.LIMITED -> "El panel no se puede leer entero ahora."
        }
    }

    /** The explicit action: switch to showing the whole pane scaled down. */
    fun showWholePane(language: AppLanguage): String = when (language) {
        AppLanguage.ENGLISH -> "Show the whole pane"
        AppLanguage.SPANISH -> "Mostrar el panel entero"
    }

    /** The primary action of the cropped notice (S32a): re-wrap the pane's
     *  text to the phone's columns, readable. Short, so the two actions of
     *  the cropped state still leave the sentence its two lines without an
     *  ellipsis (measured on the card: the long label ellipsized it). */
    fun wrapText(language: AppLanguage): String = when (language) {
        AppLanguage.ENGLISH -> "Wrap"
        AppLanguage.SPANISH -> "Reenvolvé"
    }

    /** The action's other face: return the session to the phone's own
     *  grid. Nothing to type, one tap. */
    fun backToPhone(language: AppLanguage): String = when (language) {
        AppLanguage.ENGLISH -> "Back to the phone size"
        AppLanguage.SPANISH -> "Volver al tamaño del teléfono"
    }
}
