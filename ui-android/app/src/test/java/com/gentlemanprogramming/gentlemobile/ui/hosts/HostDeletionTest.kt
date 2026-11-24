package com.gentlemanprogramming.gentlemobile.ui.hosts

import com.gentlemanprogramming.gentlemobile.pair.KnownHostsFile
import com.gentlemanprogramming.gentlemobile.pair.SavedHostEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** T50: deleting a host removes exactly its own pin — the per-host
 *  known_hosts file (S24-4) — and nothing else. The rule is the same
 *  [KnownHostsFile] derivation the connect path already trusts. */
class HostDeletionTest {

    @Test
    fun a_host_s_pin_file_is_its_own_known_hosts_file() {
        val entry = SavedHostEntry("Desktop", "100.96.18.28", "22", "erick")
        assertEquals(
            KnownHostsFile.fileName("100.96.18.28", 22),
            HostDeletion.pinFile(entry),
        )
    }

    @Test
    fun two_hosts_never_share_a_pin_file() {
        val desktop = SavedHostEntry("Desktop", "100.96.18.28", "22", "erick")
        val notebook = SavedHostEntry("Notebook", "100.96.18.28", "2222", "erick")
        assertNotEquals(HostDeletion.pinFile(desktop), HostDeletion.pinFile(notebook))
    }

    @Test
    fun an_unparseable_port_still_yields_a_stable_file() {
        val entry = SavedHostEntry("Desktop", "100.96.18.28", "", "erick")
        assertEquals(KnownHostsFile.fileName("100.96.18.28", 0), HostDeletion.pinFile(entry))
    }
}
