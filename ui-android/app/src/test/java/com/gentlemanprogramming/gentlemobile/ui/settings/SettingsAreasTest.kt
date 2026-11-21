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
                SettingsArea.ABOUT,
            ),
            SettingsAreas.order,
        )
    }

    @Test
    fun `the connection choice is deleted`() {
        assertFalse(SettingsArea.entries.any { it.name == "CONNECTION" })
    }

    @Test
    fun `appearance carries follow the phone and the pinned theme`() {
        assertTrue(SettingsAreas.appearanceRows.contains(SettingsRow.FOLLOW_PHONE))
        assertTrue(SettingsAreas.appearanceRows.contains(SettingsRow.PINNED_THEME))
    }
}
