package com.gentlemanprogramming.gentlemobile.pair

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Settings → Hooks screen shows the commands that really exist in this
 * repo's helper, per platform (T55) — never Moshi's commands. The strings
 * are built in one place so the screen and the copy button agree.
 */
class HooksCommandTest {

    @Test
    fun `mac linux block is the helpers own pair status and revoke verbs`() {
        assertEquals(
            listOf(
                "./scripts/gentle-mobile pair",
                "gentle-mobile status",
                "gentle-mobile revoke",
            ).joinToString("\n"),
            HooksCommand.macLinuxBlock(),
        )
    }

    @Test
    fun `mac linux block has no trailing newline`() {
        val block = HooksCommand.macLinuxBlock()
        assertEquals(false, block.endsWith("\n"))
    }

    @Test
    fun `windows runs the posix helper under wsl`() {
        assertEquals("wsl ./scripts/gentle-mobile pair", HooksCommand.windowsBlock())
    }
}
