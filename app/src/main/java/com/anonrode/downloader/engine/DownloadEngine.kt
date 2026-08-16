package com.anonrode.downloader.engine

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.StatFs
import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus
import com.anonrode.downloader.resolvers.ResolverRegistry
import com.anonrode.downloader.service.DownloadService
import com.anonrode.downloader.util.NetworkObserver
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID

class DownloadEngine(
    private val context: Context,
    private val repository: DownloadRepository
) {
    companion object {
        // Marker set when a task is auto-paused by a network drop, so the
        // reconnect handler knows which PAUSED tasks to auto-resume (vs the
        // ones the user paused by hand, which must stay paused).
        private const val NET_WAIT_MSG = "Waiting for network..."
    }

    val tasks: StateFlow<List<DownloadTask>> = repository.tasks
    val networkObserver = NetworkObserver(context)

    private val prefs: SharedPreferences = context.getSharedPreferences("anon_downloader_settings", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeJobs = mutableMapOf<String, Job>()

    var maxConcurrentDownloads: Int = prefs.getInt("pref_max_concurrent", 3)
    var parallelSocketsPerFile: Int = prefs.getInt("pref_sockets", 16)
    var defaultQuality: String = prefs.getString("pref_quality", "720p") ?: "720p"
    var autoOrganizeByShow: Boolean = prefs.getBoolean("pref_auto_organize", true)
    var instantSocialDownload: Boolean = prefs.getBoolean("pref_instant_social", true)
    var storageGuardGb: Double = prefs.getFloat("pref_storage_guard", 1.0f).toDouble()
    var downloadTorrentsWifiOnly: Boolean = prefs.getBoolean("pref_torrents_wifi_only", false)
    // Show poster art in search results. Off = the styled-initial tile only, so a
    // user on metered data pays zero image bandwidth. Default on.
    var showPostersInResults: Boolean = prefs.getBoolean("pref_show_posters", true)

    fun setShowPosters(value: Boolean) {
        showPostersInResults = value
        prefs.edit().putBoolean("pref_show_posters", value).apply()
    }

    init {
        repository.initPersistence(context.filesDir)

        scope.launch {
            networkObserver.status.collect { net ->
                if (!net.isConnected) {
                    activeJobs.keys.toList().forEach { id ->
                        pause(id)
                        repository.update(id) { it.copy(errorMessage = NET_WAIT_MSG) }
                    }
                } else {
                    // pause() above set these to PAUSED; processQueue only starts
                    // QUEUED tasks, so without re-queueing them here a network blip
                    // would strand every download as PAUSED forever.
                    tasks.value
                        .filter { it.status == TaskStatus.PAUSED && it.errorMessage == NET_WAIT_MSG }
                        .forEach { t -> repository.update(t.id) { it.copy(status = TaskStatus.QUEUED, errorMessage = null) } }
                    processQueue()
                }
            }
        }

        scope.launch {
            processQueue()
        }
    }

    fun saveAllSettings(
        maxConcurrent: Int,
        parallelSockets: Int,
        quality: String,
        autoOrganize: Boolean,
        storageGuard: Double,
        wifiOnlyTorrents: Boolean
    ) {
        maxConcurrentDownloads = maxConcurrent
        parallelSocketsPerFile = parallelSockets
        defaultQuality = quality
        autoOrganizeByShow = autoOrganize
        storageGuardGb = storageGuard
        downloadTorrentsWifiOnly = wifiOnlyTorrents

        prefs.edit()
            .putInt("pref_max_concurrent", maxConcurrent)
            .putInt("pref_sockets", parallelSockets)
            .putString("pref_quality", quality)
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
        val ext = if (audioOnly) "mp3" else if (backend == "yt-dlp" || !isDirect) "mp4" else "mkv"
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

    /**
     * A last-resort guard against the "downloaded an HTML page" failure: if an
     * unresolved embed page slips through, aria2c saves the HTML, which can be
     * large enough to pass the size check. Sniff the first bytes for a markup
     * signature and reject it as a failed download rather than a video.
     */
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

    private fun checkStorageAvailable(): Boolean {        try {
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

        // Find the first QUEUED task we can actually start. Skipping (not
        // returning) is deliberate: a Wi-Fi-only torrent at the head of the
        // queue must not block unrelated downloads on mobile data.
        for (queued in currentTasks.filter { it.status == TaskStatus.QUEUED }) {
            if (queued.showTitle == "Torrents" && downloadTorrentsWifiOnly && !net.isWifi) {
                repository.update(queued.id) { it.copy(errorMessage = "Paused: Waiting for Wi-Fi (Torrent)") }
                continue
            }
            // Claim the task synchronously BEFORE launching. startTask flips the
            // status inside a coroutine, so without claiming it here a second
            // processQueue() (fired on every enqueue) could re-pick the same
            // QUEUED task and launch a duplicate job for it.
            repository.update(queued.id) { it.copy(status = TaskStatus.RESOLVING, errorMessage = null) }
            startTask(queued.copy(status = TaskStatus.RESOLVING))
            return
        }
    }

    private fun startTask(task: DownloadTask) {
        val job = scope.launch {
            try {
                if (!checkStorageAvailable()) {
                    repository.update(task.id) { it.copy(status = TaskStatus.PAUSED, errorMessage = "Paused: Storage below ${storageGuardGb}GB") }
                    return@launch
                }

                var streamUrl = task.directUrl
                val isMagnet = streamUrl.startsWith("magnet:?", ignoreCase = true)

                if (task.backend != "yt-dlp" && !isMagnet) {
                    repository.update(task.id) { it.copy(status = TaskStatus.RESOLVING) }
                    updateServiceState()
                    val resolved = ResolverRegistry.resolve(task.directUrl, defaultQuality)
                    if (resolved.isNullOrBlank()) {
                        repository.update(task.id) { it.copy(status = TaskStatus.FAILED, errorMessage = "Could not resolve stream link") }
                        updateServiceState()
                        processQueue()
                        return@launch
                    }
                    streamUrl = resolved
                }

                repository.update(task.id) { it.copy(status = TaskStatus.DOWNLOADING) }
                updateServiceState()

                val targetFolder = getDownloadDirectory(task.showTitle)
                val isExtractor = task.backend == "yt-dlp"

                val producedFile = YoutubeDlDownloader.download(
                    context = context,
                    taskId = task.id,
                    sourceUrl = streamUrl,
                    targetDir = targetFolder,
                    preferredFilename = File(task.filePath).name,
                    backend = task.backend,
                    referer = "",
                    parallelSockets = if (task.backend == "yt-dlp") 1 else task.parallelSockets,
                    quality = defaultQuality,
                    isExtractorTask = isExtractor,
                    onProgress = { progressFloat ->
                        val pct = progressFloat.toLong().coerceIn(0L, 100L)
                        repository.update(task.id) { it.copy(downloadedBytes = pct, totalBytes = 100L) }
                        updateServiceState()
                    }
                )

                if (producedFile != null && producedFile.exists() && producedFile.length() > 500 * 1024
                    && !looksLikeHtml(producedFile)) {
                    val finalTitle = producedFile.nameWithoutExtension
                    repository.update(task.id) {
                        it.copy(
                            filePath = producedFile.absolutePath,
                            // Only extractor/social tasks get their title from the
                            // produced filename (yt-dlp metadata). A drama episode
                            // keeps "Episode 05" instead of becoming the file stem.
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
                    repository.update(task.id) { it.copy(status = TaskStatus.FAILED, errorMessage = "Output file was too small or missing") }
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
