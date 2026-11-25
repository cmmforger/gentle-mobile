package com.gentlemanprogramming.gentlemobile.dictation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The model catalogue (T54): pure data — split into the two cards the Voz
 *  screen shows (Inglés and Español/Multilingüe), each row carrying its
 *  weight label and its trade-off key. */
class WhisperCatalogueTest {

    @Test
    fun the_catalogue_splits_into_english_and_multilingual_cards() {
        assertEquals(
            listOf("tiny.en", "base.en", "small.en"),
            WhisperCatalogue.english.map { it.id },
        )
        assertEquals(
            listOf("tiny", "base", "small", "large-v3-turbo"),
            WhisperCatalogue.multilingual.map { it.id },
        )
    }

    @Test
    fun the_flat_catalogue_keeps_all_seven_in_table_order() {
        assertEquals(
            listOf("tiny.en", "tiny", "base.en", "base", "small.en", "small", "large-v3-turbo"),
            WhisperCatalogue.models.map { it.id },
        )
    }

    @Test
    fun every_size_label_matches_the_human_s_table() {
        fun label(size: ModelSize) = WhisperCatalogue.models.first { it.size == size }.sizeLabel
        assertEquals("75 MB", label(ModelSize.TINY))
        assertEquals("142 MB", label(ModelSize.BASE))
        assertEquals("466 MB", label(ModelSize.SMALL))
        assertEquals("1.6 GB", label(ModelSize.TURBO))
    }

    @Test
    fun every_model_carries_its_size_s_trade_off() {
        assertEquals("fastest_least_accurate", WhisperCatalogue.byId("tiny.en")!!.tradeOffKey)
        assertEquals("fastest_least_accurate", WhisperCatalogue.byId("tiny")!!.tradeOffKey)
        assertEquals("fast_good", WhisperCatalogue.byId("base.en")!!.tradeOffKey)
        assertEquals("fast_good", WhisperCatalogue.byId("base")!!.tradeOffKey)
        assertEquals("slower_excellent", WhisperCatalogue.byId("small.en")!!.tradeOffKey)
        assertEquals("slower_excellent", WhisperCatalogue.byId("small")!!.tradeOffKey)
        assertEquals("maximum_accuracy", WhisperCatalogue.byId("large-v3-turbo")!!.tradeOffKey)
    }

    @Test
    fun english_and_multilingual_share_a_size_label() {
        val tiny = WhisperCatalogue.byId("tiny")!!
        val tinyEn = WhisperCatalogue.byId("tiny.en")!!
        assertEquals(tiny.sizeLabel, tinyEn.sizeLabel)
        assertEquals(ModelLanguage.MULTILINGUAL, tiny.language)
        assertEquals(ModelLanguage.ENGLISH, tinyEn.language)
    }

    @Test
    fun the_url_is_the_canonical_huggingface_resolve_path() {
        assertEquals(
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin",
            WhisperCatalogue.byId("tiny.en")!!.url,
        )
        assertEquals(
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo.bin",
            WhisperCatalogue.byId("large-v3-turbo")!!.url,
        )
    }

    @Test
    fun the_file_name_is_ggml_id_bin() {
        assertEquals("ggml-tiny.en.bin", WhisperCatalogue.byId("tiny.en")!!.fileName)
    }

    @Test
    fun the_display_name_shows_the_language_only_when_it_is_a_real_choice() {
        assertEquals("Tiny · English", WhisperCatalogue.byId("tiny.en")!!.displayName)
        assertEquals("Tiny", WhisperCatalogue.byId("tiny")!!.displayName)
        assertEquals("Turbo", WhisperCatalogue.byId("large-v3-turbo")!!.displayName)
    }

    @Test
    fun turbo_is_multilingual_only() {
        val turbo = WhisperCatalogue.byId("large-v3-turbo")!!
        assertEquals(ModelLanguage.MULTILINGUAL, turbo.language)
        assertEquals("maximum_accuracy", turbo.tradeOffKey)
    }

    @Test
    fun byId_returns_null_for_an_unknown_model() {
        assertNull(WhisperCatalogue.byId("medium.en"))
    }

    @Test
    fun the_declared_sizes_are_the_rounded_labels_not_exact_bytes() {
        assertTrue(WhisperCatalogue.byId("small.en")!!.sizeBytes > 400L * 1024 * 1024)
        assertTrue(WhisperCatalogue.byId("large-v3-turbo")!!.sizeBytes > 1_600_000_000L)
    }
}
