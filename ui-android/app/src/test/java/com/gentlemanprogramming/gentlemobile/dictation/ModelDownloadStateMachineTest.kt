package com.gentlemanprogramming.gentlemobile.dictation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T54: the download state machine — one model's row, as pure transitions. */
class ModelDownloadStateMachineTest {

    @Test
    fun start_enters_downloading_with_zero_received() {
        val machine = ModelDownloadStateMachine()
        machine.start(totalBytes = 77704715L)
        assertEquals(
            ModelStatus.Downloading(receivedBytes = 0L, totalBytes = 77704715L),
            machine.state,
        )
        assertTrue(machine.isDownloading())
    }

    @Test
    fun start_accepts_an_unknown_total() {
        val machine = ModelDownloadStateMachine()
        machine.start(totalBytes = null)
        assertEquals(ModelStatus.Downloading(receivedBytes = 0L, totalBytes = null), machine.state)
    }

    @Test
    fun progress_only_advances_while_downloading() {
        val machine = ModelDownloadStateMachine()
        machine.progress(5L, 100L) // ignored: not started
        assertEquals(ModelStatus.NotInstalled, machine.state)

        machine.start(100L)
        machine.progress(40L, 100L)
        assertEquals(ModelStatus.Downloading(receivedBytes = 40L, totalBytes = 100L), machine.state)
    }

    @Test
    fun complete_lands_on_installed_with_the_real_file_size() {
        val machine = ModelDownloadStateMachine()
        machine.start(100L)
        machine.progress(100L, 100L)
        machine.complete(fileSizeBytes = 77704715L)
        assertEquals(ModelStatus.Installed(fileSizeBytes = 77704715L), machine.state)
    }

    @Test
    fun a_failed_download_carries_its_reason() {
        val machine = ModelDownloadStateMachine()
        machine.start(100L)
        machine.fail("HTTP 404")
        assertEquals(ModelStatus.Failed(message = "HTTP 404"), machine.state)
    }

    @Test
    fun cancel_returns_the_row_to_not_installed() {
        val machine = ModelDownloadStateMachine()
        machine.start(100L)
        machine.progress(50L, 100L)
        machine.cancel()
        assertEquals(ModelStatus.NotInstalled, machine.state)
        assertFalse(machine.isDownloading())
    }

    @Test
    fun a_stray_event_after_cancel_never_resurrects_the_download() {
        val machine = ModelDownloadStateMachine()
        machine.start(100L)
        machine.cancel()
        machine.progress(90L, 100L)
        assertEquals(ModelStatus.NotInstalled, machine.state)
        machine.complete(90L)
        assertEquals(ModelStatus.NotInstalled, machine.state)
    }

    @Test
    fun a_failed_download_can_start_again() {
        val machine = ModelDownloadStateMachine()
        machine.start(100L)
        machine.fail("timeout")
        machine.start(100L)
        assertEquals(ModelStatus.Downloading(receivedBytes = 0L, totalBytes = 100L), machine.state)
    }

    @Test
    fun a_second_start_is_a_no_op_while_downloading() {
        val machine = ModelDownloadStateMachine()
        machine.start(100L)
        machine.progress(30L, 100L)
        machine.start(200L) // ignored
        assertEquals(ModelStatus.Downloading(receivedBytes = 30L, totalBytes = 100L), machine.state)
    }

    @Test
    fun progress_never_reports_a_negative_count() {
        val machine = ModelDownloadStateMachine()
        machine.start(100L)
        machine.progress(-5L, 100L)
        assertEquals(ModelStatus.Downloading(receivedBytes = 0L, totalBytes = 100L), machine.state)
    }

    @Test
    fun hydrate_marks_an_already_downloaded_file_as_installed() {
        // refresh re-reads disk: a file that exists is Installed no matter
        // what the machine held before — the row never shows Download twice.
        val machine = ModelDownloadStateMachine()
        machine.hydrateInstalled(fileSizeBytes = 77704715L)
        assertEquals(ModelStatus.Installed(fileSizeBytes = 77704715L), machine.state)

        val failed = ModelDownloadStateMachine()
        failed.start(100L)
        failed.fail("timeout")
        failed.hydrateInstalled(fileSizeBytes = 77704715L)
        assertEquals(ModelStatus.Installed(fileSizeBytes = 77704715L), failed.state)
    }
}
