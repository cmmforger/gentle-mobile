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

    /**
     * Every refusal this parser can produce, each carrying the English
     * default the pure tests assert (S24-1). [parse]'s [invalidMessage]
     * parameter lets the UI build the same refusal in the user's language.
     */
    enum class InvalidReason(val default: String) {
        EMPTY("Paste the link printed by gentle-mobile-pair (gc://host:port?user=…)."),
        NOT_GC("That is not a gc:// link — copy the “Connection link” line the pair command prints."),
        MISSING_QUERY("The link is missing ?user=… — copy the full connection link."),
        TOKEN_WITHOUT_PAIR("The link carries a pairing token but no &pair=host:port — copy the full link."),
        PAIR_WITHOUT_TOKEN("The link carries a pairing endpoint but no &t=token — copy the full link."),
        BAD_TOKEN("The link's pairing token looks malformed."),
        BAD_PAIR_HOSTPORT("The link's pair endpoint needs host:port, like &pair=192.168.1.24:8787."),
        BAD_AUTHORITY("The link needs host:port before the ?, like gc://192.168.1.24:22?user=me."),
        BAD_HOST_NAME("The link's host name looks malformed."),
        BAD_PORT("The link's port must be a number from 1 to 65535."),
        MISSING_USER("The link is missing ?user=… — copy the full connection link."),
        BAD_USER_URL("The link's user name is not valid URL text."),
        BAD_USER_NAME("The link's user name looks malformed."),
    }

    fun parse(raw: String): PairLinkResult = parse(raw) { it.default }

    /** Same parse, with the refusal messages built by [invalidMessage]. */
    fun parse(
        raw: String,
        invalidMessage: (InvalidReason) -> String,
    ): PairLinkResult {
        fun invalid(reason: InvalidReason) = PairLinkResult.Invalid(invalidMessage(reason))
        val text = raw.trim()
        if (text.isEmpty()) {
            return invalid(InvalidReason.EMPTY)
        }
        if (!text.startsWith("gc://")) {
            return invalid(InvalidReason.NOT_GC)
        }
        val rest = text.removePrefix("gc://")
        val queryStart = rest.indexOf('?')
        if (queryStart < 0) {
            return invalid(InvalidReason.MISSING_QUERY)
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
            val pairHostPort = pairParam ?: return invalid(InvalidReason.TOKEN_WITHOUT_PAIR)
            val token = tokenParam ?: return invalid(InvalidReason.PAIR_WITHOUT_TOKEN)
            if (token.isEmpty()) {
                return invalid(InvalidReason.BAD_TOKEN)
            }
            val colon = pairHostPort.lastIndexOf(':')
            if (colon <= 0) {
                return invalid(InvalidReason.BAD_PAIR_HOSTPORT)
            }
            val pairHost = pairHostPort.substring(0, colon)
            val pairPort = pairHostPort.substring(colon + 1)
            val pairPortNumber = pairPort.toIntOrNull()
            if (pairHost.isEmpty() || pairPortNumber == null || pairPortNumber !in 1..65535) {
                return invalid(InvalidReason.BAD_PAIR_HOSTPORT)
            }
            PairParams(pairHost, pairPort, token)
        }

        val portStart = authority.lastIndexOf(':')
        if (portStart <= 0) {
            return invalid(InvalidReason.BAD_AUTHORITY)
        }
        val host = authority.substring(0, portStart)
        val port = authority.substring(portStart + 1)
        if (host.isEmpty() || host.contains('/') || host.any { it.isWhitespace() }) {
            return invalid(InvalidReason.BAD_HOST_NAME)
        }
        val portNumber = port.toIntOrNull()
        if (portNumber == null || portNumber !in 1..65535) {
            return invalid(InvalidReason.BAD_PORT)
        }

        val user = query.split('&').firstNotNullOfOrNull { part ->
            val eq = part.indexOf('=')
            if (eq > 0 && part.substring(0, eq) == "user") part.substring(eq + 1) else null
        } ?: return invalid(InvalidReason.MISSING_USER)
        val decodedUser = runCatching { java.net.URLDecoder.decode(user, "UTF-8") }.getOrNull()
            ?: return invalid(InvalidReason.BAD_USER_URL)
        if (decodedUser.isEmpty()) {
            return invalid(InvalidReason.BAD_USER_NAME)
        }

        return PairLinkResult.Parsed(host = host, port = port, user = decodedUser, pair = pair)
    }
}
