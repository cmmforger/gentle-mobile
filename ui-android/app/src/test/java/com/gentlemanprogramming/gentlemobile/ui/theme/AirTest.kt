package com.gentlemanprogramming.gentlemobile.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The air rules (board `10-air-rules.html`, S33) as numbers, pinned where the
 * screens read them: type scale, rhythm, radii, hairline and touch floor.
 * A screen that invents its own numbers drifts off the board; a screen that
 * reads [Air] cannot.
 */
class AirTest {

    @Test
    fun `the type scale is the board's`() {
        assertEquals(28, Air.TITLE_SP)
        assertEquals(15, Air.ROW_NAME_SP)
        assertEquals(15, Air.BODY_SP)
        assertEquals(13, Air.COMMAND_SP)
        assertEquals(11, Air.SECTION_LABEL_SP)
        assertEquals(11, Air.META_SP)
    }

    @Test
    fun `the rhythm and spacing are the board's`() {
        assertEquals(16, Air.SIDE_DP)
        assertEquals(12, Air.TOP_DP)
        assertEquals(12, Air.BETWEEN_CARDS_DP)
        assertEquals(16, Air.CARD_PADDING_DP)
        assertEquals(44, Air.TOUCH_DP)
    }

    @Test
    fun `the radii, the hairline and the section tracking are the board's`() {
        assertEquals(16, Air.RADIUS_CARD_DP)
        assertEquals(12, Air.RADIUS_TERMINAL_DP)
        assertEquals(8, Air.RADIUS_FIELD_DP)
        assertEquals(1, Air.HAIRLINE_DP)
        assertEquals(0.08f, Air.SECTION_TRACKING_EM)
    }
}
