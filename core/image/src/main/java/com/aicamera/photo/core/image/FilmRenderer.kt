package com.aicamera.photo.core.image

import android.graphics.Bitmap
import com.aicamera.photo.domain.FilmRecipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Renders a [FilmRecipe] onto a bitmap. Works in horizontal bands so a full-resolution render
 * needs only one extra band buffer per worker. The source bitmap passed in is never the original file.
 */
object FilmRenderer {

    /** Returns a new bitmap with the recipe applied; [src] is left untouched. */
    suspend fun render(src: Bitmap, recipe: FilmRecipe): Bitmap = withContext(Dispatchers.Default) {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        renderInPlace(out, recipe)
        out
    }

    suspend fun renderInPlace(bitmap: Bitmap, recipe: FilmRecipe) = coroutineScope {
        val profile = FilmProfiles.byId(recipe.profileId)
        val luts = ToneMath.buildLuts(recipe, profile)
        val w = bitmap.width
        val h = bitmap.height
        val strength = recipe.profileStrength.coerceIn(0f, 1f)
        val satBase = 1f + recipe.saturation + profile.saturation * strength
        val mono = profile.monochrome && strength > 0.5f
        val grain = (recipe.grain + profile.defaultGrain * strength).coerceIn(0f, 1f)
        val grainAmp = grain * 28f
        // Grain size scales with the image so previews and full renders look alike.
        val grainScale = max(1, max(w, h) / 1400)
        val vignette = recipe.vignette.coerceIn(0f, 1f)
        val tintStrength = profile.tintStrength * strength
        val sh = profile.shadowTint
        val hi = profile.highlightTint
        val cx = w / 2f
        val cy = h / 2f
        val maxDist = sqrt(cx * cx + cy * cy)

        val workers = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val bandHeight = max(16, (h + workers * 4 - 1) / (workers * 4))
        val bands = (0 until h step bandHeight).toList()
        bands.chunked(max(1, bands.size / workers)).map { chunk ->
            async(Dispatchers.Default) {
                val buf = IntArray(w * bandHeight)
                for (y0 in chunk) {
                    ensureActive()
                    val rows = minOf(bandHeight, h - y0)
                    bitmap.getPixels(buf, 0, w, 0, y0, w, rows)
                    for (ry in 0 until rows) {
                        val y = y0 + ry
                        val dy = y - cy
                        for (x in 0 until w) {
                            val i = ry * w + x
                            val c = buf[i]
                            val a = c ushr 24
                            var r = luts[0][(c shr 16) and 0xFF].toFloat()
                            var g = luts[1][(c shr 8) and 0xFF].toFloat()
                            var b = luts[2][c and 0xFF].toFloat()
                            val l = 0.299f * r + 0.587f * g + 0.114f * b
                            if (mono) {
                                r = l; g = l; b = l
                            } else {
                                var sat = satBase
                                if (recipe.vibrance != 0f) {
                                    val mx = max(r, max(g, b))
                                    val mn = minOf(r, g, b)
                                    val curSat = if (mx <= 0f) 0f else (mx - mn) / mx
                                    sat += recipe.vibrance * (1f - curSat)
                                }
                                if (sat != 1f) {
                                    r = l + (r - l) * sat
                                    g = l + (g - l) * sat
                                    b = l + (b - l) * sat
                                }
                            }
                            if (tintStrength > 0f) {
                                val ln = l / 255f
                                if (sh != null) {
                                    val ws = (1f - ln) * (1f - ln) * tintStrength
                                    r += (sh.r * 255f - r) * ws; g += (sh.g * 255f - g) * ws; b += (sh.b * 255f - b) * ws
                                }
                                if (hi != null) {
                                    val wh = ln * ln * tintStrength
                                    r += (hi.r * 255f - r) * wh; g += (hi.g * 255f - g) * wh; b += (hi.b * 255f - b) * wh
                                }
                            }
                            if (vignette > 0f) {
                                val dx = x - cx
                                val d = sqrt(dx * dx + dy * dy) / maxDist
                                val f = 1f - vignette * 0.75f * (d * d).coerceAtMost(1f)
                                r *= f; g *= f; b *= f
                            }
                            if (grainAmp > 0f) {
                                val n = noise(x / grainScale, y / grainScale) * grainAmp
                                // Grain is strongest in midtones, as on film.
                                val m = 1f - kotlin.math.abs(l / 127.5f - 1f) * 0.6f
                                r += n * m; g += n * m; b += n * m
                            }
                            buf[i] = (a shl 24) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)
                        }
                    }
                    bitmap.setPixels(buf, 0, w, 0, y0, w, rows)
                }
            }
        }.awaitAll()
    }

    private fun clamp(v: Float): Int = when {
        v <= 0f -> 0
        v >= 255f -> 255
        else -> (v + 0.5f).toInt()
    }

    /** Deterministic value noise in -1..1 so a recipe always renders the same grain. */
    private fun noise(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return ((h and 0xFFFF) / 32767.5f) - 1f
    }
}
