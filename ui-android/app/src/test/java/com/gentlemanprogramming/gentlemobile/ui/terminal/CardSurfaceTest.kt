package com.gentlemanprogramming.gentlemobile.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calibrated surface derivation (S28-3, second round): "fill the
 * screen" is not "maximise the cell". Maximising drove the columns down
 * to 22 and the daemon re-rendered its TUI at 22 columns — every line
 * cut mid-word. A phone terminal lives in **56 to 96 columns**: the cell
 * is the largest that keeps the columns inside that range, and the rows
 * follow from the card's height so the frame fills it.
 *
 * The derivation also keeps the 8 px of air per side (the same margin
 * [TerminalFit] enforces), so no column ever touches the card's edge —
 * the measured defect this round.
 *
 * Pure arithmetic, so a UI runtime is never needed to pin the rule.
 */
class CardSurfaceTest {

    // The bundled JetBrains Mono's measured ratios (the same round values
    // TerminalFitTest pins): 0.6em advance, 1.2em line span.
    private val advance = 0.6f
    private val lineHeight = 1.2f

    /** The calibration's own constants (documented on CardSurface). */
    private val minCols = CardSurface.MIN_COLS
    private val maxCols = CardSurface.MAX_COLS

    // A Pixel 6-sized terminal card in pixels (1080 px wide minus the
    // card's hair of padding; the height is whatever the composer leaves).
    private val pixel6CardWidth = 974f
    private val pixel6CardHeight = 1475f
    private val maxFont = 73.5f // 28 sp at the Pixel 6's density

    // --- the column range [56, 96] ------------------------------------------

    // --- the column range [38, 48] ---------------------------------------------

    @Test
    fun `the calibrated range is the phone target 38 to 48 columns (S31b)`() {
        // The fifth-round target: ~42 columns like Moshi, at the largest
        // cell that still fits — 56–96 landed at ~57 columns, too dense at
        // small text on a phone card.
        assertEquals(38, CardSurface.MIN_COLS)
        assertEquals(48, CardSurface.MAX_COLS)
    }

    @Test
    fun `a phone card keeps its columns inside the calibrated range`() {
        val surface = CardSurface.derive(
            pixel6CardWidth, pixel6CardHeight, advance, lineHeight,
            minFontPx = 1f, maxFontPx = maxFont,
        )
        assertTrue(
            "cols ${surface.cols} must not fall under $minCols — that is the 22-column cut-off again",
            surface.cols >= minCols,
        )
        assertTrue("cols ${surface.cols} must stay within $maxCols", surface.cols <= maxCols)
    }

    @Test
    fun `the cell is the largest that still yields the column floor`() {
        val surface = CardSurface.derive(
            pixel6CardWidth, pixel6CardHeight, advance, lineHeight,
            minFontPx = 1f, maxFontPx = maxFont,
        )
        // One pixel larger and the floor would break.
        val oneMore = surface.fontPx + 1f
        val colsAtOneMore = ((pixel6CardWidth - 2 * CardSurface.SAFETY_PX) / (oneMore * advance)).toInt()
        assertTrue(
            "a larger cell must break the column floor: $colsAtOneMore < $minCols",
            colsAtOneMore < minCols,
        )
    }

    @Test
    fun `a huge card is capped at the column ceiling, not just the font cap`() {
        // A card wide enough that the font cap alone would give 200+
        // columns: the ceiling must push the cell up instead.
        val surface = CardSurface.derive(
            6000f, 3000f, advance, lineHeight,
            minFontPx = 1f, maxFontPx = maxFont,
        )
        assertTrue("cols ${surface.cols} must stay within $maxCols", surface.cols <= maxCols)
    }

    // --- rows fill the height -----------------------------------------------

    @Test
    fun `the rows fill the card's height without overflowing it`() {
        val surface = CardSurface.derive(
            pixel6CardWidth, pixel6CardHeight, advance, lineHeight,
            minFontPx = 1f, maxFontPx = maxFont,
        )
        val usableHeight = pixel6CardHeight - 2 * CardSurface.SAFETY_PX
        assertTrue(
            "height ${surface.gridHeightPx} must fit the usable $usableHeight",
            surface.gridHeightPx <= usableHeight,
        )
        val unusedHeight = usableHeight - surface.gridHeightPx
        assertTrue(
            "no empty band below: unused height $unusedHeight < one cell ${surface.cellHeightPx}",
            unusedHeight < surface.cellHeightPx,
        )
    }

    @Test
    fun `the frame keeps the 8 px of air at the right edge`() {
        val surface = CardSurface.derive(
            pixel6CardWidth, pixel6CardHeight, advance, lineHeight,
            minFontPx = 1f, maxFontPx = maxFont,
        )
        assertTrue(
            "the grid must stop ${CardSurface.SAFETY_PX} px short of the card's edge",
            surface.gridWidthPx <= pixel6CardWidth - 2 * CardSurface.SAFETY_PX,
        )
    }

    // --- the keyboard-open card ---------------------------------------------

    @Test
    fun `a keyboard-open card keeps at least the column floor when the width allows`() {
        // The keyboard shrinks the HEIGHT, not the width: the column count
        // must not collapse with it. Under the S31b calibration the floor
        // itself is 38 (the 56–96 range landed at ~57 columns, too dense).
        val surface = CardSurface.derive(
            pixel6CardWidth, 656f, advance, lineHeight,
            minFontPx = 1f, maxFontPx = maxFont,
        )
        assertTrue("cols ${surface.cols} must stay >= $minCols", surface.cols >= minCols)
    }

    @Test
    fun `a truly narrow card still derives a drawable grid and never goes negative`() {
        // A card too narrow for the column floor at a readable font: the
        // font floors instead, and the derivation stays honest (never 0,
        // never an overflow). The floor itself may exceed the caller's
        // minimum: the S31b target range still governs when it fits.
        val surface = CardSurface.derive(
            300f, 800f, advance, lineHeight,
            minFontPx = 10f, maxFontPx = maxFont,
        )
        assertTrue("cols never zero, got ${surface.cols}", surface.cols >= 1)
        assertTrue("rows never zero, got ${surface.rows}", surface.rows >= 1)
        assertTrue(
            "the font never goes under the caller's minimum, got ${surface.fontPx}",
            surface.fontPx >= 10f,
        )
        assertTrue(surface.gridWidthPx <= 300f)
    }

    // --- the shape of the rule ----------------------------------------------

    @Test
    fun `columns still follow from the width and rows from the height`() {
        val surface = CardSurface.derive(
            pixel6CardWidth, pixel6CardHeight, advance, lineHeight,
            minFontPx = 1f, maxFontPx = maxFont,
        )
        val usableWidth = pixel6CardWidth - 2 * CardSurface.SAFETY_PX
        val usableHeight = pixel6CardHeight - 2 * CardSurface.SAFETY_PX
        assertEquals(
            (usableWidth / surface.cellWidthPx).toInt(),
            surface.cols,
        )
        assertEquals(
            (usableHeight / surface.cellHeightPx).toInt(),
            surface.rows,
        )
    }
}
