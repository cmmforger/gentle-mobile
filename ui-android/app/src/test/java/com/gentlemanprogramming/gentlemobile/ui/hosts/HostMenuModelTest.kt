package com.gentlemanprogramming.gentlemobile.ui.hosts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T57 (hosts: a menu, not a swipe): the menu's entries are a pure decision —
 *  Conectar, Editar and Eliminar only; Duplicar and Reordenar are left out
 *  because they cannot be done well yet. Eliminar is the one destructive
 *  entry and is the one that asks for confirmation. */
class HostMenuModelTest {

    @Test
    fun `the menu is connect, edit and delete in that order`() {
        assertEquals(
            listOf(HostMenuEntry.CONNECT, HostMenuEntry.EDIT, HostMenuEntry.DELETE),
            HostMenuModel.entries(),
        )
    }

    @Test
    fun `only delete is destructive`() {
        assertFalse(HostMenuModel.isDestructive(HostMenuEntry.CONNECT))
        assertFalse(HostMenuModel.isDestructive(HostMenuEntry.EDIT))
        assertTrue(HostMenuModel.isDestructive(HostMenuEntry.DELETE))
    }
}
