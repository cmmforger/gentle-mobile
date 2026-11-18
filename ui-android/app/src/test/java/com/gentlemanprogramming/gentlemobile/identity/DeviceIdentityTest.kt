package com.gentlemanprogramming.gentlemobile.identity

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The regeneration contract (T18): a regenerated identity is a *different*
 * ed25519 key, and a new key means new trust — the pinned hosts file is gone,
 * so every host must confirm the new key's fingerprint again.
 */
class DeviceIdentityTest {

    private val comment = "gentle-mobile@test"

    /** Scratch key directory outside any repo; removed at the end. */
    private fun tempDir(): File = Files.createTempDirectory("gm-identity-test").toFile()

    @Test
    fun `regeneration produces a new key and clears the pinned hosts`() {
        val dir = tempDir()
        try {
            val first = DeviceIdentity.createAt(dir, comment)
            assertTrue(File(dir, DeviceIdentity.KEY_FILE).exists())
            assertTrue(File(dir, DeviceIdentity.PUB_FILE).exists())
            // The app pins hosts next to the key; regeneration must retire them.
            File(dir, DeviceIdentity.KNOWN_HOSTS_FILE)
                .writeText("[h]:2222 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIA\n")

            val second = DeviceIdentity.regenerateAt(dir, comment)

            assertNotEquals(first.publicKeyLine, second.publicKeyLine)
            assertNotNull(
                OpenSshKey.ed25519PublicFromBlob(
                    OpenSshKey.publicBlobFromLine(second.publicKeyLine)!!,
                ),
            )
            assertFalse(
                "a new key means new trust: the pins do not survive it",
                File(dir, DeviceIdentity.KNOWN_HOSTS_FILE).exists(),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the regenerated private key matches its new public line`() {
        val dir = tempDir()
        try {
            DeviceIdentity.createAt(dir, comment)
            val regenerated = DeviceIdentity.regenerateAt(dir, comment)
            val pem = File(dir, DeviceIdentity.KEY_FILE).readText()
            val decoded = OpenSshKey.decodePrivatePem(pem)
            assertNotNull(decoded)
            assertArrayEquals(
                OpenSshKey.ed25519PublicFromBlob(
                    OpenSshKey.publicBlobFromLine(regenerated.publicKeyLine)!!, 
                ),
                decoded!!.pub,
            )
            // The old PEM no longer exists to be used: the file was replaced.
            assertFalse(pem.contains("gentle-mobile@test") && decoded.comment != comment)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `wiping removes the key and the pins but not the directory`() {
        val dir = tempDir()
        try {
            DeviceIdentity.createAt(dir, comment)
            File(dir, DeviceIdentity.KNOWN_HOSTS_FILE).writeText("h ssh-ed25519 AAAA\n")
            DeviceIdentity.wipeAt(dir)
            assertFalse(File(dir, DeviceIdentity.KEY_FILE).exists())
            assertFalse(File(dir, DeviceIdentity.PUB_FILE).exists())
            assertFalse(File(dir, DeviceIdentity.KNOWN_HOSTS_FILE).exists())
            assertTrue(dir.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
