package com.aicamera.photo.features.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.text.font.FontWeight
import com.aicamera.photo.ui.components.GlassIconButton
import com.aicamera.photo.ui.components.PillSwitch
import com.aicamera.photo.ui.components.glass
import com.aicamera.photo.ui.theme.LumenIcons
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.aicamera.photo.container
import com.aicamera.photo.core.ai.AiStatus
import com.aicamera.photo.core.camera.CameraControlRequest
import com.aicamera.photo.core.camera.FlashSetting
import com.aicamera.photo.core.camera.ThermalMonitor
import com.aicamera.photo.core.data.CameraMode
import com.aicamera.photo.domain.AlignmentState
import com.aicamera.photo.navigation.Routes
import com.aicamera.photo.ui.theme.LumenColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun CameraRoute(nav: NavHostController, practiceLessonId: String?, practiceStrategy: String?) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        askedOnce = true
    }
    if (granted) {
        CameraScreen(nav, practiceLessonId, practiceStrategy)
    } else {
        PermissionScreen(askedOnce, onRequest = { launcher.launch(Manifest.permission.CAMERA) }, onLibrary = { nav.navigate(Routes.LIBRARY) }, onAcademy = { nav.navigate(Routes.ACADEMY) })
    }
}

@Composable
private fun PermissionScreen(askedOnce: Boolean, onRequest: () -> Unit, onLibrary: () -> Unit, onAcademy: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(LumenIcons.Camera, null, tint = LumenColors.Amber, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text("See better. Shoot better.\nLearn photography.", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "Lumen coaches you in the viewfinder while you shoot. Everything is analysed on this phone - no account, no uploads.",
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text(if (askedOnce) "Allow camera access" else "Start the camera") }
        if (askedOnce) {
            Text("If the prompt doesn't appear, enable Camera for Lumen in system settings.", style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.material3.OutlinedButton(onClick = onLibrary) { Text("Library") }
            androidx.compose.material3.OutlinedButton(onClick = onAcademy) { Text("Academy") }
        }
    }
}

@Composable
fun CameraScreen(nav: NavHostController, practiceLessonId: String?, practiceStrategy: String?) {
    val context = LocalContext.current
    val container = context.container
    val vm: CameraViewModel = viewModel { CameraViewModel(container) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val cam by vm.camera.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val diag by vm.diagnostics.collectAsStateWithLifecycle()
    val ai by vm.aiStatus.collectAsStateWithLifecycle()
    val latestId by vm.latestPhotoId.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var showPro by remember { mutableStateOf(false) }

    LaunchedEffect(practiceLessonId, practiceStrategy) { vm.setPractice(practiceLessonId, practiceStrategy) }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    LaunchedEffect(previewView, lifecycleOwner) {
        vm.start(lifecycleOwner, previewView.surfaceProvider)
        vm.setMeteringFactory(previewView.meteringPointFactory)
    }
    DisposableEffect(Unit) { onDispose { vm.stop() } }

    // One haptic tick when the guided composition becomes ready.
    LaunchedEffect(ui.alignment?.state) {
        if (ui.alignment?.state == AlignmentState.READY && settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    LaunchedEffect(ui.toast, cam.message) {
        if (ui.toast != null || cam.message != null) {
            delay(2_800)
            vm.consumeToast()
        }
    }
    LaunchedEffect(ui.showExposure, ui.focusPoint) {
        if (ui.showExposure) {
            delay(5_000)
            vm.hideExposure()
        }
    }
    val flash = remember { Animatable(0f) }
    LaunchedEffect(ui.lastCapturedAt) {
        if (ui.lastCapturedAt > 0) {
            flash.snapTo(0.7f)
            flash.animateTo(0f, tween(250))
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // ---- Top bar: frosted round buttons
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassIconButton(LumenIcons.Settings, "Settings", { nav.navigate(Routes.SETTINGS) })
            if (cam.capabilities?.hasFlash == true) {
                GlassIconButton(
                    when (cam.flash) { FlashSetting.OFF -> LumenIcons.FlashOff; FlashSetting.AUTO -> LumenIcons.FlashAuto; FlashSetting.ON -> LumenIcons.FlashOn },
                    "Flash: ${cam.flash.name.lowercase()}",
                    {
                        val next = when (cam.flash) { FlashSetting.OFF -> FlashSetting.AUTO; FlashSetting.AUTO -> FlashSetting.ON; FlashSetting.ON -> FlashSetting.OFF }
                        vm.apply(CameraControlRequest.Flash(next))
                    },
                    active = cam.flash != FlashSetting.OFF,
                    tint = if (cam.flash != FlashSetting.OFF) LumenColors.Amber else Color.White,
                )
            }
            if (cam.capabilities?.rawSupported == true) {
                Box(
                    Modifier
                        .height(42.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (cam.rawActive) LumenColors.Amber.copy(alpha = 0.22f) else Color(0x59101418))
                        .border(1.dp, if (cam.rawRequested) LumenColors.Amber.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
                        .clickable { vm.setRaw(!cam.rawRequested) }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("RAW", color = if (cam.rawRequested) LumenColors.Amber else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            AiChip(ai, ui.aiThinking) {
                when (ai) {
                    AiStatus.NotInstalled, AiStatus.Disabled -> nav.navigate(Routes.MODELS)
                    else -> vm.askAi()
                }
            }
            GlassIconButton(LumenIcons.Academy, "Academy", { nav.navigate(Routes.ACADEMY) })
            GlassIconButton(LumenIcons.Grid, "Library", { nav.navigate(Routes.LIBRARY) })
        }

        // ---- Mode switch: sliding pill
        PillSwitch(
            options = listOf("Guided", "Coach"),
            selected = if (ui.mode == CameraMode.GUIDED) 0 else 1,
            onSelect = { vm.setMode(if (it == 0) CameraMode.GUIDED else CameraMode.CONTINUOUS) },
            modifier = Modifier.align(Alignment.CenterHorizontally).width(220.dp).padding(bottom = 8.dp),
        )
        ui.practiceLessonId?.let {
            Text("Practice mode - photos are linked to your lesson", color = LumenColors.Teal, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp))
        }

        // ---- Viewfinder (3:4 matches the 4:3 analysis stream, so overlay geometry maps 1:1)
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(20.dp))
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { o -> vm.tapToFocus(o.x, o.y) })
                }
                .pointerInput(cam.zoomRatio) {
                    detectTransformGestures { _, _, zoom, _ ->
                        if (zoom != 1f) vm.apply(CameraControlRequest.Zoom(cam.zoomRatio * zoom))
                    }
                },
        ) {
            AndroidView({ previewView }, Modifier.fillMaxSize())
            ViewfinderOverlay(ui, settings, cam.isFrontCamera, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value)))

            if (settings.showDiagnostics) {
                Text(
                    "${cam.capabilities?.role?.label ?: "-"} ${String.format("%.1f", cam.zoomRatio)}x\n" +
                        "vision ${String.format("%.1f", diag.analysisFps)} fps · ${diag.analysisMs} ms (ML ${diag.detectorMs})\n" +
                        "dropped ${diag.framesDropped} · interval ${diag.analysisIntervalMs} ms\n" +
                        "thermal ${ThermalMonitor.label(diag.thermalStatus)} · heap ${diag.javaHeapMb}/${diag.nativeHeapMb} MB\n" +
                        "AI ${diag.aiState}${diag.aiLastLatencyMs?.let { " · $it ms" } ?: ""}\n" +
                        "ISO ${cam.live.iso ?: "-"} · ${cam.live.exposureTimeNs?.let { shutterLabel(it) } ?: "-"}",
                    color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 12.sp,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).glass(RoundedCornerShape(12.dp), strong = true).padding(8.dp),
                )
            }

            // Coaching layers.
            Column(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(10.dp)) {
                if (ui.mode == CameraMode.CONTINUOUS) {
                    AnimatedVisibility(ui.suggestion != null, enter = fadeIn(), exit = fadeOut()) {
                        ui.suggestion?.let { s ->
                            SuggestionCard(
                                s, ui.suggestionAcknowledged,
                                onFollow = vm::acknowledgeSuggestion,
                                onDismiss = vm::dismissSuggestion,
                                onAnother = vm::anotherIdea,
                                onGuided = { vm.setMode(CameraMode.GUIDED) },
                            )
                        }
                    }
                }
                if (ui.mode == CameraMode.GUIDED && ui.guidedPhase == GuidedPhase.ANALYZING) AnalyzingBanner()
                if (ui.aiThinking) {
                    Row(
                        Modifier.padding(top = 8.dp).glass(RoundedCornerShape(50), strong = true).padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = LumenColors.Ai)
                        Spacer(Modifier.width(10.dp))
                        Text(if (ai == AiStatus.Loading) "Loading AI model…" else "AI is looking at the scene…", color = Color.White, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (ui.mode == CameraMode.GUIDED) {
                    when (ui.guidedPhase) {
                        GuidedPhase.CHOOSING -> CompositionChooser(ui.candidates, vm::selectCandidate, vm::shootFreely, vm::reanalyze)
                        GuidedPhase.ALIGNING -> ui.selected?.let { AlignmentPill(it.strategy.displayName, ui.alignment, vm::reanalyze) }
                        GuidedPhase.FREE -> Text("Shooting freely - tap Guided again to analyse", color = Color.White,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.glass(RoundedCornerShape(50), strong = true).clickable { vm.reanalyze() }.padding(horizontal = 14.dp, vertical = 9.dp))
                        else -> Unit
                    }
                }
                val message = ui.toast ?: cam.message
                AnimatedVisibility(message != null, enter = fadeIn(), exit = fadeOut()) {
                    Text(message.orEmpty(), color = Color.White, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp).glass(RoundedCornerShape(50), strong = true).padding(horizontal = 16.dp, vertical = 10.dp))
                }
            }
        }

        // ---- Controls
        Column(Modifier.fillMaxWidth().weight(1f).navigationBarsPadding(), verticalArrangement = Arrangement.SpaceEvenly) {
            if (showPro) {
                ProControls(cam, { vm.apply(it) }, Modifier.padding(horizontal = 10.dp))
            } else if (ui.showExposure && cam.capabilities?.supportsExposureCompensation == true) {
                val caps = cam.capabilities!!
                Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("☀", color = LumenColors.Amber)
                    Slider(
                        value = cam.exposureIndex.toFloat(),
                        onValueChange = { vm.apply(CameraControlRequest.ExposureCompensation(it.toInt())) },
                        valueRange = caps.exposureIndexRange.first.toFloat()..caps.exposureIndexRange.last.toFloat(),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text(String.format("%+.1f", cam.exposureIndex * caps.exposureStep), color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
            // Lens chips: optical switches are labelled; pinch zoom beyond them is digital.
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                Modifier.glass(RoundedCornerShape(50)).padding(4.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                cam.lensOptions.forEach { lens ->
                    val selected = lens.id == cam.selectedLensId
                    Box(
                        Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) LumenColors.Amber else Color.Transparent)
                            .clickable { vm.camera.selectLens(lens.id) }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(lens.label, color = if (selected) Color(0xFF221500) else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                val lensZoom = cam.lensOptions.firstOrNull { it.id == cam.selectedLensId }?.zoomRatio ?: 1f
                if (kotlin.math.abs(cam.zoomRatio - lensZoom) > 0.05f) {
                    Text("${String.format("%.1f", cam.zoomRatio)}×", color = LumenColors.Amber, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
            cam.lensOptions.firstOrNull { it.id == cam.selectedLensId }?.let {
                Text(it.detail, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val thumbId = ui.lastPhotoId ?: latestId
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.08f))
                        .border(1.5.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                        .clickable { thumbId?.let { nav.navigate(Routes.review(it)) } ?: nav.navigate(Routes.LIBRARY) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (thumbId != null) LatestThumb(thumbId)
                    else Icon(LumenIcons.Library, "Library", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(24.dp))
                }
                ShutterButton(
                    ready = ui.mode == CameraMode.GUIDED && ui.alignment?.state == AlignmentState.READY,
                    nearly = ui.mode == CameraMode.GUIDED && ui.alignment?.state == AlignmentState.NEARLY_ALIGNED,
                    busy = cam.capturing,
                ) { vm.capture() }
                GlassIconButton(LumenIcons.Pro, "Pro controls", { showPro = !showPro }, size = 56.dp, active = showPro,
                    tint = if (showPro) LumenColors.Amber else Color.White)
            }
        }
    }
}

@Composable
private fun LatestThumb(photoId: String) {
    val context = LocalContext.current
    var path by remember(photoId) { mutableStateOf<String?>(null) }
    LaunchedEffect(photoId) {
        val p = context.container.photos.get(photoId)
        path = p?.thumbnailPath?.takeIf { File(it).exists() } ?: p?.originalPath
    }
    path?.let { AsyncImage(model = File(it), contentDescription = "Latest photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
}

@Composable
private fun ShutterButton(ready: Boolean, nearly: Boolean, busy: Boolean, onClick: () -> Unit) {
    val ring by animateColorAsState(
        when {
            ready -> LumenColors.Ready
            nearly -> LumenColors.Nearly
            else -> Color.White
        },
        label = "ring",
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val inner by animateFloatAsState(if (pressed || busy) 0.82f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "shutter")
    val glow by animateFloatAsState(if (ready) 1f else 0f, label = "glow")
    Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
        // Soft glow when the guided composition is ready.
        Box(Modifier.size(84.dp).scale(0.9f + 0.1f * glow).clip(CircleShape).background(LumenColors.Ready.copy(alpha = 0.25f * glow)))
        Box(
            Modifier
                .size(76.dp)
                .clip(CircleShape)
                .border(4.dp, ring, CircleShape)
                .clickable(interactionSource = interaction, indication = null, enabled = !busy, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(60.dp)
                    .scale(inner)
                    .clip(CircleShape)
                    .background(if (busy) Color.White.copy(alpha = 0.5f) else if (ready) LumenColors.Ready else Color.White),
            )
        }
    }
}

/** Tap: ask the on-device AI for an idea about this scene (or set it up if not installed). */
@Composable
private fun AiChip(status: AiStatus, thinking: Boolean, onClick: () -> Unit) {
    val color = when {
        thinking -> LumenColors.Amber
        status == AiStatus.Ready || status == AiStatus.Idle -> LumenColors.Ai
        status is AiStatus.LowMemory || status == AiStatus.ThermalPaused -> LumenColors.Issue
        else -> Color.White.copy(alpha = 0.55f)
    }
    GlassIconButton(
        LumenIcons.Ai, "Ask on-device AI (${status.label})", onClick,
        tint = color, enabled = !thinking, active = thinking,
    )
}
