package com.gentlemanprogramming.gentlemobile.ui.terminal

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The calibrated surface derivation (S28-3, recalibrated by T26/S31b).
 *
 * A phone terminal lives in **[MIN_COLS] to [MAX_COLS] columns** — the
 * fifth-round target is ~42 columns like Moshi, at the largest cell that
 * still fits. The earlier 56–96 range landed at ~57 columns on a phone
 * card: too dense, small text. The rules:
 *
 * 1. the cell is the **largest** that keeps the column count inside that
 *    range — floored, capped by [TerminalFontMaxSp] at the call site,
 *    and floored at the caller's minimum font;
 * 2. the column count follows from the card's usable width at that cell:
 *    `floor((width - 2·air) / cellW)`;
 * 3. the row count follows from the card's usable height at that cell
 *    height: `floor((height - 2·air) / cellH)` — the frame fills the
 *    height, so no empty band is left below (S31b: both axes derive from
 *    the card, and the frame fills it in both directions).
 *
 * The usable area subtracts [SAFETY_PX] of air per side — the same
 * margin [TerminalFit] enforces when it renders — so no column ever
 * touches the card's edge (measured defect: the last column glued to
 * the border). Deriving from the same usable area the renderer draws
 * into keeps the two in agreement.
 *
 * A card too narrow for the column floor at a readable font floors the
 * font instead and accepts fewer columns — never zero, never an
 * overflow. A card wide enough to break the ceiling through the font
 * cap alone is pulled back up to the ceiling.
 *
 * Pure arithmetic so the rule is unit-tested without a UI runtime
 * (CardSurfaceTest).
 */
object CardSurface {

    /**
     * The calibrated column floor: below this a herdr TUI re-renders with
     * every line cut mid-word — 22 columns measured on the real host. 38
     * is the narrow end of the S31b phone target (~42 like Moshi).
     */
    const val MIN_COLS = 38

    /**
     * The calibrated column ceiling: past this a phone cell gets too
     * small to read, so the cell grows instead.
     */
    const val MAX_COLS = 48

    /**
     * The air kept between the drawn frame and the card's edge, per side
     * in pixels — the same margin [TerminalFit] keeps, and the reason the
     * last column no longer touches the border.
     */
    const val SAFETY_PX = 8f

    /** Never smaller than a pixel: a degenerate card still derives. */
    private const val MIN_FONT_PX = 1f

    /** The fit for one card: the grid to ask the daemon for, and the cell
     *  size it renders at. */
    class Result(
        val cols: Int,
        val rows: Int,
        val fontPx: Float,
        val cellWidthPx: Float,
        val cellHeightPx: Float,
    ) {
        /** The grid's on-screen size — what the frame covers. */
        val gridWidthPx: Float get() = cols * cellWidthPx
        val gridHeightPx: Float get() = rows * cellHeightPx
    }

    /**
     * Derive the grid for one card. `advanceRatio` and `lineHeightRatio`
     * are the cell ratios the renderer will draw with — pass the painted
     * advance (the larger of the nominal and the measured, as
     * [TerminalFit.fit] takes it) so the derived grid can never overflow
     * the card that derived it.
     */
    fun derive(
        cardWidthPx: Float,
        cardHeightPx: Float,
        advanceRatio: Float,
        lineHeightRatio: Float,
        minFontPx: Float,
        maxFontPx: Float,
        minCols: Int = MIN_COLS,
        maxCols: Int = MAX_COLS,
        advanceRatioOverride: Float? = null,
    ): Result {
        // The painted advance governs when the caller measured it (the same
        // rule the renderer's fit uses): a hinted font paints wider than its
        // nominal ratio, and the derived grid must never overflow the card.
        val advance = maxOf(advanceRatio, advanceRatioOverride ?: advanceRatio)
        val usableWidth = (cardWidthPx - 2f * SAFETY_PX).coerceAtLeast(0f)
        val usableHeight = (cardHeightPx - 2f * SAFETY_PX).coerceAtLeast(0f)
        // The largest cell that still yields the column floor.
        val colsFloorBound = if (usableWidth > 0f) usableWidth / (minCols.coerceAtLeast(1) * advance) else Float.MAX_VALUE
        val fontPx = floor(min(maxFontPx, colsFloorBound))
            .coerceAtLeast(minFontPx)
            .coerceAtLeast(MIN_FONT_PX)
        // The column ceiling: a card so wide that even the font cap would
        // give more than `maxCols` columns pulls the cell up to the ceiling.
        val ceilingBound = if (usableWidth > 0f) usableWidth / (maxCols.coerceAtLeast(1) * advance) else 0f
        val fontAtCeiling = kotlin.math.ceil(ceilingBound)
        val font = max(fontPx, fontAtCeiling)
        val cellWidthPx = font * advance
        val cellHeightPx = font * lineHeightRatio
        return Result(
            cols = if (cellWidthPx > 0f) max(1, floor(usableWidth / cellWidthPx).toInt()) else 1,
            rows = if (cellHeightPx > 0f) max(1, floor(usableHeight / cellHeightPx).toInt()) else 1,
            fontPx = font,
            cellWidthPx = cellWidthPx,
            cellHeightPx = cellHeightPx,
        )
    }
}
