package com.aicamera.photo.ui.components

import com.aicamera.photo.ui.theme.LumenIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.aicamera.photo.domain.ConfidenceLevel
import com.aicamera.photo.domain.FindingSeverity
import com.aicamera.photo.domain.RecommendationSource
import com.aicamera.photo.ui.theme.LumenColors
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LumenTopBar(title: String, nav: NavHostController?, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (nav != null) IconButton(onClick = { nav.popBackStack() }) {
                Icon(LumenIcons.Back, contentDescription = "Back")
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(top = 8.dp, bottom = 8.dp))
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, textColor: Color = Color.Black) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = textColor)
    }
}

/** Confidence is shown as words, so uncertain findings are never presented as facts. */
fun confidenceWords(value: Float): String = when (ConfidenceLevel.of(value)) {
    ConfidenceLevel.HIGH -> "High confidence"
    ConfidenceLevel.MEDIUM -> "Likely"
    ConfidenceLevel.LOW -> "Possible"
}

fun severityColor(s: FindingSeverity): Color = when (s) {
    FindingSeverity.STRENGTH -> LumenColors.Ready
    FindingSeverity.INFO -> Color(0xFF90A4AE)
    FindingSeverity.SUGGESTION -> LumenColors.Amber
    FindingSeverity.ISSUE -> LumenColors.Issue
}

fun severityLabel(s: FindingSeverity): String = when (s) {
    FindingSeverity.STRENGTH -> "Strength"
    FindingSeverity.INFO -> "Note"
    FindingSeverity.SUGGESTION -> "Idea"
    FindingSeverity.ISSUE -> "Fix"
}

@Composable
fun SourceBadge(source: RecommendationSource) {
    if (source == RecommendationSource.LOCAL_AI) {
        Pill("On-device AI", LumenColors.Ai.copy(alpha = 0.25f), textColor = LumenColors.Ai)
    }
}

@Composable
fun Dot(color: Color, size: Int = 8) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

@Composable
fun LabeledValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

fun formatDate(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

fun formatBytes(b: Long): String = when {
    b >= 1_000_000_000 -> String.format("%.2f GB", b / 1e9)
    b >= 1_000_000 -> String.format("%.1f MB", b / 1e6)
    b >= 1_000 -> String.format("%.0f KB", b / 1e3)
    else -> "$b B"
}
