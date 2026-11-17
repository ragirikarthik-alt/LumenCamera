package com.aicamera.photo.core.ai

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.aicamera.photo.composition.SceneSignature
import com.aicamera.photo.domain.CoachingRecommendation
import com.aicamera.photo.domain.CompositionCandidate
import com.aicamera.photo.domain.PhotoFinding
import com.aicamera.photo.domain.SceneSnapshot
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

sealed interface AiStatus {
    val label: String
    data object Disabled : AiStatus { override val label = "Off" }
    data object NotInstalled : AiStatus { override val label = "Model not installed" }
    data object Idle : AiStatus { override val label = "Installed (not loaded)" }
    data object Loading : AiStatus { override val label = "Loading model" }
    data object Ready : AiStatus { override val label = "Ready" }
    data object Thinking : AiStatus { override val label = "Thinking" }
    data object ThermalPaused : AiStatus { override val label = "Paused (device warm)" }
    data class LowMemory(val availableMb: Int, val neededMb: Int) : AiStatus {
        override val label = "Not enough free memory"
    }
    data class Failed(val reason: String) : AiStatus { override val label = "Unavailable" }
}

/** Reads system-wide available memory; GPU allocations of the model don't show in process RSS. */
class MemoryGuard(context: Context) {
    private val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    fun availableMb(): Int {
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return (mi.availMem / 1_048_576).toInt()
    }

    fun isLow(): Boolean {
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.lowMemory
    }

    companion object {
        /**
         * Free memory required before loading. Measured on a Galaxy S23 (8 GB), GPU backend:
         * text-only profile ~500 MB of system memory, multimodal ~1,950 MB. The margin keeps the
         * camera and other apps alive.
         */
        fun requiredMb(profile: LlmProfile) = if (profile.vision) 2_600 else 1_200

        /** Below this while loaded, the model is released immediately. */
        const val RELEASE_BELOW_MB = 650
    }
}

/**
 * Loop B - selective reasoning. Camera priority comes first: the model is loaded only on demand, only
 * when there is enough free memory, in its lighter text-only profile next to the camera, and it is
 * released when idle, when memory runs low, or when the device gets hot.
 */
class AiCoordinator(
    private val llm: LocalLlm,
    private val models: ModelManager,
    private val memory: MemoryGuard,
    private val cacheDir: String,
    private val onTiming: (state: String, latencyMs: Long?, coldMs: Long?) -> Unit = { _, _, _ -> },
) {
    private val _status = MutableStateFlow<AiStatus>(AiStatus.NotInstalled)
    val status: StateFlow<AiStatus> = _status

    private val mutex = Mutex()
    private var lastSignature: SceneSignature? = null
    private var lastRunAt = 0L
    @Volatile private var lastUsedAt = 0L
    private var consecutiveFailures = 0
    var enabled = true
    var preferredBackend = LlmBackend.GPU
    var selectedModelId: String? = ModelCatalog.GEMMA_4_E2B.id

    /** Minimum time between automatic scene interpretations (live AI coaching). */
    var minIntervalMs = 15_000L

    /** Unload after this long without use, to give memory back to the system. */
    var idleUnloadMs = 90_000L

    val isLoaded: Boolean get() = llm.isLoaded
    val isBusy: Boolean get() = mutex.isLocked

    fun refreshStatus(thermal: Int = PowerManager.THERMAL_STATUS_NONE) {
        _status.value = when {
            !enabled -> AiStatus.Disabled
            models.installed(selectedModelId) == null -> AiStatus.NotInstalled
            thermal >= PowerManager.THERMAL_STATUS_SEVERE -> AiStatus.ThermalPaused
            llm.isLoaded -> if (_status.value == AiStatus.Thinking) AiStatus.Thinking else AiStatus.Ready
            _status.value is AiStatus.Failed || _status.value is AiStatus.LowMemory -> _status.value
            else -> AiStatus.Idle
        }
        onTiming(_status.value.label, null, null)
    }

    /** Whether an automatic (not user-requested) interpretation is worth running now. */
    fun shouldInterpret(scene: SceneSnapshot, now: Long, thermal: Int): Boolean {
        if (!enabled || models.installed(selectedModelId) == null) return false
        if (thermal >= PowerManager.THERMAL_STATUS_MODERATE) return false
        if (mutex.isLocked) return false
        if (consecutiveFailures >= 3) return false
        if (now - lastRunAt < minIntervalMs) return false
        // Never load automatically under memory pressure; only reuse an already-loaded model.
        if (!llm.isLoaded && memory.availableMb() < MemoryGuard.requiredMb(LlmProfile.COACH)) return false
        val sig = SceneSignature.of(scene)
        if (!sig.materiallyDifferentFrom(lastSignature, threshold = 0.75f) && now - lastRunAt < minIntervalMs * 4) return false
        if (scene.visualSignals.motion > 0.3f) return false
        return true
    }

    suspend fun ensureLoaded(profile: LlmProfile = LlmProfile.COACH): Boolean {
        val rec = models.installed(selectedModelId) ?: run {
            _status.value = AiStatus.NotInstalled
            return false
        }
        if (llm.isLoaded && llm.activeProfile == profile) return true
        if (llm.isLoaded) llm.unload() // switching profile: free memory before measuring
        val available = memory.availableMb()
        val needed = MemoryGuard.requiredMb(profile)
        if (available < needed) {
            Log.i(TAG, "Not loading model: ${available}MB free, ${needed}MB needed")
            _status.value = AiStatus.LowMemory(available, needed)
            onTiming("Low memory (${available} MB free)", null, null)
            return false
        }
        _status.value = AiStatus.Loading
        onTiming("Loading model", null, null)
        return try {
            val cold = llm.load(rec.path, preferredBackend, cacheDir, profile)
            lastUsedAt = System.currentTimeMillis()
            _status.value = AiStatus.Ready
            onTiming("Ready (${llm.activeBackend}, ${if (profile.vision) "vision" else "text"})", null, cold)
            consecutiveFailures = 0
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Model load failed", t)
            _status.value = AiStatus.Failed(t.message ?: "Load failed")
            onTiming("Load failed", null, null)
            consecutiveFailures++
            false
        }
    }

    /**
     * Scene interpretation for coaching, using structured vision data only (no image), which keeps the
     * vision encoder out of memory while the camera runs. [userRequested] bypasses the pacing rules.
     */
    suspend fun interpret(
        scene: SceneSnapshot,
        candidates: List<CompositionCandidate>,
        shown: List<CoachingRecommendation>,
        now: Long,
        userRequested: Boolean = false,
    ): List<CoachingRecommendation> {
        if (!mutex.tryLock()) return emptyList()
        try {
            lastRunAt = now
            lastSignature = SceneSignature.of(scene)
            if (userRequested) consecutiveFailures = 0
            if (!ensureLoaded(LlmProfile.COACH)) return emptyList()
            _status.value = AiStatus.Thinking
            val start = System.currentTimeMillis()
            val reply = withTimeout(15_000) {
                llm.generate(Prompts.COACH_SYSTEM, Prompts.coachPrompt(scene, candidates, shown), null, 140)
            }
            val latency = System.currentTimeMillis() - start
            lastUsedAt = System.currentTimeMillis()
            onTiming("Ready", latency, null)
            consecutiveFailures = 0
            return ResponseValidator.parseCoach(reply, System.currentTimeMillis(), alreadyShown = shown.map { it.type }.toSet())
        } catch (e: TimeoutCancellationException) {
            llm.cancel()
            consecutiveFailures++
            onTiming("Timed out", null, null)
            return emptyList()
        } catch (t: Throwable) {
            Log.w(TAG, "Interpretation failed", t)
            consecutiveFailures++
            return emptyList()
        } finally {
            if (_status.value == AiStatus.Thinking) _status.value = AiStatus.Ready
            mutex.unlock()
        }
    }

    /** Artistic critique of a finished photo (camera not running), with image input. */
    suspend fun critique(scene: SceneSnapshot, technical: List<PhotoFinding>, strategyName: String?, jpeg: ByteArray?): Pair<String?, List<PhotoFinding>> =
        mutex.withLock {
            if (!enabled) return null to emptyList()
            // Image input needs ~2 GB; fall back to the text-only profile (measured facts only) when memory is tight.
            val canSee = jpeg != null && (llm.activeProfile?.vision == true || memory.availableMb() >= MemoryGuard.requiredMb(LlmProfile.MULTIMODAL))
            if (!ensureLoaded(if (canSee) LlmProfile.MULTIMODAL else LlmProfile.COACH)) return null to emptyList()
            _status.value = AiStatus.Thinking
            try {
                val reply = withTimeout(40_000) {
                    llm.generate(Prompts.CRITIQUE_SYSTEM, Prompts.critiquePrompt(scene, technical, strategyName), if (canSee) jpeg else null, 300)
                }
                lastUsedAt = System.currentTimeMillis()
                ResponseValidator.parseCritique(reply)
            } catch (t: Throwable) {
                Log.w(TAG, "Critique failed", t)
                llm.cancel()
                null to emptyList()
            } finally {
                _status.value = AiStatus.Ready
            }
        }

    /**
     * Periodic housekeeping: release the model when idle or when system memory gets tight.
     * Returns true if the model was released.
     */
    suspend fun maintain(now: Long = System.currentTimeMillis()): Boolean {
        if (!llm.isLoaded || mutex.isLocked) return false
        val idle = now - lastUsedAt > idleUnloadMs
        val pressure = memory.availableMb() < MemoryGuard.RELEASE_BELOW_MB || memory.isLow()
        if (idle || pressure) {
            Log.i(TAG, "Releasing model (idle=$idle, pressure=$pressure)")
            release()
            return true
        }
        return false
    }

    /** Releases model memory (thermal pressure, low memory, backgrounding, idle, or user request). */
    suspend fun release() {
        llm.cancel()
        mutex.withLock { llm.unload() }
        refreshStatus()
    }

    fun resetFailures() {
        consecutiveFailures = 0
        if (_status.value is AiStatus.Failed || _status.value is AiStatus.LowMemory) _status.value = AiStatus.Idle
    }

    companion object {
        private const val TAG = "LumenAI"
    }
}
