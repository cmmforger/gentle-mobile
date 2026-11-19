package com.gentlemanprogramming.gentlemobile.pair

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The phone side of the one-scan pairing (S22): POST only this phone's
 * public key and the one-time token to the host's single-use listener —
 * `POST http://pairHost:pairPort/pair` with
 * `{"token": …, "key": …}`. No password, no private key, no other field.
 *
 * The host's refusal codes (401 wrong token, 403 expired, 409 already
 * used, 400 not a key) become readable messages — never a silent spinner.
 * Blocking I/O; the caller owns the thread. `parseReply` and `jsonEscape`
 * are pure so the mapping is unit-testable on the JVM.
 */
object PairClient {

    sealed interface Result {
        /** The host trusted the key. `alreadyTrusted` means nothing was written. */
        data class Accepted(val alreadyTrusted: Boolean) : Result

        /** The host refused: token, expiry, a consumed pairing, or a bad key. */
        data class Refused(val message: String) : Result
    }

    const val CONNECT_TIMEOUT_MS = 10_000

    /** Minimal JSON string escaping for the token and the key line. */
    fun jsonEscape(value: String): String {
        val out = StringBuilder(value.length + 8)
        for (c in value) {
            when (c) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else ->
                    if (c < ' ') {
                        out.append("\\u").append(String.format("%04x", c.code))
                    } else {
                        out.append(c)
                    }
            }
        }
        return out.toString()
    }

    /**
     * POST the key. Returns [Result.Accepted] when the host wrote it, or
     * [Result.Refused] with the reason. A network failure (listener gone,
     * TTL over, unreachable host) is a refusal too, with its own message.
     */
    fun sendKey(pairHost: String, pairPort: String, token: String, publicKeyLine: String): Result =
        sendKey(pairHost, pairPort, token, publicKeyLine, ::defaultUnreachable, ::defaultRefusal)

    /** Same request, with the refusal messages built by [unreachable] and
     *  [refusal] so the UI can show them in the user's language (S24-1). */
    fun sendKey(
        pairHost: String,
        pairPort: String,
        token: String,
        publicKeyLine: String,
        unreachable: (pairHost: String, pairPort: String, reason: String) -> String,
        refusal: (status: Int, detail: String?) -> String,
    ): Result {
        val body = "{\"token\":\"${jsonEscape(token)}\",\"key\":\"${jsonEscape(publicKeyLine)}\"}"
        return try {
            val connection = URL("http://$pairHost:$pairPort/pair").openConnection()
                as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = CONNECT_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setFixedLengthStreamingMode(body.toByteArray(Charsets.UTF_8).size)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val reply = readBody(connection)
            parseReply(status, reply, refusal)
        } catch (error: IOException) {
            Result.Refused(
                unreachable(pairHost, pairPort, error.message ?: "connection failed"),
            )
        }
    }

    /** The default (English) unreachable refusal; the pure tests rely on it. */
    fun defaultUnreachable(pairHost: String, pairPort: String, reason: String): String =
        "Could not reach the pairing endpoint at $pairHost:$pairPort — " +
            "run gentle-mobile-pair on the host and start again. ($reason)"

    private fun readBody(connection: HttpURLConnection): String =
        (connection.inputStream ?: connection.errorStream)?.use { it.readBytes() }
            ?.toString(Charsets.UTF_8).orEmpty()

    /** Map the listener's status + JSON body to a result with a readable message. */
    fun parseReply(status: Int, body: String): Result =
        parseReply(status, body, ::defaultRefusal)

    /** Same mapping, with the refusal message built by [refusal] for the
     *  UI's language (S24-1). */
    fun parseReply(
        status: Int,
        body: String,
        refusal: (status: Int, detail: String?) -> String,
    ): Result {
        val error = jsonStringField(body, "error")
        if (status in 200..299) {
            if (jsonStringField(body, "written") == "already trusted") {
                return Result.Accepted(alreadyTrusted = true)
            }
            return Result.Accepted(alreadyTrusted = false)
        }
        val detail = error?.takeIf { it.isNotBlank() }
        return Result.Refused(refusal(status, detail))
    }

    /** The default (English) refusal per status; the pure tests rely on it. */
    fun defaultRefusal(status: Int, detail: String?): String {
        val suffix = detail?.let { " — $it" }.orEmpty()
        return when (status) {
            401 -> "The host refused the token — this link does not match the running gentle-mobile-pair." + suffix
            403 -> "Pairing expired — run gentle-mobile-pair on the host and scan or paste the new link." + suffix
            409 -> "That pairing was already used — run gentle-mobile-pair on the host for a new one." + suffix
            400 -> "The host refused the key" + (detail?.let { ": $it" } ?: " as malformed.")
            else -> "The host refused the pairing (HTTP $status)" + (detail?.let { ": $it" } ?: ".")
        }
    }

    /** Read one string field out of the listener's small JSON replies. */
    fun jsonStringField(body: String, field: String): String? {
        val key = "\"$field\""
        val keyIndex = body.indexOf(key)
        if (keyIndex < 0) return null
        var i = body.indexOf(':', keyIndex + key.length)
        if (i < 0) return null
        i += 1
        while (i < body.length && body[i].isWhitespace()) i += 1
        if (i >= body.length || body[i] != '"') return null
        i += 1
        val out = StringBuilder()
        while (i < body.length) {
            when (val c = body[i]) {
                '"' -> return out.toString()
                '\\' -> {
                    i += 1
                    if (i >= body.length) return null
                    when (val e = body[i]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'u' -> {
                            if (i + 4 >= body.length) return null
                            val code = body.substring(i + 1, i + 5).toIntOrNull(16) ?: return null
                            out.append(code.toChar())
                            i += 4
                        }
                        else -> out.append(e)
                    }
                }
                else -> out.append(c)
            }
            i += 1
        }
        return null
    }
}
