package com.gentlemanprogramming.gentlemobile.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T15 (S11): the foreground service runs exactly while a connection is
 * live — one start per connection, one stop when it ends or the user
 * disconnects, never two starts without a stop between (start/stop pairs).
 */
class ForegroundServiceGateTest {

    @Test
    fun `a live connection starts the service once`() {
        val gate = ForegroundServiceGate()
        assertTrue(gate.onConnected("10.0.2.2"))
        assertEquals("10.0.2.2", gate.runningHost)
        // A second connected report for the same host must not stack a
        // second startForegroundService: the one running service owns it.
        assertFalse("already running for this host", gate.onConnected("10.0.2.2"))
    }

    @Test
    fun `the connection ending stops the service exactly once`() {
        val gate = ForegroundServiceGate()
        gate.onConnected("host-a")
        assertTrue(gate.onConnectionEnded())
        assertEquals(null, gate.runningHost)
        assertFalse("nothing is running; a second stop has no pair", gate.onConnectionEnded())
    }

    @Test
    fun `stop when never started issues nothing`() {
        val gate = ForegroundServiceGate()
        assertFalse(gate.onConnectionEnded())
    }

    @Test
    fun `a new connection after a stop starts again`() {
        val gate = ForegroundServiceGate()
        gate.onConnected("host-a")
        gate.onConnectionEnded()
        assertTrue(gate.onConnected("host-a"))
    }

    @Test
    fun `a reconnect to a different host while running restarts for the new host`() {
        val gate = ForegroundServiceGate()
        gate.onConnected("host-a")
        assertTrue("the running notification names the old host", gate.onConnected("host-b"))
        assertEquals("host-b", gate.runningHost)
    }

    @Test
    fun `start and stop stay paired through a full session`() {
        val gate = ForegroundServiceGate()
        val commands = mutableListOf<String>()
        // connect → drop → retry → connect → user disconnect
        assertTrue(gate.onConnected("host")).also { commands += "start" }
        assertTrue(gate.onConnectionEnded()).also { commands += "stop" }
        assertTrue(gate.onConnected("host")).also { commands += "start" }
        assertTrue(gate.onConnectionEnded()).also { commands += "stop" }
        assertEquals(listOf("start", "stop", "start", "stop"), commands)
        assertEquals(null, gate.runningHost)
    }
}
