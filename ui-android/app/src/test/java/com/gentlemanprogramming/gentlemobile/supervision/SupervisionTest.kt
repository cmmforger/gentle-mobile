package com.gentlemanprogramming.gentlemobile.supervision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The supervision brain, unit-tested as pure transitions (T8c): connected →
 * interrupted on a watchdog drop, interrupted → connecting on retry, failed
 * on refusal — plus the "no stale data as live" rule and the poller counter
 * that proves teardown. T41 adds the background rules: a gap while the app
 * is backgrounded never produces the interrupted state, and a failed versus
 * successful probe decides the state on resume.
 */
class SupervisionTest {

    @Test
    fun `an attempt starts connecting and goes live`() {
        val machine = SupervisionMachine()
        machine.begin()
        assertEquals(SupervisionState.Connecting(attempt = 1), machine.state)
        machine.onLive()
        assertEquals(SupervisionState.Connected, machine.state)
        assertTrue("connected means live", machine.isLive)
    }

    @Test
    fun `a watchdog drop sends connected to interrupted with its reason`() {
        val machine = SupervisionMachine()
        machine.begin()
        machine.onLive()
        machine.onDropped("keepalive gave up")
        assertEquals(
            SupervisionState.Interrupted(reason = "keepalive gave up", autoRetryUsed = false),
            machine.state,
        )
        assertFalse("interrupted is not live", machine.isLive)
    }

    @Test
    fun `retry sends interrupted back to connecting`() {
        val machine = SupervisionMachine()
        machine.begin()
        machine.onLive()
        machine.onDropped("the host closed the connection")
        machine.begin()
        assertEquals(SupervisionState.Connecting(attempt = 2), machine.state)
    }

    @Test
    fun `a refusal fails the attempt with its reason`() {
        val machine = SupervisionMachine()
        machine.begin()
        machine.onRefused("host key mismatch: pinned AA, presented BB")
        assertEquals(
            SupervisionState.Failed(reason = "host key mismatch: pinned AA, presented BB"),
            machine.state,
        )
        assertFalse(machine.isLive)
    }

    @Test
    fun `a drop while connecting is an interruption, not a fake connected`() {
        val machine = SupervisionMachine()
        machine.begin()
        machine.onDropped("the ssh endpoint never accepted a connection")
        assertTrue(machine.state is SupervisionState.Interrupted)
    }

    @Test
    fun `foreground return retries an interrupted link exactly once`() {
        val machine = SupervisionMachine()
        machine.begin()
        machine.onLive()
        machine.onDropped("link was down while asleep")

        // First foreground: exactly one automatic attempt.
        assertEquals(ForegroundAction.Retry, machine.onForegrounded())
        assertTrue(machine.state is SupervisionState.Connecting)

        // The automatic attempt fails: back to interrupted, auto used up.
        machine.onDropped("the host did not answer the liveness probe")
        val interrupted = machine.state as SupervisionState.Interrupted
        assertTrue("the failed auto attempt must be recorded", interrupted.autoRetryUsed)

        // Second foreground: never a silent loop — no more automatic attempts.
        assertEquals(ForegroundAction.Nothing, machine.onForegrounded())
        assertTrue(machine.state is SupervisionState.Interrupted)
    }

    @Test
    fun `foreground return does nothing while connected`() {
        val machine = SupervisionMachine()
        machine.begin()
        machine.onLive()
        assertEquals(ForegroundAction.Nothing, machine.onForegrounded())
        assertEquals(SupervisionState.Connected, machine.state)
    }

    // T41 rule 1: a background gap is not evidence of failure.
    @Test
    fun `a background gap never produces the interrupted state`() {
        val machine = SupervisionMachine()
        machine.begin()
        machine.onLive()
        machine.onBackgrounded()
        machine.onDropped("keepalive gave up while the app was away")
        assertEquals(
            "a background gap must not latch the interrupted state",
            SupervisionState.Connected,
            machine.state,
        )
        assertTrue(machine.isLive)
    }

    // T41 rule 2: a failed-vs-successful probe decides the state on resume.
    @Test
    fun `a failed probe surfaces the interruption on resume`() {
        val machine = SupervisionMachine()
        machine.begin()
        machine.onLive()
        machine.onBackgrounded()
        machine.onDropped("background gap")

        assertEquals(ForegroundAction.Probe, machine.onForegrounded())
        assertEquals(
            "the background gap stays latent until the probe fails",
            SupervisionState.Connected,
            machine.state,
        )

        machine.onDropped("the host did not answer the probe")
        assertEquals(
            SupervisionState.Interrupted(reason = "the host did not answer the probe", autoRetryUsed = false),
            machine.state,
        )
    }

    @Test
    fun `a successful probe clears the stale background drop and stays connected`() {
        val machine = SupervisionMachine()
        machine.begin()
        machine.onLive()
        machine.onBackgrounded()
        machine.onDropped("background gap")

        assertEquals(ForegroundAction.Probe, machine.onForegrounded())
        machine.onProbeAlive()
        assertEquals(SupervisionState.Connected, machine.state)
        assertTrue(machine.isLive)

        // The stale drop is gone: the next foreground does nothing, no retry.
        assertEquals(ForegroundAction.Nothing, machine.onForegrounded())
    }

    // T41 rule 3: a resume with a live link leaves the session selection
    // untouched — no reconnect is requested, so the caller never redirects
    // or re-claims the pane.
    @Test
    fun `a live-link resume leaves the session selection untouched`() {
        val machine = SupervisionMachine()
        machine.begin()
        machine.onLive()
        machine.onBackgrounded()
        machine.onDropped("background gap")

        // Never a Retry: the caller must not redirect or re-claim the pane.
        assertEquals(ForegroundAction.Probe, machine.onForegrounded())
        machine.onProbeAlive()

        // The same live connection stands: no attempt bump, no state change.
        assertEquals(SupervisionState.Connected, machine.state)
        assertTrue(machine.isLive)
    }

    @Test
    fun `data on screen is never live outside the connected state`() {
        // The rule: only a Connected connection may claim "live". A snapshot
        // or surface shown while interrupted (or at all outside Connected) is
        // the last known data and must be labelled not live — data presence
        // never flips the word.
        val machine = SupervisionMachine()
        machine.begin()
        machine.onLive()
        assertEquals("live", livenessLabel(machine.state))

        machine.onDropped("the host socket closed")
        // A stale snapshot IS on screen here — it must still read not live.
        assertEquals("not live", livenessLabel(machine.state))

        machine.begin()
        assertEquals("not live", livenessLabel(machine.state))
        machine.onRefused("unknown host key")
        assertEquals("not live", livenessLabel(machine.state))
    }

    @Test
    fun `a stopped poller releases its slot and the count returns to zero`() {
        val tracker = PollTracker()
        val first = tracker.register()
        val second = tracker.register()
        assertEquals(2, tracker.count)
        second.close()
        assertEquals(1, tracker.count)
        first.close()
        assertEquals("every registered poller must stop before teardown is provable", 0, tracker.count)
    }
}
