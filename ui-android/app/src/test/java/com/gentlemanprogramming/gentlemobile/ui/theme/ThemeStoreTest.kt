package com.gentlemanprogramming.gentlemobile.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The selected theme must survive an app restart: the store persists the
 * choice as a string and reads it back, defaulting to Cute (S9) and refusing
 * garbage instead of crashing on a corrupted preference.
 */
class ThemeStoreTest {

    /** Same shape the SharedPreferences adapter implements; pure, no Android. */
    private class MemoryStore : KeyValueStore {
        val map = LinkedHashMap<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) {
            map[key] = value
        }
    }

    @Test
    fun `selection round-trips through the store`() {
        for (theme in AppTheme.entries) {
            val store = MemoryStore()
            ThemeStore(store).select(theme)
            assertEquals("a fresh store over the same backing must read $theme", theme, ThemeStore(store).selected())
        }
    }

    @Test
    fun `the default is cute when nothing was ever selected`() {
        assertEquals(AppTheme.CUTE, ThemeStore(MemoryStore()).selected())
    }

    @Test
    fun `an unknown stored value falls back to cute instead of crashing`() {
        val store = MemoryStore()
        store.putString(ThemeStore.KEY, "mustaccin-neon")
        assertEquals(AppTheme.CUTE, ThemeStore(store).selected())
    }

    @Test
    fun `selecting overwrites a previous choice`() {
        val store = ThemeStore(MemoryStore())
        store.select(AppTheme.SEXY)
        store.select(AppTheme.GENTLE)
        assertEquals(AppTheme.GENTLE, store.selected())
    }

    @Test
    fun `paper persists like the other themes`() {
        val store = MemoryStore()
        val themeStore = ThemeStore(store)
        themeStore.select(AppTheme.PAPER)
        assertEquals(AppTheme.PAPER, themeStore.selected())
    }

    @Test
    fun `storing an unknown follow value reads as off`() {
        val store = MemoryStore()
        store.putString(ThemeStore.KEY_FOLLOW, "maybe")
        assertEquals(false, ThemeStore(store).followThePhone())
    }
}
