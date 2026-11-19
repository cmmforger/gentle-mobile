package com.gentlemanprogramming.gentlemobile.ui.sessions

import com.gentlemanprogramming.gentlemobile.core.HostProfileDto
import com.gentlemanprogramming.gentlemobile.core.LeaseViewDto
import com.gentlemanprogramming.gentlemobile.core.SessionDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The in-memory sessions index cache (S24): re-entering Session history
 * must be instant instead of re-reading every session file over SSH. The
 * cache may only serve a listing younger than its TTL — anything older is
 * not cached data to show, it is the normal loading state — and it never
 * confuses two hosts or two directories on one host.
 */
class SessionsCacheTest {

    private val fetchedAtMs = 1_763_632_800_000L

    private fun profile(host: String = "100.96.18.28") = HostProfileDto(
        host = host,
        port = 22u,
        user = "erickfm",
        identityPath = "/dev/null",
        knownHostsPath = "/dev/null",
    )

    private fun session(id: String) = SessionDto(
        fileName = "20261120_$id.jsonl",
        sessionId = id,
        cwd = "/srv/app",
        lastModifiedEpochMs = fetchedAtMs.toULong(),
        messageCount = 2uL,
        lease = LeaseViewDto.OPEN,
    )

    private fun keyOf(profile: HostProfileDto, dir: String = ""): String =
        SessionsCache.key(profile, dir)

    // --- serving -------------------------------------------------------------

    @Test
    fun a_fresh_entry_is_served() {
        val key = keyOf(profile())
        SessionsCache.put(key, listOf(session("a")), fetchedAtMs)
        assertEquals(listOf(session("a")), SessionsCache.fresh(key, fetchedAtMs + 1_000))
    }

    @Test
    fun an_entry_is_fresh_up_to_the_ttl_and_stale_at_it() {
        val key = keyOf(profile())
        SessionsCache.put(key, listOf(session("a")), fetchedAtMs)
        // Exactly at the TTL it is already too old to show as live.
        assertEquals(
            listOf(session("a")),
            SessionsCache.fresh(key, fetchedAtMs + SessionsCache.TTL_MS - 1),
        )
        assertNull(SessionsCache.fresh(key, fetchedAtMs + SessionsCache.TTL_MS))
        assertNull(SessionsCache.fresh(key, fetchedAtMs + SessionsCache.TTL_MS + 1))
    }

    @Test
    fun an_empty_cache_serves_nothing() {
        assertNull(SessionsCache.fresh("never-put", fetchedAtMs))
    }

    @Test
    fun a_put_overwrites_the_previous_entry() {
        val key = keyOf(profile())
        SessionsCache.put(key, listOf(session("a")), fetchedAtMs)
        val newer = fetchedAtMs + 1_000
        SessionsCache.put(key, listOf(session("b")), newer)
        assertEquals(listOf(session("b")), SessionsCache.fresh(key, newer))
    }

    // --- keying --------------------------------------------------------------

    @Test
    fun two_hosts_do_not_share_an_entry() {
        val desktop = keyOf(profile("100.96.18.28"))
        val notebook = keyOf(profile("100.96.18.29"))
        SessionsCache.put(desktop, listOf(session("a")), fetchedAtMs)
        assertEquals(listOf(session("a")), SessionsCache.fresh(desktop, fetchedAtMs))
        assertNull(SessionsCache.fresh(notebook, fetchedAtMs))
    }

    @Test
    fun the_same_host_with_a_different_directory_is_a_different_index() {
        val withOverride = keyOf(profile(), "/tmp/fixture")
        val defaultDir = keyOf(profile(), "")
        SessionsCache.put(withOverride, listOf(session("a")), fetchedAtMs)
        assertEquals(listOf(session("a")), SessionsCache.fresh(withOverride, fetchedAtMs))
        assertNull(SessionsCache.fresh(defaultDir, fetchedAtMs))
    }

    // --- invalidation --------------------------------------------------------

    @Test
    fun invalidate_drops_the_entry() {
        val key = keyOf(profile())
        SessionsCache.put(key, listOf(session("a")), fetchedAtMs)
        SessionsCache.invalidate(key)
        assertNull(SessionsCache.fresh(key, fetchedAtMs + 1))
    }

    @Test
    fun different_lists_are_not_equal() {
        assertNotEquals(listOf(session("a")), listOf(session("b")))
    }
}
