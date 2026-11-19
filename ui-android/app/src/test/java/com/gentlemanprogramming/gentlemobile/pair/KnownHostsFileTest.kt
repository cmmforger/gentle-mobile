package com.gentlemanprogramming.gentlemobile.pair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-host trust (S24-4): every host gets its **own** known_hosts file, with
 * a safe filename derived from host and port. Pinning one machine must never
 * satisfy another, so two entries that only differ in address — or in odd
 * characters — must never collapse onto the same file. Pure: no Android.
 */
class KnownHostsFileTest {

    // --- the two hosts the emulator verification uses -------------------------------

    @Test
    fun `the same machine twice under two addresses gets two files`() {
        val a = KnownHostsFile.fileName("127.0.0.1", 2222)
        val b = KnownHostsFile.fileName("10.0.2.2", 2222)
        assertNotEquals(a, b)
    }

    @Test
    fun `the same host on two ports gets two files`() {
        val a = KnownHostsFile.fileName("10.0.2.2", 22)
        val b = KnownHostsFile.fileName("10.0.2.2", 2222)
        assertNotEquals(a, b)
    }

    // --- readable and safe ------------------------------------------------------------

    @Test
    fun `a plain host keeps its readable name with the port`() {
        assertEquals(
            "known_hosts-10.0.2.2-2222-" + KnownHostsFile.suffix("10.0.2.2", 2222),
            KnownHostsFile.fileName("10.0.2.2", 2222),
        )
    }

    @Test
    fun `odd characters never reach the filename`() {
        val name = KnownHostsFile.fileName("a/b c:d?e", 22)
        assertTrue("no slash", !name.contains('/'))
        assertTrue("no space", !name.contains(' '))
        assertTrue("no colon", !name.contains(':'))
        assertTrue("no question mark", !name.contains('?'))
    }

    @Test
    fun `two hosts that sanitize to the same word still get different files`() {
        // 'a/b' and 'a\b' both sanitize to 'a_b'; only the address hash keeps
        // them apart — one pin must never satisfy the other.
        val a = KnownHostsFile.fileName("a/b", 22)
        val b = KnownHostsFile.fileName("a\\b", 22)
        assertNotEquals(a, b)
    }

    @Test
    fun `an ipv6 address produces a safe filename`() {
        val name = KnownHostsFile.fileName("2001:db8::1", 22)
        assertTrue(!name.contains(':'))
        assertTrue(name.startsWith("known_hosts-"))
    }

    // --- determinism and length ---------------------------------------------------------

    @Test
    fun `the derivation is deterministic`() {
        assertEquals(
            KnownHostsFile.fileName("10.0.2.2", 2222),
            KnownHostsFile.fileName("10.0.2.2", 2222),
        )
    }

    @Test
    fun `two long hosts sharing a long prefix still differ`() {
        val longA = "desktop-${"a".repeat(60)}.example"
        val longB = "desktop-${"b".repeat(60)}.example"
        val a = KnownHostsFile.fileName(longA, 22)
        val b = KnownHostsFile.fileName(longB, 22)
        assertNotEquals(a, b)
        // Long names stay filesystem-friendly: bounded length.
        assertTrue(a.length < 120)
        assertTrue(b.length < 120)
    }

    @Test
    fun `the filename always starts with the known_hosts prefix`() {
        assertFalse(KnownHostsFile.fileName("x", 22).isEmpty())
        assertTrue(KnownHostsFile.fileName("x", 22).startsWith("known_hosts-"))
        assertTrue(KnownHostsFile.fileName("", 22).startsWith("known_hosts-"))
    }
}
