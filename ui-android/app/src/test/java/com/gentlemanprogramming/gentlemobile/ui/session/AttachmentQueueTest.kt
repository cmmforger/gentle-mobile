package com.gentlemanprogramming.gentlemobile.ui.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composer's attachment state machine, unit-tested as pure transitions
 * (T13): picked → uploading (with byte progress) → uploaded, with failure,
 * retry and remove, and the blocking rule — a send is impossible while any
 * attachment has not landed, so a failed upload can never produce a prompt
 * that references a file that is not on the host.
 */
class AttachmentQueueTest {

    private fun queueWithUploaded(name: String = "shot.png"): Pair<AttachmentQueue, Long> {
        val queue = AttachmentQueue()
        val id = when (val added = queue.add(name, "$name", 1024)) {
            is AddResult.Accepted -> added.id
            is AddResult.Refused -> throw AssertionError("add must be accepted")
        }
        queue.onUploaded(id, "/home/u/.local/state/gentle-mobile/uploads/20260212-101500-$name", "[attached image: /home/u/.local/state/gentle-mobile/uploads/20260212-101500-$name]")
        return queue to id
    }

    @Test
    fun `an accepted attachment starts ready with its stored name`() {
        val queue = AttachmentQueue()
        val result = queue.add("my photo (1).png", "20260212-101500-my_photo__1_.png", 2048)
        assertTrue(result is AddResult.Accepted)
        val items = queue.snapshot()
        assertEquals(1, items.size)
        assertEquals("my photo (1).png", items[0].displayName)
        assertEquals("20260212-101500-my_photo__1_.png", items[0].storedName)
        assertEquals(2048L, items[0].sizeBytes)
        assertEquals(AttachmentState.Ready, items[0].state)
    }

    @Test
    fun `an attachment over the size limit is refused before it is added`() {
        val queue = AttachmentQueue()
        val result = queue.add("big.png", "20260212-101500-big.png", AttachmentQueue.MAX_UPLOAD_BYTES + 1)
        assertTrue("oversize must be refused", result is AddResult.Refused)
        assertTrue("a refused attachment never enters the queue", queue.snapshot().isEmpty())
        // Exactly at the limit is accepted.
        val edge = queue.add("edge.png", "20260212-101500-edge.png", AttachmentQueue.MAX_UPLOAD_BYTES)
        assertTrue(edge is AddResult.Accepted)
    }

    @Test
    fun `progress moves a ready attachment to uploading with its bytes`() {
        val queue = AttachmentQueue()
        val id = (queue.add("shot.png", "20260212-101500-shot.png", 1_900_000) as AddResult.Accepted).id
        queue.onProgress(id, 1_216_000)
        val state = queue.snapshot().single().state
        assertEquals(AttachmentState.Uploading(sentBytes = 1_216_000), state)
    }

    @Test
    fun `a confirmed upload carries the remote path and the prompt suffix`() {
        val (queue, id) = queueWithUploaded()
        val state = queue.snapshot().single().state
        assertTrue(state is AttachmentState.Uploaded)
        state as AttachmentState.Uploaded
        assertEquals(
            "/home/u/.local/state/gentle-mobile/uploads/20260212-101500-shot.png",
            state.remotePath,
        )
        assertEquals(
            "[attached image: /home/u/.local/state/gentle-mobile/uploads/20260212-101500-shot.png]",
            state.promptSuffix,
        )
        assertEquals(id, (queue.snapshot().single()).id)
    }

    @Test
    fun `a failed upload blocks the send`() {
        val queue = AttachmentQueue()
        val id = (queue.add("shot.png", "20260212-101500-shot.png", 1024) as AddResult.Accepted).id
        queue.onUploaded(id, "/abs/shot.png", "[attached image: /abs/shot.png]")
        queue.add("broken.png", "20260212-101500-broken.png", 1024)
        val brokenId = queue.snapshot().last().id
        queue.onProgress(brokenId, 100)
        queue.onFailed(brokenId, "the host unreachable")
        assertTrue("a failed upload blocks the send", !queue.canSend("hello"))
        // Only the uploaded suffix survives: the failed path can never reach
        // the agent.
        assertEquals(listOf("[attached image: /abs/shot.png]"), queue.promptSuffixes())
    }

    @Test
    fun `ready and uploading attachments block the send too`() {
        val queue = AttachmentQueue()
        queue.add("a.png", "20260212-101500-a.png", 1024)
        assertFalse("a not-yet-sent attachment blocks the send", queue.canSend("hello"))
        val id = queue.snapshot().single().id
        queue.onProgress(id, 512)
        assertFalse("an uploading attachment blocks the send", queue.canSend("hello"))
    }

    @Test
    fun `retry returns a failed upload to ready`() {
        val queue = AttachmentQueue()
        val id = (queue.add("shot.png", "20260212-101500-shot.png", 1024) as AddResult.Accepted).id
        queue.onProgress(id, 100)
        queue.onFailed(id, "the host unreachable")
        queue.retry(id)
        assertEquals(AttachmentState.Ready, queue.snapshot().single().state)
        // A retried (ready) upload still blocks until it lands again.
        assertFalse("a retried upload still blocks until it lands", queue.canSend("hello"))
    }

    @Test
    fun `removing a failed attachment unblocks the send`() {
        val (queue, id) = queueWithUploaded()
        queue.add("broken.png", "20260212-101500-broken.png", 1024)
        val brokenId = queue.snapshot().last().id
        queue.onFailed(brokenId, "the host unreachable")
        assertFalse(queue.canSend("hello"))
        queue.remove(brokenId)
        assertTrue("only the uploaded attachment remains, so send is free", queue.canSend("hello"))
        assertEquals(1, queue.snapshot().size)
        queue.remove(id)
        assertTrue(queue.snapshot().isEmpty())
    }

    @Test
    fun `a send needs text or one uploaded image`() {
        val queue = AttachmentQueue()
        assertFalse("nothing at all cannot send", queue.canSend(""))
        assertFalse("blank text and no uploads cannot send", queue.canSend("   "))
        // Text alone sends (the classic prompt).
        assertTrue(queue.canSend("hello"))
        // One uploaded image sends even with a blank draft.
        val (withUpload, _) = queueWithUploaded()
        assertTrue(withUpload.canSend(""))
    }

    @Test
    fun `prompt suffixes list only uploaded files in attachment order`() {
        val queue = AttachmentQueue()
        val first = (queue.add("a.png", "20260212-101500-a.png", 10) as AddResult.Accepted).id
        queue.add("b.png", "20260212-101500-b.png", 10) // stays ready
        val third = (queue.add("c.png", "20260212-101500-c.png", 10) as AddResult.Accepted).id
        queue.onUploaded(first, "/abs/a.png", "[attached image: /abs/a.png]")
        queue.onUploaded(third, "/abs/c.png", "[attached image: /abs/c.png]")
        assertEquals(
            listOf("[attached image: /abs/a.png]", "[attached image: /abs/c.png]"),
            queue.promptSuffixes(),
        )
    }

    @Test
    fun `bytes format for the attachment meta line`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1023 B", formatBytes(1023))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("241 KB", formatBytes(241 * 1024))
        assertEquals("1.2 MB", formatBytes(1_216_000))
        assertEquals("19 MB", formatBytes(19 * 1024 * 1024))
        assertEquals("20 MB", formatBytes(20 * 1024 * 1024))
    }
}
