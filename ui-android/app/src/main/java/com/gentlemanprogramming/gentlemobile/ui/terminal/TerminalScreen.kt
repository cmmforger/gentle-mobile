package com.gentlemanprogramming.gentlemobile.ui.terminal

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gentlemanprogramming.gentlemobile.R
import com.gentlemanprogramming.gentlemobile.core.TerminalSession
import com.gentlemanprogramming.gentlemobile.ui.session.ExtraKeyRowModel
import com.gentlemanprogramming.gentlemobile.ui.theme.AppTheme
import com.gentlemanprogramming.gentlemobile.ui.theme.LocalPalette
import kotlinx.coroutines.delay

private val TERMINAL_FONT_SP = 14.sp

/**
 * The real terminal (T42): the PTY's byte stream drawn as a cell grid, full
 * bleed. Top bar (back · panes · refresh, 44 dp) hides while the keyboard is
 * up; the key row above the terminal does the same. The soft keyboard sends
 * keystrokes straight to the PTY and the IME resize re-derives the grid and
 * re-asks the PTY (`window-change`).
 */
@Composable
fun TerminalScreen(
    title: String,
    theme: AppTheme,
    session: TerminalSession?,
    starting: Boolean,
    error: String?,
    onStart: (cols: Int, rows: Int) -> Unit,
    onBack: () -> Unit,
) {
    val palette = LocalPalette.current
    val terminalColors = remember(theme) { TerminalPalette.forTheme(theme) }
    var emulator by remember { mutableStateOf<TerminalEmulator?>(null) }
    var frameVersion by remember { mutableStateOf(0) }
    var closed by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf(TextFieldValue("")) }
    val composer = remember { TerminalComposer() }

    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg)
            .safeDrawingPadding()
            .imePadding(),
    ) {
        if (!keyboardVisible) {
            TerminalTopBar(title = title, onBack = onBack)
        }
        if (!keyboardVisible) {
            TerminalKeyRow(onKey = { bytes -> session?.write(bytes) })
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val density = LocalDensity.current
            val metrics = rememberTerminalCellMetrics()
            val fontSizePx = with(density) { TERMINAL_FONT_SP.toPx() }
            val cellW = fontSizePx * metrics.advanceRatio
            val cellH = fontSizePx * metrics.lineHeightRatio
            val grid = TerminalGeometry.derive(
                constraints.maxWidth.toFloat(),
                constraints.maxHeight.toFloat(),
                cellW,
                cellH,
            )
            // Start the PTY once a real grid exists, and keep the emulator
            // and the PTY on the same geometry through every IME change.
            LaunchedEffect(grid.cols, grid.rows, session) {
                val e = emulator
                if (e == null) {
                    if (grid.rows >= 2 && grid.cols >= 20) {
                        onStart(grid.cols, grid.rows)
                        emulator = TerminalEmulator(grid.cols, grid.rows, terminalColors)
                    }
                    return@LaunchedEffect
                }
                if (e.cols != grid.cols || e.rows != grid.rows) {
                    e.resize(grid.cols, grid.rows)
                }
                val s = session
                if (s != null && (e.cols != s.cols().toInt() || e.rows != s.rows().toInt())) {
                    s.resize(e.cols.toUInt(), e.rows.toUInt())
                }
            }
            // Drain the PTY as bytes arrive; nothing is batched.
            LaunchedEffect(session) {
                val s = session ?: return@LaunchedEffect
                closed = false
                while (true) {
                    val bytes = s.read(4096u)
                    val e = emulator
                    if (bytes.isNotEmpty() && e != null) {
                        e.feed(bytes)
                        frameVersion++
                        val replies = e.drainReplies()
                        if (replies.isNotEmpty()) runCatching { s.write(replies) }
                    }
                    if (s.isClosed()) {
                        closed = true
                        break
                    }
                    delay(20)
                }
                frameVersion++
            }
            TerminalCanvas(
                emulator = emulator,
                colors = terminalColors,
                fontSizePx = fontSizePx,
                closed = closed,
                starting = starting && emulator == null,
                error = error,
                frameVersion = frameVersion,
                modifier = Modifier.fillMaxSize(),
            )
        }
        TerminalInputField(
            enabled = session != null && !closed,
            value = input,
            onValueChange = { new ->
                val delta = composer.commit(new.text)
                if (delta.isNotEmpty()) session?.write(delta)
                input = if (new.text.contains('\n')) TextFieldValue("") else new
            },
        )
    }
}

@Composable
private fun TerminalTopBar(title: String, onBack: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_back_chevron),
                contentDescription = stringResource(R.string.inbox_back),
                tint = palette.accent,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_pane),
                contentDescription = stringResource(R.string.session_panes),
                tint = palette.accent,
                modifier = Modifier.size(18.dp),
            )
        }
        Box(
            modifier = Modifier.size(44.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_refresh),
                contentDescription = stringResource(R.string.session_refresh),
                tint = palette.accent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun TerminalKeyRow(onKey: (ByteArray) -> Unit) {
    var ctrlSticky by remember { mutableStateOf(false) }
    var altSticky by remember { mutableStateOf(false) }

    fun press(id: String) {
        val bytes = TerminalKeys.bytesFor(id, ctrlSticky, altSticky)
        if (bytes.isNotEmpty()) onKey(bytes)
        ctrlSticky = false
        altSticky = false
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ExtraKeyRowModel.chips().forEach { chip ->
            val pressed = chip.id == "ctrl" && ctrlSticky || chip.id == "alt" && altSticky
            TerminalKeyChip(
                label = chip.label,
                pressed = pressed,
                modifier = Modifier.weight(1f),
            ) {
                when (chip.kind) {
                    ExtraKeyRowModel.Kind.STICKY -> {
                        if (chip.id == "ctrl") ctrlSticky = !ctrlSticky
                        else altSticky = !altSticky
                    }
                    ExtraKeyRowModel.Kind.MOMENTARY -> press(chip.id)
                }
            }
        }
    }
}

@Composable
private fun TerminalKeyChip(
    label: String,
    pressed: Boolean,
    modifier: Modifier = Modifier,
    onPress: () -> Unit,
) {
    val palette = LocalPalette.current
    Box(
        modifier = modifier
            .height(44.dp)
            .padding(horizontal = 3.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .border(
                    1.dp,
                    if (pressed) palette.accentEdge else palette.line,
                    RoundedCornerShape(8.dp),
                )
                .background(
                    if (pressed) palette.selection else palette.surfaceRaised,
                    RoundedCornerShape(8.dp),
                )
                .clickable(onClick = onPress),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = if (pressed) palette.accentEdge else MaterialTheme.colorScheme.onBackground,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TerminalInputField(
    enabled: Boolean,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
) {
    val palette = LocalPalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .border(1.dp, palette.line, RoundedCornerShape(8.dp))
            .background(palette.surfaceRaised, RoundedCornerShape(8.dp))
            .height(44.dp)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = false,
            textStyle = TextStyle(
                color = if (enabled) MaterialTheme.colorScheme.onBackground else palette.textDisabled,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
            ),
            keyboardOptions = KeyboardOptions(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TerminalCanvas(
    emulator: TerminalEmulator?,
    colors: TermColors,
    fontSizePx: Float,
    closed: Boolean,
    starting: Boolean,
    error: String?,
    frameVersion: Int,
    modifier: Modifier,
) {
    val typefaces = rememberTypefaces()
    Canvas(modifier = modifier) {
        @Suppress("UNUSED_EXPRESSION")
        frameVersion
        val e = emulator
        if (e == null) {
            val message = when {
                starting -> "Starting the shell…"
                error != null -> error
                else -> "No terminal"
            }
            drawCenteredText(message)
            return@Canvas
        }
        drawTerminal(e, colors, typefaces, fontSizePx, closed)
        if (closed) {
            drawCenteredText("The shell has ended.")
        }
    }
}

private fun DrawScope.drawCenteredText(text: String) {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.color = 0xFFA78E9B.toInt()
    paint.textSize = 14f
    paint.textAlign = android.graphics.Paint.Align.CENTER
    drawContext.canvas.nativeCanvas.drawText(text, size.width / 2f, size.height / 2f, paint)
}

private fun DrawScope.drawTerminal(
    emulator: TerminalEmulator,
    colors: TermColors,
    typefaces: TerminalTypefaces,
    fontSizePx: Float,
    closed: Boolean,
) {
    val canvas = drawContext.canvas.nativeCanvas
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.textSize = fontSizePx
    paint.typeface = typefaces.regular ?: android.graphics.Typeface.DEFAULT
    val fm = paint.fontMetrics
    var cellWidth = paint.measureText("M")
    var cellHeight = fm.descent - fm.ascent

    val lines = emulator.screenLines()
    val rows = lines.size
    val cols = emulator.cols

    // The whole grid must fit the canvas: re-measure and rescale once if the
    // real advance at this size runs wider than the nominal ratio.
    var guard = 0
    while ((cellWidth * cols > size.width || cellHeight * rows > size.height) && guard < 4) {
        val widthScale = if (cellWidth * cols > size.width) size.width / (cellWidth * cols) else 1f
        val heightScale = if (cellHeight * rows > size.height) size.height / (cellHeight * rows) else 1f
        paint.textSize *= minOf(widthScale, heightScale)
        cellWidth = paint.measureText("M")
        val fm2 = paint.fontMetrics
        cellHeight = fm2.descent - fm2.ascent
        guard++
    }
    val baseline = -paint.fontMetrics.ascent

    drawRect(Color(colors.background), Offset.Zero, Size(size.width, size.height))

    fun cellX(column: Int) = column * cellWidth
    fun cellY(row: Int) = row * cellHeight

    for ((row, line) in lines.withIndex()) {
        for ((column, cell) in line.withIndex()) {
            if (cell.cont) continue
            if (cell.bg != colors.background) {
                drawRect(
                    Color(cell.bg),
                    Offset(cellX(column), cellY(row)),
                    Size(if (cell.wide) cellWidth * 2 else cellWidth, cellHeight),
                )
            }
            if (cell.text.isNotEmpty() && cell.flags and TermFlags.HIDDEN == 0) {
                val reversed = cell.flags and TermFlags.REVERSED != 0
                paint.color = if (reversed) cell.bg else cell.fg
                paint.alpha = if (cell.flags and TermFlags.DIM != 0) 158 else 255
                paint.typeface = when {
                    cell.flags and TermFlags.BOLD != 0 && cell.flags and TermFlags.ITALIC != 0 -> typefaces.boldItalic
                    cell.flags and TermFlags.BOLD != 0 -> typefaces.bold
                    cell.flags and TermFlags.ITALIC != 0 -> typefaces.italic
                    else -> typefaces.regular
                } ?: android.graphics.Typeface.DEFAULT
                paint.isUnderlineText = cell.flags and TermFlags.UNDERLINE != 0
                paint.isStrikeThruText = cell.flags and TermFlags.STRUCK != 0
                canvas.drawText(cell.text, cellX(column), cellY(row) + baseline, paint)
            }
        }
    }
    paint.alpha = 255
    paint.isUnderlineText = false
    paint.isStrikeThruText = false

    // Cursor on top, in the terminal's foreground colour.
    if (emulator.cursorVisible && !closed) {
        val cx = emulator.cursorX
        val cy = emulator.cursorY
        if (cx < cols && cy < rows) {
            paint.color = colors.foreground
            drawRect(
                Color(colors.foreground),
                Offset(cellX(cx), cellY(cy)),
                Size(cellWidth, cellHeight),
            )
        }
    }
}

/** A canned PTY stream for the offline fixture: a prompt, coloured `ls`,
 *  wide glyphs, 256-colour and truecolour — no SSH, no host. */
fun fixtureTerminalBytes(): ByteArray = buildString {
    append("\u001B[1;32muser@host\u001B[0m:\u001B[1;34m~/gentle-mobile\u001B[0m$ ")
    append("\u001B[1;31mls --color=auto\u001B[0m\r\n")
    append("\u001B[01;34msrc\u001B[0m  \u001B[01;34mui-android\u001B[0m  \u001B[01;32mrun.sh\u001B[0m  ")
    append("\u001B[0;36mlink\u001B[0m  \u001B[0;31mbroken\u001B[0m\r\n")
    append("$ echo 界 💥\r\n")
    append("界 💥\r\n")
    append("$ \u001B[38;5;196mred256\u001B[0m \u001B[38;2;120;200;255mtruecolour\u001B[0m\r\n")
    append("red256 truecolour\r\n")
    append("$ ")
}.toByteArray(Charsets.UTF_8)

/** The offline terminal proof (T42): the real renderer over a canned PTY
 *  stream, so colours, wide characters, the cursor and the four palettes
 *  can be seen without sshd. Launch with `--ez terminal_fixture true`. */
@Composable
fun TerminalFixtureScreen(theme: AppTheme, onBack: () -> Unit) {
    val palette = LocalPalette.current
    val terminalColors = remember(theme) { TerminalPalette.forTheme(theme) }
    val emulator = remember(theme) {
        TerminalEmulator(64, 24, terminalColors).apply { feed(fixtureTerminalBytes()) }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg)
            .safeDrawingPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = 4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_back_chevron),
                    contentDescription = stringResource(R.string.inbox_back),
                    tint = palette.accent,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Terminal · fixture (${theme.label})",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val density = LocalDensity.current
            val fontSizePx = with(density) { TERMINAL_FONT_SP.toPx() }
            TerminalCanvas(
                emulator = emulator,
                colors = terminalColors,
                fontSizePx = fontSizePx,
                closed = false,
                starting = false,
                error = null,
                frameVersion = 0,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
