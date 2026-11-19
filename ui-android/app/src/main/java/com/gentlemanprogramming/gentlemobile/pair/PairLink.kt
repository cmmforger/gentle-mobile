package com.gentlemanprogramming.gentlemobile.pair

/**
 * The pairing link `scripts/gentle-mobile` prints on the host and the QR
 * encodes. The one-scan form carries the pairing listener and its one-time
 * token: `gc://host:port?user=name&pair=pairHost:pairPort&t=token`.
 * Since S25-3 it may also carry `v=1` (format version — a version the app
 * does not understand is refused with the "update the app" message) and
 * `fp=<64 hex>` (the host key fingerprint, shown before connecting and
 * enforced at the handshake). Without `pair`/`t` it is the legacy paste
 * form. Parsing is pure Kotlin (no android.net.Uri) so it is unit-testable
 * on the JVM and refuses anything else with a readable reason — never a
 * silent no-op — and always **locally, before any native call** (S25-4),
 * so a foreign QR never stalls the camera preview.
 */
sealed interface PairLinkResult {
    data class Parsed(
        val host: String,
        val port: String,
        val user: String,
        /** Present only on a one-scan link: where to POST the key, and the token. */
        val pair: PairParams? = null,
        /** Present when the link carries `fp=`: the host key fingerprint,
         *  64 lowercase hex. Shown before connecting and enforced. */
        val fingerprint: String? = null,
    ) : PairLinkResult

    /** A refusal: its typed reason (with the taxonomy family) and the
     *  localized message built by [PairLink.parse]'s message builder. */
    data class Invalid(val reason: PairLink.InvalidReason, val message: String) : PairLinkResult {
        /** The taxonomy family the refusal belongs to (S25-4). */
        val reasonFamily: PairLink.Family get() = reason.family
    }
}

/** The single-use pairing listener endpoint plus its one-time token. */
data class PairParams(val host: String, val port: String, val token: String)

object PairLink {

    /**
     * The QR error taxonomy (S25-4): three families a foreign camera
     * preview must be able to answer locally — not our code, a code that
     * is ours but broken, and a code from a newer version. Every concrete
     * refusal belongs to exactly one family and carries its own message.
     */
    enum class Family { FOREIGN, BROKEN, NEWER }

    /**
     * Every refusal this parser can produce, each carrying the English
     * default the pure tests assert (S24-1) and its [Family]. [parse]'s
     * [invalidMessage] parameter lets the UI build the same refusal in the
     * user's language.
     */
    enum class InvalidReason(val family: Family, val default: String) {
        EMPTY(Family.FOREIGN, "Paste the link printed by gentle-mobile pair (gc://host:port?user=…)."),
        NOT_GC(
            Family.FOREIGN,
            "That is not a gc:// link — copy the “Connection link” line the pair command prints.",
        ),
        MISSING_QUERY(Family.BROKEN, "The link is missing ?user=… — copy the full connection link."),
        TOKEN_WITHOUT_PAIR(
            Family.BROKEN,
            "The link carries a pairing token but no &pair=host:port — copy the full link.",
        ),
        PAIR_WITHOUT_TOKEN(
            Family.BROKEN,
            "The link carries a pairing endpoint but no &t=token — copy the full link.",
        ),
        BAD_TOKEN(Family.BROKEN, "The link's pairing token looks malformed."),
        BAD_PAIR_HOSTPORT(
            Family.BROKEN,
            "The link's pair endpoint needs host:port, like &pair=192.168.1.24:8787.",
        ),
        BAD_AUTHORITY(
            Family.BROKEN,
            "The link needs host:port before the ?, like gc://192.168.1.24:22?user=me.",
        ),
        BAD_HOST_NAME(Family.BROKEN, "The link's host name looks malformed."),
        BAD_PORT(Family.BROKEN, "The link's port must be a number from 1 to 65535."),
        MISSING_USER(Family.BROKEN, "The link is missing ?user=… — copy the full connection link."),
        BAD_USER_URL(Family.BROKEN, "The link's user name is not valid URL text."),
        BAD_USER_NAME(Family.BROKEN, "The link's user name looks malformed."),
        UNSUPPORTED_VERSION(
            Family.NEWER,
            "That link was made by a newer Gentle Mobile — update the app and try again.",
        ),
        BAD_FINGERPRINT(
            Family.BROKEN,
            "The fingerprint in the link must be 64 hexadecimal characters — copy the full link.",
        ),
    }

    fun parse(raw: String): PairLinkResult = parse(raw) { it.default }

    /** Same parse, with the refusal messages built by [invalidMessage]. */
    fun parse(
        raw: String,
        invalidMessage: (InvalidReason) -> String,
    ): PairLinkResult {
        fun invalid(reason: InvalidReason) = PairLinkResult.Invalid(reason, invalidMessage(reason))
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

        // The parameters: the one-scan pair endpoint + token, the format
        // version, and the host key fingerprint. Either half of the pair
        // must be refused, never silently downgraded to the legacy form.
        var pairParam: String? = null
        var tokenParam: String? = null
        var versionParam: String? = null
        var fingerprintParam: String? = null
        for (part in query.split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            when (part.substring(0, eq)) {
                "pair" -> pairParam = part.substring(eq + 1)
                "t" -> tokenParam = part.substring(eq + 1)
                "v" -> versionParam = part.substring(eq + 1)
                "fp" -> fingerprintParam = part.substring(eq + 1)
            }
        }
        // S25-3: a version the app does not understand is refused with the
        // "update the app" message — locally, before anything else runs.
        if (versionParam != null && versionParam != "1") {
            return invalid(InvalidReason.UNSUPPORTED_VERSION)
        }
        val fingerprint = fingerprintParam?.let {
            val lowered = it.lowercase()
            if (!FingerprintCheck.isHex64(lowered)) {
                return invalid(InvalidReason.BAD_FINGERPRINT)
            }
            lowered
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

        return PairLinkResult.Parsed(
            host = host,
            port = port,
            user = decodedUser,
            pair = pair,
            fingerprint = fingerprint,
        )
    }
}
