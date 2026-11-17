package com.aicamera.photo.core.vision

import com.aicamera.photo.domain.LumaImage
import com.aicamera.photo.domain.HorizonSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.tan

class ImageSignalsTest {
    private val w = 160
    private val h = 120

    /** Bright sky above a dark ground, horizon at [y0] (fraction), tilted by [deg] (positive slopes down right). */
    private fun horizonImage(y0: Float, deg: Float): LumaImage {
        val px = ByteArray(w * h)
        val slope = tan(Math.toRadians(deg.toDouble())).toFloat()
        for (y in 0 until h) for (x in 0 until w) {
            val lineY = y0 * h + (x - w / 2f) * slope
            px[y * w + x] = (if (y < lineY) 210 else 60).toByte()
        }
        return LumaImage(w, h, px)
    }

    @Test
    fun detectsLevelHorizonPosition() {
        val r = ImageSignals().compute(horizonImage(0.4f, 0f), null, emptyList(), null, 1f)
        val line = r.horizonLine
        assertNotNull(line)
        assertEquals(0.4f, line!!.positionY, 0.04f)
        assertEquals(0f, line.angleDegrees, 2.5f)
    }

    @Test
    fun detectsTiltSign() {
        val r = ImageSignals().compute(horizonImage(0.5f, 6f), null, emptyList(), null, 1f)
        val line = r.horizonLine
        assertNotNull(line)
        assertTrue("expected positive tilt, got ${line!!.angleDegrees}", line.angleDegrees > 3f)
    }

    @Test
    fun sensorTiltWinsWhenAvailable() {
        val fused = DefaultSceneAnalyzer.fuseHorizon(1.0f, HorizonLine(5f, 0.5f, 0.8f))
        assertEquals(1.0f, fused!!.tiltDegrees, 1e-4f)
        assertEquals(HorizonSource.FUSED, fused.source)
        assertNull(DefaultSceneAnalyzer.fuseHorizon(null, null))
    }

    @Test
    fun flatImageIsNotSymmetricOrSharp() {
        val flat = LumaImage(w, h, ByteArray(w * h) { 120.toByte() })
        val r = ImageSignals().compute(flat, null, emptyList(), null, 1f)
        assertEquals(0f, r.signals.symmetry, 1e-4f)
        assertTrue(r.signals.sharpness < 0.1f)
        assertNull(r.horizonLine)
    }

    @Test
    fun motionDetectedBetweenFrames() {
        val s = ImageSignals()
        s.compute(horizonImage(0.3f, 0f), null, emptyList(), null, 1f)
        val r = s.compute(horizonImage(0.7f, 0f), null, emptyList(), null, 1f)
        assertTrue(r.signals.motion > 0.3f)
    }

    @Test
    fun clippingMeasured() {
        val bright = LumaImage(w, h, ByteArray(w * h) { 255.toByte() })
        val r = ImageSignals().compute(bright, null, emptyList(), null, 1f)
        assertEquals(1f, r.signals.highlightClipping, 1e-4f)
    }
}
