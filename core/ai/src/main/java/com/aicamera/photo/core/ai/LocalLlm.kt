package com.aicamera.photo.core.ai

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

enum class LlmBackend { GPU, CPU }

/**
 * How the model is loaded. Leaving out the vision encoder and using a smaller context window cuts
 * resident memory; the full multimodal profile is only needed when an image is sent.
 */
data class LlmProfile(val vision: Boolean, val maxTokens: Int, val cpuThreads: Int? = null) {
    companion object {
        /** Live coaching beside the camera: structured scene facts only, small context. */
        val COACH = LlmProfile(vision = false, maxTokens = 1024)
        /** Post-capture critique and benchmarks: image input. */
        val MULTIMODAL = LlmProfile(vision = true, maxTokens = 1536)
    }
}

/** Abstraction over an on-device LLM runtime so the app is not locked to one dependency. */
interface LocalLlm {
    val isLoaded: Boolean
    val activeBackend: LlmBackend?
    val activeProfile: LlmProfile?

    /** Loads the model (reloading if the profile differs). Returns the cold-start time in ms, 0 if already loaded. */
    suspend fun load(modelPath: String, preferred: LlmBackend, cacheDir: String, profile: LlmProfile = LlmProfile.MULTIMODAL): Long

    suspend fun generate(system: String, prompt: String, imageJpeg: ByteArray?, maxOutputTokens: Int = 320): String

    fun cancel()

    suspend fun unload()
}

/**
 * LiteRT-LM implementation. All calls run on one dedicated thread (the runtime is not re-entrant), so
 * inference never touches the main thread or the camera analysis thread.
 */
class LiteRtLmEngine : LocalLlm {
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "lumen-llm").apply { priority = Thread.NORM_PRIORITY - 1 } }
        .asCoroutineDispatcher()
    private var engine: Engine? = null
    @Volatile private var currentConversation: com.google.ai.edge.litertlm.Conversation? = null

    override val isLoaded: Boolean get() = engine?.isInitialized() == true
    override var activeBackend: LlmBackend? = null
        private set
    override var activeProfile: LlmProfile? = null
        private set
    private var loadedPreference: LlmBackend? = null

    override suspend fun load(modelPath: String, preferred: LlmBackend, cacheDir: String, profile: LlmProfile): Long = withContext(dispatcher) {
        if (isLoaded && activeProfile == profile && loadedPreference == preferred) return@withContext 0L
        if (isLoaded) unloadNow()
        // Inference must never outrank the camera or the UI thread.
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        val start = System.currentTimeMillis()
        val order = if (preferred == LlmBackend.GPU) listOf(LlmBackend.GPU, LlmBackend.CPU) else listOf(LlmBackend.CPU)
        var lastError: Throwable? = null
        for (b in order) {
            try {
                val backend = if (b == LlmBackend.GPU) Backend.GPU() else Backend.CPU(profile.cpuThreads, null)
                val e = Engine(
                    EngineConfig(
                        modelPath = modelPath,
                        backend = backend,
                        visionBackend = if (profile.vision) backend else null,
                        audioBackend = null,
                        maxNumTokens = profile.maxTokens,
                        maxNumImages = if (profile.vision) 1 else null,
                        cacheDir = cacheDir,
                    )
                )
                e.initialize()
                engine = e
                activeBackend = b
                activeProfile = profile
                loadedPreference = preferred
                return@withContext System.currentTimeMillis() - start
            } catch (t: Throwable) {
                Log.w(TAG, "Backend $b failed to initialize", t)
                lastError = t
            }
        }
        throw IllegalStateException("Model could not be loaded: ${lastError?.message}", lastError)
    }

    override suspend fun generate(system: String, prompt: String, imageJpeg: ByteArray?, maxOutputTokens: Int): String = withContext(dispatcher) {
        val e = engine ?: error("Model not loaded")
        val conversation = e.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = SamplerConfig(40, 0.9, 0.3, 7),
                maxOutputToken = maxOutputTokens,
            )
        )
        currentConversation = conversation
        try {
            val parts = buildList<Content> {
                if (imageJpeg != null && activeProfile?.vision == true) add(Content.ImageBytes(imageJpeg))
                add(Content.Text(prompt))
            }
            val reply = conversation.sendMessage(Contents.of(parts))
            reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
        } finally {
            currentConversation = null
            conversation.close()
        }
    }

    override fun cancel() {
        runCatching { currentConversation?.cancelProcess() }
    }

    override suspend fun unload() = withContext(dispatcher) { unloadNow() }

    private fun unloadNow() {
        runCatching { engine?.close() }
        engine = null
        activeBackend = null
        activeProfile = null
        loadedPreference = null
    }

    companion object {
        private const val TAG = "LumenLLM"
    }
}
