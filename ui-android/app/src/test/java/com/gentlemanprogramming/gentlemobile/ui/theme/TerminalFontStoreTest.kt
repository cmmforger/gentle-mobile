package com.gentlemanprogramming.gentlemobile.ui.theme

import com.gentlemanprogramming.gentlemobile.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The terminal font choice (T44) must survive an app restart (same contract
 * as the theme and typeface stores), the default is IosevkaTerm Nerd Font,
 * and every choice maps to real bundled font resources — JetBrains Mono stays
 * as the alternative with its four weights, IosevkaTerm ships regular + bold.
 */
class TerminalFontStoreTest {

    private class MemoryStore : KeyValueStore {
        val map = LinkedHashMap<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) {
            map[key] = value
        }
    }

    @Test
    fun `the default terminal font is iosevka term`() {
        assertEquals(TerminalFont.IOSEVKA_TERM, TerminalFont.default)
        assertEquals(TerminalFont.IOSEVKA_TERM, TerminalFontStore(MemoryStore()).selected())
    }

    @Test
    fun `selection round-trips through the store`() {
        for (choice in TerminalFont.entries) {
            val store = MemoryStore()
            TerminalFontStore(store).select(choice)
            assertEquals(
                "a fresh store over the same backing must read $choice",
                choice,
                TerminalFontStore(store).selected(),
            )
        }
    }

    @Test
    fun `an unknown stored value falls back to the default`() {
        val store = MemoryStore()
        store.putString(TerminalFontStore.KEY, "comic-sans")
        assertEquals(TerminalFont.IOSEVKA_TERM, TerminalFontStore(store).selected())
    }

    @Test
    fun `selecting overwrites a previous choice`() {
        val store = TerminalFontStore(MemoryStore())
        store.select(TerminalFont.IOSEVKA_TERM)
        store.select(TerminalFont.JETBRAINS_MONO)
        assertEquals(TerminalFont.JETBRAINS_MONO, store.selected())
    }

    @Test
    fun `iosevka term maps to regular and bold with no italic`() {
        val face = bundledTerminalFace(TerminalFont.IOSEVKA_TERM)
        assertEquals(R.font.iosevkaterm_regular, face.regular)
        assertEquals(R.font.iosevkaterm_bold, face.bold)
        assertNull("IosevkaTerm ships no italic", face.italic)
        assertNull("IosevkaTerm ships no bold-italic", face.boldItalic)
    }

    @Test
    fun `jetbrains mono maps to all four weights`() {
        val face = bundledTerminalFace(TerminalFont.JETBRAINS_MONO)
        assertEquals(R.font.jetbrainsmono_regular, face.regular)
        assertEquals(R.font.jetbrainsmono_bold, face.bold)
        assertEquals(R.font.jetbrainsmono_italic, face.italic)
        assertEquals(R.font.jetbrainsmono_bolditalic, face.boldItalic)
    }

    @Test
    fun `the two terminal faces are distinct bundled faces`() {
        val iosevka = bundledTerminalFace(TerminalFont.IOSEVKA_TERM)
        val jetbrains = bundledTerminalFace(TerminalFont.JETBRAINS_MONO)
        assertFalse(iosevka.regular == jetbrains.regular)
    }
}
