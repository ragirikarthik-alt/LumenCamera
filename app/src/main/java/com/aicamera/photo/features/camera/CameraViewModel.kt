package com.aicamera.photo.features.camera

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicamera.photo.AppContainer
import com.aicamera.photo.composition.CoachingSession
import com.aicamera.photo.composition.DefaultCompositionEngine
import com.aicamera.photo.composition.OpportunityDetector
import com.aicamera.photo.composition.SceneSignature
import com.aicamera.photo.core.ai.AiStatus
import com.aicamera.photo.core.camera.AdaptiveRateController
import com.aicamera.photo.core.camera.CameraController
import com.aicamera.photo.core.camera.CameraControlRequest
import com.aicamera.photo.core.camera.DeviceOrientationMonitor
import com.aicamera.photo.core.camera.ThermalMonitor
import com.aicamera.photo.core.data.AppSettings
import com.aicamera.photo.core.data.CameraMode
import com.aicamera.photo.core.data.CaptureRecord
import com.aicamera.photo.core.vision.DefaultSceneAnalyzer
import com.aicamera.photo.core.vision.FrameContext
import com.aicamera.photo.core.vision.LiveFrameAnalyzer
import com.aicamera.photo.core.vision.MlKitDetectors
import com.aicamera.photo.domain.AlignmentResult
import com.aicamera.photo.domain.AlignmentState
import com.aicamera.photo.domain.Clock
import com.aicamera.photo.domain.CoachingPhase
import com.aicamera.photo.domain.CoachingRecommendation
import com.aicamera.photo.domain.CompositionCandidate
import com.aicamera.photo.domain.CompositionStrategy
import com.aicamera.photo.domain.SceneSnapshot
import com.aicamera.photo.services.BackgroundJobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

enum class GuidedPhase { ANALYZING, CHOOSING, ALIGNING, FREE }

data class CameraUiState(
    val mode: CameraMode = CameraMode.CONTINUOUS,
    val scene: SceneSnapshot? = null,
    val frameRotationCw: Int = 0,
    val guidedPhase: GuidedPhase = GuidedPhase.ANALYZING,
    val candidates: List<CompositionCandidate> = emptyList(),
    val selected: CompositionCandidate? = null,
    val alignment: AlignmentResult? = null,
    val suggestion: CoachingRecommendation? = null,
    val suggestionAcknowledged: Boolean = false,
    val practiceLessonId: String? = null,
    val lastPhotoId: String? = null,
    val lastCapturedAt: Long = 0,
    val toast: String? = null,
    val focusPoint: Pair<Float, Float>? = null,
    val showExposure: Boolean = false,
    val aiThinking: Boolean = false,
)

class CameraViewModel(private val c: AppContainer) : ViewModel() {
    val camera = CameraController(c.appContext, c.diagnostics)
    private val orientation = DeviceOrientationMonitor(c.appContext)
    private val rate = AdaptiveRateController()
    private val engine = DefaultCompositionEngine()
    private val opportunities = OpportunityDetector()
    private val clock = Clock.SYSTEM
    private val analysisExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "lumen-analysis") }

    /** All composition/coaching state is mutated on this single logical thread (the session is not thread-safe). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val logic = Dispatchers.Default.limitedParallelism(1)
    private var detectors: MlKitDetectors? = null
    private var sessionId: String? = null
    private var aiRecs: List<CoachingRecommendation> = emptyList()
    private var aiJob: Job? = null
    private var analyzingSince = 0L
    private var lastFrameContext: FrameContext? = null

    private val _ui = MutableStateFlow(CameraUiState())
    val ui: StateFlow<CameraUiState> = _ui.asStateFlow()

    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val diagnostics = c.diagnostics.state
    val aiStatus: StateFlow<AiStatus> = c.ai.status
    val latestPhotoId = c.photos.observeLatestId().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val session = CoachingSession(clock) { event ->
        val sid = sessionId ?: return@CoachingSession
        viewModelScope.launch(Dispatchers.IO) { c.coaching.record(sid, event) }
        if (event.phase == CoachingPhase.ACHIEVED) _ui.update { it.copy(toast = "Nice - ${event.recommendation.title.lowercase()} done") }
    }

    /** Latest snapshot from the analysis thread (conflated: only the newest matters). */
    private val snapshots = MutableSharedFlow<Pair<SceneSnapshot, FrameContext>>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val analyzer: LiveFrameAnalyzer

    init {
        detectors = MlKitDetectors(streamMode = true)
        analyzer = LiveFrameAnalyzer(
            analyzer = DefaultSceneAnalyzer(detectors),
            orientation = orientation.state,
            rate = rate,
            diagnostics = c.diagnostics,
        ) { snapshot, ctx -> snapshots.tryEmit(snapshot to ctx) }

        viewModelScope.launch {
            val s = c.settings.current()
            _ui.update { it.copy(mode = s.defaultMode) }
            session.setIntensity(s.coachingIntensity)
            camera.setRawRequested(s.captureRaw)
            sessionId = c.photos.startSession(s.defaultMode, null)
        }
        viewModelScope.launch(logic) { settings.collect { session.setIntensity(it.coachingIntensity) } }
        viewModelScope.launch { c.thermal.status.collect { rate.thermalStatus = it } }
        viewModelScope.launch {
            orientation.state.collect { camera.setTargetRotation(it.surfaceRotation) }
        }
        viewModelScope.launch(logic) {
            snapshots.collect { (scene, ctx) -> onScene(scene, ctx) }
        }
    }

    fun setPractice(lessonId: String?, strategy: String?) {
        if (lessonId == _ui.value.practiceLessonId) return
        _ui.update { it.copy(practiceLessonId = lessonId) }
        if (strategy != null) {
            preferredStrategy = runCatching { CompositionStrategy.valueOf(strategy) }.getOrNull()
            setMode(CameraMode.GUIDED)
        }
    }

    private var preferredStrategy: CompositionStrategy? = null

    suspend fun start(owner: LifecycleOwner, surface: Preview.SurfaceProvider) {
        try {
            camera.initialize()
            orientation.start()
            camera.bind(owner, surface, analysisExecutor, analyzer)
        } catch (t: Throwable) {
            Log.e(TAG, "Camera start failed", t)
            _ui.update { it.copy(toast = "Camera could not start: ${t.message}") }
        }
    }

    fun stop() {
        orientation.stop()
        // Leaving the camera expires the active suggestion so it isn't shown stale on return.
        viewModelScope.launch(logic) {
            session.setPaused(true)
            session.setPaused(_ui.value.mode != CameraMode.CONTINUOUS)
        }
    }

    fun setMeteringFactory(f: MeteringPointFactory) = camera.setMeteringPointFactory(f)

    // ---------------------------------------------------------------- analysis tick

    private suspend fun onScene(scene: SceneSnapshot, ctx: FrameContext) {
        lastFrameContext = ctx
        val start = SystemClock.elapsedRealtime()
        val state = _ui.value
        when (state.mode) {
            CameraMode.GUIDED -> guidedTick(scene, state)
            CameraMode.CONTINUOUS -> continuousTick(scene)
        }
        c.diagnostics.compositionTime(SystemClock.elapsedRealtime() - start)
        _ui.update { it.copy(scene = scene, frameRotationCw = ctx.worldRotationCw) }
    }

    private fun guidedTick(scene: SceneSnapshot, state: CameraUiState) {
        when (state.guidedPhase) {
            GuidedPhase.ANALYZING -> {
                val now = clock.now()
                if (analyzingSince == 0L) analyzingSince = now
                // Let detection settle for a moment before proposing compositions.
                if (now - analyzingSince < 900) return
                val candidates = engine.generate(scene)
                if (candidates.isNotEmpty()) {
                    val preferred = preferredStrategy?.let { p -> candidates.firstOrNull { it.strategy == p } }
                    if (preferred != null) {
                        selectCandidateInternal(preferred, candidates)
                    } else {
                        _ui.update { it.copy(candidates = candidates, guidedPhase = GuidedPhase.CHOOSING) }
                    }
                }
            }
            GuidedPhase.ALIGNING -> {
                // The target stays fixed in the frame once chosen (it was jumping when regenerated every
                // frame). Alignment is recalculated from the latest scene by following the locked subject.
                val selected = state.selected ?: return
                val alignment = engine.calculateAlignment(scene, selected)
                _ui.update { it.copy(alignment = alignment) }
            }
            else -> Unit
        }
    }

    private fun continuousTick(scene: SceneSnapshot) {
        val now = clock.now()
        aiRecs = aiRecs.filter { it.expiresAt > now }
        val rules = opportunities.detect(scene, now)
        val shown = session.offer(rules + aiRecs, SceneSignature.of(scene))
        val active = session.active
        _ui.update {
            it.copy(
                suggestion = shown,
                suggestionAcknowledged = active?.phase == CoachingPhase.USER_RESPONDING,
            )
        }
        maybeRunAi(scene, now)
    }

    /** Live AI coaching: automatic, opt-in, paced, and never allowed to load the model under memory pressure. */
    private fun maybeRunAi(scene: SceneSnapshot, now: Long) {
        if (!settings.value.liveAi) return
        if (aiJob?.isActive == true) return
        if (!c.ai.shouldInterpret(scene, now, c.thermal.status.value)) return
        aiJob = viewModelScope.launch(Dispatchers.Default) {
            rate.busy = true
            try {
                val candidates = engine.generate(scene)
                val shown = listOfNotNull(session.active?.recommendation)
                val recs = c.ai.interpret(scene, candidates, shown, clock.now())
                if (recs.isNotEmpty()) aiRecs = recs
            } finally {
                rate.busy = false
            }
        }
    }

    /** "Ask AI": one on-demand idea for the current scene. Vision analysis slows down while the model thinks. */
    fun askAi() {
        if (aiJob?.isActive == true) return
        val scene = _ui.value.scene ?: run {
            _ui.update { it.copy(toast = "Point the camera at a scene first") }
            return
        }
        aiJob = viewModelScope.launch(Dispatchers.Default) {
            _ui.update { it.copy(aiThinking = true) }
            rate.busy = true
            try {
                val candidates = engine.generate(scene)
                val shown = listOfNotNull(session.active?.recommendation)
                val recs = c.ai.interpret(scene, candidates, shown, clock.now(), userRequested = true)
                val first = recs.firstOrNull()
                if (first != null) {
                    aiRecs = recs
                    withContext(logic) {
                        if (_ui.value.mode != CameraMode.CONTINUOUS) setModeNow(CameraMode.CONTINUOUS)
                        val shownRec = session.showNow(first)
                        _ui.update { it.copy(suggestion = shownRec, suggestionAcknowledged = false) }
                    }
                } else {
                    val status = c.ai.status.value
                    val msg = when (status) {
                        is AiStatus.LowMemory -> "Not enough free memory for AI (${status.availableMb} MB free). Close other apps and try again."
                        AiStatus.NotInstalled -> "Install the AI model in Settings first"
                        AiStatus.ThermalPaused -> "AI paused while the phone cools down"
                        else -> "The AI had no new idea for this scene"
                    }
                    _ui.update { it.copy(toast = msg) }
                }
            } finally {
                rate.busy = false
                _ui.update { it.copy(aiThinking = false) }
            }
        }
    }

    // ---------------------------------------------------------------- user actions

    fun setMode(mode: CameraMode) = viewModelScope.launch(logic) { setModeNow(mode) }

    private fun setModeNow(mode: CameraMode) {
        if (_ui.value.mode == mode) return
        engine.resetAlignment()
        analyzingSince = 0L
        session.setPaused(mode != CameraMode.CONTINUOUS)
        _ui.update {
            it.copy(
                mode = mode,
                guidedPhase = GuidedPhase.ANALYZING,
                candidates = emptyList(),
                selected = null,
                alignment = null,
                suggestion = null,
            )
        }
    }

    fun selectCandidate(candidate: CompositionCandidate) = viewModelScope.launch(logic) { selectCandidateInternal(candidate) }

    private fun selectCandidateInternal(candidate: CompositionCandidate, all: List<CompositionCandidate> = _ui.value.candidates) {
        engine.resetAlignment()
        _ui.update { it.copy(selected = candidate, candidates = all, guidedPhase = GuidedPhase.ALIGNING, alignment = null) }
    }

    fun reanalyze() = viewModelScope.launch(logic) {
        engine.resetAlignment()
        analyzingSince = 0L
        preferredStrategy = null
        _ui.update { it.copy(guidedPhase = GuidedPhase.ANALYZING, candidates = emptyList(), selected = null, alignment = null) }
    }

    fun shootFreely() = viewModelScope.launch(logic) { _ui.update { it.copy(guidedPhase = GuidedPhase.FREE, selected = null, alignment = null) } }

    fun acknowledgeSuggestion() = viewModelScope.launch(logic) {
        session.acknowledge()
        _ui.update { it.copy(suggestionAcknowledged = true) }
    }

    fun dismissSuggestion() = viewModelScope.launch(logic) {
        session.dismiss()
        _ui.update { it.copy(suggestion = null) }
    }

    fun anotherIdea() = viewModelScope.launch(logic) {
        val next = session.anotherIdea()
        _ui.update { it.copy(suggestion = next, suggestionAcknowledged = false, toast = if (next == null) "No other ideas right now" else null) }
    }

    fun tapToFocus(x: Float, y: Float) {
        camera.apply(CameraControlRequest.FocusAt(x, y))
        _ui.update { it.copy(focusPoint = x to y, showExposure = true) }
    }

    fun hideExposure() = _ui.update { it.copy(showExposure = false, focusPoint = null) }

    fun apply(request: CameraControlRequest) = camera.apply(request)

    fun setRaw(enabled: Boolean) {
        camera.setRawRequested(enabled)
        viewModelScope.launch { c.settings.setCaptureRaw(enabled) }
    }

    fun consumeToast() {
        _ui.update { it.copy(toast = null) }
        camera.clearMessage()
    }

    /** Shutter: only ever triggered by the user. */
    fun capture() {
        if (camera.state.value.capturing) return
        viewModelScope.launch {
            val state = _ui.value
            val photoId = c.storage.newPhotoId()
            val jpeg = c.storage.originalFileFor(photoId)
            val raw = if (camera.state.value.rawActive) c.storage.rawFileFor(photoId) else null
            val sceneAtCapture = state.scene
            val tilt = lastFrameContext?.deviceTiltDegrees
            val outcome = runCatching { camera.capture(jpeg, raw) }.getOrElse {
                Log.e(TAG, "Capture failed", it)
                _ui.update { s -> s.copy(toast = "Capture failed: ${it.message}") }
                return@launch
            }
            val sid = sessionId ?: c.photos.startSession(state.mode, null).also { sessionId = it }
            runCatching {
                c.photos.saveCapture(
                    CaptureRecord(
                        photoId = photoId,
                        sessionId = sid,
                        originalFile = outcome.jpeg,
                        rawFile = outcome.raw,
                        capturedAt = System.currentTimeMillis(),
                        mode = state.mode,
                        metadata = outcome.metadata,
                        previewScene = sceneAtCapture,
                        composition = state.selected.takeIf { state.mode == CameraMode.GUIDED },
                        alignment = state.alignment.takeIf { state.mode == CameraMode.GUIDED },
                        lessonId = state.practiceLessonId,
                    )
                )
                state.practiceLessonId?.let { c.lessons.addPracticePhoto(it, photoId) }
            }.onFailure { Log.e(TAG, "Saving capture record failed", it) }
            BackgroundJobs.afterCapture(c.appContext, photoId, tilt, settings.value.autoCritique)
            _ui.update {
                it.copy(
                    lastPhotoId = photoId,
                    lastCapturedAt = System.currentTimeMillis(),
                    toast = if (outcome.raw != null) "Saved JPEG + RAW" else null,
                )
            }
        }
    }

    override fun onCleared() {
        orientation.stop()
        camera.shutdown()
        analysisExecutor.execute { detectors?.close() }
        analysisExecutor.shutdown()
        sessionId?.let { id -> c.appScope.launch { c.photos.endSession(id) } }
        super.onCleared()
    }

    val isAlignedReady: Boolean get() = _ui.value.alignment?.state == AlignmentState.READY

    companion object {
        private const val TAG = "LumenCameraVM"
        fun thermalLabel(status: Int) = ThermalMonitor.label(status)
    }
}

typealias FrameAnalyzer = ImageAnalysis.Analyzer
