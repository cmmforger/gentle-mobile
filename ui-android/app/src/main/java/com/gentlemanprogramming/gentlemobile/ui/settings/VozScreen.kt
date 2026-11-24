package com.gentlemanprogramming.gentlemobile.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.gentlemanprogramming.gentlemobile.R
import com.gentlemanprogramming.gentlemobile.dictation.DictationEngineChoice
import com.gentlemanprogramming.gentlemobile.dictation.LocalDictationState
import com.gentlemanprogramming.gentlemobile.dictation.LocalWhisperDictation
import com.gentlemanprogramming.gentlemobile.dictation.ModelStatus
import com.gentlemanprogramming.gentlemobile.dictation.WhisperCatalogue
import com.gentlemanprogramming.gentlemobile.dictation.WhisperModel
import com.gentlemanprogramming.gentlemobile.dictation.WordReplacement
import com.gentlemanprogramming.gentlemobile.dictation.VozSettings
import com.gentlemanprogramming.gentlemobile.executor
import com.gentlemanprogramming.gentlemobile.mainHandler
import com.gentlemanprogramming.gentlemobile.ui.session.formatBytes
import com.gentlemanprogramming.gentlemobile.ui.theme.Air
import com.gentlemanprogramming.gentlemobile.ui.theme.LocalPalette

/**
 * The Voz screen (T54, S8): the dictation settings — engine choice (phone
 * keyboard, which works today, vs local Whisper, which needs a model), the
 * auto-send switch, a live test transcription, the model table with
 * download/use/delete and the real on-disk size, and the word replacements
 * applied to every transcript.
 */
@Composable
fun VozScreen(
    settings: VozSettings,
    modelStatuses: Map<String, ModelStatus>,
    activeModelPath: String?,
    onSetEngine: (DictationEngineChoice) -> Unit,
    onSetAutoSend: (Boolean) -> Unit,
    onUseModel: (String) -> Unit,
    onDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onDeleteModel: (String) -> Unit,
    onReplacementsChange: (List<WordReplacement>) -> Unit,
    onBack: () -> Unit,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val activeModel = settings.activeModelId?.let { WhisperCatalogue.byId(it) }
    val activeInstalled = activeModel != null && modelStatuses[activeModel.id] is ModelStatus.Installed

    var testState by remember { mutableStateOf<LocalDictationState>(LocalDictationState.Idle) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testElapsed by remember { mutableStateOf(0L) }
    var confirmDelete by remember { mutableStateOf<WhisperModel?>(null) }
    var editingReplacement by remember { mutableStateOf<WordReplacement?>(null) }

    val dictation = remember {
        LocalWhisperDictation(
            modelPathProvider = { activeModelPath },
            replacementsProvider = { settings.replacements },
            executor = executor,
            mainHandler = mainHandler,
        )
    }
    DisposableEffect(Unit) {
        onDispose { dictation.dispose() }
    }
    val dictationListener = remember(dictation) {
        object : LocalWhisperDictation.Listener {
            override fun onState(state: LocalDictationState) {
                testState = state
                if (state is LocalDictationState.Idle) {
                    // The result line stays until the next run.
                }
            }

            override fun onFinal(text: String, elapsedMillis: Long) {
                testResult = text
                testElapsed = elapsedMillis
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            dictation.start(dictationListener)
        } else {
            testState = LocalDictationState.Error(
                context.getString(R.string.dict_permission_refused),
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Air.SIDE_DP.dp, vertical = Air.TOP_DP.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val backCd = stringResource(R.string.cd_back)
            Box(
                modifier = Modifier
                    .size(Air.TOUCH_DP.dp)
                    .clickable(onClick = onBack)
                    .semantics { contentDescription = backCd },
                contentAlignment = Alignment.Center,
            ) {
                Text("‹", color = p.accent, fontSize = 20.sp)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.voz_title),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = Air.TITLE_SP.sp,
                lineHeight = (Air.TITLE_SP * Air.TITLE_LINE_HEIGHT).sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(12.dp))

        SectionLabel(stringResource(R.string.voz_engine_label))
        Card {
            EngineRow(
                label = stringResource(R.string.voz_engine_keyboard),
                sub = stringResource(R.string.voz_engine_keyboard_sub),
                selected = settings.engine == DictationEngineChoice.PHONE_KEYBOARD,
                onClick = { onSetEngine(DictationEngineChoice.PHONE_KEYBOARD) },
            )
            Hairline()
            EngineRow(
                label = stringResource(R.string.voz_engine_whisper),
                sub = stringResource(R.string.voz_engine_whisper_sub),
                selected = settings.engine == DictationEngineChoice.LOCAL_WHISPER,
                onClick = { onSetEngine(DictationEngineChoice.LOCAL_WHISPER) },
            )
            if (settings.engine == DictationEngineChoice.LOCAL_WHISPER && !activeInstalled) {
                Note(stringResource(R.string.voz_engine_whisper_no_model))
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionLabel(stringResource(R.string.voz_auto_send_label))
        Card {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Air.TOUCH_DP.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.voz_auto_send),
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = Air.ROW_NAME_SP.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.voz_auto_send_sub),
                        color = p.muted,
                        fontSize = 13.sp,
                    )
                }
                VozSwitch(checked = settings.autoSend, onToggle = onSetAutoSend)
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionLabel(stringResource(R.string.voz_test_label))
        Card {
            val testEnabled = activeModelPath != null
            val label = when (testState) {
                is LocalDictationState.Listening -> stringResource(R.string.voz_test_listening)
                is LocalDictationState.Transcribing -> stringResource(R.string.voz_test_transcribing)
                else -> stringResource(R.string.voz_test_label)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Air.TOUCH_DP.dp)
                    .clickable(enabled = testEnabled && testState !is LocalDictationState.Transcribing) {
                        when (testState) {
                            is LocalDictationState.Listening -> dictation.stop(dictationListener)
                            else -> {
                                if (hasMicPermission(context)) {
                                    dictation.start(dictationListener)
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        }
                    },
            ) {
                Text(
                    text = label,
                    color = if (testEnabled) p.accent else p.textDisabled,
                    fontSize = Air.ROW_NAME_SP.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            if (!testEnabled) {
                Note(stringResource(R.string.voz_test_no_model))
            }
            if (testState is LocalDictationState.Error) {
                Note((testState as LocalDictationState.Error).message)
            }
            testResult?.let { text ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (text.isBlank()) {
                        stringResource(R.string.voz_test_empty)
                    } else {
                        stringResource(R.string.voz_test_result, text, testElapsed)
                    },
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = Air.COMMAND_SP.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionLabel(stringResource(R.string.voz_models_label))
        Card {
            WhisperCatalogue.models.forEach { model ->
                ModelRow(
                    model = model,
                    status = modelStatuses[model.id] ?: ModelStatus.NotInstalled,
                    active = model.id == settings.activeModelId && modelStatuses[model.id] is ModelStatus.Installed,
                    onDownload = { onDownload(model.id) },
                    onCancel = { onCancelDownload(model.id) },
                    onUse = { onUseModel(model.id) },
                    onDelete = { confirmDelete = model },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionLabel(stringResource(R.string.voz_replacements_label))
        Card {
            val deleteCd = stringResource(R.string.voz_delete)
            Text(
                text = stringResource(R.string.voz_replacements_sub),
                color = p.muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (settings.replacements.isEmpty()) {
                Note(stringResource(R.string.voz_replace_empty))
            }
            settings.replacements.forEach { replacement ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Air.TOUCH_DP.dp)
                        .clickable { editingReplacement = replacement },
                ) {
                    Text(
                        text = "${replacement.from}  →  ${replacement.to}",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = Air.COMMAND_SP.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(Air.TOUCH_DP.dp)
                            .clickable {
                                onReplacementsChange(settings.replacements - replacement)
                            }
                            .semantics { contentDescription = deleteCd },
                    ) {
                        Text("✕", color = p.muted, fontSize = 15.sp)
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Air.TOUCH_DP.dp)
                    .clickable { editingReplacement = WordReplacement("", "") },
            ) {
                Text(
                    text = stringResource(R.string.voz_add_replacement),
                    color = p.accent,
                    fontSize = Air.ROW_NAME_SP.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (confirmDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.voz_delete_confirm_title), fontWeight = FontWeight.SemiBold) },
            text = { Text(stringResource(R.string.voz_delete_confirm_body), fontSize = 13.sp, lineHeight = 18.sp) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete?.let { onDeleteModel(it.id) }
                    confirmDelete = null
                }) {
                    Text(stringResource(R.string.voz_delete), color = p.error, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text(stringResource(R.string.set_cancel), color = p.muted)
                }
            },
            containerColor = p.surfaceRaised,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            textContentColor = p.muted,
        )
    }
    if (editingReplacement != null) {
        ReplacementDialog(
            initial = editingReplacement!!,
            onSave = { replacement ->
                val others = settings.replacements - editingReplacement!!
                onReplacementsChange(others + replacement)
                editingReplacement = null
            },
            onDismiss = { editingReplacement = null },
        )
    }
}

@Composable
private fun EngineRow(label: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Air.TOUCH_DP.dp)
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = Air.ROW_NAME_SP.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = sub,
                color = p.muted,
                fontSize = 13.sp,
            )
        }
        if (selected) {
            Text("✓", color = p.accent, fontSize = 15.sp)
        }
    }
}

@Composable
private fun ModelRow(
    model: WhisperModel,
    status: ModelStatus,
    active: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onUse: () -> Unit,
    onDelete: () -> Unit,
) {
    val p = LocalPalette.current
    val deleteCd = stringResource(R.string.voz_delete)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Air.TOUCH_DP.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = model.displayName,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = Air.ROW_NAME_SP.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                when (status) {
                    is ModelStatus.Downloading -> Text(
                        text = stringResource(
                            R.string.voz_downloading,
                            formatBytes(status.receivedBytes),
                            status.totalBytes?.let { formatBytes(it) } ?: "…",
                        ),
                        color = p.muted,
                        fontSize = Air.META_SP.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    is ModelStatus.Installed -> Text(
                        text = stringResource(R.string.voz_installed_size, formatBytes(status.fileSizeBytes)),
                        color = p.muted,
                        fontSize = Air.META_SP.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    is ModelStatus.Failed -> Text(
                        text = stringResource(R.string.voz_download_failed, status.message),
                        color = p.error,
                        fontSize = Air.META_SP.sp,
                    )
                    else -> Text(
                        text = model.sizeLabel,
                        color = p.muted,
                        fontSize = Air.META_SP.sp,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            when (status) {
                is ModelStatus.Downloading -> MiniAction(stringResource(R.string.voz_cancel), p.accent, onCancel)
                is ModelStatus.Installed -> {
                    if (active) {
                        Text(
                            text = stringResource(R.string.voz_active),
                            color = p.accent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    } else {
                        MiniAction(stringResource(R.string.voz_use), p.accent, onUse)
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(Air.TOUCH_DP.dp)
                            .clickable(onClick = onDelete)
                            .semantics { contentDescription = deleteCd },
                    ) {
                        Text("✕", color = p.muted, fontSize = 15.sp)
                    }
                }
                is ModelStatus.Failed -> MiniAction(stringResource(R.string.voz_retry), p.accent, onDownload)
                else -> MiniAction(stringResource(R.string.voz_download), p.accent, onDownload)
            }
        }
        if (status is ModelStatus.Downloading) {
            val pct = status.totalBytes?.let { total ->
                if (total > 0) (status.receivedBytes * 100 / total).toInt().coerceIn(0, 100) else null
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(p.selection),
            ) {
                if (pct != null) {
                    Box(
                        Modifier
                            .fillMaxWidth(pct / 100f)
                            .height(3.dp)
                            .background(p.accent),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
    Hairline()
}

@Composable
private fun MiniAction(label: String, tint: Color, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = Air.TOUCH_DP.dp)
            .padding(horizontal = 8.dp)
            .clickable(onClick = onClick),
    ) {
        Text(text = label, color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ReplacementDialog(
    initial: WordReplacement,
    onSave: (WordReplacement) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    var from by remember(initial) { mutableStateOf(initial.from) }
    var to by remember(initial) { mutableStateOf(initial.to) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voz_add_replacement), fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                OutlinedTextField(
                    value = from,
                    onValueChange = { from = it },
                    label = { Text(stringResource(R.string.voz_replace_from_hint)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = to,
                    onValueChange = { to = it },
                    label = { Text(stringResource(R.string.voz_replace_to_hint)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(WordReplacement(from = from.trim(), to = to)) },
                enabled = from.isNotBlank(),
            ) {
                Text(stringResource(R.string.set_save), color = p.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.set_cancel), color = p.muted)
            }
        },
        containerColor = p.surfaceRaised,
        titleContentColor = MaterialTheme.colorScheme.onBackground,
        textContentColor = p.muted,
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = LocalPalette.current.muted,
        fontSize = Air.SECTION_LABEL_SP.sp,
        letterSpacing = Air.SECTION_TRACKING_SP.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Air.RADIUS_CARD_DP.dp))
            .background(p.surface)
            .border(Air.HAIRLINE_DP.dp, p.line, RoundedCornerShape(Air.RADIUS_CARD_DP.dp))
            .padding(horizontal = Air.CARD_PADDING_DP.dp, vertical = Air.CARD_PADDING_DP.dp),
    ) {
        content()
    }
}

@Composable
private fun Hairline() {
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(LocalPalette.current.line),
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        color = LocalPalette.current.textDisabled,
        fontSize = Air.META_SP.sp,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun VozSwitch(checked: Boolean, onToggle: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Box(
        modifier = Modifier.size(Air.TOUCH_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(26.dp)
                .clickable { onToggle(!checked) }
                .border(
                    Air.HAIRLINE_DP.dp,
                    if (checked) p.accent else p.line,
                    RoundedCornerShape(999.dp),
                )
                .background(if (checked) p.accent else p.surface, RoundedCornerShape(999.dp)),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .padding(start = if (checked) 21.dp else 3.dp)
                    .size(18.dp)
                    .background(if (checked) p.bg else p.muted, CircleShape),
            )
        }
    }
}

private fun hasMicPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
