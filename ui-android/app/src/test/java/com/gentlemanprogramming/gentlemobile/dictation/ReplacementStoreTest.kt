package com.gentlemanprogramming.gentlemobile.dictation

import com.gentlemanprogramming.gentlemobile.ui.theme.KeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Test

/** The replacement store (T58): adding a word, removing one, and the list
 *  surviving a persist round trip — the flow behind the Voz replacements
 *  section. Pure, so the rules are pinned here instead of by reading UI. */
class ReplacementStoreTest {

    private class FakeStore : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) {
            map[key] = value
        }
    }

    @Test
    fun add_appends_a_replacement_with_a_trimmed_from() {
        val out = ReplacementStore.add(emptyList(), "  gonna  ", "going to")
        assertEquals(listOf(WordReplacement("gonna", "going to")), out)
    }

    @Test
    fun a_blank_from_changes_nothing() {
        val current = listOf(WordReplacement("gonna", "going to"))
        assertEquals(current, ReplacementStore.add(current, "   ", "x"))
    }

    @Test
    fun re_adding_the_same_from_replaces_it_never_duplicates() {
        val current = listOf(
            WordReplacement("gonna", "going to"),
            WordReplacement("can't", "cannot"),
        )
        val out = ReplacementStore.add(current, "GONNA", "gonna")
        assertEquals(
            listOf(WordReplacement("can't", "cannot"), WordReplacement("GONNA", "gonna")),
            out,
        )
    }

    @Test
    fun remove_drops_exactly_that_replacement() {
        val current = listOf(
            WordReplacement("gonna", "going to"),
            WordReplacement("can't", "cannot"),
        )
        assertEquals(
            listOf(WordReplacement("gonna", "going to")),
            ReplacementStore.remove(current, WordReplacement("can't", "cannot")),
        )
    }

    @Test
    fun the_list_persists_through_the_voz_store() {
        val store = FakeStore()
        val wanted = VozSettings(
            replacements = listOf(
                WordReplacement("gonna", "going to"),
                WordReplacement("can't", "cannot"),
            ),
        )
        VozSettingsStore(store).save(wanted)
        assertEquals(wanted.replacements, VozSettingsStore(store).load().replacements)
    }
}
