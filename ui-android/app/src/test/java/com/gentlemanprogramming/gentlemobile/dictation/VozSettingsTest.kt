package com.gentlemanprogramming.gentlemobile.dictation

import com.gentlemanprogramming.gentlemobile.ui.theme.KeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T54: the Voz settings store and the active-model/fallback rule, as pure
 *  data over an in-memory store. */
class VozSettingsTest {

    private class FakeStore : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) {
            map[key] = value
        }
    }

    // ---- defaults ----

    @Test
    fun the_default_engine_is_the_phone_keyboard() {
        assertEquals(DictationEngineChoice.PHONE_KEYBOARD, VozSettings().engine)
    }

    @Test
    fun auto_send_is_off_by_default() {
        assertFalse(VozSettings().autoSend)
    }

    @Test
    fun no_model_is_active_by_default() {
        assertNull(VozSettings().activeModelId)
    }

    @Test
    fun a_fresh_store_reads_the_defaults() {
        assertEquals(VozSettings(), VozSettingsStore(FakeStore()).load())
    }

    // ---- round trip ----

    @Test
    fun a_saved_settings_round_trip() {
        val store = FakeStore()
        val wanted = VozSettings(
            engine = DictationEngineChoice.LOCAL_WHISPER,
            autoSend = true,
            activeModelId = "base.en",
            replacements = listOf(
                WordReplacement("gonna", "going to"),
                WordReplacement("can't", "cannot"),
            ),
        )
        VozSettingsStore(store).save(wanted)
        assertEquals(wanted, VozSettingsStore(store).load())
    }

    @Test
    fun a_garbage_engine_falls_back_to_the_keyboard() {
        val store = FakeStore()
        store.map[VozSettingsStore.KEY_ENGINE] = "not-an-engine"
        assertEquals(DictationEngineChoice.PHONE_KEYBOARD, VozSettingsStore(store).load().engine)
    }

    @Test
    fun replacements_round_trip_even_with_whitespace_edges() {
        val store = FakeStore()
        val wanted = VozSettings(
            replacements = listOf(WordReplacement("  errr  ", "uh")),
        )
        VozSettingsStore(store).save(wanted)
        // The stored `from` keeps its surrounding whitespace trimmed on decode.
        assertEquals(WordReplacement("errr", "uh"), VozSettingsStore(store).load().replacements.single())
    }

    // ---- the active model and the fallback (T54) ----

    @Test
    fun the_active_model_is_the_picked_model_when_it_is_installed() {
        assertEquals(
            WhisperCatalogue.byId("base.en"),
            resolveActiveModel("base.en", installed = setOf("base.en", "tiny")),
        )
    }

    @Test
    fun a_deleted_active_model_resolves_to_null() {
        // "delete it to fall back to the default": the picked model is gone,
        // so nothing is active and the engine falls back to the keyboard.
        assertNull(resolveActiveModel("base.en", installed = setOf("tiny")))
    }

    @Test
    fun no_pick_and_no_models_resolve_to_null() {
        assertNull(resolveActiveModel(null, installed = emptySet()))
    }

    @Test
    fun an_unknown_active_id_resolves_to_null() {
        assertNull(resolveActiveModel("medium.en", installed = setOf("medium.en")))
    }

    @Test
    fun local_whisper_needs_an_active_model_otherwise_the_keyboard() {
        assertEquals(
            DictationEngineChoice.LOCAL_WHISPER,
            effectiveEngine(DictationEngineChoice.LOCAL_WHISPER, WhisperCatalogue.byId("tiny")),
        )
        assertEquals(
            DictationEngineChoice.PHONE_KEYBOARD,
            effectiveEngine(DictationEngineChoice.LOCAL_WHISPER, activeModel = null),
        )
    }

    @Test
    fun the_phone_keyboard_is_never_overridden() {
        assertEquals(
            DictationEngineChoice.PHONE_KEYBOARD,
            effectiveEngine(DictationEngineChoice.PHONE_KEYBOARD, WhisperCatalogue.byId("tiny")),
        )
    }

    @Test
    fun deleting_every_model_falls_back_to_the_keyboard() {
        val chosen = DictationEngineChoice.LOCAL_WHISPER
        val noModel = resolveActiveModel("tiny", installed = emptySet())
        assertEquals(DictationEngineChoice.PHONE_KEYBOARD, effectiveEngine(chosen, noModel))
        assertTrue(noModel == null)
    }
}
