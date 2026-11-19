package com.gentlemanprogramming.gentlemobile.ui

import com.gentlemanprogramming.gentlemobile.ui.sessions.SessionsModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pure row words for the grouped agent list (S24-6/S24-8): the project
 * name stands in as the row title, `pi` is never repeated beside it, and the
 * group label carries the count only when it is not one. The composable that
 * renders these is MainActivity's inbox; the words themselves are tested
 * here.
 */
class AgentRowTest {

    // --- projectTitle (S24-8) ---------------------------------------------------

    @Test
    fun the_row_title_is_the_project_name_the_last_path_segment() {
        assertEquals("erp-web", AgentRowModel.projectTitle("/mnt/D-SSD/work/projects/erp-web", "pi"))
    }

    @Test
    fun a_trailing_slash_never_becomes_an_empty_title() {
        assertEquals("erp-web", AgentRowModel.projectTitle("/mnt/D-SSD/work/projects/erp-web/", "pi"))
    }

    @Test
    fun without_a_cwd_the_agent_name_stands_in() {
        assertEquals("pi", AgentRowModel.projectTitle(null, "pi"))
    }

    @Test
    fun a_blank_cwd_falls_back_to_the_agent_name() {
        assertEquals("review", AgentRowModel.projectTitle("   ", "review"))
    }

    // --- secondaryKind (S24-8) ----------------------------------------------------

    @Test
    fun pi_is_never_repeated_in_the_row_meta() {
        assertNull(AgentRowModel.secondaryKind("pi"))
    }

    @Test
    fun a_kind_that_differs_from_pi_stays_in_the_row_meta() {
        assertEquals("codex", AgentRowModel.secondaryKind("codex"))
    }

    @Test
    fun a_blank_kind_is_not_shown() {
        assertNull(AgentRowModel.secondaryKind(""))
        assertNull(AgentRowModel.secondaryKind(null))
    }

    // --- secondaryMeta (S24-8): kind when it is not `pi`, plus relative last-used time,
    //     never the full path -------------------------------------------------------

    @Test
    fun `the meta line carries the kind and the relative last-used time`() {
        val now = 1_000_000L
        assertEquals(
            "codex · 2 min ago",
            AgentRowModel.secondaryMeta("codex", now - 2 * 60_000, now),
        )
    }

    @Test
    fun `pi is never repeated and the time stands alone`() {
        val now = 1_000_000L
        assertEquals("just now", AgentRowModel.secondaryMeta("pi", now - 10_000, now))
        assertNull(AgentRowModel.secondaryMeta("pi", null, now))
    }

    @Test
    fun `a kind without observed activity still names the kind`() {
        assertEquals("codex", AgentRowModel.secondaryMeta("codex", null, 0))
        assertNull(AgentRowModel.secondaryMeta(null, null, 0))
    }

    @Test
    fun `a host clock slightly ahead never shows a negative time`() {
        val now = 1_000_000L
        assertEquals("just now", AgentRowModel.secondaryMeta(null, now + 60_000, now))
    }

    // --- groupLabel (S24-6) ---------------------------------------------------------

    @Test
    fun the_group_label_drops_the_count_when_it_is_one() {
        assertEquals("Idle", AgentRowModel.groupLabel("Idle", 1))
        assertEquals("Needs you", AgentRowModel.groupLabel("Needs you", 1))
    }

    @Test
    fun the_group_label_keeps_the_count_when_it_is_not_one() {
        assertEquals("Idle · 2", AgentRowModel.groupLabel("Idle", 2))
        assertEquals("Needs you · 3", AgentRowModel.groupLabel("Needs you", 3))
    }
}
