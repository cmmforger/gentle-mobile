package com.gentlemanprogramming.gentlemobile.pair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The saved host list (S24-4): several machines, each with the name the
 * human gave it, in the order they were added. Pure over [KeyValueStore]:
 * unit-tested with a fake, no Android. The store upserts by host+port —
 * renaming is saving the same address with a new name — and the name is
 * required at save time: a blank one falls back to `user@host`.
 */
class HostsStoreTest {

    private class FakeStore : com.gentlemanprogramming.gentlemobile.ui.theme.KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) {
            map[key] = value
        }
    }

    private fun entry(
        name: String,
        host: String,
        port: String = "22",
        user: String = "erickfm",
    ) = SavedHostEntry(name, host, port, user)

    // --- the empty state ---------------------------------------------------------

    @Test
    fun `an empty store reads as an empty list`() {
        assertEquals(emptyList<SavedHostEntry>(), HostsStore(FakeStore()).load())
    }

    // --- add and order -----------------------------------------------------------

    @Test
    fun `a saved host loads back`() {
        val store = FakeStore()
        val hs = HostsStore(store)
        hs.save(entry("Desktop", "10.0.2.2", "2222"))
        assertEquals(listOf(entry("Desktop", "10.0.2.2", "2222")), hs.load())
    }

    @Test
    fun `hosts keep the order they were added in`() {
        val hs = HostsStore(FakeStore())
        hs.save(entry("Desktop", "10.0.2.2", "2222"))
        hs.save(entry("Notebook", "127.0.0.1", "2222"))
        hs.save(entry("Tailnet", "100.96.18.28"))
        assertEquals(
            listOf(
                entry("Desktop", "10.0.2.2", "2222"),
                entry("Notebook", "127.0.0.1", "2222"),
                entry("Tailnet", "100.96.18.28"),
            ),
            hs.load(),
        )
    }

    // --- rename (upsert by host+port) ---------------------------------------------

    @Test
    fun `saving the same address again renames it in place`() {
        val hs = HostsStore(FakeStore())
        hs.save(entry("Desktop", "10.0.2.2", "2222"))
        hs.save(entry("Notebook", "127.0.0.1", "2222"))
        hs.save(entry("The desk machine", "10.0.2.2", "2222"))
        assertEquals(
            listOf(
                entry("The desk machine", "10.0.2.2", "2222"),
                entry("Notebook", "127.0.0.1", "2222"),
            ),
            hs.load(),
        )
    }

    @Test
    fun `renaming keeps the user the address already had`() {
        val hs = HostsStore(FakeStore())
        hs.save(entry("Desktop", "10.0.2.2", "2222", user = "erickfm"))
        hs.save(entry("Desktop", "10.0.2.2", "2222", user = "erickfm"))
        assertEquals("erickfm", hs.load().single().user)
    }

    // --- remove -------------------------------------------------------------------

    @Test
    fun `a removed host leaves the others untouched`() {
        val hs = HostsStore(FakeStore())
        hs.save(entry("Desktop", "10.0.2.2", "2222"))
        hs.save(entry("Notebook", "127.0.0.1", "2222"))
        hs.remove("10.0.2.2", "2222")
        assertEquals(listOf(entry("Notebook", "127.0.0.1", "2222")), hs.load())
    }

    @Test
    fun `removing an unknown host is quiet`() {
        val hs = HostsStore(FakeStore())
        hs.save(entry("Desktop", "10.0.2.2", "2222"))
        hs.remove("no-such-host", "22")
        assertEquals(listOf(entry("Desktop", "10.0.2.2", "2222")), hs.load())
    }

    // --- the name is required: fallback to user@host -------------------------------

    @Test
    fun `a blank name falls back to user at host`() {
        val hs = HostsStore(FakeStore())
        hs.save(entry("   ", "10.0.2.2", "2222"))
        assertEquals("erickfm@10.0.2.2", hs.load().single().name)
    }

    @Test
    fun `the fallback trims the saved name`() {
        val hs = HostsStore(FakeStore())
        hs.save(entry(" Desktop ", "10.0.2.2", "2222"))
        assertEquals("Desktop", hs.load().single().name)
    }

    @Test
    fun `the name is free-form text but never carries line breaks or tabs`() {
        val hs = HostsStore(FakeStore())
        hs.save(entry("Desktop\nold\tmachine", "10.0.2.2", "2222"))
        val loaded = hs.load().single()
        assertTrue(!loaded.name.contains('\n'))
        assertTrue(!loaded.name.contains('\t'))
        assertTrue(!loaded.name.contains('\r'))
    }

    // --- robustness ----------------------------------------------------------------

    @Test
    fun `a corrupted line is skipped, not a crash`() {
        val store = FakeStore()
        store.putString(
            HostsStore.KEY_LIST,
            "Desktop\t10.0.2.2\t2222\terickfm\nbroken-line\n\t\t\t\nNotebook\t127.0.0.1\t2222\terickfm",
        )
        assertEquals(
            listOf(
                entry("Desktop", "10.0.2.2", "2222"),
                entry("Notebook", "127.0.0.1", "2222"),
            ),
            HostsStore(store).load(),
        )
    }
}
