#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>

#include "whisper.h"

#define TAG "WhisperJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

JNIEXPORT jlong JNICALL
Java_com_gentlemanprogramming_gentlemobile_dictation_WhisperJni_init(
        JNIEnv *env, jobject thiz, jstring model_path) {
    (void) thiz;
    const char *path = (*env)->GetStringUTFChars(env, model_path, NULL);
    if (path == NULL) {
        return 0;
    }
    struct whisper_context *ctx =
            whisper_init_from_file_with_params(path, whisper_context_default_params());
    (*env)->ReleaseStringUTFChars(env, model_path, path);
    if (ctx == NULL) {
        LOGW("whisper_init_from_file_with_params failed");
    }
    return (jlong) ctx;
}

JNIEXPORT void JNICALL
Java_com_gentlemanprogramming_gentlemobile_dictation_WhisperJni_free(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    (void) env;
    (void) thiz;
    if (context_ptr != 0) {
        whisper_free((struct whisper_context *) context_ptr);
    }
}

JNIEXPORT jstring JNICALL
Java_com_gentlemanprogramming_gentlemobile_dictation_WhisperJni_transcribe(
        JNIEnv *env, jobject thiz, jlong context_ptr, jfloatArray samples, jint num_threads) {
    (void) thiz;
    struct whisper_context *ctx = (struct whisper_context *) context_ptr;
    if (ctx == NULL) {
        return (*env)->NewStringUTF(env, "");
    }

    jsize n = (*env)->GetArrayLength(env, samples);
    if (n <= 0) {
        return (*env)->NewStringUTF(env, "");
    }
    jfloat *data = (*env)->GetFloatArrayElements(env, samples, NULL);
    if (data == NULL) {
        return (*env)->NewStringUTF(env, "");
    }

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime   = false;
    params.print_progress   = false;
    params.print_timestamps = false;
    params.print_special    = false;
    params.translate        = false;
    params.language         = "auto";
    params.n_threads        = num_threads > 0 ? num_threads : 4;
    params.offset_ms        = 0;
    params.no_context       = true;
    params.single_segment   = false;
    params.token_timestamps = false;

    whisper_reset_timings(ctx);

    int ret = whisper_full(ctx, params, data, n);
    (*env)->ReleaseFloatArrayElements(env, samples, data, JNI_ABORT);

    if (ret != 0) {
        LOGW("whisper_full failed: %d", ret);
        return (*env)->NewStringUTF(env, "");
    }

    int n_segments = whisper_full_n_segments(ctx);

    size_t total = 0;
    for (int i = 0; i < n_segments; i++) {
        total += strlen(whisper_full_get_segment_text(ctx, i));
    }
    char *out = (char *) malloc(total + 1);
    if (out == NULL) {
        return (*env)->NewStringUTF(env, "");
    }
    out[0] = '\0';
    for (int i = 0; i < n_segments; i++) {
        strcat(out, whisper_full_get_segment_text(ctx, i));
    }

    jstring result = (*env)->NewStringUTF(env, out);
    free(out);
    return result;
}
