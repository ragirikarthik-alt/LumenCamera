package com.aicamera.photo.core.vision

import com.aicamera.photo.domain.DetectedSubject
import com.aicamera.photo.domain.LightingCondition
import com.aicamera.photo.domain.SceneType
import com.aicamera.photo.domain.SubjectKind
import com.aicamera.photo.domain.VisualSignals

data class SceneLabel(val text: String, val confidence: Float)

/** Maps detector output and visual signals to a scene type and lighting condition, with confidence. */
object SceneClassifier {

    private val keywords: Map<SceneType, Set<String>> = mapOf(
        SceneType.LANDSCAPE to setOf(
            "sky", "mountain", "lake", "beach", "sunset", "cloud", "field", "waterfall", "river", "desert",
            "forest", "sea", "ocean", "hill", "glacier", "snow", "grass", "plant", "tree", "flower", "garden", "park",
        ),
        SceneType.ARCHITECTURE to setOf(
            "building", "skyscraper", "bridge", "tower", "church", "monument", "castle", "ruins", "temple",
            "stairs", "window", "arch", "column", "city", "house", "roof", "dome", "mosque", "palace",
        ),
        SceneType.STREET to setOf("road", "car", "bicycle", "vehicle", "crowd", "market", "street", "bus", "motorcycle", "traffic"),
        SceneType.FOOD to setOf("food", "cuisine", "dish", "fruit", "dessert", "cake", "pizza", "bread", "coffee", "drink", "meal", "vegetable", "cookie", "sushi", "salad"),
        SceneType.ANIMAL to setOf("dog", "cat", "bird", "pet", "horse", "animal", "insect", "butterfly", "fish", "cattle", "duck"),
        SceneType.INDOOR to setOf("room", "furniture", "couch", "table", "kitchen", "bedroom", "shelf", "desk", "curtain", "ceiling", "bed"),
    )

    fun classify(
        labels: List<SceneLabel>,
        subjects: List<DetectedSubject>,
        signals: VisualSignals,
        horizonVisible: Boolean,
    ): Pair<SceneType, Float> {
        val faces = subjects.filter { it.kind == SubjectKind.FACE }
        if (faces.size >= 2) return SceneType.GROUP to 0.85f
        if (faces.size == 1 && faces[0].boundingBox.area > 0.03f) return SceneType.PORTRAIT to 0.85f

        if (signals.meanLuma < 0.12f) return SceneType.NIGHT to 0.8f

        val scores = mutableMapOf<SceneType, Float>()
        for (l in labels) {
            val t = l.text.lowercase()
            for ((type, words) in keywords) {
                if (words.any { t == it || t.contains(it) }) scores[type] = (scores[type] ?: 0f) + l.confidence
            }
        }
        val best = scores.maxByOrNull { it.value }
        if (best != null && best.value >= 0.6f) {
            return best.key to (0.45f + best.value / 3f).coerceAtMost(0.9f)
        }
        val primary = subjects.maxByOrNull { it.importance }
        if (primary != null && primary.boundingBox.area > 0.25f) return SceneType.CLOSE_UP to 0.55f
        if (horizonVisible && subjects.isEmpty()) return SceneType.LANDSCAPE to 0.5f
        if (faces.isNotEmpty()) return SceneType.STREET to 0.5f
        return SceneType.UNKNOWN to 0.3f
    }

    fun lighting(signals: VisualSignals, primary: DetectedSubject?): LightingCondition {
        val s = signals
        val subj = s.subjectLuma
        val bg = s.backgroundLuma
        return when {
            s.meanLuma < 0.18f -> LightingCondition.LOW_LIGHT
            primary != null && subj != null && bg != null && bg - subj > 0.2f && s.highlightClipping > 0.01f -> LightingCondition.BACKLIT
            s.meanLuma > 0.55f && s.contrast > 0.25f && s.highlightClipping > 0.03f -> LightingCondition.BRIGHT_HARSH
            s.contrast > 0.33f || (s.highlightClipping > 0.05f && s.shadowClipping > 0.1f) -> LightingCondition.HIGH_CONTRAST
            s.warmth > 0.25f && s.contrast < 0.24f && s.meanLuma in 0.25f..0.65f -> LightingCondition.WARM_SOFT
            else -> LightingCondition.BALANCED
        }
    }
}
