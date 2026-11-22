package com.gentlemanprogramming.gentlemobile.ui.hosts

import com.gentlemanprogramming.gentlemobile.pair.SavedHostEntry
import com.gentlemanprogramming.gentlemobile.supervision.SupervisionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S34 (T35): the hosts screen is a clean list — one row per host, the
 *  live host first — and every host's own things (Workspaces, Session
 *  history, state, disconnect) live inside that host's screen, reported
 *  as one of five words. The decisions are pure and pinned here; the
 *  Compose view around them is not unit-tested. */
class HostsModelTest {

    private val desktop = SavedHostEntry("Desktop", "100.96.18.28", "22", "erick")
    private val notebook = SavedHostEntry("Notebook", "100.96.18.29", "22", "erick")
    private val lab = SavedHostEntry("pi-lab", "192.168.18.10", "2222", "erick")

    @Test
    fun `one row per host - the list model never grows nested sections`() {
        val rows = HostsModel.listRows(
            listOf(desktop, notebook, lab),
            live = desktop,
            liveState = HostScreenState.Connected,
        )
        assertEquals(3, rows.size)
        assertEquals(listOf("Desktop", "Notebook", "pi-lab"), rows.map { it.entry.name })
    }

    @Test
    fun `the live host leads the list, saved hosts keep the order they were added`() {
        val rows = HostsModel.listRows(
            listOf(desktop, notebook, lab),
            live = notebook,
            liveState = HostScreenState.Connected,
        )
        assertEquals(listOf(notebook, desktop, lab), rows.map { it.entry })
    }

    @Test
    fun `the live row is matched by host and port, ignoring case`() {
        assertNull(HostsModel.liveEntry(listOf(desktop, notebook), "100.96.18.99", "22"))
        assertEquals(desktop, HostsModel.liveEntry(listOf(desktop, notebook), "100.96.18.28", "22"))
        assertEquals(
            desktop,
            HostsModel.liveEntry(listOf(desktop, notebook), "100.96.18.28".uppercase(), "22"),
        )
        assertEquals(desktop, HostsModel.liveEntry(listOf(desktop, notebook), "100.96.18.28", "22".uppercase()))
    }

    @Test
    fun `a connected live row reads connected, an interrupted one interrupted`() {
        val connected = HostsModel.listRows(listOf(desktop, notebook), live = desktop, liveState = HostScreenState.Connected)
        assertEquals(HostRowState.Connected, connected.first().state)
        assertEquals(HostRowState.Saved, connected.last().state)

        val interrupted = HostsModel.listRows(listOf(desktop, notebook), live = desktop, liveState = HostScreenState.Interrupted)
        assertEquals(HostRowState.Interrupted, interrupted.first().state)
        assertEquals(HostRowState.Saved, interrupted.last().state)
    }

    @Test
    fun `without a live host every row reads saved`() {
        val rows = HostsModel.listRows(listOf(desktop, notebook, lab), live = null, liveState = HostScreenState.Unconnected)
        assertEquals(listOf(desktop, notebook, lab), rows.map { it.entry })
        assertTrue(rows.all { it.state == HostRowState.Saved })
    }

    @Test
    fun `a row's address is user@host-port`() {
        val rows = HostsModel.listRows(listOf(desktop), live = null, liveState = HostScreenState.Unconnected)
        assertEquals("erick@100.96.18.28:22", HostsModel.address(rows.first().entry))
    }

    @Test
    fun `the host screen reports the machine state as one of five words`() {
        assertEquals(HostScreenState.Unconnected, HostsModel.hostScreen(null).state)
        assertEquals(HostScreenState.Unconnected, HostsModel.hostScreen(SupervisionState.Idle).state)
        assertEquals(HostScreenState.Connecting, HostsModel.hostScreen(SupervisionState.Connecting(1)).state)
        assertEquals(HostScreenState.Connected, HostsModel.hostScreen(SupervisionState.Connected).state)
        assertEquals(
            HostScreenState.Interrupted,
            HostsModel.hostScreen(SupervisionState.Interrupted("watchdog", autoRetryUsed = false)).state,
        )
        assertEquals(HostScreenState.Failed, HostsModel.hostScreen(SupervisionState.Failed("refused")).state)
    }

    @Test
    fun `the disconnect action shows only while a link exists`() {
        assertFalse(HostsModel.hostScreen(null).disconnectVisible)
        assertFalse(HostsModel.hostScreen(SupervisionState.Idle).disconnectVisible)
        assertFalse(HostsModel.hostScreen(SupervisionState.Connecting(1)).disconnectVisible)
        assertFalse(HostsModel.hostScreen(SupervisionState.Failed("refused")).disconnectVisible)
        assertTrue(HostsModel.hostScreen(SupervisionState.Connected).disconnectVisible)
        assertTrue(
            HostsModel.hostScreen(SupervisionState.Interrupted("watchdog", autoRetryUsed = false)).disconnectVisible,
        )
    }
}
