package com.gentlemanprogramming.gentlemobile.dictation

/**
 * T54 (S8): one loaded whisper model — load, transcribe, release — with the
 * wall-clock the Voz screen's test line reports. Not thread-safe: whisper
 * allows one caller per context, so a single owner (the dictation engine or
 * the Voz test) drives it at a time.
 */
data class WhisperTranscription(val text: String, val elapsedMillis: Long)

class WhisperEngine(private val modelPath: String) : AutoCloseable {

    private var context: Long = 0L

    val isLoaded: Boolean
        get() = context != 0L

    /** Load the model once; returns false when the file cannot be read. */
    fun load(): Boolean {
        if (context != 0L) return true
        context = WhisperJni.init(modelPath)
        return context != 0L
    }

    /** Transcribe a 16 kHz mono PCM float buffer; [numThreads] is clamped
     *  to a sensible range by the native side. */
    fun transcribe(samples: FloatArray, numThreads: Int = 4): WhisperTranscription {
        if (!load()) return WhisperTranscription(text = "", elapsedMillis = 0L)
        val started = System.nanoTime()
        val text = WhisperJni.transcribe(context, samples, numThreads)
        val elapsed = (System.nanoTime() - started) / 1_000_000L
        return WhisperTranscription(text, elapsed)
    }

    /** Release the native context; idempotent. */
    fun release() {
        if (context != 0L) {
            WhisperJni.free(context)
            context = 0L
        }
    }

    override fun close() = release()
}
