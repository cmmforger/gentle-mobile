package com.gentlemanprogramming.gentlemobile.dictation

import com.gentlemanprogramming.gentlemobile.ui.theme.KeyValueStore

/**
 * T54 (S8): the two ways the phone turns speech into text.
 *
 * PHONE_KEYBOARD is what works today with no model: the composer is a plain
 * field and the system keyboard's own voice input does the dictation.
 * LOCAL_WHISPER records the microphone in the app and transcribes with
 * whisper.cpp, which needs an installed model. Choosing local Whisper with
 * no installed model resolves back to the phone keyboard — the app ships no
 * model, so "the default" is always the keyboard.
 */
enum class DictationEngineChoice { PHONE_KEYBOARD, LOCAL_WHISPER }

/** One word replacement: [from] is what the engine said, [to] is what the
 *  field must show instead. Applied to a transcript after transcription and
 *  before it lands in the composer. */
data class WordReplacement(val from: String, val to: String)

/**
 * The Voz settings (T54): engine choice, auto-send, the model the user
 * picked with "use this", and the word replacements applied to every
 * transcript. Pure data — no Android types — so the defaults, the
 * round-trip and the fallback rule are pinned by unit tests.
 */
data class VozSettings(
    val engine: DictationEngineChoice = DictationEngineChoice.PHONE_KEYBOARD,
    val autoSend: Boolean = false,
    val activeModelId: String? = null,
    val replacements: List<WordReplacement> = emptyList(),
)

/** The persisted Voz settings over the same [KeyValueStore] every
 *  preference uses. A garbage stored value falls back to the default. */
class VozSettingsStore(private val store: KeyValueStore) {

    fun load(): VozSettings {
        val engine = store.getString(KEY_ENGINE)
            ?.let { stored -> DictationEngineChoice.entries.firstOrNull { it.name == stored } }
            ?: VozSettings().engine
        val replacements = decodeReplacements(store.getString(KEY_REPLACEMENTS))
        return VozSettings(
            engine = engine,
            autoSend = store.getString(KEY_AUTO_SEND) == "true",
            activeModelId = store.getString(KEY_ACTIVE_MODEL),
            replacements = replacements,
        )
    }

    fun save(settings: VozSettings) {
        store.putString(KEY_ENGINE, settings.engine.name)
        store.putString(KEY_AUTO_SEND, settings.autoSend.toString())
        if (settings.activeModelId != null) {
            store.putString(KEY_ACTIVE_MODEL, settings.activeModelId)
        }
        store.putString(KEY_REPLACEMENTS, encodeReplacements(settings.replacements))
    }

    companion object {
        const val KEY_ENGINE = "voz_engine"
        const val KEY_AUTO_SEND = "voz_auto_send"
        const val KEY_ACTIVE_MODEL = "voz_active_model"
        const val KEY_REPLACEMENTS = "voz_replacements"

        /** Replacements are stored as one line each: `from\u0001to`, joined
         *  by `\u0002`. The control characters cannot appear in a word the
         *  user types in this feature, so the encoding round-trips exactly. */
        internal fun encodeReplacements(replacements: List<WordReplacement>): String =
            replacements.joinToString("\u0002") { "${it.from}\u0001${it.to}" }

        internal fun decodeReplacements(stored: String?): List<WordReplacement> {
            if (stored.isNullOrEmpty()) return emptyList()
            return stored.split("\u0002").mapNotNull { line ->
                val at = line.indexOf('\u0001')
                if (at <= 0) return@mapNotNull null
                val from = line.substring(0, at).trim()
                if (from.isEmpty()) return@mapNotNull null
                WordReplacement(from = from, to = line.substring(at + 1))
            }
        }
    }
}

/**
 * The model the local Whisper engine uses: the user's "use this" choice when
 * that model is still installed, otherwise `null` — the engine then falls
 * back to the phone keyboard, because no installed model means no local
 * transcription.
 */
fun resolveActiveModel(activeId: String?, installed: Set<String>): WhisperModel? =
    activeId?.let { WhisperCatalogue.byId(it) }?.takeIf { it.id in installed }

/**
 * The engine the composer actually uses (T54): local Whisper only when the
 * user chose it **and** an installed model is active; every other state is
 * the phone keyboard — the default that works with no model.
 */
fun effectiveEngine(engine: DictationEngineChoice, activeModel: WhisperModel?): DictationEngineChoice =
    if (engine == DictationEngineChoice.LOCAL_WHISPER && activeModel != null) {
        DictationEngineChoice.LOCAL_WHISPER
    } else {
        DictationEngineChoice.PHONE_KEYBOARD
    }
