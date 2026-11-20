package com.gentlemanprogramming.gentlemobile.ui.workspaces

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gentlemanprogramming.gentlemobile.R
import com.gentlemanprogramming.gentlemobile.core.DirCheckDto
import com.gentlemanprogramming.gentlemobile.core.DirVerdictDto
import com.gentlemanprogramming.gentlemobile.core.DiskDto
import com.gentlemanprogramming.gentlemobile.core.FolderEntryDto
import com.gentlemanprogramming.gentlemobile.core.WorkspaceRowDto
import com.gentlemanprogramming.gentlemobile.ui.theme.LocalPalette

/**
 * The workspaces screen (S26-2): the host's herdr workspaces — the
 * repositories the human navigates on the desk — as hairline rows on one
 * surface. Each row: the workspace's own label, the branch when the repo
 * behind it has one (ahead/behind does not exist on the wire, so it is
 * never invented), the aggregate state as a word, and the agent count.
 * Tapping a row focuses the workspace on the host and opens its agents.
 *
 * The `+` (S26-3, T27): one accent action opening the browser — the
 * machine's disks first (root first, then by mount point, in the order the
 * core sent), then the chosen disk's folders, with back navigation up to
 * the disk list. A folder with a `.git` marker carries the marker, a
 * symlink is shown with its target and is never followed. Choosing a
 * folder creates the workspace through the same validated path the typed
 * flow used (`checkHostDir`, then `workspace.create` — measured live, the
 * daemon silently falls back to `$HOME` for a missing directory, so the
 * check is the guard). Every rejection is its own plain sentence, and a
 * non-git directory says so and may still be added.
 *
 * Honesty rules:
 * * `rows == null` (never read) and an empty list (the daemon's own
 *   answer) render differently — an empty host is not a failed read;
 * * a branch the host could not name is simply not shown;
 * * a listing failure is typed (missing / not a directory / no permission /
 *   host), never an empty list pretending success.
 */
@Composable
fun WorkspacesScreen(
    rows: List<WorkspaceRowDto>?,
    loading: Boolean,
    error: String?,
    browseOpen: Boolean,
    browseLevel: WorkspacesModel.BrowseState,
    disks: List<DiskDto>?,
    disksLoading: Boolean,
    disksError: String?,
    folders: List<FolderEntryDto>?,
    foldersLoading: Boolean,
    foldersError: String?,
    checking: Boolean,
    verdict: DirCheckDto?,
    creating: Boolean,
    createError: String?,
    onOpen: (WorkspaceRowDto) -> Unit,
    onBrowseOpened: () -> Unit,
    onSelectDisk: (DiskDto) -> Unit,
    onSelectFolder: (FolderEntryDto) -> Unit,
    onChooseFolder: () -> Unit,
    onBrowseBack: () -> Unit,
    onCloseBrowse: () -> Unit,
    onBack: () -> Unit,
) {
    val p = LocalPalette.current
    val inFolder = browseLevel as? WorkspacesModel.BrowseState.InFolder

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // S28-5: the title must not collide with the status bar clock —
            // the same safe-drawing header every other screen has.
            .safeDrawingPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp),
    ) {
        // The header never scrolls away (T27): the back chevron is the way
        // up — out of the browser, or off the screen — and S6 forbids a
        // gesture-only way back. Only the body below it scrolls.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // S28-5: the same back-button header the other screens have —
            // an accent chevron in a 44 dp touch target. While browsing it
            // goes up one level; from the disk list it closes the browser;
            // on the workspaces list it leaves the screen (S6: never a
            // gesture-only action).
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clickable(onClick = when {
                        !browseOpen -> onBack
                        inFolder != null -> onBrowseBack
                        else -> onCloseBrowse
                    }),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_back_chevron),
                    contentDescription = stringResource(R.string.cd_back),
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

        if (browseOpen) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                BrowseBody(
                level = browseLevel,
                disks = disks,
                disksLoading = disksLoading,
                disksError = disksError,
                folders = folders,
                foldersLoading = foldersLoading,
                foldersError = foldersError,
                checking = checking,
                verdict = verdict,
                creating = creating,
                createError = createError,
                onSelectDisk = onSelectDisk,
                onSelectFolder = onSelectFolder,
                onChooseFolder = onChooseFolder,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
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
            // The one accent action (S26-3, T27): browse the machine's
            // disks and folders to add a project.
            Button(
                onClick = onBrowseOpened,
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
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** The browser body (T27, S30-3): one surface, hairline rows, the words
 *  the design law asks for. Disk level first; a folder level shows the
 *  folder's own path and its folders, each row its own action. The
 *  check/create verdict renders where the choice was made. */
@Composable
private fun BrowseBody(
    level: WorkspacesModel.BrowseState,
    disks: List<DiskDto>?,
    disksLoading: Boolean,
    disksError: String?,
    folders: List<FolderEntryDto>?,
    foldersLoading: Boolean,
    foldersError: String?,
    checking: Boolean,
    verdict: DirCheckDto?,
    creating: Boolean,
    createError: String?,
    onSelectDisk: (DiskDto) -> Unit,
    onSelectFolder: (FolderEntryDto) -> Unit,
    onChooseFolder: () -> Unit,
) {
    val p = LocalPalette.current
    val inFolder = level as? WorkspacesModel.BrowseState.InFolder

    if (inFolder == null) {
        Text(
            text = stringResource(R.string.ws_browse_title),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 22.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.Bold,
        )
        GroupLabel(stringResource(R.string.ws_browse_disks))
        when {
            disksLoading && disks == null -> Text(
                text = stringResource(R.string.ws_browse_reading),
                color = p.muted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            disksError != null -> Text(
                text = disksError,
                color = p.error,
                fontSize = 13.sp,
            )
            disks != null && disks.isEmpty() -> Text(
                text = stringResource(R.string.ws_browse_empty),
                color = p.muted,
                fontSize = 13.sp,
            )
            disks != null -> WorkspacesModel.diskRows(disks).forEach { disk ->
                BrowseRow(
                    title = disk.label,
                    meta = WorkspacesModel.diskMeta(disk),
                    badgeWord = null,
                    badgeAccent = false,
                    enabled = !checking && !creating,
                    onClick = { onSelectDisk(disk) },
                )
            }
        }
    } else {
        Text(
            text = WorkspacesModel.workspaceLabel(inFolder.path),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 22.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = inFolder.path,
            color = p.muted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        GroupLabel(stringResource(R.string.ws_browse_folder))
        when {
            foldersLoading && folders == null -> Text(
                text = stringResource(R.string.ws_browse_reading_folder),
                color = p.muted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            foldersError != null -> Text(
                text = foldersError,
                color = p.error,
                fontSize = 13.sp,
            )
            folders != null && folders.isEmpty() -> Text(
                text = stringResource(R.string.ws_browse_empty),
                color = p.muted,
                fontSize = 13.sp,
            )
            folders != null -> folders.forEach { folder ->
                val badge = WorkspacesModel.folderBadge(folder)
                BrowseRow(
                    title = folder.name,
                    meta = folder.path,
                    badgeWord = when (badge) {
                        is WorkspacesModel.FolderBadge.Git -> stringResource(R.string.ws_browse_git)
                        is WorkspacesModel.FolderBadge.Symlink ->
                            badge.target?.let { stringResource(R.string.ws_browse_symlink, it) }
                                ?: "→"
                        null -> null
                    },
                    badgeAccent = badge is WorkspacesModel.FolderBadge.Git,
                    enabled = !checking && !creating,
                    onClick = { onSelectFolder(folder) },
                )
            }
        }
    }

    // The screen's one accent action (S30-3): choosing the folder the
    // human is standing in creates the workspace there. A row tap only
    // moves the browser — the choice is deliberate, never a side effect
    // of descending.
    if (inFolder != null) {
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onChooseFolder,
            enabled = !checking && !creating,
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
            Text(text = stringResource(R.string.ws_browse_choose), fontWeight = FontWeight.SemiBold)
        }
    }

    // The measured verdict (S26-3): its own plain sentence when the chosen
    // path was refused, the git note when it was accepted — the same
    // sentences the typed path showed.
    if (checking) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.ws_add_working),
            color = p.muted,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
    verdict?.let { v ->
        Spacer(Modifier.height(8.dp))
        WorkspacesModel.rejectionRes(v.verdict)?.let { res ->
            Text(
                text = stringResource(res, inFolder?.path ?: ""),
                color = p.error,
                fontSize = 13.sp,
            )
        }
        if (v.verdict == DirVerdictDto.OK) {
            Text(
                text = stringResource(WorkspacesModel.gitNoteRes(v.isGitRepo)),
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
}

/** One browser row (the artboard's hairline row): title, mono meta, an
 *  optional badge word (the `.git` marker in accent, or the symlink target
 *  quietly) and the chevron that makes the whole row the action. */
@Composable
private fun BrowseRow(
    title: String,
    meta: String,
    badgeWord: String?,
    badgeAccent: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (badgeWord != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = badgeWord,
                        color = if (badgeAccent) p.accent else p.muted,
                        fontSize = 11.sp,
                        letterSpacing = 0.5.sp,
                    )
                }
            }
            Text(
                text = meta,
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
