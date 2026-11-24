package com.gentlemanprogramming.gentlemobile.identity

import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings → Hooks screen shows this phone's key fingerprint (T55) —
 * the OpenSSH `SHA256:…` form of the device public key blob, the same
 * string `ssh-keygen -lf` prints for a key. Pure, tested on the JVM.
 */
class DeviceKeyFingerprintTest {

    private fun reference(pub: ByteArray): String =
        "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(OpenSshKey.publicBlob(pub)))

    @Test
    fun `fingerprint is the openssh sha256 of the key blob`() {
        val pub = ByteArray(32) { (it + 1).toByte() }
        val line = OpenSshKey.publicKeyLine(pub, "gentle-mobile@test")
        assertEquals(reference(pub), DeviceKeyFingerprint.fingerprint(line))
    }

    @Test
    fun `fingerprint is insensitive to the trailing comment`() {
        val pub = ByteArray(32) { (it * 7 + 3).toByte() }
        val a = OpenSshKey.publicKeyLine(pub, "gentle-mobile@phone")
        val b = OpenSshKey.publicKeyLine(pub, "gentle-mobile-20261120-115353")
        assertEquals(DeviceKeyFingerprint.fingerprint(a), DeviceKeyFingerprint.fingerprint(b))
    }

    @Test
    fun `fingerprint has the fixed openssh shape`() {
        val pub = ByteArray(32) { it.toByte() }
        val line = OpenSshKey.publicKeyLine(pub, "gentle-mobile@test")
        assertTrue(
            DeviceKeyFingerprint.fingerprint(line)!!.matches(Regex("SHA256:[A-Za-z0-9+/]{43}")),
        )
    }

    @Test
    fun `a line that is not a key has no fingerprint`() {
        assertNull(DeviceKeyFingerprint.fingerprint("not a key"))
        assertNull(DeviceKeyFingerprint.fingerprint(""))
    }

    @Test
    fun `key type and blob are the first two tokens`() {
        val pub = ByteArray(32) { (it + 9).toByte() }
        val line = OpenSshKey.publicKeyLine(pub, "gentle-mobile@test")
        assertEquals("ssh-ed25519", DeviceKeyFingerprint.keyType(line))
        val blob = OpenSshKey.publicBlobFromLine(line)!!
        val expectedBlob = Base64.getEncoder().encodeToString(blob)
        assertEquals(expectedBlob, DeviceKeyFingerprint.keyBlob(line))
    }
}
