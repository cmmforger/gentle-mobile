package com.gentlemanprogramming.gentlemobile.ui.session

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * T14 (S8): one shared dictation session — the engine, the permission gate
 * and the state machine wired together, used identically by the live
 * session screen and the fixture so there is exactly one flow to verify.
 *
 * The sequence (S24-9): tap → availability gate → the system permission
 * request (a real `RequestPermission` launcher) **before** any recognizer
 * is constructed → granted starts the recognizer and listening begins;
 * refused lands on [DictationState.Denied] with the exact refusal line.
 * If the system has fixed the refusal (two denials — the dialog would
 * never appear again), the next tap lands on [DictationState.PermanentlyDenied]
 * pointing at the settings, and the mic tap opens them instead of launching
 * a dialog that cannot come. Partials fill the field as they arrive, the
 * final replaces them, Stop commits and Cancel discards.
 *
 * The line above the composer is a message area, empty by default (S24-9
 * amendment): it shows text only when there is something the icon cannot
 * say — a refusal, an unavailable recognizer, an error, the listening
 * state itself. No idle copy.
 */
class DictationSession(
    private val controller: DictationController,
    private val onApply: (DictationUpdate) -> Unit,
    private val engine: DictationEngine,
    private val requestPermission: () -> Unit,
    /** True when the system will never show the dialog again for this app. */
    private val isPermanentlyDenied: () -> Boolean,
    private val openSettings: () -> Unit,
) {
    /** Set after the first refusal; the permanence check needs it, because
     *  a never-asked permission also reports no-rationale. */
    private var refusedOnce = false

    val listener = object : DictationEngine.Listener {
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
        if (engine.needsPermission) {
            // A permanently fixed refusal: the dialog cannot come, so the
            // tap lands on the settings pointer instead of a silent no-op.
            if (refusedOnce && isPermanentlyDenied()) {
                onApply(controller.onPermissionPermanentlyDenied(engine.permanentlyRefusedMessage))
                return
            }
            onApply(controller.begin(currentDraft, needsPermission = true))
            requestPermission()
            return
        }
        onApply(controller.begin(currentDraft, needsPermission = false))
        engine.start(listener)
    }

    /** The system dialog answered; granted starts the recognizer — only now. */
    fun onPermissionResult(granted: Boolean) {
        if (!granted) {
            refusedOnce = true
            onApply(controller.onPermissionDenied(engine.refusalMessage))
            return
        }
        onApply(controller.onPermissionGranted())
        engine.start(listener)
    }

    /** The settings pointer's action (and the mic tap while permanently denied). */
    fun openPermissionSettings() = openSettings()

    /** The transient result message's four seconds are over: clear it —
     *  the text stays, only the line above the composer goes. */
    fun dismissMessage() = onApply(controller.onMessageExpired())

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
            isPermanentlyDenied = {
                // Only meaningful after a refusal (the session guards it):
                // no rationale wanted means the system fixed the denial.
                context.findActivity()
                    ?.let { activity ->
                        !activity.shouldShowRequestPermissionRationale(
                            android.Manifest.permission.RECORD_AUDIO,
                        )
                    } ?: false
            },
            openSettings = {
                context.findActivity()?.let { activity ->
                    activity.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", activity.packageName, null),
                        ),
                    )
                }
            },
        ).also { holder.clear(); holder.add(it) }
    }
    DisposableEffect(session) {
        onDispose { session.dispose() }
    }
    return session
}

/** LocalContext may be the locale-wrapped context (S24-1): unwrap the chain
 *  to the Activity the permission APIs and the settings screen need. */
private tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
