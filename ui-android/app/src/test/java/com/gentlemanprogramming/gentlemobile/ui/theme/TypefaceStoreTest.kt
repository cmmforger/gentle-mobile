package com.gentlemanprogramming.gentlemobile.ui.theme

import androidx.compose.ui.text.font.FontFamily
import com.gentlemanprogramming.gentlemobile.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The typeface choice must survive an app restart (same contract as the
 * theme store), the default is the system face, and every bundled choice
 * maps to real font resources — never a silent fallback to System unless
 * System is the choice.
 */
class TypefaceStoreTest {

    /** Same shape the SharedPreferences adapter implements; pure, no Android. */
    private class MemoryStore : KeyValueStore {
        val map = LinkedHashMap<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) {
            map[key] = value
        }
    }

    @Test
    fun `the default is the system face when nothing was ever selected`() {
        assertEquals(AppTypeface.SYSTEM, TypefaceStore(MemoryStore()).selected())
    }

    @Test
    fun `selection round-trips through the store`() {
        for (choice in AppTypeface.entries) {
            val store = MemoryStore()
            TypefaceStore(store).select(choice)
            assertEquals(
                "a fresh store over the same backing must read $choice",
                choice,
                TypefaceStore(store).selected(),
            )
        }
    }

    @Test
    fun `an unknown stored value falls back to the system face instead of crashing`() {
        val store = MemoryStore()
        store.putString(TypefaceStore.KEY, "comic-sans")
        assertEquals(AppTypeface.SYSTEM, TypefaceStore(store).selected())
    }

    @Test
    fun `selecting overwrites a previous choice`() {
        val store = TypefaceStore(MemoryStore())
        store.select(AppTypeface.SPACE_GROTESK)
        store.select(AppTypeface.ALDRICH)
        assertEquals(AppTypeface.ALDRICH, store.selected())
    }

    @Test
    fun `the system choice maps to no bundled face`() {
        assertNull(bundledFace(AppTypeface.SYSTEM))
    }

    @Test
    fun `every bundled choice resolves to a bundled face`() {
        for (choice in AppTypeface.entries.filter { it != AppTypeface.SYSTEM }) {
            assertNotNull("$choice must ship a bundled face, not fall back to System", bundledFace(choice))
        }
    }

    @Test
    fun `bundled faces ship the weights they claim`() {
        val spaceGrotesk = requireNotNull(bundledFace(AppTypeface.SPACE_GROTESK))
        assertEquals(R.font.space_grotesk_regular, spaceGrotesk.regular)
        assertEquals(R.font.space_grotesk_bold, spaceGrotesk.bold)
        val plex = requireNotNull(bundledFace(AppTypeface.IBM_PLEX_SANS))
        assertEquals(R.font.ibm_plex_sans_regular, plex.regular)
        assertEquals(R.font.ibm_plex_sans_bold, plex.bold)
        val aldrich = requireNotNull(bundledFace(AppTypeface.ALDRICH))
        assertEquals(R.font.aldrich_regular, aldrich.regular)
        assertNull("Aldrich ships one weight only", aldrich.bold)
    }

    @Test
    fun `bundled resources are real and distinct`() {
        val faces = AppTypeface.entries
            .filter { it != AppTypeface.SYSTEM }
            .map { requireNotNull(bundledFace(it)) }
        assertTrue(faces.all { it.regular != 0 })
        assertFalse(faces[0].regular == faces[1].regular)
        assertFalse(faces[0].regular == faces[2].regular)
    }

    @Test
    fun `the system family is the platform default`() {
        assertEquals(FontFamily.Default, fontFamilyFor(AppTypeface.SYSTEM))
    }

    @Test
    fun `iosevka term is a choice with its own label`() {
        assertTrue(AppTypeface.entries.contains(AppTypeface.IOSEVKA_TERM))
        assertEquals("IosevkaTerm Nerd Font", AppTypeface.IOSEVKA_TERM.label)
    }

    @Test
    fun `iosevka term ships regular and bold`() {
        val face = requireNotNull(bundledFace(AppTypeface.IOSEVKA_TERM))
        assertEquals(R.font.iosevkaterm_regular, face.regular)
        assertEquals(R.font.iosevkaterm_bold, face.bold)
    }

    @Test
    fun `the monospaced choice drives the terminal with its own face`() {
        val resolution = terminalFaceFor(AppTypeface.IOSEVKA_TERM)
        assertEquals(R.font.iosevkaterm_regular, resolution.regular)
        assertEquals(R.font.iosevkaterm_bold, resolution.bold)
        assertNull("IosevkaTerm ships no italic", resolution.italic)
        assertNull("IosevkaTerm ships no bold-italic", resolution.boldItalic)
        assertFalse("a monospaced choice must not fall back", resolution.fallback)
    }

    @Test
    fun `every proportional choice falls back to the bundled monospace`() {
        for (choice in AppTypeface.entries.filter { it != AppTypeface.IOSEVKA_TERM }) {
            val resolution = terminalFaceFor(choice)
            assertEquals("$choice falls back to JetBrains Mono regular", R.font.jetbrainsmono_regular, resolution.regular)
            assertEquals(R.font.jetbrainsmono_bold, resolution.bold)
            assertEquals(R.font.jetbrainsmono_italic, resolution.italic)
            assertEquals(R.font.jetbrainsmono_bolditalic, resolution.boldItalic)
            assertTrue("$choice is proportional and must fall back", resolution.fallback)
        }
    }

    @Test
    fun `fallback marks exactly the proportional choices`() {
        for (choice in AppTypeface.entries) {
            assertEquals(
                "fallback must be true exactly for non-monospaced choices",
                choice != AppTypeface.IOSEVKA_TERM,
                terminalFaceFor(choice).fallback,
            )
        }
    }
}
