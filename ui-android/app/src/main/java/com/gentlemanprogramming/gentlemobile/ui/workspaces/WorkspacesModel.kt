package com.gentlemanprogramming.gentlemobile.ui.workspaces

import androidx.annotation.StringRes
import com.gentlemanprogramming.gentlemobile.R
import com.gentlemanprogramming.gentlemobile.core.AgentDto
import com.gentlemanprogramming.gentlemobile.core.AgentStatusDto
import com.gentlemanprogramming.gentlemobile.core.DirVerdictDto
import com.gentlemanprogramming.gentlemobile.core.DiskDto
import com.gentlemanprogramming.gentlemobile.core.FolderEntryDto
import com.gentlemanprogramming.gentlemobile.ui.sessions.SessionsModel

/**
 * The pure row words and decisions for the workspaces screen and the
 * `+` add-project flow (S26-2, S26-3). The composable renders these; the
 * words themselves are unit-tested so the workspaces work cannot regress
 * silently.
 *
 * Honesty rules encoded here:
 * * the inbox filter shows only the chosen workspace's agents, and `null`
 *   (the "All workspaces" action) shows every agent again;
 * * a path verdict that is not OK carries its own plain sentence — the `+`
   * flow never invents a generic failure;
 * * a directory that is not a git repository says so, and may still be
 *   added (a workspace does not require git).
 */
object WorkspacesModel {

    /**
     * The agents the inbox shows for the chosen workspace (S26-2): only
     * that workspace's own agents. `null` means no filter — every agent
     * of the host, exactly the pre-workspaces inbox.
     */
    fun agentsInWorkspace(all: List<AgentDto>, workspaceId: String?): List<AgentDto> =
        if (workspaceId == null) all else all.filter { it.workspaceId == workspaceId }

    /**
     * The row's state word resource: the same human words the inbox groups
     * and the session header use (S24-6), never a raw wire word. The word
     * `Idle` never renders (S27-8): an idle workspace says nothing, its row
     * meta stays branch · agent count.
     */
    @StringRes
    fun statusWordRes(status: AgentStatusDto): Int? = when (status) {
        AgentStatusDto.IDLE -> null
        AgentStatusDto.WORKING -> R.string.status_working
        AgentStatusDto.BLOCKED -> R.string.status_needs_you
        AgentStatusDto.DONE -> R.string.status_done
        AgentStatusDto.UNKNOWN -> R.string.status_unknown
    }

    /**
     * The `+` flow's default workspace label: the folder's own name, the
     * same rule the sessions rows use for project names (reuse, not a
     * second implementation).
     */
    fun workspaceLabel(path: String): String = SessionsModel.repoName(path)

    /** Where tapping a workspace row goes (S28-5, no screen that only
     *  repeats the name): a workspace with exactly one agent goes straight
     *  into that agent's session; several agents — or none — go to the
     *  workspace's agent list, which shows an honest empty state rather
     *  than inventing a session. */
    sealed interface WorkspaceEntry {
        /** Straight into the one agent's session. */
        data class Session(val agent: AgentDto) : WorkspaceEntry

        /** The workspace's agent list (empty included). */
        data object AgentList : WorkspaceEntry
    }

    fun workspaceEntry(agents: List<AgentDto>): WorkspaceEntry =
        if (agents.size == 1) WorkspaceEntry.Session(agents[0]) else WorkspaceEntry.AgentList

    /**
     * The plain sentence for a failed path check (S26-3), or `null` when
     * the path is fine and the flow may continue to `workspace.create`.
     */
    @StringRes
    fun rejectionRes(verdict: DirVerdictDto): Int? = when (verdict) {
        DirVerdictDto.OK -> null
        DirVerdictDto.MISSING -> R.string.ws_path_missing
        DirVerdictDto.NOT_A_DIRECTORY -> R.string.ws_not_a_directory
        DirVerdictDto.NO_PERMISSION -> R.string.ws_no_permission
    }

    /**
     * Whether the checked path is a git repository, as the row under the
     * field says it (S26-3: "say whether it is a git repository").
     */
    @StringRes
    fun gitNoteRes(isGitRepo: Boolean): Int =
        if (isGitRepo) R.string.ws_is_git else R.string.ws_not_git

    // --- T27 (S30-3): the disk and folder browser of the `+` -----------------

    /** Where the browser stands: the machine's disks, or inside one folder
     *  (the path carrying the absolute location the human tapped into). */
    sealed interface BrowseState {
        /** The machine's disks, root first, exactly in the order the core
         *  sent. */
        data object Disks : BrowseState

        /** Inside a folder: the path is where the next listing happens and
         *  what the workspace would be created from. */
        data class InFolder(val path: String) : BrowseState
    }

    /** Tapping a disk opens its mount point for browsing. */
    fun selectDisk(state: BrowseState, disk: DiskDto): BrowseState =
        BrowseState.InFolder(disk.mountPoint)

    /** Tapping a folder descends into it — the choice is a separate
     *  action, never a side effect of moving ([chosenPath]). */
    fun selectFolder(state: BrowseState, folder: FolderEntryDto): BrowseState =
        BrowseState.InFolder(folder.path)

    /** The folder the workspace would be created from: the one the human
     *  is standing in — what the screen's choose action sends. */
    fun chosenPath(state: BrowseState): String = when (state) {
        is BrowseState.InFolder -> state.path
        BrowseState.Disks -> ""
    }

    /** Back navigation: one level up inside the machine; from a disk's mount
     *  point, or from the filesystem root, back to the disk list; from the
     *  disk list the browser closes (`null` — the caller leaves the screen).
     *  The mount points are where "up" stops, so a deep folder returns to its
     *  parent instead of jumping to the top in one press. */
    fun browseBack(state: BrowseState, mountPoints: List<String>): BrowseState? = when (state) {
        is BrowseState.InFolder ->
            if (state.path in mountPoints) BrowseState.Disks
            else parentOf(state.path)?.let { BrowseState.InFolder(it) } ?: BrowseState.Disks
        BrowseState.Disks -> null
    }

    /** The folder that holds [path], or `null` when [path] is the
     *  filesystem root (which is a mount point anyway). */
    private fun parentOf(path: String): String? {
        val trimmed = path.trimEnd('/')
        if (trimmed.isEmpty()) return null
        val slash = trimmed.lastIndexOf('/')
        return if (slash <= 0) "/" else trimmed.substring(0, slash)
    }

    /** The disk rows in the order the core sent them — root first, then by
     *  mount point; never re-sorted here. */
    fun diskRows(disks: List<DiskDto>): List<DiskDto> = disks

    /** The mono meta of a disk row: the mount point and the filesystem
     *  type the host reported. */
    fun diskMeta(disk: DiskDto): String = "${disk.mountPoint} · ${disk.fsType}"

    /** What a folder row says besides its name: a `.git` marker when the
     *  folder is a repository, a symlink's target (never followed) when it
     *  is a link. The symlink decides first — the core never claims a git
     *  verdict for a symlink, and only one badge ever renders. */
    sealed interface FolderBadge {
        /** The folder carries a `.git` entry. */
        data object Git : FolderBadge

        /** A symlink: the target the host reported, or `null` when even
         *  the target could not be read — the row stays honest either way. */
        data class Symlink(val target: String?) : FolderBadge
    }

    fun folderBadge(row: FolderEntryDto): FolderBadge? = when {
        row.isSymlink -> FolderBadge.Symlink(row.target)
        row.isGitRepo -> FolderBadge.Git
        else -> null
    }
}
