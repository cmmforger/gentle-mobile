package com.gentlemanprogramming.gentlemobile.pair

/**
 * The command blocks the Settings → Hooks screen shows (T55): the commands
 * that really exist in this repo's helper, per platform — never Moshi's
 * commands. Kept as a pure builder so the per-platform text is unit-tested
 * and the screen's Copy copies exactly what the user sees.
 */
object HooksCommand {

    /**
     * macOS/Linux: the repo's own helper prints the QR this phone scans,
     * the CLI's status verb reports what is trusted, and revoke removes it.
     * One command per line; the whole block is copied at once.
     */
    fun macLinuxBlock(): String = listOf(
        "./scripts/gentle-mobile pair",
        "gentle-mobile status",
        "gentle-mobile revoke",
    ).joinToString("\n")

    /**
     * Windows: the helper is a POSIX script, so it runs under WSL — there is
     * no native Windows path yet (T55). The block says exactly that truth.
     */
    fun windowsBlock(): String = "wsl ./scripts/gentle-mobile pair"
}
