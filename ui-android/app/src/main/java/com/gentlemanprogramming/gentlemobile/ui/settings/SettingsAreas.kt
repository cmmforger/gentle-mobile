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
    VOZ,
    TYPEFACE,
    TERMINAL_FONT,
    SHORTCUTS,
    ADVANCED,
    ABOUT,
}

/** The rows the Appearance card carries: the follow switch and the pinned theme. */
enum class SettingsRow {
    FOLLOW_PHONE,
    PINNED_THEME,
}

/** The rows the Security card carries (T50): what a person can act on —
 *  the biometric gate and host pinning. The device key and the saved hosts
 *  left: the identity is real but not a settings card, and deleting a host
 *  lives on the host row itself. */
enum class SecurityRow {
    BIOMETRIC,
    PINNING,
}

object SettingsAreas {
    val order: List<SettingsArea> = listOf(
        SettingsArea.SECURITY,
        SettingsArea.APPEARANCE,
        SettingsArea.LANGUAGE,
        SettingsArea.VOZ,
        SettingsArea.TYPEFACE,
        SettingsArea.TERMINAL_FONT,
        SettingsArea.SHORTCUTS,
        SettingsArea.ADVANCED,
        SettingsArea.ABOUT,
    )

    val appearanceRows: List<SettingsRow> = listOf(
        SettingsRow.FOLLOW_PHONE,
        SettingsRow.PINNED_THEME,
    )

    val securityRows: List<SecurityRow> = listOf(
        SecurityRow.BIOMETRIC,
        SecurityRow.PINNING,
    )
}
