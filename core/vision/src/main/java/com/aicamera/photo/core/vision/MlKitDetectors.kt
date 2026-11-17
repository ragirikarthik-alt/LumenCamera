package com.aicamera.photo.core.vision

import com.aicamera.photo.domain.DetectedSubject
import com.aicamera.photo.domain.NormalizedRect
import com.aicamera.photo.domain.SubjectKind
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlin.math.hypot
import kotlin.math.sqrt

/** Platform image for detectors, with its world-upright dimensions after rotation. */
class PlatformInput(val image: InputImage, val uprightWidth: Int, val uprightHeight: Int)

data class Detections(val subjects: List<DetectedSubject>, val labels: List<SceneLabel>?)

/**
 * Bundled (offline) ML Kit detectors: object detection with tracking, face detection and
 * image labeling for scene type. Stream mode for live preview, single-image mode for stills.
 */
class MlKitDetectors(streamMode: Boolean) : AutoCloseable {
    private val objects = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(if (streamMode) ObjectDetectorOptions.STREAM_MODE else ObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
    )
    private val faces = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(if (streamMode) FaceDetectorOptions.PERFORMANCE_MODE_FAST else FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setMinFaceSize(0.08f)
            .apply { if (streamMode) enableTracking() }
            .build()
    )
    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(0.55f).build()
    )

    suspend fun detect(input: PlatformInput, runLabeler: Boolean, animalScene: Boolean): Detections = coroutineScope {
        val objTask = async { runCatching { objects.process(input.image).await() }.getOrDefault(emptyList()) }
        val faceTask = async { runCatching { faces.process(input.image).await() }.getOrDefault(emptyList()) }
        val labelTask = if (runLabeler) async { runCatching { labeler.process(input.image).await() }.getOrNull() } else null

        val w = input.uprightWidth.toFloat()
        val h = input.uprightHeight.toFloat()
        fun norm(r: android.graphics.Rect) = NormalizedRect(r.left / w, r.top / h, r.right / w, r.bottom / h).clamped()

        val faceSubjects = faceTask.await().mapIndexed { i, f ->
            val box = norm(f.boundingBox)
            DetectedSubject(
                trackingId = 10_000 + (f.trackingId ?: i),
                label = "person",
                confidence = if (box.area > 0.01f) 0.9f else 0.7f,
                boundingBox = box,
                importance = importance(SubjectKind.FACE, box, 0.9f),
                kind = SubjectKind.FACE,
            )
        }
        val objectSubjects = objTask.await().mapIndexed { i, o ->
            val box = norm(o.boundingBox)
            val best = o.labels.maxByOrNull { it.confidence }
            val containsFace = faceSubjects.any { box.contains(it.boundingBox.center) }
            val (label, conf, kind) = when {
                containsFace -> Triple("person", 0.8f, SubjectKind.PERSON)
                best != null && best.text.equals("Food", true) -> Triple("food", best.confidence, SubjectKind.OBJECT)
                best != null && best.text.equals("Plant", true) -> Triple("plant", best.confidence, SubjectKind.OBJECT)
                best != null && best.text.equals("Fashion good", true) -> Triple("item", best.confidence * 0.9f, SubjectKind.OBJECT)
                best != null && best.text.equals("Home good", true) -> Triple("object", best.confidence * 0.9f, SubjectKind.OBJECT)
                animalScene -> Triple("animal", 0.6f, SubjectKind.ANIMAL)
                // Unclassified detections are real objects, but we don't know what they are.
                else -> Triple("subject", 0.55f, SubjectKind.OBJECT)
            }
            DetectedSubject(
                trackingId = 20_000 + (o.trackingId ?: i),
                label = label,
                confidence = conf.coerceIn(0f, 1f),
                boundingBox = box,
                importance = importance(kind, box, conf),
                kind = kind,
            )
        }.filter { it.boundingBox.area in 0.002f..0.95f }

        val labels = labelTask?.await()?.map { SceneLabel(it.text, it.confidence) }
        Detections(faceSubjects + objectSubjects, labels)
    }

    override fun close() {
        objects.close()
        faces.close()
        labeler.close()
    }

    companion object {
        fun importance(kind: SubjectKind, box: NormalizedRect, confidence: Float): Float {
            val base = when (kind) {
                SubjectKind.FACE -> 1.0f
                SubjectKind.ANIMAL -> 0.85f
                SubjectKind.PERSON -> 0.75f
                SubjectKind.OBJECT -> 0.6f
            }
            val size = 0.5f + 0.5f * sqrt((box.area / 0.25f).coerceAtMost(1f))
            val c = box.center
            val centrality = 1f - 0.3f * (hypot(c.x - 0.5f, c.y - 0.5f) / 0.707f)
            return base * size * centrality * (0.6f + 0.4f * confidence)
        }
    }
}
