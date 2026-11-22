package com.gentlemanprogramming.gentlemobile.ui.terminal

import com.gentlemanprogramming.gentlemobile.ui.locale.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wrap mode (S32a, T30). The daemon keeps the pane's PTY at the desktop
 * width while the human's TUI is attached (L22), so the frames the phone
 * receives are already cut (T29 detects and says so). The borrowed idea:
 * re-flow the pane's text to the card's own columns and show it readable.
 *
 * The rules this file pins, one per T30 rule:
 * * the wrapper is a pure function — word wrap where a line has words,
 *   a hard break inside a token longer than the width (S31a's token is
 *   one hyphenated word with no spaces), the exact original text back
 *   when unwrapped, and wide characters counted as the grid counts them
 *   (two cells);
 * * the notice's states and actions: nothing cropped offers nothing (the
 *   sharp 1:1 grid stays untouched), the cropped notice offers wrap as
 *   the primary action and the existing whole-pane view as the secondary
 *   exact-layout fallback, and wrap/whole offer the single return to 1:1;
 * * honesty: a truncated read refuses wrap and says so instead, and wrap
 *   leaves on its own when the host stops cropping — with no typing;
 * * the copy: one short line per state, in both languages, rioplatense
 *   voseo in Spanish.
 */
class WrapGuardTest {

    /** S31a, verbatim: one long token with hyphens and no spaces. */
    private val controlPhrase =
        "LA-FRASE-DE-CONTROL-CRUZA-VARIAS-LINEAS-EN-UN-PANE-DE-38-COLUMNAS" +
            "-Y-CADA-PALABRA-DEBE-VERSE-COMPLETA-SIN-UN-SOLO-CARACTER-CORTADO" +
            "-EN-EL-BORDE-DERECHO-DE-LA-GRILLA"

    // --- the wrapper as a pure function --------------------------------------

    @Test
    fun `word wrap breaks at spaces and stays inside the width`() {
        assertEquals(
            listOf("the quick", "brown fox", "jumps"),
            WrapGuard.lines("the quick brown fox jumps", cols = 10),
        )
    }

    @Test
    fun `unwrapping the pieces reconstructs the original text exactly`() {
        val texts = listOf(
            "the quick brown fox jumps",
            "ab   cd  ef", // multi-space runs survive
            "abc   ", // trailing spaces
            "  ab cd", // leading spaces
            controlPhrase,
        )
        for (text in texts) {
            assertEquals(
                "reconstruction failed for $text",
                text,
                WrapGuard.reconstruct(WrapGuard.pieces(text, cols = 10)),
            )
        }
    }

    @Test
    fun `a token longer than the width breaks inside it and never clips`() {
        val token = "AAAAAAAAAA"
        val lines = WrapGuard.lines(token, cols = 3)
        assertEquals(listOf("AAA", "AAA", "AAA", "A"), lines)
        assertEquals(token, WrapGuard.reconstruct(WrapGuard.pieces(token, cols = 3)))
    }

    @Test
    fun `the control phrase reads across at least five lines at 38 columns with nothing lost`() {
        val lines = WrapGuard.lines(controlPhrase, cols = 38)
        assertTrue("expected at least 5 lines, got ${lines.size}", lines.size >= 5)
        for (line in lines) {
            assertTrue(
                "line wider than the grid: $line",
                CropGuard.widestLineCells(line) <= 38,
            )
        }
        assertEquals(
            controlPhrase,
            WrapGuard.reconstruct(WrapGuard.pieces(controlPhrase, cols = 38)),
        )
    }

    @Test
    fun `wide characters count two cells and are never split across lines`() {
        val text = "漢字漢字漢字漢字漢字"
        val lines = WrapGuard.lines(text, cols = 7)
        for (line in lines) {
            assertTrue(
                "wide line counted wider than the grid allows: $line",
                CropGuard.widestLineCells(line) <= 7,
            )
        }
        assertEquals(text, WrapGuard.reconstruct(WrapGuard.pieces(text, cols = 7)))
    }

    @Test
    fun `a wide character on a one column grid is emitted whole, never clipped`() {
        val lines = WrapGuard.lines("漢", cols = 1)
        assertEquals(listOf("漢"), lines)
    }

    @Test
    fun `newlines are structure the wrapper never eats`() {
        val text = "aa\nb\n\ncccc"
        assertEquals(text, WrapGuard.reconstruct(WrapGuard.pieces(text, cols = 10)))
        assertEquals(listOf("aa", "b", "", "cccc"), WrapGuard.lines(text, cols = 10))
    }

    // --- the honesty rules ----------------------------------------------------

    @Test
    fun `a truncated read refuses wrap`() {
        assertFalse(WrapGuard.canWrap(truncated = true))
        assertTrue(WrapGuard.canWrap(truncated = false))
    }

    @Test
    fun `wrap leaves on its own when the host stops cropping, no typing`() {
        assertTrue(WrapGuard.wrapDone(widestCells = 37, grantedCols = 38))
        assertFalse(WrapGuard.wrapDone(widestCells = 130, grantedCols = 38))
    }

    @Test
    fun `no measurement is no decision, so a failed read never empties the card`() {
        // widest 0 = the read never landed: wrap holds instead of pretending
        // the crop is over.
        assertFalse(WrapGuard.wrapDone(widestCells = 0, grantedCols = 38))
    }

    // --- the mode and action decision -----------------------------------------

    @Test
    fun `nothing cropped offers nothing and the 1-1 grid stays as it is`() {
        assertNull(WrapGuard.noticePlan(cropped = false, mode = WrapGuard.Fallback.FLAT, readLimited = false))
    }
    @Test
    fun `the cropped notice offers wrap as the primary action and the whole pane as the exact-layout fallback`() {
        val plan = WrapGuard.noticePlan(
            cropped = true,
            mode = WrapGuard.Fallback.FLAT,
            readLimited = false,
        )
        assertEquals(CropVoice.State.FLAT, plan?.sentence)
        assertEquals(listOf(WrapGuard.NoticeAction.WRAP, WrapGuard.NoticeAction.WHOLE), plan?.actions)
    }

    @Test
    fun `in wrap mode the notice names the state and offers the single return to 1-1`() {
        val plan = WrapGuard.noticePlan(
            cropped = true,
            mode = WrapGuard.Fallback.WRAP,
            readLimited = false,
        )
        assertEquals(CropVoice.State.WRAP, plan?.sentence)
        assertEquals(listOf(WrapGuard.NoticeAction.BACK), plan?.actions)
    }

    @Test
    fun `whole-pane mode keeps its single return action`() {
        val plan = WrapGuard.noticePlan(
            cropped = true,
            mode = WrapGuard.Fallback.WHOLE,
            readLimited = false,
        )
        assertEquals(CropVoice.State.WHOLE, plan?.sentence)
        assertEquals(listOf(WrapGuard.NoticeAction.BACK), plan?.actions)
    }

    @Test
    fun `a truncated read in wrap mode says so instead of wrapping`() {
        val plan = WrapGuard.noticePlan(
            cropped = true,
            mode = WrapGuard.Fallback.WRAP,
            readLimited = true,
        )
        assertEquals(CropVoice.State.LIMITED, plan?.sentence)
        assertTrue(plan?.actions?.isEmpty() == true)
    }

    // --- the automatic default (T36): wrap engages on its own when cropped ------

    @Test
    fun `a cropped flat pane engages wrap on its own`() {
        assertEquals(true, WrapGuard.autoWrap(cropped = true, wrapMode = false, declined = false))
    }

    @Test
    fun `a pane that fits never engages wrap`() {
        assertNull(WrapGuard.autoWrap(cropped = false, wrapMode = false, declined = false))
    }

    @Test
    fun `a declined crop keeps the sharp grid until the pane fits again`() {
        assertNull(WrapGuard.autoWrap(cropped = true, wrapMode = false, declined = true))
        // Once the host stops cropping, the decline is forgotten and the
        // automatic default stands again.
        assertNull(WrapGuard.autoWrap(cropped = false, wrapMode = false, declined = true))
    }

    @Test
    fun `wrap already on needs no second decision`() {
        assertNull(WrapGuard.autoWrap(cropped = true, wrapMode = true, declined = false))
    }

    // --- the copy, per state ---------------------------------------------------

    @Test
    fun `the wrap sentence is the approved copy, in both languages`() {
        val english = CropVoice.notice(AppLanguage.ENGLISH, CropVoice.State.WRAP)
        val spanish = CropVoice.notice(AppLanguage.SPANISH, CropVoice.State.WRAP)
        assertEquals("Wrapped to this phone's width — nothing is cut.", english)
        assertEquals("Ajustado al ancho de este teléfono — no se corta nada.", spanish)
        assertFalse(english.contains("\n"))
        assertFalse(spanish.contains("\n"))
    }

    @Test
    fun `the limited read sentence exists in both languages and owns the honesty line`() {
        val english = CropVoice.notice(AppLanguage.ENGLISH, CropVoice.State.LIMITED)
        val spanish = CropVoice.notice(AppLanguage.SPANISH, CropVoice.State.LIMITED)
        assertTrue(english.contains("read"))
        assertTrue(spanish.contains("leer"))
    }

    @Test
    fun `the wrap action is labelled plainly in both languages`() {
        assertEquals("Wrap", CropVoice.wrapText(AppLanguage.ENGLISH))
        assertEquals("Reenvolvé", CropVoice.wrapText(AppLanguage.SPANISH))
    }

    @Test
    fun `every sentence sits under the reserved slot's line cap without an ellipsis`() {
        for (language in AppLanguage.entries) {
            for (state in CropVoice.State.entries) {
                val sentence = CropVoice.notice(language, state)
                assertTrue(
                    "sentence too long for the slot: $sentence",
                    sentence.length <= CropVoice.SLOT_MAX_CHARS,
                )
            }
        }
    }

    // --- the wrap grid: gutter, reading width, font -----------------------------

    @Test
    fun `the wrap gutter reserves real space at each edge of the card`() {
        // The reading view is for reading: no glyph may touch the card's
        // edge. The gutter is inner padding on both sides, and the columns
        // are derived from the reduced width.
        assertTrue(WrapGuard.WRAP_PADDING_DP >= 8)
    }

    @Test
    fun `the wrap font targets the reading width inside the gutter`() {
        val floorPx = 42f
        val capPx = 73.5f
        val usable = 911f
        val font = WrapGuard.wrapFont(usable, advanceRatio = 0.6f, floorPx = floorPx, capPx = capPx)
        assertEquals(usable / (0.6f * WrapGuard.WRAP_TARGET_COLS), font)
        val cols = WrapGuard.wrapCols(usable, font, 0.6f)
        assertEquals(WrapGuard.WRAP_TARGET_COLS, cols)
    }

    @Test
    fun `a narrow card keeps the reading floor and accepts fewer columns`() {
        val floorPx = 42f
        val capPx = 73.5f
        val usable = 700f
        val font = WrapGuard.wrapFont(usable, advanceRatio = 0.6f, floorPx = floorPx, capPx = capPx)
        assertEquals(floorPx, font)
        val cols = WrapGuard.wrapCols(usable, font, 0.6f)
        assertTrue("expected fewer than the target columns, got $cols", cols < WrapGuard.WRAP_TARGET_COLS)
        assertTrue(cols >= 1)
    }

    @Test
    fun `a wide card stops at the font cap and takes the wider columns`() {
        val floorPx = 42f
        val capPx = 73.5f
        val usable = 1800f
        val font = WrapGuard.wrapFont(usable, advanceRatio = 0.6f, floorPx = floorPx, capPx = capPx)
        assertEquals(capPx, font)
        assertTrue(WrapGuard.wrapCols(usable, font, 0.6f) > WrapGuard.WRAP_TARGET_COLS)
    }

    @Test
    fun `the columns are one, never zero`() {
        assertEquals(1, WrapGuard.wrapCols(widthPx = 0f, fontPx = 10f, advanceRatio = 0.6f))
    }
}
