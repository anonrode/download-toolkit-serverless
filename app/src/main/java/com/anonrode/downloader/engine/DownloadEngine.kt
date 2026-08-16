package com.anonrode.downloader.engine

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.StatFs
import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus
import com.anonrode.downloader.resolvers.ResolverRegistry
import com.anonrode.downloader.security.TorrentSecurityShield
import com.anonrode.downloader.service.DownloadService
import com.anonrode.downloader.util.NetworkObserver
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DownloadEngine(
    private val context: Context,
    private val repository: DownloadRepository,
    private val networkObserver: NetworkObserver
) {
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeJobs = ConcurrentHashMap<String, Job>()

    var maxConcurrentDownloads: Int = 3
    var defaultQuality: String = "720p"
    var autoOrganizeByShow: Boolean = true
    var storageGuardGb: Double = 1.0
    var wifiOnlyTorrents: Boolean = false

    val tasks: StateFlow<List<DownloadTask>> = repository.tasks

    init {
        loadPreferences()
    }

    private fun loadPreferences() {
        val prefs = context.getSharedPreferences("downloader_settings", Context.MODE_PRIVATE)
        maxConcurrentDownloads = prefs.getInt("pref_max_downloads", 3)
        defaultQuality = prefs.getString("pref_default_quality", "720p") ?: "720p"
        autoOrganizeByShow = prefs.getBoolean("pref_auto_organize", true)
        storageGuardGb = prefs.getFloat("pref_storage_guard", 1.0f).toDouble()
        wifiOnlyTorrents = prefs.getBoolean("pref_torrents_wifi_only", false)
    }

    fun savePreferences(
        maxDownloads: Int,
        quality: String,
        autoOrganize: Boolean,
        storageGuard: Double,
        wifiOnlyTorrents: Boolean
    ) {
        this.maxConcurrentDownloads = maxDownloads
        this.defaultQuality = quality
        this.autoOrganizeByShow = autoOrganize
        this.storageGuardGb = storageGuard
        this.wifiOnlyTorrents = wifiOnlyTorrents

        context.getSharedPreferences("downloader_settings", Context.MODE_PRIVATE).edit()
            .putInt("pref_max_downloads", maxDownloads)
            .putString("pref_default_quality", quality)
            .putBoolean("pref_auto_organize", autoOrganize)
            .putFloat("pref_storage_guard", storageGuard.toFloat())
            .putBoolean("pref_torrents_wifi_only", wifiOnlyTorrents)
            .apply()
    }

    fun enqueue(
        showTitle: String,
        episodeNum: Int,
        episodeTitle: String,
        sourceUrl: String,
        isDirect: Boolean,
        backend: String = "aria2c",
        parallelSockets: Int = 16,
        audioOnly: Boolean = false
    ): String {
        val taskId = UUID.randomUUID().toString()
        val downloadFolder = getDownloadDirectory(showTitle)

        val cleanTitle = episodeTitle.replace(Regex("""[^a-zA-Z0-9._ -]"""), "_").trim()
        val ext = if (audioOnly) "mp3" else if (backend.contains("yt") || !isDirect) "mp4" else "mkv"
        val targetFile = File(downloadFolder, "$cleanTitle.$ext")

        val task = DownloadTask(
            id = taskId,
            showTitle = showTitle,
            episodeNum = episodeNum,
            episodeTitle = episodeTitle,
            directUrl = sourceUrl,
            filePath = targetFile.absolutePath,
            status = TaskStatus.QUEUED,
            downloadedBytes = 0L,
            totalBytes = 100L,
            speedBytesPerSec = 0.0,
            backend = backend,
            parallelSockets = parallelSockets
        )

        repository.addFirst(task)
        processQueue()
        return taskId
    }

    fun pause(taskId: String) {
        activeJobs[taskId]?.cancel()
        activeJobs.remove(taskId)
        try {
            YoutubeDL.getInstance().destroyProcessById(taskId)
        } catch (_: Exception) {}
        repository.update(taskId) { it.copy(status = TaskStatus.PAUSED, speedBytesPerSec = 0.0) }
        updateServiceState()
        processQueue()
    }

    fun cancel(taskId: String) {
        activeJobs[taskId]?.cancel()
        activeJobs.remove(taskId)
        try {
            YoutubeDL.getInstance().destroyProcessById(taskId)
        } catch (_: Exception) {}
        repository.remove(taskId)
        updateServiceState()
        processQueue()
    }

    fun retry(taskId: String) {
        repository.update(taskId) { it.copy(status = TaskStatus.QUEUED, errorMessage = null) }
        processQueue()
    }

    private fun looksLikeHtml(file: File): Boolean {
        return try {
            val head = file.inputStream().use { ins ->
                val buf = ByteArray(512)
                val n = ins.read(buf)
                if (n <= 0) "" else String(buf, 0, n).trimStart().lowercase()
            }
            head.startsWith("<!doctype html") || head.startsWith("<html") || head.startsWith("<head") || head.startsWith("<!--")
        } catch (_: Exception) {
            false
        }
    }

    private fun checkStorageAvailable(): Boolean {
        try {
            val path = Environment.getExternalStorageDirectory()
            val stat = StatFs(path.path)
            val freeGb = (stat.availableBlocksLong * stat.blockSizeLong).toDouble() / (1024.0 * 1024.0 * 1024.0)
            return freeGb >= storageGuardGb
        } catch (_: Exception) {
            return true
        }
    }

    private fun getDownloadDirectory(showTitle: String): File {
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val base = File(root, "Anon")
        val dest = when {
            showTitle.startsWith("Social", ignoreCase = true) -> {
                val platform = showTitle.substringAfter("Social/", "Generic").trim()
                File(base, "Social/$platform")
            }
            showTitle.equals("Torrents", ignoreCase = true) -> File(base, "Torrents")
            autoOrganizeByShow && showTitle.isNotBlank() && showTitle != "Direct Downloads" -> {
                val safe = showTitle.replace(Regex("""[^a-zA-Z0-9.-]"""), "_")
                File(base, safe)
            }
            else -> base
        }
        if (!dest.exists()) dest.mkdirs()
        return dest
    }

    private fun getRefererForUrl(url: String): String {
        val low = url.lowercase()
        return when {
            low.contains("gogoanime") || low.contains("anitaku") || low.contains("workers.dev") -> "https://gogoanime.or.at/"
            low.contains("asianc") || low.contains("vidbasic") || low.contains("vidb") -> "https://asianc.id/"
            low.contains("pluto") || low.contains("kissorgrab") -> "https://plutomovies.com/"
            low.contains("thenkiri") || low.contains("nkiri") -> "https://thenkiri.com/"
            low.contains("9jarocks") || low.contains("loadedfiles") -> "https://my9jarocks.bz/"
            low.contains("naijavault") || low.contains("vikingfile") || low.contains("lulacloud") -> "https://www.naijavault.com/"
            low.contains("naijaprey") -> "https://www.naijaprey.tv/"
            low.contains("dramakey") -> "https://dramakey.com/"
            low.contains("dramarain") -> "https://dramarain.com/"
            else -> ""
        }
    }

    private fun updateServiceState() {
        val current = tasks.value
        val active = current.filter { it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.RESOLVING }
        if (active.isNotEmpty()) {
            val first = active.first()
            DownloadService.updateProgress(
                context,
                title = first.episodeTitle,
                progress = first.downloadedBytes.toInt(),
                activeCount = active.size
            )
        } else {
            DownloadService.stop(context)
        }
    }

    @Synchronized
    private fun processQueue() {
        val net = networkObserver.getCurrentStatus()
        if (!net.isConnected) return

        val currentTasks = tasks.value
        val activeCount = currentTasks.count { it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.RESOLVING }

        if (activeCount >= maxConcurrentDownloads) return

        val nextTask = currentTasks.firstOrNull { it.status == TaskStatus.QUEUED } ?: return

        if (!checkStorageAvailable()) {
            repository.update(nextTask.id) { it.copy(status = TaskStatus.FAILED, errorMessage = "Storage limit reached (< ${storageGuardGb}GB free)") }
            return
        }

        if (wifiOnlyTorrents && !net.isWifi && nextTask.directUrl.startsWith("magnet:")) {
            repository.update(nextTask.id) { it.copy(status = TaskStatus.PAUSED, errorMessage = "Waiting for Wi-Fi (Torrents Wi-Fi Only enabled)") }
            return
        }

        startTask(nextTask)
    }

    private fun startTask(task: DownloadTask) {
        val job = engineScope.launch {
            try {
                var streamUrl = task.directUrl
                val isMagnet = streamUrl.startsWith("magnet:")
                val isHlsStream = streamUrl.contains(".m3u8") || streamUrl.contains("manifest")
                val isExplicitYtdlp = task.backend.contains("yt-dlp") || task.backend.contains("ytdlp")
                val isSocialOrYtdlp = isExplicitYtdlp || isHlsStream

                if (!isMagnet && !isSocialOrYtdlp && !streamUrl.startsWith("http")) {
                    repository.update(task.id) { it.copy(status = TaskStatus.RESOLVING) }
                    updateServiceState()

                    val resolved = ResolverRegistry.resolve(streamUrl, defaultQuality)
                    if (resolved.isNullOrBlank()) {
                        repository.update(task.id) { it.copy(status = TaskStatus.FAILED, errorMessage = "Could not resolve stream link") }
                        return@launch
                    }
                    streamUrl = resolved
                }

                repository.update(task.id) { it.copy(status = TaskStatus.DOWNLOADING) }
                updateServiceState()

                val targetFolder = getDownloadDirectory(task.showTitle)
                val finalBackend = if (isSocialOrYtdlp || streamUrl.contains(".m3u8")) "yt-dlp" else "aria2c"
                val isExtractor = finalBackend == "yt-dlp"
                val refererToPass = getRefererForUrl(streamUrl)

                val producedFile = YoutubeDlDownloader.download(
                    context = context,
                    taskId = task.id,
                    sourceUrl = streamUrl,
                    targetDir = targetFolder,
                    preferredFilename = File(task.filePath).name,
                    backend = finalBackend,
                    referer = refererToPass,
                    parallelSockets = if (finalBackend == "yt-dlp") 1 else task.parallelSockets,
                    quality = defaultQuality,
                    isExtractorTask = isExtractor,
                    onProgress = { progressFloat ->
                        val pct = progressFloat.toLong().coerceIn(0L, 100L)
                        repository.update(task.id) { it.copy(downloadedBytes = pct, totalBytes = 100L) }
                        updateServiceState()
                    }
                )

                val validation = if (producedFile != null && producedFile.exists()) {
                    TorrentSecurityShield.validateDownloadedFile(producedFile, targetFolder)
                } else {
                    Pair(false, "File missing")
                }

                if (producedFile != null && producedFile.exists() && producedFile.length() > 500 * 1024
                    && !looksLikeHtml(producedFile) && validation.first) {
                    val finalTitle = producedFile.nameWithoutExtension
                    repository.update(task.id) {
                        it.copy(
                            filePath = producedFile.absolutePath,
                            episodeTitle = if (isExtractor) finalTitle else it.episodeTitle,
                            downloadedBytes = 100L,
                            totalBytes = 100L,
                            status = TaskStatus.COMPLETED
                        )
                    }

                    try {
                        MediaScannerConnection.scanFile(
                            context,
                            arrayOf(producedFile.absolutePath),
                            arrayOf("video/*", "audio/*"),
                            null
                        )
                    } catch (_: Exception) {}

                    DownloadService.notifyCompleted(context, finalTitle)
                } else {
                    val errReason = if (!validation.first) validation.second else "Output file was too small or corrupted"
                    repository.update(task.id) { it.copy(status = TaskStatus.FAILED, errorMessage = errReason) }
                }
            } catch (e: CancellationException) {
                // Cancelled
            } catch (e: Exception) {
                repository.update(task.id) { it.copy(status = TaskStatus.FAILED, errorMessage = e.message ?: "Download error") }
            } finally {
                activeJobs.remove(task.id)
                updateServiceState()
                processQueue()
            }
        }

        activeJobs[task.id] = job
    }
}
