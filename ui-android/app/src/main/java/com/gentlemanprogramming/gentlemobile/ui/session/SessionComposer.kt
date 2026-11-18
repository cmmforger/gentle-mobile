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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gentlemanprogramming.gentlemobile.R
import com.gentlemanprogramming.gentlemobile.ui.theme.LocalPalette

/**
 * The session composer, per `design/gentle-companion/04-session.html`: one
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
 * Honesty rules:
 * * dictation (T14) is still not implemented, so the mic renders disabled
 *   with its reason as a one-line note — never a control that pretends;
 * * the send action exists only while every attachment has landed: a failed
 *   or in-flight upload blocks the send, so a prompt can never reference a
 *   file that is not on the host.
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
    sent: Boolean,
    onSend: () -> Unit,
    onCamera: () -> Unit,
    onPickImage: () -> Unit,
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
        if (sent) {
            Text(
                text = "Sent — the host confirmed the submission.",
                color = palette.accent,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(4.dp))
        }
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
                onSend = onSend,
            )
        }
        Spacer(Modifier.height(4.dp))
        if (draft.isBlank() && attachments.none { it.state is AttachmentState.Uploaded }) {
            Text(
                text = "Dictation is not implemented yet (T14) — the microphone stays disabled.",
                color = palette.muted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
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
                .clip(RoundedCornerShape(8.dp))
                .background(palette.terminal),
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = "Preview of ${attachment.displayName}",
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
                        text = "uploading · $pct % · ${formatBytes(state.sentBytes)} / " +
                            formatBytes(attachment.sizeBytes),
                        color = palette.muted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                is AttachmentState.Uploaded -> Text(
                    text = "✓ uploaded · ${formatBytes(attachment.sizeBytes)} · " +
                        state.remotePath,
                    color = palette.success,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                is AttachmentState.Failed -> Text(
                    text = "Upload failed — ${state.message}",
                    color = palette.error,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                AttachmentState.Ready -> Text(
                    text = "waiting to upload · ${formatBytes(attachment.sizeBytes)}",
                    color = palette.muted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        if (attachment.state is AttachmentState.Failed) {
            Text(
                text = "Retry",
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
                    contentDescription = "Remove image ${attachment.displayName}"
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
            .clip(RoundedCornerShape(8.dp))
            .background(LocalPalette.current.surface)
            .border(1.dp, LocalPalette.current.line, RoundedCornerShape(8.dp))
            .padding(horizontal = 2.dp),
    ) {
        ComposerIcon(
            drawable = R.drawable.ic_camera,
            description = "Take a photo with the camera",
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
            description = "Attach an image from the gallery",
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
 * The far-right 44 px accent circle: a disabled microphone while the field
 * is empty and nothing is uploaded (dictation is T14 — rendered honestly,
 * never a tap target that pretends), the enabled send triangle once there
 * is text or an uploaded image.
 */
@Composable
private fun ComposerCircle(
    canSend: Boolean,
    sending: Boolean,
    hasContent: Boolean,
    onSend: () -> Unit,
) {
    val palette = LocalPalette.current
    val sendEnabled = canSend && !sending
    val circleModifier = if (sendEnabled) {
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(palette.accent)
            .clickable(onClick = onSend)
            .semantics { contentDescription = "Send" }
    } else {
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(palette.accentPressed)
            .semantics {
                contentDescription = if (hasContent) {
                    "Send — waiting for the attachments to finish"
                } else {
                    "Dictate — speech becomes text (not implemented yet, T14)"
                }
            }
    }
    Box(contentAlignment = Alignment.Center, modifier = circleModifier) {
        Icon(
            painter = painterResource(
                if (sendEnabled || hasContent) R.drawable.ic_send else R.drawable.ic_mic,
            ),
            contentDescription = null,
            tint = palette.bg.copy(alpha = if (sendEnabled) 1f else 0.6f),
            modifier = Modifier.size(20.dp),
        )
    }
}
