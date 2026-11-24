package com.gentlemanprogramming.gentlemobile.dictation

/**
 * T54 (S8): word replacements applied to a transcript — pure, so the rule is
 * pinned by a unit test instead of by reading the UI.
 *
 * The rules:
 * * replacements apply in list order (a later one may rewrite an earlier
 *   one's output, exactly as the user listed them);
 * * matching is a case-insensitive whole word, so `can` never matches
 *   inside `scanning`;
 * * [WordReplacement.to] is inserted literally — never interpreted as a
 *   regex group, so a `$` or `\` in the replacement stays as typed;
 * * a blank `from` is ignored (it matches nothing useful).
 */
object ReplacementEngine {

    fun apply(text: String, replacements: List<WordReplacement>): String {
        var result = text
        for (replacement in replacements) {
            val from = replacement.from.trim()
            if (from.isEmpty()) continue
            val pattern = Regex("(?i)\\b${Regex.escape(from)}\\b")
            result = pattern.replace(result) { replacement.to }
        }
        return result
    }
}
