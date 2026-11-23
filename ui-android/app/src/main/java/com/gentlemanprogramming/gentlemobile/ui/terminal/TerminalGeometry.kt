package com.gentlemanprogramming.gentlemobile.ui.terminal

/**
 * The terminal's grid derivation and the IME resize decision (T42): columns
 * from the available width and rows from the available height, at the cell
 * size the bundled mono face measures. Pure and testable — the composable
 * measures, this decides, and the PTY is re-asked only when the grid actually
 * changes.
 */
object TerminalGeometry {

    data class Grid(val cols: Int, val rows: Int)

    const val MIN_COLS = 2
    const val MIN_ROWS = 1

    fun derive(
        widthPx: Float,
        heightPx: Float,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): Grid {
        val cols = (widthPx / cellWidthPx).toInt().coerceAtLeast(MIN_COLS)
        val rows = (heightPx / cellHeightPx).toInt().coerceAtLeast(MIN_ROWS)
        return Grid(cols, rows)
    }

    sealed interface Decision {
        data object NoChange : Decision
        data class Resize(val cols: Int, val rows: Int) : Decision
    }

    /** Whether the derived grid differs from the one the PTY already has.
     *  A keyboard open/close changes rows, never the decision rule. */
    fun resizeDecision(
        widthPx: Float,
        heightPx: Float,
        cellWidthPx: Float,
        cellHeightPx: Float,
        currentCols: Int,
        currentRows: Int,
    ): Decision {
        val grid = derive(widthPx, heightPx, cellWidthPx, cellHeightPx)
        return if (grid.cols == currentCols && grid.rows == currentRows) {
            Decision.NoChange
        } else {
            Decision.Resize(grid.cols, grid.rows)
        }
    }
}
