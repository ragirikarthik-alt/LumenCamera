package com.aicamera.photo.features.review

import com.aicamera.photo.ui.theme.LumenIcons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.aicamera.photo.container
import com.aicamera.photo.core.ai.AiStatus
import com.aicamera.photo.core.data.PhotoRepository
import com.aicamera.photo.domain.PhotoFinding
import com.aicamera.photo.features.academy.AcademyContent
import com.aicamera.photo.navigation.Routes
import com.aicamera.photo.ui.components.LumenTopBar
import com.aicamera.photo.ui.components.Pill
import com.aicamera.photo.ui.components.SectionCard
import com.aicamera.photo.ui.components.SectionTitle
import com.aicamera.photo.ui.components.SourceBadge
import com.aicamera.photo.ui.components.confidenceWords
import com.aicamera.photo.ui.components.severityColor
import com.aicamera.photo.ui.components.severityLabel
import com.aicamera.photo.ui.theme.LumenColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ReviewScreen(nav: NavHostController, photoId: String) {
    val c = LocalContext.current.container
    val details by c.photos.observeDetails(photoId).collectAsStateWithLifecycle(null)
    val aiStatus by c.ai.status.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    var aiRunning by remember { mutableStateOf(false) }
    var aiMessage by remember { mutableStateOf<String?>(null) }
    val feedback = details?.feedback?.let { PhotoRepository.decodeFeedback(it.feedbackJson) }

    // The critique normally arrives from background work; run it here if it hasn't appeared.
    LaunchedEffect(photoId, details?.feedback == null) {
        if (details != null && details?.feedback == null) {
            delay(2_500)
            if (c.photos.get(photoId) != null && details?.feedback == null) runCatching { c.critique.runDeterministic(photoId, null) }
        }
    }

    Scaffold(topBar = { LumenTopBar("Review", nav) }) { pad ->
        val photo = details?.photo
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                if (photo != null) {
                    AsyncImage(
                        model = File(photo.originalPath), contentDescription = "Captured photo", contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(16.dp)),
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { nav.navigate(Routes.filmLab(photoId)) }) { Text("Develop in Film Lab") }
                    OutlinedButton(onClick = { nav.navigate(Routes.photo(photoId)) }) { Text("Details") }
                }
            }
            if (feedback == null) {
                item {
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Analysing the full-resolution photo…")
                        }
                    }
                }
                return@LazyColumn
            }
            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Summary", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (feedback.aiUsed) Pill("+ on-device AI", LumenColors.Ai.copy(alpha = 0.25f), textColor = LumenColors.Ai)
                    }
                    Text(feedback.summary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
                    AcademyContent.lesson(feedback.nextLessonId)?.let { lesson ->
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.clip(RoundedCornerShape(10.dp)).clickable { nav.navigate(Routes.lesson(lesson.id)) }.padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(LumenIcons.Lesson, null, tint = LumenColors.Teal)
                            Spacer(Modifier.width(8.dp))
                            Text("Learn next: ${lesson.title}", color = LumenColors.Teal, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            item {
                SectionTitle("Technical - measured")
                Text("Objective measurements of the final photo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (feedback.technical.isEmpty()) item { Text("Nothing notable measured.") }
            items(feedback.technical) { FindingCard(it, nav) }
            item {
                SectionTitle("Artistic - opinions")
                Text("Suggestions, not rules. Deliberate choices are valid.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (feedback.artistic.isEmpty()) item { Text("No artistic observations.") }
            items(feedback.artistic) { FindingCard(it, nav) }

            // Optional on-device AI artistic feedback.
            if (settings?.aiEnabled == true && !feedback.aiUsed) item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(LumenIcons.Ai, null, tint = LumenColors.Ai)
                        Spacer(Modifier.width(8.dp))
                        Text("On-device AI feedback", style = MaterialTheme.typography.titleSmall)
                    }
                    when (aiStatus) {
                        AiStatus.NotInstalled -> {
                            Text("Install the optional model to get artistic interpretation. Everything stays on this phone.", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { nav.navigate(Routes.MODELS) }) { Text("Manage model") }
                        }
                        AiStatus.ThermalPaused -> Text("Paused while the phone cools down.", style = MaterialTheme.typography.bodySmall)
                        else -> {
                            if (aiRunning) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Text(if (aiStatus == AiStatus.Loading) "Loading model…" else "Looking at your photo…")
                                }
                            } else {
                                Button(onClick = {
                                    aiRunning = true
                                    aiMessage = null
                                    scope.launch {
                                        val r = runCatching { c.critique.addAiObservations(photoId) }.getOrNull()
                                        aiRunning = false
                                        if (r == null) aiMessage = "The model couldn't produce feedback this time. The measured critique above is unaffected."
                                    }
                                }) { Text("Ask for artistic feedback") }
                            }
                            aiMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LumenColors.Issue) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FindingCard(f: PhotoFinding, nav: NavHostController) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(severityLabel(f.severity), severityColor(f.severity))
            Spacer(Modifier.width(8.dp))
            Text(f.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            SourceBadge(f.source)
        }
        Text(f.detail, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            f.measurement?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = LumenColors.Amber, modifier = Modifier.weight(1f)) }
                ?: Spacer(Modifier.weight(1f))
            Text(confidenceWords(f.confidence), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AcademyContent.lesson(f.lessonId)?.let { l ->
            Text("Lesson: ${l.title} ›", color = LumenColors.Teal, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 6.dp).clickable { nav.navigate(Routes.lesson(l.id)) })
        }
    }
}
