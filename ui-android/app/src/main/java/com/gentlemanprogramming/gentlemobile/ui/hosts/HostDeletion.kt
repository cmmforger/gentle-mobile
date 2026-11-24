package com.gentlemanprogramming.gentlemobile.ui.hosts

import com.gentlemanprogramming.gentlemobile.pair.KnownHostsFile
import com.gentlemanprogramming.gentlemobile.pair.SavedHostEntry

/** T50: deleting a saved host removes exactly its own pin — the per-host
 *  known_hosts file (S24-4) — and nothing else. The same derivation the
 *  connect path already trusts, so a deletion can never forget a pin a
 *  different host owns. */
object HostDeletion {

    /** The known_hosts file one saved host's pin lives in. */
    fun pinFile(entry: SavedHostEntry): String =
        KnownHostsFile.fileName(entry.host, entry.port.trim().toIntOrNull() ?: 0)
}
