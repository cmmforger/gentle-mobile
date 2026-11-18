package com.gentlemanprogramming.gentlemobile.pair

/**
 * The host-side command the Pair screen shows, ready to copy: the same
 * contract as `scripts/gentle-companion-pair`. Kept as a pure builder so it
 * is unit-testable — the screen never re-derives it inline.
 */
object PairCommand {

    const val HOST_TOOL = "gentle-companion-pair"

    /** `gentle-companion-pair --add-key '<public key line>'` — single-quoted. */
    fun addKey(publicKeyLine: String): String {
        val key = publicKeyLine.trim()
        require(key.isNotEmpty()) { "the public key line is empty" }
        require('\'' !in key) { "the public key line cannot contain a quote" }
        return "$HOST_TOOL --add-key '$key'"
    }
}
