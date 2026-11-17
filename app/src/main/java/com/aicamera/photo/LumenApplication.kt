package com.aicamera.photo

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.aicamera.photo.core.ai.LlmBackend
import com.aicamera.photo.core.camera.ThermalMonitor
import com.aicamera.photo.core.data.AiBackendPreference
import kotlinx.coroutines.launch

class LumenApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        val c = container

        // Keep the AI coordinator in sync with user settings.
        c.appScope.launch {
            c.settings.settings.collect { s ->
                c.ai.enabled = s.aiEnabled
                c.ai.preferredBackend = if (s.aiBackend == AiBackendPreference.CPU) LlmBackend.CPU else LlmBackend.GPU
                s.selectedModelId?.let { c.ai.selectedModelId = it }
                c.ai.refreshStatus(c.thermal.status.value)
            }
        }
        c.appScope.launch {
            c.models.states.collect { c.ai.refreshStatus(c.thermal.status.value) }
        }
        // Thermal policy: release model resources when the device gets critically hot.
        c.thermal.start()
        c.appScope.launch {
            c.thermal.status.collect { status ->
                c.diagnostics.thermal(status)
                if (ThermalMonitor.shouldReleaseModel(status)) c.ai.release()
                c.ai.refreshStatus(status)
            }
        }
        // Memory housekeeping: unload the model when idle or when free memory gets tight.
        c.appScope.launch {
            while (true) {
                kotlinx.coroutines.delay(5_000)
                runCatching { c.ai.maintain() }
            }
        }
        // Release the model when the app goes to the background; it is reloaded on demand.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                c.appScope.launch { c.ai.release() }
            }
        })
    }

    /** The system is short of memory: give the model's memory back before Android starts killing apps. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW && ::container.isInitialized && container.ai.isLoaded) {
            container.appScope.launch { container.ai.release() }
        }
    }
}

val android.content.Context.container: AppContainer
    get() = (applicationContext as LumenApplication).container
