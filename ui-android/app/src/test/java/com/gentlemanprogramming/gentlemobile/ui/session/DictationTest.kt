package com.gentlemanprogramming.gentlemobile.ui.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T14 (S8): the dictation state machine and the transcript merge rule,
 * as pure data — the recognizer cannot exist in a JVM test, so every rule
 * here is expressed as the transitions and text the UI must show.
 *
 * The merge rule (the contract the composer renders):
 * * existing typed text is always preserved;
 * * each partial replaces the previous partial — never accumulates;
 * * the final result replaces the partials;
 * * exactly one separating space between typed text and dictated text;
 * * a blank partial never appends anything.
 */
class DictationTest {

    private var now = 1_000L
    private fun controller() = DictationController(clock = { now })

    // ---- begin: idle → (permission?) → listening ----

    @Test
    fun `begin without a permission gate goes straight to listening`() {
        val machine = controller()
        val update = machine.begin("Fix the bug", needsPermission = false)
        assertTrue(update.state is DictationState.Listening)
        assertEquals("Fix the bug", update.draft)
    }

    @Test
    fun `begin with a permission gate requests it first`() {
        val machine = controller()
        val update = machine.begin("", needsPermission = true)
        assertEquals(DictationState.RequestingPermission, update.state)
        assertEquals("", update.draft)
    }

    @Test
    fun `granted permission starts listening`() {
        val machine = controller()
        machine.begin("", needsPermission = true)
        val update = machine.onPermissionGranted()
        assertTrue(update.state is DictationState.Listening)
    }

    @Test
    fun `denied permission lands on denied carrying the refusal, never the request explanation`() {
        val machine = controller()
        machine.begin("", needsPermission = true)
        val update = machine.onPermissionDenied(
            refusal = "Microphone permission was refused — nothing was recorded.",
        )
        assertTrue(update.state is DictationState.Denied)
        assertEquals(
            "Microphone permission was refused — nothing was recorded.",
            (update.state as DictationState.Denied).explanation,
        )
        // The reason is exactly what the line under the composer must show.
        assertEquals(
            "Microphone permission was refused — nothing was recorded.",
            update.state.disabledReason(),
        )
    }

    @Test
    fun `a permanent denial lands on permanently denied pointing at settings`() {
        val machine = controller()
        machine.begin("", needsPermission = true)
        val update = machine.onPermissionPermanentlyDenied(
            refusal = "Microphone permission is turned off — open Settings to allow the microphone.",
        )
        assertTrue(update.state is DictationState.PermanentlyDenied)
        assertEquals(
            "Microphone permission is turned off — open Settings to allow the microphone.",
            (update.state as DictationState.PermanentlyDenied).reason,
        )
        assertEquals(
            "Microphone permission is turned off — open Settings to allow the microphone.",
            update.state.disabledReason(),
        )
    }

    @Test
    fun `permanently denied keeps the draft and never listens`() {
        val machine = controller()
        machine.begin("Fix the bug", needsPermission = true)
        val update = machine.onPermissionPermanentlyDenied(refusal = "off in Settings.")
        assertEquals("Fix the bug", update.draft)
        assertTrue(update.state !is DictationState.Listening)
    }

    @Test
    fun `unavailable carries the exact reason on the line`() {
        val machine = controller()
        val update = machine.onUnavailable("No speech recognizer is installed on this device.")
        assertTrue(update.state is DictationState.Unavailable)
        assertEquals(
            "No speech recognizer is installed on this device.",
            update.state.disabledReason(),
        )
    }

    @Test
    fun `listening exposes its start time for the elapsed clock`() {
        val machine = controller()
        val update = machine.begin("", needsPermission = false)
        assertEquals(1_000L, (update.state as DictationState.Listening).startedAtMillis)
    }

    // ---- the merge rule ----

    @Test
    fun `empty draft takes the transcript as-is`() {
        val machine = controller()
        machine.begin("", needsPermission = false)
        val update = machine.onFinal("hello world")
        assertEquals("hello world", update.draft)
    }

    @Test
    fun `existing typed text is preserved with one separating space`() {
        val machine = controller()
        machine.begin("Fix the bug", needsPermission = false)
        val update = machine.onFinal("and compare it with the retry")
        assertEquals("Fix the bug and compare it with the retry", update.draft)
    }

    @Test
    fun `trailing whitespace on the typed text collapses to one space`() {
        val machine = controller()
        machine.begin("Fix the bug  ", needsPermission = false)
        val update = machine.onFinal("and rerun the tests")
        assertEquals("Fix the bug and rerun the tests", update.draft)
    }

    @Test
    fun `partials replace each other without duplicating`() {
        val machine = controller()
        machine.begin("Fix the bug", needsPermission = false)
        assertEquals("Fix the bug hel", machine.onPartial("hel").draft)
        assertEquals("Fix the bug hello", machine.onPartial("hello").draft)
        assertEquals("Fix the bug hello wor", machine.onPartial("hello wor").draft)
    }

    @Test
    fun `final replaces the partial`() {
        val machine = controller()
        machine.begin("Fix the bug", needsPermission = false)
        machine.onPartial("hello wor")
        val update = machine.onFinal("hello world")
        assertEquals("Fix the bug hello world", update.draft)
        assertTrue(update.state is DictationState.Finished)
    }

    @Test
    fun `a blank partial never appends anything`() {
        val machine = controller()
        machine.begin("Fix the bug", needsPermission = false)
        assertEquals("Fix the bug", machine.onPartial("").draft)
        assertEquals("Fix the bug", machine.onPartial("   ").draft)
    }

    // ---- stop and cancel ----

    @Test
    fun `stop commits the last partial`() {
        val machine = controller()
        machine.begin("Fix the bug", needsPermission = false)
        machine.onPartial("hello")
        val update = machine.stop()
        assertEquals("Fix the bug hello", update.draft)
        assertTrue(update.state is DictationState.Finished)
    }

    @Test
    fun `stop with nothing heard inserts nothing`() {
        val machine = controller()
        machine.begin("Fix the bug", needsPermission = false)
        val update = machine.stop()
        assertEquals("Fix the bug", update.draft)
        assertTrue(update.state is DictationState.Finished)
    }

    @Test
    fun `a final arriving after stop still lands in the draft`() {
        val machine = controller()
        machine.begin("Fix the bug", needsPermission = false)
        machine.onPartial("hello wor")
        machine.stop()
        val update = machine.onFinal("hello world")
        assertEquals("Fix the bug hello world", update.draft)
    }

    @Test
    fun `cancel discards everything dictated`() {
        val machine = controller()
        machine.begin("Fix the bug", needsPermission = false)
        machine.onPartial("hello")
        val update = machine.cancel()
        assertEquals("Fix the bug", update.draft)
        assertEquals(DictationState.Idle, update.state)
    }

    // ---- errors: never a half-inserted transcript without saying so ----

    @Test
    fun `error removes the uncommitted partial and carries the message`() {
        val machine = controller()
        machine.begin("Fix the bug", needsPermission = false)
        machine.onPartial("hello wor")
        val update = machine.onError("No speech heard.")
        assertEquals("Fix the bug", update.draft)
        assertTrue(update.state is DictationState.Error)
        assertEquals("No speech heard.", update.state.disabledReason())
    }

    @Test
    fun `error after the final committed keeps the committed text`() {
        val machine = controller()
        machine.begin("Fix the bug", needsPermission = false)
        machine.onFinal("hello world")
        val update = machine.onError("No speech heard.")
        assertEquals("Fix the bug hello world", update.draft)
        assertTrue(update.state is DictationState.Error)
    }

    // ---- the transient result message (S24-9 amendment) ----

    @Test
    fun `a refusal message expires to idle and keeps the field text`() {
        val machine = controller()
        machine.begin("Fix the bug", needsPermission = true)
        machine.onPermissionDenied(refusal = "Microphone permission was refused — nothing was recorded.")
        val update = machine.onMessageExpired()
        assertEquals(DictationState.Idle, update.state)
        assertEquals("Fix the bug", update.draft)
    }

    @Test
    fun `an error message expires to idle and keeps the committed text`() {
        val machine = controller()
        machine.begin("Fix the bug", needsPermission = false)
        machine.onFinal("hello world")
        machine.onError("No speech heard.")
        val update = machine.onMessageExpired()
        assertEquals(DictationState.Idle, update.state)
        assertEquals("Fix the bug hello world", update.draft)
    }

    @Test
    fun `an unavailable message expires to idle`() {
        val machine = controller()
        machine.onUnavailable("No speech recognizer is installed on this device.")
        val update = machine.onMessageExpired()
        assertEquals(DictationState.Idle, update.state)
    }

    @Test
    fun `a permanent denial never expires — the user must act on it`() {
        val machine = controller()
        machine.begin("", needsPermission = true)
        machine.onPermissionPermanentlyDenied(
            refusal = "Microphone permission is turned off — open Settings to allow the microphone.",
        )
        val update = machine.onMessageExpired()
        assertTrue(update.state is DictationState.PermanentlyDenied)
    }

    @Test
    fun `an expiry is a no-op while listening or idle`() {
        val machine = controller()
        machine.begin("", needsPermission = false)
        val listening = machine.onMessageExpired()
        assertTrue(listening.state is DictationState.Listening)
        machine.cancel()
        assertEquals(DictationState.Idle, machine.onMessageExpired().state)
    }

    @Test
    fun `after an expiry a new dictation still builds on the kept text`() {
        val machine = controller()
        machine.begin("Fix the bug", needsPermission = false)
        machine.onFinal("hello")
        machine.onError("No speech heard.")
        machine.onMessageExpired()
        val update = machine.begin("Fix the bug hello", needsPermission = false)
        assertTrue(update.state is DictationState.Listening)
        assertEquals("Fix the bug hello world", machine.onFinal("world").draft)
    }

    // ---- restart and the disabled reason ----

    @Test
    fun `a new dictation after finishing builds on the current draft`() {
        val machine = controller()
        machine.begin("", needsPermission = false)
        machine.onFinal("hello")
        val update = machine.begin("hello", needsPermission = false)
        assertTrue(update.state is DictationState.Listening)
        assertEquals("hello", update.draft)
        assertEquals("hello world", machine.onFinal("world").draft)
    }

    @Test
    fun `idle and active states never show a disabled reason`() {
        assertNull(DictationState.Idle.disabledReason())
        assertNull(DictationState.Finished.disabledReason())
        assertNull(DictationState.Listening(null, 0L).disabledReason())
        assertNull(DictationState.RequestingPermission.disabledReason())
    }

    @Test
    fun `denied and permanently denied states do show a disabled reason`() {
        assertEquals(
            "refused",
            DictationState.Denied("refused").disabledReason(),
        )
        assertEquals(
            "off in Settings",
            DictationState.PermanentlyDenied("off in Settings").disabledReason(),
        )
    }

    // ---- the elapsed clock on the listening line ----

    @Test
    fun `elapsed time formats as m ss`() {
        assertEquals("0:07", formatDictationElapsed(startedAtMillis = 1_000L, nowMillis = 8_000L))
        assertEquals("1:05", formatDictationElapsed(startedAtMillis = 0L, nowMillis = 65_000L))
    }

    @Test
    fun `a clock that has not moved shows zero elapsed`() {
        assertEquals("0:00", formatDictationElapsed(startedAtMillis = 5_000L, nowMillis = 5_000L))
    }

    @Test
    fun `elapsed never goes backwards if a late tick predates the start`() {
        assertEquals("0:00", formatDictationElapsed(startedAtMillis = 9_000L, nowMillis = 1_000L))
    }
}
