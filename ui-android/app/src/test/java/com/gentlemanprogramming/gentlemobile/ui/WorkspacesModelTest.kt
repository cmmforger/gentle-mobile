package com.gentlemanprogramming.gentlemobile.ui

import com.gentlemanprogramming.gentlemobile.core.AgentDto
import com.gentlemanprogramming.gentlemobile.core.AgentStatusDto
import com.gentlemanprogramming.gentlemobile.core.DirVerdictDto
import com.gentlemanprogramming.gentlemobile.core.DiskDto
import com.gentlemanprogramming.gentlemobile.core.FolderEntryDto
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

    /** S27-8: the word `Idle` never renders — an idle workspace's row meta
     *  carries just the branch and the agent count, quietly. */
    @Test
    fun an_idle_workspace_says_nothing() {
        assertNull(WorkspacesModel.statusWordRes(AgentStatusDto.IDLE))
    }

    @Test
    fun every_other_workspace_status_maps_to_its_human_word() {
        assertEquals(R.string.status_needs_you, WorkspacesModel.statusWordRes(AgentStatusDto.BLOCKED))
        assertEquals(R.string.status_done, WorkspacesModel.statusWordRes(AgentStatusDto.DONE))
        assertEquals(R.string.status_working, WorkspacesModel.statusWordRes(AgentStatusDto.WORKING))
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

    // --- T27 (S30-3): the disk and folder browser of the `+` -------------------

    private fun disk(mountPoint: String, label: String, fsType: String) = DiskDto(
        mountPoint = mountPoint,
        label = label,
        fsType = fsType,
    )

    private fun folder(name: String, path: String, isSymlink: Boolean = false, target: String? = null, isGit: Boolean = false) = FolderEntryDto(
        name = name,
        path = path,
        isSymlink = isSymlink,
        target = target,
        isGitRepo = isGit,
    )

    /** The rows keep the order the core sent — root first, then by mount
     *  point; sorting again here would move the root away from the top. */
    @Test
    fun disk_rows_keep_the_core_order() {
        val fromCore = listOf(
            disk("/", "System", "btrfs"),
            disk("/mnt/D-SSD", "D-SSD", "ext4"),
            disk("/mnt/WD-Black", "WD-Black", "ext4"),
        )
        val rows = WorkspacesModel.diskRows(fromCore)
        assertEquals(fromCore, rows)
    }

    /** The row meta is the mount point and the filesystem type, mono — the
     *  hardware's own words, nothing invented. */
    @Test
    fun a_disk_row_says_its_mount_point_and_filesystem() {
        assertEquals("/mnt/D-SSD · ext4", WorkspacesModel.diskMeta(disk("/mnt/D-SSD", "D-SSD", "ext4")))
        assertEquals("/ · btrfs", WorkspacesModel.diskMeta(disk("/", "System", "btrfs")))
    }

    /** The disk's own label is the title the human recognises. */
    @Test
    fun a_disk_row_shows_the_disk_label_not_the_path() {
        val rows = WorkspacesModel.diskRows(listOf(disk("/mnt/D-SSD", "D-SSD", "ext4")))
        assertEquals("D-SSD", rows.single().label)
    }

    // --- folder badges: `.git` marker, symlink target ---------------------------

    @Test
    fun a_git_folder_carries_the_git_marker() {
        val badge = WorkspacesModel.folderBadge(folder("work", "/repos/work", isGit = true))
        assertEquals(WorkspacesModel.FolderBadge.Git, badge)
    }

    @Test
    fun a_symlink_shows_its_target_and_never_a_git_verdict() {
        val badge = WorkspacesModel.folderBadge(folder("old", "/repos/old", isSymlink = true, target = "/srv/archive/repos/old"))
        assertEquals(WorkspacesModel.FolderBadge.Symlink("/srv/archive/repos/old"), badge)
    }

    @Test
    fun a_symlink_without_a_readable_target_is_still_a_symlink() {
        val badge = WorkspacesModel.folderBadge(folder("broken", "/repos/broken", isSymlink = true, target = null))
        assertEquals(WorkspacesModel.FolderBadge.Symlink(null), badge)
    }

    @Test
    fun a_plain_folder_carries_no_badge() {
        assertNull(WorkspacesModel.folderBadge(folder("scratch", "/repos/scratch")))
    }

    // --- the browsing state machine: disk → folder → back ------------------------

    @Test
    fun tapping_a_disk_opens_its_mount_point() {
        val next = WorkspacesModel.selectDisk(WorkspacesModel.BrowseState.Disks, disk("/mnt/D-SSD", "D-SSD", "ext4"))
        assertEquals(WorkspacesModel.BrowseState.InFolder("/mnt/D-SSD"), next)
    }

    @Test
    fun tapping_a_folder_descends_into_it() {
        val state = WorkspacesModel.BrowseState.InFolder("/mnt/D-SSD")
        val next = WorkspacesModel.selectFolder(state, folder("projects", "/mnt/D-SSD/projects"))
        assertEquals(WorkspacesModel.BrowseState.InFolder("/mnt/D-SSD/projects"), next)
    }

    /** T27 (S30-3): a row tap only moves the browser; the workspace is
     *  created from the folder the human is standing in, by the screen's
     *  one accent action — never as a side effect of descending. */
    @Test
    fun the_chosen_path_is_the_folder_the_human_is_standing_in() {
        assertEquals(
            "/mnt/D-SSD/projects/foo",
            WorkspacesModel.chosenPath(WorkspacesModel.BrowseState.InFolder("/mnt/D-SSD/projects/foo")),
        )
        assertEquals(
            "/",
            WorkspacesModel.chosenPath(WorkspacesModel.BrowseState.InFolder("/")),
        )
    }

    @Test
    fun back_from_a_folder_returns_to_the_disk_list() {
        val state = WorkspacesModel.BrowseState.InFolder("/mnt/D-SSD/projects")
        assertEquals(WorkspacesModel.BrowseState.Disks, WorkspacesModel.browseBack(state))
    }

    @Test
    fun back_from_the_disk_list_leaves_the_browser() {
        assertNull(WorkspacesModel.browseBack(WorkspacesModel.BrowseState.Disks))
    }

    // --- the label of a chosen folder reuses the typed-path rule -----------------

    @Test
    fun the_chosen_folders_label_is_its_name() {
        assertEquals("foo", WorkspacesModel.workspaceLabel("/mnt/D-SSD/projects/foo"))
    }

    @Test
    fun a_folder_chosen_at_the_root_labels_itself_the_same_way() {
        assertEquals("foo", WorkspacesModel.workspaceLabel("/foo"))
    }

    // --- workspaceEntry (S28-5): where tapping a workspace row goes ---------------

    @Test
    fun a_workspace_with_one_agent_goes_straight_into_its_session() {
        val agents = listOf(agent("p1", "w1F"))
        val entry = WorkspacesModel.workspaceEntry(agents)
        assertEquals("p1", (entry as WorkspacesModel.WorkspaceEntry.Session).agent.paneId)
    }

    @Test
    fun a_workspace_with_several_agents_goes_to_their_list() {
        val agents = listOf(agent("p1", "w1F"), agent("p2", "w1F"))
        assertEquals(WorkspacesModel.WorkspaceEntry.AgentList, WorkspacesModel.workspaceEntry(agents))
    }

    @Test
    fun a_workspace_with_no_agents_goes_to_the_honest_empty_list() {
        assertEquals(WorkspacesModel.WorkspaceEntry.AgentList, WorkspacesModel.workspaceEntry(emptyList()))
    }
}
