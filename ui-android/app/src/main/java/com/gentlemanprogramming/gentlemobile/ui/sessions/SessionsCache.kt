package com.gentlemanprogramming.gentlemobile.ui.sessions

import com.gentlemanprogramming.gentlemobile.core.HostProfileDto
import com.gentlemanprogramming.gentlemobile.core.SessionDto

/**
 * The in-memory sessions index cache (S24): re-entering Session history
 * shows the last listing instead of re-reading every session file over
 * SSH. The honesty rule is absolute — cached data is never shown as live:
 *
 * * a cache entry is served only while it is **younger than** [TTL_MS];
 *   at or past the TTL [fresh] returns `null` and the screen shows its
 *   normal loading state (a new fetch, which then re-arms the cache);
 * * the explicit Refresh action and every lease mutation (take/release,
 *   which change lease state on the host) bypass the cache entirely.
 *
 * The cache is keyed by host **and** the listed directory: the same host
 * can be listed at a different directory (the dev sessions-dir override),
 * and those are different indexes — one must never be served for the
 * other. Everything lives in the app process's memory only; nothing is
 * persisted, so a killed app starts with a cold (honest) cache.
 */
object SessionsCache {

    /** How long a listing may be re-shown without a fresh fetch. */
    const val TTL_MS = 60_000L

    private data class Entry(val sessions: List<SessionDto>, val fetchedAtMs: Long)

    private val entries = HashMap<String, Entry>()

    /** The cache key for one host's listing of one directory. */
    fun key(profile: HostProfileDto, sessionsDir: String): String =
        "${profile.user}@${profile.host}:${profile.port}#${sessionsDir}"

    /** The cached listing, only when it is younger than [TTL_MS]. Anything
     *  older returns `null` — the caller shows its loading state instead of
     *  stale data. */
    @Synchronized
    fun fresh(key: String, nowMs: Long): List<SessionDto>? {
        val entry = entries[key] ?: return null
        return if (nowMs - entry.fetchedAtMs < TTL_MS) entry.sessions else null
    }

    /** Store (or overwrite) one host's listing with the moment it was
     *  fetched. */
    @Synchronized
    fun put(key: String, sessions: List<SessionDto>, fetchedAtMs: Long) {
        entries[key] = Entry(sessions, fetchedAtMs)
    }

    /** Drop one entry (used after a lease mutation so the next listing is
     *  fetched, not served). */
    @Synchronized
    fun invalidate(key: String) {
        entries.remove(key)
    }
}
