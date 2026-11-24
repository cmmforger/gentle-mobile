package com.gentlemanprogramming.gentlemobile.ui.session

import com.gentlemanprogramming.gentlemobile.core.AgentDto
import com.gentlemanprogramming.gentlemobile.core.AgentStatusDto
import com.gentlemanprogramming.gentlemobile.core.PaneDto
import com.gentlemanprogramming.gentlemobile.core.TabDto

/**
 * The pane switcher (T36): one sheet listing every pane of the workspace —
 * its title and its state — where a pick actually switches the pane the
 * phone controls. The strip on the card opens the sheet; the rows here are
 * the pure decisions, unit-tested without a UI runtime.
 *
 * Title rule: an agent pane names the project (the same rule the inbox and
 * the session header use), a shell pane falls back to its label, then its
 * working directory's last segment, then its tab's label. State: an agent
 * pane carries the agent's own status word; a shell pane says `shell`.
 */
object PaneSwitcherModel {

    /** One row of the switcher sheet. */
    data class Row(
        val paneId: String,
        val tabId: String,
        val title: String,
        val state: State,
        val agentPane: Boolean,
        val current: Boolean,
    )

    /** The row's state: an agent's own status, or a shell pane. */
    sealed interface State {
        data class Agent(val status: AgentStatusDto) : State
        object Shell : State
    }

    /** The workspace's panes, in the daemon's tab order and snapshot order
     *  within each tab, with the visible pane marked. Panes of other
     *  workspaces never appear. */
    fun rows(
        panes: List<PaneDto>,
        agents: List<AgentDto>,
        tabs: List<TabDto>,
        workspaceId: String,
        currentPaneId: String,
    ): List<Row> {
        val orderedTabs = tabs.filter { it.workspaceId == workspaceId }.sortedBy { it.number.toLong() }
        val tabOrder = orderedTabs.mapIndexed { index, tab -> tab.id to index }.toMap()
        val agentsByPane = agents.associateBy { it.paneId }
        return panes
            .filter { it.workspaceId == workspaceId && it.tabId in tabOrder }
            .map { pane ->
                val agent = agentsByPane[pane.id]
                val tab = orderedTabs.firstOrNull { it.id == pane.tabId }
                Row(
                    paneId = pane.id,
                    tabId = pane.tabId,
                    title = title(pane, agent, tab),
                    state = agent?.let { State.Agent(it.status) } ?: State.Shell,
                    agentPane = agent != null,
                    current = pane.id == currentPaneId,
                )
            }
            .sortedWith(compareBy({ tabOrder[it.tabId] ?: Int.MAX_VALUE }, { it.paneId }))
    }

    /** The row's title, nothing invented: agent panes use the project-name
     *  rule; shell panes fall back label → cwd segment → tab label. */
    private fun title(pane: PaneDto, agent: AgentDto?, tab: TabDto?): String {
        agent?.let {
            val project = it.cwd?.trim()?.trimEnd('/')?.substringAfterLast('/') ?: ""
            if (project.isNotEmpty()) return project
            if (it.name.isNotBlank()) return it.name
        }
        pane.label?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        pane.cwd?.trim()?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }?.let { return it }
        return tab?.label?.trim()?.takeIf { it.isNotEmpty() } ?: pane.id
    }

    /** One tab row of the pane sheet (T51): the workspace's own tabs, with
     *  the delete rule — never the workspace's last tab. */
    data class TabRow(
        val tabId: String,
        val label: String,
        val deletable: Boolean,
    )

    /** The workspace's tabs as sheet rows, in the daemon's tab order. A tab
     *  is deletable only while the workspace has more than one — the last
     *  tab is never deleted, because a workspace always keeps one. */
    fun tabRows(tabs: List<TabDto>, workspaceId: String): List<TabRow> {
        val ordered = tabs.filter { it.workspaceId == workspaceId }.sortedBy { it.number.toLong() }
        return ordered.map { tab ->
            TabRow(
                tabId = tab.id,
                label = tab.label.trim().takeIf { it.isNotEmpty() } ?: tab.number.toString(),
                deletable = ordered.size > 1,
            )
        }
    }
}
