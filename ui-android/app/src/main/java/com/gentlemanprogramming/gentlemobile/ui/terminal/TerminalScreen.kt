package com.gentlemanprogramming.gentlemobile.ui.terminal

import android.graphics.Paint
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.gentlemanprogramming.gentlemobile.R
import com.gentlemanprogramming.gentlemobile.core.TerminalSession
import com.gentlemanprogramming.gentlemobile.dictation.LocalDictationState
import com.gentlemanprogramming.gentlemobile.dictation.LocalWhisperDictation
import com.gentlemanprogramming.gentlemobile.dictation.WordReplacement
import com.gentlemanprogramming.gentlemobile.executor
import com.gentlemanprogramming.gentlemobile.mainHandler
import com.gentlemanprogramming.gentlemobile.ui.session.Attachment
import com.gentlemanprogramming.gentlemobile.ui.session.AttachmentState
import com.gentlemanprogramming.gentlemobile.ui.session.PaneSwitcherModel
import com.gentlemanprogramming.gentlemobile.ui.session.ShortcutAction
import com.gentlemanprogramming.gentlemobile.ui.session.ShortcutKey
import com.gentlemanprogramming.gentlemobile.ui.session.ShortcutPad
import com.gentlemanprogramming.gentlemobile.ui.session.TerminalKeyBar
import com.gentlemanprogramming.gentlemobile.ui.session.bytes
import com.gentlemanprogramming.gentlemobile.ui.session.formatBytes
import com.gentlemanprogramming.gentlemobile.ui.theme.Air
import com.gentlemanprogramming.gentlemobile.ui.theme.AppTheme
import com.gentlemanprogramming.gentlemobile.ui.theme.LocalTerminalFace
import com.gentlemanprogramming.gentlemobile.ui.theme.LocalPalette
import com.gentlemanprogramming.gentlemobile.ui.workspaces.WorkspacesModel
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private val TERMINAL_FONT_SP = 14.sp

/** The blink period in ms — the same 530 ms [TerminalCursor] expects. */
private const val BLINK_PERIOD_MS = 530L

/**
 * The only session view (T48): the PTY's byte stream drawn as a cell grid,
 * full bleed. The top bar (back · panes · refresh, 44 dp) stays tappable
 * while the keyboard is up; the key row and the composer sit at the bottom
 * above the IME. The panes button opens the workspace's pane/tab list with a
 * `+` to create a tab; picking one focuses it on the host.
 *
 * The composer is a normal field: the phone keyboard's own voice-to-text
 * types into it. When the terminal runs an agent (the app launched one), the
 * composer also offers camera and clip — an image uploads over SFTP and its
 * `[attached image: <path>]` line is typed into the PTY on send. A bare
 * shell offers no attachments: nothing would receive them.
 */
@Composable
fun TerminalScreen(
    title: String,
    theme: AppTheme,
    session: TerminalSession?,
    starting: Boolean,
    error: String?,
    attachments: List<Attachment>,
    thumbnails: Map<Long, ImageBitmap>,
    paneRows: List<PaneSwitcherModel.Row>,
    tabRows: List<PaneSwitcherModel.TabRow>,
    tabError: String?,
    onStart: (cols: Int, rows: Int) -> Unit,
    onBack: () -> Unit,
    onCreateTab: () -> Unit,
    onDeleteTab: (PaneSwitcherModel.TabRow) -> Unit,
    onPaneSelected: (PaneSwitcherModel.Row) -> Unit,
    onCamera: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onRetryUpload: (Long) -> Unit,
    onRemoveAttachment: (Long) -> Unit,
    onAttachmentsConsumed: () -> Unit,
    cursorMode: CursorMode,
    cursorBlink: Boolean,
    useComposer: Boolean,
    onUseComposerChange: (Boolean) -> Unit,
    sendOnEnter: Boolean,
    shortcutPad: ShortcutPad,
    dictationEnabled: Boolean,
    autoSend: Boolean,
    activeModelPath: String?,
    replacements: List<WordReplacement>,
) {
    val palette = LocalPalette.current
    val terminalColors = remember(theme) { TerminalPalette.forTheme(theme) }
    val terminalTypefaces = rememberTerminalTypefaces(LocalTerminalFace.current)
    var emulator by remember { mutableStateOf<TerminalEmulator?>(null) }
    var frameVersion by remember { mutableStateOf(0) }
    var closed by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf(TextFieldValue("")) }
    val composer = remember { TerminalComposer() }
    var paneSheet by remember { mutableStateOf(false) }
    var padOpen by remember { mutableStateOf(false) }
    var linesOpen by remember { mutableStateOf(false) }
    var padLocked by remember { mutableStateOf(false) }
    var clipboardNotice by remember { mutableStateOf<String?>(null) }
    var blinkPhase by remember { mutableStateOf(0L) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = LocalClipboardManager.current
    val clipboardEmptyMessage = stringResource(R.string.cd_clipboard_empty)

    // T54 (S8): local dictation — record with the microphone, transcribe on
    // Stop, and land the text in the field (auto-sent only when the setting
    // says so). The mic is offered only when local Whisper is the resolved
    // engine; otherwise the phone keyboard's own voice input applies.
    var dictationState by remember { mutableStateOf<LocalDictationState>(LocalDictationState.Idle) }
    val whisperDictation = remember(activeModelPath) {
        LocalWhisperDictation(
            modelPathProvider = { activeModelPath },
            replacementsProvider = { replacements },
            executor = executor,
            mainHandler = mainHandler,
        )
    }
    DisposableEffect(whisperDictation) {
        onDispose { whisperDictation.dispose() }
    }
    fun submitNow() {
        val suffixes = attachments.mapNotNull {
            (it.state as? AttachmentState.Uploaded)?.promptSuffix
        }
        val bytes = composer.submit() + TerminalAttachments.suffixBytes(suffixes)
        if (bytes.isNotEmpty()) session?.write(bytes)
        input = TextFieldValue("")
        if (suffixes.isNotEmpty()) onAttachmentsConsumed()
    }
    fun dictationListener() = object : LocalWhisperDictation.Listener {
        override fun onState(state: LocalDictationState) {
            dictationState = state
        }

        override fun onFinal(text: String, elapsedMillis: Long) {
            dictationState = LocalDictationState.Idle
            if (text.isNotBlank()) {
                input = TextFieldValue(mergeDictated(input.text, text))
                if (autoSend) submitNow()
            }
        }
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            whisperDictation.start(dictationListener())
        } else {
            dictationState = LocalDictationState.Error(
                context.getString(R.string.dict_permission_refused),
            )
        }
    }
    fun startDictation() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            whisperDictation.start(dictationListener())
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    fun stopDictation() = whisperDictation.stop(dictationListener())
    fun cancelDictation() = whisperDictation.cancel(dictationListener())

    fun pressKey(key: ShortcutKey) {
        val bytes = key.bytes(composer.ctrlSticky, composer.altSticky)
        if (bytes.isNotEmpty()) {
            session?.write(bytes)
            composer.disarm()
        }
    }

    fun pressChip(key: ShortcutKey) {
        when ((key.action as? ShortcutAction.Key)?.name) {
            "ctrl" -> composer.toggleCtrl()
            "alt" -> composer.toggleAlt()
            else -> pressKey(key)
        }
    }

    fun pasteClipboard() {
        val text = clipboard.getText()?.text.orEmpty()
        if (text.isEmpty()) {
            clipboardNotice = clipboardEmptyMessage
        } else {
            session?.write(text.toByteArray(Charsets.UTF_8))
        }
    }

    LaunchedEffect(clipboardNotice) {
        if (clipboardNotice != null) {
            delay(4000)
            clipboardNotice = null
        }
    }

    LaunchedEffect(cursorBlink) {
        while (cursorBlink) {
            kotlinx.coroutines.delay(530L)
            blinkPhase++
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg)
            .safeDrawingPadding()
            .imePadding(),
    ) {
        TerminalTopBar(title = title, onBack = onBack, onOpenPanes = { paneSheet = true })
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val density = LocalDensity.current
            val fontSizePx = with(density) { TERMINAL_FONT_SP.toPx() }
            // The cell size is measured at the paint size the renderer uses,
            // so the derived grid is exactly the grid that is painted — the
            // PTY, the buffer and the canvas can never disagree on rows.
            val cell = rememberTerminalCellSize(terminalTypefaces, fontSizePx)
            val grid = TerminalGeometry.derive(
                constraints.maxWidth.toFloat(),
                constraints.maxHeight.toFloat(),
                cell.widthPx,
                cell.heightPx,
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
                    // A resize must re-paint immediately, never wait for the
                    // next PTY byte — the keyboard open/close redraws at once.
                    frameVersion++
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
                typefaces = terminalTypefaces,
                fontSizePx = fontSizePx,
                closed = closed,
                starting = starting && emulator == null,
                error = error,
                frameVersion = frameVersion,
                cursorMode = cursorMode,
                cursorBlink = cursorBlink,
                blinkPhase = blinkPhase,
                modifier = Modifier.fillMaxSize(),
            )
            if (clipboardNotice != null) {
                Text(
                    text = clipboardNotice.orEmpty(),
                    color = palette.muted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.surfaceRaised.copy(alpha = 0.92f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            if (padOpen) {
                FloatingPad(
                    locked = padLocked,
                    onToggleLock = { padLocked = !padLocked },
                    onDismiss = { padOpen = false },
                    onKey = ::pressChip,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
        TerminalKeyRow(
            composer = composer,
            useComposer = useComposer,
            padOpen = padOpen,
            linesOpen = linesOpen,
            onUseComposerChange = onUseComposerChange,
            onTogglePad = { padOpen = !padOpen },
            onToggleLines = { linesOpen = !linesOpen },
            onPasteClipboard = ::pasteClipboard,
            onKey = { bytes -> session?.write(bytes) },
        )
        if (linesOpen) {
            TerminalKeysInlineRow(
                pad = shortcutPad,
                composer = composer,
                onKey = { bytes -> session?.write(bytes) },
            )
        }
        if (useComposer) {
            TerminalComposerRow(
                enabled = session != null && !closed,
                attachments = attachments,
                thumbnails = thumbnails,
                value = input,
                sendOnEnter = sendOnEnter,
                dictationEnabled = dictationEnabled,
                dictationState = dictationState,
                onValueChange = { new ->
                    val delta = composer.commit(new.text)
                    if (delta.isNotEmpty()) session?.write(delta)
                    input = if (composer.fieldText == new.text) new else TextFieldValue(composer.fieldText)
                },
                onSubmit = { submitNow() },
                onStartDictation = { startDictation() },
                onStopDictation = { stopDictation() },
                onCancelDictation = { cancelDictation() },
                onCamera = onCamera,
                onPickImage = onPickImage,
                onPickFile = onPickFile,
                onRetryUpload = onRetryUpload,
                onRemoveAttachment = onRemoveAttachment,
            )
        } else {
            TerminalDirectInputRow(
                enabled = session != null && !closed,
                onBytes = { bytes -> session?.write(bytes) },
            )
        }
    }

    if (paneSheet) {
        TerminalPaneSheet(
            rows = paneRows,
            tabRows = tabRows,
            tabError = tabError,
            onDismiss = { paneSheet = false },
            onPick = { row ->
                paneSheet = false
                onPaneSelected(row)
            },
            onCreateTab = {
                paneSheet = false
                onCreateTab()
            },
            onDeleteTab = onDeleteTab,
        )
    }
}

@Composable
private fun TerminalTopBar(title: String, onBack: () -> Unit, onOpenPanes: () -> Unit) {
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
        Box(
            modifier = Modifier
                .size(44.dp)
                .clickable(onClick = onOpenPanes),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_pane),
                contentDescription = stringResource(R.string.session_panes),
                tint = palette.accent,
                modifier = Modifier.size(18.dp),
            )
        }
        Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
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
private fun TerminalKeyRow(
    composer: TerminalComposer,
    useComposer: Boolean,
    padOpen: Boolean,
    linesOpen: Boolean,
    onUseComposerChange: (Boolean) -> Unit,
    onTogglePad: () -> Unit,
    onToggleLines: () -> Unit,
    onPasteClipboard: () -> Unit,
    onKey: (ByteArray) -> Unit,
) {
    fun press(key: ShortcutKey) {
        val bytes = key.bytes(composer.ctrlSticky, composer.altSticky)
        if (bytes.isNotEmpty()) {
            onKey(bytes)
            composer.disarm()
        }
    }

    fun pressChip(key: ShortcutKey) {
        when ((key.action as? ShortcutAction.Key)?.name) {
            "ctrl" -> composer.toggleCtrl()
            "alt" -> composer.toggleAlt()
            else -> press(key)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TerminalKeyBar.barChips().forEach { chip ->
            val name = (chip.action as? ShortcutAction.Key)?.name
            val pressed = name == "ctrl" && composer.ctrlSticky
            TerminalKeyChip(
                label = chip.label,
                pressed = pressed,
                modifier = Modifier.weight(1f),
            ) { pressChip(chip) }
        }
        BarIcon(
            drawable = R.drawable.ic_arrows,
            description = stringResource(R.string.cd_pad),
            selected = padOpen,
            onClick = onTogglePad,
        )
        BarIcon(
            drawable = R.drawable.ic_lines,
            description = stringResource(R.string.cd_lines),
            selected = linesOpen,
            onClick = onToggleLines,
        )
        BarIcon(
            drawable = R.drawable.ic_clipboard,
            description = stringResource(R.string.cd_clipboard),
            selected = false,
            onClick = onPasteClipboard,
        )
        BarIcon(
            drawable = R.drawable.ic_chat,
            description = stringResource(R.string.cd_input_composer),
            selected = useComposer,
            onClick = { onUseComposerChange(!useComposer) },
        )
        BarIcon(
            drawable = R.drawable.ic_keyboard,
            description = stringResource(R.string.cd_input_direct),
            selected = !useComposer,
            onClick = { onUseComposerChange(false) },
        )
    }
}

/** One 44 dp icon of the bar; the selected surface is marked in the accent. */
@Composable
private fun BarIcon(
    drawable: Int,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) palette.selection else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        Icon(
            painter = painterResource(drawable),
            contentDescription = null,
            tint = if (selected) palette.accent else palette.muted,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** The inline keys row (T59): toggled by the lines icon — the shortcut
 *  pad's own keys, in their order. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TerminalKeysInlineRow(
    pad: ShortcutPad,
    composer: TerminalComposer,
    onKey: (ByteArray) -> Unit,
) {
    fun press(key: ShortcutKey) {
        val bytes = key.bytes(composer.ctrlSticky, composer.altSticky)
        if (bytes.isNotEmpty()) {
            onKey(bytes)
            composer.disarm()
        }
    }

    fun pressChip(key: ShortcutKey) {
        when ((key.action as? ShortcutAction.Key)?.name) {
            "ctrl" -> composer.toggleCtrl()
            "alt" -> composer.toggleAlt()
            else -> press(key)
        }
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        TerminalKeyBar.inlineKeys(pad).forEach { key ->
            val name = (key.action as? ShortcutAction.Key)?.name
            val pressed = (name == "ctrl" && composer.ctrlSticky) ||
                (name == "alt" && composer.altSticky)
            PanelKeyChip(label = key.label, pressed = pressed) { pressChip(key) }
        }
    }
}

/** A fixed-size chip in the keys panel (no weight: the panel flows). */
@Composable
private fun PanelKeyChip(label: String, pressed: Boolean, onPress: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(2.dp)
            .height(40.dp)
            .widthIn(min = 52.dp)
            .border(
                1.dp,
                if (pressed) palette.accentEdge else palette.line,
                RoundedCornerShape(8.dp),
            )
            .background(if (pressed) palette.selection else palette.surfaceRaised, RoundedCornerShape(8.dp))
            .clickable(onClick = onPress)
            .padding(horizontal = 10.dp),
    ) {
        Text(
            text = label,
            color = if (pressed) palette.accentEdge else MaterialTheme.colorScheme.onBackground,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
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



/**
 * The terminal composer (T49): the app's rounded field with its side
 * margins and the send button beside it, exactly the session composer's
 * shape (defect: it had lost its border and run edge to edge). When the
 * terminal runs an agent, the field carries camera and clip inside; a bare
 * shell keeps a plain field, because nothing would receive an attachment.
 *
 * T54 (S8): when local Whisper is the resolved engine, the circle is the
 * microphone while the field is empty (and Stop while listening), and a
 * one-line status above the field reports listening, transcribing or the
 * honest error. Otherwise the circle stays send and the phone keyboard's
 * own voice input types into the field.
 */
@Composable
private fun TerminalComposerRow(
    enabled: Boolean,
    attachments: List<Attachment>,
    thumbnails: Map<Long, ImageBitmap>,
    value: TextFieldValue,
    sendOnEnter: Boolean,
    dictationEnabled: Boolean,
    dictationState: LocalDictationState,
    onValueChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    onStartDictation: () -> Unit,
    onStopDictation: () -> Unit,
    onCancelDictation: () -> Unit,
    onCamera: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onRetryUpload: (Long) -> Unit,
    onRemoveAttachment: (Long) -> Unit,
) {
    val palette = LocalPalette.current
    var attachmentsSheet by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        when (val state = dictationState) {
            is LocalDictationState.Listening -> DictationStatusLine(
                text = stringResource(R.string.comp_listening),
                onStop = onStopDictation,
                onCancel = onCancelDictation,
            )
            is LocalDictationState.Transcribing -> DictationStatusLine(
                text = stringResource(R.string.voz_test_transcribing),
            )
            is LocalDictationState.Error -> DictationStatusLine(
                text = state.message,
                error = true,
            )
            else -> {}
        }
        attachments.forEach { attachment ->
            TerminalAttachmentRow(
                attachment = attachment,
                thumbnail = thumbnails[attachment.id],
                onRetry = { onRetryUpload(attachment.id) },
                onRemove = { onRemoveAttachment(attachment.id) },
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp, max = 96.dp)
                    .clip(RoundedCornerShape(Air.RADIUS_TERMINAL_DP.dp))
                    .background(palette.surface, RoundedCornerShape(Air.RADIUS_TERMINAL_DP.dp))
                    .border(1.dp, palette.line, RoundedCornerShape(Air.RADIUS_TERMINAL_DP.dp))
                    .padding(horizontal = 2.dp),
            ) {
                ComposerIcon(
                    drawable = R.drawable.ic_attach,
                    description = stringResource(R.string.cd_attach),
                    enabled = enabled,
                    onClick = { attachmentsSheet = true },
                )
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = if (enabled) MaterialTheme.colorScheme.onBackground else palette.textDisabled,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                    cursorBrush = SolidColor(palette.accent),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        imeAction = if (sendOnEnter) ImeAction.Send else ImeAction.None,
                    ),
                    keyboardActions = if (sendOnEnter) {
                        KeyboardActions(onSend = { onSubmit() })
                    } else {
                        KeyboardActions()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 12.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            ComposerCircle(
                enabled = enabled,
                dictationEnabled = dictationEnabled,
                fieldEmpty = value.text.isBlank(),
                dictationState = dictationState,
                onSubmit = onSubmit,
                onStartDictation = onStartDictation,
                onStopDictation = onStopDictation,
            )
        }
    }
    if (attachmentsSheet) {
        TerminalAttachmentsSheet(
            onDismiss = { attachmentsSheet = false },
            onCamera = {
                attachmentsSheet = false
                onCamera()
            },
            onPickImage = {
                attachmentsSheet = false
                onPickImage()
            },
            onPickFile = {
                attachmentsSheet = false
                onPickFile()
            },
        )
    }
}

/** The circle: mic while the field is empty and dictation is live, stop
 *  while listening, send otherwise. 44 dp, accent, Moshi's one control. */
@Composable
private fun ComposerCircle(
    enabled: Boolean,
    dictationEnabled: Boolean,
    fieldEmpty: Boolean,
    dictationState: LocalDictationState,
    onSubmit: () -> Unit,
    onStartDictation: () -> Unit,
    onStopDictation: () -> Unit,
) {
    val palette = LocalPalette.current
    val listening = dictationState is LocalDictationState.Listening
    val transcribing = dictationState is LocalDictationState.Transcribing
    val showMic = dictationEnabled && (listening || (fieldEmpty && !transcribing))
    val clickable = enabled && if (showMic) !transcribing else true
    val onClick = when {
        !showMic -> onSubmit
        listening -> onStopDictation
        else -> onStartDictation
    }
    val description = when {
        listening -> stringResource(R.string.cd_mic_stop)
        showMic -> stringResource(R.string.cd_mic)
        else -> stringResource(R.string.cd_send)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(palette.accent)
            .clickable(enabled = clickable, onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        Icon(
            painter = painterResource(if (showMic) R.drawable.ic_mic else R.drawable.ic_send),
            contentDescription = null,
            tint = palette.bg,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** The one quiet line above the composer: listening/transcribing/error. */
@Composable
private fun DictationStatusLine(
    text: String,
    error: Boolean = false,
    onStop: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
    ) {
        Text(
            text = text,
            color = if (error) palette.error else palette.muted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (onStop != null) {
            Text(
                text = stringResource(R.string.comp_stop),
                color = palette.accentEdge,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable(onClick = onStop)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (onCancel != null) {
            Text(
                text = stringResource(R.string.comp_cancel),
                color = palette.muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ComposerIcon(drawable: Int, description: String, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Icon(
            painter = painterResource(drawable),
            contentDescription = description,
            tint = if (enabled) palette.text else palette.muted,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** The floating key pad (T59): a cross of big keys over the terminal with a
 *  transparent surround, a drag handle and a lock toggle. Locked it stays;
 *  unlocked it can be dragged lower and dismissed. */
@Composable
private fun FloatingPad(
    locked: Boolean,
    onToggleLock: () -> Unit,
    onDismiss: () -> Unit,
    onKey: (ShortcutKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    var offsetY by remember { mutableStateOf(0f) }
    val dismissThresholdPx = with(LocalDensity.current) { 120.dp.toPx() }
    val lockDescription = stringResource(if (locked) R.string.cd_unlock_pad else R.string.cd_lock_pad)
    Box(modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .offset { IntOffset(0, offsetY.roundToInt()) }
                .padding(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .clickable(onClick = onToggleLock)
                        .semantics { contentDescription = lockDescription },
                ) {
                    Icon(
                        painter = painterResource(if (locked) R.drawable.ic_lock else R.drawable.ic_lock_open),
                        contentDescription = null,
                        tint = palette.text,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .width(120.dp)
                        .height(32.dp)
                        .pointerInput(locked) {
                            if (!locked) {
                                detectVerticalDragGestures(
                                    onDragEnd = {
                                        if (offsetY > dismissThresholdPx) onDismiss() else offsetY = 0f
                                    },
                                    onVerticalDrag = { change, dragAmount ->
                                        change.consume()
                                        offsetY = (offsetY + dragAmount).coerceAtLeast(0f)
                                    },
                                )
                            }
                        },
                ) {
                    Box(
                        Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(if (locked) palette.muted else palette.text),
                    )
                }
                Spacer(Modifier.width(44.dp))
            }
            PadCross(onKey = onKey)
        }
    }
}

/** The pad's fixed cross: backspace, up, clear on top; left, Enter, right in
 *  the middle; down at the bottom — the seven keys in row-major order. */
@Composable
private fun PadCross(onKey: (ShortcutKey) -> Unit) {
    val keys = TerminalKeyBar.padKeys()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PadKey(keys[0], onKey)
            PadKey(keys[1], onKey)
            PadKey(keys[2], onKey)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PadKey(keys[3], onKey)
            PadKey(keys[4], onKey)
            PadKey(keys[5], onKey)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(56.dp))
            PadKey(keys[6], onKey)
            Spacer(Modifier.size(56.dp))
        }
    }
}

/** One big key of the floating pad: a filled plate, never a bare glyph on
 *  the transparent surround. */
@Composable
private fun PadKey(key: ShortcutKey, onKey: (ShortcutKey) -> Unit) {
    val palette = LocalPalette.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(3.dp)
            .size(50.dp)
            .border(1.dp, palette.line, RoundedCornerShape(10.dp))
            .background(palette.surfaceRaised, RoundedCornerShape(10.dp))
            .clickable { onKey(key) },
    ) {
        when (key.id) {
            "pad_backspace" -> Icon(
                painter = painterResource(R.drawable.ic_backspace),
                contentDescription = key.label,
                tint = palette.text,
                modifier = Modifier.size(22.dp),
            )
            "pad_clear" -> Icon(
                painter = painterResource(R.drawable.ic_broom),
                contentDescription = key.label,
                tint = palette.text,
                modifier = Modifier.size(22.dp),
            )
            else -> Text(
                text = key.label,
                color = palette.text,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
    }
}

/** The attachment sheet (T59): Cámara, Foto o video, Archivo — the three
 *  upload sources behind the composer's `+`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerminalAttachmentsSheet(
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
) {
    val p = LocalPalette.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            TerminalKeyBar.attachmentRows().forEach { kind ->
                val drawable = when (kind) {
                    TerminalKeyBar.AttachmentKind.CAMERA -> R.drawable.ic_camera
                    TerminalKeyBar.AttachmentKind.GALLERY -> R.drawable.ic_clip
                    TerminalKeyBar.AttachmentKind.FILE -> R.drawable.ic_file
                }
                val label = when (kind) {
                    TerminalKeyBar.AttachmentKind.CAMERA -> R.string.att_camera
                    TerminalKeyBar.AttachmentKind.GALLERY -> R.string.att_gallery
                    TerminalKeyBar.AttachmentKind.FILE -> R.string.att_file
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clickable {
                            when (kind) {
                                TerminalKeyBar.AttachmentKind.CAMERA -> onCamera()
                                TerminalKeyBar.AttachmentKind.GALLERY -> onPickImage()
                                TerminalKeyBar.AttachmentKind.FILE -> onPickFile()
                            }
                        },
                ) {
                    Icon(
                        painter = painterResource(drawable),
                        contentDescription = null,
                        tint = p.text,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(label),
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 15.sp,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * The direct input row (T50, T58): with "use the field" off there is no
 * visible composer — an invisible field keeps the IME up and every keystroke
 * reaches the PTY the moment it is typed. Enter sends one CR through the
 * keyboard's send action.
 */
@Composable
private fun TerminalDirectInputRow(enabled: Boolean, onBytes: (ByteArray) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue("")) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(100)
        focusRequester.requestFocus()
    }
    BasicTextField(
        value = value,
        onValueChange = { new ->
            val delta = TerminalKeys.forwardInput(value.text, new.text)
            if (delta.isNotEmpty()) onBytes(delta)
            value = TextFieldValue("")
        },
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(
            color = Color.Transparent,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
        ),
        cursorBrush = SolidColor(Color.Transparent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            imeAction = ImeAction.Send,
        ),
        keyboardActions = KeyboardActions(onSend = { onBytes(byteArrayOf(0x0D)) }),
        modifier = Modifier
            .focusRequester(focusRequester)
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    )
}

@Composable
private fun TerminalAttachmentRow(
    attachment: Attachment,
    thumbnail: ImageBitmap?,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    val palette = LocalPalette.current
    val removeCd = stringResource(R.string.att_remove_cd, attachment.displayName)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = attachment.displayName,
                color = palette.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when (val state = attachment.state) {
                is AttachmentState.Uploading -> Text(
                    text = stringResource(
                        R.string.att_uploading,
                        if (attachment.sizeBytes > 0) ((state.sentBytes * 100) / attachment.sizeBytes).toInt().coerceIn(0, 100) else 0,
                        formatBytes(state.sentBytes),
                        formatBytes(attachment.sizeBytes),
                    ),
                    color = palette.muted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                is AttachmentState.Uploaded -> Text(
                    text = stringResource(R.string.att_uploaded, formatBytes(attachment.sizeBytes), state.remotePath),
                    color = palette.success,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                is AttachmentState.Failed -> Text(
                    text = stringResource(R.string.att_failed, state.message),
                    color = palette.error,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AttachmentState.Ready -> Text(
                    text = stringResource(R.string.att_ready, formatBytes(attachment.sizeBytes)),
                    color = palette.muted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        if (attachment.state is AttachmentState.Failed) {
            Text(
                text = stringResource(R.string.att_retry),
                color = palette.accentEdge,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 8.dp, vertical = 12.dp),
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clickable(onClick = onRemove),
        ) {
            Text(text = "✕", color = palette.muted, fontSize = 15.sp)
        }
    }
}

/** The workspace's pane/tab list (T47/T51): tabs, each with a delete
 *  action, the panes under each, and a `+` that creates a tab. Picking a
 *  pane focuses it on the host; deleting a tab kills its processes, so it
 *  asks first — and the workspace's last tab is refused, never deleted. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerminalPaneSheet(
    rows: List<PaneSwitcherModel.Row>,
    tabRows: List<PaneSwitcherModel.TabRow>,
    tabError: String?,
    onDismiss: () -> Unit,
    onPick: (PaneSwitcherModel.Row) -> Unit,
    onCreateTab: () -> Unit,
    onDeleteTab: (PaneSwitcherModel.TabRow) -> Unit,
) {
    val p = LocalPalette.current
    var pendingDelete by remember { mutableStateOf<PaneSwitcherModel.TabRow?>(null) }
    var refusedLast by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = stringResource(R.string.session_panes),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            tabRows.forEach { tabRow ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp),
                ) {
                    Text(
                        text = tabRow.label,
                        color = p.muted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(44.dp)
                            .clickable {
                                if (tabRow.deletable) pendingDelete = tabRow else refusedLast = true
                            },
                    ) {
                        Text(
                            text = "✕",
                            color = if (tabRow.deletable) p.error else p.textDisabled,
                            fontSize = 15.sp,
                        )
                    }
                }
                rows.filter { it.tabId == tabRow.tabId }.forEach { row ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .padding(start = 16.dp)
                            .clickable { onPick(row) },
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = row.title,
                                color = if (row.agentPane) p.accent else MaterialTheme.colorScheme.onBackground,
                                fontSize = 15.sp,
                                fontWeight = if (row.current) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = paneStateWord(row),
                                color = p.muted,
                                fontSize = 11.sp,
                            )
                        }
                        if (row.current) {
                            Text(text = "‹", color = p.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clickable(onClick = onCreateTab),
            ) {
                Text(text = "+", color = p.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.terminal_new_tab),
                    color = p.accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (tabError != null) {
                Text(
                    text = tabError,
                    color = p.error,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    pendingDelete?.let { tab ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.set_tab_delete_title), fontWeight = FontWeight.SemiBold) },
            text = { Text(stringResource(R.string.set_tab_delete_body, tab.label), fontSize = 13.sp, lineHeight = 18.sp) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDeleteTab(tab)
                }) {
                    Text(stringResource(R.string.hosts_delete), color = p.error, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.set_cancel), color = p.muted)
                }
            },
            containerColor = p.surfaceRaised,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            textContentColor = p.muted,
        )
    }
    if (refusedLast) {
        AlertDialog(
            onDismissRequest = { refusedLast = false },
            title = { Text(stringResource(R.string.set_tab_delete_title), fontWeight = FontWeight.SemiBold) },
            text = { Text(stringResource(R.string.set_tab_delete_last), fontSize = 13.sp, lineHeight = 18.sp) },
            confirmButton = {
                TextButton(onClick = { refusedLast = false }) {
                    Text(stringResource(R.string.set_cancel), color = p.accentEdge)
                }
            },
            containerColor = p.surfaceRaised,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            textContentColor = p.muted,
        )
    }
}

@Composable
private fun paneStateWord(row: PaneSwitcherModel.Row): String = when (val state = row.state) {
    is PaneSwitcherModel.State.Agent ->
        WorkspacesModel.statusWordRes(state.status)?.let { stringResource(it) }
            ?: stringResource(R.string.status_unknown)
    PaneSwitcherModel.State.Shell -> stringResource(R.string.session_pane_shell)
}

/** The cell size the renderer paints at: measured at [fontSizePx] with the
 *  resolved terminal face, so the derived grid is the painted grid. */
private class TerminalCellSize(val widthPx: Float, val heightPx: Float)

@Composable
private fun rememberTerminalCellSize(typefaces: TerminalTypefaces, fontSizePx: Float): TerminalCellSize {
    return remember(typefaces, fontSizePx) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.typeface = typefaces.regular ?: android.graphics.Typeface.DEFAULT
        paint.textSize = fontSizePx
        val fm = paint.fontMetrics
        TerminalCellSize(paint.measureText("M"), fm.descent - fm.ascent)
    }
}

@Composable
private fun TerminalCanvas(
    emulator: TerminalEmulator?,
    colors: TermColors,
    typefaces: TerminalTypefaces,
    fontSizePx: Float,
    closed: Boolean,
    starting: Boolean,
    error: String?,
    frameVersion: Int,
    cursorMode: CursorMode,
    cursorBlink: Boolean,
    blinkPhase: Long,
    modifier: Modifier,
) {
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
        drawTerminal(e, colors, typefaces, fontSizePx, closed, cursorMode, cursorBlink, blinkPhase)
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
    cursorMode: CursorMode,
    cursorBlink: Boolean,
    blinkPhase: Long,
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

    // The grid was derived from this exact cell size, so this loop is only a
    // safety net against a font engine that paints wider than it measures.
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

    // Cursor on top, shaped by the Advanced choice and hidden on the blink's
    // off phase. The block fills, underline and bar are fractions of the cell.
    val paintCursor = TerminalCursor.visible(
        emulator.cursorVisible,
        cursorBlink,
        blinkPhase * BLINK_PERIOD_MS,
    )
    if (paintCursor && !closed) {
        val cx = emulator.cursorX
        val cy = emulator.cursorY
        if (cx < cols && cy < rows) {
            val rect = TerminalCursor.rect(cursorMode)
            val left = cellX(cx) + rect.left * cellWidth
            val top = cellY(cy) + rect.top * cellHeight
            val right = cellX(cx) + rect.right * cellWidth
            val bottom = cellY(cy) + rect.bottom * cellHeight
            drawRect(
                Color(colors.foreground),
                Offset(left, top),
                Size(right - left, bottom - top),
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
    val terminalTypefaces = rememberTerminalTypefaces(LocalTerminalFace.current)
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
                typefaces = terminalTypefaces,
                fontSizePx = fontSizePx,
                closed = false,
                starting = false,
                error = null,
                frameVersion = 0,
                cursorMode = CursorMode.BLOCK,
                cursorBlink = false,
                blinkPhase = 0,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The transcript merge rule (T54, S8): the dictated text is appended to the
 * typed text with exactly one separating space; a blank field takes the
 * transcript as-is. Typed text is never dropped or duplicated.
 */
private fun mergeDictated(base: String, spoken: String): String {
    val spokenTrimmed = spoken.trim()
    if (spokenTrimmed.isEmpty()) return base
    val baseTrimmed = base.trimEnd()
    if (baseTrimmed.isEmpty()) return spokenTrimmed
    return "$baseTrimmed $spokenTrimmed"
}
