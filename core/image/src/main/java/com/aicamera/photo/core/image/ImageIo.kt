package com.aicamera.photo.core.image

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.aicamera.photo.domain.FilmRecipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object ImageIo {

    /** Decodes [file] upright (EXIF rotation applied) with its long side at most [maxSide]. */
    fun decodeUpright(file: File, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0) { "Cannot read ${file.name}" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val raw = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }) ?: error("Cannot decode ${file.name}")
        val rotation = runCatching { ExifInterface(file).rotationDegrees }.getOrDefault(0)
        val scale = (maxSide.toFloat() / maxOf(raw.width, raw.height)).coerceAtMost(1f)
        if (rotation == 0 && scale >= 0.999f) return raw
        val m = Matrix().apply { postScale(scale, scale); postRotate(rotation.toFloat()) }
        val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (out != raw) raw.recycle()
        return if (out.isMutable) out else out.copy(Bitmap.Config.ARGB_8888, true).also { out.recycle() }
    }

    fun writeJpeg(bitmap: Bitmap, file: File, quality: Int = 95) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    /** Copies descriptive EXIF tags (capture time, camera, exposure) from the original to a developed output. */
    fun copyExif(from: File, to: File, software: String) {
        runCatching {
            val src = ExifInterface(from)
            val dst = ExifInterface(to)
            val tags = listOf(
                ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
                ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
                ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_FLASH,
                ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            )
            for (t in tags) src.getAttribute(t)?.let { dst.setAttribute(t, it) }
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            dst.setAttribute(ExifInterface.TAG_SOFTWARE, software)
            dst.saveAttributes()
        }
    }
}

/**
 * Non-destructive development: reads the original, renders the recipe into a NEW file and never
 * writes to the original path.
 */
class Developer {
    /** Longest side for the full-quality developed output (bounded for memory safety). */
    var maxOutputSide = 6000

    suspend fun develop(original: File, recipe: FilmRecipe, output: File): File = withContext(Dispatchers.Default) {
        require(original.canonicalPath != output.canonicalPath) { "Refusing to overwrite the original" }
        val bitmap = ImageIo.decodeUpright(original, maxOutputSide)
        try {
            FilmRenderer.renderInPlace(bitmap, recipe)
            withContext(Dispatchers.IO) {
                ImageIo.writeJpeg(bitmap, output, 95)
                ImageIo.copyExif(original, output, "Lumen Camera Film Lab (${recipe.profileId})")
            }
        } finally {
            bitmap.recycle()
        }
        output
    }

    suspend fun thumbnail(source: File, output: File, side: Int = 480): File = withContext(Dispatchers.IO) {
        val bmp = ImageIo.decodeUpright(source, side)
        try { ImageIo.writeJpeg(bmp, output, 85) } finally { bmp.recycle() }
        output
    }
}

/** Exports app-private media to shared storage via MediaStore (Pictures/Lumen Camera). */
class MediaExporter(private val context: Context) {

    suspend fun export(file: File, displayName: String, mimeType: String = "image/jpeg"): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Lumen Camera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: error("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: error("Cannot open output stream")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }
}
