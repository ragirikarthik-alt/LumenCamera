package com.aicamera.photo.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicamera.photo.domain.CoachingIntensity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class CameraMode { GUIDED, CONTINUOUS }

enum class AiBackendPreference { GPU, CPU }

data class AppSettings(
    val coachingIntensity: CoachingIntensity = CoachingIntensity.BALANCED,
    val defaultMode: CameraMode = CameraMode.CONTINUOUS,
    val showGrid: Boolean = true,
    val showHorizonLevel: Boolean = true,
    val showSubjectBoxes: Boolean = true,
    val haptics: Boolean = true,
    val showDiagnostics: Boolean = false,
    val captureRaw: Boolean = false,
    val aiEnabled: Boolean = true,
    /** Automatic AI ideas while the camera runs. Off by default: it keeps ~1 GB of model in memory. */
    val liveAi: Boolean = false,
    val aiBackend: AiBackendPreference = AiBackendPreference.GPU,
    val selectedModelId: String? = null,
    val autoCritique: Boolean = true,
    val onboardingDone: Boolean = false,
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val intensity = stringPreferencesKey("coaching_intensity")
        val mode = stringPreferencesKey("default_mode")
        val grid = booleanPreferencesKey("show_grid")
        val horizon = booleanPreferencesKey("show_horizon")
        val boxes = booleanPreferencesKey("show_boxes")
        val haptics = booleanPreferencesKey("haptics")
        val diagnostics = booleanPreferencesKey("diagnostics")
        val raw = booleanPreferencesKey("capture_raw")
        val ai = booleanPreferencesKey("ai_enabled")
        val liveAi = booleanPreferencesKey("live_ai")
        val aiBackend = stringPreferencesKey("ai_backend")
        val model = stringPreferencesKey("selected_model")
        val critique = booleanPreferencesKey("auto_critique")
        val onboarding = booleanPreferencesKey("onboarding_done")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            coachingIntensity = p[Keys.intensity]?.let { runCatching { CoachingIntensity.valueOf(it) }.getOrNull() } ?: CoachingIntensity.BALANCED,
            defaultMode = p[Keys.mode]?.let { runCatching { CameraMode.valueOf(it) }.getOrNull() } ?: CameraMode.CONTINUOUS,
            showGrid = p[Keys.grid] ?: true,
            showHorizonLevel = p[Keys.horizon] ?: true,
            showSubjectBoxes = p[Keys.boxes] ?: true,
            haptics = p[Keys.haptics] ?: true,
            showDiagnostics = p[Keys.diagnostics] ?: false,
            captureRaw = p[Keys.raw] ?: false,
            aiEnabled = p[Keys.ai] ?: true,
            liveAi = p[Keys.liveAi] ?: false,
            aiBackend = p[Keys.aiBackend]?.let { runCatching { AiBackendPreference.valueOf(it) }.getOrNull() } ?: AiBackendPreference.GPU,
            selectedModelId = p[Keys.model],
            autoCritique = p[Keys.critique] ?: true,
            onboardingDone = p[Keys.onboarding] ?: false,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setIntensity(v: CoachingIntensity) = context.dataStore.edit { it[Keys.intensity] = v.name }
    suspend fun setDefaultMode(v: CameraMode) = context.dataStore.edit { it[Keys.mode] = v.name }
    suspend fun setShowGrid(v: Boolean) = context.dataStore.edit { it[Keys.grid] = v }
    suspend fun setShowHorizon(v: Boolean) = context.dataStore.edit { it[Keys.horizon] = v }
    suspend fun setShowBoxes(v: Boolean) = context.dataStore.edit { it[Keys.boxes] = v }
    suspend fun setHaptics(v: Boolean) = context.dataStore.edit { it[Keys.haptics] = v }
    suspend fun setDiagnostics(v: Boolean) = context.dataStore.edit { it[Keys.diagnostics] = v }
    suspend fun setCaptureRaw(v: Boolean) = context.dataStore.edit { it[Keys.raw] = v }
    suspend fun setAiEnabled(v: Boolean) = context.dataStore.edit { it[Keys.ai] = v }
    suspend fun setLiveAi(v: Boolean) = context.dataStore.edit { it[Keys.liveAi] = v }
    suspend fun setAiBackend(v: AiBackendPreference) = context.dataStore.edit { it[Keys.aiBackend] = v.name }
    suspend fun setSelectedModel(id: String?) = context.dataStore.edit { if (id == null) it.remove(Keys.model) else it[Keys.model] = id }
    suspend fun setAutoCritique(v: Boolean) = context.dataStore.edit { it[Keys.critique] = v }
    suspend fun setOnboardingDone(v: Boolean) = context.dataStore.edit { it[Keys.onboarding] = v }
}
