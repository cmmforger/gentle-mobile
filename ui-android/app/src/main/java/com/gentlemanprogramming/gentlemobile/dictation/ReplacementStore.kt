package com.gentlemanprogramming.gentlemobile.dictation

/**
 * T58 (S8): the word-replacement list the Voz screen edits. Pure list rules —
 * add a word, remove one — so the flow is pinned by a unit test, and the
 * list itself persists through [VozSettingsStore].
 *
 * Rules:
 * * [add] trims the `from`, ignores a blank one, and replaces an existing
 *   entry with the same `from` (case-insensitive, because the matcher is
 *   case-insensitive) instead of duplicating it;
 * * [remove] drops exactly the entry given.
 */
object ReplacementStore {

    fun add(current: List<WordReplacement>, from: String, to: String): List<WordReplacement> {
        val trimmed = from.trim()
        if (trimmed.isEmpty()) return current
        val replacement = WordReplacement(trimmed, to)
        val without = current.filterNot { it.from.equals(trimmed, ignoreCase = true) }
        return without + replacement
    }

    fun remove(current: List<WordReplacement>, replacement: WordReplacement): List<WordReplacement> =
        current.filterNot { it == replacement }
}
