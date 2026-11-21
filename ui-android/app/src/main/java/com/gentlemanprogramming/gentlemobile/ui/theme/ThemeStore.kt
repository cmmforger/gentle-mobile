package com.gentlemanprogramming.gentlemobile.ui.theme

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the selected theme (S9: `cute` default, Sexy and Gentle selectable)
 * across app restarts. The store logic is pure — unit-tested over an
 * in-memory [KeyValueStore] — and [SharedPreferencesStore] is the only
 * Android-dependent adapter, a thin pass-through.
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

class ThemeStore(private val store: KeyValueStore) {

    /** The persisted choice, or Cute when nothing valid was stored. */
    fun selected(): AppTheme =
        store.getString(KEY)?.let { AppTheme.fromStored(it) } ?: AppTheme.default

    fun select(theme: AppTheme) {
        store.putString(KEY, theme.name)
    }

    /** Follow the phone (S33): dark → Cute, light → Paper. Off by default —
     *  the pinned theme stands unless the human turns this on. */
    fun followThePhone(): Boolean = store.getString(KEY_FOLLOW) == "true"

    fun setFollowThePhone(follow: Boolean) {
        store.putString(KEY_FOLLOW, follow.toString())
    }

    companion object {
        const val KEY = "app_theme"
        const val KEY_FOLLOW = "follow_the_phone"
    }
}

/** The real backing store: the app's private default SharedPreferences. */
class SharedPreferencesStore(context: Context) : KeyValueStore {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    companion object {
        private const val PREFS_NAME = "gentle-mobile"
    }
}
