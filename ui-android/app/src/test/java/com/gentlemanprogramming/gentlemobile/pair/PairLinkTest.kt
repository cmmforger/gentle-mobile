package com.gentlemanprogramming.gentlemobile.pair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gc:// link is what `scripts/gentle-mobile-pair` prints on the host
 * and what the QR encodes: `gc://host:port?user=name`. Parsing must fill the
 * connect form or refuse with a readable reason — never a silent no-op.
 */
class PairLinkTest {

    @Test
    fun `parses the link the host script prints`() {
        val result = PairLink.parse("gc://10.0.2.2:2222?user=erickfm")
        assertEquals(PairLinkResult.Parsed("10.0.2.2", "2222", "erickfm"), result)
    }

    @Test
    fun `parses a tailscale host name and a url-encoded user`() {
        val result = PairLink.parse("gc://erick-laptop.tail1234.ts.net:22?user=eri%20ck")
        assertEquals(PairLinkResult.Parsed("erick-laptop.tail1234.ts.net", "22", "eri ck"), result)
    }

    @Test
    fun `accepts surrounding whitespace from a sloppy paste`() {
        val result = PairLink.parse("  gc://192.168.1.24:2222?user=erickfm \n")
        assertEquals(PairLinkResult.Parsed("192.168.1.24", "2222", "erickfm"), result)
    }

    @Test
    fun `empty paste is refused with a reason`() {
        val result = PairLink.parse("   ")
        assertTrue(result is PairLinkResult.Invalid)
        assertTrue((result as PairLinkResult.Invalid).reason.isNotBlank())
    }

    @Test
    fun `a non gc scheme is refused with a reason`() {
        val result = PairLink.parse("ssh://10.0.2.2:2222?user=erickfm")
        assertTrue(result is PairLinkResult.Invalid)
        assertTrue((result as PairLinkResult.Invalid).reason.contains("gc://"))
    }

    @Test
    fun `plain text without a link is refused`() {
        val result = PairLink.parse("hello world")
        assertTrue(result is PairLinkResult.Invalid)
    }

    @Test
    fun `missing port is refused`() {
        val result = PairLink.parse("gc://10.0.2.2?user=erickfm")
        assertTrue(result is PairLinkResult.Invalid)
    }

    @Test
    fun `port zero and port 70000 are refused`() {
        assertTrue(PairLink.parse("gc://h:0?user=u") is PairLinkResult.Invalid)
        assertTrue(PairLink.parse("gc://h:70000?user=u") is PairLinkResult.Invalid)
        assertTrue(PairLink.parse("gc://h:abc?user=u") is PairLinkResult.Invalid)
    }

    @Test
    fun `missing host is refused`() {
        assertTrue(PairLink.parse("gc://:2222?user=u") is PairLinkResult.Invalid)
    }

    @Test
    fun `missing user query is refused`() {
        val result = PairLink.parse("gc://10.0.2.2:2222")
        assertTrue(result is PairLinkResult.Invalid)
    }

    @Test
    fun `blank user is refused`() {
        val result = PairLink.parse("gc://10.0.2.2:2222?user=")
        assertTrue(result is PairLinkResult.Invalid)
    }
}
