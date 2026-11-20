package com.gentlemanprogramming.gentlemobile.ui.terminal

import com.gentlemanprogramming.gentlemobile.ui.locale.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The honest crop (S31c, T29). Measured on the real host (L22): while the
 * human's desktop TUI is attached, the daemon confirms the phone's claim
 * and renders frames at the phone's grid **without reflowing the pane's
 * PTY**, so long lines arrive already cut. The rules this file pins:
 *
 * * detection compares the pane's widest visible line with the columns the
 *   phone was granted, with wide characters counted the way the grid counts
 *   them (two cells) — a pure measurement, run once per confirmed claim;
 * * the sentence is one short line in English and Spanish, rioplatense
 *   voseo, no jargon;
 * * the whole-pane fallback asks for the pane's own width — the widest
 *   measured line is the honest source — and returns to the phone grid
 *   without typing when it no longer needs to;
 * * the notice slot is always reserved, so a notice can never move the
 *   derived grid (the measured oscillation 38x24↔38x26).
 */
class CropGuardTest {

    // --- rule 1: the widest visible line, in the grid's own units ----------

    @Test
    fun `the widest visible line is measured in cells`() {
        val text = "short\n" + "x".repeat(43) + "\n" + "y".repeat(20)
        assertEquals(43, CropGuard.widestLineCells(text))
    }

    @Test
    fun `wide characters count two cells the way the grid counts them`() {
        // Ten CJK glyphs: the grid draws each as two cells, so the line is
        // twenty cells wide — twice what a naive character count says and
        // wider than a 38-column grant.
        val text = "漢字漢字漢字漢字漢字"
        assertEquals(20, CropGuard.widestLineCells(text))
        assertFalse(CropGuard.widestLineCells(text) == text.length)
    }

    @Test
    fun `fullwidth forms count two cells`() {
        val text = "Ａｂｃｄｅ"
        assertEquals(10, CropGuard.widestLineCells(text))
    }

    @Test
    fun `an empty pane measures zero and is never a crop`() {
        assertEquals(0, CropGuard.widestLineCells(""))
        assertFalse(CropGuard.cropped(0, 38))
    }

    @Test
    fun `a line wider than the granted grid is a crop`() {
        assertTrue(CropGuard.cropped(50, 38))
        assertTrue(CropGuard.cropped(184, 38))
    }

    @Test
    fun `a line at or under the granted grid is not a crop`() {
        assertFalse(CropGuard.cropped(38, 38))
        assertFalse(CropGuard.cropped(20, 38))
    }

    // --- rule 2: the one-line sentence, per state, in the app's languages ---

    @Test
    fun `the flat sentence exists in English and names the cut`() {
        val sentence = CropVoice.notice(AppLanguage.ENGLISH, CropVoice.State.FLAT)
        assertTrue(sentence.contains("desktop"))
        assertTrue(sentence.contains("cut"))
        assertFalse(sentence.contains("\n"))
    }

    @Test
    fun `the flat sentence exists in rioplatense Spanish and names the cut`() {
        val sentence = CropVoice.notice(AppLanguage.SPANISH, CropVoice.State.FLAT)
        assertTrue(sentence.contains("escritorio"))
        assertTrue(sentence.contains("cortadas"))
        assertFalse(sentence.contains("\n"))
    }

    @Test
    fun `the whole-pane sentence says the pane is shown whole, in both languages`() {
        val english = CropVoice.notice(AppLanguage.ENGLISH, CropVoice.State.WHOLE)
        assertTrue(english.contains("whole"))
        assertFalse(english.contains("cut"))
        val spanish = CropVoice.notice(AppLanguage.SPANISH, CropVoice.State.WHOLE)
        assertTrue(spanish.contains("entero"))
        assertFalse(spanish.contains("cortadas"))
    }

    @Test
    fun `the cut wording belongs to the flat state only`() {
        // The whole-pane state shows every line: saying the lines are cut
        // there would be false (the human's first T29b correction).
        assertFalse(
            CropVoice.notice(AppLanguage.ENGLISH, CropVoice.State.WHOLE)
                .contains(CropVoice.notice(AppLanguage.ENGLISH, CropVoice.State.FLAT)),
        )
        assertFalse(
            CropVoice.notice(AppLanguage.SPANISH, CropVoice.State.WHOLE)
                .contains(CropVoice.notice(AppLanguage.SPANISH, CropVoice.State.FLAT)),
        )
    }

    @Test
    fun `every sentence fits the reserved slot's line cap without an ellipsis`() {
        // Measured cap of the slot's notice line (two 12 sp lines beside the
        // action, T29b): the Spanish sentence ellipsized at 80 characters,
        // the English fit at 57. Every sentence must sit under the cap.
        for (language in AppLanguage.entries) {
            for (state in listOf(CropVoice.State.FLAT, CropVoice.State.WHOLE)) {
                val sentence = CropVoice.notice(language, state)
                assertTrue(
                    "sentence too long for the slot: $sentence",
                    sentence.length <= CropVoice.SLOT_MAX_CHARS,
                )
            }
        }
    }

    @Test
    fun `the whole-pane action is labelled plainly in both languages`() {
        assertFalse(CropVoice.showWholePane(AppLanguage.ENGLISH).contains("\n"))
        assertFalse(CropVoice.showWholePane(AppLanguage.SPANISH).contains("panel entero\\n"))
        assertTrue(CropVoice.showWholePane(AppLanguage.SPANISH).contains("entero"))
        assertTrue(CropVoice.backToPhone(AppLanguage.SPANISH).contains("teléfono"))
        assertTrue(CropVoice.backToPhone(AppLanguage.ENGLISH).contains("phone"))
    }

    // --- rule 3: the whole-pane fallback asks the pane's own width ----------

    @Test
    fun `the fallback asks for the pane's own width, not the phone's`() {
        val grid = CropGuard.targetGrid(
            wholePane = true,
            mirrorCols = 184,
            phoneCols = 38,
            phoneRows = 26,
        )
        assertEquals(184, grid.cols)
        assertEquals(26, grid.rows)
    }

    @Test
    fun `the flat session asks for the phone's own derived frame`() {
        val grid = CropGuard.targetGrid(
            wholePane = false,
            mirrorCols = 184,
            phoneCols = 38,
            phoneRows = 26,
        )
        assertEquals(38, grid.cols)
        assertEquals(26, grid.rows)
    }

    @Test
    fun `without a measured pane width there is no fallback ask`() {
        // mirrorCols 0 means no pane read landed yet: the fallback must
        // never claim a zero-width grid, it stays at the phone's frame.
        val grid = CropGuard.targetGrid(
            wholePane = true,
            mirrorCols = 0,
            phoneCols = 38,
            phoneRows = 26,
        )
        assertEquals(38, grid.cols)
    }

    @Test
    fun `the fallback returns to the flat grid once the pane is at the phone's width`() {
        // The desktop let go and the pane reflowed: the widest visible line
        // now sits inside the phone's grid, so 1:1 is whole again with no
        // typing.
        assertTrue(CropGuard.returnsToFlatPanel(widestCells = 38, phoneCols = 38))
        assertTrue(CropGuard.returnsToFlatPanel(widestCells = 12, phoneCols = 38))
    }

    @Test
    fun `the fallback holds while the pane is still wider than the phone`() {
        assertFalse(CropGuard.returnsToFlatPanel(widestCells = 184, phoneCols = 38))
        assertFalse(CropGuard.returnsToFlatPanel(widestCells = 0, phoneCols = 38))
    }

    // --- rule 4: the notice cannot move the derived grid --------------------

    @Test
    fun `the card height a grid derives from never depends on a notice`() {
        // One height computation for the card's terminal area: the notice
        // slot is subtracted ALWAYS, so there is no notice state for the
        // grid to depend on — the measurement the oscillation bug (claim
        // re-fired every 10 s, seq 38x24↔38x26) came from a conditional
        // line above the card.
        val withNotice = CropGuard.cardHeightForGrid(totalHeightPx = 1475f, density = 2.625f)
        val withoutNotice = CropGuard.cardHeightForGrid(totalHeightPx = 1475f, density = 2.625f)
        assertEquals(withNotice, withoutNotice, 0f)
        assertTrue(withNotice < 1475f)
        assertTrue(withNotice > 0f)
    }

    @Test
    fun `the reserved slot leaves a real card on a small screen`() {
        val height = CropGuard.cardHeightForGrid(totalHeightPx = 800f, density = 2f)
        assertTrue(height > 0f)
    }
}
