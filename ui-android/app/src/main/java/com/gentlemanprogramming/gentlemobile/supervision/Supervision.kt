package com.gentlemanprogramming.gentlemobile.supervision

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Supervision of one connection, as the four words the UI must show (T8c):
 * connecting, connected, interrupted, failed — each carrying its reason where
 * one exists. The machine is the single source of truth for liveness: screens
 * read `state`, never a separate "interrupted" flag that can drift.
 *
 * Rules this machine enforces, each unit-tested:
 *
 * * a drop (watchdog, socket error, dead liveness probe) sends connected —
 *   or connecting — to **interrupted** with its reason; the last data stays
 *   on screen but may no longer claim to be live;
 * * a retry (manual or the one automatic foreground attempt) goes through
 *   **connecting** again; a refusal goes to **failed**;
 * * the foreground return retries an interrupted link **exactly once** —
 *   never a silent loop that hides a failure;
 * * data on screen is **live only in the connected state** — a stale
 *   snapshot is never rendered as if it were live.
 */
sealed interface SupervisionState {
    /** No attempt is running: the form is up, nothing to supervise. */
    data object Idle : SupervisionState

    /** An attempt is in flight — first connect, retry, or the automatic
     *  foreground attempt. The number counts attempts since the machine
     *  was created. */
    data class Connecting(val attempt: Int) : SupervisionState

    /** Live: streamed data may claim to be live. */
    data object Connected : SupervisionState

    /** The link ended after being live (or the attempt dropped while
     *  connecting): the last data stays on screen, labelled not live.
     *  [autoRetryUsed] records that the automatic foreground attempt was
     *  already spent, so a second foreground return cannot loop. */
    data class Interrupted(val reason: String, val autoRetryUsed: Boolean) : SupervisionState

    /** The attempt was refused (host key, auth, unreachable host) with its
     *  reason. Only an explicit user action starts a new attempt. */
    data class Failed(val reason: String) : SupervisionState
}

/** The word a data header must show for what is on screen. */
fun livenessLabel(state: SupervisionState): String = when (state) {
    is SupervisionState.Connected -> "live"
    else -> "not live"
}

class SupervisionMachine {
    var state: SupervisionState by mutableStateOf(SupervisionState.Idle)
        private set

    /** True only in the connected state: the one state where streamed data
     *  may be presented as live. */
    val isLive: Boolean
        get() = state is SupervisionState.Connected

    private var attempts: Int = 0

    /** Set while the current attempt is the automatic foreground one, so a
     *  failed auto attempt is recorded instead of re-arming the loop. */
    private var autoAttemptActive: Boolean = false

    /** Begin an attempt: a fresh connect, or a retry after an interruption.
     *  The attempt counter always advances, so the UI can show it. */
    fun begin() {
        attempts += 1
        autoAttemptActive = false
        state = SupervisionState.Connecting(attempts)
    }

    /** The attempt reached a live connection. */
    fun onLive() {
        autoAttemptActive = false
        state = SupervisionState.Connected
    }

    /** The link dropped: watchdog, socket error, or a failed liveness probe.
     *  From connected or connecting this records the interruption with its
     *  reason; a failed automatic attempt is marked so the foreground return
     *  does not retry forever. Already-interrupted and other states keep
     *  their first honest reason. */
    fun onDropped(reason: String) {
        val wasAuto = autoAttemptActive
        autoAttemptActive = false
        val previousAutoUsed = (state as? SupervisionState.Interrupted)?.autoRetryUsed ?: false
        when (state) {
            is SupervisionState.Connected, is SupervisionState.Connecting ->
                state = SupervisionState.Interrupted(
                    reason = reason,
                    autoRetryUsed = wasAuto || previousAutoUsed,
                )
            else -> {}
        }
    }

    /** The attempt was refused with a typed reason: no automatic retry may
     *  paper over it, only an explicit user action restarts. */
    fun onRefused(reason: String) {
        autoAttemptActive = false
        if (state is SupervisionState.Connecting) {
            state = SupervisionState.Failed(reason)
        }
    }

    /** The app returned to the foreground. When (and only when) the link is
     *  interrupted and its one automatic attempt has not been spent, start
     *  exactly one: true means an attempt is now in flight. */
    fun onForeground(): Boolean {
        val current = state
        return if (current is SupervisionState.Interrupted && !current.autoRetryUsed) {
            attempts += 1
            autoAttemptActive = true
            state = SupervisionState.Connecting(attempts)
            true
        } else {
            false
        }
    }

    /** The user gave up on this connection: nothing is supervised. */
    fun reset() {
        attempts = 0
        autoAttemptActive = false
        state = SupervisionState.Idle
    }
}

/**
 * Counts registered pollers so teardown is provable (T8c): the app logs this
 * count at every change, and the unit test asserts a stopped poller releases
 * its slot. Each poller registers once for its whole life — rescheduling
 * keeps the slot; cancelling, going back, or replacing the connection closes
 * it exactly once.
 */
class PollTracker {
    private val active = AtomicInteger(0)

    val count: Int
        get() = active.get()

    /** Register one poller slot. The returned guard must be closed exactly
     *  when the poller stops rescheduling. */
    fun register(): AutoCloseable {
        active.incrementAndGet()
        var closed = false
        return AutoCloseable {
            if (!closed) {
                closed = true
                active.decrementAndGet()
            }
        }
    }
}
