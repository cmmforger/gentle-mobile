package com.gentlemanprogramming.gentlecompanion.pair

/**
 * The pairing link `scripts/gentle-companion-pair` prints on the host and the
 * QR encodes: `gc://host:port?user=name`. Parsing is pure Kotlin (no
 * android.net.Uri) so it is unit-testable on the JVM and refuses anything
 * else with a readable reason — never a silent no-op.
 */
sealed interface PairLinkResult {
    data class Parsed(val host: String, val port: String, val user: String) : PairLinkResult
    data class Invalid(val reason: String) : PairLinkResult
}

object PairLink {

    fun parse(raw: String): PairLinkResult {
        val text = raw.trim()
        if (text.isEmpty()) {
            return PairLinkResult.Invalid(
                "Paste the link printed by gentle-companion-pair (gc://host:port?user=…).",
            )
        }
        if (!text.startsWith("gc://")) {
            return PairLinkResult.Invalid(
                "That is not a gc:// link — copy the “Connection link” line the pair command prints.",
            )
        }
        val rest = text.removePrefix("gc://")
        val queryStart = rest.indexOf('?')
        if (queryStart < 0) {
            return PairLinkResult.Invalid(
                "The link is missing ?user=… — copy the full connection link.",
            )
        }
        val authority = rest.substring(0, queryStart)
        val query = rest.substring(queryStart + 1)

        val portStart = authority.lastIndexOf(':')
        if (portStart <= 0) {
            return PairLinkResult.Invalid(
                "The link needs host:port before the ?, like gc://192.168.1.24:22?user=me.",
            )
        }
        val host = authority.substring(0, portStart)
        val port = authority.substring(portStart + 1)
        if (host.isEmpty() || host.contains('/') || host.any { it.isWhitespace() }) {
            return PairLinkResult.Invalid("The link's host name looks malformed.")
        }
        val portNumber = port.toIntOrNull()
        if (portNumber == null || portNumber !in 1..65535) {
            return PairLinkResult.Invalid("The link's port must be a number from 1 to 65535.")
        }

        val user = query.split('&').firstNotNullOfOrNull { part ->
            val eq = part.indexOf('=')
            if (eq > 0 && part.substring(0, eq) == "user") part.substring(eq + 1) else null
        } ?: return PairLinkResult.Invalid("The link is missing ?user=… — copy the full connection link.")
        val decodedUser = runCatching { java.net.URLDecoder.decode(user, "UTF-8") }.getOrNull()
            ?: return PairLinkResult.Invalid("The link's user name is not valid URL text.")
        if (decodedUser.isEmpty()) {
            return PairLinkResult.Invalid("The link's user name looks malformed.")
        }

        return PairLinkResult.Parsed(host = host, port = port, user = decodedUser)
    }
}
