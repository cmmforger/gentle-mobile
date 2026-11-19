package com.gentlemanprogramming.gentlemobile.pair

import java.util.Base64

/**
 * The `fp=` link parameter (S25-3): the host's key fingerprint travels in
 * the QR so the phone can show it **before connecting** and refuse on a
 * mismatch — fail closed. The link carries 64 lowercase hex (SHA-256
 * digest); the core's challenge carries the OpenSSH `SHA256:base64` form.
 * Pure: no Android, unit-tested on the JVM.
 */
object FingerprintCheck {

    /** The comparison of the link's fingerprint against what the host presented. */
    sealed interface Verdict {
        data object Match : Verdict
        data class Mismatch(val linkFingerprint: String, val presented: String) : Verdict
    }

    /** Exactly 64 hexadecimal characters — the only form the link accepts. */
    fun isHex64(value: String): Boolean =
        value.length == 64 && value.all { Character.digit(it, 16) >= 0 }

    /**
     * Turn 64 hex into the `SHA256:…` form the host's tools print, so the
     * user compares like with like. Null when [hex] is not 64 hex.
     */
    fun hexToOpenSsh(hex: String): String? {
        if (!isHex64(hex)) return null
        val digest = ByteArray(32)
        for (i in 0 until 32) {
            digest[i] = ((Character.digit(hex[2 * i], 16) shl 4) +
                Character.digit(hex[2 * i + 1], 16)).toByte()
        }
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    /**
     * Compare the link's fingerprint with the challenge fingerprint the
     * core read off the wire. A challenge the phone cannot read is a
     * [Verdict.Mismatch]: with a pinned fingerprint, refusing is the only
     * safe answer.
     */
    fun compare(linkFingerprint: String, challengeFingerprint: String): Verdict {
        val expected = hexToOpenSsh(linkFingerprint) ?: return Verdict.Mismatch(
            linkFingerprint,
            challengeFingerprint,
        )
        return if (challengeFingerprint == expected) {
            Verdict.Match
        } else {
            Verdict.Mismatch(linkFingerprint, challengeFingerprint)
        }
    }
}
