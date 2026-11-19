package com.gentlemanprogramming.gentlemobile.pair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Connection form's validator (S25-5): pure, framework-free, one
 * exact message per field — never a generic error. The English defaults
 * are asserted verbatim; [ConnectValidator.host]/[port]/[user] overloads
 * taking a message builder let the UI show the same refusals in the
 * user's language (S24-1) without the validator touching Android.
 */
class ConnectValidatorTest {

    // --- host: empty, IPv4, IPv6, hostname, anything else refused ---

    @Test
    fun `empty host is refused with the exact message`() {
        assertEquals(
            ConnectValidator.Reason.HOST_EMPTY,
            ConnectValidator.hostReason(""),
        )
        assertEquals(
            "Write the IP or host of your computer.",
            ConnectValidator.host("   "),
        )
    }

    @Test
    fun `ipv4 addresses are accepted`() {
        assertNull(ConnectValidator.host("192.168.1.24"))
        assertNull(ConnectValidator.host("10.0.2.2"))
        assertNull(ConnectValidator.host("255.255.255.255"))
    }

    @Test
    fun `broken ipv4 is refused`() {
        assertEquals(ConnectValidator.Reason.HOST_INVALID, ConnectValidator.hostReason("256.1.1.1"))
        assertEquals(ConnectValidator.Reason.HOST_INVALID, ConnectValidator.hostReason("1.2.3"))
        assertEquals(ConnectValidator.Reason.HOST_INVALID, ConnectValidator.hostReason("1.2.3.4.5"))
        assertEquals(ConnectValidator.Reason.HOST_INVALID, ConnectValidator.hostReason("192.168.1.24:22"))
    }

    @Test
    fun `ipv6 addresses are accepted`() {
        assertNull(ConnectValidator.host("::1"))
        assertNull(ConnectValidator.host("fe80::1"))
        assertNull(ConnectValidator.host("2001:db8::8a2e:370:7334"))
        assertNull(ConnectValidator.host("::ffff:192.168.0.1"))
    }

    @Test
    fun `broken ipv6 is refused`() {
        assertEquals(ConnectValidator.Reason.HOST_INVALID, ConnectValidator.hostReason("2001:db8:::1"))
        assertEquals(ConnectValidator.Reason.HOST_INVALID, ConnectValidator.hostReason("fe80::1::2"))
        assertEquals(ConnectValidator.Reason.HOST_INVALID, ConnectValidator.hostReason(":fe80:"))
    }

    @Test
    fun `host names are accepted`() {
        assertNull(ConnectValidator.host("myhost"))
        assertNull(ConnectValidator.host("erick-laptop.tail1234.ts.net"))
        assertNull(ConnectValidator.host("my_host.local"))
    }

    @Test
    fun `broken host names are refused`() {
        assertEquals(ConnectValidator.Reason.HOST_INVALID, ConnectValidator.hostReason("-bad-"))
        assertEquals(ConnectValidator.Reason.HOST_INVALID, ConnectValidator.hostReason("host with space"))
        assertEquals(ConnectValidator.Reason.HOST_INVALID, ConnectValidator.hostReason("ho/st"))
    }

    @Test
    fun `surrounding whitespace is trimmed before checking`() {
        assertNull(ConnectValidator.host("  192.168.1.24 \n"))
    }

    @Test
    fun `host refusals carry the exact English default`() {
        assertEquals(
            "That host is not an IP or a host name — write it like 192.168.1.24.",
            ConnectValidator.host("!!"),
        )
    }

    // --- port: 1..65535, one message per field ---

    @Test
    fun `valid ports are accepted`() {
        assertNull(ConnectValidator.port("22"))
        assertNull(ConnectValidator.port("1"))
        assertNull(ConnectValidator.port("65535"))
        assertNull(ConnectValidator.port(" 2222 "))
    }

    @Test
    fun `invalid ports are refused with the exact message`() {
        assertEquals("The port must be a number from 1 to 65535.", ConnectValidator.port(""))
        assertEquals("The port must be a number from 1 to 65535.", ConnectValidator.port("0"))
        assertEquals("The port must be a number from 1 to 65535.", ConnectValidator.port("65536"))
        assertEquals("The port must be a number from 1 to 65535.", ConnectValidator.port("abc"))
        assertEquals("The port must be a number from 1 to 65535.", ConnectValidator.port("22.5"))
        assertEquals("The port must be a number from 1 to 65535.", ConnectValidator.port("-1"))
    }

    // --- user: no spaces, no @, one message per field ---

    @Test
    fun `valid users are accepted`() {
        assertNull(ConnectValidator.user("erickfm"))
        assertNull(ConnectValidator.user("eri.ck-fm_2"))
    }

    @Test
    fun `empty user is refused with the exact message`() {
        assertEquals(ConnectValidator.Reason.USER_EMPTY, ConnectValidator.userReason(""))
        assertEquals(
            "Write your user on that computer.",
            ConnectValidator.user("   "),
        )
    }

    @Test
    fun `user with spaces or at is refused with the exact message`() {
        assertEquals(
            "The user cannot contain spaces or @.",
            ConnectValidator.user("eri ck"),
        )
        assertEquals(
            "The user cannot contain spaces or @.",
            ConnectValidator.user("erick@host"),
        )
    }

    // --- the message builder keeps one reason per field, localized elsewhere ---

    @Test
    fun `the message builder receives the field's own reason`() {
        val seen = mutableListOf<ConnectValidator.Reason>()
        val message = { reason: ConnectValidator.Reason ->
            seen += reason
            "localized"
        }
        assertEquals("localized", ConnectValidator.host("", message))
        assertEquals("localized", ConnectValidator.port("abc", message))
        assertEquals("localized", ConnectValidator.user("a b", message))
        assertEquals(
            listOf(
                ConnectValidator.Reason.HOST_EMPTY,
                ConnectValidator.Reason.PORT_INVALID,
                ConnectValidator.Reason.USER_INVALID,
            ),
            seen,
        )
    }

    @Test
    fun `every reason carries a non blank default`() {
        for (reason in ConnectValidator.Reason.entries) {
            assertTrue(reason.default.isNotBlank())
        }
    }
}
