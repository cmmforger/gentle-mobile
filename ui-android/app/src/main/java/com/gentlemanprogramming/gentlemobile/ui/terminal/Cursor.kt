package com.gentlemanprogramming.gentlemobile.ui.terminal

/** The cursor shapes the Advanced → Cursor row offers (T50). */
enum class CursorMode { BLOCK, UNDERLINE, BAR }

/** A cursor's paint rectangle as fractions of the measured cell, so the
 *  renderer scales it by the cell size it already derived — never a fixed
 *  pixel count that a font change would break. */
data class CursorRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** Pure cursor rules (T50): the shape per mode and the blink duty cycle. */
object TerminalCursor {

    /** The cell fraction one cursor mode covers: block fills the cell,
     *  underline is the bottom stripe, bar is the leading stripe. */
    fun rect(mode: CursorMode): CursorRect = when (mode) {
        CursorMode.BLOCK -> CursorRect(0f, 0f, 1f, 1f)
        CursorMode.UNDERLINE -> CursorRect(0f, 0.82f, 1f, 1f)
        CursorMode.BAR -> CursorRect(0f, 0f, 0.16f, 1f)
    }

    /** Whether the cursor paints at [elapsedMs]: solid when blink is off, a
     *  50/50 duty cycle when it is on, and never when the app hid it. */
    fun visible(cursorOn: Boolean, blink: Boolean, elapsedMs: Long, periodMs: Long = 530L): Boolean =
        if (!cursorOn) false
        else if (!blink) true
        else (elapsedMs / periodMs) % 2L == 0L
}

/** The picker's preview of a cursor shape (T57): the blink switch drives it
 *  — on, the shape blinks on the same duty cycle; off, it is steady. */
object CursorPreview {
    const val PERIOD_MS = 530L

    fun visible(blink: Boolean, elapsedMs: Long, periodMs: Long = PERIOD_MS): Boolean =
        TerminalCursor.visible(cursorOn = true, blink = blink, elapsedMs = elapsedMs, periodMs = periodMs)
}
