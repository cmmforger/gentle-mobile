package com.gentlemanprogramming.gentlemobile.ui.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalAttachmentsTest {

    @Test
    fun an_agent_terminal_offers_camera_and_clip() {
        assertTrue(TerminalAttachments.offersCameraAndClip(true))
    }

    @Test
    fun a_bare_shell_offers_no_attachments() {
        assertFalse(TerminalAttachments.offersCameraAndClip(false))
    }

    @Test
    fun no_uploaded_images_types_no_suffix_bytes() {
        assertArrayEquals(ByteArray(0), TerminalAttachments.suffixBytes(emptyList()))
    }

    @Test
    fun each_uploaded_image_types_one_cr_terminated_line() {
        val bytes = TerminalAttachments.suffixBytes(
            listOf(
                "[attached image: /home/u/a.png]",
                "[attached image: /home/u/b.png]",
            ),
        )
        assertArrayEquals(
            "[attached image: /home/u/a.png]\r[attached image: /home/u/b.png]\r".toByteArray(Charsets.UTF_8),
            bytes,
        )
    }
}
