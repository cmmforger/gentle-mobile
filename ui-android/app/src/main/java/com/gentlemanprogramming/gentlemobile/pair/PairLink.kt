package com.gentlemanprogramming.gentlemobile.pair

/**
 * The pairing link `scripts/gentle-mobile-pair` prints on the host and the
 * QR encodes. The one-scan form carries the pairing listener and its
 * one-time token: `gc://host:port?user=name&pair=pairHost:pairPort&t=token`.
 * Without `pair`/`t` it is the legacy paste form. Parsing is pure Kotlin
 * (no android.net.Uri) so it is unit-testable on the JVM and refuses
 * anything else with a readable reason — never a silent no-op.
 */
sealed interface PairLinkResult {
    data class Parsed(
        val host: String,
        val port: String,
        val user: String,
        /** Present only on a one-scan link: where to POST the key, and the token. */
        val pair: PairParams? = null,
    ) : PairLinkResult
    data class Invalid(val reason: String) : PairLinkResult
}

/** The single-use pairing listener endpoint plus its one-time token. */
data class PairParams(val host: String, val port: String, val token: String)

object PairLink {

    fun parse(raw: String): PairLinkResult {
        val text = raw.trim()
        if (text.isEmpty()) {
            return PairLinkResult.Invalid(
                "Paste the link printed by gentle-mobile-pair (gc://host:port?user=…).",
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

        // The one-scan parameters: pair endpoint and one-time token. Either
        // both must be present or neither — a half link is refused, never
        // silently downgraded to the legacy paste form.
        var pairParam: String? = null
        var tokenParam: String? = null
        for (part in query.split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            when (part.substring(0, eq)) {
                "pair" -> pairParam = part.substring(eq + 1)
                "t" -> tokenParam = part.substring(eq + 1)
            }
        }
        val pair = if (pairParam == null && tokenParam == null) {
            null
        } else {
            val pairHostPort = pairParam
                ?: return PairLinkResult.Invalid(
                    "The link carries a pairing token but no &pair=host:port — copy the full link.",
                )
            val token = tokenParam
                ?: return PairLinkResult.Invalid(
                    "The link carries a pairing endpoint but no &t=token — copy the full link.",
                )
            if (token.isEmpty()) {
                return PairLinkResult.Invalid(
                    "The link's pairing token looks malformed.",
                )
            }
            val colon = pairHostPort.lastIndexOf(':')
            if (colon <= 0) {
                return PairLinkResult.Invalid(
                    "The link's pair endpoint needs host:port, like &pair=192.168.1.24:8787.",
                )
            }
            val pairHost = pairHostPort.substring(0, colon)
            val pairPort = pairHostPort.substring(colon + 1)
            val pairPortNumber = pairPort.toIntOrNull()
            if (pairHost.isEmpty() || pairPortNumber == null || pairPortNumber !in 1..65535) {
                return PairLinkResult.Invalid(
                    "The link's pair endpoint needs host:port, like &pair=192.168.1.24:8787.",
                )
            }
            PairParams(pairHost, pairPort, token)
        }

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

        return PairLinkResult.Parsed(host = host, port = port, user = decodedUser, pair = pair)
    }
}
