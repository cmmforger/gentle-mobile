package com.gentlemanprogramming.gentlemobile.ui

/**
 * The pure row words for the grouped agent list (S24-6, S24-8). The inbox
 * composable renders these; the words themselves are unit-tested so the
 * corrections from the first phone test cannot regress silently.
 */
object AgentRowModel {

    /**
     * The row's title (S24-8): the **project name** — the last path segment
     * of the agent's cwd — never the full path. When the host gave no cwd
     * (or a blank one), the agent's own name stands in; nothing is invented.
     */
    fun projectTitle(cwd: String?, fallbackName: String): String =
        cwd?.trim()?.trimEnd('/')
            ?.takeIf { it.isNotEmpty() }
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotEmpty() }
            ?: fallbackName

    /**
     * The agent's kind as the row's secondary line carries it (S24-8): kept
     * only when it differs from `pi` — the default kind must not repeat
     * beside every row. Blank or missing kinds are not shown either.
     */
    fun secondaryKind(kind: String?): String? =
        kind?.trim()?.takeIf { it.isNotEmpty() && it != "pi" }

    /**
     * The group label (S24-6): the human word already in sentence case, with
     * the count kept only when it is not one — a single-row group does not
     * need "· 1" repeating what the list shows (design/system.md redundancy
     * rule).
     */
    fun groupLabel(word: String, count: Int): String =
        if (count == 1) word else "$word · $count"
}
