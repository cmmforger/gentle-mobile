package com.gentlemanprogramming.gentlemobile.ui.hosts

import com.gentlemanprogramming.gentlemobile.pair.SavedHostEntry
import com.gentlemanprogramming.gentlemobile.supervision.SupervisionState

/** The word the hosts list carries for one host. */
enum class HostRowState { Connected, Interrupted, Saved }

/** One row of the hosts list: the host as it was saved, one state word.
 *  Nothing else — Workspaces and Session history live inside the host's
 *  own screen (S34), never appended to the list. */
data class HostRowModel(
    val entry: SavedHostEntry,
    val state: HostRowState,
)

/** The word a host's own screen reports about the connection. */
enum class HostScreenState { Unconnected, Connecting, Connected, Interrupted, Failed }

data class HostScreenModel(
    val state: HostScreenState,
) {
    /** The disconnect action exists only while a link may be up: live, or
     *  interrupted after having been live. */
    val disconnectVisible: Boolean
        get() = state == HostScreenState.Connected || state == HostScreenState.Interrupted
}

object HostsModel {

    /** The row's plain address, the way the list has always shown it. */
    fun address(entry: SavedHostEntry): String = "${entry.user}@${entry.host}:${entry.port}"

    /** The saved host the live connection belongs to: the pair (host, port)
     *  is the identity, the host case-insensitive as the device itself is. */
    fun liveEntry(
        hosts: List<SavedHostEntry>,
        host: String,
        port: String,
    ): SavedHostEntry? =
        hosts.firstOrNull { entry ->
            entry.host.equals(host.trim(), ignoreCase = true) && entry.port.trim() == port.trim()
        }

    /** The hosts list (S34): the live host first with its live word, every
     *  other host in the order it was added, one row per host and nothing
     *  nested under any of them. */
    fun listRows(
        hosts: List<SavedHostEntry>,
        live: SavedHostEntry?,
        liveState: HostScreenState,
    ): List<HostRowModel> {
        val head = live?.let { entry ->
            listOf(
                HostRowModel(
                    entry = entry,
                    state = when (liveState) {
                        HostScreenState.Connected -> HostRowState.Connected
                        HostScreenState.Interrupted -> HostRowState.Interrupted
                        else -> HostRowState.Saved
                    },
                ),
            )
        } ?: emptyList()
        val rest = hosts.filter { it != live }
        return head + rest.map { HostRowModel(it, HostRowState.Saved) }
    }

    /** The host's own screen reports the supervising machine's state, in
     *  the five words S34 names: unconnected, connecting, connected,
     *  interrupted, failed. */
    fun hostScreen(state: SupervisionState?): HostScreenModel = HostScreenModel(
        when (state) {
            is SupervisionState.Connecting -> HostScreenState.Connecting
            is SupervisionState.Connected -> HostScreenState.Connected
            is SupervisionState.Interrupted -> HostScreenState.Interrupted
            is SupervisionState.Failed -> HostScreenState.Failed
            null, SupervisionState.Idle -> HostScreenState.Unconnected
        },
    )
}
