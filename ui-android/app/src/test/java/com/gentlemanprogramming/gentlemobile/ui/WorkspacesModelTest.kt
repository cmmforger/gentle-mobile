package com.gentlemanprogramming.gentlemobile.ui

import com.gentlemanprogramming.gentlemobile.core.AgentDto
import com.gentlemanprogramming.gentlemobile.core.AgentStatusDto
import com.gentlemanprogramming.gentlemobile.core.DirVerdictDto
import com.gentlemanprogramming.gentlemobile.R
import com.gentlemanprogramming.gentlemobile.ui.workspaces.WorkspacesModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pure decisions behind the workspaces screen and the `+` add-project
 * flow (S26-2, S26-3): the inbox filter, the state words, the folder-name
 * label, and one plain sentence per path-check rejection.
 */
class WorkspacesModelTest {

    private fun agent(paneId: String, workspaceId: String) = AgentDto(
        name = "agent-$paneId",
        kind = "pi",
        status = AgentStatusDto.IDLE,
        cwd = "/srv/repo",
        focused = false,
        paneId = paneId,
        workspaceId = workspaceId,
    )

    // --- agentsInWorkspace (S26-2) ---------------------------------------------

    @Test
    fun the_filter_shows_only_the_chosen_workspaces_agents() {
        val all = listOf(
            agent("p1", "w1F"),
            agent("p2", "wN"),
            agent("p3", "w1F"),
        )
        val shown = WorkspacesModel.agentsInWorkspace(all, "w1F")
        assertEquals(listOf("p1", "p3"), shown.map { it.paneId })
    }

    @Test
    fun no_filter_shows_every_agent_again() {
        val all = listOf(agent("p1", "w1F"), agent("p2", "wN"))
        assertEquals(all, WorkspacesModel.agentsInWorkspace(all, null))
    }

    @Test
    fun a_workspace_without_agents_shows_an_empty_list_not_everything() {
        val all = listOf(agent("p1", "w1F"))
        assertEquals(emptyList<AgentDto>(), WorkspacesModel.agentsInWorkspace(all, "wN"))
    }

    // --- statusWordRes (S26-2) ---------------------------------------------------

    @Test
    fun every_workspace_status_maps_to_its_human_word() {
        assertEquals(R.string.status_needs_you, WorkspacesModel.statusWordRes(AgentStatusDto.BLOCKED))
        assertEquals(R.string.status_done, WorkspacesModel.statusWordRes(AgentStatusDto.DONE))
        assertEquals(R.string.status_working, WorkspacesModel.statusWordRes(AgentStatusDto.WORKING))
        assertEquals(R.string.status_idle, WorkspacesModel.statusWordRes(AgentStatusDto.IDLE))
        assertEquals(R.string.status_unknown, WorkspacesModel.statusWordRes(AgentStatusDto.UNKNOWN))
    }

    // --- workspaceLabel (S26-3) ---------------------------------------------------

    @Test
    fun the_label_is_the_folder_name_not_the_path() {
        assertEquals("t36-repo", WorkspacesModel.workspaceLabel("/mnt/WD-Black/test_tmp/gentle-companion/t36-repo"))
    }

    @Test
    fun a_trailing_slash_never_makes_an_empty_label() {
        assertEquals("t36-repo", WorkspacesModel.workspaceLabel("/mnt/tmp/t36-repo/"))
    }

    // --- rejectionRes (S26-3): one plain sentence per rejection -------------------

    @Test
    fun a_good_path_has_no_rejection_sentence() {
        assertNull(WorkspacesModel.rejectionRes(DirVerdictDto.OK))
    }

    @Test
    fun every_bad_verdict_has_its_own_sentence() {
        val sentences = listOf(
            WorkspacesModel.rejectionRes(DirVerdictDto.MISSING),
            WorkspacesModel.rejectionRes(DirVerdictDto.NOT_A_DIRECTORY),
            WorkspacesModel.rejectionRes(DirVerdictDto.NO_PERMISSION),
        )
        assertEquals("each rejection carries its own sentence", 3, sentences.toSet().size)
        assertEquals(R.string.ws_path_missing, sentences[0])
        assertEquals(R.string.ws_not_a_directory, sentences[1])
        assertEquals(R.string.ws_no_permission, sentences[2])
    }

    // --- gitNoteRes (S26-3) --------------------------------------------------------

    @Test
    fun the_git_note_says_what_the_path_is() {
        assertEquals(R.string.ws_is_git, WorkspacesModel.gitNoteRes(true))
        assertEquals(R.string.ws_not_git, WorkspacesModel.gitNoteRes(false))
    }
}
