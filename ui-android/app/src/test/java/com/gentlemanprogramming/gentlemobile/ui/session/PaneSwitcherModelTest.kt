package com.gentlemanprogramming.gentlemobile.ui.session

import com.gentlemanprogramming.gentlemobile.core.AgentDto
import com.gentlemanprogramming.gentlemobile.core.AgentStatusDto
import com.gentlemanprogramming.gentlemobile.core.PaneDto
import com.gentlemanprogramming.gentlemobile.core.TabDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pane switcher (T36): the session screen's strip opens one sheet with
 * every pane of the workspace — title, state word, the visible one marked —
 * the way Termux's session switcher works. The decisions are pure and pinned
 * here; the sheet's Compose view is not unit-tested.
 */
class PaneSwitcherModelTest {

    private fun pane(id: String, tab: String, label: String? = null, cwd: String? = null, focused: Boolean = false) =
        PaneDto(id = id, workspaceId = "w1", tabId = tab, label = label, cwd = cwd, focused = focused)

    private fun tab(id: String, number: Long, label: String = "", focused: Boolean = false) =
        TabDto(id = id, workspaceId = "w1", number = number.toULong(), label = label, focused = focused)

    private fun agent(pane: String, cwd: String? = "/repo/app", name: String = "pi", status: AgentStatusDto = AgentStatusDto.IDLE) =
        AgentDto(name = name, kind = null, status = status, cwd = cwd, focused = false, paneId = pane, workspaceId = "w1")

    @Test
    fun `rows come in the daemon's tab order, panes within a tab in snapshot order`() {
        val rows = PaneSwitcherModel.rows(
            panes = listOf(pane("w1:p2", "t2", cwd = "/repo/b"), pane("w1:p1", "t1", cwd = "/repo/a")),
            agents = emptyList(),
            tabs = listOf(tab("t2", 2), tab("t1", 1)),
            workspaceId = "w1",
            currentPaneId = "w1:p1",
        )
        assertEquals(listOf("w1:p1", "w1:p2"), rows.map { it.paneId })
    }

    @Test
    fun `an agent pane's title is the project name and its state the agent status`() {
        val rows = PaneSwitcherModel.rows(
            panes = listOf(pane("w1:p1", "t1", cwd = "/repo/gentle-mobile")),
            agents = listOf(agent("w1:p1", cwd = "/repo/gentle-mobile", status = AgentStatusDto.WORKING)),
            tabs = listOf(tab("t1", 1)),
            workspaceId = "w1",
            currentPaneId = "w1:p1",
        )
        assertEquals("gentle-mobile", rows.single().title)
        assertEquals(PaneSwitcherModel.State.Agent(AgentStatusDto.WORKING), rows.single().state)
    }

    @Test
    fun `a shell pane falls back to its label, then its cwd, then the tab`() {
        val rows = PaneSwitcherModel.rows(
            panes = listOf(
                pane("w1:p1", "t1", label = "build", cwd = null),
                pane("w1:p2", "t2", label = null, cwd = "/srv/deploy"),
                pane("w1:p3", "t3", label = null, cwd = null),
            ),
            agents = emptyList(),
            tabs = listOf(tab("t1", 1), tab("t2", 2), tab("t3", 3, label = "logs")),
            workspaceId = "w1",
            currentPaneId = "w1:p1",
        )
        assertEquals("build", rows[0].title)
        assertEquals("deploy", rows[1].title)
        assertEquals("logs", rows[2].title)
        assertTrue(rows.all { it.state == PaneSwitcherModel.State.Shell })
    }

    @Test
    fun `the visible pane is marked and panes outside the workspace never appear`() {
        val rows = PaneSwitcherModel.rows(
            panes = listOf(
                pane("w1:p1", "t1", cwd = "/a", focused = true),
                pane("w9:p1", "t9", cwd = "/other"),
            ),
            agents = emptyList(),
            tabs = listOf(tab("t1", 1)),
            workspaceId = "w1",
            currentPaneId = "w1:p1",
        )
        assertEquals(1, rows.size)
        assertTrue(rows.single().current)
    }

    @Test
    fun `an agent pane is switchable, a shell pane carries the honest no-agent word`() {
        val rows = PaneSwitcherModel.rows(
            panes = listOf(pane("w1:p1", "t1", cwd = "/a"), pane("w1:p2", "t2", cwd = "/b")),
            agents = listOf(agent("w1:p1")),
            tabs = listOf(tab("t1", 1), tab("t2", 2)),
            workspaceId = "w1",
            currentPaneId = "w1:p1",
        )
        assertTrue(rows[0].agentPane)
        assertFalse(rows[1].agentPane)
    }
}
