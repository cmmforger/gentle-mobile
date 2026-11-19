package com.gentlemanprogramming.gentlemobile.pair

/**
 * The host-side command the Pair screen shows, ready to copy: the same
 * contract as `scripts/gentle-mobile`, which owns the subcommands
 * (S25-1). The old `gentle-mobile-pair` engine still works as a shim, but
 * the screen shows the real CLI name. Kept as a pure builder so it is
 * unit-testable — the screen never re-derives it inline.
 */
object PairCommand {

    const val HOST_TOOL = "gentle-mobile"

    /** `gentle-mobile pair --add-key '<public key line>'` — single-quoted. */
    fun addKey(publicKeyLine: String): String {
        val key = publicKeyLine.trim()
        require(key.isNotEmpty()) { "the public key line is empty" }
        require('\'' !in key) { "the public key line cannot contain a quote" }
        return "$HOST_TOOL pair --add-key '$key'"
    }
}
