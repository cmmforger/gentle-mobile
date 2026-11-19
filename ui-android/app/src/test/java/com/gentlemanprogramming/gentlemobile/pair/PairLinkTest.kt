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
        assertTrue((result as PairLinkResult.Invalid).message.isNotBlank())
    }

    @Test
    fun `a non gc scheme is refused with a reason`() {
        val result = PairLink.parse("ssh://10.0.2.2:2222?user=erickfm")
        assertTrue(result is PairLinkResult.Invalid)
        assertTrue((result as PairLinkResult.Invalid).message.contains("gc://"))
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

    // --- S22 one-scan pairing: the link also carries the pair endpoint and ---
    // --- the one-time token.                                              ---

    @Test
    fun `parses the one-scan link with pair endpoint and token`() {
        val result = PairLink.parse(
            "gc://100.96.18.28:22?user=erickfm&pair=192.168.1.24:8787&t=abc123",
        )
        assertEquals(
            PairLinkResult.Parsed(
                "100.96.18.28",
                "22",
                "erickfm",
                PairParams("192.168.1.24", "8787", "abc123"),
            ),
            result,
        )
    }

    @Test
    fun `pair endpoint without a token is refused`() {
        val result = PairLink.parse("gc://h:22?user=u&pair=192.168.1.24:8787")
        assertTrue(result is PairLinkResult.Invalid)
    }

    @Test
    fun `token without a pair endpoint is refused`() {
        val result = PairLink.parse("gc://h:22?user=u&t=abc123")
        assertTrue(result is PairLinkResult.Invalid)
    }

    @Test
    fun `a malformed pair port is refused`() {
        assertTrue(PairLink.parse("gc://h:22?user=u&pair=192.168.1.24:0&t=t") is PairLinkResult.Invalid)
        assertTrue(PairLink.parse("gc://h:22?user=u&pair=192.168.1.24&t=t") is PairLinkResult.Invalid)
        assertTrue(PairLink.parse("gc://h:22?user=u&pair=192.168.1.24:70000&t=t") is PairLinkResult.Invalid)
    }

    @Test
    fun `an empty token is refused`() {
        assertTrue(PairLink.parse("gc://h:22?user=u&pair=192.168.1.24:8787&t=") is PairLinkResult.Invalid)
    }

    // --- S25-3: the link gains v=1 (format version) and fp=<64 hex> (the ---
    // --- host key fingerprint shown before connecting). Old links keep   ---
    // --- parsing unchanged.                                              ---

    @Test
    fun `a link without v and fp still parses as before`() {
        val result = PairLink.parse("gc://10.0.2.2:2222?user=erickfm")
        assertEquals(
            PairLinkResult.Parsed("10.0.2.2", "2222", "erickfm", fingerprint = null),
            result,
        )
    }

    @Test
    fun `v one is the understood version`() {
        val result = PairLink.parse("gc://h:22?user=u&v=1")
        assertTrue(result is PairLinkResult.Parsed)
    }

    @Test
    fun `a newer version is refused with the update message`() {
        val result = PairLink.parse("gc://h:22?user=u&v=2")
        assertTrue(result is PairLinkResult.Invalid)
        assertEquals(PairLink.InvalidReason.UNSUPPORTED_VERSION, (result as PairLinkResult.Invalid).reason)
        assertEquals(PairLink.Family.NEWER, result.reasonFamily)
        assertTrue(result.message.contains("update"))
    }

    @Test
    fun `an empty version is treated as not understood`() {
        val result = PairLink.parse("gc://h:22?user=u&v=")
        assertEquals(
            PairLink.InvalidReason.UNSUPPORTED_VERSION,
            (result as PairLinkResult.Invalid).reason,
        )
    }

    @Test
    fun `a 64 hex fingerprint travels with the link`() {
        val hex = "a".repeat(64)
        val result = PairLink.parse("gc://h:22?user=u&fp=$hex")
        assertEquals(
            PairLinkResult.Parsed("h", "22", "u", pair = null, fingerprint = hex),
            result,
        )
    }

    @Test
    fun `an uppercase fingerprint is normalized to lowercase`() {
        val result = PairLink.parse("gc://h:22?user=u&fp=${"AB".repeat(32)}")
        assertEquals("ab".repeat(32), (result as PairLinkResult.Parsed).fingerprint)
    }

    @Test
    fun `a fingerprint that is not 64 hex is refused`() {
        assertEquals(
            PairLink.InvalidReason.BAD_FINGERPRINT,
            (PairLink.parse("gc://h:22?user=u&fp=${"a".repeat(63)}") as PairLinkResult.Invalid).reason,
        )
        assertEquals(
            PairLink.InvalidReason.BAD_FINGERPRINT,
            (PairLink.parse("gc://h:22?user=u&fp=${"g".repeat(64)}") as PairLinkResult.Invalid).reason,
        )
        assertEquals(
            PairLink.InvalidReason.BAD_FINGERPRINT,
            (PairLink.parse("gc://h:22?user=u&fp=") as PairLinkResult.Invalid).reason,
        )
    }

    @Test
    fun `the one-scan link with version and fingerprint parses fully`() {
        val hex = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val result = PairLink.parse(
            "gc://100.96.18.28:22?user=erickfm&pair=192.168.1.24:8787&t=abc123&v=1&fp=$hex",
        )
        assertEquals(
            PairLinkResult.Parsed(
                "100.96.18.28",
                "22",
                "erickfm",
                PairParams("192.168.1.24", "8787", "abc123"),
                fingerprint = hex,
            ),
            result,
        )
    }

    // --- S25-4: the QR error taxonomy — three families with distinct ---
    // --- messages, answered locally before any native call.          ---

    @Test
    fun `not our code lands in the foreign family`() {
        val notGc = PairLink.parse("ssh://h:22?user=u") as PairLinkResult.Invalid
        assertEquals(PairLink.Family.FOREIGN, notGc.reasonFamily)
        val empty = PairLink.parse("   ") as PairLinkResult.Invalid
        assertEquals(PairLink.Family.FOREIGN, empty.reasonFamily)
        val plain = PairLink.parse("hello world") as PairLinkResult.Invalid
        assertEquals(PairLink.Family.FOREIGN, plain.reasonFamily)
    }

    @Test
    fun `broken codes land in the broken family`() {
        val cases = listOf(
            "gc://h:22", // no query
            "gc://:22?user=u", // no host
            "gc://h:70000?user=u", // bad port
            "gc://h:22", // missing user
            "gc://h:22?user=u&t=abc", // token without pair
        )
        for (case in cases) {
            val result = PairLink.parse(case) as PairLinkResult.Invalid
            assertEquals("$case must be BROKEN", PairLink.Family.BROKEN, result.reasonFamily)
        }
    }

    @Test
    fun `a newer protocol lands in the newer family`() {
        val result = PairLink.parse("gc://h:22?user=u&v=9") as PairLinkResult.Invalid
        assertEquals(PairLink.Family.NEWER, result.reasonFamily)
    }

    @Test
    fun `the three families answer with three distinct messages`() {
        val foreign = (PairLink.parse("ssh://h:22?user=u") as PairLinkResult.Invalid).message
        val broken = (PairLink.parse("gc://:22?user=u") as PairLinkResult.Invalid).message
        val newer = (PairLink.parse("gc://h:22?user=u&v=9") as PairLinkResult.Invalid).message
        assertEquals(3, setOf(foreign, broken, newer).size)
    }
}
