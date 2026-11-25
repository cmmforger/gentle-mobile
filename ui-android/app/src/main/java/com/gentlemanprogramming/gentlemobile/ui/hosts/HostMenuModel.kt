package com.gentlemanprogramming.gentlemobile.ui.hosts

/** One entry of a host row's menu (T57): Conectar, Editar and Eliminar only.
 *  Duplicar and Reordenar are left out — they cannot be done well yet, and a
 *  dead entry is worse than an absent one. */
enum class HostMenuEntry { CONNECT, EDIT, DELETE }

/** The host row's menu model: which entries exist, and which one is
 *  destructive (the one that asks for confirmation). */
object HostMenuModel {
    fun entries(): List<HostMenuEntry> = listOf(
        HostMenuEntry.CONNECT,
        HostMenuEntry.EDIT,
        HostMenuEntry.DELETE,
    )

    fun isDestructive(entry: HostMenuEntry): Boolean = entry == HostMenuEntry.DELETE
}
