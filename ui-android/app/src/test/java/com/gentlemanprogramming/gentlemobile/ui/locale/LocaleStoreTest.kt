package com.gentlemanprogramming.gentlemobile.ui.locale

import com.gentlemanprogramming.gentlemobile.ui.theme.KeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The app language choice (S24-1), pure like the theme store: English is
 * the default when nothing was ever stored, a stored Spanish choice reads
 * back, an unknown value falls back to English, and a stored choice
 * survives the store being re-created (process death).
 */
class LocaleStoreTest {

    /** Same shape the SharedPreferences adapter implements; pure, no Android. */
    private class MemoryStore : KeyValueStore {
        val map = LinkedHashMap<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) {
            map[key] = value
        }
    }

    @Test
    fun `the default is english when nothing was ever stored`() {
        assertEquals(AppLanguage.ENGLISH, LocaleStore(MemoryStore()).selected())
    }

    @Test
    fun `a stored spanish choice reads back`() {
        val store = MemoryStore()
        LocaleStore(store).select(AppLanguage.SPANISH)
        assertEquals(AppLanguage.SPANISH, LocaleStore(store).selected())
    }

    @Test
    fun `an unknown stored value falls back to english`() {
        val store = MemoryStore()
        store.putString(LocaleStore.KEY, "fr")
        assertEquals(AppLanguage.ENGLISH, LocaleStore(store).selected())
        store.putString(LocaleStore.KEY, "de-DE")
        assertEquals(AppLanguage.ENGLISH, LocaleStore(store).selected())
        store.putString(LocaleStore.KEY, "")
        assertEquals(AppLanguage.ENGLISH, LocaleStore(store).selected())
    }

    @Test
    fun `a stored choice survives re-creation like process death`() {
        val backing = MemoryStore()
        LocaleStore(backing).select(AppLanguage.SPANISH)
        // A brand-new store object over the same backing (the app restarted):
        // the choice must still be Spanish.
        assertEquals(AppLanguage.SPANISH, LocaleStore(MemoryStore().also { it.map.putAll(backing.map) }).selected())
    }

    @Test
    fun `selecting overwrites a previous choice`() {
        val store = LocaleStore(MemoryStore())
        store.select(AppLanguage.SPANISH)
        store.select(AppLanguage.ENGLISH)
        assertEquals(AppLanguage.ENGLISH, store.selected())
    }

    @Test
    fun `language tags resolve exactly and fall back to english`() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("xx"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("EN"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("english"))
        assertEquals(AppLanguage.SPANISH, AppLanguage.fromTag("es"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en"))
    }
}
