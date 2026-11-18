package com.gentlemanprogramming.gentlemobile.ui.session

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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
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
 * there is text. No "Send" label anywhere.
 *
 * Honesty rule: dictation (T14) and attachments (T13) are not implemented,
 * so the mic, camera and clip render disabled with their reason as a
 * one-line note under the row — never a control that pretends to work.
 * The field starts at 44 px and grows to at most 96 px; the row lifts with
 * the IME (the caller applies imePadding) and never scrolls off screen.
 */
@Composable
fun SessionComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    sending: Boolean,
    sendError: String?,
    sent: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (sendError != null) {
            Text(
                text = sendError,
                color = LocalPalette.current.error,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(4.dp))
        }
        if (sent) {
            Text(
                text = "Sent — the host confirmed the submission.",
                color = LocalPalette.current.accent,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(4.dp))
        }
        // align-items:end on the board: the circle pins to the bottom of the
        // growing field.
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
            ComposerField(
                draft = draft,
                onDraftChange = onDraftChange,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            ComposerCircle(
                hasText = draft.isNotBlank(),
                sending = sending,
                onSend = onSend,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (draft.isBlank()) {
                "Dictation is not implemented yet (T14) — the microphone stays disabled."
            } else {
                "Attachments are not implemented yet (T13) — the camera and clip icons stay disabled."
            },
            color = LocalPalette.current.muted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** The field: filled surface, 8 px corners, no placeholder, camera left and clip right. */
@Composable
private fun ComposerField(
    draft: String,
    onDraftChange: (String) -> Unit,
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
            description = "Take a photo — attachments are not implemented yet (T13)",
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
            description = "Attach image or file — attachments are not implemented yet (T13)",
        )
    }
}

/** A 44 px touch target inside the field, no background; disabled until T13. */
@Composable
private fun ComposerIcon(drawable: Int, description: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .semantics {
                disabled()
                contentDescription = description
            },
    ) {
        Icon(
            painter = painterResource(drawable),
            contentDescription = null,
            tint = LocalPalette.current.muted,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * The far-right 44 px accent circle: a disabled microphone while the field
 * is empty (dictation is T14 — rendered honestly, never a tap target that
 * pretends), the enabled send triangle once there is text.
 */
@Composable
private fun ComposerCircle(
    hasText: Boolean,
    sending: Boolean,
    onSend: () -> Unit,
) {
    val palette = LocalPalette.current
    val sendEnabled = hasText && !sending
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
                disabled()
                contentDescription = "Dictate — speech becomes text (not implemented yet, T14)"
            }
    }
    Box(contentAlignment = Alignment.Center, modifier = circleModifier) {
        Icon(
            painter = painterResource(if (sendEnabled) R.drawable.ic_send else R.drawable.ic_mic),
            contentDescription = null,
            tint = palette.bg.copy(alpha = if (sendEnabled) 1f else 0.6f),
            modifier = Modifier.size(20.dp),
        )
    }
}
