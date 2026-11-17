package com.aicamera.photo.features.academy

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.aicamera.photo.ui.theme.LumenColors

private val Sky = Color(0xFF2B4A6B)
private val SkyWarm = Color(0xFFE8A65D)
private val Ground = Color(0xFF2F3A2C)
private val Subject = Color(0xFFF4F1EA)
private val Guide = LumenColors.Amber
private val Faint = Color.White.copy(alpha = 0.35f)

/** Schematic explanatory diagrams for lessons (vector, theme-consistent, no image assets). */
@Composable
fun LessonDiagram(diagram: Diagram, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1B2127)),
    ) {
        val w = size.width
        val h = size.height
        fun p(x: Float, y: Float) = Offset(x * w, y * h)
        val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))

        fun skyGround(horizon: Float, warm: Boolean = false, tilt: Float = 0f) {
            rotate(tilt, pivot = p(0.5f, horizon)) {
                drawRect(Brush.verticalGradient(listOf(if (warm) SkyWarm else Sky, if (warm) Color(0xFFF6D29B) else Color(0xFF4F6D8C)), 0f, horizon * h), Offset(-w, -h), Size(w * 3, h + horizon * h))
                drawRect(Ground, Offset(-w, horizon * h), Size(w * 3, h * 2))
            }
        }
        fun person(x: Float, y: Float, s: Float, color: Color = Subject) {
            drawCircle(color, s * 0.18f * h, p(x, y - s * 0.32f))
            drawRoundRect(color, Offset((x - s * 0.13f) * w, (y - s * 0.12f) * h), Size(s * 0.26f * w, s * 0.5f * h), androidx.compose.ui.geometry.CornerRadius(12f, 12f))
        }
        fun thirds(alpha: Float = 0.35f) {
            val c = Color.White.copy(alpha = alpha)
            for (i in 1..2) {
                drawLine(c, p(i / 3f, 0f), p(i / 3f, 1f), 2f)
                drawLine(c, p(0f, i / 3f), p(1f, i / 3f), 2f)
            }
        }
        fun ring(x: Float, y: Float, color: Color = Guide) = drawCircle(color, 0.05f * h, p(x, y), style = Stroke(5f))
        fun arrow(from: Offset, to: Offset, color: Color = Guide) {
            drawLine(color, from, to, 5f, StrokeCap.Round)
            val d = (to - from)
            val len = d.getDistance().coerceAtLeast(1f)
            val u = Offset(d.x / len, d.y / len)
            val n = Offset(-u.y, u.x)
            drawLine(color, to, to - u * 22f + n * 14f, 5f, StrokeCap.Round)
            drawLine(color, to, to - u * 22f - n * 14f, 5f, StrokeCap.Round)
        }
        fun sun(x: Float, y: Float, r: Float = 0.07f) = drawCircle(Color(0xFFFFD27A), r * h, p(x, y))

        when (diagram) {
            Diagram.THIRDS -> { skyGround(2f / 3f); thirds(0.5f); person(2f / 3f, 0.62f, 0.5f); ring(2f / 3f, 1f / 3f) }
            Diagram.SYMMETRY -> {
                skyGround(0.75f)
                drawRect(Color(0xFF8B7E74), p(0.2f, 0.25f), Size(0.6f * w, 0.5f * h))
                drawRect(Color(0xFF1B2127), p(0.42f, 0.45f), Size(0.16f * w, 0.3f * h))
                drawLine(Guide, p(0.5f, 0f), p(0.5f, 1f), 3f, pathEffect = dash)
            }
            Diagram.LEADING_LINES -> {
                skyGround(0.4f)
                val road = Path().apply { moveTo(0.1f * w, h); lineTo(0.47f * w, 0.4f * h); lineTo(0.53f * w, 0.4f * h); lineTo(0.9f * w, h); close() }
                drawPath(road, Color(0xFF4A4A4A))
                drawLine(Guide, p(0.1f, 1f), p(0.47f, 0.4f), 4f); drawLine(Guide, p(0.9f, 1f), p(0.53f, 0.4f), 4f)
                person(0.5f, 0.45f, 0.18f); ring(0.5f, 0.4f)
            }
            Diagram.NEGATIVE_SPACE -> { drawRect(Brush.verticalGradient(listOf(Sky, Color(0xFF6E8BA8)))); person(0.25f, 0.72f, 0.18f); arrow(p(0.35f, 0.5f), p(0.8f, 0.5f), Faint) }
            Diagram.FRAMING -> {
                skyGround(0.65f)
                drawRect(Color(0xFF15191D), Offset.Zero, Size(w, 0.15f * h)); drawRect(Color(0xFF15191D), Offset.Zero, Size(0.18f * w, h)); drawRect(Color(0xFF15191D), p(0.82f, 0f), Size(0.18f * w, h))
                person(0.5f, 0.7f, 0.35f); drawRect(Guide, p(0.18f, 0.15f), Size(0.64f * w, 0.85f * h), style = Stroke(4f, pathEffect = dash))
            }
            Diagram.DIAGONAL -> {
                drawRect(Sky)
                val stairs = Path().apply { moveTo(0f, h); lineTo(w, 0.15f * h); lineTo(w, h); close() }
                drawPath(stairs, Color(0xFF7A6A5A))
                drawLine(Guide, p(0f, 1f), p(1f, 0.15f), 4f, pathEffect = dash); person(2f / 3f, 0.42f, 0.25f)
            }
            Diagram.FILL_FRAME -> { drawRect(Color(0xFF3A2E2A)); drawCircle(Color(0xFFE07A5F), 0.42f * h, p(0.5f, 0.5f)); drawCircle(Color(0xFFF2CC8F), 0.18f * h, p(0.5f, 0.5f)) }
            Diagram.CLEAN_EDGES -> {
                skyGround(0.65f); person(0.4f, 0.65f, 0.4f)
                drawRect(Color(0xFFB0B0B0), p(0.92f, 0.45f), Size(0.12f * w, 0.4f * h))
                drawCircle(LumenColors.Issue, 0.12f * h, p(0.95f, 0.62f), style = Stroke(5f))
            }
            Diagram.HORIZON -> { skyGround(1f / 3f); drawLine(LumenColors.Ready, p(0.3f, 0.5f), p(0.7f, 0.5f), 6f, StrokeCap.Round); drawLine(Guide, p(0f, 1f / 3f), p(1f, 1f / 3f), 3f, pathEffect = dash) }
            Diagram.GOLDEN_HOUR -> { skyGround(0.62f, warm = true); sun(0.78f, 0.56f); person(0.35f, 0.66f, 0.35f, Color(0xFF3A2A1E)); drawLine(Color(0x553A2A1E), p(0.35f, 0.82f), p(0.02f, 0.95f), 18f) }
            Diagram.BACKLIGHT -> { skyGround(0.7f, warm = true); sun(0.5f, 0.35f, 0.12f); person(0.5f, 0.7f, 0.45f, Color(0xFF1E1A18)) }
            Diagram.SIDE_LIGHT -> {
                drawRect(Color(0xFF22282E)); sun(0.08f, 0.4f)
                drawCircle(Brush.horizontalGradient(listOf(Color(0xFFF2D7B6), Color(0xFF3A2F28)), 0.3f * w, 0.7f * w), 0.3f * h, p(0.5f, 0.5f))
                arrow(p(0.16f, 0.42f), p(0.3f, 0.48f))
            }
            Diagram.HARSH_LIGHT -> { skyGround(0.7f); sun(0.5f, 0.1f); person(0.3f, 0.72f, 0.4f); drawRect(Color(0xFF1F2A1D), p(0.6f, 0.35f), Size(0.4f * w, 0.65f * h)); person(0.78f, 0.72f, 0.4f, Color(0xFFCFC8BC)) }
            Diagram.EXPOSURE -> {
                val bars = listOf(0.1f, 0.25f, 0.45f, 0.7f, 0.9f, 0.75f, 0.5f, 0.3f, 0.2f, 0.6f)
                bars.forEachIndexed { i, v -> drawRect(Color.White.copy(alpha = 0.7f), p(0.08f + i * 0.085f, 0.85f - v * 0.7f), Size(0.07f * w, v * 0.7f * h)) }
                drawRect(LumenColors.Issue, p(0.92f, 0.1f), Size(0.04f * w, 0.75f * h))
            }
            Diagram.CAMERA_HEIGHT -> {
                skyGround(0.7f); person(0.7f, 0.72f, 0.5f)
                listOf(0.85f, 0.55f, 0.25f).forEach { y -> drawRect(Guide, p(0.1f, y - 0.03f), Size(0.08f * w, 0.06f * h)); arrow(p(0.2f, y), p(0.55f, 0.55f), Faint) }
            }
            Diagram.DISTANCE -> { skyGround(0.6f); person(0.25f, 0.85f, 0.6f); person(0.7f, 0.62f, 0.15f); arrow(p(0.45f, 0.95f), p(0.65f, 0.7f), Faint) }
            Diagram.FOREGROUND -> {
                skyGround(1f / 3f)
                drawOval(Color(0xFF6B6B6B), p(0.05f, 0.72f), Size(0.4f * w, 0.25f * h))
                drawLine(Guide, p(0f, 1f / 3f), p(1f, 1f / 3f), 3f, pathEffect = dash); drawLine(Guide, p(0f, 2f / 3f), p(1f, 2f / 3f), 3f, pathEffect = dash)
            }
            Diagram.FOCAL_LENGTH -> {
                drawRect(Color(0xFF22282E))
                listOf(0.95f to "w", 0.6f to "m", 0.3f to "t").forEach { (spread, _) ->
                    drawLine(Guide.copy(alpha = 0.8f), p(0.5f, 0.95f), p(0.5f - spread / 2, 0.1f), 3f)
                    drawLine(Guide.copy(alpha = 0.8f), p(0.5f, 0.95f), p(0.5f + spread / 2, 0.1f), 3f)
                }
                drawCircle(Subject, 0.05f * h, p(0.5f, 0.95f))
            }
            Diagram.FOCUS -> { drawRect(Color(0xFF22282E)); drawCircle(Subject, 0.2f * h, p(0.35f, 0.55f)); drawCircle(Subject.copy(alpha = 0.3f), 0.12f * h, p(0.75f, 0.4f)); drawRect(Guide, p(0.2f, 0.33f), Size(0.3f * w, 0.42f * h), style = Stroke(4f)) }
            Diagram.MOTION -> {
                drawRect(Color(0xFF22282E))
                for (i in 0 until 5) drawCircle(Subject.copy(alpha = 0.15f + i * 0.17f), 0.1f * h, p(0.2f + i * 0.12f, 0.5f))
                arrow(p(0.15f, 0.75f), p(0.8f, 0.75f))
            }
            Diagram.SHUTTER_ISO -> {
                drawRect(Color(0xFF22282E))
                drawLine(Faint, p(0.1f, 0.35f), p(0.9f, 0.35f), 4f); drawLine(Faint, p(0.1f, 0.7f), p(0.9f, 0.7f), 4f)
                drawCircle(Guide, 0.04f * h, p(0.7f, 0.35f)); drawCircle(LumenColors.Teal, 0.04f * h, p(0.3f, 0.7f))
            }
            Diagram.EXPOSURE_COMP -> {
                drawRect(Brush.horizontalGradient(listOf(Color(0xFF111111), Color(0xFFEEEEEE))))
                drawLine(Guide, p(0.1f, 0.85f), p(0.9f, 0.85f), 5f); drawCircle(Guide, 0.05f * h, p(0.62f, 0.85f))
            }
            Diagram.LAYERS -> {
                skyGround(0.45f)
                drawRect(Color(0xFF5B6B7B), p(0f, 0.3f), Size(w, 0.15f * h)); drawRect(Color(0xFF6B5B4B), p(0.1f, 0.48f), Size(0.5f * w, 0.25f * h)); person(0.75f, 0.92f, 0.5f)
            }
            Diagram.ISOLATION -> {
                skyGround(0.75f)
                drawLine(Color(0xFF9A9A9A), p(0.4f, 0f), p(0.4f, 0.8f), 12f)
                person(0.4f, 0.75f, 0.4f, Subject.copy(alpha = 0.6f)); person(0.68f, 0.75f, 0.4f)
                arrow(p(0.45f, 0.95f), p(0.62f, 0.95f))
            }
            Diagram.CONTEXT -> {
                drawRect(Color(0xFF22282E))
                listOf(0.05f to 0.5f, 0.38f to 0.32f, 0.71f to 0.2f).forEach { (x, s) -> drawRect(Faint, p(x, 0.3f), Size(0.24f * w, 0.4f * h), style = Stroke(4f)); drawCircle(Guide, s * 0.15f * h, p(x + 0.12f, 0.5f)) }
            }
            Diagram.DECISIVE -> { skyGround(0.75f); thirds(); person(2f / 3f, 0.55f, 0.35f); drawLine(Faint, p(0.5f, 0.75f), p(0.62f, 0.6f), 3f, pathEffect = dash); ring(2f / 3f, 0.5f) }
        }
    }
}

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())
