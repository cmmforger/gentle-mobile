package com.gentlemanprogramming.gentlemobile.pair

import com.gentlemanprogramming.gentlemobile.ui.theme.KeyValueStore

/**
 * The last-used connection form (the reworked Pair screen): the optional
 * Name label — kept even though only one host is supported today, so the
 * multi-host work has its data ready — plus host, port and user. Saved
 * on connect, reloaded on entry. Pure over [KeyValueStore].
 */
data class SavedHost(
    val name: String = "",
    val host: String = "",
    val port: String = "22",
    val user: String = "",
)

class HostStore(private val store: KeyValueStore) {

    fun load(): SavedHost = SavedHost(
        name = store.getString(KEY_NAME).orEmpty().trim(),
        host = store.getString(KEY_HOST).orEmpty().trim(),
        port = store.getString(KEY_PORT)?.trim().takeUnless { it.isNullOrEmpty() } ?: "22",
        user = store.getString(KEY_USER).orEmpty().trim(),
    )

    fun save(saved: SavedHost) {
        store.putString(KEY_NAME, saved.name.trim())
        store.putString(KEY_HOST, saved.host.trim())
        store.putString(KEY_PORT, saved.port.trim())
        store.putString(KEY_USER, saved.user.trim())
    }

    companion object {
        const val KEY_NAME = "last_host_name"
        const val KEY_HOST = "last_host_host"
        const val KEY_PORT = "last_host_port"
        const val KEY_USER = "last_host_user"
    }
}
