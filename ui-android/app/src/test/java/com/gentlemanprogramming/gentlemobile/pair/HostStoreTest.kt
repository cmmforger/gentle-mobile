package com.gentlemanprogramming.gentlemobile.pair

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The last-used connection form (S25, reworked screen): the optional
 * Name label — kept even though only one host is supported today, so the
 * multi-host work has its data ready — plus host, port and user, all
 * persisted on connect and reloaded on entry. Pure over [KeyValueStore]:
 * unit-tested with a fake, no Android.
 */
class HostStoreTest {

    private class FakeStore : com.gentlemanprogramming.gentlemobile.ui.theme.KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) {
            map[key] = value
        }
    }

    @Test
    fun `empty store reads as the blank form with port 22`() {
        val saved = HostStore(FakeStore()).load()
        assertEquals(SavedHost(name = "", host = "", port = "22", user = ""), saved)
    }

    @Test
    fun `what was saved is what loads`() {
        val store = FakeStore()
        val hs = HostStore(store)
        hs.save(SavedHost(name = "Desktop", host = "100.96.18.28", port = "22", user = "erickfm"))
        assertEquals(
            SavedHost("Desktop", "100.96.18.28", "22", "erickfm"),
            hs.load(),
        )
    }

    @Test
    fun `an empty port falls back to 22`() {
        val store = FakeStore()
        store.putString(HostStore.KEY_PORT, "")
        assertEquals("22", HostStore(store).load().port)
    }

    @Test
    fun `save trims the fields`() {
        val store = FakeStore()
        HostStore(store).save(
            SavedHost(name = " Desktop ", host = " 10.0.2.2 ", port = " 2222 ", user = " erickfm "),
        )
        assertEquals("Desktop", store.getString(HostStore.KEY_NAME))
        assertEquals("10.0.2.2", store.getString(HostStore.KEY_HOST))
        assertEquals("2222", store.getString(HostStore.KEY_PORT))
        assertEquals("erickfm", store.getString(HostStore.KEY_USER))
    }
}
