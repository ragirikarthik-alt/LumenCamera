package com.aicamera.photo.domain

import kotlinx.serialization.Serializable

/**
 * A non-destructive development recipe. The original file is never modified; the recipe is stored
 * and rendered into a separate developed output.
 *
 * Adjustments are in -1..1 unless noted (0 = neutral).
 */
@Serializable
data class FilmRecipe(
    val profileId: String = "natural",
    /** 0..1 blend between neutral and the full film profile character. */
    val profileStrength: Float = 1f,
    /** Exposure in stops, -2..2. */
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val saturation: Float = 0f,
    val vibrance: Float = 0f,
    /** 0..1 */
    val grain: Float = 0f,
    /** 0..1 */
    val vignette: Float = 0f,
    /** 0..1 lifted blacks / matte look. */
    val fade: Float = 0f,
) {
    companion object {
        const val VERSION = 1
    }
}
