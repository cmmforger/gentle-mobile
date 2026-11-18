package com.gentlemanprogramming.gentlemobile.ui.sessions

import com.gentlemanprogramming.gentlemobile.core.LeaseViewDto
import com.gentlemanprogramming.gentlemobile.core.SessionDto
import com.gentlemanprogramming.gentlemobile.ui.theme.KeyValueStore
import org.junit.Assert.assertEquals
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
            private val map = mutableMapOf(OVERRIDE_KEY_STORE_KEY to "  /mnt/WD-Black/test_tmp/gentle-companion/t27-sessions  ")
            override fun getString(key: String): String? = map[key]
            override fun putString(key: String, value: String) { map[key] = value }
        }
        assertEquals(
            "/mnt/WD-Black/test_tmp/gentle-companion/t27-sessions",
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
}
