package com.gentlemanprogramming.gentlemobile.ui.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The permission/state sequence of the shared dictation session, as pure
 * data (the real recognizer cannot exist in a JVM test): a fake engine
 * records every call, so the test proves the order the phone must follow —
 *
 * * the mic tap runs the system permission request **before** any
 *   recognizer is constructed: the fake engine records zero `start` calls
 *   while the gate is open;
 * * refused → the exact refusal line, the mic stays disabled, the engine
 *   never starts;
 * * permanently refused (the dialog can never come again) → the exact
 *   settings-pointer line, no request launch, the engine never starts;
 * * granted → and only then, the recognizer starts.
 */
class DictationSessionTest {

    /** A fake engine: records calls, never touches Android. */
    private class FakeEngine(
        private var granted: Boolean = false,
        private var available: Boolean = true,
    ) : DictationEngine {
        val started = mutableListOf<Int>()
        var stopped = 0
        var cancelled = 0
        var disposed = 0

        override val needsPermission: Boolean get() = !granted
        override val refusalMessage: String =
            "Microphone permission was refused — nothing was recorded."
        override val permanentlyRefusedMessage: String =
            "Microphone permission is turned off — open Settings to allow the microphone."
        override fun availabilityReason(): String? =
            if (available) null else "No speech recognizer is installed on this device."

        override fun start(listener: DictationEngine.Listener) {
            started.add(started.size + 1)
        }
        override fun stop() {
            stopped++
        }
        override fun cancel() {
            cancelled++
        }
        override fun dispose() {
            disposed++
        }

        fun grant() {
            granted = true
        }
    }

    private class Captured(val state: DictationState, val draft: String)

    private var applied: Captured? = null
    private var permissionAsked = 0
    private var settingsOpened = 0

    private fun session(
        engine: FakeEngine,
        permanentlyDenied: () -> Boolean = { false },
    ): DictationSession = DictationSession(
        controller = DictationController(clock = { 1_000L }),
        onApply = { update ->
            applied = Captured(update.state, update.draft)
        },
        engine = engine,
        requestPermission = { permissionAsked++ },
        isPermanentlyDenied = permanentlyDenied,
        openSettings = { settingsOpened++ },
    )

    // ---- not asked → asking: the gate comes first, no recognizer ----

    @Test
    fun `the first tap asks for the permission and never starts the recognizer`() {
        val engine = FakeEngine()
        session(engine).start("")
        assertEquals(1, permissionAsked)
        assertEquals(0, engine.started.size)
        assertEquals(DictationState.RequestingPermission, applied?.state)
    }

    @Test
    fun `the gate runs before any recognizer construction even with typed text`() {
        val engine = FakeEngine()
        session(engine).start("Fix the bug")
        assertEquals(1, permissionAsked)
        assertEquals(0, engine.started.size)
        assertEquals("Fix the bug", applied?.draft)
    }

    // ---- granted → listening: only now the recognizer exists ----

    @Test
    fun `a granted permission starts the recognizer exactly once`() {
        val engine = FakeEngine()
        val session = session(engine)
        session.start("")
        engine.grant()
        session.onPermissionResult(granted = true)
        assertEquals(1, engine.started.size)
        assertTrue(applied?.state is DictationState.Listening)
    }

    // ---- refused: the plain refusal line, the engine never starts ----

    @Test
    fun `a refused permission shows the exact refusal and starts nothing`() {
        val engine = FakeEngine()
        val session = session(engine)
        session.start("")
        session.onPermissionResult(granted = false)
        assertEquals(0, engine.started.size)
        assertTrue(applied?.state is DictationState.Denied)
        assertEquals(
            "Microphone permission was refused — nothing was recorded.",
            (applied?.state as DictationState.Denied).explanation,
        )
    }

    @Test
    fun `a refusal leaves the typed draft untouched`() {
        val engine = FakeEngine()
        val session = session(engine)
        session.start("Fix the bug")
        session.onPermissionResult(granted = false)
        assertEquals("Fix the bug", applied?.draft)
    }

    // ---- retry after a simple refusal: the dialog may come again ----

    @Test
    fun `tapping the mic again after a simple refusal asks again before any recognizer`() {
        val engine = FakeEngine()
        val session = session(engine)
        session.start("")
        session.onPermissionResult(granted = false)
        session.start("")
        assertEquals(2, permissionAsked)
        assertEquals(0, engine.started.size)
        assertEquals(DictationState.RequestingPermission, applied?.state)
    }

    // ---- permanently refused: the settings pointer, no dialog, no engine ----

    @Test
    fun `a permanent refusal lands on the settings pointer without launching a dialog`() {
        val engine = FakeEngine()
        var fixed = false
        val session = session(engine, permanentlyDenied = { fixed })
        session.start("")
        session.onPermissionResult(granted = false)
        fixed = true // the system has fixed the refusal: the dialog never comes again
        session.start("")
        assertEquals(1, permissionAsked) // no second launch
        assertEquals(0, engine.started.size)
        assertTrue(applied?.state is DictationState.PermanentlyDenied)
        assertEquals(
            "Microphone permission is turned off — open Settings to allow the microphone.",
            (applied?.state as DictationState.PermanentlyDenied).reason,
        )
    }

    @Test
    fun `a permanent refusal never reports a fake listening state`() {
        val engine = FakeEngine()
        val session = session(engine, permanentlyDenied = { true })
        session.start("")
        session.onPermissionResult(granted = false)
        session.start("")
        assertTrue(applied?.state !is DictationState.Listening)
    }

    // ---- the settings action stays available through the session ----

    @Test
    fun `opening the settings reaches the wired action`() {
        val engine = FakeEngine()
        session(engine).openPermissionSettings()
        assertEquals(1, settingsOpened)
    }

    // ---- unavailable: the honest reason, no gate, no recognizer ----

    @Test
    fun `no recognizer on the device shows the reason and starts nothing`() {
        val engine = FakeEngine(available = false)
        session(engine).start("")
        assertEquals(0, permissionAsked)
        assertEquals(0, engine.started.size)
        assertTrue(applied?.state is DictationState.Unavailable)
        assertEquals(
            "No speech recognizer is installed on this device.",
            (applied?.state as DictationState.Unavailable).reason,
        )
    }

    // ---- the transient result message: refused then cleared ----

    @Test
    fun `the refusal message is dismissed through the session and keeps the draft`() {
        val engine = FakeEngine()
        val session = session(engine)
        session.start("Fix the bug")
        session.onPermissionResult(granted = false)
        session.dismissMessage()
        assertEquals(DictationState.Idle, applied?.state)
        assertEquals("Fix the bug", applied?.draft)
    }

    @Test
    fun `the permanent denial is sticky — dismissal leaves the settings pointer`() {
        val engine = FakeEngine()
        val session = session(engine, permanentlyDenied = { true })
        session.start("")
        session.onPermissionResult(granted = false)
        session.start("")
        session.dismissMessage()
        assertTrue(applied?.state is DictationState.PermanentlyDenied)
    }

    // ---- lifecycle: stop, cancel, dispose still reach the engine ----

    @Test
    fun `stop and cancel reach the engine after a grant`() {
        val engine = FakeEngine()
        val session = session(engine)
        session.start("")
        engine.grant()
        session.onPermissionResult(granted = true)
        session.stop()
        session.cancel()
        assertEquals(1, engine.stopped)
        assertEquals(1, engine.cancelled)
        assertEquals(DictationState.Idle, applied?.state)
    }

    @Test
    fun `dispose releases the engine`() {
        val engine = FakeEngine()
        session(engine).dispose()
        assertEquals(1, engine.disposed)
    }
}
