package com.gentlemanprogramming.gentlemobile.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

/**
 * T14 (S8): one shared dictation session — the engine, the permission gate
 * and the state machine wired together, used identically by the live
 * session screen and the fixture so there is exactly one flow to verify.
 *
 * The sequence: tap → the machine goes to requesting-permission (only when
 * RECORD_AUDIO is not granted) → the system dialog → granted starts the
 * recognizer and listening begins; refused lands on the disabled state
 * carrying the same explanation. Partials fill the field as they arrive,
 * the final replaces them, Stop commits and Cancel discards.
 */
class DictationSession(
    private val controller: DictationController,
    private val onApply: (DictationUpdate) -> Unit,
    private val engine: AndroidDictationEngine,
    private val requestPermission: () -> Unit,
) {
    val listener = object : AndroidDictationEngine.Listener {
        override fun onPartial(text: String) = onApply(controller.onPartial(text))
        override fun onFinal(text: String) = onApply(controller.onFinal(text))
        override fun onError(message: String) = onApply(controller.onError(message))
    }

    /** The microphone was tapped: availability gate, then the permission gate. */
    fun start(currentDraft: String) {
        val reason = engine.availabilityReason()
        if (reason != null) {
            onApply(controller.onUnavailable(reason))
            return
        }
        val update = controller.begin(
            currentDraft,
            needsPermission = engine.needsPermission,
            explanation = engine.permissionExplanation,
        )
        onApply(update)
        if (update.state is DictationState.Listening) {
            engine.start(listener)
        } else {
            requestPermission()
        }
    }

    /** The system dialog answered; granted starts the recognizer. */
    fun onPermissionResult(granted: Boolean) {
        val update = if (granted) {
            controller.onPermissionGranted()
        } else {
            controller.onPermissionDenied()
        }
        onApply(update)
        if (granted) engine.start(listener)
    }

    /** Stop: the last partial commits now; the final result still lands. */
    fun stop() {
        engine.stop()
        onApply(controller.stop())
    }

    /** Cancel: the request is discarded and everything dictated is undone. */
    fun cancel() {
        engine.cancel()
        onApply(controller.cancel())
    }

    /** The screen went away: release the recognizer, keep no audio. */
    fun dispose() = engine.dispose()
}

/** The one composable that owns the session's lifecycle (and nothing else). */
@Composable
fun rememberDictationSession(
    controller: DictationController,
    onApply: (DictationUpdate) -> Unit,
): DictationSession {
    val context = LocalContext.current
    val engine = remember { AndroidDictationEngine(context) }
    // The launcher needs the session; the session needs the launcher, so a
    // holder breaks the cycle without hiding either side.
    val holder = remember { mutableListOf<DictationSession>() }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> holder.firstOrNull()?.onPermissionResult(granted) }
    val session = remember(controller, engine) {
        DictationSession(
            controller = controller,
            onApply = onApply,
            engine = engine,
            requestPermission = {
                permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            },
        ).also { holder.clear(); holder.add(it) }
    }
    DisposableEffect(session) {
        onDispose { session.dispose() }
    }
    return session
}
