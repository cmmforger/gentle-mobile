package com.gentlemanprogramming.gentlemobile.pair

/**
 * The Connection form's validator (S25-5): pure, framework-free, and one
 * exact message per field — never a generic error. The rules are the ones
 * the reviewed repo made testable and we adopt: host as IPv4/IPv6/hostname,
 * port 1–65535, user without spaces or `@`. The English defaults live on
 * [Reason]; the message-builder overloads let the UI show the same
 * refusals in the user's language (S24-1) without Android anywhere here.
 */
object ConnectValidator {

    /** The one field each refusal belongs to. */
    enum class Field { HOST, PORT, USER }

    /**
     * Every refusal, each carrying the English default the pure tests
     * assert verbatim (S24-1).
     */
    enum class Reason(val field: Field, val default: String) {
        HOST_EMPTY(Field.HOST, "Write the IP or host of your computer."),
        HOST_INVALID(
            Field.HOST,
            "That host is not an IP or a host name — write it like 192.168.1.24.",
        ),
        PORT_INVALID(Field.PORT, "The port must be a number from 1 to 65535."),
        USER_EMPTY(Field.USER, "Write your user on that computer."),
        USER_INVALID(Field.USER, "The user cannot contain spaces or @."),
    }

    private val hostNameLabel = Regex("[A-Za-z0-9]([A-Za-z0-9_-]{0,61}[A-Za-z0-9])?")

    /** Host: empty → [Reason.HOST_EMPTY]; IPv4, IPv6 or hostname otherwise. */
    fun hostReason(raw: String): Reason? = checkHost(raw)

    /** Host as a message, in English by default. */
    fun host(raw: String): String? = host(raw) { it.default }

    /** Same rule, with the refusal built by [message] for the UI's language. */
    fun host(raw: String, message: (Reason) -> String): String? =
        hostReason(raw)?.let(message)

    /** Port: trimmed, integer, 1–65535. */
    fun portReason(raw: String): Reason? {
        val number = raw.trim().toIntOrNull()
        return if (number == null || number !in 1..65535) Reason.PORT_INVALID else null
    }

    /** Port as a message, in English by default. */
    fun port(raw: String): String? = port(raw) { it.default }

    /** Same rule, with the refusal built by [message] for the UI's language. */
    fun port(raw: String, message: (Reason) -> String): String? =
        portReason(raw)?.let(message)

    /** User: non-empty, no spaces, no `@`. */
    fun userReason(raw: String): Reason? {
        val trimmed = raw.trim()
        return when {
            trimmed.isEmpty() -> Reason.USER_EMPTY
            ' ' in trimmed || '\t' in trimmed || '@' in trimmed -> Reason.USER_INVALID
            else -> null
        }
    }

    /** User as a message, in English by default. */
    fun user(raw: String): String? = user(raw) { it.default }

    /** Same rule, with the refusal built by [message] for the UI's language. */
    fun user(raw: String, message: (Reason) -> String): String? =
        userReason(raw)?.let(message)

    private fun checkHost(raw: String): Reason? {
        val host = raw.trim()
        if (host.isEmpty()) return Reason.HOST_EMPTY
        if (isIpv6(host)) return null
        // A dotted all-numeric string is an IPv4 attempt, never a host
        // name: `256.1.1.1` must be refused, not read as four labels.
        if (isIpv4Shape(host)) {
            return if (isIpv4(host)) null else Reason.HOST_INVALID
        }
        return if (isHostName(host)) null else Reason.HOST_INVALID
    }

    private fun isIpv4Shape(host: String): Boolean =
        host.contains('.') &&
            host.split('.').all { part -> part.isNotEmpty() && part.all { it.isDigit() } }

    private fun isIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.length in 1..3 && part.all { it.isDigit() } && part.toInt() <= 255
        }
    }

    private fun isIpv6(host: String): Boolean {
        // Standard compressed/uncompressed forms, an optional zone id after
        // `%`, and an IPv4 tail (`::ffff:192.168.0.1`). Fail closed: any
        // shape this does not recognise is not an address.
        val lowered = host.lowercase()
        val body = lowered.substringBefore('%')
        if (lowered.contains('%')) {
            val zone = lowered.substringAfter('%')
            if (zone.isEmpty() || zone.any { !isAlnum(it) }) return false
        }
        var text = body
        var extraGroups = 0
        if (text.contains('.')) {
            val lastColon = text.lastIndexOf(':')
            if (lastColon < 0) return false
            if (!isIpv4(text.substring(lastColon + 1))) return false
            text = text.substring(0, lastColon)
            extraGroups = 2
        }
        val idx = text.indexOf("::")
        val hexGroups: Int
        val compressed: Boolean
        if (idx >= 0) {
            compressed = true
            val left = text.substring(0, idx)
            val right = text.substring(idx + 2)
            // Around the single compression, no side may carry a dangling
            // colon (that is where a second "::" would hide).
            if (left.contains(':') && (left.startsWith(':') || left.endsWith(':'))) return false
            if (right.contains(':') && (right.startsWith(':') || right.endsWith(':'))) return false
            val leftGroups = countGroups(left)
            val rightGroups = countGroups(right)
            if (leftGroups < 0 || rightGroups < 0) return false
            hexGroups = leftGroups + rightGroups
        } else {
            compressed = false
            if (text.startsWith(':') || text.endsWith(':')) return false
            hexGroups = countGroups(text)
            if (hexGroups < 0) return false
        }
        val total = hexGroups + extraGroups
        // A compressed address omits at least one group; an uncompressed
        // one must name all eight.
        return if (compressed) total <= 7 else total == 8
    }

    /** The number of valid hex groups in a `:`-separated chunk, or -1 if
     *  any piece is empty, too long, or not hexadecimal. */
    private fun countGroups(chunk: String): Int {
        if (chunk.isEmpty()) return 0
        val pieces = chunk.split(':')
        for (piece in pieces) {
            if (piece.isEmpty() || piece.length > 4) return -1
            if (piece.any { Character.digit(it, 16) < 0 }) return -1
        }
        return pieces.size
    }

    private fun isHostName(host: String): Boolean {
        if (host.length > 253) return false
        val labels = host.split('.')
        return labels.isNotEmpty() && labels.all { hostNameLabel.matches(it) }
    }

    private fun isAlnum(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'
}
