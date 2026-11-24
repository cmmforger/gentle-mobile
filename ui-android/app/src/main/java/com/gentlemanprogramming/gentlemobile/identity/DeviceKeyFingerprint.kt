package com.gentlemanprogramming.gentlemobile.identity

import java.security.MessageDigest
import java.util.Base64

/**
 * This phone's own key fingerprint (T55): the OpenSSH `SHA256:…` form of
 * the device public key blob — the same string `ssh-keygen -lf` prints for
 * a key. The Hooks screen shows it instead of a fake token, so the human
 * sees the identity the host actually trusts. Pure, tested on the JVM.
 */
object DeviceKeyFingerprint {

    /** `SHA256:<base64>` over the wire-format public key blob, or null when
     *  [publicKeyLine] is not an OpenSSH public key line. */
    fun fingerprint(publicKeyLine: String): String? {
        val blob = OpenSshKey.publicBlobFromLine(publicKeyLine) ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    /** The key type — the first token of an OpenSSH public line. */
    fun keyType(publicKeyLine: String): String? =
        publicKeyLine.trim().split(Regex("\\s+")).getOrNull(0)

    /** The base64 key blob — the second token, what the host's
     *  `authorized_keys` check compares against (comment-insensitive). */
    fun keyBlob(publicKeyLine: String): String? =
        publicKeyLine.trim().split(Regex("\\s+")).getOrNull(1)
}
