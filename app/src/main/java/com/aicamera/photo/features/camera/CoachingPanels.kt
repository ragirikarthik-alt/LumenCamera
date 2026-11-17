package com.aicamera.photo.features.camera

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicamera.photo.domain.AlignmentResult
import com.aicamera.photo.domain.AlignmentState
import com.aicamera.photo.domain.CoachingRecommendation
import com.aicamera.photo.domain.CompositionCandidate
import com.aicamera.photo.domain.CompositionStrategy
import com.aicamera.photo.domain.ConfidenceLevel
import com.aicamera.photo.domain.RecommendationSource
import com.aicamera.photo.ui.components.GlassAction
import com.aicamera.photo.ui.components.IconBadge
import com.aicamera.photo.ui.components.glass
import com.aicamera.photo.ui.theme.LumenColors
import com.aicamera.photo.ui.theme.LumenIcons
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Balance
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Grid3x3
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material.icons.rounded.North
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.ZoomIn

/** Continuous mode: one suggestion at a time, with follow / dismiss / another idea / guided. */
@Composable
fun SuggestionCard(
    rec: CoachingRecommendation,
    acknowledged: Boolean,
    onFollow: () -> Unit,
    onDismiss: () -> Unit,
    onAnother: () -> Unit,
    onGuided: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isAi = rec.source == RecommendationSource.LOCAL_AI
    val accent = if (isAi) LumenColors.Ai else LumenColors.Amber
    Column(modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp), strong = true).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(if (isAi) LumenIcons.Ai else LumenIcons.forTip(rec.type), accent)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(rec.title, style = MaterialTheme.typography.titleSmall, color = accent)
                val sub = buildList {
                    if (isAi) add("On-device AI")
                    if (ConfidenceLevel.of(rec.confidence) != ConfidenceLevel.HIGH) add(if (rec.confidence >= 0.5f) "likely" else "maybe")
                }
                if (sub.isNotEmpty()) Text(sub.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.55f))
            }
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f)).clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) { Icon(LumenIcons.Close, "Dismiss", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(18.dp)) }
        }
        Text(
            rec.instruction,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 10.dp),
        )
        if (rec.rationale.isNotBlank()) {
            Text(rec.rationale, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(top = 2.dp))
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassAction(if (acknowledged) "On it…" else "OK", onFollow, primary = !acknowledged, enabled = !acknowledged)
            GlassAction("Another idea", onAnother)
            GlassAction("Guide me", onGuided)
        }
    }
}

private fun strategyIcon(s: CompositionStrategy) = when (s) {
    CompositionStrategy.RULE_OF_THIRDS -> Icons.Rounded.Grid3x3
    CompositionStrategy.CENTERED_SYMMETRY -> Icons.Rounded.Balance
    CompositionStrategy.LEADING_LINES -> Icons.Rounded.North
    CompositionStrategy.NEGATIVE_SPACE -> Icons.Rounded.BlurOn
    CompositionStrategy.FRAME_WITHIN_FRAME -> Icons.Rounded.CropFree
    CompositionStrategy.LAYERED_LANDSCAPE -> Icons.Rounded.Landscape
    CompositionStrategy.DIAGONAL -> Icons.Rounded.NorthEast
    CompositionStrategy.FILL_THE_FRAME -> Icons.Rounded.ZoomIn
}

/** Guided mode, step 2: up to three understandable composition options. */
@Composable
fun CompositionChooser(
    candidates: List<CompositionCandidate>,
    onSelect: (CompositionCandidate) -> Unit,
    onFree: () -> Unit,
    onReanalyze: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp), strong = true).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
            Text("Pick a style for this photo", style = MaterialTheme.typography.titleSmall, color = Color.White, modifier = Modifier.weight(1f))
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f)).clickable(onClick = onReanalyze),
                contentAlignment = Alignment.Center,
            ) { Icon(LumenIcons.Refresh, "Look again", tint = Color.White, modifier = Modifier.size(18.dp)) }
        }
        candidates.forEachIndexed { i, c ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (i == 0) LumenColors.Amber.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.05f))
                    .border(1.dp, if (i == 0) LumenColors.Amber.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                    .clickable { onSelect(c) }
                    .padding(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                IconBadge(strategyIcon(c.strategy), if (i == 0) LumenColors.Amber else LumenColors.Teal)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.strategy.displayName, style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.weight(1f))
                        // Suitability is a ranking signal, not "the best photo": shown as fit, in words.
                        Text(fitLabel(c.suitability), style = MaterialTheme.typography.labelSmall,
                            color = if (c.suitability >= 0.7f) LumenColors.Teal else Color.White.copy(alpha = 0.6f))
                    }
                    Text(c.explanation, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.78f), modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        Text(
            "Shoot freely instead",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.align(Alignment.End).clip(RoundedCornerShape(50)).clickable(onClick = onFree).padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

fun fitLabel(s: Float) = when {
    s >= 0.7f -> "Great fit"
    s >= 0.5f -> "Good fit"
    else -> "Worth a try"
}

@Composable
fun AnalyzingBanner(modifier: Modifier = Modifier) {
    Row(
        modifier.glass(RoundedCornerShape(50), strong = true).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = LumenColors.Amber)
        Spacer(Modifier.width(10.dp))
        Text("Looking at the scene - point at what you want to photograph", style = MaterialTheme.typography.bodySmall, color = Color.White)
    }
}

/** Guided mode, step 3: a progress ring for alignment plus the single most useful hint. */
@Composable
fun AlignmentPill(
    strategyName: String,
    alignment: AlignmentResult?,
    onChange: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = alignment?.state ?: AlignmentState.NOT_ALIGNED
    val color by animateColorAsState(
        when (state) {
            AlignmentState.READY -> LumenColors.Ready
            AlignmentState.NEARLY_ALIGNED -> LumenColors.Nearly
            AlignmentState.NOT_ALIGNED -> Color.White
        },
        label = "alignColor",
    )
    val progress by animateFloatAsState(alignment?.score?.coerceIn(0f, 1f) ?: 0f, label = "alignProgress")
    Row(
        modifier.glass(RoundedCornerShape(24.dp), strong = true).padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(38.dp)) {
                drawArc(Color.White.copy(alpha = 0.15f), 0f, 360f, false, style = Stroke(4.dp.toPx()))
                drawArc(color, -90f, 360f * progress, false, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
            }
            if (state == AlignmentState.READY) Icon(LumenIcons.Done, null, tint = color, modifier = Modifier.size(20.dp))
            else Text("${(progress * 100).toInt()}", style = MaterialTheme.typography.labelSmall, color = Color.White)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text("$strategyName · ${state.label}", style = MaterialTheme.typography.labelMedium, color = color)
            AnimatedContent(alignment?.hint ?: "Move your subject onto the circle", label = "hint") { hint ->
                Text(hint, style = MaterialTheme.typography.bodyMedium, color = Color.White, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.width(6.dp))
        GlassAction("Change", onChange)
    }
}
