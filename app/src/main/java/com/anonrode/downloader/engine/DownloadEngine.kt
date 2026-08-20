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
import java.io.RandomAccessFile
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
                    repository.update(t.id) { it.copy(status = TaskStatus.QUEUED, speedBytesPerSec = 0.0) }
                }
            }
            networkObserver.status.collect { net ->
                if (net.isConnected) {
                    if (lastNetworkTag != null && net.networkTag != null && lastNetworkTag != net.networkTag) {
                        // Network identity changed while still connected (Wi-Fi -> mobile, VPN toggle):
                        // restart active jobs losslessly on the new network instead of letting
                        // their sockets stall against the dead one.
                        activeJobs.keys.toList().forEach { id -> pauseForNetwork(id) }
                    }
                    lastNetworkTag = net.networkTag
                    // Auto-resume tasks parked by a connectivity drop
                    repository.tasks.value
                        .filter { it.status == TaskStatus.PAUSED && it.errorMessage == NETWORK_PAUSE_MESSAGE }
                        .forEach { repository.update(it.id) { t -> t.copy(status = TaskStatus.QUEUED, errorMessage = null) } }
                    // Wi-Fi-gated torrents resume only once an actual Wi-Fi network is back
                    repository.tasks.value
                        .filter { it.status == TaskStatus.PAUSED && it.errorMessage?.startsWith("Waiting for Wi-Fi") == true && net.isWifi }
                        .forEach { repository.update(it.id) { t -> t.copy(status = TaskStatus.QUEUED, errorMessage = null) } }
                    processQueue()
                } else {
                    // Park active jobs so a reconnect resumes them instead of failing them
                    lastNetworkTag = null
                    activeJobs.keys.toList().forEach { id -> pauseForNetwork(id) }
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
        wifiOnlyTorrents: Boolean,
        instantSocial: Boolean = false,
        showPosters: Boolean = true
    ) {
        this.maxConcurrentDownloads = maxConcurrent
        this.parallelSocketsPerFile = parallelSockets
        this.defaultQuality = quality
        this.autoOrganizeByShow = autoOrganize
        this.storageGuardGb = storageGuard
        this.wifiOnlyTorrents = wifiOnlyTorrents
        this.instantSocialDownload = instantSocial
        this.showPostersInResults = showPosters

        context.getSharedPreferences("downloader_settings", Context.MODE_PRIVATE).edit()
            .putInt("pref_max_downloads", maxConcurrent)
            .putInt("pref_parallel_sockets", parallelSockets)
            .putString("pref_default_quality", quality)
            .putBoolean("pref_auto_organize", autoOrganize)
            .putFloat("pref_storage_guard", storageGuard.toFloat())
            .putBoolean("pref_torrents_wifi_only", wifiOnlyTorrents)
            .putBoolean("pref_instant_social", instantSocial)
            .putBoolean("pref_show_posters", showPosters)
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
            sourceUrl = sourceUrl,
            filePath = targetFile.absolutePath,
            status = TaskStatus.QUEUED,
            downloadedBytes = 0L,
            totalBytes = 0L,
            speedBytesPerSec = 0.0,
            etaSeconds = 0L,
            backend = backend,
            parallelSockets = parallelSockets,
            site = site,
            audioOnly = audioOnly
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
        updateServiceState(force = true)
        processQueue()
    }

    private fun pauseForNetwork(taskId: String) {
        val task = repository.find(taskId) ?: return
        if (task.status != TaskStatus.DOWNLOADING && task.status != TaskStatus.RESOLVING) return
        activeJobs[taskId]?.cancel()
        activeJobs.remove(taskId)
        YoutubeDlDownloader.killProcess(taskId)
        repository.update(taskId) { it.copy(status = TaskStatus.PAUSED, speedBytesPerSec = 0.0, errorMessage = NETWORK_PAUSE_MESSAGE) }
        updateServiceState(force = true)
    }

    fun cancel(taskId: String) {
        val task = repository.find(taskId)
        activeJobs[taskId]?.cancel()
        activeJobs.remove(taskId)
        YoutubeDlDownloader.killProcess(taskId)
        if (task != null) {
            // Remove every partial artifact so cancelled downloads cannot leave orphaned files
            try {
                val target = File(task.filePath)
                target.delete()
                File(task.filePath + ".part").delete()
                File(task.filePath + ".ytdl").delete()
                File(task.filePath + ".aria2").delete()
                File(task.filePath + ".turbo").delete()
            } catch (_: Throwable) {}
        }
        repository.remove(taskId)
        updateServiceState(force = true)
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
        if (dest.exists() && !dest.isDirectory) {
            try { dest.delete() } catch (_: Exception) {}
        }
        if (createDirs && !dest.exists()) {
            dest.mkdirs()
        }
        return dest
    }

    private fun getRefererForUrl(url: String): String {
        val low = url.lowercase()
        return when {
            low.contains("gogoanime") || low.contains("anitaku") || low.contains("workers.dev") -> "https://gogoanime.or.at/"
            low.contains("asianc") -> "https://asianc.id/"
            // Vidbasic's segment host (hls.vidbasic.top / jisooido.top) allowlists the
            // player origin as Referer and serves an HTML decoy to everything else —
            // any other referer makes every fragment 416 and the download produces an
            // unplayable file (monolith parity, downloader.py get_referer_for_url).
            low.contains("vidbasic") || low.contains("vidb") || low.contains("jisooido") -> "https://vidb.top/"
            low.contains("tamilembed") || low.contains("animesama") || low.contains("kickassanime") -> "https://anitaku.com.ro/"
            low.contains("megap.") -> "https://megaplay.buzz/"
            low.contains("blogger.com") -> "https://anitaku.com.ro/"
            low.contains("googlevideo") -> "https://www.blogger.com/"
            low.contains("pluto") || low.contains("kissorgrab.com") -> "https://plutomovies.com/"
            low.contains("thenkiri") || low.contains("nkiri") -> "https://thenkiri.com/"
            low.contains("9jarocks") || low.contains("loadedfiles") -> "https://my9jarocks.bz/"
            low.contains("naijavault") || low.contains("vikingfile") || low.contains("lulacloud") -> "https://www.naijavault.com/"
            low.contains("naijaprey") -> "https://www.naijaprey.tv/"
            low.contains("dramakey") -> "https://dramakey.com/"
            low.contains("dramarain") -> "https://dramarain.com/"
            else -> ""
        }
    }

    // Adaptive-streaming manifest detection (monolith is_streaming_link parity):
    // DASH/ISM/F4M manifests and player-token URLs must go to yt-dlp, or the
    // segmented downloader grabs manifest XML and produces a corrupt file.
    private fun isStreamingLink(url: String): Boolean {
        val low = url.lowercase()
        if (low.contains(".m3u8") || low.contains("manifest") || low.contains("kickassanime")) return true
        val path = low.substringBefore('?').substringBefore('#')
        if (path.endsWith(".mpd") || path.endsWith(".m3u") || path.endsWith(".ism") || path.endsWith(".f4m")) return true
        return STREAMING_QUERY_PATTERN.containsMatchIn(low)
    }

    companion object {
        private val STREAMING_QUERY_PATTERN = Regex("""[?&][^=&]*=(?:mpd|dash|hls)(?:&|$)""")
    }

    private var lastNotificationTime: Long = 0L
    private val NETWORK_PAUSE_MESSAGE = "Waiting for network..."
    private var lastNetworkTag: String? = null

    private fun updateServiceState(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotificationTime < 1000L) {
            return
        }
        lastNotificationTime = now
        val current = tasks.value
        val active = current.filter { it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.RESOLVING }
        if (active.isNotEmpty()) {
            val first = active.first()
            val pct = if (first.totalBytes > 0) (first.downloadedBytes * 100 / first.totalBytes).toInt().coerceIn(0, 100) else 0
            val speedMb = first.speedBytesPerSec / (1024.0 * 1024.0)
            val speedStr = if (speedMb > 0.05) " • %.1f MB/s".format(java.util.Locale.US, speedMb) else ""
            DownloadService.updateProgress(
                context,
                title = "${first.episodeTitle}$speedStr",
                progress = pct,
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
            // Park every queued task, not just the head, so the whole queue drains to PAUSED
            // in one pass instead of stalling on a single task per processQueue call.
            currentTasks.filter { it.status == TaskStatus.QUEUED }.forEach { queued ->
                repository.update(queued.id) { t -> t.copy(status = TaskStatus.PAUSED, errorMessage = "Storage limit reached (< ${storageGuardGb}GB free)") }
            }
            return
        }

        if (wifiOnlyTorrents && !net.isWifi && nextTask.directUrl.startsWith("magnet:")) {
            repository.update(nextTask.id) { it.copy(status = TaskStatus.PAUSED, errorMessage = "Waiting for Wi-Fi (Torrents Wi-Fi Only enabled)") }
            return
        }

        val isDirect = isDirectMediaUrl(nextTask.directUrl) && !isKnownLockerHost(nextTask.directUrl)
        val initialStatus = if (isDirect) TaskStatus.DOWNLOADING else TaskStatus.RESOLVING
        repository.update(nextTask.id) { it.copy(status = initialStatus) }
        updateServiceState(force = true)

        startTask(nextTask)
    }

    private fun isKnownLockerHost(url: String): Boolean {
        if (url.isBlank()) return false
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
            "strwsh.",
            "stwish.",
            "sfastwish.",
            "vidhide.",
            "kissorgrab.com",
            "nkiserv.com",
            "wildshare.net",
            "vidmoly.",
            "mixdrop.",
            "mixdrp.",
            "streamtape.",
            "pixeldrain.com/u/",
            "vidbasic.",
            "vidb.top",
            "lightdl.cc",
            "5play.cc",
            "megaplay.",
            "blogger.com"
        ).any { lower.contains(it) }
    }

    private suspend fun resolveStreamUrl(permUrl: String, site: String, defaultQual: String): String? {
        // 1. Try direct resolution via ResolverRegistry
        var resolved = ResolverRegistry.resolve(permUrl, defaultQual)
        if (!resolved.isNullOrBlank() && !isKnownLockerHost(resolved)) {
            return resolved
        }

        // 2. Try ProviderRegistry
        if (site.isNotBlank()) {
            try {
                val recipe = ProviderRegistry.resolveEpisode(site, permUrl, defaultQual)
                if (recipe.directUrl.isNotBlank() && recipe.directUrl != permUrl) {
                    resolved = recipe.directUrl
                }
            } catch (_: Exception) {}
        }

        if (resolved.isNullOrBlank() || isKnownLockerHost(resolved)) {
            for (provider in ProviderRegistry.allProviders) {
                if (provider.canHandle(permUrl)) {
                    try {
                        val recipe = provider.resolveEpisode(permUrl, defaultQual)
                        if (recipe.directUrl.isNotBlank() && recipe.directUrl != permUrl) {
                            resolved = recipe.directUrl
                            break
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // 3. Unpack secondary lockers if present. Non-direct URLs that are NOT known
        // lockers are embed/watch pages (e.g. vidsrc.mov): the registry can't crack
        // their token-gated chains, so don't waste fetches — startTask routes them
        // straight to yt-dlp.
        if (!resolved.isNullOrBlank() && isKnownLockerHost(resolved)) {
            try {
                val inner = ResolverRegistry.resolve(resolved, defaultQual)
                if (!inner.isNullOrBlank() && !isKnownLockerHost(inner)) {
                    resolved = inner
                }
            } catch (_: Exception) {}
        }

        return if (!resolved.isNullOrBlank() && !isKnownLockerHost(resolved)) resolved else null
    }

    private fun startTask(task: DownloadTask) {
        val existingJob = activeJobs[task.id]
        if (existingJob != null && existingJob.isActive) {
            return
        }

        val job = engineScope.launch {
            try {
                if (!isActive) return@launch
                var streamUrl = task.directUrl
                val isMagnet = streamUrl.startsWith("magnet:", ignoreCase = true)
                val isSocial = task.showTitle.startsWith("Social/", ignoreCase = true) || task.backend.contains("yt-dlp")
                val permUrl = task.sourceUrl.ifBlank { streamUrl }

                // Zero-Latency Resume: Check if streamUrl is already direct
                val isAlreadyDirect = isDirectMediaUrl(streamUrl) && !isKnownLockerHost(streamUrl)

                if (!isMagnet && !isSocial && !isAlreadyDirect) {
                    repository.update(task.id) { it.copy(status = TaskStatus.RESOLVING) }
                    updateServiceState(force = true)

                    val resolved = resolveStreamUrl(permUrl, task.site, defaultQuality)
                    if (!resolved.isNullOrBlank()) {
                        streamUrl = resolved
                    } else if (isKnownLockerHost(streamUrl) || !isDirectMediaUrl(streamUrl)) {
                        // Our resolver chain came up empty (e.g. a JS-driven watch page
                        // whose embed network is token-gated). Hand the raw URL to
                        // yt-dlp's generic extractor instead of failing outright — it
                        // cracks many embed chains our registry doesn't know.
                        android.util.Log.w("AnonDownload", "Resolver chain empty for $streamUrl, handing to yt-dlp")
                    }
                }

                val isHlsStream = isStreamingLink(streamUrl)
                val isEmbedOrPage = !isMagnet && !isDirectMediaUrl(streamUrl)
                val finalBackend = if (isSocial || isHlsStream || isEmbedOrPage || task.audioOnly) "yt-dlp" else "aria2c"
                val isExtractor = isSocial || task.audioOnly || isEmbedOrPage

                // kissorgrab.com rejects multi-connection downloads; force a single
                // socket there (monolith parity, downloader.py aria2c forced 1/1).
                val effectiveSockets = if (streamUrl.lowercase().contains("kissorgrab.com")) 1 else task.parallelSockets

                coroutineContext.ensureActive()
                repository.update(task.id) { it.copy(status = TaskStatus.DOWNLOADING, directUrl = streamUrl) }
                updateServiceState(force = true)

                val targetFolder = getDownloadDirectory(task.showTitle, createDirs = true)
                val refererToPass = getRefererForUrl(streamUrl)

                var producedFile: File? = null
                var turboFailure: TurboDownloader.TurboResult.Failure? = null

                if (finalBackend == "aria2c" && !isMagnet) {
                    val hdrs = mutableMapOf("User-Agent" to HttpClient.DEFAULT_UA)
                    if (refererToPass.isNotBlank()) hdrs["Referer"] = refererToPass
                    val dest = File(targetFolder, File(task.filePath).name)

                    val progressCb: (Long, Long, Long) -> Unit = { got, tot, bps ->
                        val eta = if (bps > 0 && tot > got) (tot - got) / bps else 0L
                        repository.updateProgress(
                            taskId = task.id,
                            downloaded = got,
                            total = tot,
                            speed = bps.toDouble(),
                            eta = eta
                        )
                        updateServiceState(force = false)
                    }

                    var turboResult: TurboDownloader.TurboResult = TurboDownloader.download(
                        url = streamUrl,
                        dest = dest,
                        headers = hdrs,
                        configuredSockets = effectiveSockets,
                        onProgress = progressCb
                    )

                    if (turboResult is TurboDownloader.TurboResult.Success) {
                        producedFile = turboResult.file
                    } else if (turboResult is TurboDownloader.TurboResult.Failure) {
                        val failure = turboResult
                        turboFailure = failure

                        // Self-healing token refresh: only re-scrape when the CDN rejected the
                        // link outright (expired/token-gated). Transient failures (timeouts,
                        // 5xx, 416) skip the 5-15s resolver chain and fall straight to aria2c.
                        val tokenExpired = failure.httpStatus == 401 || failure.httpStatus == 403 ||
                            failure.httpStatus == 404 || failure.httpStatus == 410
                        if (tokenExpired && coroutineContext.isActive && !isSocial) {
                            android.util.Log.w("AnonDownload", "Direct link rejected (HTTP ${failure.httpStatus}), refreshing stream token...")
                            repository.update(task.id) { it.copy(status = TaskStatus.RESOLVING) }
                            updateServiceState(force = true)

                            val freshUrl = resolveStreamUrl(permUrl, task.site, defaultQuality)
                            if (!freshUrl.isNullOrBlank() && freshUrl != streamUrl) {
                                streamUrl = freshUrl
                                repository.update(task.id) { it.copy(status = TaskStatus.DOWNLOADING, directUrl = streamUrl) }
                                updateServiceState(force = true)

                                val freshReferer = getRefererForUrl(streamUrl)
                                val freshHdrs = mutableMapOf("User-Agent" to HttpClient.DEFAULT_UA)
                                if (freshReferer.isNotBlank()) freshHdrs["Referer"] = freshReferer

                                turboResult = TurboDownloader.download(
                                    url = streamUrl,
                                    dest = dest,
                                    headers = freshHdrs,
                                    configuredSockets = effectiveSockets,
                                    onProgress = progressCb
                                )
                                when (turboResult) {
                                    is TurboDownloader.TurboResult.Success -> producedFile = turboResult.file
                                    is TurboDownloader.TurboResult.Failure -> turboFailure = turboResult
                                }
                            }
                        }
                    }

                    if (producedFile == null && coroutineContext.isActive) {
                        // Turbo → aria2c resume handoff: hand over the longest contiguous
                        // prefix so the fallback continues instead of restarting. Only when
                        // the output name matches what yt-dlp's aria2c will write, and never
                        // over an existing partial (which may carry its own .aria2 resume state).
                        // The sidecar is deleted with the rename: it describes the .part file,
                        // which no longer exists once the prefix becomes the aria2c target.
                        try {
                            val urlExt = streamUrl.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
                            if (urlExt.isNotBlank() && urlExt.equals(File(task.filePath).extension, ignoreCase = true) && !dest.exists()) {
                                val partFile = File(dest.absolutePath + ".part")
                                val prefix = TurboState(File(dest.absolutePath + ".turbo")).contiguousPrefixBytes()
                                if (prefix != null && prefix > 0 && prefix <= partFile.length() && partFile.exists()) {
                                    RandomAccessFile(partFile, "rw").use { it.setLength(prefix) }
                                    if (partFile.renameTo(dest)) {
                                        TurboState(File(dest.absolutePath + ".turbo")).delete()
                                        android.util.Log.w("AnonDownload", "Handed ${prefix / 1024 / 1024} MiB prefix to aria2c for resume")
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                        android.util.Log.w("AnonDownload", "Turbo failed, falling back to aria2c")
                        producedFile = YoutubeDlDownloader.download(
                            context = context,
                            taskId = task.id,
                            sourceUrl = streamUrl,
                            targetDir = targetFolder,
                            preferredFilename = File(task.filePath).name,
                            backend = finalBackend,
                            referer = refererToPass,
                            ua = HttpClient.DEFAULT_UA,
                            parallelSockets = effectiveSockets,
                            quality = defaultQuality,
                            isExtractorTask = false,
                            audioOnly = task.audioOnly,
                            onProgress = { dl, tot, spd, eta ->
                                repository.updateProgress(
                                    taskId = task.id,
                                    downloaded = dl,
                                    total = tot,
                                    speed = spd,
                                    eta = eta
                                )
                                updateServiceState(force = false)
                            }
                        )
                    }

                    // aria2c → Turbo rescue: resume from whichever state is freshest on
                    // disk — Turbo's own sidecar, or aria2c's control file converted to
                    // Turbo's piece map. Only when nothing else produced a file yet.
                    if (producedFile == null && coroutineContext.isActive) {
                        try {
                            val turboState = TurboState(File(dest.absolutePath + ".turbo"))
                            val partFile = File(dest.absolutePath + ".part")
                            var resumable = partFile.exists() && partFile.length() > 0 &&
                                turboState.contiguousPrefixBytes() != null
                            if (!resumable) {
                                val control = File(dest.absolutePath + ".aria2")
                                val parsed = if (control.exists()) Aria2Control.parse(control) else null
                                if (parsed != null && dest.exists() && dest.length() > 0) {
                                    val hadPart = partFile.exists()
                                    if (hadPart) partFile.delete()
                                    if (dest.renameTo(partFile)) {
                                        turboState.commit(parsed.pieces, parsed.fileLength, force = true)
                                        control.delete()
                                        resumable = true
                                    } else if (hadPart) {
                                        // The sidecar describes the deleted .part: without it,
                                        // the map must not survive or the next run would skip
                                        // bytes that are not actually on disk.
                                        turboState.delete()
                                    }
                                }
                            }
                            if (resumable) {
                                android.util.Log.w("AnonDownload", "Resuming partial download with Turbo")
                                val rescueHdrs = mutableMapOf("User-Agent" to HttpClient.DEFAULT_UA)
                                val rescueReferer = getRefererForUrl(streamUrl)
                                if (rescueReferer.isNotBlank()) rescueHdrs["Referer"] = rescueReferer
                                turboResult = TurboDownloader.download(
                                    url = streamUrl,
                                    dest = dest,
                                    headers = rescueHdrs,
                                    configuredSockets = effectiveSockets,
                                    onProgress = progressCb
                                )
                                when (turboResult) {
                                    is TurboDownloader.TurboResult.Success -> producedFile = turboResult.file
                                    is TurboDownloader.TurboResult.Failure -> turboFailure = turboResult
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                } else {
                    producedFile = YoutubeDlDownloader.download(
                        context = context,
                        taskId = task.id,
                        sourceUrl = streamUrl,
                        targetDir = targetFolder,
                        preferredFilename = File(task.filePath).name,
                        backend = finalBackend,
                        referer = refererToPass,
                        ua = HttpClient.DEFAULT_UA,
                        parallelSockets = effectiveSockets.coerceIn(4, 16),
                        quality = defaultQuality,
                        isExtractorTask = isExtractor,
                        audioOnly = task.audioOnly,
                        onProgress = { dl, tot, spd, eta ->
                            repository.updateProgress(
                                taskId = task.id,
                                downloaded = dl,
                                total = tot,
                                speed = spd,
                                eta = eta
                            )
                            updateServiceState(force = false)
                        }
                    )
                }

                val validation = if (producedFile != null && producedFile.exists()) {
                    TorrentSecurityShield.validateDownloadedFile(producedFile, targetFolder)
                } else {
                    Pair(false, "File missing")
                }

                val isAudio = task.filePath.lowercase().let { it.endsWith(".mp3") || it.endsWith(".m4a") || it.endsWith(".aac") }
                val minSize = if (isAudio) 10 * 1024L else 50 * 1024L
                if (producedFile != null && producedFile.exists() && producedFile.length() >= minSize
                    && !looksLikeHtml(producedFile) && validation.first) {
                    val finalTitle = producedFile.nameWithoutExtension
                    val finalBytes = producedFile.length()
                    repository.update(task.id) {
                        it.copy(
                            filePath = producedFile.absolutePath,
                            episodeTitle = if (isExtractor) finalTitle else it.episodeTitle,
                            downloadedBytes = finalBytes,
                            totalBytes = finalBytes,
                            speedBytesPerSec = 0.0,
                            etaSeconds = 0L,
                            status = TaskStatus.COMPLETED
                        )
                    }

                    try {
                        File(producedFile.absolutePath + ".turbo").delete()
                        File(producedFile.absolutePath + ".part").delete()
                        File(producedFile.absolutePath + ".aria2").delete()
                    } catch (_: Throwable) {}

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
                    val errReason = when {
                        producedFile != null && looksLikeHtml(producedFile) -> "Server returned an HTML page instead of the file"
                        !validation.first -> validation.second
                        producedFile == null && turboFailure?.httpStatus != null -> "Download rejected by server (HTTP ${turboFailure.httpStatus}) — retry to refresh the link"
                        producedFile == null -> "Download failed — the server never produced a file"
                        else -> "Output file was too small or corrupted"
                    }
                    repository.update(task.id) { it.copy(status = TaskStatus.FAILED, errorMessage = errReason) }
                }
            } catch (e: CancellationException) {
                // Cancelled
            } catch (e: Exception) {
                if (coroutineContext.isActive) {
                    repository.update(task.id) { it.copy(status = TaskStatus.FAILED, errorMessage = e.message ?: "Download error") }
                } else {
                    // Cancelled (pause/cancel/network park): native-process teardown can surface
                    // as a plain exception, so never report that as FAILED. Also unwedge a task
                    // that was still marked DOWNLOADING when the job was cancelled.
                    repository.update(task.id) { t ->
                        if (t.status == TaskStatus.DOWNLOADING || t.status == TaskStatus.RESOLVING) {
                            t.copy(status = TaskStatus.PAUSED, speedBytesPerSec = 0.0)
                        } else t
                    }
                }
            } finally {
                val thisJob = coroutineContext[Job]
                if (thisJob != null && activeJobs[task.id] === thisJob) {
                    activeJobs.remove(task.id)
                }
                updateServiceState(force = true)
                processQueue()
            }
        }

        activeJobs[task.id] = job
    }
}
