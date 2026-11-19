package com.gentlemanprogramming.gentlemobile.ui.terminal

import com.gentlemanprogramming.gentlemobile.core.CellDto
import com.gentlemanprogramming.gentlemobile.core.CursorDto
import com.gentlemanprogramming.gentlemobile.core.SurfaceDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The desktop-composer crop (S29). The frame the phone paints includes pi's
 * own composer — the `✿ waiting for input ─── [ Panel ] INSERT ─` bar and the
 * `› type, or / for commands` prompt row under it. The phone writes through
 * its own composer, so that box is unusable chrome: the transform finds the
 * composer rows in the frame's row grid, removes them, shifts the rows below
 * up, and pads the freed space at the top of the grid with blank rows.
 *
 * Detection is deliberately conservative (S29): only the last few rows are
 * inspected, the prompt row must start with the prompt glyph AND carry pi's
 * exact hint, the panel bar must sit within one row of it, a metrics-looking
 * row is never removed, and an unclear pattern removes nothing — showing a
 * redundant box is better than hiding an agent's line.
 *
 * Pure transform, unit-tested without a UI runtime.
 */
class ComposerCropTest {

    // --- fixtures -------------------------------------------------------------

    /** A surface painted from text rows; spaces become blank cells. */
    private fun surface(rows: List<String>, cursor: CursorDto? = null): SurfaceDto {
        val width = rows.maxOf { it.length }.coerceAtLeast(1)
        val cells = rows.flatMap { row ->
            row.padEnd(width).map { ch ->
                CellDto(
                    symbol = if (ch == ' ') "" else ch.toString(),
                    fg = 0u, bg = 0u, flags = 0u, wide = false, skip = false,
                )
            }
        }
        return SurfaceDto(
            width = width.toUInt(),
            height = rows.size.toUInt(),
            revision = 7uL,
            cells = cells,
            cursor = cursor,
            popupPresent = false,
        )
    }

    /** Read one row's text back out of a surface (blank cells are spaces). */
    private fun rowText(surface: SurfaceDto, row: Int): String {
        val width = surface.width.toInt()
        return surface.cells.subList(row * width, (row + 1) * width)
            .joinToString("") { it.symbol.ifEmpty { " " } }
            .trimEnd()
    }

    private fun cursorAt(x: Int, y: Int) = CursorDto(
        x = x.toUInt(), y = y.toUInt(), visible = true, shape = 2u.toUByte(),
    )

    /** The idle frame the host paints: conversation, composer, metrics. */
    private val idleFrame = listOf(
        "fix: make the pane width follow the phone",
        "docs(odd): the fourth phone test",
        "✿ waiting for input ─── [ Panel ] INSERT ──────",
        "› type, or / for commands",
        "TPS 42.0 tok/s. out 1,234, in 567",
        "2 files · +12 −3",
    )

    // --- composer present: rows removed and shifted ---------------------------

    @Test
    fun `the composer rows are removed and the rows below shift up into their place`() {
        val result = ComposerCrop.crop(surface(idleFrame))
        assertEquals(2, result.removedRows)
        val cropped = result.surface
        // The grid keeps its dimensions: the freed space is padded at the top.
        assertEquals(idleFrame.size, cropped.height.toInt())
        assertEquals(rows_maxWidth(), cropped.width.toInt())
        // Rows below the composer shifted up: the metrics now sit directly
        // under the conversation, at the bottom of the grid.
        assertEquals(idleFrame[0], rowText(cropped, 2))
        assertEquals(idleFrame[1], rowText(cropped, 3))
        assertEquals(idleFrame[4], rowText(cropped, 4))
        assertEquals(idleFrame[5], rowText(cropped, 5))
        // No trace of the composer anywhere in the grid.
        for (row in 0 until cropped.height.toInt()) {
            val text = rowText(cropped, row)
            assertTrue("bar row must be gone at row $row", !text.contains("waiting for input"))
            assertTrue("prompt row must be gone at row $row", !text.contains("type, or / for commands"))
        }
    }

    @Test
    fun `the metrics lines survive the crop verbatim at the bottom`() {
        val cropped = ComposerCrop.crop(surface(idleFrame)).surface
        val last = cropped.height.toInt() - 1
        assertEquals("TPS 42.0 tok/s. out 1,234, in 567", rowText(cropped, last - 1))
        assertEquals("2 files · +12 −3", rowText(cropped, last))
    }

    @Test
    fun `the freed space is padded with blank rows at the top of the grid`() {
        val cropped = ComposerCrop.crop(surface(idleFrame)).surface
        for (row in 0 until result_removedRows(idleFrame)) {
            for (cell in cropped.cells.subList(row * cropped.width.toInt(), (row + 1) * cropped.width.toInt())) {
                assertEquals("top pad must be blank", "", cell.symbol)
            }
        }
    }

    private fun rows_maxWidth(): Int = idleFrame.maxOf { it.length }

    private fun result_removedRows(rows: List<String>): Int =
        ComposerCrop.crop(surface(rows)).removedRows

    @Test
    fun `revision width height and popup flag are carried through unchanged`() {
        val original = surface(idleFrame)
        val cropped = ComposerCrop.crop(original).surface
        assertEquals(original.revision, cropped.revision)
        assertEquals(original.width, cropped.width)
        assertEquals(original.height, cropped.height)
        assertEquals(original.popupPresent, cropped.popupPresent)
        assertEquals(original.cells.size, cropped.cells.size)
    }

    // --- marker absent: nothing is removed ------------------------------------

    @Test
    fun `a frame without the composer markers removes nothing`() {
        val rows = listOf(
            "reading the daemon source",
            "the pane keeps the desktop width",
            "TPS 42.0 tok/s. out 1,234, in 567",
            "2 files · +12 −3",
        )
        val original = surface(rows)
        val result = ComposerCrop.crop(original)
        assertEquals(0, result.removedRows)
        assertEquals(original, result.surface)
    }

    // --- prompt row is the primary evidence, the bar is optional (T44) --------

    @Test
    fun `the working bar still crops when the prompt row is there`() {
        // While an agent streams the bar's label is not "waiting for input"
        // (measured: `─ ✿ working ─────`) — the prompt row carries the
        // detection, the bar above is optional and only widens the block.
        val rows = listOf(
            "the agent is streaming output",
            "─ ✿ working ─────",
            "› type, or / for commands",
            "TPS 42.0 tok/s. out 1,234, in 567",
        )
        val result = ComposerCrop.crop(surface(rows))
        assertEquals(2, result.removedRows)
    }

    @Test
    fun `the thinking bar and the panel bar both crop with the prompt row`() {
        val thinking = listOf(
            "reading the daemon source",
            "─ ✿ thinking ─────",
            "› type, or / for commands",
            "TPS 42.0 tok/s. out 1,234, in 567",
        )
        assertEquals(2, ComposerCrop.crop(surface(thinking)).removedRows)
        val panel = listOf(
            "reading the daemon source",
            "─ [ Panel ] INSERT ──────",
            "› type, or / for commands",
            "TPS 42.0 tok/s. out 1,234, in 567",
        )
        assertEquals(2, ComposerCrop.crop(surface(panel)).removedRows)
    }

    @Test
    fun `a prompt row with no bar above it still crops one row`() {
        val rows = listOf(
            "reading the daemon source",
            "› type, or / for commands",
            "TPS 42.0 tok/s. out 1,234, in 567",
            "2 files · +12 −3",
        )
        val result = ComposerCrop.crop(surface(rows))
        assertEquals(1, result.removedRows)
        // Only the prompt row is gone; the conversation and metrics stay.
        assertEquals("reading the daemon source", rowText(result.surface, 1))
        assertEquals("2 files · +12 −3", rowText(result.surface, result.surface.height.toInt() - 1))
    }

    @Test
    fun `a line where the hint does not follow the prompt glyph removes nothing`() {
        // An agent line that starts with the glyph but quotes the hint is a
        // paragraph, not the composer's prompt row: the hint must come
        // directly after the glyph (measured prompt: `›  type, or / …`).
        val rows = listOf(
            "✿ waiting for input ─── [ Panel ] INSERT ──────",
            "› see the docs: type, or / for commands — quoted",
            "TPS 42.0 tok/s. out 1,234, in 567",
        )
        assertEquals(0, ComposerCrop.crop(surface(rows)).removedRows)
    }

    // --- marker inside an agent paragraph: nothing is removed ------------------

    @Test
    fun `the hint inside a long agent paragraph removes nothing`() {
        val rows = listOf(
            "the docs say the prompt reads: › type, or / for commands and then",
            "more prose follows on the same line so the row is not a prompt row",
            "✿ waiting for input ─── [ Panel ] INSERT ──────",
            "TPS 42.0 tok/s. out 1,234, in 567",
        )
        // The bar is present but the quoted hint is mid-paragraph, not a
        // prompt row within one row of the bar.
        assertEquals(0, ComposerCrop.crop(surface(rows)).removedRows)
    }

    @Test
    fun `a prompt row farther than one row from the bar crops the prompt row alone`() {
        // The prompt row is the primary evidence: a confirmed prompt row
        // crops by itself, and a bar that is not adjacent to it stays —
        // widening the block across an intermediate line could hide one.
        val rows = listOf(
            "✿ waiting for input ─── [ Panel ] INSERT ──────",
            "some intermediate line the composer never paints",
            "› type, or / for commands",
            "TPS 42.0 tok/s. out 1,234, in 567",
        )
        val result = ComposerCrop.crop(surface(rows))
        assertEquals(1, result.removedRows)
        assertEquals(
            "✿ waiting for input ─── [ Panel ] INSERT ──────",
            rowText(result.surface, 1),
        )
    }

    // --- metrics are never removed ---------------------------------------------

    @Test
    fun `a candidate row that carries the metrics declines the whole crop`() {
        // A metrics-looking row inside the candidate block: removing it would
        // hide real metrics, so nothing is removed.
        val rows = listOf(
            "fix: make the pane width follow the phone",
            "✿ working… TPS 42.0 tok/s ────",
            "› type, or / for commands",
            "2 files · +12 −3",
        )
        assertEquals(0, ComposerCrop.crop(surface(rows)).removedRows)
    }

    // --- the row-count arithmetic ----------------------------------------------

    @Test
    fun `the pane is asked for the derived rows plus the FULL allowance`() {
        // The measured removed rows must not drive the request: on the first
        // pass nothing has been measured yet, so the freed rows would never
        // come back and the card kept its empty area (measured defect, T44).
        // The full allowance is requested unconditionally.
        assertEquals(34, ComposerCrop.requestRows(30))
        assertEquals(41, ComposerCrop.requestRows(37))
    }

    @Test
    fun `the cropped frame holds at least the derived rows`() {
        // Whatever the detector ends up removing (it is capped at
        // MAX_REMOVE), the returned frame still holds the derived rows and
        // so still fills the card that derived them.
        for (removed in 0..ComposerCrop.MAX_REMOVE) {
            val requested = ComposerCrop.requestRows(30)
            assertTrue("removed=$removed: $requested rows must hold 30", requested - removed >= 30)
        }
    }

    @Test
    fun `the transform never reports more than four removed rows`() {
        // A degenerate frame whose whole bottom is marker-like still removes
        // at most MAX_REMOVE rows.
        val rows = List(10) { "› type, or / for commands" }
        assertTrue(ComposerCrop.crop(surface(rows)).removedRows <= 4)
    }

    // --- the cursor -------------------------------------------------------------

    @Test
    fun `a cursor inside the composer is dropped with the composer`() {
        val result = ComposerCrop.crop(surface(idleFrame, cursor = cursorAt(27, 3)))
        assertNull(result.surface.cursor)
    }

    @Test
    fun `a cursor above the composer shifts down with the padded grid`() {
        val result = ComposerCrop.crop(surface(idleFrame, cursor = cursorAt(4, 0)))
        assertEquals(2u, result.surface.cursor!!.y)
        assertEquals(4u, result.surface.cursor!!.x)
    }

    @Test
    fun `a cursor below the composer keeps its row`() {
        val result = ComposerCrop.crop(surface(idleFrame, cursor = cursorAt(1, 5)))
        assertEquals(5u, result.surface.cursor!!.y)
    }
}
