package com.gentlemanprogramming.gentlemobile.ui.locale

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import com.gentlemanprogramming.gentlemobile.ui.theme.KeyValueStore
import com.gentlemanprogramming.gentlemobile.ui.theme.SharedPreferencesStore
import java.util.Locale

/**
 * The app language (S24-1): English (default) and Spanish, chosen inside
 * the app, persisted, and applied immediately without a restart.
 *
 * The mechanism is a stored-preference locale override (the pattern
 * AppCompatDelegate uses, implemented without the AppCompat dependency
 * because MainActivity is a FragmentActivity and minSdk is 29 — per-app
 * locales via `LocaleManager` only exist on API 33+, so they cannot be the
 * whole answer here):
 *
 * * [LocaleStore] is the pure choice, unit-tested over an in-memory
 *   [KeyValueStore]: nothing stored means English, an unknown stored value
 *   falls back to English;
 * * [AppLocale.wrap] builds a locale-configured context. MainActivity
 *   wraps itself in `attachBaseContext` (the choice survives process
 *   death) and re-provides `LocalContext` inside the composition whenever
 *   the choice changes (every `stringResource` call follows immediately —
 *   no restart);
 * * notifications are posted through [AppLocale.wrapStored] too, so a
 *   notification created after a switch uses the chosen language.
 */
enum class AppLanguage(val tag: String, val displayName: String) {
    ENGLISH("en", "English"),
    SPANISH("es", "Español");

    companion object {
        /** Resolves a stored tag; anything unknown or missing is English. */
        fun fromTag(tag: String?): AppLanguage =
            entries.firstOrNull { it.tag == tag } ?: ENGLISH
    }
}

/** The persisted language choice. Pure: unit-tested with a fake store. */
class LocaleStore(private val store: KeyValueStore) {

    fun selected(): AppLanguage = AppLanguage.fromTag(store.getString(KEY))

    fun select(language: AppLanguage) {
        store.putString(KEY, language.tag)
    }

    companion object {
        const val KEY = "app_language"
    }
}

/** The Android side: building locale-configured contexts from the choice. */
object AppLocale {

    /** The stored choice as this device holds it right now. */
    fun stored(context: Context): AppLanguage =
        LocaleStore(SharedPreferencesStore(context)).selected()

    /** A context whose resources resolve in [language]'s locale. */
    fun wrap(context: Context, language: AppLanguage): Context {
        val locale = Locale.forLanguageTag(language.tag)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    /** The same, resolving the choice from the stored preference at call
     *  time — this is what notifications use, so a notification posted
     *  after a switch reads the new language. */
    fun wrapStored(context: Context): Context = wrap(context, stored(context))

    /** The FragmentActivity behind a (possibly locale-wrapped) context:
     *  the biometric gate and the camera lifecycle need the real activity,
     *  which the wrapper only carries as its base. */
    tailrec fun unwrapToLifecycle(context: Context): Context? = when (context) {
        is androidx.lifecycle.LifecycleOwner -> context
        is ContextWrapper -> unwrapToLifecycle(context.baseContext)
        else -> null
    }
}
