package com.gentlemanprogramming.gentlemobile.dictation

import java.io.File

/**
 * T54 (S8): where the downloaded models live — app storage only, one file
 * per catalogue entry, never in the APK. [fileFor] is the installed file,
 * [tempFileFor] is the in-flight download that atomically replaces it.
 */
class ModelRepository(private val modelsDir: File) {

    fun ensureDir() {
        if (!modelsDir.exists()) modelsDir.mkdirs()
    }

    fun fileFor(model: WhisperModel): File = File(modelsDir, model.fileName)

    fun tempFileFor(model: WhisperModel): File = File(modelsDir, model.fileName + ".part")

    fun isInstalled(model: WhisperModel): Boolean = fileFor(model).isFile

    /** The ids of every catalogue model whose file exists on disk. */
    fun installedIds(): Set<String> =
        WhisperCatalogue.models
            .filter { fileFor(it).isFile }
            .map { it.id }
            .toSet()

    /** The real on-disk size of the installed file, or null when absent. */
    fun onDiskSize(model: WhisperModel): Long? =
        fileFor(model).takeIf { it.isFile }?.length()

    /** Remove a model file; true when the file is gone afterwards. */
    fun delete(model: WhisperModel): Boolean {
        val file = fileFor(model)
        return !file.exists() || file.delete()
    }
}
