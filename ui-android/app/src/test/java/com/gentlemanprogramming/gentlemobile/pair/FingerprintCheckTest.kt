package com.gentlemanprogramming.gentlemobile.pair

import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `fp=` link parameter (S25-3): the host's key fingerprint travels in
 * the QR so the phone can show it before connecting and refuse on a
 * mismatch — fail closed. Everything here is pure: the challenge
 * fingerprint is the core's `SHA256:` form, the link carries 64 hex.
 */
class FingerprintCheckTest {

    /** The reference digest, computed here — never a hand-typed truth. */
    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun opensshForm(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    @Test
    fun `hex converts to the openssh form the host prints`() {
        val bytes = byteArrayOf(0, 1, 2, 3)
        assertEquals(
            opensshForm(bytes),
            FingerprintCheck.hexToOpenSsh(sha256Hex(bytes)),
        )
    }

    @Test
    fun `the empty digest converts exactly`() {
        // sha256 of nothing, a known vector — proves the hex and base64
        // paths agree instead of both being wrong in the same way.
        assertEquals(
            "SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU",
            FingerprintCheck.hexToOpenSsh(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ),
        )
    }

    @Test
    fun `matching fingerprint compares equal`() {
        val bytes = "host-key-blob".toByteArray()
        val hex = sha256Hex(bytes)
        assertEquals(
            FingerprintCheck.Verdict.Match,
            FingerprintCheck.compare(hex, opensshForm(bytes)),
        )
    }

    @Test
    fun `an uppercase hex link still matches`() {
        val bytes = "host-key-blob".toByteArray()
        assertEquals(
            FingerprintCheck.Verdict.Match,
            FingerprintCheck.compare(sha256Hex(bytes).uppercase(), opensshForm(bytes)),
        )
    }

    @Test
    fun `a mismatched host is refused`() {
        val linkHex = sha256Hex("what the host printed".toByteArray())
        val presented = opensshForm("what the wire presented".toByteArray())
        val verdict = FingerprintCheck.compare(linkHex, presented)
        assertTrue(verdict is FingerprintCheck.Verdict.Mismatch)
        assertEquals(linkHex, (verdict as FingerprintCheck.Verdict.Mismatch).linkFingerprint)
        assertEquals(presented, verdict.presented)
    }

    @Test
    fun `a challenge the phone cannot read fails closed`() {
        // Not a SHA256: form, not decodable — refusing is the only safe
        // answer when the link pinned a fingerprint.
        val verdict = FingerprintCheck.compare(
            sha256Hex("host-key-blob".toByteArray()),
            "not-a-fingerprint",
        )
        assertTrue(verdict is FingerprintCheck.Verdict.Mismatch)
    }

    @Test
    fun `isHex64 accepts exactly 64 hex characters`() {
        assertTrue(FingerprintCheck.isHex64("a".repeat(64)))
        assertTrue(FingerprintCheck.isHex64("ABCDEF0123456789".repeat(4)))
        assertFalse(FingerprintCheck.isHex64("a".repeat(63)))
        assertFalse(FingerprintCheck.isHex64("a".repeat(65)))
        assertFalse(FingerprintCheck.isHex64("g".repeat(64)))
        assertFalse(FingerprintCheck.isHex64(""))
    }
}
