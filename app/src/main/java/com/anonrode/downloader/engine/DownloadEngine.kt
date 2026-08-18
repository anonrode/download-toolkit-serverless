package com.anonrode.downloader.engine

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.StatFs
import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.providers.ProviderRegistry
import com.anonrode.downloader.resolvers.ResolverRegistry
import com.anonrode.downloader.resolvers.isDirectMediaUrl
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
    private val networkObserver: NetworkObserver = NetworkObserver(context)
) {
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeJobs = ConcurrentHashMap<String, Job>()

    var maxConcurrentDownloads: Int = 3
    var parallelSocketsPerFile: Int = 16
    var defaultQuality: String = "720p"
    var autoOrganizeByShow: Boolean = true
    var instantSocialDownload: Boolean = false
    var wifiOnlyTorrents: Boolean = false
    var downloadTorrentsWifiOnly: Boolean
        get() = wifiOnlyTorrents
        set(value) { wifiOnlyTorrents = value }
    var showPostersInResults: Boolean = true
    var storageGuardGb: Double = 1.0

    val tasks: StateFlow<List<DownloadTask>> = repository.tasks

    init {
        loadPreferences()
        engineScope.launch {
            // Auto-rescue tasks interrupted by app kill/crash
            val currentTasks = repository.tasks.value
            currentTasks.forEach { t ->
                if (t.status == TaskStatus.DOWNLOADING || t.status == TaskStatus.RESOLVING) {
                    repository.update(t.id) { it.copy(status = TaskStatus.QUEUED) }
                }
            }
            networkObserver.status.collect { net ->
                if (net.isConnected) {
                    processQueue()
                }
            }
        }
    }

    private fun loadPreferences() {
        val prefs = context.getSharedPreferences("downloader_settings", Context.MODE_PRIVATE)
        maxConcurrentDownloads = prefs.getInt("pref_max_downloads", 3)
        parallelSocketsPerFile = prefs.getInt("pref_parallel_sockets", 16)
        defaultQuality = prefs.getString("pref_default_quality", "720p") ?: "720p"
        autoOrganizeByShow = prefs.getBoolean("pref_auto_organize", true)
        instantSocialDownload = prefs.getBoolean("pref_instant_social", false)
        wifiOnlyTorrents = prefs.getBoolean("pref_torrents_wifi_only", false)
        showPostersInResults = prefs.getBoolean("pref_show_posters", true)
        storageGuardGb = prefs.getFloat("pref_storage_guard", 1.0f).toDouble()
    }

    fun setShowPosters(show: Boolean) {
        this.showPostersInResults = show
        context.getSharedPreferences("downloader_settings", Context.MODE_PRIVATE).edit()
            .putBoolean("pref_show_posters", show)
            .apply()
    }

    fun saveAllSettings(
        maxConcurrent: Int,
        parallelSockets: Int,
        quality: String,
        autoOrganize: Boolean,
        storageGuard: Double,
        wifiOnlyTorrents: Boolean
    ) {
        this.maxConcurrentDownloads = maxConcurrent
        this.parallelSocketsPerFile = parallelSockets
        this.defaultQuality = quality
        this.autoOrganizeByShow = autoOrganize
        this.storageGuardGb = storageGuard
        this.wifiOnlyTorrents = wifiOnlyTorrents

        context.getSharedPreferences("downloader_settings", Context.MODE_PRIVATE).edit()
            .putInt("pref_max_downloads", maxConcurrent)
            .putInt("pref_parallel_sockets", parallelSockets)
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
        audioOnly: Boolean = false,
        site: String = ""
    ): String {
        val taskId = UUID.randomUUID().toString()
        val downloadFolder = getDownloadDirectory(showTitle, createDirs = false)

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
            parallelSockets = parallelSockets,
            site = site
        )

        repository.addFirst(task)
        processQueue()
        return taskId
    }

    fun pause(taskId: String) {
        activeJobs[taskId]?.cancel()
        activeJobs.remove(taskId)
        YoutubeDlDownloader.killProcess(taskId)
        repository.update(taskId) { it.copy(status = TaskStatus.PAUSED, speedBytesPerSec = 0.0) }
        updateServiceState()
        processQueue()
    }

    fun cancel(taskId: String) {
        activeJobs[taskId]?.cancel()
        activeJobs.remove(taskId)
        YoutubeDlDownloader.killProcess(taskId)
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
            val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val stat = StatFs(path.path)
            val freeGb = (stat.availableBlocksLong * stat.blockSizeLong).toDouble() / (1024.0 * 1024.0 * 1024.0)
            return freeGb >= storageGuardGb
        } catch (_: Exception) {
            return true
        }
    }

    private fun getDownloadDirectory(showTitle: String, createDirs: Boolean = false): File {
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val base = File(root, "Anon")
        val dest = when {
            showTitle.startsWith("Social", ignoreCase = true) -> {
                val platform = showTitle.substringAfter("Social/", "Generic").trim()
                val safePlatform = platform.replace(Regex("""[^a-zA-Z0-9.-]"""), "_")
                File(base, "Social/$safePlatform")
            }
            showTitle.equals("Torrents", ignoreCase = true) -> File(base, "Torrents")
            autoOrganizeByShow && showTitle.isNotBlank() && showTitle != "Direct Downloads" -> {
                val safe = showTitle.replace(Regex("""[^a-zA-Z0-9.-]"""), "_")
                File(base, safe)
            }
            else -> base
        }
        if (createDirs && !dest.exists()) dest.mkdirs()
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

    private fun isKnownLockerHost(url: String): Boolean {
        if (url.isBlank()) return false
        // If it already points to a direct video file (.mp4, .mkv, .m3u8, etc.), it is cracked and NOT an uncracked locker page
        if (isDirectMediaUrl(url)) return false

        val lower = url.lowercase()
        return listOf(
            "downloadwella.com",
            "loadedfiles.",
            "wetafiles.com",
            "vikingfile.com",
            "lulacloud.com",
            "waffi",
            "dood.",
            "streamwish.",
            "vidhide.",
            "kissorgrab.com",
            "nkiserv.com"
        ).any { lower.contains(it) }
    }

    private fun startTask(task: DownloadTask) {
        val job = engineScope.launch {
            try {
                var streamUrl = task.directUrl
                val isMagnet = streamUrl.startsWith("magnet:", ignoreCase = true)
                val isSocial = task.showTitle.startsWith("Social/", ignoreCase = true) || task.backend.contains("yt-dlp")
                android.util.Log.d("AnonDownload", "startTask: id=${task.id}, title=${task.episodeTitle}, rawUrl=$streamUrl, site=${task.site}")

                // If not a magnet and not a social media URL, resolve locker/embed URLs to direct stream/manifest URLs
                if (!isMagnet && !isSocial) {
                    repository.update(task.id) { it.copy(status = TaskStatus.RESOLVING) }
                    updateServiceState()

                    var resolved: String? = null

                    // 1. Try direct resolution via ResolverRegistry (for lockers)
                    resolved = ResolverRegistry.resolve(streamUrl, defaultQuality)
                    android.util.Log.d("AnonDownload", "Tier 1 ResolverRegistry resolved: $resolved")

                    // 2. If unresolved or returned a locker, resolve via ProviderRegistry (for provider episode pages like AsianC, Anitaku, DramaKey, Pluto, etc.)
                    if (resolved.isNullOrBlank() || isKnownLockerHost(resolved)) {
                        if (task.site.isNotBlank()) {
                            try {
                                val recipe = ProviderRegistry.resolveEpisode(task.site, streamUrl, defaultQuality)
                                if (recipe.directUrl.isNotBlank() && recipe.directUrl != streamUrl) {
                                    resolved = recipe.directUrl
                                    android.util.Log.d("AnonDownload", "Tier 2 ProviderRegistry (${task.site}) resolved: $resolved")
                                }
                            } catch (e: Exception) {
                                android.util.Log.w("AnonDownload", "Tier 2 ProviderRegistry error: ${e.message}")
                            }
                        }

                        if (resolved.isNullOrBlank() || isKnownLockerHost(resolved)) {
                            for (provider in ProviderRegistry.allProviders) {
                                if (provider.canHandle(streamUrl)) {
                                    try {
                                        val recipe = provider.resolveEpisode(streamUrl, defaultQuality)
                                        if (recipe.directUrl.isNotBlank() && recipe.directUrl != streamUrl) {
                                            resolved = recipe.directUrl
                                            android.util.Log.d("AnonDownload", "Tier 2 Fallback Provider (${provider.name}) resolved: $resolved")
                                            break
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.w("AnonDownload", "Tier 2 Fallback Provider error: ${e.message}")
                                    }
                                }
                            }
                        }
                    }

                    // 3. If provider resolved an intermediate locker link (e.g. downloadwella/loadedfiles), resolve locker
                    if (!resolved.isNullOrBlank() && (isKnownLockerHost(resolved) || !isDirectMediaUrl(resolved))) {
                        try {
                            val innerResolved = ResolverRegistry.resolve(resolved, defaultQuality)
                            if (!innerResolved.isNullOrBlank() && !isKnownLockerHost(innerResolved)) {
                                resolved = innerResolved
                                android.util.Log.d("AnonDownload", "Tier 3 Secondary Resolver resolved: $resolved")
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("AnonDownload", "Tier 3 Secondary Resolver error: ${e.message}")
                        }
                    }

                    if (activeJobs[task.id] == null) return@launch

                    if (!resolved.isNullOrBlank() && !isKnownLockerHost(resolved)) {
                        streamUrl = resolved
                    } else {
                        // Check if it's already a direct media URL (ends in .mp4/.mkv/.m3u8) AND not a raw locker page
                        val isLocker = isKnownLockerHost(streamUrl)
                        val isDirectMedia = isDirectMediaUrl(streamUrl)
                        if (isLocker || !isDirectMedia) {
                            android.util.Log.e("AnonDownload", "Resolution FAILED: streamUrl=$streamUrl, resolved=$resolved, isLocker=$isLocker, isDirectMedia=$isDirectMedia")
                            repository.update(task.id) { it.copy(status = TaskStatus.FAILED, errorMessage = "Could not crack stream link ($streamUrl)") }
                            return@launch
                        }
                    }
                }

                val isHlsStream = streamUrl.lowercase().contains(".m3u8") || streamUrl.lowercase().contains("manifest")
                val finalBackend = if (isSocial || isHlsStream) "yt-dlp" else "aria2c"
                val isExtractor = isSocial
                android.util.Log.d("AnonDownload", "Final stream URL: $streamUrl, backend: $finalBackend")

                repository.update(task.id) { it.copy(status = TaskStatus.DOWNLOADING, directUrl = streamUrl) }
                updateServiceState()

                val targetFolder = getDownloadDirectory(task.showTitle, createDirs = true)
                val refererToPass = getRefererForUrl(streamUrl)

                // Direct CDN files go through TurboDownloader: multi-socket byte
                // ranges written at absolute offsets into ONE file. The lockers
                // throttle per connection, so parallel ranges are measurably
                // faster (155 KB/s -> ~355 KB/s on the loadedfiles CDN), and
                // there are no .partN fragments to merge. Socket count is per-host
                // capped inside TurboDownloader. Magnets and social/HLS still need
                // yt-dlp/aria2c (peer logic, playlist muxing), so they fall through.
                val producedFile: File? = if (finalBackend == "aria2c" && !isMagnet) {
                    val hdrs = mutableMapOf("User-Agent" to HttpClient.DEFAULT_UA)
                    if (refererToPass.isNotBlank()) hdrs["Referer"] = refererToPass
                    val dest = File(targetFolder, File(task.filePath).name)
                    val turbo = TurboDownloader.download(
                        url = streamUrl,
                        dest = dest,
                        headers = hdrs,
                        configuredSockets = task.parallelSockets,
                        onProgress = { got, tot, bps ->
                            val pct = if (tot > 0) (got * 100 / tot).coerceIn(0L, 100L) else 0L
                            repository.update(task.id) {
                                it.copy(downloadedBytes = pct, totalBytes = 100L,
                                        speedBytesPerSec = bps.toDouble())
                            }
                            updateServiceState()
                        }
                    )
                    if (turbo != null) {
                        android.util.Log.d("AnonDownload",
                            "Turbo done: ${turbo.bytes} bytes, segmented=${turbo.segmented}")
                        turbo.file
                    } else {
                        // Turbo exhausted its own single-stream fallback; let the
                        // aria2c subprocess try before calling the task failed.
                        android.util.Log.w("AnonDownload", "Turbo failed, falling back to aria2c")
                        YoutubeDlDownloader.download(
                            context = context,
                            taskId = task.id,
                            sourceUrl = streamUrl,
                            targetDir = targetFolder,
                            preferredFilename = File(task.filePath).name,
                            backend = finalBackend,
                            referer = refererToPass,
                            ua = HttpClient.DEFAULT_UA,
                            parallelSockets = task.parallelSockets,
                            quality = defaultQuality,
                            isExtractorTask = false,
                            onProgress = { progressFloat ->
                                val pct = progressFloat.toLong().coerceIn(0L, 100L)
                                repository.update(task.id) { it.copy(downloadedBytes = pct, totalBytes = 100L) }
                                updateServiceState()
                            }
                        )
                    }
                } else {
                    YoutubeDlDownloader.download(
                        context = context,
                        taskId = task.id,
                        sourceUrl = streamUrl,
                        targetDir = targetFolder,
                        preferredFilename = File(task.filePath).name,
                        backend = finalBackend,
                        referer = refererToPass,
                        ua = HttpClient.DEFAULT_UA,
                        parallelSockets = if (finalBackend == "yt-dlp") 1 else task.parallelSockets,
                        quality = defaultQuality,
                        isExtractorTask = isExtractor,
                        onProgress = { progressFloat ->
                            val pct = progressFloat.toLong().coerceIn(0L, 100L)
                            repository.update(task.id) { it.copy(downloadedBytes = pct, totalBytes = 100L) }
                            updateServiceState()
                        }
                    )
                }

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
                            null,
                            null
                        )
                    } catch (_: Throwable) {}

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
