package com.gentlemanprogramming.gentlemobile.ui.terminal

import com.gentlemanprogramming.gentlemobile.ui.locale.AppLanguage
import kotlin.math.floor

/**
 * Wrap mode (S32a, T30, borrowed from `tuan3w/herdr-mobile`'s idea, not
 * its code): when the granted grid crops wider pane content (T29's
 * detection), the phone can re-flow the pane's text to its own columns —
 * readable, nothing cut — instead of only cropping or shrinking the whole
 * pane. The sharp 1:1 grid stays the default; the whole-pane view stays
 * the exact-layout fallback.
 *
 * Pure decisions, unit-tested without a UI runtime (WrapGuardTest):
 * * [pieces]/[lines]/[reconstruct] — the wrapper. It prefers a break at a
 *   space; when a run between spaces is longer than the width (S31a's
 *   token: one hyphenated word, no spaces) it breaks inside that token at
 *   exactly the width, never clipping a character; wide characters count
 *   as the grid counts them ([CropGuard.cellWidth], two cells) and are
 *   never split across lines; the exact original text comes back from
 *   [reconstruct]; `\n` is structure, never eaten.
 * * [canWrap] — honesty rule: a read the host already truncated is never
 *   wrapped; [wrapDone] — wrap leaves on its own, with no typing, once
 *   the host stops cropping (a failed read holds: no measurement, no
 *   decision, never an empty card pretending success).
 * * [noticePlan] — the reserved slot's sentence and actions per state:
 *   nothing cropped offers nothing, the cropped notice offers wrap as
 *   the primary action with the existing whole-pane view as the secondary
 *   exact-layout fallback, and wrap/whole offer the one-tap return to 1:1.
 * * [wrapFont]/[wrapCols] — the render grid: a reading width of
 *   [WRAP_TARGET_COLS] columns at the largest font that fits inside the
 *   [WRAP_PADDING_DP] dp gutter, between the 16 sp reading floor and the
 *   terminal's cap.
 */
object WrapGuard {

    /** Inner gutter of the wrapped view, in dp, EACH side of the card: no
     *  glyph touches the card's edge, and the column count is derived from
     *  the width reduced by this gutter (the human's presentation rule). */
    const val WRAP_PADDING_DP = 12

    /** The reading width (in columns) the wrap view targets: text for
     *  reading, not a headline — around 32 columns at the largest font that
     *  fits inside the gutter. */
    const val WRAP_TARGET_COLS = 32

    /** One rendered line plus the exact gap that followed it in the
     *  original (`null` = nothing follows — the text ended). Concatenating
     *  every piece and gap reconstructs the original text exactly; the
     *  gap only ever ends its own rendered line, so rendering drops it. */
    data class Piece(val text: String, val nextGap: String?)

    /**
     * Wrap [text] to [cols] cells, wide characters as two cells. One piece
     * per rendered line, in original order; every piece is at most [cols]
     * cells wide (`\n` is structure, carried in the gaps).
     */
    fun pieces(text: String, cols: Int): List<Piece> {
        require(cols >= 1) { "cols must be at least 1" }
        val result = mutableListOf<Piece>()
        var lineStart = 0
        while (lineStart <= text.length) {
            val newline = text.indexOf('\n', lineStart)
            val lineEnd = if (newline == -1) text.length else newline
            val wrapped = wrapWithin(text, lineStart, lineEnd, cols)
            result.addAll(wrapped)
            if (newline != -1) {
                result[result.size - 1] = result.last().copy(nextGap = "\n")
            }
            if (newline == -1) break
            lineStart = newline + 1
        }
        return result
    }

    /** The rendered lines: one string per wrapped line, gaps dropped (a
     *  gap always ends its own rendered line, so it is invisible). */
    fun lines(text: String, cols: Int): List<String> =
        pieces(text, cols).map { it.text }

    /** The exact original text back from [pieces]' output. */
    fun reconstruct(pieces: List<Piece>): String =
        pieces.joinToString("") { it.text + (it.nextGap ?: "") }

    /** The pane-read honesty: a read the host already truncated is never
     *  wrapped — wrap would promise content that never crossed the wire. */
    fun canWrap(truncated: Boolean): Boolean = !truncated

    /** True when the pane sits inside the granted grid again, so wrap is
     *  no longer needed and 1:1 shows the pane whole with no typing. A
     *  zero measurement is no decision — a failed read holds the mode. */
    fun wrapDone(widestCells: Int, grantedCols: Int): Boolean =
        grantedCols > 0 && widestCells in 1..grantedCols

    /** One wrap action in the reserved slot: wrap as the primary action,
     *  the whole-pane exact-layout fallback, or the return to 1:1. */
    enum class NoticeAction { WRAP, WHOLE, BACK }

    /** The fallback state of the session screen's terminal. */
    enum class Fallback { FLAT, WRAP, WHOLE }

    /** The slot's plan: one sentence (a [CropVoice.State]) plus the actions
     *  it offers, or `null` when the slot stays empty — nothing cropped,
     *  so the sharp 1:1 grid is untouched. */
    data class NoticePlan(val sentence: CropVoice.State, val actions: List<NoticeAction>)

    /** The reserved slot's decision. `readLimited` = wrap is on but the
     *  pane read failed or came truncated: the slot says so instead of
     *  wrapping, and nothing pretends success on an empty card. */
    fun noticePlan(cropped: Boolean, mode: Fallback, readLimited: Boolean): NoticePlan? {
        val sentence = when {
            readLimited -> CropVoice.State.LIMITED
            mode == Fallback.WRAP -> CropVoice.State.WRAP
            mode == Fallback.WHOLE -> CropVoice.State.WHOLE
            cropped -> CropVoice.State.FLAT
            else -> return null
        }
        val actions = when {
            sentence == CropVoice.State.LIMITED ->
                if (cropped && mode != Fallback.WRAP) listOf(NoticeAction.WHOLE) else emptyList()
            sentence == CropVoice.State.WRAP || sentence == CropVoice.State.WHOLE ->
                listOf(NoticeAction.BACK)
            else -> listOf(NoticeAction.WRAP, NoticeAction.WHOLE)
        }
        return NoticePlan(sentence, actions)
    }

    /** Columns the card holds at [fontPx]: the card's usable width over the
     *  painted advance of one cell. At least one column, never zero. */
    fun wrapCols(widthPx: Float, fontPx: Float, advanceRatio: Float): Int =
        floor(widthPx / (advanceRatio * fontPx)).toInt().coerceAtLeast(1)

    /**
     * The reading font: the size that lays out [WRAP_TARGET_COLS] columns
     * inside the guttered width, held between the reading floor and the
     * terminal's cap. A narrow card keeps the floor and accepts fewer
     * columns; a wide one stops at the cap and takes wider columns. Either
     * way the wrap boundary stays inside the gutter, never flush.
     */
    fun wrapFont(widthPx: Float, advanceRatio: Float, floorPx: Float, capPx: Float): Float =
        (widthPx / (advanceRatio * WRAP_TARGET_COLS)).coerceIn(floorPx, capPx)

    /**
     * The rendered lines of one original line, [from] inclusive to [to]
     * exclusive — one piece per rendered line.
     * Greedy: take what fits; where the run between spaces is longer than
     * the width, break inside it at exactly the width. The scan is
     * code-point wise, so a wide character is never split — it moves whole
     * to the next piece — and a one-column grid still emits a wide
     * character whole instead of clipping it.
     */
    private fun wrapWithin(text: String, from: Int, to: Int, cols: Int): List<Piece> {
        if (from == to) return listOf(Piece("", null))
        val pieces = mutableListOf<Piece>()
        var start = from
        while (start < to) {
            var end = start
            var cells = 0
            while (end < to) {
                val cp = text.codePointAt(end)
                val width = CropGuard.cellWidth(cp)
                if (cells + width > cols) break
                cells += width
                end += Character.charCount(cp)
            }
            if (end >= to) {
                pieces.add(Piece(text.substring(start, to), null))
                break
            }
            // Break needed before `end`: prefer the rightmost space that
            // leaves a whole word behind; otherwise break inside the token.
            var space = -1
            var scan = end - 1
            while (scan > start) {
                if (text[scan] == ' ') {
                    space = scan
                    break
                }
                scan--
            }
            if (space > start) {
                var gapEnd = space
                while (gapEnd < to && text[gapEnd] == ' ') gapEnd++
                val gap = text.substring(space, gapEnd)
                pieces.add(Piece(text.substring(start, space), gap))
                if (gapEnd >= to) break
                start = gapEnd
            } else {
                if (end == start) {
                    // No character fits at all (a one-column grid and a
                    // wide first character): emit one character whole.
                    end = start + Character.charCount(text.codePointAt(start))
                }
                pieces.add(Piece(text.substring(start, end), ""))
                start = end
            }
        }
        return pieces
    }
}
