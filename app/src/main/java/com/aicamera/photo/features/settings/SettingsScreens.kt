package com.aicamera.photo.features.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.aicamera.photo.container
import com.aicamera.photo.core.ai.LlmBackend
import com.aicamera.photo.core.ai.ModelBenchmark
import com.aicamera.photo.core.ai.ModelCatalog
import com.aicamera.photo.core.ai.ModelInstallState
import com.aicamera.photo.core.camera.CapabilityProbe
import com.aicamera.photo.core.camera.ThermalMonitor
import com.aicamera.photo.core.data.AiBackendPreference
import com.aicamera.photo.core.data.AppSettings
import com.aicamera.photo.core.data.CameraMode
import com.aicamera.photo.domain.CoachingIntensity
import com.aicamera.photo.navigation.Routes
import com.aicamera.photo.services.BackgroundJobs
import com.aicamera.photo.ui.components.LabeledValue
import com.aicamera.photo.ui.components.LumenTopBar
import com.aicamera.photo.ui.components.SectionCard
import com.aicamera.photo.ui.components.SectionTitle
import com.aicamera.photo.ui.components.formatBytes
import com.aicamera.photo.ui.components.formatDate
import com.aicamera.photo.ui.theme.LumenColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(nav: NavHostController) {
    val c = LocalContext.current.container
    val s by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val scope = rememberCoroutineScope()
    val ai by c.ai.status.collectAsStateWithLifecycle()

    Scaffold(topBar = { LumenTopBar("Settings", nav) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { SectionTitle("Coaching") }
            item {
                SectionCard {
                    Text("Coaching intensity", style = MaterialTheme.typography.titleSmall)
                    CoachingIntensity.entries.forEach { i ->
                        Row(Modifier.fillMaxWidth().clickable { scope.launch { c.settings.setIntensity(i) } }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = s.coachingIntensity == i, onClick = { scope.launch { c.settings.setIntensity(i) } })
                            Column {
                                Text(i.label)
                                Text(i.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Start in", style = MaterialTheme.typography.titleSmall)
                    listOf(CameraMode.CONTINUOUS to "Coach (continuous suggestions)", CameraMode.GUIDED to "Guided (choose a composition)").forEach { (m, label) ->
                        Row(Modifier.fillMaxWidth().clickable { scope.launch { c.settings.setDefaultMode(m) } }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = s.defaultMode == m, onClick = { scope.launch { c.settings.setDefaultMode(m) } })
                            Text(label)
                        }
                    }
                }
            }
            item {
                SectionCard {
                    Toggle("Thirds grid", s.showGrid) { scope.launch { c.settings.setShowGrid(it) } }
                    Toggle("Horizon level", s.showHorizonLevel) { scope.launch { c.settings.setShowHorizon(it) } }
                    Toggle("Subject outlines", s.showSubjectBoxes) { scope.launch { c.settings.setShowBoxes(it) } }
                    Toggle("Haptic cue when aligned", s.haptics) { scope.launch { c.settings.setHaptics(it) } }
                }
            }
            item { SectionTitle("Capture & review") }
            item {
                SectionCard {
                    Toggle("Save RAW (DNG) when supported", s.captureRaw, "Only offered on lenses that report RAW support.") { scope.launch { c.settings.setCaptureRaw(it) } }
                    Toggle("Critique every photo automatically", s.autoCritique, "Runs on the phone after each capture.") { scope.launch { c.settings.setAutoCritique(it) } }
                }
            }
            item { SectionTitle("On-device AI") }
            item {
                SectionCard {
                    Toggle("Use on-device AI when installed", s.aiEnabled, "AI adds scene interpretation and artistic feedback. The camera works fully without it.") {
                        scope.launch { c.settings.setAiEnabled(it) }
                    }
                    Toggle(
                        "Live AI ideas while shooting", s.liveAi,
                        "Off: tap ✨ in the camera to ask for an idea. On: ideas appear automatically, but the model stays in memory (~1 GB) while the camera is open.",
                    ) { scope.launch { c.settings.setLiveAi(it) } }
                    Text("Processor", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    Row {
                        AiBackendPreference.entries.forEach { b ->
                            Row(Modifier.clickable { scope.launch { c.settings.setAiBackend(b) } }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = s.aiBackend == b, onClick = { scope.launch { c.settings.setAiBackend(b) } })
                                Text(if (b == AiBackendPreference.GPU) "GPU (faster)" else "CPU (fallback)")
                                Spacer(Modifier.width(12.dp))
                            }
                        }
                    }
                    LabeledValue("Status", ai.label)
                    Button(onClick = { nav.navigate(Routes.MODELS) }, modifier = Modifier.padding(top = 8.dp)) { Text("Manage model & benchmark") }
                }
            }
            item { SectionTitle("Storage, privacy & diagnostics") }
            item {
                SectionCard {
                    NavRow("Storage and deletion") { nav.navigate(Routes.STORAGE) }
                    NavRow("Diagnostics and camera capabilities") { nav.navigate(Routes.DIAGNOSTICS) }
                    Toggle("Show diagnostics overlay in camera", s.showDiagnostics) { scope.launch { c.settings.setDiagnostics(it) } }
                }
            }
            item {
                SectionCard {
                    Text("Privacy", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Lumen works offline. Photos, scene analysis, coaching history and critiques are processed and stored only on this phone. " +
                            "There is no account and nothing is uploaded. The network is used only if you choose to download the AI model.",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            item {
                Text("Lumen Camera ${com.aicamera.photo.BuildConfig.VERSION_NAME} · AI proposes, deterministic logic calculates, you decide.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
            }
        }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, detail: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun NavRow(label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        Text("›")
    }
}

@Composable
fun ModelsScreen(nav: NavHostController) {
    val c = LocalContext.current.container
    val states by c.models.states.collectAsStateWithLifecycle()
    val benchmarks by c.database.benchmarkDao().observeAll().collectAsStateWithLifecycle(emptyList())
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val ai by c.ai.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val spec = ModelCatalog.GEMMA_4_E2B
    var confirmDownload by remember { mutableStateOf(false) }
    var benchProgress by remember { mutableStateOf<ModelBenchmark.Progress?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) c.models.importFrom(spec, uri)
    }
    val state = states[spec.id] ?: ModelInstallState.NotInstalled

    Scaffold(topBar = { LumenTopBar("On-device AI model", nav) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard {
                    Text(spec.name, style = MaterialTheme.typography.titleMedium)
                    Text(spec.description, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
                    LabeledValue("Download size", formatBytes(spec.sizeBytes))
                    LabeledValue("Memory while running", "≈ 1.1 GB with image input, less for text-only coaching")
                    LabeledValue("Licence", spec.license)
                    LabeledValue("Free space", formatBytes(c.models.freeSpaceBytes()))
                    LabeledValue("Status", ai.label)
                    (ai as? com.aicamera.photo.core.ai.AiStatus.LowMemory)?.let {
                        Text("Only ${it.availableMb} MB of memory is free; the model needs about ${it.neededMb} MB. Close other apps and try again.",
                            color = LumenColors.Issue, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    when (val st = state) {
                        ModelInstallState.NotInstalled, is ModelInstallState.Failed -> {
                            if (st is ModelInstallState.Failed) Text(st.reason, color = LumenColors.Issue, style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { confirmDownload = true }) { Text("Download") }
                                OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }) { Text("Import file") }
                            }
                        }
                        is ModelInstallState.Downloading -> {
                            Text("Downloading ${formatBytes(st.downloaded)} of ${formatBytes(st.total)}")
                            LinearProgressIndicator(progress = { st.fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                            TextButton(onClick = { c.models.cancelDownload(spec) }) { Text("Cancel") }
                        }
                        is ModelInstallState.Importing -> {
                            Text("Importing ${formatBytes(st.copied)}${if (st.total > 0) " of ${formatBytes(st.total)}" else ""}")
                            LinearProgressIndicator(progress = { if (st.total > 0) st.copied / st.total.toFloat() else 0f }, modifier = Modifier.fillMaxWidth())
                        }
                        is ModelInstallState.Verifying -> {
                            Text("Verifying checksum…")
                            LinearProgressIndicator(progress = { st.fraction }, modifier = Modifier.fillMaxWidth())
                        }
                        is ModelInstallState.Installed -> {
                            Text(if (st.record.verified) "Installed and verified (SHA-256 matches)." else "Installed, but the file doesn't match the published checksum - it may be a different build.",
                                color = if (st.record.verified) LumenColors.Ready else LumenColors.Amber, style = MaterialTheme.typography.bodySmall)
                            LabeledValue("Version", st.record.version)
                            LabeledValue("Checksum", st.record.sha256.take(16) + "…")
                            LabeledValue("Installed", formatDate(st.record.installedAt))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                                Button(onClick = { scope.launch { c.ai.resetFailures(); c.ai.ensureLoaded() } }) { Text("Load now") }
                                OutlinedButton(onClick = { scope.launch { c.ai.release() } }) { Text("Unload") }
                                TextButton(onClick = { scope.launch { c.ai.release(); c.models.delete(spec) } }) { Text("Delete") }
                            }
                        }
                    }
                    Text("The model is never bundled with the app and is downloaded only when you ask. After that, it works with no connection.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
            if (state is ModelInstallState.Installed) item {
                SectionCard {
                    Text("Device benchmark", style = MaterialTheme.typography.titleMedium)
                    Text("Measures cold start, multimodal response time, memory and thermal impact on this phone. Takes about a minute; keep the app open.",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
                    val p = benchProgress
                    if (p != null && p.fraction < 1f) {
                        Text(p.step)
                        LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                    } else {
                        Button(onClick = {
                            scope.launch(Dispatchers.Default) {
                                c.ai.release()
                                val backend = if (settings.aiBackend == AiBackendPreference.CPU) LlmBackend.CPU else LlmBackend.GPU
                                c.benchmark.run(spec, backend) { benchProgress = it }
                                c.ai.refreshStatus(c.thermal.status.value)
                            }
                        }) { Text("Run benchmark") }
                    }
                }
            }
            if (benchmarks.isNotEmpty()) item { SectionTitle("Benchmark results") }
            items(benchmarks) { b ->
                SectionCard {
                    Text("${b.deviceModel} · ${b.backend} · ${formatDate(b.createdAt)}", style = MaterialTheme.typography.labelMedium)
                    if (b.success) {
                        LabeledValue("Cold start", "${b.coldStartMs} ms")
                        LabeledValue("First multimodal response", "${b.firstResponseMs} ms")
                        LabeledValue("Warm response (avg of 3)", "${b.warmResponseMs} ms")
                        LabeledValue("Image input", if (b.imageInputWorked) "Works" else "No response")
                        b.peakMemoryMb?.let { LabeledValue("Memory used (system)", "$it MB") }
                        LabeledValue("Thermal", "${ModelBenchmark.thermalLabel(b.thermalBefore)} → ${ModelBenchmark.thermalLabel(b.thermalAfter)}")
                        val verdict = (b.warmResponseMs ?: Long.MAX_VALUE) <= 3_000
                        Text(if (verdict) "Within the 3 s target for coaching suggestions." else "Slower than the 3 s target - suggestions will appear less often.",
                            color = if (verdict) LumenColors.Ready else LumenColors.Amber, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("Failed: ${b.error}", color = LumenColors.Issue)
                    }
                }
            }
        }
        if (confirmDownload) {
            AlertDialog(
                onDismissRequest = { confirmDownload = false },
                title = { Text("Download ${formatBytes(spec.sizeBytes)}?") },
                text = { Text("This downloads the model from Hugging Face (${spec.license}). Wi-Fi is recommended. It needs about ${formatBytes((spec.sizeBytes * 1.1).toLong())} of free space and uses ≈${spec.estimatedRuntimeRamGb} GB of memory while running. Only the model file is downloaded - nothing is uploaded.") },
                confirmButton = { TextButton(onClick = { confirmDownload = false; c.models.download(spec) }) { Text("Download") } },
                dismissButton = { TextButton(onClick = { confirmDownload = false }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
fun StorageScreen(nav: NavHostController) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf<com.aicamera.photo.core.data.MediaStorage.Usage?>(null) }
    var modelBytes by remember { mutableStateOf(0L) }
    var confirm by remember { mutableStateOf<Pair<String, suspend () -> Unit>?>(null) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) {
        withContext(Dispatchers.IO) {
            usage = c.storage.usage()
            modelBytes = c.models.totalBytes()
        }
    }
    Scaffold(topBar = { LumenTopBar("Storage", nav) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard {
                    val u = usage
                    if (u == null) Text("Calculating…") else {
                        LabeledValue("Originals", formatBytes(u.originals))
                        LabeledValue("RAW files", formatBytes(u.raw))
                        LabeledValue("Developed versions", formatBytes(u.developed))
                        LabeledValue("Thumbnails", formatBytes(u.thumbnails))
                        LabeledValue("AI model", formatBytes(modelBytes))
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        LabeledValue("Total", formatBytes(u.total - u.models + modelBytes))
                    }
                }
            }
            item {
                SectionCard {
                    Text("Delete", style = MaterialTheme.typography.titleSmall)
                    Text("Exported copies in your gallery are not affected.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { c.storage.clearThumbnails(); BackgroundJobs.rebuildThumbnails(context); refresh++ }) { Text("Rebuild thumbnails") }
                    OutlinedButton(onClick = { confirm = "Delete the AI model?" to { c.ai.release(); c.models.delete(ModelCatalog.GEMMA_4_E2B) } }) { Text("Delete AI model") }
                    OutlinedButton(onClick = { confirm = "Reset Academy progress?" to { c.lessons.resetAll() } }) { Text("Reset lesson progress") }
                    Button(onClick = { confirm = "Delete all photos, RAW files, edits, critiques and coaching history?" to { c.photos.deleteAll() } }) { Text("Delete all photos and data") }
                }
            }
        }
        confirm?.let { (question, action) ->
            AlertDialog(
                onDismissRequest = { confirm = null },
                title = { Text(question) },
                text = { Text("This can't be undone.") },
                confirmButton = { TextButton(onClick = { scope.launch { action(); confirm = null; refresh++ } }) { Text("Delete") } },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
fun DiagnosticsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val c = context.container
    val diag by c.diagnostics.state.collectAsStateWithLifecycle()
    val thermal by c.thermal.status.collectAsStateWithLifecycle()
    var caps by remember { mutableStateOf<List<com.aicamera.photo.core.camera.CameraCapabilities>>(emptyList()) }
    LaunchedEffect(Unit) {
        runCatching {
            val provider = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(context).get()
            caps = provider.availableCameraInfos.map { CapabilityProbe.probe(context, it) }
        }
    }
    Scaffold(topBar = { LumenTopBar("Diagnostics", nav) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard {
                    Text("Runtime", style = MaterialTheme.typography.titleSmall)
                    LabeledValue("Camera", diag.cameraState)
                    LabeledValue("Vision rate", String.format("%.1f fps", diag.analysisFps))
                    LabeledValue("Frames processed / dropped", "${diag.framesProcessed} / ${diag.framesDropped}")
                    LabeledValue("Analysis time", "${diag.analysisMs} ms (detectors ${diag.detectorMs} ms)")
                    LabeledValue("Composition time", "${diag.compositionMs} ms")
                    LabeledValue("Analysis interval", "${diag.analysisIntervalMs} ms")
                    LabeledValue("Thermal", ThermalMonitor.label(thermal) + (c.thermal.headroom()?.let { " · headroom %.2f".format(it) } ?: ""))
                    LabeledValue("AI", diag.aiState)
                    diag.aiColdStartMs?.let { LabeledValue("AI cold start", "$it ms") }
                    diag.aiLastLatencyMs?.let { LabeledValue("AI last latency", "$it ms") }
                    LabeledValue("Heap (Java / native)", "${diag.javaHeapMb} / ${diag.nativeHeapMb} MB")
                    Text("Values update while the camera is open.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { SectionTitle("Camera capabilities") }
            items(caps) { cap ->
                SectionCard {
                    Text("Camera ${cap.cameraId} · ${cap.role.label}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        buildString {
                            appendLine("focal ${cap.focalLengthMm ?: "-"} mm (${cap.focalLength35mm ?: "-"} mm eq.)")
                            appendLine("logical multi-camera ${cap.isLogicalMultiCamera} (${cap.physicalCameraCount} physical)")
                            appendLine("zoom ${cap.zoomRange.start}–${cap.zoomRange.endInclusive}×")
                            appendLine("flash ${cap.hasFlash} · EV index ${cap.exposureIndexRange}")
                            appendLine("manual sensor ${cap.manualSensor} · ISO ${cap.isoRange ?: "-"}")
                            appendLine("exposure ${cap.exposureTimeRangeNs?.let { "${it.first}–${it.last} ns" } ?: "-"}")
                            appendLine("manual focus ${cap.manualFocus} (min ${cap.minFocusDistance} D)")
                            appendLine("AWB modes ${cap.awbModes}")
                            append("RAW ${cap.rawSupported} · hw level ${cap.hardwareLevel}")
                        },
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
