package com.gentlemanprogramming.gentlemobile.pair

import java.security.MessageDigest

/**
 * Per-host trust (S24-4): every host gets its **own** known_hosts file, so
 * pinning one machine can never satisfy another. The filename is derived
 * from host and port, deterministically: a readable sanitized body plus a
 * short hash of the exact `host:port`, so two different machines can never
 * collapse onto one trust store — not even hosts whose sanitized names would
 * otherwise look the same (`a/b` and `a\b` both sanitize to `a_b`, but their
 * files differ). Pure: no Android.
 */
object KnownHostsFile {

    /** Longest readable body before the hash; keeps filenames short enough
     *  for any filesystem while the hash keeps distinct hosts distinct. */
    private const val BODY_MAX = 40

    /** The per-host known_hosts filename for this exact host and port. */
    fun fileName(host: String, port: Int): String =
        "known_hosts-" + sanitize(host) + "-" + port + "-" + suffix(host, port)

    /** The hash that disambiguates: the first 8 hex chars of SHA-256 over
     *  the raw `host:port`, before any sanitizing. */
    fun suffix(host: String, port: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$host:$port".toByteArray(Charsets.UTF_8))
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }

    /** Only filesystem-safe characters survive: letters, digits, dot, dash,
     *  underscore. Everything else — slash, space, colon, Unicode — becomes
     *  an underscore. An empty host still yields a valid filename. */
    private fun sanitize(host: String): String =
        host.replace(Regex("[^A-Za-z0-9._-]"), "_").take(BODY_MAX).ifBlank { "host" }
}
