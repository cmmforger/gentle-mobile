package com.gentlemanprogramming.gentlemobile.ui.sessions

import com.gentlemanprogramming.gentlemobile.core.LeaseViewDto
import com.gentlemanprogramming.gentlemobile.core.SessionDto
import com.gentlemanprogramming.gentlemobile.ui.theme.KeyValueStore
import java.security.MessageDigest

/**
 * The pure rendering contract for the Sessions screen (T17a, S15/S18).
 * The lease *semantics* — liveness threshold, reclaim, JSON format — live
 * in the Rust core (`core/src/sessions.rs`); this file only turns the
 * core's lease view and session summary into the words and rows the boards
 * prescribe: state as a word, cwd plus a relative last-used time as the
 * mono meta, and a resume action that stays honestly disabled until the
 * RPC lane exists (T17b).
 */
object SessionsModel {

    /** The device id the advisory lease carries: pid-less, derived from the
     *  identity key so reinstalling the app keeps the same claim holder.
     *  The id hashes the key *blob* — the second token of the OpenSSH line
     *  — so the key comment never changes the identity. */
    fun deviceLeaseId(publicKeyLine: String): String {
        val blob = publicKeyLine.split(" ").getOrNull(1) ?: publicKeyLine
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(blob.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "dm-" + hex.take(16)
    }

    /** The row's state word, per the boards: a plain word in secondary
     *  text, never a chip. A reclaimed session keeps the visible word so a
     *  takeover is never silent. */
    fun stateWord(view: LeaseViewDto, reclaimed: Boolean = false): String = when (view) {
        LeaseViewDto.OPEN -> "open"
        LeaseViewDto.HELD_HERE -> if (reclaimed) "stale lease reclaimed" else "held by this device"
        LeaseViewDto.IN_USE_ELSEWHERE -> "in use elsewhere"
        LeaseViewDto.STALE -> "stale lease"
    }

    /** Why Resume is disabled for this row, or `null` when it may run. The
     *  gate is the lease (S18): the lease is taken **before** any RPC child
     *  exists, so only a session this device holds can resume. An open
     *  session asks for the Take action first; a stale one says reclaim
     *  first; a foreign one is never offered. */
    fun resumeReason(view: LeaseViewDto, isHeldHere: Boolean): String? {
        if (isHeldHere) {
            return null
        }
        return when (view) {
            LeaseViewDto.OPEN, LeaseViewDto.HELD_HERE -> RESUME_TAKE_LEASE_FIRST
            LeaseViewDto.IN_USE_ELSEWHERE ->
                "In use elsewhere — another device holds a live lease, so this session must not be opened."
            LeaseViewDto.STALE ->
                "The lease on the host is stale (older than 90 s). Take it to reclaim, then resume."
        }
    }

    /** Why Resume is disabled on a session the app does not hold yet. */
    const val RESUME_TAKE_LEASE_FIRST =
        "Take the lease first — resuming opens this session for this device, and the guard is ours (Pi has no lock)."

    /** Relative last-used time for the row meta; a host clock slightly
     *  ahead of the phone's is "just now", never a negative duration. */
    fun relativeTime(epochMs: Long, nowMs: Long): String {
        val delta = (nowMs - epochMs).coerceAtLeast(0)
        val minutes = delta / 60_000
        val hours = delta / 3_600_000
        val days = delta / 86_400_000
        return when {
            minutes < 1 -> "just now"
            hours < 1 -> "$minutes min ago"
            days < 1 -> "$hours h ago"
            else -> "$days d ago"
        }
    }

    /** The row's mono meta line: cwd · message count · relative time, each
     *  part only when the host actually gave it. */
    fun rowMeta(session: SessionDto, relative: String): String =
        // The time leads: two sessions of the same project share the whole cwd
        // prefix, so a trailing timestamp is what gets ellipsised away and the
        // rows become indistinguishable.
        listOfNotNull(
            relative,
            session.messageCount?.let { "${it.toLong()} messages" },
            session.cwd,
        ).joinToString(" · ")

    /** Newest first, and capped: a host can hold hundreds of sessions and a
     *  phone list is not a file browser. */
    fun ordered(sessions: List<SessionDto>, cap: Int = 60): List<SessionDto> =
        sessions.sortedByDescending { it.lastModifiedEpochMs }.take(cap)

    /** The visible note after reclaiming a stale lease (S18: never
     *  silently). */
    const val RECLAIM_NOTE =
        "Stale lease reclaimed — the previous holder's heartbeat was older than 90 s."

    /** The note when a lease take was refused because another device is
     *  live on it. */
    const val LEASE_LIVE_ELSEWHERE_NOTE =
        "Another device holds a live lease on this session — it was not taken."

    /** The resumed screen's header: `resumed · <sessionId>`, exactly the
     *  id the child itself reported at handshake (T5). */
    fun resumedHeader(sessionId: String): String = "resumed · $sessionId"

    /** The resumed screen's meta: the child's own message count. */
    fun resumedMeta(messageCount: ULong): String =
        if (messageCount == 1UL) "1 message" else "$messageCount messages"

    /** Transcript role words: user and assistant render as the words the
     *  boards use; anything else renders as itself — nothing invented. */
    fun roleLabel(role: String): String = when (role) {
        "user" -> "you"
        "assistant" -> "agent"
        else -> role
    }

    /** The visible note when a resumed transcript is empty. */
    const val EMPTY_TRANSCRIPT_NOTE = "This session has no messages yet."
}

/** Where the Sessions screen lists from. The default is the core's
 *  preference (Gentle Shell agent home); a non-blank persisted override
 *  wins — that is how a test build points the app at a fixture directory
 *  instead of the real sessions. */
object SessionsConfig {

    const val OVERRIDE_KEY = "sessions_dir_override"

    fun sessionsDir(store: KeyValueStore, defaultDir: String): String =
        store.getString(OVERRIDE_KEY)?.trim()?.takeIf { it.isNotEmpty() } ?: defaultDir
}
