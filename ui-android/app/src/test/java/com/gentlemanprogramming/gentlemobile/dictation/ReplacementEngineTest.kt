package com.gentlemanprogramming.gentlemobile.dictation

import org.junit.Assert.assertEquals
import org.junit.Test

/** T54: the replacement engine — whole-word, case-insensitive, in order. */
class ReplacementEngineTest {

    @Test
    fun a_single_replacement_is_applied() {
        assertEquals(
            "going to",
            ReplacementEngine.apply("gonna", listOf(WordReplacement("gonna", "going to"))),
        )
    }

    @Test
    fun matching_is_case_insensitive() {
        assertEquals(
            "going to",
            ReplacementEngine.apply("Gonna", listOf(WordReplacement("gonna", "going to"))),
        )
        assertEquals(
            "going to going to",
            ReplacementEngine.apply(
                "gonna GONNA",
                listOf(WordReplacement("gonna", "going to")),
            ),
        )
    }

    @Test
    fun matching_is_a_whole_word() {
        assertEquals(
            "scanning",
            ReplacementEngine.apply("scanning", listOf(WordReplacement("can", "may"))),
        )
        assertEquals(
            "may",
            ReplacementEngine.apply("can", listOf(WordReplacement("can", "may"))),
        )
    }

    @Test
    fun replacements_apply_in_list_order() {
        val replacements = listOf(
            WordReplacement("colour", "color"),
            WordReplacement("color", "hue"),
        )
        assertEquals(
            "hue",
            ReplacementEngine.apply("colour", replacements),
        )
    }

    @Test
    fun the_replacement_is_inserted_literally_not_as_a_regex_group() {
        assertEquals(
            "a $100",
            ReplacementEngine.apply("a hundred", listOf(WordReplacement("hundred", "$100"))),
        )
        assertEquals(
            "a \\ path",
            ReplacementEngine.apply("a slash", listOf(WordReplacement("slash", "\\ path"))),
        )
    }

    @Test
    fun a_blank_from_is_ignored() {
        assertEquals(
            "hello",
            ReplacementEngine.apply("hello", listOf(WordReplacement("   ", "x"))),
        )
    }

    @Test
    fun no_replacements_is_an_identity() {
        assertEquals("hello world", ReplacementEngine.apply("hello world", emptyList()))
    }

    @Test
    fun a_punctuation_adjacent_word_is_still_replaced() {
        assertEquals(
            "going to.",
            ReplacementEngine.apply("gonna.", listOf(WordReplacement("gonna", "going to"))),
        )
    }
}
