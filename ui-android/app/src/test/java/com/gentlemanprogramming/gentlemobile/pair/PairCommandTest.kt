package com.gentlemanprogramming.gentlemobile.pair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The host command block on the Pair screen shows exactly the command the
 * host runs; the string is built in one place so paste and screen agree.
 */
class PairCommandTest {

    @Test
    fun `host tool is gentle-mobile with pair as the subcommand`() {
        assertEquals("gentle-mobile", PairCommand.HOST_TOOL)
    }

    @Test
    fun `wraps the key in single quotes`() {
        assertEquals(
            "gentle-mobile pair --add-key 'ssh-ed25519 AAAAC3Nza demo@gentle'",
            PairCommand.addKey("ssh-ed25519 AAAAC3Nza demo@gentle"),
        )
    }

    @Test
    fun `trims whitespace around the pasted key`() {
        assertEquals(
            "gentle-mobile pair --add-key 'ssh-ed25519 AAAAC3Nza'",
            PairCommand.addKey("  ssh-ed25519 AAAAC3Nza \n"),
        )
    }

    @Test
    fun `refuses an empty key`() {
        assertThrows(IllegalArgumentException::class.java) {
            PairCommand.addKey("   ")
        }
    }

    @Test
    fun `refuses a key containing a quote`() {
        assertThrows(IllegalArgumentException::class.java) {
            PairCommand.addKey("ssh-ed25519 AAA'demo")
        }
    }
}
