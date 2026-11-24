package com.gentlemanprogramming.gentlemobile.ui.settings

import com.gentlemanprogramming.gentlemobile.ui.terminal.CursorMode
import com.gentlemanprogramming.gentlemobile.ui.theme.KeyValueStore

/** The Advanced settings (T50): every value is real and wired — a switch
 *  that does nothing is a bug, so this data class only carries the rows the
 *  app actually honours. */
data class AdvancedSettings(
    val cursorMode: CursorMode = CursorMode.BLOCK,
    val cursorBlink: Boolean = false,
    val useComposer: Boolean = true,
    val sendOnEnter: Boolean = true,
    val keepScreenOn: Boolean = false,
    val sortSessionsByName: Boolean = false,
)

/** The persisted Advanced settings, over the same [KeyValueStore] every
 *  preference uses. A garbage stored value falls back to the default, never
 *  a crash. */
class AdvancedSettingsStore(private val store: KeyValueStore) {

    fun load(): AdvancedSettings = AdvancedSettings(
        cursorMode = store.getString(KEY_CURSOR_MODE)
            ?.let { stored -> CursorMode.entries.firstOrNull { it.name == stored } }
            ?: AdvancedSettings().cursorMode,
        cursorBlink = store.getString(KEY_CURSOR_BLINK) == "true",
        useComposer = store.getString(KEY_USE_COMPOSER) != "false",
        sendOnEnter = store.getString(KEY_SEND_ON_ENTER) != "false",
        keepScreenOn = store.getString(KEY_KEEP_SCREEN_ON) == "true",
        sortSessionsByName = store.getString(KEY_SORT_SESSIONS_BY_NAME) == "true",
    )

    fun save(settings: AdvancedSettings) {
        store.putString(KEY_CURSOR_MODE, settings.cursorMode.name)
        store.putString(KEY_CURSOR_BLINK, settings.cursorBlink.toString())
        store.putString(KEY_USE_COMPOSER, settings.useComposer.toString())
        store.putString(KEY_SEND_ON_ENTER, settings.sendOnEnter.toString())
        store.putString(KEY_KEEP_SCREEN_ON, settings.keepScreenOn.toString())
        store.putString(KEY_SORT_SESSIONS_BY_NAME, settings.sortSessionsByName.toString())
    }

    companion object {
        const val KEY_CURSOR_MODE = "adv_cursor_mode"
        const val KEY_CURSOR_BLINK = "adv_cursor_blink"
        const val KEY_USE_COMPOSER = "adv_use_composer"
        const val KEY_SEND_ON_ENTER = "adv_send_on_enter"
        const val KEY_KEEP_SCREEN_ON = "adv_keep_screen_on"
        const val KEY_SORT_SESSIONS_BY_NAME = "adv_sort_sessions_by_name"
    }
}
