package com.gentlemanprogramming.gentlemobile.ui.session

import com.gentlemanprogramming.gentlemobile.core.AgentDto
import com.gentlemanprogramming.gentlemobile.core.WorkspaceRowDto
import com.gentlemanprogramming.gentlemobile.ui.AgentRowModel

/**
 * The workspaces sidebar's rows (T60): the host's workspaces, each with its
 * agents under it, the current workspace and the current pane marked. The
 * sidebar is read-only — entering another workspace focuses it in the shared
 * daemon and disturbs the human's desktop session (L22–L25), so the rows are
 * a list to look at and jump within the current workspace, never a switch
 * that reflows someone else's TUI. Pure, so the row order and the current
 * markers are pinned by a test.
 */
object TerminalSidebar {

    /** One row: a workspace header or an agent under the last header. */
    sealed interface Row {
        data class Workspace(
            val id: String,
            val label: String,
            val branch: String?,
            val current: Boolean,
        ) : Row

        data class Agent(
            val agent: AgentDto,
            val title: String,
            val current: Boolean,
        ) : Row
    }

    /** The rows in the host's own order: each workspace header followed by
     *  its agents, the current workspace and the current pane marked. */
    fun rows(
        workspaces: List<WorkspaceRowDto>,
        agents: List<AgentDto>,
        currentWorkspaceId: String,
        currentPaneId: String,
    ): List<Row> = buildList {
        for (workspace in workspaces) {
            add(
                Row.Workspace(
                    id = workspace.id,
                    label = workspace.label,
                    branch = workspace.branch,
                    current = workspace.id == currentWorkspaceId,
                ),
            )
            for (agent in agents.filter { it.workspaceId == workspace.id }) {
                add(
                    Row.Agent(
                        agent = agent,
                        title = AgentRowModel.projectTitle(agent.cwd, agent.name),
                        current = agent.paneId == currentPaneId,
                    ),
                )
            }
        }
    }
}
