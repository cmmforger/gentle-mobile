package com.gentlemanprogramming.gentlemobile.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading a known_hosts spec back into the host and port a `Forget` needs
 * (T18). The core writes bare hosts for port 22 and `[host]:port` otherwise
 * (core `spec_for`); the app must reverse exactly that shape.
 */
class KnownHostSpecTest {

    @Test
    fun `a bare spec is port 22`() {
        assertEquals("host.example" to 22, parseHostSpec("host.example"))
    }

    @Test
    fun `a bracketed spec carries its port`() {
        assertEquals("127.0.0.1" to 2424, parseHostSpec("[127.0.0.1]:2424"))
    }

    @Test
    fun `a shape the core never wrote is refused`() {
        assertNull(parseHostSpec("[127.0.0.1]:notaport"))
        assertNull(parseHostSpec(""))
    }
}
