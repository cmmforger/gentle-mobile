package com.gentlemanprogramming.gentlemobile.ui.session

import com.gentlemanprogramming.gentlemobile.core.AgentDto
import com.gentlemanprogramming.gentlemobile.core.AgentStatusDto
import com.gentlemanprogramming.gentlemobile.core.WorkspaceRowDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T60 (the branches sidebar): the rows are the host's workspaces, each
 *  followed by its agents, with the current workspace and pane marked. */
class TerminalSidebarTest {

    private fun workspace(id: String, label: String, branch: String? = null) =
        WorkspaceRowDto(
            id = id,
            number = 0uL,
            label = label,
            focused = false,
            status = AgentStatusDto.IDLE,
            branch = branch,
            agentCount = 0uL,
        )

    private fun agent(paneId: String, workspaceId: String, cwd: String? = "/home/x/project") =
        AgentDto(
            name = "pi",
            kind = null,
            status = AgentStatusDto.IDLE,
            cwd = cwd,
            focused = false,
            paneId = paneId,
            workspaceId = workspaceId,
        )

    @Test
    fun `a workspace header is followed by its agents, in the host's order`() {
        val rows = TerminalSidebar.rows(
            workspaces = listOf(workspace("w1", "gentle-mobile", "main"), workspace("w2", "notes")),
            agents = listOf(
                agent("p2", "w2"),
                agent("p1", "w1"),
                agent("p3", "w1"),
            ),
            currentWorkspaceId = "w1",
            currentPaneId = "p1",
        )
        assertEquals(5, rows.size)
        assertEquals(TerminalSidebar.Row.Workspace("w1", "gentle-mobile", "main", current = true), rows[0])
        assertEquals("p1", (rows[1] as TerminalSidebar.Row.Agent).agent.paneId)
        assertEquals("p3", (rows[2] as TerminalSidebar.Row.Agent).agent.paneId)
        assertEquals(TerminalSidebar.Row.Workspace("w2", "notes", null, current = false), rows[3])
        assertEquals("p2", (rows[4] as TerminalSidebar.Row.Agent).agent.paneId)
    }

    @Test
    fun `the agent rows mark the current pane and use the project name`() {
        val rows = TerminalSidebar.rows(
            workspaces = listOf(workspace("w1", "gentle-mobile")),
            agents = listOf(
                agent("p1", "w1", "/home/x/gentle-mobile"),
                agent("p2", "w1", "/home/x/other-repo"),
            ),
            currentWorkspaceId = "w1",
            currentPaneId = "p2",
        )
        val p1 = rows[1] as TerminalSidebar.Row.Agent
        val p2 = rows[2] as TerminalSidebar.Row.Agent
        assertEquals("gentle-mobile", p1.title)
        assertEquals("other-repo", p2.title)
        assertTrue(!p1.current)
        assertTrue(p2.current)
    }

    @Test
    fun `a workspace with no agents still appears as a header`() {
        val rows = TerminalSidebar.rows(
            workspaces = listOf(workspace("w1", "empty")),
            agents = emptyList(),
            currentWorkspaceId = "w1",
            currentPaneId = "nope",
        )
        assertEquals(1, rows.size)
        assertEquals(TerminalSidebar.Row.Workspace("w1", "empty", null, current = true), rows[0])
    }
}
