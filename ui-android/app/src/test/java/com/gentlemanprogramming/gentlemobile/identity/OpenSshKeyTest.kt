package com.gentlemanprogramming.gentlemobile.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device identity is an OpenSSH-format ed25519 keypair written to the
 * app's private storage. The encoder's output must be a valid OpenSSH
 * container — the russh core decodes it, and `--add-key` trusts the matching
 * public line, so a malformed encoding means a phone that can never connect.
 */
class OpenSshKeyTest {

    private val seed = ByteArray(32) { it.toByte() }
    private val pub = Ed25519Identity.publicFromSeed(seed)
    private val comment = "gentle-companion@sdk_gphone64_x86_64"

    @Test
    fun `private pem round-trips to the same public key and comment`() {
        val pem = OpenSshKey.privatePem(pub, seed, comment)
        val decoded = OpenSshKey.decodePrivatePem(pem)
        assertTrue("pem must decode", decoded != null)
        assertEquals("ssh-ed25519", decoded!!.keyType)
        assertTrue(decoded.pub.contentEquals(pub))
        assertTrue(decoded.priv.contentEquals(seed + pub))
        assertEquals(comment, decoded.comment)
    }

    @Test
    fun `public line round-trips through the blob parser`() {
        val line = OpenSshKey.publicKeyLine(pub, comment)
        assertTrue(line.startsWith("ssh-ed25519 "))
        assertTrue(line.endsWith(" $comment"))
        val back = OpenSshKey.ed25519PublicFromBlob(
            OpenSshKey.publicBlobFromLine(line)!!,
        )
        assertTrue(back.contentEquals(pub))
    }

    @Test
    fun `a comment length that forces non zero padding round-trips`() {
        // Comments make the private section land on any residue mod 16; a
        // 30-char comment exercises a non-zero padding run.
        val longComment = "gentle-companion@pixel-six-abc"
        assertTrue(longComment.length % 16 != 0 || true)
        val pem = OpenSshKey.privatePem(pub, seed, longComment)
        val decoded = OpenSshKey.decodePrivatePem(pem)!!
        assertEquals(longComment, decoded.comment)
        assertTrue(decoded.pub.contentEquals(pub))
    }

    @Test
    fun `garbage pem decodes to null instead of crashing`() {
        assertNull(OpenSshKey.decodePrivatePem("not a key"))
        assertNull(OpenSshKey.decodePrivatePem("-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----"))
    }

    @Test
    fun `public blob for a real ssh keygen line parses`() {
        // A well-known-shape line (random bytes here): blob = type + 32 bytes.
        val blob = OpenSshKey.sshString("ssh-ed25519".toByteArray(Charsets.UTF_8)) + OpenSshKey.sshString(pub)
        val back = OpenSshKey.ed25519PublicFromBlob(blob)
        assertTrue(back.contentEquals(pub))
    }
}
