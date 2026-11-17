package com.aicamera.photo.features.academy

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.aicamera.photo.container
import com.aicamera.photo.navigation.Routes
import com.aicamera.photo.ui.components.LumenTopBar
import com.aicamera.photo.ui.components.SectionCard
import com.aicamera.photo.ui.components.SectionTitle
import com.aicamera.photo.ui.theme.LumenColors
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun AcademyScreen(nav: NavHostController) {
    val c = LocalContext.current.container
    val progress by c.lessons.observeAll().map { list -> list.associateBy { it.lessonId } }.collectAsStateWithLifecycle(emptyMap())
    val lessonCounts by c.photos.observeFeedbackLessonCounts().collectAsStateWithLifecycle(emptyList())
    // Recommend the lesson most often linked from your own critiques that isn't completed yet.
    val recommended = lessonCounts.firstNotNullOfOrNull { lc ->
        AcademyContent.lesson(lc.nextLessonId)?.takeIf { progress[it.id]?.completed != true }
    }
    val completed = progress.values.count { it.completed }

    Scaffold(topBar = { LumenTopBar("Photography Academy", nav) { androidx.compose.material3.TextButton(onClick = { nav.navigate(Routes.PROGRESS) }) { Text("Progress") } } }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard {
                    Text("$completed of ${AcademyContent.lessons.size} lessons completed", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { completed / AcademyContent.lessons.size.toFloat() }, modifier = Modifier.fillMaxWidth())
                    Text("Lessons are short and practical. Each ends with a photo exercise in the camera.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
            recommended?.let { l ->
                item {
                    SectionCard(Modifier.clickable { nav.navigate(Routes.lesson(l.id)) }) {
                        Text("Recommended from your photos", style = MaterialTheme.typography.labelLarge, color = LumenColors.Teal)
                        Text(l.title, style = MaterialTheme.typography.titleLarge)
                        Text(l.summary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Track.entries.forEach { track ->
                item {
                    SectionTitle(track.title)
                    Text(track.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(AcademyContent.forTrack(track)) { l ->
                            val p = progress[l.id]
                            Column(
                                Modifier.width(220.dp).clip(RoundedCornerShape(16.dp)).clickable { nav.navigate(Routes.lesson(l.id)) },
                            ) {
                                LessonDiagram(l.diagram)
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                                    Text(l.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    if (p?.completed == true) Icon(LumenIcons.Done, "Completed", tint = LumenColors.Ready, modifier = Modifier.size(18.dp))
                                }
                                Text("${l.minutes} min · ${l.summary}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                if (p != null && !p.completed) LinearProgressIndicator(progress = { p.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LessonScreen(nav: NavHostController, lessonId: String) {
    val c = LocalContext.current.container
    val lesson = remember(lessonId) { AcademyContent.lesson(lessonId) }
    val scope = rememberCoroutineScope()
    val progressList by c.lessons.observeAll().collectAsStateWithLifecycle(emptyList())
    val progress = progressList.firstOrNull { it.lessonId == lessonId }
    val practicePhotos by c.photos.observeForLesson(lessonId).collectAsStateWithLifecycle(emptyList())

    LaunchedEffect(lessonId) { c.lessons.markOpened(lessonId) }

    Scaffold(topBar = { LumenTopBar(lesson?.track?.title ?: "Lesson", nav) }) { pad ->
        if (lesson == null) {
            Text("Lesson not found", Modifier.padding(pad).padding(16.dp))
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Text(lesson.title, style = MaterialTheme.typography.headlineSmall)
                Text(lesson.summary, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { LessonDiagram(lesson.diagram) }
            items(lesson.sections) { s ->
                Column {
                    Text(s.heading, style = MaterialTheme.typography.titleMedium)
                    Text(s.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (lesson.tips.isNotEmpty()) item {
                SectionCard {
                    Text("Tips", style = MaterialTheme.typography.titleSmall, color = LumenColors.Amber)
                    lesson.tips.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
            item {
                SectionCard {
                    Text("Practice: ${lesson.practice.title}", style = MaterialTheme.typography.titleMedium, color = LumenColors.Teal)
                    lesson.practice.steps.forEachIndexed { i, step -> Text("${i + 1}. $step", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { nav.navigate(Routes.camera(lesson.id, lesson.practice.strategy?.name)) }) {
                        Icon(LumenIcons.Shoot, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start practice in camera")
                    }
                    Text("${practicePhotos.size} of ${lesson.practice.shots} practice photos taken", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
            if (practicePhotos.isNotEmpty()) item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(practicePhotos) { p ->
                        AsyncImage(
                            model = File(p.thumbnailPath ?: p.originalPath), contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(96.dp).clip(RoundedCornerShape(10.dp)).clickable { nav.navigate(Routes.review(p.id)) },
                        )
                    }
                }
                if (practicePhotos.size >= 2) {
                    OutlinedButton(onClick = { nav.navigate(Routes.compare(practicePhotos[1].id, practicePhotos[0].id)) }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Compare your last two")
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
                    scope.launch { c.lessons.setCompleted(lessonId, progress?.completed != true) }
                }) {
                    Checkbox(checked = progress?.completed == true, onCheckedChange = { scope.launch { c.lessons.setCompleted(lessonId, it) } })
                    Text("Mark lesson as completed")
                }
            }
        }
    }
}
