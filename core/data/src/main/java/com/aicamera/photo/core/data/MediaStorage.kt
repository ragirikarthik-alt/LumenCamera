package com.aicamera.photo.core.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Private app storage layout. Originals, RAW files, developed outputs, thumbnails and model assets
 * live under the app's private files directory; only exports go to shared storage (MediaStore).
 */
class MediaStorage(context: Context) {
    private val root = File(context.filesDir, "media")
    val originalsDir = File(root, "originals")
    val rawDir = File(root, "raw")
    val developedDir = File(root, "developed")
    val thumbnailsDir = File(root, "thumbnails")
    val modelsDir = File(context.filesDir, "models")
    val cacheDir: File = context.cacheDir

    init {
        listOf(originalsDir, rawDir, developedDir, thumbnailsDir, modelsDir).forEach { it.mkdirs() }
    }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun newPhotoId(): String = UUID.randomUUID().toString()

    fun originalFileFor(photoId: String) = File(originalsDir, "LUMEN_${stamp()}_${photoId.take(8)}.jpg")
    fun rawFileFor(photoId: String) = File(rawDir, "LUMEN_${stamp()}_${photoId.take(8)}.dng")
    fun developedFileFor(editId: String) = File(developedDir, "LUMEN_DEV_${stamp()}_${editId.take(8)}.jpg")
    fun thumbnailFor(photoId: String) = File(thumbnailsDir, "$photoId.jpg")

    data class Usage(val originals: Long, val raw: Long, val developed: Long, val thumbnails: Long, val models: Long) {
        val total get() = originals + raw + developed + thumbnails + models
    }

    fun usage(): Usage = Usage(
        originals = originalsDir.sizeRecursive(),
        raw = rawDir.sizeRecursive(),
        developed = developedDir.sizeRecursive(),
        thumbnails = thumbnailsDir.sizeRecursive(),
        models = modelsDir.sizeRecursive(),
    )

    fun deleteAllMedia() {
        listOf(originalsDir, rawDir, developedDir, thumbnailsDir).forEach { dir ->
            dir.listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    fun deleteDeveloped() {
        developedDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    fun clearThumbnails() {
        thumbnailsDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    /** Only deletes files inside app-managed storage. */
    fun deleteManagedFile(path: String?) {
        if (path == null) return
        val f = File(path)
        if (f.canonicalPath.startsWith(root.canonicalPath) || f.canonicalPath.startsWith(modelsDir.canonicalPath)) f.delete()
    }

    private fun File.sizeRecursive(): Long = if (!exists()) 0 else walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
