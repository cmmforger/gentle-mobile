package com.gentlemanprogramming.gentlemobile.ui.hosts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T58 (hosts: a menu, not a swipe): the menu's entries are a pure decision —
 *  Editar and Eliminar only. Conectar is gone (tapping the row already
 *  connects) and Duplicar/Reordenar are left out because they cannot be done
 *  well yet. Eliminar is the one destructive entry and the one that asks for
 *  confirmation. */
class HostMenuModelTest {

    @Test
    fun `the menu is edit and delete only`() {
        assertEquals(
            listOf(HostMenuEntry.EDIT, HostMenuEntry.DELETE),
            HostMenuModel.entries(),
        )
    }

    @Test
    fun `only delete is destructive`() {
        assertFalse(HostMenuModel.isDestructive(HostMenuEntry.EDIT))
        assertTrue(HostMenuModel.isDestructive(HostMenuEntry.DELETE))
    }
}
