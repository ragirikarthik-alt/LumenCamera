package com.aicamera.photo.core.ai

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import com.aicamera.photo.core.data.db.BenchmarkDao
import com.aicamera.photo.core.data.db.ModelBenchmarkEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * On-device benchmark (spec 9.4): verifies the model format and runtime, multimodal image input,
 * memory, cold-start time, sustained latency and thermal impact. Results are stored locally.
 */
class ModelBenchmark(
    private val context: Context,
    private val llm: LocalLlm,
    private val models: ModelManager,
    private val memory: MemoryGuard,
    private val dao: BenchmarkDao,
) {
    data class Progress(val step: String, val fraction: Float)

    suspend fun run(spec: ModelSpec, backend: LlmBackend, onProgress: (Progress) -> Unit): ModelBenchmarkEntity {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermalBefore = power.currentThermalStatus
        val record = models.installed(spec.id)
        var peakMb = 0
        var cold: Long? = null
        var first: Long? = null
        var warm: Long? = null
        var imageOk = false
        var error: String? = null
        try {
            requireNotNull(record) { "Model not installed" }
            if (llm.isLoaded) llm.unload()
            System.gc()
            delay(1_000)
            // The model's GPU buffers don't appear in process memory: measure the drop in system-wide free memory.
            val availBefore = memory.availableMb()
            coroutineScope {
                val sampler = launch(Dispatchers.Default) {
                    while (isActive) {
                        peakMb = maxOf(peakMb, availBefore - memory.availableMb())
                        delay(250)
                    }
                }
                try {
                    onProgress(Progress("Loading model (cold start)", 0.1f))
                    cold = llm.load(record.path, backend, context.cacheDir.absolutePath, LlmProfile.MULTIMODAL)
                    val image = testImage()
                    onProgress(Progress("First multimodal response", 0.4f))
                    val t1 = System.currentTimeMillis()
                    val r1 = llm.generate(Prompts.COACH_SYSTEM, BENCH_PROMPT, image, 200)
                    first = System.currentTimeMillis() - t1
                    imageOk = r1.isNotBlank()
                    onProgress(Progress("Warm responses", 0.7f))
                    val times = (0 until 3).map {
                        val t = System.currentTimeMillis()
                        llm.generate(Prompts.COACH_SYSTEM, BENCH_PROMPT, image, 200)
                        System.currentTimeMillis() - t
                    }
                    warm = times.average().toLong()
                } finally {
                    sampler.cancel()
                }
            }
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
        }
        onProgress(Progress("Saving results", 0.95f))
        val entity = ModelBenchmarkEntity(
            modelId = spec.id,
            backend = llm.activeBackend?.name ?: backend.name,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            success = error == null,
            error = error,
            coldStartMs = cold,
            firstResponseMs = first,
            warmResponseMs = warm,
            imageInputWorked = imageOk,
            peakMemoryMb = peakMb.takeIf { it > 0 },
            thermalBefore = thermalBefore,
            thermalAfter = power.currentThermalStatus,
            createdAt = System.currentTimeMillis(),
        )
        withContext(Dispatchers.IO) { dao.insert(entity) }
        onProgress(Progress("Done", 1f))
        return entity
    }


    private fun testImage(): ByteArray {
        // A synthetic "landscape": sky gradient, tilted horizon and a dark subject on the centre line.
        val bmp = Bitmap.createBitmap(448, 336, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val sky = Paint().apply { shader = LinearGradient(0f, 0f, 0f, 180f, Color.rgb(120, 170, 230), Color.rgb(250, 210, 160), Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, 448f, 200f, sky)
        val ground = Paint().apply { color = Color.rgb(70, 90, 50) }
        c.save(); c.rotate(-4f, 224f, 200f); c.drawRect(-50f, 190f, 500f, 400f, ground); c.restore()
        val subject = Paint().apply { color = Color.rgb(30, 30, 35) }
        c.drawRect(210f, 120f, 240f, 230f, subject)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        return out.toByteArray()
    }

    companion object {
        private val BENCH_PROMPT = """
            Measurements:
            - scene: Landscape (confidence 70%)
            - light: Warm, soft light
            - subject at the centre, 2% of frame
            - camera tilt 4.0 degrees, horizon line visible at 60% height
            Return the JSON now.
        """.trimIndent()

        fun thermalLabel(status: Int) = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "None"
            PowerManager.THERMAL_STATUS_LIGHT -> "Light"
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
            else -> "Critical+"
        }
    }
}
