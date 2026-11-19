package com.gentlemanprogramming.gentlemobile.ui.session

/**
 * The composer's attachment queue (T13): the honest state machine between
 * picking an image and sending a prompt that references it.
 *
 * Pure Kotlin on purpose — no Android types — so the transitions are
 * unit-tested as plain data. The Android side (pickers, thumbnails, the
 * UniFFI upload call) drives the queue; the queue alone decides whether a
 * send is possible and which prompt suffixes it may carry.
 *
 * The blocking rule this module enforces:
 *
 * * an attachment that has not landed on the host (ready, uploading, or
 *   failed) blocks the send entirely;
 * * [promptSuffixes] only ever returns suffixes handed back by confirmed
 *   uploads — a failed upload can never reach the agent as a path, because
 *   the failed state neither blocks-with-a-path nor contributes a suffix.
 *
 * [MAX_UPLOAD_BYTES] mirrors the core's `uploads::MAX_UPLOAD_BYTES`; the
 * core re-checks the limit at the transfer, this guard only makes the
 * refusal instant at pick time.
 */
class AttachmentQueue {

    companion object {
        /** Mirrors `uploads::MAX_UPLOAD_BYTES` in the Rust core. */
        const val MAX_UPLOAD_BYTES: Long = 20L * 1024 * 1024
    }

    private var nextId: Long = 1L
    private val items = mutableListOf<Attachment>()

    /** A stable copy, so UI reads never race a mutation. */
    fun snapshot(): List<Attachment> = items.toList()

    /**
     * Add a picked image in the ready state. Oversize files are refused
     * before they enter the queue — the UI shows the reason and nothing is
     * ever half-added. [tooLargeMessage] builds the refusal in the user's
     * language (S24-1); the default keeps the English contract the pure
     * tests assert.
     */
    fun add(
        displayName: String,
        storedName: String,
        sizeBytes: Long,
        tooLargeMessage: (sizeBytes: Long, maxBytes: Long) -> String = ::defaultTooLargeMessage,
    ): AddResult {
        if (sizeBytes > MAX_UPLOAD_BYTES) {
            return AddResult.Refused(tooLargeMessage(sizeBytes, MAX_UPLOAD_BYTES))
        }
        val id = nextId++
        items.add(
            Attachment(
                id = id,
                displayName = displayName,
                storedName = storedName,
                sizeBytes = sizeBytes,
                state = AttachmentState.Ready,
            ),
        )
        return AddResult.Accepted(id)
    }

    /** Progress from the core's per-chunk callback; bytes never go backwards. */
    fun onProgress(id: Long, sentBytes: Long) {
        mutate(id) { attachment ->
            val current = (attachment.state as? AttachmentState.Uploading)?.sentBytes ?: 0L
            attachment.copy(state = AttachmentState.Uploading(maxOf(sentBytes, current)))
        }
    }

    /** A confirmed upload: the core's outcome carries the path and the suffix. */
    fun onUploaded(id: Long, remotePath: String, promptSuffix: String) {
        mutate(id) { attachment ->
            attachment.copy(state = AttachmentState.Uploaded(remotePath, promptSuffix))
        }
    }

    /** The upload failed: honest state, no path, send blocked until resolved. */
    fun onFailed(id: Long, message: String) {
        mutate(id) { attachment -> attachment.copy(state = AttachmentState.Failed(message)) }
    }

    /** Retry a failed upload: back to ready, the UI starts the transfer again. */
    fun retry(id: Long) {
        mutate(id) { attachment ->
            if (attachment.state is AttachmentState.Failed) {
                attachment.copy(state = AttachmentState.Ready)
            } else {
                attachment
            }
        }
    }

    /** Remove an attachment entirely; the send no longer waits for it. */
    fun remove(id: Long) {
        items.removeAll { it.id == id }
    }

    /**
     * A send is possible only when nothing is in flight and something has
     * content: text in the draft, or at least one uploaded image.
     */
    fun canSend(draft: String): Boolean {
        val pending = items.any {
            it.state is AttachmentState.Ready ||
                it.state is AttachmentState.Uploading ||
                it.state is AttachmentState.Failed
        }
        if (pending) return false
        return draft.isNotBlank() || items.any { it.state is AttachmentState.Uploaded }
    }

    /**
     * The prompt suffixes a send may carry: only confirmed uploads, in
     * attachment order. Handed to the core's `composePrompt`.
     */
    fun promptSuffixes(): List<String> = items.mapNotNull {
        (it.state as? AttachmentState.Uploaded)?.promptSuffix
    }

    private fun mutate(id: Long, change: (Attachment) -> Attachment) {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) items[index] = change(items[index])
    }
}

/** The default (English) oversize refusal, the pure contract the tests
 *  rely on; the UI passes a localized builder instead (S24-1). */
fun defaultTooLargeMessage(sizeBytes: Long, maxBytes: Long): String =
    "the file is too large: ${formatBytes(sizeBytes)}, the host accepts up to " +
        formatBytes(maxBytes)

/** One attachment in the composer, with its honest state. */
data class Attachment(
    val id: Long,
    /** The name the user picked, shown in the preview row. */
    val displayName: String,
    /** The core's timestamp-prefixed, sanitised stored name. */
    val storedName: String,
    val sizeBytes: Long,
    val state: AttachmentState,
)

/** The states an attachment moves through, exactly the design board's. */
sealed interface AttachmentState {
    /** Picked, its upload not started yet. */
    object Ready : AttachmentState

    /** In flight, with the bytes the host has confirmed so far. */
    data class Uploading(val sentBytes: Long) : AttachmentState

    /** The host has the file; carries the path and the prompt suffix. */
    data class Uploaded(val remotePath: String, val promptSuffix: String) : AttachmentState

    /** The upload failed; nothing may reference this file. */
    data class Failed(val message: String) : AttachmentState
}

/** The outcome of adding a picked file. */
sealed interface AddResult {
    data class Accepted(val id: Long) : AddResult
    data class Refused(val reason: String) : AddResult
}

/**
 * Human bytes for the attachment meta line (`241 KB`, `1.2 MB`), matching
 * the design board's wording.
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) {
        return if (kb >= 10) "${kb.toInt()} KB" else String.format("%.1f KB", kb)
    }
    val mb = kb / 1024.0
    return if (mb >= 10) "${mb.toInt()} MB" else String.format("%.1f MB", mb)
}
