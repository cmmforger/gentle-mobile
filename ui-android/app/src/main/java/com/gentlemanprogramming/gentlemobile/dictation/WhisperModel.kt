package com.gentlemanprogramming.gentlemobile.dictation

/**
 * The local-Whisper model catalogue (T54, T58). Pure data — no Android types —
 * so the catalogue's name, size, url, language and trade-off are pinned by a
 * unit test.
 *
 * The app ships no models: every entry is downloaded on demand from the
 * canonical whisper.cpp GGML URLs into app storage. The declared sizes are
 * the human's own numbers from the product table (they are rounded labels,
 * never an exact byte count — the real download progress comes from the
 * HTTP Content-Length).
 *
 * T58: the models split into two cards — Inglés (the `.en` models) and
 * Español/Multilingüe — and every row carries its weight and its trade-off
 * (`sizeLabel · tradeOffKey`, localised on screen).
 */
enum class ModelLanguage { ENGLISH, MULTILINGUAL }

enum class ModelSize { TINY, BASE, SMALL, TURBO }

data class WhisperModel(
    val id: String,
    val size: ModelSize,
    val language: ModelLanguage,
    val sizeBytes: Long,
) {
    val url: String
        get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-$id.bin"

    val fileName: String
        get() = "ggml-$id.bin"

    val label: String
        get() = when (size) {
            ModelSize.TINY -> "Tiny"
            ModelSize.BASE -> "Base"
            ModelSize.SMALL -> "Small"
            ModelSize.TURBO -> "Turbo"
        }

    /** The row name: language only when it is a real choice. */
    val displayName: String
        get() = when (language) {
            ModelLanguage.ENGLISH -> "$label · English"
            ModelLanguage.MULTILINGUAL -> label
        }

    /** The rounded size the row shows, exactly as the human wrote it. */
    val sizeLabel: String
        get() = when (size) {
            ModelSize.TINY -> "75 MB"
            ModelSize.BASE -> "142 MB"
            ModelSize.SMALL -> "466 MB"
            ModelSize.TURBO -> "1.6 GB"
        }

    /** The second half of the row's "75 MB · …" line, keyed per size so the
     *  screen localises it (EN/ES) without putting Android strings in data. */
    val tradeOffKey: String
        get() = when (size) {
            ModelSize.TINY -> "fastest_least_accurate"
            ModelSize.BASE -> "fast_good"
            ModelSize.SMALL -> "slower_excellent"
            ModelSize.TURBO -> "maximum_accuracy"
        }
}

/** The whole catalogue, in table order (size, then English before multilingual). */
object WhisperCatalogue {
    private const val MB = 1024L * 1024L
    private const val GB = 1024L * 1024L * 1024L

    val models: List<WhisperModel> = listOf(
        WhisperModel("tiny.en", ModelSize.TINY, ModelLanguage.ENGLISH, 75 * MB),
        WhisperModel("tiny", ModelSize.TINY, ModelLanguage.MULTILINGUAL, 75 * MB),
        WhisperModel("base.en", ModelSize.BASE, ModelLanguage.ENGLISH, 142 * MB),
        WhisperModel("base", ModelSize.BASE, ModelLanguage.MULTILINGUAL, 142 * MB),
        WhisperModel("small.en", ModelSize.SMALL, ModelLanguage.ENGLISH, 466 * MB),
        WhisperModel("small", ModelSize.SMALL, ModelLanguage.MULTILINGUAL, 466 * MB),
        WhisperModel("large-v3-turbo", ModelSize.TURBO, ModelLanguage.MULTILINGUAL, 16 * GB / 10),
    )

    /** T58: the Inglés card — the `.en` models. */
    val english: List<WhisperModel> = models.filter { it.language == ModelLanguage.ENGLISH }

    /** T58: the Español/Multilingüe card. */
    val multilingual: List<WhisperModel> = models.filter { it.language == ModelLanguage.MULTILINGUAL }

    fun byId(id: String): WhisperModel? = models.firstOrNull { it.id == id }
}
