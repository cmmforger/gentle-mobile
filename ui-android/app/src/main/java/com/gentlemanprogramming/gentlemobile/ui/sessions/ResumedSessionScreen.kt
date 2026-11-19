package com.gentlemanprogramming.gentlemobile.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gentlemanprogramming.gentlemobile.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.gentlemanprogramming.gentlemobile.core.DialogRequestDto
import com.gentlemanprogramming.gentlemobile.core.TranscriptEntryDto
import com.gentlemanprogramming.gentlemobile.ui.theme.LocalPalette

/**
 * The resumed session (T17b, S15/S17): the transcript read-only — messages
 * with role and text, no invented styling, tool traffic and thinking never
 * render because the core never produced them — plus a composer that sends
 * prompts through the RPC lane, and `Close session` which ends the pump and
 * releases the lease (S18).
 *
 * Why the composer here is its own simplified row instead of the Lane A
 * `SessionComposer`: that component's contract is attachments (T13) and
 * dictation (T14) — an upload gate that must block sending until images
 * land on the host, and a recognizer state. The RPC lane has neither: there
 * are no attachments, no camera, no microphone. Reuse would have meant
 * passing empty attachment lists and a fake dictation state to satisfy its
 * signature — dishonest states the screen does not have. The visual shape
 * (one field, 8 px corners, accent circle that is the send triangle once
 * there is text, 44 px targets, no Send label) is the same board (04), the
 * capability set is honestly narrower.
 *
 * Honesty rules:
 * * a dialog (S17d) renders as its own card while it waits, with explicit
 *   answer actions — never auto-answered silently by the UI;
 * * a pump that ends (close, EOF, transport death) renders its reason; the
 *   lease is released by the core on every one of those paths;
 * * `resumed · <sessionId>` and the child's own message count are the
 *   words the handshake reported — never a local guess.
 */
@Composable
fun ResumedSessionScreen(
    sessionId: String,
    messageCount: ULong,
    transcript: List<TranscriptEntryDto>,
    reclaimed: Boolean,
    pendingDialog: DialogRequestDto?,
    draft: String,
    onDraftChange: (String) -> Unit,
    sending: Boolean,
    sendError: String?,
    closed: Boolean,
    closeError: String?,
    onSend: () -> Unit,
    onAnswerDialog: (dialogId: String, cancelled: Boolean, value: String?) -> Unit,
    onClose: () -> Unit,
    onBack: () -> Unit,
) {
    val p = LocalPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp),
    ) {
        Row(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.res_back),
                color = p.accent,
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(vertical = 12.dp),
            )
            Text(
                text = stringResource(R.string.res_lane),
                color = p.muted,
                fontSize = 11.sp,
                letterSpacing = 0.8.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.res_header, sessionId),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 18.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = listOfNotNull(
                pluralStringResource(
                    R.plurals.messages_count,
                    messageCount.toInt(),
                    messageCount.toInt(),
                ),
                if (reclaimed) stringResource(R.string.res_reclaimed) else null,
            ).joinToString(" · "),
            color = p.muted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(12.dp))

        if (transcript.isEmpty()) {
            Text(
                text = stringResource(R.string.res_empty),
                color = p.muted,
                fontSize = 13.sp,
            )
        } else {
            transcript.forEachIndexed { index, entry ->
                if (index > 0) {
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(p.line),
                    )
                }
                TranscriptRow(entry)
            }
        }

        pendingDialog?.let { dialog ->
            Spacer(Modifier.height(12.dp))
            DialogCard(
                dialog = dialog,
                onAnswer = { cancelled, value -> onAnswerDialog(dialog.dialogId, cancelled, value) },
            )
        }

        sendError?.let { text ->
            Spacer(Modifier.height(12.dp))
            Text(text = text, color = p.error, fontSize = 13.sp)
        }
        if (closed) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = closeError
                    ?: stringResource(R.string.res_closed),
                color = if (closeError != null) p.error else p.muted,
                fontSize = 13.sp,
            )
        }

        Spacer(Modifier.height(16.dp))
        // The composer: visible while the session is live, replaced by the
        // Close path once it is not.
        if (!closed) {
            RpcComposer(
                draft = draft,
                onDraftChange = onDraftChange,
                sending = sending,
                onSend = onSend,
            )
        }
        Spacer(Modifier.height(12.dp))
        val closeCd = stringResource(R.string.cd_close_session)
        Text(
            text = stringResource(R.string.res_close),
            color = p.accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clickable(onClick = onClose)
                .padding(vertical = 12.dp)
                .semantics { contentDescription = closeCd },
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** The transcript role word, localized (the pure [SessionsModel.roleLabel]
 *  rule: unknown roles render as themselves — nothing invented). */
@Composable
private fun roleLabel(role: String): String = when (role) {
    "user" -> stringResource(R.string.res_role_you)
    "assistant" -> stringResource(R.string.res_role_agent)
    else -> role
}

/** One transcript row: the role word and the text. No styling invented —
 *  the same type the rest of the app uses, mono meta only for the role. */
@Composable
private fun TranscriptRow(entry: TranscriptEntryDto) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = roleLabel(entry.role),
            color = p.muted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = entry.text,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 13.sp,
        )
    }
}

/** One blocking dialog (S17d): title, message or options as given, and
 *  explicit answer actions. Cancel answers with the documented default. */
@Composable
private fun DialogCard(
    dialog: DialogRequestDto,
    onAnswer: (cancelled: Boolean, value: String?) -> Unit,
) {
    val p = LocalPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(p.surface)
            .border(1.dp, p.line, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(
            text = dialog.title ?: stringResource(R.string.res_dialog_fallback),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        dialog.message?.let { message ->
            Spacer(Modifier.height(4.dp))
            Text(text = message, color = p.muted, fontSize = 13.sp)
        }
        if (dialog.options.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            dialog.options.forEach { option ->
                Text(
                    text = option,
                    color = p.accent,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickable { onAnswer(false, option) }
                        .padding(vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row {
            Text(
                text = stringResource(R.string.comp_cancel),
                color = p.muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable { onAnswer(true, null) }
                    .padding(vertical = 12.dp, horizontal = 8.dp),
            )
        }
    }
}

/** The RPC composer: the board's shape, honestly narrower. The accent
 *  circle is the send triangle exactly while the field has text and the
 *  lane is not already sending. */
@Composable
private fun RpcComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    sending: Boolean,
    onSend: () -> Unit,
) {
    val p = LocalPalette.current
    val canSend = draft.isNotBlank() && !sending
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 44.dp, max = 96.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(p.surface)
                .border(1.dp, p.line, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp),
        ) {
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
                cursorBrush = SolidColor(p.accent),
            )
        }
        Spacer(Modifier.width(8.dp))
        // The 44 px circle: accent while it can send, quiet while it cannot.
        val circleDescription = when {
            sending -> stringResource(R.string.res_send_waiting)
            canSend -> stringResource(R.string.res_send_cd)
            else -> stringResource(R.string.res_send_type_first)
        }
        androidx.compose.foundation.layout.Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (canSend) p.accent else p.accentPressed)
                .then(
                    if (canSend) {
                        Modifier.clickable(onClick = onSend)
                    } else {
                        Modifier
                    }
                )
                .semantics { contentDescription = circleDescription },
        ) {
            Text(text = "▲", color = p.bg, fontSize = 15.sp)
        }
    }
}
