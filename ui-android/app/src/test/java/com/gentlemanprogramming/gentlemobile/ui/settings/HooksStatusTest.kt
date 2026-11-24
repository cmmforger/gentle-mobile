package com.gentlemanprogramming.gentlemobile.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings → Hooks status area (T55): the host's answer — whether this
 * phone's key is in its authorized_keys — maps to one truthful word. No
 * answer is ever guessed: without a live connection the word is "connect
 * first", and a read failure is "cannot tell" with the reason.
 */
class HooksStatusTest {

    @Test
    fun `without a live connection the word is connect first`() {
        assertEquals(
            HooksStatus.ConnectFirst,
            mapHooksStatus(connected = false, registered = null, error = null),
        )
    }

    @Test
    fun `the host answered registered`() {
        assertEquals(
            HooksStatus.Registered,
            mapHooksStatus(connected = true, registered = true, error = null),
        )
    }

    @Test
    fun `the host answered not registered`() {
        assertEquals(
            HooksStatus.NotRegistered,
            mapHooksStatus(connected = true, registered = false, error = null),
        )
    }

    @Test
    fun `a typed read failure is cannot tell with the reason`() {
        val status = mapHooksStatus(
            connected = true,
            registered = null,
            error = "the authorized_keys file could not be read",
        )
        assertTrue(status is HooksStatus.CannotTell)
        assertEquals(
            "the authorized_keys file could not be read",
            (status as HooksStatus.CannotTell).reason,
        )
    }

    @Test
    fun `a missing reason still reads cannot tell never registered`() {
        // A defensive branch: registered==null always carries an error in the
        // real flow, but a missing one must not silently read as "not
        // registered".
        assertEquals(
            HooksStatus.CannotTell(""),
            mapHooksStatus(connected = true, registered = null, error = null),
        )
    }
}
