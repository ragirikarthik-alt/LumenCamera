package com.aicamera.photo.core.ai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import com.aicamera.photo.core.data.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

/** A model that can be installed. Large models are never bundled in the APK. */
data class ModelSpec(
    val id: String,
    val name: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String?,
    val license: String,
    val description: String,
    val supportsImages: Boolean,
    /** Rough peak RAM while running, for user guidance (download size != runtime RAM). */
    val estimatedRuntimeRamGb: Float,
    val version: String,
)

object ModelCatalog {
    val GEMMA_4_E2B = ModelSpec(
        id = "gemma-4-e2b-it",
        name = "Gemma 4 E2B (instruction-tuned)",
        fileName = "gemma-4-E2B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        sizeBytes = 2_588_147_712L,
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        license = "Apache 2.0",
        description = "Multimodal on-device model for scene interpretation and critique. Runs fully offline once installed.",
        supportsImages = true,
        estimatedRuntimeRamGb = 3.0f,
        version = "litert-community/main",
    )

    val all = listOf(GEMMA_4_E2B)
    fun byId(id: String?) = all.firstOrNull { it.id == id }
}

sealed interface ModelInstallState {
    data object NotInstalled : ModelInstallState
    data class Downloading(val downloaded: Long, val total: Long) : ModelInstallState {
        val fraction get() = if (total > 0) downloaded.toFloat() / total else 0f
    }
    data class Importing(val copied: Long, val total: Long) : ModelInstallState
    data class Verifying(val fraction: Float) : ModelInstallState
    data class Installed(val record: InstalledModel) : ModelInstallState
    data class Failed(val reason: String) : ModelInstallState
}

@Serializable
data class InstalledModel(
    val modelId: String,
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
    /** True when the checksum matched the catalog. Imported custom files may be unverified. */
    val verified: Boolean,
    val version: String,
    val installedAt: Long,
)

@Serializable
private data class ModelRegistry(
    val installed: List<InstalledModel> = emptyList(),
    val pendingDownloads: Map<String, Long> = emptyMap(),
)

/**
 * Installs model assets on explicit user request: download via the system DownloadManager or import
 * from a local file, then verify the SHA-256 checksum. The camera never depends on a model.
 */
class ModelManager(private val context: Context, private val scope: CoroutineScope) {

    val modelsDir: File = (context.getExternalFilesDir("models") ?: File(context.filesDir, "models")).also { it.mkdirs() }
    private val registryFile = File(modelsDir, "registry.json")
    private val downloads = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private val _states = MutableStateFlow<Map<String, ModelInstallState>>(emptyMap())
    val states: StateFlow<Map<String, ModelInstallState>> = _states
    private val jobs = mutableMapOf<String, Job>()

    init {
        val reg = readRegistry()
        val initial = ModelCatalog.all.associate { spec ->
            val rec = reg.installed.firstOrNull { it.modelId == spec.id && File(it.path).exists() }
            spec.id to (if (rec != null) ModelInstallState.Installed(rec) else ModelInstallState.NotInstalled)
        }
        _states.value = initial
        // Resume tracking downloads that were running when the app was closed.
        reg.pendingDownloads.forEach { (modelId, downloadId) ->
            ModelCatalog.byId(modelId)?.let { trackDownload(it, downloadId) }
        }
    }

    fun state(modelId: String): ModelInstallState = _states.value[modelId] ?: ModelInstallState.NotInstalled

    fun installed(modelId: String?): InstalledModel? =
        (modelId?.let { state(it) } as? ModelInstallState.Installed)?.record

    fun freeSpaceBytes(): Long = runCatching { StatFs(modelsDir.absolutePath).availableBytes }.getOrDefault(0L)

    fun hasSpaceFor(spec: ModelSpec) = freeSpaceBytes() > (spec.sizeBytes * 1.1).toLong()

    /** User-initiated download. */
    fun download(spec: ModelSpec) {
        if (jobs[spec.id]?.isActive == true) return
        if (!hasSpaceFor(spec)) {
            setState(spec.id, ModelInstallState.Failed("Not enough free storage. ${formatBytes(spec.sizeBytes)} needed."))
            return
        }
        val target = File(modelsDir, spec.fileName + ".part")
        target.delete()
        val request = DownloadManager.Request(Uri.parse(spec.url))
            .setTitle("Lumen Camera: ${spec.name}")
            .setDescription("On-device AI model (${formatBytes(spec.sizeBytes)})")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(target))
            .setAllowedOverMetered(true)
        val id = downloads.enqueue(request)
        updateRegistry { it.copy(pendingDownloads = it.pendingDownloads + (spec.id to id)) }
        trackDownload(spec, id)
    }

    fun cancelDownload(spec: ModelSpec) {
        val id = readRegistry().pendingDownloads[spec.id]
        if (id != null) downloads.remove(id)
        jobs[spec.id]?.cancel()
        updateRegistry { it.copy(pendingDownloads = it.pendingDownloads - spec.id) }
        File(modelsDir, spec.fileName + ".part").delete()
        setState(spec.id, ModelInstallState.NotInstalled)
    }

    private fun trackDownload(spec: ModelSpec, downloadId: Long) {
        jobs[spec.id]?.cancel()
        jobs[spec.id] = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val q = DownloadManager.Query().setFilterById(downloadId)
                val (status, done, total, reason) = downloads.query(q).use { c ->
                    if (!c.moveToFirst()) return@use listOf(-1L, 0L, 0L, 0L)
                    listOf(
                        c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)).toLong(),
                        c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                        c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                        c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)).toLong(),
                    )
                }
                when (status.toInt()) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        updateRegistry { it.copy(pendingDownloads = it.pendingDownloads - spec.id) }
                        finalizeFile(spec, File(modelsDir, spec.fileName + ".part"))
                        return@launch
                    }
                    DownloadManager.STATUS_FAILED, -1 -> {
                        updateRegistry { it.copy(pendingDownloads = it.pendingDownloads - spec.id) }
                        setState(spec.id, ModelInstallState.Failed("Download failed (code $reason). Check your connection and try again."))
                        return@launch
                    }
                    else -> setState(spec.id, ModelInstallState.Downloading(done, if (total > 0) total else spec.sizeBytes))
                }
                delay(500)
            }
        }
    }

    /** Imports a model file the user already has (e.g. copied from a computer). */
    fun importFrom(spec: ModelSpec, uri: Uri) {
        if (jobs[spec.id]?.isActive == true) return
        jobs[spec.id] = scope.launch(Dispatchers.IO) {
            val target = File(modelsDir, spec.fileName + ".part")
            try {
                val total = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                if (total > 0 && freeSpaceBytes() < total * 1.05) {
                    setState(spec.id, ModelInstallState.Failed("Not enough free storage to import ${formatBytes(total)}."))
                    return@launch
                }
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(1 shl 20)
                        var copied = 0L
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            copied += n
                            if (copied - lastReport > 32L * 1024 * 1024) {
                                lastReport = copied
                                setState(spec.id, ModelInstallState.Importing(copied, total))
                            }
                        }
                    }
                } ?: error("Cannot open the selected file")
                finalizeFile(spec, target, allowUnverified = true)
            } catch (t: Throwable) {
                target.delete()
                setState(spec.id, ModelInstallState.Failed("Import failed: ${t.message}"))
            }
        }
    }

    private suspend fun finalizeFile(spec: ModelSpec, part: File, allowUnverified: Boolean = false) {
        setState(spec.id, ModelInstallState.Verifying(0f))
        val sha = sha256(part) { f -> setState(spec.id, ModelInstallState.Verifying(f)) }
        val verified = spec.sha256 != null && sha.equals(spec.sha256, ignoreCase = true)
        if (!verified && !allowUnverified) {
            part.delete()
            setState(spec.id, ModelInstallState.Failed("Checksum mismatch - the download was corrupted. Please try again."))
            return
        }
        val final = File(modelsDir, spec.fileName)
        final.delete()
        if (!part.renameTo(final)) {
            part.copyTo(final, overwrite = true)
            part.delete()
        }
        val rec = InstalledModel(spec.id, final.absolutePath, final.length(), sha, verified, spec.version, System.currentTimeMillis())
        updateRegistry { r -> r.copy(installed = r.installed.filterNot { it.modelId == spec.id } + rec) }
        setState(spec.id, ModelInstallState.Installed(rec))
    }

    fun delete(spec: ModelSpec) {
        cancelDownloadIfAny(spec)
        File(modelsDir, spec.fileName).delete()
        File(modelsDir, spec.fileName + ".part").delete()
        updateRegistry { r -> r.copy(installed = r.installed.filterNot { it.modelId == spec.id }) }
        setState(spec.id, ModelInstallState.NotInstalled)
    }

    private fun cancelDownloadIfAny(spec: ModelSpec) {
        readRegistry().pendingDownloads[spec.id]?.let { downloads.remove(it) }
        jobs[spec.id]?.cancel()
        updateRegistry { it.copy(pendingDownloads = it.pendingDownloads - spec.id) }
    }

    fun totalBytes(): Long = modelsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun setState(id: String, s: ModelInstallState) = _states.update { it + (id to s) }

    @Synchronized
    private fun readRegistry(): ModelRegistry =
        runCatching { AppJson.decodeFromString(ModelRegistry.serializer(), registryFile.readText()) }.getOrDefault(ModelRegistry())

    @Synchronized
    private fun updateRegistry(change: (ModelRegistry) -> ModelRegistry) {
        val next = change(readRegistry())
        registryFile.writeText(AppJson.encodeToString(ModelRegistry.serializer(), next))
    }

    companion object {
        suspend fun sha256(file: File, progress: (Float) -> Unit = {}): String = withContext(Dispatchers.IO) {
            val md = MessageDigest.getInstance("SHA-256")
            val total = file.length().coerceAtLeast(1)
            var read = 0L
            var last = 0L
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                    read += n
                    if (read - last > 64L * 1024 * 1024) { last = read; progress(read.toFloat() / total) }
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }

        fun formatBytes(b: Long): String = when {
            b >= 1_000_000_000 -> String.format("%.2f GB", b / 1e9)
            b >= 1_000_000 -> String.format("%.0f MB", b / 1e6)
            b >= 1_000 -> String.format("%.0f KB", b / 1e3)
            else -> "$b B"
        }
    }
}
