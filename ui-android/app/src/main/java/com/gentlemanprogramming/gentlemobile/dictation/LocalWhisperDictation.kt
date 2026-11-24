package com.gentlemanprogramming.gentlemobile.dictation

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** What the microphone-driven dictation is doing right now, for the UI. */
sealed interface LocalDictationState {
    data object Idle : LocalDictationState
    data object Listening : LocalDictationState
    data object Transcribing : LocalDictationState
    data class Error(val message: String) : LocalDictationState
}

/**
 * T54 (S8): microphone → whisper → text. Records 16 kHz mono PCM with
 * [AudioRecord], transcribes the whole buffer with [WhisperEngine] on Stop,
 * applies the word replacements, and hands the final text to the listener.
 *
 * The caller gates RECORD_AUDIO before [start]; this class never asks. The
 * model loads once on first use (loading a model is slow) and stays loaded
 * until [dispose]. Audio is held only in memory for the duration of one
 * dictation and never written to a file.
 */
class LocalWhisperDictation(
    private val modelPathProvider: () -> String?,
    private val replacementsProvider: () -> List<WordReplacement>,
    private val executor: ExecutorService,
    private val mainHandler: Handler,
) {
    interface Listener {
        fun onState(state: LocalDictationState)
        fun onFinal(text: String, elapsedMillis: Long)
    }

    private var engine: WhisperEngine? = null
    private var record: AudioRecord? = null
    private var captureThread: Thread? = null
    private var buffer: ByteArrayOutputStream? = null
    private val recording = AtomicBoolean(false)
    @Volatile
    private var modelReady = CountDownLatch(1)

    /** Start recording. The permission gate belongs to the caller. */
    fun start(listener: Listener) {
        val path = modelPathProvider()
        if (path == null) {
            listener.onState(LocalDictationState.Error("No model is installed — download one in Settings → Voz."))
            return
        }
        val audio = openRecorder()
        if (audio == null) {
            listener.onState(LocalDictationState.Error("The microphone could not be opened."))
            return
        }
        record = audio
        val sink = ByteArrayOutputStream()
        buffer = sink
        recording.set(true)
        audio.startRecording()
        captureThread = Thread({
            val chunk = ShortArray(4096)
            while (recording.get()) {
                val read = audio.read(chunk, 0, chunk.size)
                if (read <= 0) continue
                for (i in 0 until read) {
                    val s = chunk[i].toInt()
                    sink.write(s and 0xFF)
                    sink.write((s shr 8) and 0xFF)
                }
            }
        }, "whisper-capture").also { it.start() }
        listener.onState(LocalDictationState.Listening)
        // Load the model in parallel with recording so the first tap is not
        // spent waiting; a failure surfaces as an error and stops recording.
        executor.execute {
            try {
                val loaded = engine ?: WhisperEngine(path).also { candidate ->
                    if (candidate.load()) engine = candidate
                }
                if (!(loaded?.isLoaded ?: false)) {
                    stopRecording()
                    mainHandler.post {
                        listener.onState(LocalDictationState.Error("The model could not be loaded."))
                    }
                }
            } finally {
                modelReady.countDown()
            }
        }
    }

    /** Stop recording and transcribe the captured audio. */
    fun stop(listener: Listener) {
        val bytes = stopRecording()
        if (bytes == null || bytes.size < 2) {
            listener.onFinal("", 0L)
            listener.onState(LocalDictationState.Idle)
            return
        }
        listener.onState(LocalDictationState.Transcribing)
        val replacements = replacementsProvider()
        executor.execute {
            try {
                // The model loads in parallel with recording; a quick Stop
                // must still wait for it so the first tap never loses audio.
                modelReady.await(30, TimeUnit.SECONDS)
                val floats = shortsToFloat(bytes)
                val result = engine?.transcribe(floats) ?: WhisperTranscription("", 0L)
                val text = ReplacementEngine.apply(result.text, replacements)
                mainHandler.post {
                    listener.onFinal(text, result.elapsedMillis)
                    listener.onState(LocalDictationState.Idle)
                }
            } catch (error: Exception) {
                mainHandler.post {
                    listener.onState(LocalDictationState.Error(error.message ?: "Transcription failed."))
                }
            }
        }
    }

    /** Cancel: discard the recording, release the microphone, no transcript. */
    fun cancel(listener: Listener) {
        stopRecording()
        listener.onState(LocalDictationState.Idle)
    }

    /** The screen went away: release the recorder and the loaded model. */
    fun dispose() {
        stopRecording()
        engine?.release()
        engine = null
        // The next start re-loads the model, so the latch is fresh again.
        modelReady = CountDownLatch(1)
    }

    private fun openRecorder(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE * 2 * 2) // at least ~2 s
        return try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun stopRecording(): ByteArray? {
        if (!recording.getAndSet(false)) return null
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        captureThread?.join(500)
        captureThread = null
        try {
            record?.release()
        } catch (_: Exception) {
        }
        record = null
        val captured = buffer?.toByteArray()
        buffer = null
        return captured
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        /** Little-endian s16 samples → float in [-1, 1] for whisper. */
        internal fun shortsToFloat(bytes: ByteArray): FloatArray {
            val count = bytes.size / 2
            val out = FloatArray(count)
            for (i in 0 until count) {
                val low = bytes[i * 2].toInt() and 0xFF
                val high = bytes[i * 2 + 1].toInt() and 0xFF
                val s = (low or (high shl 8)).toShort().toInt()
                out[i] = s / 32768f
            }
            return out
        }
    }
}
