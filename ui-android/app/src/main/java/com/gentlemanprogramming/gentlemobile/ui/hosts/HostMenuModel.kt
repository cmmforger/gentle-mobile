package com.gentlemanprogramming.gentlemobile.ui.hosts

/** One entry of a host row's menu (T58): Editar and Eliminar only. Conectar
 *  is gone — tapping the row already connects — and Duplicar/Reordenar are
 *  left out because they cannot be done well yet. */
enum class HostMenuEntry { EDIT, DELETE }

/** The host row's menu model: which entries exist, and which one is
 *  destructive (the one that asks for confirmation). */
object HostMenuModel {
    fun entries(): List<HostMenuEntry> = listOf(
        HostMenuEntry.EDIT,
        HostMenuEntry.DELETE,
    )

    fun isDestructive(entry: HostMenuEntry): Boolean = entry == HostMenuEntry.DELETE
}
