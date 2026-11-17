package com.aicamera.photo.core.image

import com.aicamera.photo.domain.FilmRecipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneMathTest {
    @Test
    fun identityCurveIsIdentity() {
        val c = ToneMath.curve(FilmProfile.IDENTITY)
        for (i in 0 until 256) assertEquals(i / 255f, c[i], 1e-3f)
    }

    @Test
    fun curvesAreMonotone() {
        for (p in FilmProfiles.all) for (pts in listOf(p.red, p.green, p.blue)) {
            val c = ToneMath.curve(pts)
            for (i in 1 until 256) assertTrue("${p.id} not monotone at $i", c[i] >= c[i - 1] - 1e-4f)
        }
    }

    @Test
    fun neutralRecipeWithZeroStrengthIsNearIdentity() {
        val luts = ToneMath.buildLuts(FilmRecipe(profileStrength = 0f), FilmProfiles.byId("natural"))
        for (ch in 0..2) for (i in 0 until 256) assertEquals(i.toFloat(), luts[ch][i].toFloat(), 1.01f)
    }

    @Test
    fun exposureBrightens() {
        val base = ToneMath.tone(0.4f, FilmRecipe())
        val up = ToneMath.tone(0.4f, FilmRecipe(exposure = 1f))
        assertTrue(up > base)
    }

    @Test
    fun temperatureWarmsRedAndCoolsBlue() {
        val luts = ToneMath.buildLuts(FilmRecipe(profileStrength = 0f, temperature = 1f), FilmProfiles.byId("natural"))
        assertTrue(luts[0][128] > 128)
        assertTrue(luts[2][128] < 128)
    }
}
