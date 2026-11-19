package com.gentlemanprogramming.gentlemobile.pair

import com.gentlemanprogramming.gentlemobile.ui.theme.KeyValueStore

/**
 * One saved host (S24-4): several machines per install, each with the name
 * the human gave it — free-form text, their own words for their own machines.
 * The pair (host, port) is the identity: saving the same address again is a
 * rename in place, never a duplicate row.
 */
data class SavedHostEntry(
    val name: String,
    val host: String,
    val port: String,
    val user: String,
)

/**
 * The persisted host list, in the order hosts were added. Encoded over one
 * [KeyValueStore] key: one entry per line, fields separated by tabs — the
 * host, port and user come from validated form fields, and the name is
 * stripped of line breaks and tabs at save time, so the format cannot be
 * broken by the human's own text. A corrupted line is skipped, never a crash.
 */
class HostsStore(private val store: KeyValueStore) {

    /** Every saved host, in insertion order. */
    fun load(): List<SavedHostEntry> =
        store.getString(KEY_LIST).orEmpty()
            .split('\n')
            .mapNotNull { line ->
                val fields = line.split('\t')
                if (fields.size != 4) return@mapNotNull null
                val (name, host, port, user) = fields
                if (host.isBlank() || user.isBlank()) return@mapNotNull null
                SavedHostEntry(name, host, port, user)
            }

    /** Add a host, or rename/re-save the one that already has this address.
     *  The name is required at save time: a blank one falls back to
     *  `user@host`, the address itself — the human's machines must never
     *  show up as blank rows. */
    fun save(entry: SavedHostEntry) {
        val host = entry.host.trim()
        val port = entry.port.trim()
        val user = entry.user.trim()
        val name = entry.name.trim()
            .replace(Regex("[\\t\\n\\r]"), " ")
            .trim()
            .ifBlank { "$user@$host" }
        val updated = SavedHostEntry(name, host, port, user)
        val existing = load()
        // Upsert in place: a rename keeps the row where it was added; only a
        // new address is appended. The match is on the host+port identity.
        val index = existing.indexOfFirst {
            it.host.equals(host, ignoreCase = true) && it.port == port
        }
        write(if (index >= 0) {
            existing.toMutableList().also { it[index] = updated }
        } else {
            existing + updated
        })
    }

    /** Remove one host by address. Removing an unknown host is quiet. */
    fun remove(host: String, port: String) {
        write(
            load().filterNot {
                it.host.equals(host.trim(), ignoreCase = true) && it.port == port.trim()
            },
        )
    }

    private fun write(entries: List<SavedHostEntry>) {
        if (entries.isEmpty()) {
            store.putString(KEY_LIST, "")
        } else {
            store.putString(KEY_LIST, entries.joinToString("\n") {
                "${it.name}\t${it.host}\t${it.port}\t${it.user}"
            })
        }
    }

    companion object {
        const val KEY_LIST = "saved_hosts_v1"
    }
}
