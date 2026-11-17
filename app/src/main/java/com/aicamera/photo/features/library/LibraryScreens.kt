package com.aicamera.photo.features.library

import com.aicamera.photo.ui.theme.LumenIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.aicamera.photo.container
import com.aicamera.photo.core.data.EditRepository
import com.aicamera.photo.core.data.PhotoRepository
import com.aicamera.photo.core.data.db.PhotoListItem
import com.aicamera.photo.core.image.FilmProfiles
import com.aicamera.photo.domain.CoachingPhase
import com.aicamera.photo.domain.FindingSeverity
import com.aicamera.photo.domain.RecommendationType
import com.aicamera.photo.features.academy.AcademyContent
import com.aicamera.photo.navigation.Routes
import com.aicamera.photo.ui.components.LabeledValue
import com.aicamera.photo.ui.components.LumenTopBar
import com.aicamera.photo.ui.components.Pill
import com.aicamera.photo.ui.components.SectionCard
import com.aicamera.photo.ui.components.SectionTitle
import com.aicamera.photo.ui.components.formatDate
import com.aicamera.photo.ui.components.severityColor
import com.aicamera.photo.ui.theme.LumenColors
import kotlinx.coroutines.launch
import java.io.File

private enum class LibraryFilter(val label: String) { ALL("All"), DEVELOPED("Developed"), RAW("RAW"), PRACTICE("Practice"), GUIDED("Guided") }

@Composable
fun LibraryScreen(nav: NavHostController) {
    val c = LocalContext.current.container
    val items by c.photos.observeList().collectAsStateWithLifecycle(emptyList())
    var filter by remember { mutableStateOf(LibraryFilter.ALL) }
    val compareSelection = remember { mutableStateListOf<String>() }
    var comparing by remember { mutableStateOf(false) }

    val shown = items.filter {
        when (filter) {
            LibraryFilter.ALL -> true
            LibraryFilter.DEVELOPED -> it.editCount > 0
            LibraryFilter.RAW -> it.rawPath != null
            LibraryFilter.PRACTICE -> it.lessonId != null
            LibraryFilter.GUIDED -> it.mode == "GUIDED"
        }
    }

    Scaffold(topBar = {
        LumenTopBar(if (comparing) "Select two photos" else "Library", nav) {
            TextButton(onClick = { nav.navigate(Routes.PROGRESS) }) { Text("Progress") }
            IconButton(onClick = { comparing = !comparing; compareSelection.clear() }) {
                Icon(LumenIcons.Compare, "Compare", tint = if (comparing) LumenColors.Amber else MaterialTheme.colorScheme.onSurface)
            }
        }
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibraryFilter.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) }) }
            }
            if (comparing && compareSelection.size == 2) {
                Button(onClick = { nav.navigate(Routes.compare(compareSelection[0], compareSelection[1])); comparing = false; compareSelection.clear() },
                    modifier = Modifier.padding(12.dp).fillMaxWidth()) { Text("Compare") }
            }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (items.isEmpty()) "No photos yet - take your first one!" else "No photos match this filter.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyVerticalGrid(GridCells.Adaptive(110.dp), contentPadding = PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(shown, key = { it.id }) { item ->
                        val selected = item.id in compareSelection
                        Thumb(item, selected) {
                            if (comparing) {
                                if (selected) compareSelection.remove(item.id) else if (compareSelection.size < 2) compareSelection.add(item.id)
                            } else nav.navigate(Routes.photo(item.id))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Thumb(item: PhotoListItem, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp))
            .border(if (selected) 3.dp else 0.dp, LumenColors.Amber, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
    ) {
        val path = item.latestEditPath?.takeIf { File(it).exists() } ?: item.thumbnailPath?.takeIf { File(it).exists() } ?: item.originalPath
        AsyncImage(model = File(path), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Row(Modifier.align(Alignment.BottomStart).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (item.rawPath != null) Badge("RAW")
            if (item.editCount > 0) Badge("DEV")
            if (item.lessonId != null) Badge("PRACTICE")
        }
    }
}

@Composable
private fun Badge(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = Color.White,
        modifier = Modifier.background(Color(0xAA000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
}

@Composable
fun PhotoDetailScreen(nav: NavHostController, photoId: String) {
    val c = LocalContext.current.container
    val details by c.photos.observeDetails(photoId).collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    var versionIndex by remember { mutableStateOf(0) } // 0 = original
    var confirmDelete by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = {
        LumenTopBar("Photo", nav) {
            IconButton(onClick = { confirmDelete = true }) { Icon(LumenIcons.Delete, "Delete") }
        }
    }) { pad ->
        val d = details
        if (d == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text("This photo no longer exists.") }
            return@Scaffold
        }
        val edits = d.edits.sortedByDescending { it.updatedAt }
        val meta = PhotoRepository.decodeMetadata(d.photo.metadataJson)
        val shownPath = if (versionIndex == 0) d.photo.originalPath else edits.getOrNull(versionIndex - 1)?.outputPath ?: d.photo.originalPath
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                AsyncImage(File(shownPath), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(16.dp)))
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = versionIndex == 0, onClick = { versionIndex = 0 }, label = { Text("Original") })
                    edits.forEachIndexed { i, e ->
                        FilterChip(selected = versionIndex == i + 1, onClick = { versionIndex = i + 1 }, label = { Text(FilmProfiles.byId(e.profileId).name) })
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { nav.navigate(Routes.review(photoId)) }) { Text("Critique") }
                    OutlinedButton(onClick = {
                        val editId = if (versionIndex > 0) edits.getOrNull(versionIndex - 1)?.id else null
                        nav.navigate(Routes.filmLab(photoId, editId))
                    }) { Text(if (versionIndex > 0) "Edit version" else "Film Lab") }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            message = runCatching {
                                val f = File(shownPath)
                                val uri = c.exporter.export(f, f.name)
                                if (versionIndex == 0) c.photos.setExportedUri(photoId, uri.toString())
                                else edits.getOrNull(versionIndex - 1)?.let { c.edits.setExportedUri(it.id, uri.toString()) }
                                "Exported to Pictures/Lumen Camera"
                            }.getOrElse { "Export failed: ${it.message}" }
                        }
                    }) { Text("Export this version") }
                    d.photo.rawPath?.let { raw ->
                        OutlinedButton(onClick = {
                            scope.launch {
                                message = runCatching { c.exporter.export(File(raw), File(raw).name, "image/x-adobe-dng"); "RAW exported" }
                                    .getOrElse { "Export failed: ${it.message}" }
                            }
                        }) { Text("Export RAW") }
                    }
                }
                message?.let { Text(it, color = LumenColors.Teal, style = MaterialTheme.typography.bodySmall) }
            }
            item {
                SectionCard {
                    Text("Capture", style = MaterialTheme.typography.titleSmall)
                    LabeledValue("Date", formatDate(d.photo.capturedAt))
                    LabeledValue("Mode", if (d.photo.mode == "GUIDED") "Guided" else "Coach")
                    meta?.lensLabel?.let { LabeledValue("Lens", it) }
                    meta?.focalLength35mm?.let { LabeledValue("Focal length (35mm eq.)", "$it mm") }
                    meta?.aperture?.let { LabeledValue("Aperture", "f/%.1f".format(it)) }
                    meta?.shutterLabel()?.let { LabeledValue("Shutter", it) }
                    meta?.iso?.let { LabeledValue("ISO", it.toString()) }
                    meta?.exposureCompensation?.let { if (it != 0f) LabeledValue("Exposure comp.", "%+.1f EV".format(it)) }
                    LabeledValue("Size", "${meta?.width ?: d.photo.width} × ${meta?.height ?: d.photo.height}")
                    LabeledValue("RAW", if (d.photo.rawPath != null) "DNG saved" else "No")
                    d.photo.lessonId?.let { id -> AcademyContent.lesson(id)?.let { LabeledValue("Practice", it.title) } }
                }
            }
            d.compositions.firstOrNull()?.let { comp ->
                item {
                    SectionCard {
                        Text("Composition", style = MaterialTheme.typography.titleSmall)
                        val cand = PhotoRepository.decodeCandidate(comp.candidateJson)
                        LabeledValue("Selected", cand?.strategy?.displayName ?: comp.strategy)
                        comp.alignmentState?.let { LabeledValue("Alignment at capture", it.replace('_', ' ').lowercase().replaceFirstChar { c -> c.uppercase() }) }
                        cand?.explanation?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                    }
                }
            }
            d.analyses.firstOrNull { it.stage == "final" }?.let { a ->
                item {
                    val scene = PhotoRepository.decodeScene(a.snapshotJson)
                    SectionCard {
                        Text("Scene analysis", style = MaterialTheme.typography.titleSmall)
                        scene?.let {
                            LabeledValue("Scene", it.sceneType.label)
                            LabeledValue("Light", it.lighting.label)
                            LabeledValue("Subjects", it.subjects.size.toString())
                            if (it.sceneLabels.isNotEmpty()) LabeledValue("Labels", it.sceneLabels.joinToString())
                        }
                    }
                }
            }
            if (d.coachingEvents.isNotEmpty()) item {
                SectionCard {
                    Text("Coaching before this shot", style = MaterialTheme.typography.titleSmall)
                    d.coachingEvents.filter { it.phase == CoachingPhase.DISPLAYED.name }.takeLast(5).forEach { e ->
                        val outcome = d.coachingEvents.lastOrNull { it.recommendationId == e.recommendationId && it.phase != CoachingPhase.DISPLAYED.name && it.phase != CoachingPhase.NEW.name }
                        Text("• ${e.instruction}${outcome?.let { " (${it.phase.lowercase()})" } ?: ""}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
            d.feedback?.let { f ->
                item {
                    SectionCard(Modifier.clickable { nav.navigate(Routes.review(photoId)) }) {
                        Text("Critique", style = MaterialTheme.typography.titleSmall)
                        Text(f.summary, style = MaterialTheme.typography.bodyMedium)
                        Text("Open full critique ›", color = LumenColors.Teal, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Delete photo?") },
                text = { Text("This removes the original, any RAW file, developed versions and the critique from Lumen. Copies already exported to your gallery are kept.") },
                confirmButton = { TextButton(onClick = { scope.launch { c.photos.deletePhoto(photoId); confirmDelete = false; nav.popBackStack() } }) { Text("Delete") } },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
fun CompareScreen(nav: NavHostController, a: String, b: String) {
    val c = LocalContext.current.container
    val da by c.photos.observeDetails(a).collectAsStateWithLifecycle(null)
    val db by c.photos.observeDetails(b).collectAsStateWithLifecycle(null)
    Scaffold(topBar = { LumenTopBar("Compare", nav) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOfNotNull(da, db).forEachIndexed { i, d ->
                item {
                    SectionCard {
                        Text(if (i == 0) "Photo A" else "Photo B", style = MaterialTheme.typography.titleSmall, color = LumenColors.Amber)
                        Text(formatDate(d.photo.capturedAt), style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(6.dp))
                        AsyncImage(File(d.edits.firstOrNull()?.outputPath ?: d.photo.originalPath), null, contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(12.dp)))
                        d.compositions.firstOrNull()?.let { comp -> Text("Composition: ${PhotoRepository.decodeCandidate(comp.candidateJson)?.strategy?.displayName ?: comp.strategy}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                        val fb = d.feedback?.let { PhotoRepository.decodeFeedback(it.feedbackJson) }
                        fb?.let { f ->
                            Text(f.summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                            Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                f.findings.forEach { Pill(it.title, severityColor(it.severity).copy(alpha = 0.85f)) }
                            }
                        } ?: Text("No critique yet.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** Profile: progress, recurring strengths and mistakes, coaching follow-through. */
@Composable
fun ProgressScreen(nav: NavHostController) {
    val c = LocalContext.current.container
    val photos by c.photos.observeList().collectAsStateWithLifecycle(emptyList())
    val feedback by c.photos.observeRecentFeedback(60).collectAsStateWithLifecycle(emptyList())
    val lessons by c.lessons.observeAll().collectAsStateWithLifecycle(emptyList())
    val coaching by c.coaching.observeTypeCounts().collectAsStateWithLifecycle(emptyList())

    val findings = feedback.mapNotNull { PhotoRepository.decodeFeedback(it.feedbackJson) }.flatMap { it.findings }
    val strengths = findings.filter { it.severity == FindingSeverity.STRENGTH }.groupingBy { it.title }.eachCount().entries.sortedByDescending { it.value }.take(4)
    val issues = findings.filter { it.severity == FindingSeverity.ISSUE || it.severity == FindingSeverity.SUGGESTION }
        .groupBy { it.title }.entries.sortedByDescending { it.value.size }.take(4)
    val shown = coaching.filter { it.phase == CoachingPhase.DISPLAYED.name }.sumOf { it.count }
    val achieved = coaching.filter { it.phase == CoachingPhase.ACHIEVED.name }.sumOf { it.count }
    val dismissed = coaching.filter { it.phase == CoachingPhase.DISMISSED.name }.sumOf { it.count }

    Scaffold(topBar = { LumenTopBar("Your progress", nav) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard {
                    LabeledValue("Photos taken", photos.size.toString())
                    LabeledValue("Guided captures", photos.count { it.mode == "GUIDED" }.toString())
                    LabeledValue("Developed in Film Lab", photos.count { it.editCount > 0 }.toString())
                    LabeledValue("Lessons completed", "${lessons.count { it.completed }} / ${AcademyContent.lessons.size}")
                }
            }
            item {
                SectionCard {
                    Text("Coaching", style = MaterialTheme.typography.titleSmall)
                    LabeledValue("Suggestions shown", shown.toString())
                    LabeledValue("Achieved", achieved.toString())
                    LabeledValue("Dismissed", dismissed.toString())
                    if (shown > 0) {
                        Text("Follow-through", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                        LinearProgressIndicator(progress = { achieved / shown.toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                    val top = coaching.filter { it.phase == CoachingPhase.DISPLAYED.name }.maxByOrNull { it.count }
                    top?.let { t ->
                        val label = runCatching { RecommendationType.valueOf(t.type).label }.getOrDefault(t.type)
                        Text("Most frequent tip: $label", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            item { SectionTitle("Recurring strengths") }
            if (strengths.isEmpty()) item { Text("Take a few photos to see patterns.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(strengths) { (title, n) -> Text("• $title ($n×)", style = MaterialTheme.typography.bodyMedium) }
            item { SectionTitle("Things to work on") }
            if (issues.isEmpty()) item { Text("Nothing recurring yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(issues) { (title, list) ->
                val lesson = AcademyContent.lesson(list.firstNotNullOfOrNull { it.lessonId })
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = lesson != null) { lesson?.let { nav.navigate(Routes.lesson(it.id)) } }.padding(4.dp)) {
                    Text("• $title (${list.size}×)", style = MaterialTheme.typography.bodyMedium)
                    lesson?.let { Text("   Lesson: ${it.title} ›", color = LumenColors.Teal, style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
}
