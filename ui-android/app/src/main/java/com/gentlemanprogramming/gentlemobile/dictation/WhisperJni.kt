package com.gentlemanprogramming.gentlemobile.dictation

/**
 * The JNI surface for whisper.cpp (T54): load a model file, transcribe
 * 16 kHz mono PCM floats, release. The native side is `jni/whisper_jni.c`;
 * these are instance methods on a singleton, so the C `jobject` receiver
 * lines up with the Kotlin object.
 *
 * The library is loaded once, lazily, on first access to this object. The
 * shared library is built by the app's `externalNativeBuild` (CMake) and
 * carries no model — models are downloaded into app storage at runtime.
 */
object WhisperJni {
    init {
        System.loadLibrary("whisper_jni")
    }

    /** Load a model file; returns a native handle or 0 on failure. */
    external fun init(modelPath: String): Long

    /** Release the context returned by [init]; idempotent. */
    external fun free(context: Long)

    /** Transcribe 16 kHz mono PCM floats in [-1, 1]; empty string on failure. */
    external fun transcribe(context: Long, samples: FloatArray, numThreads: Int): String
}
