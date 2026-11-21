package com.gentlemanprogramming.gentlemobile.ui.settings

/**
 * The settings screen's grouping (board `07-settings.html`, L31): one card
 * per area, in the board's order, and the Connection choice is gone — the
 * app talks to herdr, there is nothing to choose. Pure data so the grouping
 * is pinned by a test, not by reading the composable.
 */
enum class SettingsArea {
    SECURITY,
    APPEARANCE,
    LANGUAGE,
    TYPEFACE,
    TERMINAL_FONT,
    ABOUT,
}

/** The rows the Appearance card carries: the follow switch and the pinned theme. */
enum class SettingsRow {
    FOLLOW_PHONE,
    PINNED_THEME,
}

object SettingsAreas {
    val order: List<SettingsArea> = listOf(
        SettingsArea.SECURITY,
        SettingsArea.APPEARANCE,
        SettingsArea.LANGUAGE,
        SettingsArea.TYPEFACE,
        SettingsArea.TERMINAL_FONT,
        SettingsArea.ABOUT,
    )

    val appearanceRows: List<SettingsRow> = listOf(
        SettingsRow.FOLLOW_PHONE,
        SettingsRow.PINNED_THEME,
    )
}
