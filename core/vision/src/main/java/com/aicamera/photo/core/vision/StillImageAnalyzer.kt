package com.aicamera.photo.core.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.aicamera.photo.domain.AnalysisFrame
import com.aicamera.photo.domain.ChromaGrid
import com.aicamera.photo.domain.LumaImage
import com.aicamera.photo.domain.SceneSnapshot
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Analyses the final full-resolution photograph (not the preview) for post-capture critique.
 * Decodes a bounded-size, EXIF-upright bitmap to keep memory predictable.
 */
class StillImageAnalyzer : AutoCloseable {
    private val detectors = MlKitDetectors(streamMode = false)

    data class Result(val snapshot: SceneSnapshot, val luma: LumaImage, val preview: Bitmap)

    suspend fun analyze(file: File, captureTiltDegrees: Float?): Result = withContext(Dispatchers.Default) {
        val bitmap = decodeUpright(file, 1280)
        val luma = lumaFrom(bitmap, 480)
        val chroma = chromaFrom(bitmap)
        val analyzer = DefaultSceneAnalyzer(detectors, ImageSignals(sharpnessK = 260f), trackMotion = false)
        val frame = AnalysisFrame(
            timestamp = System.currentTimeMillis(),
            luma = luma,
            chroma = chroma,
            worldRotation = 0,
            deviceTiltDegrees = captureTiltDegrees,
            deviceStability = 1f,
            platformImage = PlatformInput(InputImage.fromBitmap(bitmap, 0), bitmap.width, bitmap.height),
            runSceneClassifier = true,
        )
        Result(analyzer.analyze(frame), luma, bitmap)
    }

    override fun close() = detectors.close()

    companion object {
        fun decodeUpright(file: File, maxSide: Int): Bitmap {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = BitmapFactory.decodeFile(file.absolutePath, opts) ?: error("Cannot decode ${file.name}")
            val rotation = runCatching { ExifInterface(file).rotationDegrees }.getOrDefault(0)
            val scale = (maxSide.toFloat() / maxOf(raw.width, raw.height)).coerceAtMost(1f)
            if (rotation == 0 && scale >= 0.999f) return raw
            val m = Matrix().apply {
                postScale(scale, scale)
                postRotate(rotation.toFloat())
            }
            val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            if (out != raw) raw.recycle()
            return out
        }

        fun lumaFrom(bitmap: Bitmap, longSide: Int): LumaImage {
            val scale = longSide.toFloat() / maxOf(bitmap.width, bitmap.height)
            val w = (bitmap.width * scale).toInt().coerceAtLeast(16)
            val h = (bitmap.height * scale).toInt().coerceAtLeast(16)
            val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            if (small != bitmap) small.recycle()
            val out = ByteArray(w * h)
            for (i in px.indices) {
                val c = px[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                out[i] = ((r * 77 + g * 150 + b * 29) shr 8).toByte()
            }
            return LumaImage(w, h, out)
        }

        fun chromaFrom(bitmap: Bitmap, cols: Int = 8, rows: Int = 6): ChromaGrid {
            val landscape = bitmap.width >= bitmap.height
            val c = if (landscape) cols else rows
            val r = if (landscape) rows else cols
            val u = FloatArray(c * r)
            val v = FloatArray(c * r)
            for (gy in 0 until r) for (gx in 0 until c) {
                var su = 0f
                var sv = 0f
                var n = 0
                for (dy in 0..2) for (dx in 0..2) {
                    val x = ((gx + (dx + 0.5f) / 3f) * bitmap.width / c).toInt().coerceIn(0, bitmap.width - 1)
                    val y = ((gy + (dy + 0.5f) / 3f) * bitmap.height / r).toInt().coerceIn(0, bitmap.height - 1)
                    val p = bitmap.getPixel(x, y)
                    val rr = ((p shr 16) and 0xFF) / 255f
                    val gg = ((p shr 8) and 0xFF) / 255f
                    val bb = (p and 0xFF) / 255f
                    val yv = 0.299f * rr + 0.587f * gg + 0.114f * bb
                    su += (bb - yv) * 0.564f * 2f
                    sv += (rr - yv) * 0.713f * 2f
                    n++
                }
                u[gy * c + gx] = su / n
                v[gy * c + gx] = sv / n
            }
            return ChromaGrid(c, r, u, v)
        }
    }
}
