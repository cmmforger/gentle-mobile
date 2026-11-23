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
 *   snapshot is never rendered as if it were live;
 * * (T41) **a background gap is not evidence of failure**: a drop observed
 *   while the app is backgrounded is recorded, never painted or latched.
 *   The return probes the live connection first, and only a failed probe
 *   surfaces the interrupted state; a successful probe clears the stale
 *   drop and stays connected without a reconnect.
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

/** What the caller must do after the app returned to the foreground (T41). */
enum class ForegroundAction {
    /** Nothing to do: the link is as it was, or already handled. */
    Nothing,

    /** The link was interrupted and its one automatic attempt is available:
     *  start exactly one reconnect. */
    Retry,

    /** A drop was observed while the app was backgrounded but the link is
     *  still held connected: probe the live connection first. Only a failed
     *  probe may surface the interrupted state. */
    Probe,
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

    /** T41: the app is in the background. A drop there is a gap, not
     *  evidence that the link died. */
    private var backgrounded: Boolean = false

    /** A drop observed while backgrounded, not yet resolved by a probe. */
    private var dropObservedWhileBackgrounded: Boolean = false

    /** Begin an attempt: a fresh connect, or a retry after an interruption.
     *  The attempt counter always advances, so the UI can show it. */
    fun begin() {
        attempts += 1
        autoAttemptActive = false
        dropObservedWhileBackgrounded = false
        state = SupervisionState.Connecting(attempts)
    }

    /** The attempt reached a live connection. */
    fun onLive() {
        autoAttemptActive = false
        dropObservedWhileBackgrounded = false
        state = SupervisionState.Connected
    }

    /** The link dropped: watchdog, socket error, or a failed liveness probe.
     *  From connected or connecting this records the interruption with its
     *  reason; a failed automatic attempt is marked so the foreground return
     *  does not retry forever. Already-interrupted and other states keep
     *  their first honest reason.
     *
     *  T41: while the app is backgrounded a drop is a gap, not a verdict —
     *  it is recorded for the return's probe but never latched here. */
    fun onDropped(reason: String) {
        if (backgrounded && state is SupervisionState.Connected) {
            dropObservedWhileBackgrounded = true
            return
        }
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
        dropObservedWhileBackgrounded = false
        if (state is SupervisionState.Connecting) {
            state = SupervisionState.Failed(reason)
        }
    }

    /** The app went to the background (T41): from here on, drops are gaps. */
    fun onBackgrounded() {
        backgrounded = true
    }

    /** The app returned to the foreground. The caller must act on the
     *  returned action: [ForegroundAction.Retry] starts the one automatic
     *  reconnect, [ForegroundAction.Probe] probes the live connection before
     *  painting any state, [ForegroundAction.Nothing] leaves everything as it
     *  is. */
    fun onForegrounded(): ForegroundAction {
        backgrounded = false
        val hadBackgroundDrop = dropObservedWhileBackgrounded
        dropObservedWhileBackgrounded = false
        val current = state
        return when {
            current is SupervisionState.Interrupted && !current.autoRetryUsed -> {
                attempts += 1
                autoAttemptActive = true
                state = SupervisionState.Connecting(attempts)
                ForegroundAction.Retry
            }
            hadBackgroundDrop && current is SupervisionState.Connected ->
                ForegroundAction.Probe
            else -> ForegroundAction.Nothing
        }
    }

    /** A successful probe (T41): the link is alive. Clear the stale
     *  background drop and stay connected — no reconnect, so the session
     *  view is never redirected or re-claimed by the check. */
    fun onProbeAlive() {
        dropObservedWhileBackgrounded = false
    }

    /** The user gave up on this connection: nothing is supervised. */
    fun reset() {
        attempts = 0
        autoAttemptActive = false
        backgrounded = false
        dropObservedWhileBackgrounded = false
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
