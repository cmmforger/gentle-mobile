package com.gentlemanprogramming.gentlemobile.ui.workspaces

import androidx.annotation.StringRes
import com.gentlemanprogramming.gentlemobile.R
import com.gentlemanprogramming.gentlemobile.core.AgentDto
import com.gentlemanprogramming.gentlemobile.core.AgentStatusDto
import com.gentlemanprogramming.gentlemobile.core.DirVerdictDto
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
}
