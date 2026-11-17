package com.aicamera.photo.core.camera

import androidx.camera.core.ImageProxy
import com.aicamera.photo.domain.ChromaGrid
import com.aicamera.photo.domain.LumaImage

/**
 * Converts YUV_420_888 analysis frames into a small, world-upright luma image and a coarse chroma grid
 * without creating Bitmaps. Point sampling keeps the cost to a few thousand reads per frame.
 */
object FrameConverter {

    /**
     * @param rotationCw clockwise rotation (0/90/180/270) to apply to the sensor image so it is world-upright.
     * @param targetLongSide approximate long side of the output, in pixels.
     */
    fun luma(image: ImageProxy, rotationCw: Int, targetLongSide: Int = 160): LumaImage {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val srcW = image.width
        val srcH = image.height
        val step = (maxOf(srcW, srcH) / targetLongSide).coerceAtLeast(1)
        val rotated = rotationCw == 90 || rotationCw == 270
        val fullW = if (rotated) srcH else srcW
        val fullH = if (rotated) srcW else srcH
        val outW = fullW / step
        val outH = fullH / step
        val out = ByteArray(outW * outH)
        val limit = buffer.limit()
        for (oy in 0 until outH) {
            val ry = oy * step
            for (ox in 0 until outW) {
                val rx = ox * step
                val sx: Int
                val sy: Int
                when (rotationCw) {
                    90 -> { sx = ry; sy = srcH - 1 - rx }
                    180 -> { sx = srcW - 1 - rx; sy = srcH - 1 - ry }
                    270 -> { sx = srcW - 1 - ry; sy = rx }
                    else -> { sx = rx; sy = ry }
                }
                val idx = sy * rowStride + sx * pixelStride
                out[oy * outW + ox] = if (idx in 0 until limit) buffer.get(idx) else 0
            }
        }
        return LumaImage(outW, outH, out)
    }

    fun chroma(image: ImageProxy, rotationCw: Int, cols: Int = 8, rows: Int = 6): ChromaGrid? {
        if (image.planes.size < 3) return null
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val srcW = image.width
        val srcH = image.height
        val rotated = rotationCw == 90 || rotationCw == 270
        val fullW = if (rotated) srcH else srcW
        val fullH = if (rotated) srcW else srcH
        val gc = if (rotated) rows else cols
        val gr = if (rotated) cols else rows
        // Grid in world-upright orientation: use cols x rows matching the output aspect.
        val outCols = if (fullW >= fullH) maxOf(gc, gr) else minOf(gc, gr)
        val outRows = if (fullW >= fullH) minOf(gc, gr) else maxOf(gc, gr)
        val u = FloatArray(outCols * outRows)
        val v = FloatArray(outCols * outRows)
        val uLimit = uPlane.buffer.limit()
        val vLimit = vPlane.buffer.limit()
        for (r in 0 until outRows) {
            for (c in 0 until outCols) {
                var su = 0f
                var sv = 0f
                var n = 0
                for (dy in 0..2) for (dx in 0..2) {
                    val rx = ((c + (dx + 0.5f) / 3f) * fullW / outCols).toInt().coerceIn(0, fullW - 1)
                    val ry = ((r + (dy + 0.5f) / 3f) * fullH / outRows).toInt().coerceIn(0, fullH - 1)
                    val sx: Int
                    val sy: Int
                    when (rotationCw) {
                        90 -> { sx = ry; sy = srcH - 1 - rx }
                        180 -> { sx = srcW - 1 - rx; sy = srcH - 1 - ry }
                        270 -> { sx = srcW - 1 - ry; sy = rx }
                        else -> { sx = rx; sy = ry }
                    }
                    val cx = sx / 2
                    val cy = sy / 2
                    val ui = cy * uPlane.rowStride + cx * uPlane.pixelStride
                    val vi = cy * vPlane.rowStride + cx * vPlane.pixelStride
                    if (ui in 0 until uLimit && vi in 0 until vLimit) {
                        su += ((uPlane.buffer.get(ui).toInt() and 0xFF) - 128) / 128f
                        sv += ((vPlane.buffer.get(vi).toInt() and 0xFF) - 128) / 128f
                        n++
                    }
                }
                if (n > 0) {
                    u[r * outCols + c] = su / n
                    v[r * outCols + c] = sv / n
                }
            }
        }
        return ChromaGrid(outCols, outRows, u, v)
    }
}
