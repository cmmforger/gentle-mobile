package com.gentlemanprogramming.gentlemobile.ui.session

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.gentlemanprogramming.gentlemobile.R
import com.gentlemanprogramming.gentlemobile.ui.theme.Air
import com.gentlemanprogramming.gentlemobile.ui.theme.LocalPalette
import kotlinx.coroutines.delay

/**
 * The session composer, per `design/gentle-mobile/04-session.html`: one
 * 44 px row — the field (8 px corners, no placeholder: the header already
 * names the agent) with a camera icon inside on the left and a clip icon
 * inside on the right, and outside on the far right a 44 px accent circle
 * that is a microphone when the field is empty and a send triangle when
 * there is content. No "Send" label anywhere.
 *
 * T13: the camera and clip icons are live — the camera opens the system
 * camera app (TakePicture against a provider-owned file), the clip opens the
 * Android photo picker — and picked images become attachment rows per
 * `05-compose.html`: hairline-separated rows above the composer, thumbnail
 * + name + meta line, with uploading (progress bar), uploaded (`✓ uploaded ·
 * size · path`) and failed (`Retry` / remove) states.
 *
 * T14 (S8): the microphone is live — speech becomes text in the field, per
 * `05-compose.html`: while listening, a quiet line above the composer shows
 * the partial transcript in italics, the elapsed time, and Stop (plus
 * Cancel); partials fill the field as they arrive and the final result
 * replaces them. Every honest failure — permission denied (with a settings
 * pointer when permanent), no recognizer, recognition error — keeps the
 * microphone disabled with that exact reason on the line below, never a
 * silent no-op. The circle returns to the send triangle as soon as the
 * field has text.
 *
 * S24-9 amendment: the line above the composer is a message area, empty by
 * default. It shows text only when there is something the mic icon cannot
 * say — a refusal, a permanent refusal (with the settings action), an
 * unavailable recognizer, an error, the listening state itself — and it
 * disappears when the state returns to idle with nothing to report. No
 * idle copy, no standing explanation paragraph.
 *
 * Honesty rules:
 * * a send exists only while every attachment has landed: a failed
 *   or in-flight upload blocks the send, so a prompt can never reference a
 *   file that is not on the host;
 * * the dictation circle never pretends: unavailable states render disabled
 *   with the reason, and listening renders the real transcript arriving.
 * The field starts at 44 px and grows to at most 96 px; the row lifts with
 * the IME (the caller applies imePadding) and never scrolls off screen.
 */
@Composable
fun SessionComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    attachments: List<Attachment>,
    thumbnails: Map<Long, androidx.compose.ui.graphics.ImageBitmap>,
    sending: Boolean,
    sendError: String?,
    dictation: DictationState,
    onSend: () -> Unit,
    onCamera: () -> Unit,
    onPickImage: () -> Unit,
    onStartDictation: () -> Unit,
    onStopDictation: () -> Unit,
    onCancelDictation: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissMessage: () -> Unit,
    onRetryUpload: (Long) -> Unit,
    onRemoveAttachment: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    Column(modifier = modifier.fillMaxWidth()) {
        if (sendError != null) {
            Text(
                text = sendError,
                color = palette.error,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(4.dp))
        }
        // S30-3: the "Sent — the host confirmed the submission." note stays
        // gone — the cleared field and the terminal's own reply are the
        // confirmation the design asks for.
        attachments.forEachIndexed { index, attachment ->
            if (index > 0) {
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(palette.line),
                )
            }
            AttachmentRow(
                attachment = attachment,
                thumbnail = thumbnails[attachment.id],
                onRetry = { onRetryUpload(attachment.id) },
                onRemove = { onRemoveAttachment(attachment.id) },
            )
        }
        if (attachments.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
        }
        // T14: the dictation message area (S24-9 amendment) — empty by
        // default, text only when there is something the icon cannot say:
        // the listening line, a refusal, a permanent refusal with the
        // settings action, an unavailable recognizer, an error.
        when (val dictationState = dictation) {
            is DictationState.Listening -> DictationListeningLine(
                partial = dictationState.partial,
                startedAtMillis = dictationState.startedAtMillis,
                onStop = onStopDictation,
                onCancel = onCancelDictation,
            )
            is DictationState.PermanentlyDenied -> DictationReasonLine(
                reason = dictationState.reason,
                actionLabel = stringResource(R.string.dict_permission_open_settings),
                onAction = onOpenSettings,
                // Sticky by rule: the user must act on it.
            )
            else -> {
                val reason = dictation.disabledReason()
                if (reason != null) {
                    // A refusal, an unavailable recognizer or an error is a
                    // result message, not standing copy: it shows for about
                    // four seconds and clears itself (S24-9 amendment).
                    DictationReasonLine(reason)
                    LaunchedEffect(dictation) {
                        delay(MESSAGE_TRANSIENT_MS)
                        onDismissMessage()
                    }
                }
            }
        }
        // align-items:end on the board: the circle pins to the bottom of the
        // growing field.
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
            ComposerField(
                draft = draft,
                onDraftChange = onDraftChange,
                onCamera = onCamera,
                onPickImage = onPickImage,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            ComposerCircle(
                canSend = canSendNow(draft, attachments),
                sending = sending,
                hasContent = draft.isNotBlank() ||
                    attachments.any { it.state is AttachmentState.Uploaded },
                dictation = dictation,
                onSend = onSend,
                onStartDictation = onStartDictation,
                onStopDictation = onStopDictation,
                onOpenSettings = onOpenSettings,
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** How long a transient result message stays before it clears itself. */
private const val MESSAGE_TRANSIENT_MS = 4_000L

/**
 * The listening line, per 05-compose.html's `.listen`: a 2 px accent bar on
 * the left, the partial transcript in italics (never a voice-clip UI — S8:
 * audio becomes text), the elapsed time, and Stop — plus Cancel, so stopping
 * and discarding are both explicit buttons, never gesture-only (S6).
 */
@Composable
private fun DictationListeningLine(
    partial: String?,
    startedAtMillis: Long,
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    val palette = LocalPalette.current
    // The elapsed clock ticks while the recognizer listens.
    var now by remember(startedAtMillis) { mutableLongStateOf(startedAtMillis) }
    LaunchedEffect(startedAtMillis) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            Modifier
                .width(2.dp)
                .height(44.dp)
                .background(palette.accentEdge),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = if (partial != null) {
                stringResource(R.string.comp_listening_partial, partial)
            } else {
                stringResource(R.string.comp_listening)
            },
            color = palette.muted,
            fontSize = 13.sp,
            fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatDictationElapsed(startedAtMillis, now),
            color = palette.muted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        DictationAction(stringResource(R.string.comp_stop), palette.accentEdge, enabled = true, onClick = onStop)
        DictationAction(stringResource(R.string.comp_cancel), palette.muted, enabled = true, onClick = onCancel)
    }
}

/** One 44 px text action on the listening line. */
@Composable
private fun DictationAction(label: String, tint: Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        Text(
            text = label,
            color = if (enabled) tint else tint.copy(alpha = 0.5f),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * The exact honest reason (refusal, availability, error) on one quiet line —
 * part of the S24-9 message area, so it exists only while there is a reason.
 * [actionLabel] renders a tappable action beside it (the settings pointer).
 */
@Composable
private fun DictationReasonLine(
    reason: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = reason,
            color = palette.muted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = actionLabel,
                color = palette.accentEdge,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable(onClick = onAction)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** The blocking rule, shared with the queue: nothing may send while any
 * attachment has not landed on the host. */
private fun canSendNow(draft: String, attachments: List<Attachment>): Boolean {
    val pending = attachments.any {
        it.state is AttachmentState.Ready ||
            it.state is AttachmentState.Uploading ||
            it.state is AttachmentState.Failed
    }
    if (pending) return false
    return draft.isNotBlank() || attachments.any { it.state is AttachmentState.Uploaded }
}

/** One attachment row per 05-compose.html: thumbnail · name + meta · actions. */
@Composable
private fun AttachmentRow(
    attachment: Attachment,
    thumbnail: androidx.compose.ui.graphics.ImageBitmap?,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    val palette = LocalPalette.current
    val removeCd = stringResource(R.string.att_remove_cd, attachment.displayName)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        // The 56 px thumbnail; before the image decodes, a flat terminal
        // plate stands in — never a broken icon or a fake preview.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(Air.RADIUS_FIELD_DP.dp))
                .background(palette.terminal),
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = stringResource(R.string.att_preview_of, attachment.displayName),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(56.dp),
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_camera),
                    contentDescription = null,
                    tint = palette.muted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = attachment.displayName,
                color = palette.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            when (val state = attachment.state) {
                is AttachmentState.Uploading -> {
                    val pct = if (attachment.sizeBytes > 0) {
                        ((state.sentBytes * 100) / attachment.sizeBytes).toInt().coerceIn(0, 100)
                    } else 0
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(palette.selection),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(pct / 100f)
                                .height(3.dp)
                                .background(palette.accent),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.att_uploading,
                            pct,
                            formatBytes(state.sentBytes),
                            formatBytes(attachment.sizeBytes),
                        ),
                        color = palette.muted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                is AttachmentState.Uploaded -> Text(
                    text = stringResource(
                        R.string.att_uploaded,
                        formatBytes(attachment.sizeBytes),
                        state.remotePath,
                    ),
                    color = palette.success,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                is AttachmentState.Failed -> Text(
                    text = stringResource(R.string.att_failed, state.message),
                    color = palette.error,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
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
                .clickable(onClick = onRemove)
                .semantics {
                    contentDescription = removeCd
                },
        ) {
            Text(text = "✕", color = palette.muted, fontSize = 15.sp)
        }
    }
}

/** The field: filled surface, 8 px corners, no placeholder, camera left and clip right — both live. */
@Composable
private fun ComposerField(
    draft: String,
    onDraftChange: (String) -> Unit,
    onCamera: () -> Unit,
    onPickImage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = 44.dp, max = 96.dp)
            .clip(RoundedCornerShape(Air.RADIUS_TERMINAL_DP.dp))
            .background(LocalPalette.current.surface)
            .border(1.dp, LocalPalette.current.line, RoundedCornerShape(Air.RADIUS_TERMINAL_DP.dp))
            .padding(horizontal = 2.dp),
    ) {
        ComposerIcon(
            drawable = R.drawable.ic_camera,
            description = stringResource(R.string.cd_camera),
            enabled = true,
            onClick = onCamera,
        )
        BasicTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 12.dp),
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            ),
            cursorBrush = SolidColor(LocalPalette.current.accent),
        )
        ComposerIcon(
            drawable = R.drawable.ic_clip,
            description = stringResource(R.string.cd_clip),
            enabled = true,
            onClick = onPickImage,
        )
    }
}

/**
 * A 44 px touch target inside the field. T13: the camera and clip are real
 * controls, so they tap; only dictation (T14) stays disabled.
 */
@Composable
private fun ComposerIcon(drawable: Int, description: String, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val tint = if (enabled) palette.text else palette.muted
    val boxModifier = if (enabled) {
        Modifier
            .size(44.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description }
    } else {
        Modifier
            .size(44.dp)
            .semantics { contentDescription = description }
    }
    Box(contentAlignment = Alignment.Center, modifier = boxModifier) {
        Icon(
            painter = painterResource(drawable),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * The far-right 44 px accent circle, per 04-session.html: the microphone
 * while the field is empty, the send triangle once there is text.
 *
 * T14: the microphone is live — a tap starts dictation (idle, finished,
 * or after a retryable failure), a tap while listening stops it, and an
 * unavailable state renders it disabled with the exact reason spoken to
 * accessibility, never a silent no-op.
 */
@Composable
private fun ComposerCircle(
    canSend: Boolean,
    sending: Boolean,
    hasContent: Boolean,
    dictation: DictationState,
    onSend: () -> Unit,
    onStartDictation: () -> Unit,
    onStopDictation: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val palette = LocalPalette.current
    val sendEnabled = canSend && !sending
    val sendCd = stringResource(R.string.cd_send)
    val sendWaitingCd = stringResource(R.string.cd_send_waiting)
    // A tap on the circle during dictation stops it; the listening line's
    // Stop and Cancel buttons are the explicit path too. After a permanent
    // refusal the tap opens the settings — the dialog cannot come again.
    val listening = dictation is DictationState.Listening
    val micStartable = dictation is DictationState.Idle ||
        dictation is DictationState.Finished ||
        dictation is DictationState.Denied ||
        dictation is DictationState.PermanentlyDenied ||
        dictation is DictationState.Error
    val micEnabled = listening || micStartable
    val micDescription = when (dictation) {
        is DictationState.Listening -> stringResource(R.string.cd_mic_stop)
        is DictationState.RequestingPermission -> stringResource(R.string.cd_mic_permission)
        is DictationState.PermanentlyDenied ->
            stringResource(R.string.cd_mic_reason, dictation.reason)
        is DictationState.Denied ->
            stringResource(R.string.cd_mic_reason, dictation.explanation)
        is DictationState.Unavailable ->
            stringResource(R.string.cd_mic_reason, dictation.reason)
        is DictationState.Error ->
            stringResource(R.string.cd_mic_reason, dictation.message)
        else -> stringResource(R.string.cd_mic)
    }
    val circleModifier = when {
        sendEnabled -> Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(palette.accent)
            .clickable(onClick = onSend)
            .semantics { contentDescription = sendCd }
        hasContent -> Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(palette.accentPressed)
            .semantics { contentDescription = sendWaitingCd }
        micEnabled -> Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(palette.accent)
            .clickable(
                onClick = when {
                    listening -> onStopDictation
                    dictation is DictationState.PermanentlyDenied -> onOpenSettings
                    else -> onStartDictation
                },
            )
            .semantics { contentDescription = micDescription }
        else -> Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(palette.accentPressed)
            .semantics { contentDescription = micDescription }
    }
    Box(contentAlignment = Alignment.Center, modifier = circleModifier) {
        Icon(
            painter = painterResource(
                if (sendEnabled || hasContent) R.drawable.ic_send else R.drawable.ic_mic,
            ),
            contentDescription = null,
            tint = palette.bg.copy(alpha = if (sendEnabled || micEnabled) 1f else 0.6f),
            modifier = Modifier.size(20.dp),
        )
    }
}
