package com.gentlemanprogramming.gentlemobile.dictation

/**
 * T54 (S8): the model manager's download state, per model — pure so the
 * transitions are pinned by a unit test. The real downloader drives this
 * machine from HTTP callbacks; the machine alone decides what the row shows.
 *
 * The states and their transitions:
 * * NotInstalled → Downloading(0, total) on start;
 * * Downloading → Downloading(received, total) on every progress tick;
 * * Downloading → Installed(fileSize) on a completed download;
 * * Downloading → Failed(message) on a failed download;
 * * Downloading → NotInstalled on cancel (the row returns to "Download").
 * Any event outside its state is a no-op — a stray progress after a cancel
 * or a failure never resurrects a download.
 */
sealed interface ModelStatus {
    data object NotInstalled : ModelStatus

    data class Downloading(val receivedBytes: Long, val totalBytes: Long?) : ModelStatus

    data class Installed(val fileSizeBytes: Long) : ModelStatus

    data class Failed(val message: String) : ModelStatus
}

class ModelDownloadStateMachine(initial: ModelStatus = ModelStatus.NotInstalled) {

    var state: ModelStatus = initial
        private set

    /** Begin a download with the HTTP Content-Length, when it is known. */
    fun start(totalBytes: Long?) {
        if (state is ModelStatus.Downloading) return
        state = ModelStatus.Downloading(receivedBytes = 0L, totalBytes = totalBytes)
    }

    /** A progress tick; only meaningful while downloading. */
    fun progress(receivedBytes: Long, totalBytes: Long?) {
        if (state !is ModelStatus.Downloading) return
        state = ModelStatus.Downloading(
            receivedBytes = receivedBytes.coerceAtLeast(0L),
            totalBytes = totalBytes,
        )
    }

    /** The file landed on disk with its real size. */
    fun complete(fileSizeBytes: Long) {
        if (state !is ModelStatus.Downloading) return
        state = ModelStatus.Installed(fileSizeBytes)
    }

    /** Rehydrate from disk (refresh): the file already exists, so the row
     *  is Installed with its real size regardless of the previous state. */
    fun hydrateInstalled(fileSizeBytes: Long) {
        state = ModelStatus.Installed(fileSizeBytes)
    }

    /** The download failed; [message] is the honest reason the row shows. */
    fun fail(message: String) {
        if (state !is ModelStatus.Downloading) return
        state = ModelStatus.Failed(message)
    }

    /** The user cancelled; the row returns to "Download". */
    fun cancel() {
        if (state !is ModelStatus.Downloading) return
        state = ModelStatus.NotInstalled
    }

    fun isDownloading(): Boolean = state is ModelStatus.Downloading
}
