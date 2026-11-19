package com.gentlemanprogramming.gentlemobile.ui.session

import com.gentlemanprogramming.gentlemobile.core.AgentDto
import com.gentlemanprogramming.gentlemobile.core.PaneDto
import com.gentlemanprogramming.gentlemobile.core.TabDto

/**
 * The workspace's own tabs (S27-7): the session card's top strip shows the
 * tabs of the workspace the human is in, from the data the daemon already
 * sends in the snapshot — on the wire these are `tab.list {workspace_id}`
 * rows (measured from `herdr api schema --json`, protocol 22) and the
 * snapshot's `tabs`/`panes` lists — with the visible one marked, so
 * tapping a tab makes it the visible one instead of a numbered bar that
 * does nothing.
 *
 * Pure decisions so the rule is unit-tested without a UI runtime
 * (WorkspaceTabsModelTest).
 */
object WorkspaceTabsModel {

    /** The current workspace's tabs, ordered by the daemon's tab number —
     *  the strip's order must match the herdr TUI's, not arrival order. */
    fun tabsOfWorkspace(tabs: List<TabDto>, workspaceId: String): List<TabDto> =
        tabs.filter { it.workspaceId == workspaceId }.sortedBy { it.number.toLong() }

    /** The tab the given pane sits in — the strip marks it as the visible
     *  one. An unknown pane (before the first snapshot) marks nothing. */
    fun currentTabId(panes: List<PaneDto>, paneId: String): String? =
        panes.firstOrNull { it.id == paneId }?.tabId

    /**
     * The agent whose pane lives in this tab, if any — the agent a tap can
     * make visible (the app renders agent panes; `null` means the tab has
     * no agent to show).
     */
    fun agentInTab(panes: List<PaneDto>, agents: List<AgentDto>, tabId: String): AgentDto? {
        val paneIds = panes.filter { it.tabId == tabId }.map { it.id }.toSet()
        if (paneIds.isEmpty()) return null
        return agents.firstOrNull { it.paneId in paneIds }
    }

    /** One lookup per tab: the agent each tab can make visible (empty when
     *  the tab hosts no agent). Built once per snapshot, read on every tap. */
    fun tabAgentsByTab(panes: List<PaneDto>, agents: List<AgentDto>): Map<String, AgentDto> {
        val paneTab = panes.associate { it.id to it.tabId }
        val map = HashMap<String, AgentDto>()
        for (agent in agents) {
            paneTab[agent.paneId]?.let { tabId -> map.putIfAbsent(tabId, agent) }
        }
        return map
    }
}
