package com.aicamera.photo.composition

import com.aicamera.photo.domain.NormalizedPoint
import com.aicamera.photo.domain.SceneSnapshot
import com.aicamera.photo.domain.SceneType
import kotlin.math.abs

/**
 * Compact fingerprint of a scene, used to decide whether the scene "changed materially"
 * (for dismissal memory and for deciding when to invoke local AI reasoning).
 */
data class SceneSignature(
    val sceneType: SceneType,
    val primaryLabel: String?,
    val primaryCenter: NormalizedPoint?,
    val meanLuma: Float,
    val subjectCount: Int,
) {
    /** 0 = completely different, 1 = same scene. */
    fun similarity(other: SceneSignature): Float {
        var s = 0f
        if (sceneType == other.sceneType) s += 0.3f
        if (primaryLabel == other.primaryLabel) s += 0.2f
        if (primaryCenter != null && other.primaryCenter != null) {
            val d = primaryCenter.distanceTo(other.primaryCenter)
            s += 0.2f * (1f - d / 0.3f).coerceIn(0f, 1f)
        } else if (primaryCenter == null && other.primaryCenter == null) {
            s += 0.2f
        }
        s += 0.2f * (1f - abs(meanLuma - other.meanLuma) / 0.2f).coerceIn(0f, 1f)
        if (subjectCount == other.subjectCount) s += 0.1f
        return s
    }

    fun materiallyDifferentFrom(other: SceneSignature?, threshold: Float = 0.6f): Boolean =
        other == null || similarity(other) < threshold

    companion object {
        fun of(scene: SceneSnapshot): SceneSignature {
            val p = scene.primarySubject
            return SceneSignature(
                sceneType = scene.sceneType,
                primaryLabel = p?.label,
                primaryCenter = p?.boundingBox?.center,
                meanLuma = scene.visualSignals.meanLuma,
                subjectCount = scene.subjects.size,
            )
        }
    }
}
