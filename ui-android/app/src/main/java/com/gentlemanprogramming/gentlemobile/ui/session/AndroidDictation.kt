package com.gentlemanprogramming.gentlemobile.ui.session

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/**
 * T14 (S8): the real recognizer behind the dictation state machine.
 *
 * Engine choice, in order:
 * 1. the on-device recognizer (`createOnDeviceSpeechRecognizer`, API 31+,
 *    when the device has an on-device model) — audio never leaves the phone
 *    and no network service can fail mid-dictation;
 * 2. the standard recognizer (`createSpeechRecognizer`) as an explicit
 *    fallback — same recording, same transcript, network-based.
 *
 * Both recognizers record the microphone, so both are gated by
 * RECORD_AUDIO ([needsPermission]); the on-device engine changes where the
 * audio is processed, not whether it is recorded. The permission is
 * requested at the moment of use — never ahead of the first tap.
 *
 * When neither exists there is no fake: [availabilityReason] carries the
 * exact reason and the machine renders the microphone disabled with it.
 *
 * Audio is never recorded or kept beyond the recognition request: no file
 * is written, no clip is stored — the only output is the transcript.
 *
 * The callbacks arrive on the main thread; [DictationSession] hands them
 * to the [DictationController], which owns every state decision.
 */
class AndroidDictationEngine(private val context: Context) {

    interface Listener {
        /** A partial transcript; it replaces the previous one in the field. */
        fun onPartial(text: String)

        /** The final transcript; it replaces every partial. */
        fun onFinal(text: String)

        /** Recognition failed; [message] is the honest reason to show. */
        fun onError(message: String)
    }

    /**
     * True when the microphone permission is not granted yet — the gate for
     * both recognizer paths, because both record audio while listening.
     */
    val needsPermission: Boolean
        get() = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO,
        ) != PackageManager.PERMISSION_GRANTED

    /**
     * `null` when a recognizer can start; otherwise the exact reason the
     * composer must show under the disabled microphone.
     */
    fun availabilityReason(): String? = when {
        onDeviceAvailable() -> null
        SpeechRecognizer.isRecognitionAvailable(context) -> null
        else -> "No speech recognizer is installed on this device — dictation is unavailable."
    }

    private fun onDeviceAvailable(): Boolean =
        android.os.Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /**
     * Start listening. The caller has already routed the permission gate;
     * partials and finals go to [listener] as they arrive.
     */
    fun start(listener: Listener) {
        val recognizer = createRecognizer() ?: run {
            listener.onError("No speech recognizer is installed on this device — dictation is unavailable.")
            return
        }
        recognizer.setRecognitionListener(Bridge(listener))
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            // Partials are the point of dictation: the field fills as the
            // user speaks, then the final result replaces them.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        try {
            recognizer.startListening(intent)
        } catch (error: Exception) {
            destroy()
            listener.onError("The recognizer could not start: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    /**
     * The user pressed Stop: let the recognizer deliver its final result —
     * recognizers flush it right after stopListening. The recognizer stays
     * alive until that final (or an error) arrives, or until cancel/dispose.
     */
    fun stop() {
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
        }
    }

    /** The user pressed Cancel: discard the request, nothing is kept. */
    fun cancel() {
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
        }
        destroy()
    }

    /** Release the recognizer; called when the screen goes away. */
    fun dispose() = destroy()

    private var recognizer: SpeechRecognizer? = null

    private fun createRecognizer(): SpeechRecognizer? {
        destroy()
        recognizer = if (onDeviceAvailable()) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else {
            null
        }
        return recognizer
    }

    private fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    /** Maps the framework callbacks onto the listener's three honest events. */
    private inner class Bridge(private val listener: Listener) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) {
            firstLine(partialResults)?.let { listener.onPartial(it) }
        }

        override fun onResults(results: Bundle?) {
            val line = firstLine(results)
            destroy()
            line?.let { listener.onFinal(it) }
        }

        override fun onError(error: Int) {
            val message = describe(error)
            destroy()
            listener.onError(message)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        private fun firstLine(bundle: Bundle?): String? =
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

        /** The recognizer's error codes, in words the composer can show. */
        private fun describe(error: Int) = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech heard — nothing was dictated."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard — the microphone stayed quiet."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                "The microphone permission was refused, so nothing was heard."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                "The recognizer's network service failed — the on-device engine avoids this."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recognizer was busy — try again in a moment."
            else -> "Dictation failed (recognizer error $error)."
        }
    }
}
