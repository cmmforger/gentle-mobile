package com.gentlemanprogramming.gentlemobile.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings screen is grouped into cards (board `07-settings.html`, L31):
 * one card per area, and the Connection choice is gone — there is nothing to
 * choose, the app talks to herdr. The grouping is a pure list; this pins its
 * order and its members.
 */
class SettingsAreasTest {

    @Test
    fun `the areas are the board's cards in the board's order`() {
        assertEquals(
            listOf(
                SettingsArea.SECURITY,
                SettingsArea.APPEARANCE,
                SettingsArea.LANGUAGE,
                SettingsArea.TYPEFACE,
                SettingsArea.TERMINAL_FONT,
                SettingsArea.SHORTCUTS,
                SettingsArea.ADVANCED,
                SettingsArea.ABOUT,
            ),
            SettingsAreas.order,
        )
    }

    @Test
    fun `the security card carries exactly what a person can act on`() {
        // T50: the device key and saved hosts left the settings screen — the
        // identity is real but not a card, and host deletion lives on the
        // host row. Biometric and pinning remain.
        assertEquals(
            listOf(
                SecurityRow.BIOMETRIC,
                SecurityRow.PINNING,
            ),
            SettingsAreas.securityRows,
        )
        assertFalse(SettingsArea.entries.any { it.name == "CONNECTION" })
        assertFalse(SettingsArea.entries.any { it.name == "SESSIONS_DIR" })
    }

    @Test
    fun `appearance carries follow the phone and the pinned theme`() {
        assertTrue(SettingsAreas.appearanceRows.contains(SettingsRow.FOLLOW_PHONE))
        assertTrue(SettingsAreas.appearanceRows.contains(SettingsRow.PINNED_THEME))
    }
}
