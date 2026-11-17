package com.aicamera.photo.features.filmlab

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.aicamera.photo.AppContainer
import com.aicamera.photo.container
import com.aicamera.photo.core.data.EditRepository
import com.aicamera.photo.core.image.FilmProfiles
import com.aicamera.photo.core.image.FilmRenderer
import com.aicamera.photo.core.image.ImageIo
import com.aicamera.photo.domain.FilmRecipe
import com.aicamera.photo.ui.components.LumenTopBar
import com.aicamera.photo.ui.theme.LumenColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class FilmLabState(
    val original: Bitmap? = null,
    val rendered: Bitmap? = null,
    val recipe: FilmRecipe = FilmRecipe(),
    val rendering: Boolean = false,
    val saving: Boolean = false,
    val editId: String? = null,
    val savedOutput: String? = null,
    val dirty: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

class FilmLabViewModel(private val c: AppContainer, private val photoId: String, initialEditId: String?) : ViewModel() {
    private val _state = MutableStateFlow(FilmLabState(editId = initialEditId))
    val state: StateFlow<FilmLabState> = _state
    private var renderJob: Job? = null
    private var originalPath: String? = null

    init {
        viewModelScope.launch {
            val photo = c.photos.get(photoId) ?: run { _state.update { it.copy(error = "Photo not found") }; return@launch }
            originalPath = photo.originalPath
            val edit = initialEditId?.let { c.edits.get(it) }
            val recipe = edit?.let { EditRepository.decodeRecipe(it.recipeJson) } ?: FilmRecipe(profileId = "natural", profileStrength = 0f)
            val preview = withContext(Dispatchers.IO) { runCatching { ImageIo.decodeUpright(File(photo.originalPath), 1600) }.getOrNull() }
            if (preview == null) {
                _state.update { it.copy(error = "Could not open the photo") }
                return@launch
            }
            _state.update { it.copy(original = preview, recipe = recipe, savedOutput = edit?.outputPath) }
            render(recipe)
        }
    }

    fun update(change: (FilmRecipe) -> FilmRecipe) {
        val r = change(_state.value.recipe)
        _state.update { it.copy(recipe = r, dirty = true, message = null) }
        render(r)
    }

    fun selectProfile(id: String) = update { it.copy(profileId = id, profileStrength = if (it.profileStrength == 0f) 1f else it.profileStrength) }

    fun reset() = update { FilmRecipe(profileId = "natural", profileStrength = 0f) }

    private fun render(recipe: FilmRecipe) {
        val src = _state.value.original ?: return
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            delay(40) // coalesce rapid slider changes
            _state.update { it.copy(rendering = true) }
            val out = FilmRenderer.render(src, recipe)
            // The previous bitmap may still be on screen this frame; let GC reclaim it instead of recycling.
            _state.update { it.copy(rendered = out, rendering = false) }
        }
    }

    /** Renders the full-resolution developed version into a new file. The original is never touched. */
    fun save(onDone: (() -> Unit)? = null) {
        val path = originalPath ?: return
        if (_state.value.saving) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true, message = null) }
            try {
                val existingId = _state.value.editId
                val outFile = c.storage.developedFileFor(existingId ?: c.storage.newPhotoId())
                c.developer.develop(File(path), _state.value.recipe, outFile)
                val entity = c.edits.save(existingId, photoId, path, _state.value.recipe, outFile.absolutePath)
                _state.update { it.copy(saving = false, editId = entity.id, savedOutput = outFile.absolutePath, dirty = false, message = "Developed version saved") }
                onDone?.invoke()
            } catch (t: Throwable) {
                _state.update { it.copy(saving = false, message = "Saving failed: ${t.message}") }
            }
        }
    }

    fun export() {
        val s = _state.value
        if (s.savedOutput == null || s.dirty) {
            save { export() }
            return
        }
        viewModelScope.launch {
            try {
                val file = File(s.savedOutput)
                val uri = c.exporter.export(file, file.name)
                s.editId?.let { c.edits.setExportedUri(it, uri.toString()) }
                _state.update { it.copy(message = "Exported to Pictures/Lumen Camera") }
            } catch (t: Throwable) {
                _state.update { it.copy(message = "Export failed: ${t.message}") }
            }
        }
    }

}

@Composable
fun FilmLabScreen(nav: NavHostController, photoId: String, editId: String?) {
    val c = LocalContext.current.container
    val vm: FilmLabViewModel = viewModel(key = "film_$photoId$editId") { FilmLabViewModel(c, photoId, editId) }
    val s by vm.state.collectAsStateWithLifecycle()
    var showBefore by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }

    Scaffold(topBar = {
        LumenTopBar("Film Lab", nav) { TextButton(onClick = vm::reset) { Text("Reset") } }
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).navigationBarsPadding()) {
            Box(
                Modifier.fillMaxWidth().weight(1f).background(Color.Black)
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            showBefore = true
                            tryAwaitRelease()
                            showBefore = false
                        })
                    },
                contentAlignment = Alignment.Center,
            ) {
                val bmp = if (showBefore) s.original else (s.rendered ?: s.original)
                if (bmp != null) {
                    Image(bmp.asImageBitmap(), contentDescription = if (showBefore) "Original" else "Developed", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                } else if (s.error != null) {
                    Text(s.error!!, color = Color.White)
                } else CircularProgressIndicator()
                Text(
                    if (showBefore) "Original" else "Hold to compare",
                    color = Color.White, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp).background(LumenColors.Scrim, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                )
                if (s.rendering) CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(10.dp).size(20.dp), strokeWidth = 2.dp)
            }

            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(FilmProfiles.all) { p ->
                    val selected = s.recipe.profileId == p.id && s.recipe.profileStrength > 0f
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp).clickable { vm.selectProfile(p.id) }) {
                        Box(
                            Modifier.size(48.dp).clip(CircleShape).background(Color(p.swatch))
                                .border(3.dp, if (selected) LumenColors.Amber else Color.Transparent, CircleShape),
                        )
                        Text(p.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(top = 4.dp),
                            color = if (selected) LumenColors.Amber else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            Text(FilmProfiles.byId(s.recipe.profileId).description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))

            PrimaryTabRow(selectedTabIndex = tab) {
                listOf("Light", "Colour", "Character").forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            Column(Modifier.fillMaxWidth().height(190.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp)) {
                val r = s.recipe
                when (tab) {
                    0 -> {
                        Adj("Exposure", r.exposure, -2f..2f) { v -> vm.update { it.copy(exposure = v) } }
                        Adj("Contrast", r.contrast) { v -> vm.update { it.copy(contrast = v) } }
                        Adj("Highlights", r.highlights) { v -> vm.update { it.copy(highlights = v) } }
                        Adj("Shadows", r.shadows) { v -> vm.update { it.copy(shadows = v) } }
                        Adj("Whites", r.whites) { v -> vm.update { it.copy(whites = v) } }
                        Adj("Blacks", r.blacks) { v -> vm.update { it.copy(blacks = v) } }
                    }
                    1 -> {
                        Adj("Temperature", r.temperature) { v -> vm.update { it.copy(temperature = v) } }
                        Adj("Tint", r.tint) { v -> vm.update { it.copy(tint = v) } }
                        Adj("Saturation", r.saturation) { v -> vm.update { it.copy(saturation = v) } }
                        Adj("Vibrance", r.vibrance) { v -> vm.update { it.copy(vibrance = v) } }
                    }
                    else -> {
                        Adj("Film strength", r.profileStrength, 0f..1f) { v -> vm.update { it.copy(profileStrength = v) } }
                        Adj("Grain", r.grain, 0f..1f) { v -> vm.update { it.copy(grain = v) } }
                        Adj("Fade", r.fade, 0f..1f) { v -> vm.update { it.copy(fade = v) } }
                        Adj("Vignette", r.vignette, 0f..1f) { v -> vm.update { it.copy(vignette = v) } }
                    }
                }
            }
            s.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LumenColors.Teal, modifier = Modifier.padding(horizontal = 16.dp)) }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { vm.save() }, enabled = !s.saving, modifier = Modifier.weight(1f)) {
                    if (s.saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text(if (s.editId == null) "Save version" else "Update version")
                }
                Button(onClick = vm::export, enabled = !s.saving, modifier = Modifier.weight(1f)) { Text("Export to gallery") }
            }
            Text("Originals are never changed - edits are saved as a recipe and a separate developed file.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
        }
    }
}

@Composable
private fun Adj(label: String, value: Float, range: ClosedFloatingPointRange<Float> = -1f..1f, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(104.dp))
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
        Text(String.format("%+.2f", value), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(44.dp).padding(start = 6.dp))
    }
    Spacer(Modifier.height(2.dp))
}
