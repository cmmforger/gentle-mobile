package com.gentlemanprogramming.gentlemobile.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gentlemanprogramming.gentlemobile.core.CellDto
import com.gentlemanprogramming.gentlemobile.core.CursorDto
import com.gentlemanprogramming.gentlemobile.core.SurfaceDto
import com.gentlemanprogramming.gentlemobile.ui.session.SessionComposer
import com.gentlemanprogramming.gentlemobile.ui.theme.LocalPalette

/**
 * A deterministic 80x24 surface for layout work.
 *
 * Launch with `adb shell am start -n <pkg>/.MainActivity --ez fixture true` to
 * iterate on the terminal geometry without an SSH connection, a host key
 * challenge or a live agent. It is development scaffolding: the real screen
 * always draws what the host sent.
 */
private const val COLS = 80
private const val ROWS = 24

/** Below this the glyphs stop being readable on a phone; scroll instead. */
private const val MIN_READABLE_FONT_PX = 10f

private val FG = 0x02F6EFF3u
private val FG_MUTED = 0x02A78E9Bu
private val FG_ACCENT = 0x02FF76C3u
private val FG_OK = 0x02B4E7C7u
private val FG_WARN = 0x02E0C27Au
private val BG = 0x021A1218u

fun fixtureSurface(): SurfaceDto {
    val cells = MutableList(COLS * ROWS) {
        CellDto(symbol = " ", fg = FG, bg = BG, flags = 0u, wide = false, skip = false)
    }

    fun put(row: Int, col: Int, text: String, fg: UInt = FG) {
        if (row !in 0 until ROWS) return
        var c = col
        for (ch in text) {
            if (c >= COLS) break
            cells[row * COLS + c] = CellDto(
                symbol = ch.toString(), fg = fg, bg = BG, flags = 0u, wide = false, skip = false,
            )
            c += 1
        }
    }

    put(0, 0, "❯ gentle-mobile · fixture 80×24", FG_ACCENT)
    put(1, 0, "This frame is generated in the app: no SSH, no host, no agent.", FG_MUTED)
    put(2, 0, "It exists so layout changes cost one build, not a full connect flow.", FG_MUTED)
    put(4, 0, "╭──────────────────────────────────────────────────────────────╮", FG_MUTED)
    put(5, 0, "│  the right edge of this box is column 80                    │", FG_OK)
    put(6, 0, "╰──────────────────────────────────────────────────────────────╯", FG_MUTED)
    put(8, 0, "❯ cargo test -p gentlecompanion-core", FG_ACCENT)
    put(9, 0, "test result: ok. 52 passed; 0 failed", FG_OK)
    put(10, 0, "❯ herdr agent list --json | head -c 120", FG_ACCENT)
    put(11, 0, "{\"agents\":[{\"agent\":\"pi\",\"agent_status\":\"working\"}]}", FG)
    put(13, 0, "wide glyph: 💥 then text that must continue on the far side", FG_WARN)
    put(15, 0, "long line: " + "abcdefghij".repeat(8).take(COLS - 11), FG_MUTED)
    put(17, 0, "cursor should sit right after this █", FG)

    // One real wide cell: the emoji occupies two columns, the follower is skipped.
    val wideRow = 13
    val wideCol = 12
    cells[wideRow * COLS + wideCol] = CellDto(
        symbol = "💥", fg = FG_WARN, bg = BG, flags = 0u, wide = true, skip = false,
    )
    cells[wideRow * COLS + wideCol + 1] = CellDto(
        symbol = "", fg = FG_WARN, bg = BG, flags = 0u, wide = false, skip = true,
    )

    return SurfaceDto(
        width = COLS.toUInt(),
        height = ROWS.toUInt(),
        revision = 1uL,
        cells = cells,
        cursor = CursorDto(x = 36u, y = 17u, visible = true, shape = 2u),
        popupPresent = false,
    )
}

@Composable
fun FixtureTerminal(modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(LocalPalette.current.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // Fit first, but never below a readable size: past that the grid scrolls
        // horizontally instead of shrinking into illegibility.
        val fitPx = maxWidth.value / (COLS * TERMINAL_FONT_RATIO)
        val fontSizePx = fitPx.coerceIn(MIN_READABLE_FONT_PX, 24f)
        val needsScroll = fitPx < MIN_READABLE_FONT_PX
        Column(Modifier.fillMaxSize()) {
            Text(
                text = "fixture · $COLS×$ROWS · font ${"%.1f".format(fontSizePx)}px" + if (needsScroll) " · scroll" else "",
                color = LocalPalette.current.muted,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth(),
            )
            val scroll = if (needsScroll) {
                Modifier.horizontalScroll(rememberScrollState())
            } else {
                Modifier
            }
            TerminalSurface(
                surface = fixtureSurface(),
                fontSizePx = fontSizePx,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .then(scroll),
            )
            Spacer(Modifier.height(8.dp))
            // The real session composer, not a lookalike: the fixture exists
            // so composer/terminal layout can be iterated without an SSH
            // host, so it hosts the same composable the live session uses.
            // Typing enables the send triangle; send itself is a no-op here.
            var fixtureDraft by remember { mutableStateOf("") }
            SessionComposer(
                draft = fixtureDraft,
                onDraftChange = { fixtureDraft = it },
                sending = false,
                sendError = null,
                sent = false,
                onSend = { fixtureDraft = "" },
            )
        }
    }
}
