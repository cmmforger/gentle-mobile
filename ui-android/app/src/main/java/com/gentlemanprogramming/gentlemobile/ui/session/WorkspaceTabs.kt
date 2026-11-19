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

    /**
     * The tab the human is seeing right now: the daemon's focused tab
     * wins — after a tap focuses another tab (S28-1) the visible pane is
     * the daemon's, whether or not the session's own agent sits there —
     * and before the first focus answer arrives, where the open agent's
     * pane sits stands in. An unknown pane (before the first snapshot)
     * marks nothing.
     */
    fun currentTabId(tabs: List<TabDto>, panes: List<PaneDto>, paneId: String): String? =
        tabs.firstOrNull { it.focused }?.id
            ?: panes.firstOrNull { it.id == paneId }?.tabId

    /**
     * The agent whose pane lives in this tab, if any — the agent a tap can
     * rebind the session to (the app renders agent panes and the composer
     * addresses an agent; `null` means the tab has no agent to rebind to).
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
