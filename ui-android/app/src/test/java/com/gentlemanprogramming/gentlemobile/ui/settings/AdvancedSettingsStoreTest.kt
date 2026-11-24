package com.gentlemanprogramming.gentlemobile.ui.settings

import com.gentlemanprogramming.gentlemobile.ui.theme.KeyValueStore
import com.gentlemanprogramming.gentlemobile.ui.terminal.CursorMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T50: the Advanced settings store — every switch has a real default, a
 *  round-trip through one persisted store, and a garbage value falls back
 *  to the default instead of crashing. Pure over [KeyValueStore]. */
class AdvancedSettingsStoreTest {

    private class FakeStore : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) {
            map[key] = value
        }
    }

    @Test
    fun the_defaults_preserve_today_s_behaviour() {
        val defaults = AdvancedSettings()
        assertEquals(CursorMode.BLOCK, defaults.cursorMode)
        assertFalse(defaults.cursorBlink)
        assertTrue("the composer is on today", defaults.useComposer)
        assertTrue("Enter sends today", defaults.sendOnEnter)
        assertFalse(defaults.keepScreenOn)
        assertFalse(defaults.sortSessionsByName)
    }

    @Test
    fun a_fresh_store_reads_the_defaults() {
        assertEquals(AdvancedSettings(), AdvancedSettingsStore(FakeStore()).load())
    }

    @Test
    fun a_saved_settings_round_trip() {
        val store = FakeStore()
        val wanted = AdvancedSettings(
            cursorMode = CursorMode.BAR,
            cursorBlink = true,
            useComposer = false,
            sendOnEnter = false,
            keepScreenOn = true,
            sortSessionsByName = true,
        )
        AdvancedSettingsStore(store).save(wanted)
        assertEquals(wanted, AdvancedSettingsStore(store).load())
    }

    @Test
    fun a_garbage_cursor_mode_falls_back_to_block() {
        val store = FakeStore()
        store.map[AdvancedSettingsStore.KEY_CURSOR_MODE] = "not-a-mode"
        assertEquals(CursorMode.BLOCK, AdvancedSettingsStore(store).load().cursorMode)
    }

    @Test
    fun a_garbage_boolean_falls_back_to_the_default() {
        val store = FakeStore()
        store.map[AdvancedSettingsStore.KEY_CURSOR_BLINK] = "yes"
        store.map[AdvancedSettingsStore.KEY_USE_COMPOSER] = "nah"
        val loaded = AdvancedSettingsStore(store).load()
        assertFalse(loaded.cursorBlink)
        assertTrue(loaded.useComposer)
    }
}
