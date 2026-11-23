package com.gentlemanprogramming.gentlemobile.ui.theme

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import com.gentlemanprogramming.gentlemobile.R

/**
 * The interface typeface the app draws with: the platform face by default,
 * or one of the bundled OFL faces. Applied through the `MaterialTheme`
 * typography in Theme.kt, so every screen follows without touching each
 * composable. The terminal keeps JetBrains Mono — this is the chrome's
 * face, never the terminal's.
 */
enum class AppTypeface(val label: String) {
    SYSTEM("System"),
    SPACE_GROTESK("Space Grotesk"),
    IBM_PLEX_SANS("IBM Plex Sans"),
    ALDRICH("Aldrich"),
    ;

    companion object {
        val default = SYSTEM

        /** Storage form of one choice name; unknown strings fall back to [default]. */
        fun fromStored(stored: String): AppTypeface? =
            entries.firstOrNull { it.name == stored }
    }
}

/**
 * The persisted typeface choice. Pure: unit-tested with a fake store, the
 * same contract as [ThemeStore].
 */
class TypefaceStore(private val store: KeyValueStore) {

    /** The persisted choice, or System when nothing valid was stored. */
    fun selected(): AppTypeface =
        store.getString(KEY)?.let { AppTypeface.fromStored(it) } ?: AppTypeface.default

    fun select(choice: AppTypeface) {
        store.putString(KEY, choice.name)
    }

    companion object {
        const val KEY = "app_typeface"
    }
}

/** The bundled face behind a choice: regular and bold font resources. */
data class BundledFace(val regular: Int, val bold: Int?)

/**
 * The bundled font resources behind a choice. `null` stands for the
 * platform face — only the System choice maps to null, so a bundled face
 * never silently falls back to System.
 */
fun bundledFace(choice: AppTypeface): BundledFace? = when (choice) {
    AppTypeface.SYSTEM -> null
    AppTypeface.SPACE_GROTESK -> BundledFace(R.font.space_grotesk_regular, R.font.space_grotesk_bold)
    AppTypeface.IBM_PLEX_SANS -> BundledFace(R.font.ibm_plex_sans_regular, R.font.ibm_plex_sans_bold)
    AppTypeface.ALDRICH -> BundledFace(R.font.aldrich_regular, null)
}

/** The choice and the way to change it, provided beside [LocalPalette] so
 *  the settings screen reads it without threading a parameter through the
 *  connect flow. */
val LocalAppTypeface = staticCompositionLocalOf { AppTypeface.default }
val LocalOnSetTypeface: ProvidableCompositionLocal<(AppTypeface) -> Unit> =
    staticCompositionLocalOf { {} }
