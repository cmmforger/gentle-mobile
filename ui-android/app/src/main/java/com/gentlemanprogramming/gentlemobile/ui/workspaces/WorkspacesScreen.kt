package com.gentlemanprogramming.gentlemobile.ui.workspaces

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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gentlemanprogramming.gentlemobile.R
import com.gentlemanprogramming.gentlemobile.core.DirCheckDto
import com.gentlemanprogramming.gentlemobile.core.DirVerdictDto
import com.gentlemanprogramming.gentlemobile.core.WorkspaceRowDto
import com.gentlemanprogramming.gentlemobile.ui.sessions.SessionsModel
import com.gentlemanprogramming.gentlemobile.ui.theme.LocalPalette

/**
 * The workspaces screen (S26-2): the host's herdr workspaces — the
 * repositories the human navigates on the desk — as hairline rows on one
 * surface. Each row: the workspace's own label, the branch when the repo
 * behind it has one (ahead/behind does not exist on the wire, so it is
 * never invented), the aggregate state as a word, and the agent count.
 * Tapping a row focuses the workspace on the host and opens its agents.
 *
 * The `+` (S26-3): one accent action asking for an absolute path on the
 * host, validated over SFTP before `workspace.create` — measured live, the
 * daemon silently falls back to `$HOME` for a missing directory, so the
 * check is the guard. Every rejection is its own plain sentence, and a
 * non-git directory says so and may still be added. No directory browser:
 * a typed path, plus the recent paths the host's sessions already know.
 *
 * Honesty rules:
 * * `rows == null` (never read) and an empty list (the daemon's own
 *   answer) render differently — an empty host is not a failed read;
 * * a branch the host could not name is simply not shown;
 * * an add failure keeps the typed reason on screen and never pretends a
 *   workspace exists.
 */
@Composable
fun WorkspacesScreen(
    rows: List<WorkspaceRowDto>?,
    loading: Boolean,
    error: String?,
    addChecking: Boolean,
    addVerdict: DirCheckDto?,
    creating: Boolean,
    createError: String?,
    recentPaths: List<SessionsModel.RepoEntry>,
    recentLoading: Boolean,
    onOpen: (WorkspaceRowDto) -> Unit,
    onAddPath: (path: String) -> Unit,
    onRecentOpened: () -> Unit,
    onBack: () -> Unit,
) {
    val p = LocalPalette.current
    var addOpen by remember { mutableStateOf(false) }
    var path by remember { mutableStateOf("") }
    val pathValid = SessionsModel.isValidStartPath(path)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // S28-5: the title must not collide with the status bar clock —
            // the same safe-drawing header every other screen has.
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // S28-5: the same back-button header the other screens have —
            // an accent chevron in a 44 dp touch target, no word to read.
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_back_chevron),
                    contentDescription = stringResource(R.string.cd_back_to_hosts),
                    tint = p.accent,
                    modifier = Modifier.size(18.dp),
                )
            }
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
            text = stringResource(R.string.ws_title),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 22.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))

        when {
            loading && rows == null -> Text(
                text = stringResource(R.string.ws_reading),
                color = p.muted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            error != null -> Text(
                text = stringResource(R.string.ws_error, error),
                color = p.error,
                fontSize = 13.sp,
            )
            rows != null && rows.isEmpty() -> Text(
                text = stringResource(R.string.ws_empty),
                color = p.muted,
                fontSize = 13.sp,
            )
            rows != null -> rows.forEach { row ->
                WorkspaceRow(row = row, onOpen = { onOpen(row) })
            }
        }

        Spacer(Modifier.height(24.dp))
        // The one accent action (S26-3): add a project at a typed absolute
        // path on the host.
        Button(
            onClick = {
                addOpen = !addOpen
                if (addOpen) onRecentOpened()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = p.accent,
                contentColor = p.bg,
            ),
        ) {
            Text(text = stringResource(R.string.ws_add), fontWeight = FontWeight.SemiBold)
        }
        if (addOpen) {
            Spacer(Modifier.height(12.dp))
            PathField(path = path, onPathChange = { path = it })
            if (path.isNotEmpty() && !pathValid) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.start_dir_not_absolute),
                    color = p.error,
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onAddPath(path.trim()) },
                enabled = pathValid && !addChecking && !creating,
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
                Text(text = stringResource(R.string.ws_add_action), fontWeight = FontWeight.SemiBold)
            }
            if (addChecking) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.ws_add_working),
                    color = p.muted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            // The measured verdict (S26-3): its own plain sentence when the
            // path was refused, the git note when it was accepted.
            addVerdict?.let { verdict ->
                Spacer(Modifier.height(8.dp))
                WorkspacesModel.rejectionRes(verdict.verdict)?.let { res ->
                    Text(
                        text = stringResource(res, path.trim()),
                        color = p.error,
                        fontSize = 13.sp,
                    )
                }
                if (verdict.verdict == DirVerdictDto.OK) {
                    Text(
                        text = stringResource(WorkspacesModel.gitNoteRes(verdict.isGitRepo)),
                        color = p.muted,
                        fontSize = 12.sp,
                    )
                }
            }
            if (creating) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.ws_add_creating),
                    color = p.muted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            createError?.let { failure ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.ws_add_failed, failure),
                    color = p.error,
                    fontSize = 13.sp,
                )
            }
            // Recent paths (S26-3): where the host's sessions already
            // worked — a tap fills the field, it never starts anything.
            if (recentPaths.isNotEmpty() || recentLoading) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.ws_recent).uppercase(),
                    color = p.muted,
                    fontSize = 11.sp,
                    letterSpacing = 0.8.sp,
                )
                if (recentLoading) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.ws_recent_reading),
                        color = p.muted,
                        fontSize = 12.sp,
                    )
                }
                recentPaths.forEach { repo ->
                    Text(
                        text = repo.path,
                        color = if (pathValid && path.trim() == repo.path) p.accent else p.accentEdge,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 36.dp)
                            .clickable { path = repo.path }
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** One workspace row (S26-2): the human's own label, branch · state · agent
 *  count as the mono meta. `on screen` marks the workspace the host has
 *  focused — the same word the herdr TUI would show. */
@Composable
private fun WorkspaceRow(row: WorkspaceRowDto, onOpen: () -> Unit) {
    val p = LocalPalette.current
    val agentsWord = if (row.agentCount == 1UL) {
        stringResource(R.string.ws_agents_one)
    } else {
        stringResource(R.string.ws_agents_many, row.agentCount.toLong())
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.label,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.focused) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.ws_focused),
                        color = p.accent,
                        fontSize = 11.sp,
                        letterSpacing = 0.5.sp,
                    )
                }
            }
            Text(
                text = listOfNotNull(
                    row.branch,
                    // S27-8: an idle workspace says nothing — no word `Idle`.
                    WorkspacesModel.statusWordRes(row.status)?.let { stringResource(it) },
                    agentsWord,
                ).joinToString(" \u00B7 "),
                color = p.muted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(text = "›", color = p.muted, fontSize = 15.sp)
    }
}

/** The typed absolute path: the same field shape the start-session screen
 *  uses; the validation is pure ([SessionsModel.isValidStartPath]) and the
 *  host-side check runs only on submit. */
@Composable
private fun PathField(path: String, onPathChange: (String) -> Unit) {
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
                        text = stringResource(R.string.ws_add_hint),
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
