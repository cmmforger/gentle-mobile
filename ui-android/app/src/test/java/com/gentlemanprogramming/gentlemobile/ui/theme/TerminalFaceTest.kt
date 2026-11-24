package com.gentlemanprogramming.gentlemobile.ui.theme

import com.gentlemanprogramming.gentlemobile.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T51 (Terminal font picker): every offered terminal face must be
 *  monospaced and carry Nerd glyphs — a proportional face must never be
 *  offered — and each maps to real, distinct, non-zero font resources. */
class TerminalFaceTest {

    @Test
    fun `at least four faces are offered`() {
        assertTrue("at least four faces", TerminalFace.entries.size >= 4)
    }

    @Test
    fun `every offered face is monospaced and carries nerd glyphs`() {
        for (face in TerminalFace.entries) {
            assertTrue("$face must be monospaced", face.monospaced)
            assertTrue("$face must carry Nerd glyphs", face.nerdGlyphs)
        }
    }

    @Test
    fun `every face maps to real distinct regular and bold resources`() {
        val regulars = TerminalFace.entries.map { it.regular }.toSet()
        val bolds = TerminalFace.entries.map { it.bold }.toSet()
        assertFalse("no face maps to the zero resource", regulars.contains(0) || bolds.contains(0))
        assertEquals("regular resources are distinct", TerminalFace.entries.size, regulars.size)
        assertEquals("bold resources are distinct", TerminalFace.entries.size, bolds.size)
    }

    @Test
    fun `iosevka term is the default and keeps its bundled files`() {
        assertEquals(TerminalFace.IOSEVKA_TERM, TerminalFace.default)
        assertEquals(R.font.iosevkaterm_regular, TerminalFace.IOSEVKA_TERM.regular)
        assertEquals(R.font.iosevkaterm_bold, TerminalFace.IOSEVKA_TERM.bold)
    }

    @Test
    fun `storage round-trips a face and falls back on garbage`() {
        for (face in TerminalFace.entries) {
            assertEquals(face, TerminalFace.fromStored(face.name))
        }
        assertTrue(TerminalFace.fromStored("comic-sans") == null)
    }

    @Test
    fun `each face has a font family drawn in itself`() {
        for (face in TerminalFace.entries) {
            assertNotNull("$face needs a family for the picker", terminalFontFamily(face))
        }
    }
}
