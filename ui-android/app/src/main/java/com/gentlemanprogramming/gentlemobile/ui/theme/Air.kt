package com.gentlemanprogramming.gentlemobile.ui.theme

/**
 * The air rules (board `10-air-rules.html`, S33) as the only numbers the
 * covered screens — session, workspaces, settings — may use. Values live
 * here, not in the composables, so the board's scale cannot drift screen by
 * screen.
 */
object Air {
    // Type: title 28/700, row name 15/600, body 15/400, command 13 mono,
    // section label 11/500 uppercase, meta 11 mono.
    const val TITLE_SP = 28
    const val ROW_NAME_SP = 15
    const val BODY_SP = 15
    const val COMMAND_SP = 13
    const val SECTION_LABEL_SP = 11
    const val META_SP = 11
    /** The section label's tracking: .08em at the label size = .88 sp. */
    const val SECTION_TRACKING_SP = 0.88f
    const val SECTION_TRACKING_EM = 0.08f

    // Rhythm: sides 16, top 12, between cards 12, card padding 16,
    // touch floor 44.
    const val SIDE_DP = 16
    const val TOP_DP = 12
    const val BETWEEN_CARDS_DP = 12
    const val CARD_PADDING_DP = 16
    const val TOUCH_DP = 44

    // Surface: card radius 16, terminal/composer 12, fields 8, hairline 1.
    const val RADIUS_CARD_DP = 16
    const val RADIUS_TERMINAL_DP = 12
    const val RADIUS_FIELD_DP = 8
    const val HAIRLINE_DP = 1

    /** Lines: body 1.5, row name 1.35, title 1.2 (× the sp size). */
    const val BODY_LINE_HEIGHT = 1.5f
    const val ROW_NAME_LINE_HEIGHT = 1.35f
    const val TITLE_LINE_HEIGHT = 1.2f
}
