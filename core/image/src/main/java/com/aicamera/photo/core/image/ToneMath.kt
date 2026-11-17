package com.aicamera.photo.core.image

import com.aicamera.photo.domain.FilmRecipe
import kotlin.math.pow

/** Pure tone math used to build per-channel lookup tables. JVM-testable. */
object ToneMath {

    /** Monotone piecewise-cubic (Fritsch-Carlson) interpolation through control points. */
    fun curve(points: List<Pair<Float, Float>>): FloatArray {
        val pts = points.sortedBy { it.first }
        val n = pts.size
        val xs = FloatArray(n) { pts[it].first }
        val ys = FloatArray(n) { pts[it].second }
        val out = FloatArray(256)
        if (n < 2) {
            for (i in 0 until 256) out[i] = i / 255f
            return out
        }
        val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]).coerceAtLeast(1e-6f) }
        val m = FloatArray(n)
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        for (i in 0 until n - 1) {
            if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f; continue }
            val a = m[i] / d[i]
            val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / kotlin.math.sqrt(s)
                m[i] = t * a * d[i]
                m[i + 1] = t * b * d[i]
            }
        }
        var seg = 0
        for (i in 0 until 256) {
            val x = i / 255f
            while (seg < n - 2 && x > xs[seg + 1]) seg++
            val x0 = xs[seg]
            val x1 = xs[seg + 1]
            val h = (x1 - x0).coerceAtLeast(1e-6f)
            val t = ((x - x0) / h).coerceIn(0f, 1f)
            val t2 = t * t
            val t3 = t2 * t
            val y = (2 * t3 - 3 * t2 + 1) * ys[seg] + (t3 - 2 * t2 + t) * h * m[seg] +
                (-2 * t3 + 3 * t2) * ys[seg + 1] + (t3 - t2) * h * m[seg + 1]
            out[i] = y.coerceIn(0f, 1f)
        }
        return out
    }

    /** Global tone adjustments applied to a normalized value (shared by all channels). */
    fun tone(v: Float, r: FilmRecipe): Float {
        var x = v
        // Exposure in stops, applied in an approximately linear domain.
        if (r.exposure != 0f) {
            val lin = x.toDouble().pow(2.2)
            x = (lin * 2.0.pow(r.exposure.toDouble())).pow(1 / 2.2).toFloat().coerceIn(0f, 1f)
        }
        // Contrast: S-curve around mid grey.
        if (r.contrast != 0f) {
            val k = 1f + r.contrast * 0.8f
            x = (0.5f + (x - 0.5f) * k).coerceIn(0f, 1f)
            // Soften clipping with a smoothstep blend when increasing contrast.
            if (r.contrast > 0) x = x * x * (3 - 2 * x) * r.contrast * 0.3f + x * (1 - r.contrast * 0.3f)
        }
        // Highlights affect the upper range, shadows the lower range (smooth weights).
        if (r.highlights != 0f) {
            val w = smooth(0.45f, 1f, x)
            x += r.highlights * 0.25f * w * (if (r.highlights < 0) x else (1 - x))
        }
        if (r.shadows != 0f) {
            val w = 1f - smooth(0f, 0.55f, x)
            x += r.shadows * 0.25f * w * (if (r.shadows > 0) (1 - x) else x)
        }
        if (r.whites != 0f) x += r.whites * 0.12f * smooth(0.7f, 1f, x)
        if (r.blacks != 0f) x += r.blacks * 0.12f * (1f - smooth(0f, 0.3f, x))
        return x.coerceIn(0f, 1f)
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    /** Builds 256-entry LUTs (0..255 ints) for R, G, B combining white balance, tone, profile and fade. */
    fun buildLuts(recipe: FilmRecipe, profile: FilmProfile): Array<IntArray> {
        val strength = recipe.profileStrength.coerceIn(0f, 1f)
        val curves = listOf(curve(profile.red), curve(profile.green), curve(profile.blue))
        val fade = (recipe.fade + profile.defaultFade * strength).coerceIn(0f, 1f)
        // White balance multipliers.
        val wb = floatArrayOf(
            1f + recipe.temperature * 0.12f,
            1f - recipe.tint * 0.08f,
            1f - recipe.temperature * 0.12f,
        )
        return Array(3) { ch ->
            IntArray(256) { i ->
                var x = (i / 255f * wb[ch]).coerceIn(0f, 1f)
                x = tone(x, recipe)
                val idx = (x * 255f).toInt().coerceIn(0, 255)
                x = x * (1 - strength) + curves[ch][idx] * strength
                x = fade * 0.14f + x * (1f - fade * 0.2f)
                (x * 255f + 0.5f).toInt().coerceIn(0, 255)
            }
        }
    }
}
