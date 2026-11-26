package com.gentlemanprogramming.gentlemobile.ui.sessions

import com.gentlemanprogramming.gentlemobile.core.LeaseViewDto
import com.gentlemanprogramming.gentlemobile.core.SessionDto
import com.gentlemanprogramming.gentlemobile.ui.theme.KeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure sessions/lease words (T17a): device lease id, row state words,
 * resume reasons, relative times and the sessions-directory override. The
 * lease *semantics* live in the Rust core (21 core tests); this is the
 * rendering contract the screen depends on.
 */
class SessionsModelTest {

    private val publicKeyLine =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIPWDm55jssnwg2DJm8f30QML gentle-mobile@sdk_gphone_x86"

    // --- deviceLeaseId ----------------------------------------------------------

    @Test
    fun the_device_lease_id_is_stable_for_the_same_key() {
        assertEquals(SessionsModel.deviceLeaseId(publicKeyLine), SessionsModel.deviceLeaseId(publicKeyLine))
    }

    @Test
    fun the_device_lease_id_is_shaped_and_hex() {
        val id = SessionsModel.deviceLeaseId(publicKeyLine)
        assertTrue("prefix: $id", id.startsWith("dm-"))
        assertEquals(19, id.length) // "dm-" + 16 hex characters
        assertTrue(id.drop(3).all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun different_keys_map_to_different_device_ids() {
        val other = publicKeyLine.replace("PWDm55jssnwg2DJm8f30QML", "PWDm55jssnwg2DJm8f30QM2")
        assertTrue(SessionsModel.deviceLeaseId(publicKeyLine) != SessionsModel.deviceLeaseId(other))
    }

    // --- stateWord ---------------------------------------------------------------

    @Test
    fun every_lease_view_has_a_word() {
        assertEquals("open", SessionsModel.stateWord(LeaseViewDto.OPEN))
        assertEquals("held by this device", SessionsModel.stateWord(LeaseViewDto.HELD_HERE))
        assertEquals("in use elsewhere", SessionsModel.stateWord(LeaseViewDto.IN_USE_ELSEWHERE))
        assertEquals("stale lease", SessionsModel.stateWord(LeaseViewDto.STALE))
    }

    @Test
    fun a_reclaimed_session_says_so_in_its_word() {
        // After the app reclaims a stale lease, the row must carry the
        // visible word, never fall back to a plain "held" state silently.
        assertEquals(
            "stale lease reclaimed",
            SessionsModel.stateWord(LeaseViewDto.HELD_HERE, reclaimed = true),
        )
        assertEquals(
            "held by this device",
            SessionsModel.stateWord(LeaseViewDto.HELD_HERE, reclaimed = false),
        )
    }

    // --- the row's true state word (T36: closed / running / leased) -------------

    private fun stateSession(lease: LeaseViewDto, modified: Long) = SessionDto(
        fileName = "f.jsonl",
        sessionId = "id",
        cwd = null,
        lastModifiedEpochMs = modified.toULong(),
        messageCount = null,
        lease = lease,
    )

    @Test
    fun a_quiet_session_with_no_live_lease_reads_closed() {
        val now = 1_763_632_800_000L
        assertEquals(
            SessionsModel.RowState.CLOSED,
            SessionsModel.rowState(stateSession(LeaseViewDto.OPEN, now - 86_400_000L), now),
        )
        // A dead claim is not a live one either: the row says closed; the
        // sheet carries the take-over flow.
        assertEquals(
            SessionsModel.RowState.CLOSED,
            SessionsModel.rowState(stateSession(LeaseViewDto.STALE, now - 86_400_000L), now),
        )
    }

    @Test
    fun a_session_the_host_wrote_moments_ago_reads_running() {
        val now = 1_763_632_800_000L
        assertEquals(
            SessionsModel.RowState.RUNNING,
            SessionsModel.rowState(stateSession(LeaseViewDto.OPEN, now - 60_000), now),
        )
        // Exactly at the window edge the session is already quiet.
        assertEquals(
            SessionsModel.RowState.CLOSED,
            SessionsModel.rowState(stateSession(LeaseViewDto.OPEN, now - SessionsModel.RUNNING_WINDOW_MS), now),
        )
    }

    @Test
    fun a_live_lease_reads_leased_whichever_device_holds_it() {
        val now = 1_763_632_800_000L
        assertEquals(
            SessionsModel.RowState.LEASED,
            SessionsModel.rowState(stateSession(LeaseViewDto.HELD_HERE, now - 60_000), now),
        )
        assertEquals(
            SessionsModel.RowState.LEASED,
            SessionsModel.rowState(stateSession(LeaseViewDto.IN_USE_ELSEWHERE, now - 60_000), now),
        )
        // A live lease wins over recency: the row never calls a leased
        // session "running".
        assertEquals(
            SessionsModel.RowState.LEASED,
            SessionsModel.rowState(stateSession(LeaseViewDto.IN_USE_ELSEWHERE, now - 86_400_000L), now),
        )
    }

    @Test
    fun a_reclaimed_row_keeps_its_visible_word() {
        val now = 1_763_632_800_000L
        assertEquals(
            SessionsModel.RowState.RECLAIMED,
            SessionsModel.rowState(stateSession(LeaseViewDto.HELD_HERE, now - 60_000), now, reclaimed = true),
        )
    }

    // --- resume gate (T17b: the RPC lane exists; the lease still gates) ---------

    @Test
    fun resume_is_blocked_with_a_reason_on_foreign_and_stale_leases() {
        val elsewhere = SessionsModel.resumeReason(LeaseViewDto.IN_USE_ELSEWHERE, isHeldHere = false)
        assertTrue(elsewhere?.contains("in use elsewhere", ignoreCase = true) == true)
        val stale = SessionsModel.resumeReason(LeaseViewDto.STALE, isHeldHere = false)
        assertTrue(stale?.contains("stale", ignoreCase = true) == true)
    }

    @Test
    fun an_open_session_is_blocked_until_this_device_takes_the_lease() {
        // The lease is taken before any child exists (S18): an open session
        // never resumes straight away — the user takes the lease first.
        val reason = SessionsModel.resumeReason(LeaseViewDto.OPEN, isHeldHere = false)
        assertTrue(reason?.contains("lease", ignoreCase = true) == true)
    }

    @Test
    fun resume_is_enabled_only_when_this_device_holds_the_lease() {
        assertNull(SessionsModel.resumeReason(LeaseViewDto.HELD_HERE, isHeldHere = true))
        // A row the host shows as held-here but the app is not holding (the
        // list can be older than the hold) must still say why it is blocked.
        assertTrue(
            SessionsModel.resumeReason(LeaseViewDto.HELD_HERE, isHeldHere = false)?.contains("lease", ignoreCase = true)
            == true,
        )
    }

    // --- the resumed screen's words ----------------------------------------------

    @Test
    fun the_resumed_header_names_the_session() {
        assertEquals(
            "resumed · 9f1c2f61-8a5e-4f6a-9f6b-3c1d2e4f5a6b",
            SessionsModel.resumedHeader("9f1c2f61-8a5e-4f6a-9f6b-3c1d2e4f5a6b"),
        )
    }

    @Test
    fun the_resumed_meta_counts_the_childs_own_messages() {
        assertEquals("7 messages", SessionsModel.resumedMeta(7u))
        assertEquals("1 message", SessionsModel.resumedMeta(1u))
        assertEquals("0 messages", SessionsModel.resumedMeta(0u))
    }

    @Test
    fun transcript_roles_render_as_words_not_styling() {
        assertEquals("you", SessionsModel.roleLabel("user"))
        assertEquals("agent", SessionsModel.roleLabel("assistant"))
        // Anything else renders as itself — nothing is invented.
        assertEquals("systemMessage", SessionsModel.roleLabel("systemMessage"))
    }

    // --- relative time ----------------------------------------------------------------

    private fun session(cwd: String?, messageCount: Long?, modified: Long = 0) = SessionDto(
        fileName = "20261120_9f1c2f61.jsonl",
        sessionId = "9f1c2f61-8a5e-4f6a-9f6b-3c1d2e4f5a6b",
        cwd = cwd,
        lastModifiedEpochMs = modified.toULong(),
        messageCount = messageCount?.toULong(),
        lease = LeaseViewDto.OPEN,
    )

    @Test
    fun relative_time_buckets() {
        val now = 1_763_632_800_000L
        assertEquals("just now", SessionsModel.relativeTime(now - 10_000, now))
        assertEquals("2 min ago", SessionsModel.relativeTime(now - 2 * 60_000, now))
        assertEquals("3 h ago", SessionsModel.relativeTime(now - 3 * 3_600_000, now))
        assertEquals("5 d ago", SessionsModel.relativeTime(now - 5 * 86_400_000L, now))
        // A host clock slightly ahead of ours is "just now", never negative.
        assertEquals("just now", SessionsModel.relativeTime(now + 60_000, now))
    }

    @Test
    fun row_meta_joins_time_count_and_cwd() {
        val meta = SessionsModel.rowMeta(
            session(cwd = "/srv/app", messageCount = 12),
            relative = "2 min ago",
        )
        // The time leads (committed rule): two sessions of the same project
        // share the whole cwd prefix, so a trailing timestamp is what gets
        // ellipsised away and the rows become indistinguishable.
        assertEquals("2 min ago · 12 messages · /srv/app", meta)
    }

    @Test
    fun row_meta_hides_what_the_host_did_not_give() {
        assertEquals("2 min ago", SessionsModel.rowMeta(session(cwd = null, messageCount = null), "2 min ago"))
    }

    // --- the listing state (S24-3) ------------------------------------------------

    @Test
    fun the_listing_state_says_why_it_can_be_slow() {
        val status = SessionsModel.listingStatus(500)
        assertTrue("mentions SSH: $status", status.contains("SSH"))
    }

    @Test
    fun past_two_seconds_the_listing_state_says_it_is_still_working() {
        val status = SessionsModel.listingStatus(5_000)
        assertTrue("still working: $status", status.contains("Still"))
        assertTrue("carries the elapsed seconds: $status", status.contains("5 s"))
    }

    @Test
    fun exactly_at_two_seconds_the_state_is_already_the_slow_one() {
        assertTrue(SessionsModel.listingStatus(2_000).contains("Still"))
    }

    // --- sessions directory override ------------------------------------------------------

    @Test
    fun the_override_wins_over_the_default() {
        val store = object : KeyValueStore {
            private val map = mutableMapOf(OVERRIDE_KEY_STORE_KEY to "  /mnt/WD-Black/test_tmp/gentle-mobile/t27-sessions  ")
            override fun getString(key: String): String? = map[key]
            override fun putString(key: String, value: String) { map[key] = value }
        }
        assertEquals(
            "/mnt/WD-Black/test_tmp/gentle-mobile/t27-sessions",
            SessionsConfig.sessionsDir(store, "/home/u/.gentle-shell/agent/sessions"),
        )
    }

    @Test
    fun a_blank_override_falls_back_to_the_default() {
        val store = object : KeyValueStore {
            private val map = mutableMapOf(OVERRIDE_KEY_STORE_KEY to "   ")
            override fun getString(key: String): String? = map[key]
            override fun putString(key: String, value: String) { map[key] = value }
        }
        assertEquals(
            "/home/u/.gentle-shell/agent/sessions",
            SessionsConfig.sessionsDir(store, "/home/u/.gentle-shell/agent/sessions"),
        )
    }

    private companion object {
        const val OVERRIDE_KEY_STORE_KEY = "sessions_dir_override"
    }

    // --- T50: sort sessions by name ---------------------------------------------

    @Test
    fun ordered_defaults_to_newest_first() {
        val sessions = listOf(
            repoSession("/srv/old", 1_000),
            repoSession("/srv/new", 9_000),
            repoSession("/srv/mid", 4_000),
        )
        assertEquals(
            listOf("/srv/new", "/srv/mid", "/srv/old"),
            SessionsModel.ordered(sessions).map { it.cwd },
        )
    }

    @Test
    fun ordered_by_name_sorts_the_project_names_case_insensitively() {
        val sessions = listOf(
            repoSession("/srv/Bravo", 9_000),
            repoSession("/srv/alpha", 1_000),
            repoSession("/srv/Charlie", 4_000),
        )
        assertEquals(
            listOf("/srv/alpha", "/srv/Bravo", "/srv/Charlie"),
            SessionsModel.ordered(sessions, byName = true).map { it.cwd },
        )
    }

    @Test
    fun a_session_without_a_cwd_sorts_by_its_id_when_by_name() {
        val withCwd = repoSession("/srv/app", 1_000)
        val withoutCwd = repoSession(null, 9_000)
        assertEquals(
            listOf(withoutCwd.cwd, withCwd.cwd),
            SessionsModel.ordered(listOf(withoutCwd, withCwd), byName = true).map { it.cwd },
        )
    }

    // --- S24-5: repositories from history --------------------------------------

    private fun repoSession(cwd: String?, modified: Long) = SessionDto(
        fileName = "20261120_9f1c2f61.jsonl",
        sessionId = "9f1c2f61-8a5e-4f6a-9f6b-3c1d2e4f5a6b",
        cwd = cwd,
        lastModifiedEpochMs = modified.toULong(),
        messageCount = 1uL,
        lease = LeaseViewDto.OPEN,
    )

    @Test
    fun repositories_dedupe_by_path_keeping_the_most_recent_use() {
        val repos = SessionsModel.repositories(
            listOf(
                repoSession("/srv/app", 1_000),
                repoSession("/srv/app", 5_000),
            ),
        )
        assertEquals(1, repos.size)
        assertEquals("/srv/app", repos[0].path)
        assertEquals(5_000L, repos[0].lastUsedEpochMs)
    }

    @Test
    fun repositories_are_ordered_most_recently_used_first() {
        val repos = SessionsModel.repositories(
            listOf(
                repoSession("/srv/old", 1_000),
                repoSession("/srv/new", 9_000),
                repoSession("/srv/mid", 4_000),
            ),
        )
        assertEquals(listOf("/srv/new", "/srv/mid", "/srv/old"), repos.map { it.path })
    }

    @Test
    fun the_repository_name_is_the_last_path_segment() {
        assertEquals("erp-web", SessionsModel.repoName("/mnt/D-SSD/work/projects/erp-web"))
        // A trailing slash is not part of the name.
        assertEquals("erp-web", SessionsModel.repoName("/mnt/D-SSD/work/projects/erp-web/"))
    }

    @Test
    fun sessions_without_a_usable_cwd_are_skipped() {
        val repos = SessionsModel.repositories(
            listOf(
                repoSession(null, 9_000),
                repoSession("", 9_000),
                repoSession("   ", 9_000),
                repoSession("/srv/app", 3_000),
            ),
        )
        assertEquals(listOf("/srv/app"), repos.map { it.path })
    }

    @Test
    fun repositories_are_capped_so_a_huge_history_stays_a_phone_list() {
        val many = (1..50).map { repoSession("/srv/repo-$it", it.toLong()) }
        assertEquals(30, SessionsModel.repositories(many).size)
    }

    // --- S24-5: the manual start path -------------------------------------------

    @Test
    fun the_manual_start_path_must_be_absolute() {
        assertTrue(SessionsModel.isValidStartPath("/mnt/x/repo"))
        // Surrounding whitespace is a typing accident, not a different path.
        assertTrue(SessionsModel.isValidStartPath(" /mnt/x/repo "))
        assertFalse(SessionsModel.isValidStartPath("mnt/x/repo"))
        assertFalse(SessionsModel.isValidStartPath(""))
        assertFalse(SessionsModel.isValidStartPath("   "))
        assertFalse(SessionsModel.isValidStartPath("~/repo"))
        assertFalse(SessionsModel.isValidStartPath("./repo"))
    }

    @Test
    fun a_started_session_is_labelled_new_not_resumed() {
        assertEquals("new · fresh-1", SessionsModel.startedHeader("fresh-1"))
    }
}
