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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.gentlemanprogramming.gentlemobile.R
import com.gentlemanprogramming.gentlemobile.core.SessionDto
import com.gentlemanprogramming.gentlemobile.ui.theme.LocalPalette

/**
 * The start-session screen (S24-5): the repositories that already have
 * session history on this host — nothing invented, nothing scanned; the
 * paths come from the sessions the host already recorded — each row an
 * accent action that spawns a fresh `gentle-shell --mode rpc` in that
 * directory over the pinned SSH connection, plus one quiet typed-path
 * affordance for the cases the history does not cover.
 *
 * Honesty rules:
 * * a spawn failure (no `gentle-shell` on the host's PATH, a missing
 *   directory, a refused exec) shows the host's own reason on this screen —
 *   never a generic "failed", and nothing half-open survives (the core
 *   closes the child, and no lease exists for a session that never became
 *   ours);
 * * a fresh session with no messages says so on the transcript screen — the
 *   start screen only starts it;
 * * the manual path is validated as absolute before any host work, and the
 *   affordance stays inside this screen.
 */
@Composable
fun StartSessionScreen(
    sessions: List<SessionDto>,
    starting: Boolean,
    startError: String?,
    onStartRepo: (path: String) -> Unit,
    onStartPath: (path: String) -> Unit,
    onBack: () -> Unit,
) {
    val p = LocalPalette.current
    var manualOpen by remember { mutableStateOf(false) }
    var manualPath by remember { mutableStateOf("") }
    var manualTouched by remember { mutableStateOf(false) }
    val repos = SessionsModel.repositories(sessions)
    val manualValid = SessionsModel.isValidStartPath(manualPath)

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
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.hist_one_device),
                color = p.muted,
                fontSize = 11.sp,
                letterSpacing = 0.8.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.start_title),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 22.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.Bold,
        )
        GroupLabel(stringResource(R.string.start_group))

        if (repos.isEmpty()) {
            Text(
                text = stringResource(R.string.start_empty),
                color = p.muted,
                fontSize = 13.sp,
            )
        } else {
            repos.forEach { repo ->
                RepoRow(
                    repo = repo,
                    enabled = !starting,
                    onStart = { onStartRepo(repo.path) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.start_other),
            color = p.accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clickable { manualOpen = !manualOpen }
                .padding(vertical = 8.dp),
        )
        if (manualOpen) {
            Spacer(Modifier.height(8.dp))
            ManualPathField(
                path = manualPath,
                enabled = !starting,
                onPathChange = {
                    manualPath = it
                    manualTouched = true
                },
            )
            if (manualTouched && !manualValid) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.start_dir_not_absolute),
                    color = p.error,
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onStartPath(manualPath.trim()) },
                enabled = manualValid && !starting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = p.accent,
                    contentColor = p.bg,
                    disabledContainerColor = p.surfaceRaised,
                    disabledContentColor = p.textDisabled,
                ),
            ) {
                Text(text = stringResource(R.string.start_start), fontWeight = FontWeight.SemiBold)
            }
        }

        if (starting) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.start_starting),
                color = p.muted,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        startError?.let { text ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.start_error, text),
                color = p.error,
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** One repository row: the name as the title, the path as the mono
 *  secondary line, the relative last-used time leading the meta, and the
 *  accent word that makes the whole row the start action. */
@Composable
private fun RepoRow(
    repo: SessionsModel.RepoEntry,
    enabled: Boolean,
    onStart: () -> Unit,
) {
    val p = LocalPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, onClick = onStart),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = repo.name,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    relativeTime(repo.lastUsedEpochMs, System.currentTimeMillis()),
                    repo.path,
                ).joinToString(" · "),
                color = p.muted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.start_row_action),
            color = p.accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** The typed manual path: the same field shape the composer uses, with the
 *  absolute-path hint. The validation is pure ([SessionsModel.isValidStartPath])
 *  and runs before any host work. */
@Composable
private fun ManualPathField(
    path: String,
    enabled: Boolean,
    onPathChange: (String) -> Unit,
) {
    val p = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(p.surface)
            .border(1.dp, p.line, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp),
    ) {
        BasicTextField(
            value = path,
            onValueChange = onPathChange,
            enabled = enabled,
            singleLine = true,
            visualTransformation = VisualTransformation.None,
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            ),
            decorationBox = { inner ->
                if (path.isEmpty()) {
                    Text(
                        text = stringResource(R.string.start_other_hint),
                        color = p.muted,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                inner()
            },
            cursorBrush = SolidColor(p.accent),
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 12.dp),
        )
    }
}

/** Relative last-used time, localized — the same buckets the sessions list
 *  uses; a host clock ahead of the phone's is "just now", never negative. */
@Composable
private fun relativeTime(epochMs: Long, nowMs: Long): String {
    val delta = (nowMs - epochMs).coerceAtLeast(0)
    val minutes = delta / 60_000
    val hours = delta / 3_600_000
    val days = delta / 86_400_000
    return when {
        minutes < 1 -> stringResource(R.string.time_just_now)
        hours < 1 -> stringResource(R.string.time_min_ago, minutes)
        days < 1 -> stringResource(R.string.time_h_ago, hours)
        else -> stringResource(R.string.time_d_ago, days)
    }
}

/** The group header: quiet, uppercase, the same rule as the other screens. */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = LocalPalette.current.muted,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}
