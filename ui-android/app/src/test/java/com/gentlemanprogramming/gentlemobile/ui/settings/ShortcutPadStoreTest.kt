package com.gentlemanprogramming.gentlemobile.ui.settings

import com.gentlemanprogramming.gentlemobile.ui.session.ShortcutAction
import com.gentlemanprogramming.gentlemobile.ui.session.ShortcutKey
import com.gentlemanprogramming.gentlemobile.ui.session.ShortcutPad
import com.gentlemanprogramming.gentlemobile.ui.theme.KeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The persisted shortcut pad (T52): a plain key-value pair that survives a
 * restart. A missing or garbage value falls back to today's defaults, never
 * a crash.
 */
class ShortcutPadStoreTest {

    private class MapStore : KeyValueStore {
        private val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) {
            map[key] = value
        }
    }

    private fun MapStore.withPad(pad: ShortcutPad): MapStore {
        ShortcutPadStore(this).save(pad)
        return this
    }

    @Test
    fun `an empty store returns today's defaults`() {
        assertEquals(ShortcutPad.DEFAULT, ShortcutPadStore(MapStore()).load())
    }

    @Test
    fun `a configured pad round-trips through the store`() {
        val pad = ShortcutPad.DEFAULT
            .withJoystick(true)
            .setKeyVisible("alt", false)
            .moveKey("up", 0)
            .addKey(ShortcutKey("k1", "hola", ShortcutAction.Text("echo hola", true)))

        val loaded = ShortcutPadStore(MapStore().withPad(pad)).load()

        assertEquals(pad.keys, loaded.keys)
        assertEquals(pad.joystickVisible, loaded.joystickVisible)
    }

    @Test
    fun `custom text with special characters round-trips`() {
        val pad = ShortcutPad.DEFAULT.addKey(
            ShortcutKey("k1", "pipe|slash\\line", ShortcutAction.Text("echo 'a|b\\c'", false)),
        )
        assertEquals(pad, ShortcutPadStore(MapStore().withPad(pad)).load())
    }

    @Test
    fun `garbage falls back to the defaults`() {
        val store = MapStore()
        store.putString(ShortcutPadStore.KEY_PAD, "not|||a|||pad")
        store.putString(ShortcutPadStore.KEY_JOYSTICK, "not-a-boolean")
        assertEquals(ShortcutPad.DEFAULT, ShortcutPadStore(store).load())
    }

    @Test
    fun `a control byte action survives a round-trip`() {
        val pad = ShortcutPad.DEFAULT.addKey(
            ShortcutKey("k2", "Ctrl+C", ShortcutAction.ControlByte(0x03)),
        )
        val loaded = ShortcutPadStore(MapStore().withPad(pad)).load()
        assertEquals(ShortcutAction.ControlByte(0x03), loaded.keys.last().action)
    }
}