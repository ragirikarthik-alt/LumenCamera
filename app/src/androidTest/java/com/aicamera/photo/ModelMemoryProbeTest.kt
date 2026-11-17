package com.aicamera.photo

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aicamera.photo.core.ai.LiteRtLmEngine
import com.aicamera.photo.core.ai.LlmBackend
import com.aicamera.photo.core.ai.LlmProfile
import com.aicamera.photo.core.ai.ModelCatalog
import com.aicamera.photo.core.ai.Prompts
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Device probe (not a pass/fail test): measures resident memory and latency of each model profile.
 * Run on the target phone with the model installed:
 *   ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.aicamera.photo.ModelMemoryProbeTest
 * Results are logged under the "LumenProbe" tag.
 */
@RunWith(AndroidJUnit4::class)
class ModelMemoryProbeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun rssMb(): Int = File("/proc/self/status").readLines().first { it.startsWith("VmRSS") }.filter { it.isDigit() }.toInt() / 1024
    private fun availMb(): Int = File("/proc/meminfo").readLines().first { it.startsWith("MemAvailable") }.filter { it.isDigit() }.toInt() / 1024

    private val prompt = """
        Measurements:
        - scene: Landscape (confidence 70%)
        - light: Warm, soft light
        - subject at the centre, 2% of frame
        - camera tilt 4.0 degrees, horizon line visible at 60% height
        Return the JSON now.
    """.trimIndent()

    @Test
    fun probeProfiles() = runBlocking {
        val model = File(context.getExternalFilesDir("models"), ModelCatalog.GEMMA_4_E2B.fileName)
        assumeTrue("Model not installed", model.exists())
        val cases = listOf(
            "GPU text 1024" to (LlmBackend.GPU to LlmProfile(false, 1024)),
            "GPU vision 1536" to (LlmBackend.GPU to LlmProfile(true, 1536)),
            "CPU text 1024 t4" to (LlmBackend.CPU to LlmProfile(false, 1024, 4)),
        )
        for ((name, cfg) in cases) {
            val llm = LiteRtLmEngine()
            System.gc(); Thread.sleep(1500)
            val baseRss = rssMb()
            val baseAvail = availMb()
            val minAvail = java.util.concurrent.atomic.AtomicInteger(baseAvail)
            val sampling = java.util.concurrent.atomic.AtomicBoolean(true)
            val sampler = Thread { while (sampling.get()) { minAvail.accumulateAndGet(availMb()) { a, b -> minOf(a, b) }; Thread.sleep(200) } }.apply { start() }
            val cold = runCatching { llm.load(model.absolutePath, cfg.first, context.cacheDir.absolutePath, cfg.second) }
            if (cold.isFailure) {
                sampling.set(false)
                Log.i("LumenProbe", "$name: load failed ${cold.exceptionOrNull()?.message}")
                continue
            }
            val loadedRss = rssMb()
            val times = (0 until 3).map {
                val t = System.currentTimeMillis()
                val out = llm.generate(Prompts.COACH_SYSTEM, prompt, null, 128)
                if (it == 0) Log.i("LumenProbe", "$name reply: ${out.take(200).replace('\n', ' ')}")
                System.currentTimeMillis() - t
            }
            val peakRss = rssMb()
            sampling.set(false)
            sampler.join()
            Log.i(
                "LumenProbe",
                "$name: backend=${llm.activeBackend} cold=${cold.getOrNull()}ms runs=${times}ms " +
                    "rss base=$baseRss loaded=$loadedRss peak=$peakRss MB, sysAvail before=$baseAvail min=${minAvail.get()} (used ${baseAvail - minAvail.get()}) MB",
            )
            llm.unload()
            Thread.sleep(3000)
        }
    }
}
