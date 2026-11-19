package com.gentlemanprogramming.gentlemobile.ui.session

import com.gentlemanprogramming.gentlemobile.core.AgentDto
import com.gentlemanprogramming.gentlemobile.core.AgentStatusDto
import com.gentlemanprogramming.gentlemobile.core.PaneDto
import com.gentlemanprogramming.gentlemobile.core.TabDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The workspace's own tabs (S27-7): the session card's top strip shows the
 * tabs of the workspace the human is in — the data the daemon already puts
 * in the snapshot (`tab.list {workspace_id}` on the wire, measured from
 * `herdr api schema --json`) — with the visible one marked, so tapping a
 * tab makes it the visible one instead of a numbered bar that does nothing.
 */
class WorkspaceTabsModelTest {

    private fun tab(id: String, workspaceId: String, number: ULong, label: String, focused: Boolean = false) =
        TabDto(id = id, workspaceId = workspaceId, number = number, label = label, focused = focused)

    private fun pane(id: String, workspaceId: String, tabId: String) = PaneDto(
        id = id, workspaceId = workspaceId, tabId = tabId, label = null, cwd = null, focused = false,
    )

    private fun agent(paneId: String, workspaceId: String) = AgentDto(
        name = "agent-$paneId",
        kind = "pi",
        status = AgentStatusDto.IDLE,
        cwd = "/srv/repo",
        focused = false,
        paneId = paneId,
        workspaceId = workspaceId,
    )

    // --- tabsOfWorkspace ----------------------------------------------------------

    @Test
    fun `only the current workspace's tabs are shown`() {
        val tabs = listOf(
            tab("t1", "wA", 1UL, "repo-a"),
            tab("t2", "wB", 1UL, "repo-b"),
            tab("t3", "wA", 2UL, "build"),
        )
        val shown = WorkspaceTabsModel.tabsOfWorkspace(tabs, "wA")
        assertEquals(listOf("t1", "t3"), shown.map { it.id })
    }

    @Test
    fun `tabs are ordered by the daemon's tab number, not by arrival`() {
        val tabs = listOf(
            tab("t3", "wA", 3UL, "logs"),
            tab("t1", "wA", 1UL, "repo"),
            tab("t2", "wA", 2UL, "build"),
        )
        assertEquals(listOf("t1", "t2", "t3"), WorkspaceTabsModel.tabsOfWorkspace(tabs, "wA").map { it.id })
    }

    // --- currentTabId -------------------------------------------------------------

    @Test
    fun `the visible tab is the one the open agent's pane sits in`() {
        val panes = listOf(pane("p1", "wA", "t1"), pane("p2", "wA", "t2"))
        assertEquals("t2", WorkspaceTabsModel.currentTabId(panes, "p2"))
    }

    @Test
    fun `a pane the snapshot does not know yet marks no tab`() {
        val panes = listOf(pane("p1", "wA", "t1"))
        assertNull(WorkspaceTabsModel.currentTabId(panes, "ghost"))
    }

    // --- agentInTab: what a tap can make visible ---------------------------------

    @Test
    fun `a tap on a tab hosting an agent can make that agent visible`() {
        val panes = listOf(pane("p1", "wA", "t1"), pane("p2", "wA", "t2"))
        val agents = listOf(agent("p1", "wA"), agent("p2", "wA"))
        assertEquals("p2", WorkspaceTabsModel.agentInTab(panes, agents, "t2")?.paneId)
    }

    @Test
    fun `a tab without an agent offers nothing to open`() {
        val panes = listOf(pane("p1", "wA", "t1"))
        val agents = listOf(agent("p1", "wA"))
        assertNull(WorkspaceTabsModel.agentInTab(panes, agents, "t2"))
    }

    @Test
    fun `with several panes in one tab the agent's own pane wins`() {
        val panes = listOf(pane("p1", "wA", "t1"), pane("p9", "wA", "t1"))
        val agents = listOf(agent("p1", "wA"))
        assertEquals("p1", WorkspaceTabsModel.agentInTab(panes, agents, "t1")?.paneId)
    }

    // --- tabAgentsByTab: the per-tab lookup the strip taps read ------------------

    @Test
    fun `the per-tab lookup maps every agent's tab`() {
        val panes = listOf(pane("p1", "wA", "t1"), pane("p2", "wA", "t2"))
        val agents = listOf(agent("p1", "wA"), agent("p2", "wA"))
        val byTab = WorkspaceTabsModel.tabAgentsByTab(panes, agents)
        assertEquals("p1", byTab["t1"]?.paneId)
        assertEquals("p2", byTab["t2"]?.paneId)
    }

    @Test
    fun `a tab without an agent has no entry in the per-tab lookup`() {
        val panes = listOf(pane("p1", "wA", "t1"), pane("p9", "wA", "t2"))
        val agents = listOf(agent("p1", "wA"))
        val byTab = WorkspaceTabsModel.tabAgentsByTab(panes, agents)
        assertNull(byTab["t2"])
    }
}
