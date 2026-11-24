package com.gentlemanprogramming.gentlemobile.ui.terminal

/**
 * The terminal's attachment decision (T49).
 *
 * "An agent runs here" is decided by what the app itself launched in this
 * terminal PTY: the state records it at launch time, so it is a fact the
 * app owns, never a guess or a probe. The terminal today launches only a
 * bare shell, so [offersCameraAndClip] is false — a bare shell would receive
 * the `[attached image: <path>]` line as a command and fail. When a launcher
 * starts an agent in the terminal, the same flag turns the buttons on.
 *
 * [suffixBytes] is the exact text the send types into the PTY: one CR for
 * the drafted line, then one `[attached image: <path>]` line per uploaded
 * image — the same lines the prompt lane composes in the Rust core.
 */
object TerminalAttachments {

    /** Camera and clip are offered only while the terminal runs an agent. */
    fun offersCameraAndClip(agentRunsHere: Boolean): Boolean = agentRunsHere

    /** The PTY bytes for the uploaded images, each on its own line. */
    fun suffixBytes(suffixes: List<String>): ByteArray =
        suffixes.flatMap { (it + "\r").toByteArray(Charsets.UTF_8).toList() }.toByteArray()
}
