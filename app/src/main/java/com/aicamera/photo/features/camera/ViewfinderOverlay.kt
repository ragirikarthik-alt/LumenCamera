package com.aicamera.photo.features.camera

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.aicamera.photo.core.data.AppSettings
import com.aicamera.photo.core.data.CameraMode
import com.aicamera.photo.domain.AlignmentState
import com.aicamera.photo.domain.ConfidenceLevel
import com.aicamera.photo.domain.FrameRotation
import com.aicamera.photo.domain.NormalizedPoint
import com.aicamera.photo.domain.NormalizedRect
import com.aicamera.photo.ui.theme.LumenColors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws guidance over the preview. Scene geometry arrives in world-upright coordinates and is mapped
 * back to the portrait screen (and mirrored for the front camera).
 */
@Composable
fun ViewfinderOverlay(
    ui: CameraUiState,
    settings: AppSettings,
    isFront: Boolean,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.85f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse",
    )
    Canvas(modifier) {
        val back = (360 - ui.frameRotationCw) % 360
        fun map(p: NormalizedPoint): Offset {
            var d = FrameRotation.rotatePointCw(p, back)
            if (isFront) d = FrameRotation.mirrorX(d)
            return Offset(d.x * size.width, d.y * size.height)
        }
        fun mapRect(r: NormalizedRect): Pair<Offset, Size> {
            val a = map(NormalizedPoint(r.left, r.top))
            val b = map(NormalizedPoint(r.right, r.bottom))
            val tl = Offset(minOf(a.x, b.x), minOf(a.y, b.y))
            return tl to Size(abs(a.x - b.x), abs(a.y - b.y))
        }

        // Rule-of-thirds grid (screen space).
        if (settings.showGrid) {
            val c = Color.White.copy(alpha = 0.28f)
            for (i in 1..2) {
                drawLine(c, Offset(size.width * i / 3f, 0f), Offset(size.width * i / 3f, size.height), 1.5f)
                drawLine(c, Offset(0f, size.height * i / 3f), Offset(size.width, size.height * i / 3f), 1.5f)
            }
        }

        val scene = ui.scene ?: return@Canvas

        // Subject boxes: corner brackets. Labels only for confident detections.
        if (settings.showSubjectBoxes) {
            val primary = scene.primarySubject
            scene.subjects.take(6).forEach { s ->
                val (tl, sz) = mapRect(s.boundingBox)
                val isPrimary = s == primary
                val color = if (isPrimary) LumenColors.Amber else Color.White.copy(alpha = 0.45f)
                brackets(tl, sz, color, if (isPrimary) 4f else 2.5f)
                if (isPrimary && s.confidenceLevel != ConfidenceLevel.LOW) {
                    drawText(
                        measurer, s.describe(), topLeft = Offset(tl.x, (tl.y - 44f).coerceAtLeast(0f)),
                        style = TextStyle(color = color, fontSize = 12.sp),
                    )
                }
            }
        }

        // Horizon level indicator (world-level reference line through the centre).
        val tilt = scene.horizon?.tiltDegrees
        if (settings.showHorizonLevel && tilt != null && abs(tilt) < 25f) {
            val rad = Math.toRadians(tilt.toDouble())
            val half = 0.16f
            val a = NormalizedPoint(0.5f - half * cos(rad).toFloat(), 0.5f - half * sin(rad).toFloat())
            val b = NormalizedPoint(0.5f + half * cos(rad).toFloat(), 0.5f + half * sin(rad).toFloat())
            val level = abs(tilt) < 1f
            val color = if (level) LumenColors.Ready else Color.White.copy(alpha = 0.85f)
            drawLine(color, map(a), map(b), 3f, cap = StrokeCap.Round)
            // Fixed reference ticks.
            val ra = map(NormalizedPoint(0.5f - half - 0.04f, 0.5f))
            val rb = map(NormalizedPoint(0.5f - half - 0.01f, 0.5f))
            val rc = map(NormalizedPoint(0.5f + half + 0.01f, 0.5f))
            val rd = map(NormalizedPoint(0.5f + half + 0.04f, 0.5f))
            drawLine(color, ra, rb, 3f)
            drawLine(color, rc, rd, 3f)
        }

        // Guided mode: target geometry and alignment.
        if (ui.mode == CameraMode.GUIDED && ui.guidedPhase == GuidedPhase.ALIGNING) {
            val sel = ui.selected ?: return@Canvas
            val alignment = ui.alignment
            val stateColor = when (alignment?.state) {
                AlignmentState.READY -> LumenColors.Ready
                AlignmentState.NEARLY_ALIGNED -> LumenColors.Nearly
                else -> LumenColors.Amber
            }
            val dash = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))
            sel.guides.forEach { g ->
                drawLine(LumenColors.Amber.copy(alpha = 0.6f), map(g.start), map(g.end), 2.5f, pathEffect = dash)
            }
            sel.targetRegion?.let { r ->
                val (tl, sz) = mapRect(r)
                drawRect(LumenColors.Teal.copy(alpha = 0.8f), tl, sz, style = Stroke(3f, pathEffect = dash))
            }
            sel.horizonTarget?.let { y ->
                drawLine(LumenColors.Teal, map(NormalizedPoint(0.04f, y)), map(NormalizedPoint(0.96f, y)), 3f, pathEffect = dash)
            }
            val target = alignment?.subjectTarget ?: sel.subjectTargets.firstOrNull()?.target
            val current = alignment?.subjectCurrent
            if (target != null) {
                val t = map(target)
                if (current != null) {
                    drawLine(stateColor.copy(alpha = 0.8f), map(current), t, 3f, pathEffect = dash, cap = StrokeCap.Round)
                    drawCircle(stateColor, 9f, map(current))
                }
                val r = size.minDimension * 0.06f * (if (alignment?.state == AlignmentState.READY) 1f else pulse)
                drawCircle(stateColor, r, t, style = Stroke(5f))
                drawCircle(stateColor.copy(alpha = 0.25f), r * 0.55f, t)
            }
        }

        // Continuous mode: point at what the suggestion refers to.
        if (ui.mode == CameraMode.CONTINUOUS) {
            ui.suggestion?.focusPoint?.let { fp ->
                drawCircle(LumenColors.Amber, size.minDimension * 0.05f * pulse, map(fp), style = Stroke(4f))
            }
        }

        // Tap-to-focus ring (already in view coordinates).
        ui.focusPoint?.let { (x, y) ->
            drawCircle(Color.White, size.minDimension * 0.07f, Offset(x, y), style = Stroke(3f))
        }
    }
}

private fun DrawScope.brackets(tl: Offset, sz: Size, color: Color, width: Float) {
    val l = minOf(sz.width, sz.height) * 0.22f
    val br = Offset(tl.x + sz.width, tl.y + sz.height)
    drawLine(color, tl, Offset(tl.x + l, tl.y), width, StrokeCap.Round)
    drawLine(color, tl, Offset(tl.x, tl.y + l), width, StrokeCap.Round)
    drawLine(color, Offset(br.x, tl.y), Offset(br.x - l, tl.y), width, StrokeCap.Round)
    drawLine(color, Offset(br.x, tl.y), Offset(br.x, tl.y + l), width, StrokeCap.Round)
    drawLine(color, Offset(tl.x, br.y), Offset(tl.x + l, br.y), width, StrokeCap.Round)
    drawLine(color, Offset(tl.x, br.y), Offset(tl.x, br.y - l), width, StrokeCap.Round)
    drawLine(color, br, Offset(br.x - l, br.y), width, StrokeCap.Round)
    drawLine(color, br, Offset(br.x, br.y - l), width, StrokeCap.Round)
}
