package com.aicamera.photo.core.image

/** RGB triple in 0..1. */
data class Rgb(val r: Float, val g: Float, val b: Float)

/**
 * A film-inspired look. Curves are control points (input, output) in 0..1 per channel and are
 * blended with identity by the recipe's profile strength. Names are generic, not brand emulations.
 */
data class FilmProfile(
    val id: String,
    val name: String,
    val description: String,
    val red: List<Pair<Float, Float>> = IDENTITY,
    val green: List<Pair<Float, Float>> = IDENTITY,
    val blue: List<Pair<Float, Float>> = IDENTITY,
    /** Added to the recipe saturation (-1..1). */
    val saturation: Float = 0f,
    val monochrome: Boolean = false,
    val shadowTint: Rgb? = null,
    val highlightTint: Rgb? = null,
    val tintStrength: Float = 0f,
    val defaultGrain: Float = 0f,
    val defaultFade: Float = 0f,
    /** Swatch colour for the profile chip. */
    val swatch: Long = 0xFF888888,
) {
    companion object {
        val IDENTITY = listOf(0f to 0f, 1f to 1f)
    }
}

object FilmProfiles {
    val all: List<FilmProfile> = listOf(
        FilmProfile(
            id = "natural", name = "Natural", description = "Faithful colour with a gentle tone curve.",
            red = listOf(0f to 0f, 0.25f to 0.24f, 0.75f to 0.77f, 1f to 1f),
            green = listOf(0f to 0f, 0.25f to 0.24f, 0.75f to 0.77f, 1f to 1f),
            blue = listOf(0f to 0f, 0.25f to 0.24f, 0.75f to 0.77f, 1f to 1f),
            swatch = 0xFF9DB4C0,
        ),
        FilmProfile(
            id = "warm_portrait", name = "Warm Portrait", description = "Soft contrast and warm, flattering skin tones.",
            red = listOf(0f to 0.03f, 0.25f to 0.28f, 0.5f to 0.54f, 0.75f to 0.79f, 1f to 0.98f),
            green = listOf(0f to 0.02f, 0.25f to 0.26f, 0.5f to 0.51f, 0.75f to 0.76f, 1f to 0.96f),
            blue = listOf(0f to 0.04f, 0.25f to 0.24f, 0.5f to 0.46f, 0.75f to 0.71f, 1f to 0.92f),
            saturation = -0.08f, shadowTint = Rgb(0.55f, 0.45f, 0.4f), highlightTint = Rgb(1f, 0.85f, 0.7f), tintStrength = 0.08f,
            defaultGrain = 0.12f, swatch = 0xFFE0A47C,
        ),
        FilmProfile(
            id = "vivid_slide", name = "Vivid Slide", description = "Punchy contrast and rich saturated colour.",
            red = listOf(0f to 0f, 0.25f to 0.2f, 0.5f to 0.5f, 0.75f to 0.81f, 1f to 1f),
            green = listOf(0f to 0f, 0.25f to 0.2f, 0.5f to 0.5f, 0.75f to 0.8f, 1f to 1f),
            blue = listOf(0f to 0.02f, 0.25f to 0.22f, 0.5f to 0.52f, 0.75f to 0.8f, 1f to 1f),
            saturation = 0.25f, defaultGrain = 0.05f, swatch = 0xFF2E86DE,
        ),
        FilmProfile(
            id = "cine_teal", name = "Cine Teal", description = "Teal shadows and warm highlights, cinematic.",
            red = listOf(0f to 0f, 0.3f to 0.26f, 0.7f to 0.74f, 1f to 1f),
            green = listOf(0f to 0.02f, 0.3f to 0.3f, 0.7f to 0.71f, 1f to 0.98f),
            blue = listOf(0f to 0.06f, 0.3f to 0.34f, 0.7f to 0.66f, 1f to 0.92f),
            saturation = -0.05f, shadowTint = Rgb(0.2f, 0.45f, 0.5f), highlightTint = Rgb(1f, 0.78f, 0.55f), tintStrength = 0.12f,
            defaultFade = 0.1f, swatch = 0xFF2A9D8F,
        ),
        FilmProfile(
            id = "faded_matte", name = "Faded Matte", description = "Lifted blacks and muted colour for a nostalgic print.",
            red = listOf(0f to 0.08f, 0.5f to 0.52f, 1f to 0.95f),
            green = listOf(0f to 0.08f, 0.5f to 0.5f, 1f to 0.94f),
            blue = listOf(0f to 0.1f, 0.5f to 0.49f, 1f to 0.9f),
            saturation = -0.25f, defaultFade = 0.35f, defaultGrain = 0.18f, swatch = 0xFFB8A99A,
        ),
        FilmProfile(
            id = "golden_hour", name = "Golden Hour", description = "Glowing warmth that extends late-day light.",
            red = listOf(0f to 0.02f, 0.5f to 0.56f, 1f to 1f),
            green = listOf(0f to 0.01f, 0.5f to 0.51f, 1f to 0.97f),
            blue = listOf(0f to 0f, 0.5f to 0.43f, 1f to 0.86f),
            saturation = 0.08f, highlightTint = Rgb(1f, 0.8f, 0.5f), tintStrength = 0.1f, swatch = 0xFFF4A261,
        ),
        FilmProfile(
            id = "cool_chrome", name = "Cool Chrome", description = "Crisp, slightly cool tones with restrained colour.",
            red = listOf(0f to 0f, 0.25f to 0.22f, 0.75f to 0.76f, 1f to 0.97f),
            green = listOf(0f to 0f, 0.25f to 0.23f, 0.75f to 0.77f, 1f to 0.99f),
            blue = listOf(0f to 0.03f, 0.25f to 0.27f, 0.75f to 0.79f, 1f to 1f),
            saturation = -0.15f, shadowTint = Rgb(0.35f, 0.4f, 0.5f), tintStrength = 0.06f, swatch = 0xFF8EA8C3,
        ),
        FilmProfile(
            id = "mono_400", name = "Mono 400", description = "Classic black and white with a natural grain.",
            red = listOf(0f to 0.02f, 0.25f to 0.22f, 0.75f to 0.79f, 1f to 1f),
            green = listOf(0f to 0.02f, 0.25f to 0.22f, 0.75f to 0.79f, 1f to 1f),
            blue = listOf(0f to 0.02f, 0.25f to 0.22f, 0.75f to 0.79f, 1f to 1f),
            monochrome = true, defaultGrain = 0.25f, swatch = 0xFF777777,
        ),
        FilmProfile(
            id = "mono_contrast", name = "Mono Contrast", description = "Deep blacks and bright whites for graphic scenes.",
            red = listOf(0f to 0f, 0.25f to 0.15f, 0.5f to 0.5f, 0.75f to 0.86f, 1f to 1f),
            green = listOf(0f to 0f, 0.25f to 0.15f, 0.5f to 0.5f, 0.75f to 0.86f, 1f to 1f),
            blue = listOf(0f to 0f, 0.25f to 0.15f, 0.5f to 0.5f, 0.75f to 0.86f, 1f to 1f),
            monochrome = true, defaultGrain = 0.15f, swatch = 0xFF222222,
        ),
    )

    fun byId(id: String): FilmProfile = all.firstOrNull { it.id == id } ?: all.first()
}
