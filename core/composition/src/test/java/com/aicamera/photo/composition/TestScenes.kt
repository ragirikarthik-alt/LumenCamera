package com.aicamera.photo.composition

import com.aicamera.photo.domain.DetectedSubject
import com.aicamera.photo.domain.HorizonEstimate
import com.aicamera.photo.domain.HorizonSource
import com.aicamera.photo.domain.LightingCondition
import com.aicamera.photo.domain.NormalizedRect
import com.aicamera.photo.domain.SceneSnapshot
import com.aicamera.photo.domain.SceneType
import com.aicamera.photo.domain.SubjectKind
import com.aicamera.photo.domain.VisualSignals

object TestScenes {
    fun subjectAt(cx: Float, cy: Float, size: Float = 0.2f, id: Int = 1, confidence: Float = 0.9f, kind: SubjectKind = SubjectKind.OBJECT) =
        DetectedSubject(
            trackingId = id,
            label = "person",
            confidence = confidence,
            boundingBox = NormalizedRect(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2),
            importance = 0.9f,
            kind = kind,
        )

    fun scene(
        subjects: List<DetectedSubject> = emptyList(),
        type: SceneType = SceneType.UNKNOWN,
        horizon: HorizonEstimate? = null,
        signals: VisualSignals = VisualSignals(sharpness = 0.6f),
        lighting: LightingCondition = LightingCondition.BALANCED,
        t: Long = 0L,
    ) = SceneSnapshot(type, 0.8f, lighting, subjects, horizon, signals, t)

    fun horizon(tilt: Float, y: Float? = 0.5f, conf: Float = 0.9f) =
        HorizonEstimate(tilt, y, conf, if (y != null) 0.8f else 0f, HorizonSource.FUSED)
}
