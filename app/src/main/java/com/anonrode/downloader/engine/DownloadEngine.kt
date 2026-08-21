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

    // Tier-A settings (AppSettings-backed): defaults equal the previous
    // hardcoded behavior, so nothing changes until the user opts in.
    var stallTimeoutSec: Int = 60
    var magnetMaxAttempts: Int = 3
    var ytdlpMaxAttempts: Int = 3
    var hlsFragmentConcurrency: Int = 8
    var globalSpeedLimitKbs: Int = 0          // 0 = unlimited
    var torrentPeers: Int = -1                // -1 = auto (RAM tier)
    var wifiOnlyAll: Boolean = false
    var clipboardDetect: Boolean = true
    var completionNotifications: Boolean = true
    var debugLogging: Boolean = false

    // No byte movement (parsed progress or filesystem bytes) for this long while
    // DOWNLOADING means the backend is hung; the watchdog kills it so the retry
    // wrapper can relaunch, and the task eventually FAILED instead of hanging.
    private val STALL_TIMEOUT_MS: Long
        get() = stallTimeoutSec * 1000L

    /**
     * Set by the UI: called when a torrent's swarm exposes more than one safe
     * file (season packs). Receives the shield-checked file list and must
     * return the selected 1-based aria2c indexes, or null to download the
     * whole torrent. Runs on the engine's IO dispatcher — the UI should
     * bridge it to the main thread (e.g. a Compose dialog).
     */
    var onTorrentFileSelection: (suspend (List<TorrentSecurityShield.TorrentFileEntry>) -> List<Int>?)? = null

    val tasks: StateFlow<List<DownloadTask>> = repository.tasks

    init {
        loadPreferences()
        engineScope.launch {
            // Auto-rescue tasks interrupted by app kill/crash. VALIDATING is
            // included: a process death mid-check otherwise leaves the task
            // stuck in VALIDATING forever (nothing ever transitions it).
            val currentTasks = repository.tasks.value
            currentTasks.forEach { t ->
                if (t.status == TaskStatus.DOWNLOADING || t.status == TaskStatus.RESOLVING || t.status == TaskStatus.VALIDATING) {
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
        // Storage-guard self-heal: tasks parked by the storage limit have no
        // natural resume trigger (the network collector only matches network
        // messages), so a separate loop re-checks periodically and re-queues
        // them when space frees up instead of leaving the queue stuck until
        // manual resume. Runs in its own coroutine — the collector above never
        // returns, so this must not be sequenced after it.
        engineScope.launch {
            while (true) {
                delay(10_000)
                if (!checkStorageAvailable()) continue
                val parked = repository.tasks.value.filter {
                    it.status == TaskStatus.PAUSED && it.errorMessage?.startsWith("Storage limit reached") == true
                }
                if (parked.isNotEmpty()) {
                    parked.forEach { repository.update(it.id) { t -> t.copy(status = TaskStatus.QUEUED, errorMessage = null) } }
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

        stallTimeoutSec = prefs.getInt("pref_stall_timeout", 60)
        magnetMaxAttempts = prefs.getInt("pref_magnet_retries", 3)
        ytdlpMaxAttempts = prefs.getInt("pref_ytdlp_retries", 3)
        hlsFragmentConcurrency = prefs.getInt("pref_hls_fragments", 8)
        globalSpeedLimitKbs = prefs.getInt("pref_speed_limit_kbs", 0)
        torrentPeers = prefs.getInt("pref_torrent_peers", -1)
        wifiOnlyAll = prefs.getBoolean("pref_wifi_only_all", false)
        clipboardDetect = prefs.getBoolean("pref_clipboard_detect", true)
        completionNotifications = prefs.getBoolean("pref_completion_notifications", true)
        debugLogging = prefs.getBoolean("pref_debug_logging", false)
    }

    fun setShowPosters(show: Boolean) {
        this.showPostersInResults = show
        context.getSharedPreferences("downloader_settings", Context.MODE_PRIVATE).edit()
            .putBoolean("pref_show_posters", show)
            .apply()
    }

    /** Persists the instant-social toggle so it survives restarts (SocialModal). */
    fun setInstantSocial(enabled: Boolean) {
        this.instantSocialDownload = enabled
        context.getSharedPreferences("downloader_settings", Context.MODE_PRIVATE).edit()
            .putBoolean("pref_instant_social", enabled)
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
        showPosters: Boolean = true,
        // Tier-A settings
        stallTimeout: Int = 60,
        magnetRetries: Int = 3,
        ytdlpRetries: Int = 3,
        hlsFragments: Int = 8,
        speedLimit: Int = 0,
        peers: Int = -1,
        wifiAll: Boolean = false,
        clipboard: Boolean = true,
        notifications: Boolean = true,
        debugLog: Boolean = false
    ) {
        this.maxConcurrentDownloads = maxConcurrent
        this.parallelSocketsPerFile = parallelSockets
        this.defaultQuality = quality
        this.autoOrganizeByShow = autoOrganize
        this.storageGuardGb = storageGuard
        this.wifiOnlyTorrents = wifiOnlyTorrents
        this.instantSocialDownload = instantSocial
        this.showPostersInResults = showPosters
        this.stallTimeoutSec = stallTimeout
        this.magnetMaxAttempts = magnetRetries
        this.ytdlpMaxAttempts = ytdlpRetries
        this.hlsFragmentConcurrency = hlsFragments
        this.globalSpeedLimitKbs = speedLimit
        this.torrentPeers = peers
        this.wifiOnlyAll = wifiAll
        this.clipboardDetect = clipboard
        this.completionNotifications = notifications
        this.debugLogging = debugLog

        context.getSharedPreferences("downloader_settings", Context.MODE_PRIVATE).edit()
            .putInt("pref_max_downloads", maxConcurrent)
            .putInt("pref_parallel_sockets", parallelSockets)
            .putString("pref_default_quality", quality)
            .putBoolean("pref_auto_organize", autoOrganize)
            .putFloat("pref_storage_guard", storageGuard.toFloat())
            .putBoolean("pref_torrents_wifi_only", wifiOnlyTorrents)
            .putBoolean("pref_instant_social", instantSocial)
            .putBoolean("pref_show_posters", showPosters)
            .putInt("pref_stall_timeout", stallTimeout)
            .putInt("pref_magnet_retries", magnetRetries)
            .putInt("pref_ytdlp_retries", ytdlpRetries)
            .putInt("pref_hls_fragments", hlsFragments)
            .putInt("pref_speed_limit_kbs", speedLimit)
            .putInt("pref_torrent_peers", peers)
            .putBoolean("pref_wifi_only_all", wifiAll)
            .putBoolean("pref_clipboard_detect", clipboard)
            .putBoolean("pref_completion_notifications", notifications)
            .putBoolean("pref_debug_logging", debugLog)
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
        site: String = "",
        quality: String? = null
    ): String {
        val taskId = UUID.randomUUID().toString()
        val downloadFolder = getDownloadDirectory(showTitle, createDirs = false)

        // Dedupe: never queue a second task for the same source URL while one
        // is already active or paused. Two tasks sharing a filePath write the
        // same .part concurrently and corrupt the output.
        val active = setOf(TaskStatus.QUEUED, TaskStatus.RESOLVING, TaskStatus.DOWNLOADING, TaskStatus.VALIDATING, TaskStatus.PAUSED)
        repository.snapshot().firstOrNull { it.sourceUrl == sourceUrl && it.status in active }?.let { return it.id }

        val cleanTitle = episodeTitle.replace(Regex("""[^a-zA-Z0-9._ -]"""), "_").trim()
        val ext = if (audioOnly) "mp3" else if (backend.contains("yt") || !isDirect) "mp4" else "mkv"

        // Uniquify the target filename so two different sources with the same
        // title (e.g. same episode from two sites) never write one filePath.
        var targetFile = File(downloadFolder, "$cleanTitle.$ext")
        var counter = 2
        while (repository.snapshot().any { it.filePath == targetFile.absolutePath }) {
            targetFile = File(downloadFolder, "$cleanTitle-$counter.$ext")
            counter++
        }

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
            audioOnly = audioOnly,
            quality = quality
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
                if (n <= 0) return false
                // Strip UTF-8 BOM (U+FEFF) before prefix matching so BOM-prefixed
                // HTML error pages are caught, then decode and normalize.
                var start = 0
                if (n >= 3 && buf[0] == 0xEF.toByte() && buf[1] == 0xBB.toByte() && buf[2] == 0xBF.toByte()) {
                    start = 3
                }
                String(buf, start, n - start).trimStart().lowercase()
            }
            head.startsWith("<!doctype html") || head.startsWith("<html") || head.startsWith("<head") ||
                head.startsWith("<body") || head.startsWith("<!--") || head.startsWith("<script") ||
                head.startsWith("<svg") || head.startsWith("<?xml") || head.startsWith("<style") ||
                head.startsWith("<iframe") || head.startsWith("<meta") || head.startsWith("<form") ||
                head.startsWith("{")
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Actual bytes this task has written to disk: the final file, its .part /
     * .ytdl partials, and yt-dlp's concurrent fragment files. The UI progress
     * watchdog uses this as the source of truth when backend output parsing
     * reports nothing, and it is what stall detection compares against.
     */
    private fun computeDiskBytes(task: DownloadTask): Long {
        return try {
            val dir = File(task.filePath).parentFile ?: return 0L
            val files = dir.listFiles() ?: return 0L
            val base = File(task.filePath).name
            // Segmented Turbo downloads pre-allocate the .part file to its full
            // size before a single byte is written, so file.length() is not a
            // progress signal there. When a .turbo sidecar exists, the piece map
            // is the source of truth: sum committed offsets, ignore the zeros.
            val sidecar = File(dir, base + ".turbo")
            if (sidecar.exists()) {
                val written = TurboState(sidecar).writtenBytes() ?: return 0L
                return written + files.sumOf { f ->
                    if (!f.isFile) 0L
                    else if (f.name == base) f.length()
                    else if (f.name.endsWith(".ytdl")) f.length()
                    else 0L
                }
            }
            files.sumOf { f ->
                if (!f.isFile) 0L
                else if (f.name.startsWith(base) && !f.name.endsWith(".turbo")) f.length()
                else if (f.name.contains(".part") || f.name.endsWith(".ytdl")) f.length()
                else 0L
            }
        } catch (_: Exception) {
            0L
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

        // Stall handling: a window must move at least this many bytes to count
        // as live progress (HLS CDNs throttle to ~1 KB/s instead of dying; the
        // crawl is a stall in disguise). 64 KiB per 60s window ≈ 1 KiB/s floor.
        private const val CRAWL_WINDOW_BYTES = 64L * 1024

        // Kill the backend this many times before giving up. The yt-dlp wrapper
        // retries 3x, then the engine re-resolves a fresh URL (rotating token
        // and edge node) for another 3 attempts — the recovery chain needs room.
        private const val MAX_STALL_KILLS = 6
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
        val activeCount = currentTasks.count {
            it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.RESOLVING || it.status == TaskStatus.VALIDATING
        }

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
        // Wi-Fi-only for ALL downloads (not just torrents)
        if (wifiOnlyAll && !net.isWifi) {
            repository.update(nextTask.id) { it.copy(status = TaskStatus.PAUSED, errorMessage = "Waiting for Wi-Fi (Wi-Fi Only enabled)") }
            return
        }

        val isDirect = isDirectMediaUrl(nextTask.directUrl) && !isKnownLockerHost(nextTask.directUrl)
        val initialStatus = if (isDirect) TaskStatus.DOWNLOADING else TaskStatus.RESOLVING
        repository.update(nextTask.id) { it.copy(status = initialStatus) }
        updateServiceState(force = true)

        startTask(nextTask)
    }

    /**
     * A URL that is provably a direct file rather than a page to crack, even
     * when its host appears in [isKnownLockerHost]'s list: pixeldrain's API
     * endpoint and token-carrying CDN links (?pt= / ?token= / ?download) are
     * resolver *outputs* — the cracking already happened — so exempting them
     * lets genuine direct links through instead of discarding them (the probe
     * in TurboDownloader still rejects any server that lies and serves HTML).
     */
    private fun isProvablyDirectFile(url: String): Boolean {
        val lower = url.lowercase()
        val path = lower.substringAfter("://", "").substringBefore('?').substringBefore('#')
        if (path.contains("/api/file/")) return true
        val query = lower.substringAfter('?', "").substringBefore('#')
        return query.contains("pt=") || query.contains("token=") || query.contains("download")
    }

    private fun isKnownLockerHost(url: String): Boolean {
        if (url.isBlank()) return false
        if (isProvablyDirectFile(url)) return false
        val lower = url.lowercase()
        // Host-based, not extension-based: locker pages carry the media filename
        // in their path (loadedfiles.net/.../Episode.mkv), so a .mkv/.mp4 suffix
        // must NOT exempt them from resolution — the host decides whether a URL
        // is a page to crack or a direct file.
        val host = lower.substringAfter("://", "").substringBefore('/').substringBefore(':')
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
            "pixeldrain.com",
            "vidbasic.",
            "vidb.top",
            "lightdl.cc",
            "5play.cc",
            "megaplay.",
            "blogger.com"
        ).any { host.contains(it) }
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
        com.anonrode.downloader.util.DebugLog.write("start task=${task.id} url=${task.directUrl.take(80)} backend=${task.backend}")

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

                    val resolved = resolveStreamUrl(permUrl, task.site, task.quality ?: defaultQuality)
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
                // A known locker host is never a direct file, even when its page
                // URL ends in a media extension — route it through yt-dlp rather
                // than downloading the page as a video. Resolver outputs that are
                // provably direct (pixeldrain API, ?pt= / ?token= CDN links) are
                // exempt: they are the cracked file itself, so Turbo may grab them.
                val isEmbedOrPage = !isMagnet && !isProvablyDirectFile(streamUrl) &&
                    (!isDirectMediaUrl(streamUrl) || isKnownLockerHost(streamUrl))
                val finalBackend = if (isSocial || isHlsStream || isEmbedOrPage || task.audioOnly) "yt-dlp" else "aria2c"
                val isExtractor = isSocial || task.audioOnly || isEmbedOrPage

                // kissorgrab.com rejects multi-connection downloads; force a single
                // socket there (monolith parity, downloader.py aria2c forced 1/1).
                val effectiveSockets = if (streamUrl.lowercase().contains("kissorgrab.com")) 1 else task.parallelSockets

                coroutineContext.ensureActive()
                repository.update(task.id) { it.copy(status = TaskStatus.DOWNLOADING, directUrl = streamUrl) }
                updateServiceState(force = true)

                // Progress watchdog: keeps the UI honest with filesystem truth and
                // kills a stalled backend so a hung download cannot stay DOWNLOADING
                // forever with 0.0 MB/s. Runs until the job is paused/cancelled or
                // the task leaves DOWNLOADING.
                launch {
                    var lastDisk = 0L
                    var lastParsed = 0L
                    var lastActivity = System.currentTimeMillis()
                    var stallKills = 0
                    // Crawl detection: some HLS CDNs (vidsrc edge nodes) throttle a
                    // connection to ~1 KB/s instead of dying. Bytes still move, so
                    // lastActivity stays fresh and the download would otherwise
                    // crawl for days. Track movement per window; below the floor
                    // it counts as stalled and the backend is relaunched (fresh
                    // token/edge on re-resolve), exactly like the monolith's
                    // idle-timeout handling.
                    var windowStartDisk = 0L
                    var windowStartParsed = 0L
                    var windowStartTime = System.currentTimeMillis()
                    while (isActive) {
                        delay(2000)
                        if (!isActive) break
                        val t = repository.find(task.id) ?: break
                        if (t.status != TaskStatus.DOWNLOADING) continue
                        val now = System.currentTimeMillis()
                        val disk = computeDiskBytes(t)
                        val parsed = t.downloadedBytes
                        val diskDelta = disk - lastDisk
                        val diskGrew = disk > lastDisk
                        val parsedGrew = parsed > lastParsed
                        if (diskGrew) lastDisk = disk
                        if (parsedGrew) lastParsed = parsed
                        if (diskGrew || parsedGrew) lastActivity = now
                        // Feed the UI from the filesystem when the backend reports
                        // nothing (fragment downloads, hung output parsing).
                        if (disk > t.downloadedBytes) {
                            val speed = if (t.speedBytesPerSec > 0.0) t.speedBytesPerSec
                            else diskDelta.coerceAtLeast(0L) * 1000.0 / 2000.0
                            repository.updateProgress(
                                taskId = task.id,
                                downloaded = disk,
                                total = t.totalBytes,
                                speed = speed,
                                eta = 0L
                            )
                        }
                        // Window progress: healthy downloads blow through the floor
                        // in seconds; a crawl never reaches it.
                        val moved = (disk - windowStartDisk) + (parsed - windowStartParsed)
                        if (moved >= CRAWL_WINDOW_BYTES) {
                            windowStartDisk = disk
                            windowStartParsed = parsed
                            windowStartTime = now
                            stallKills = 0 // real progress — recovery worked
                        }
                        val crawlStalled = now - windowStartTime > STALL_TIMEOUT_MS
                        // A task with no meaningful byte movement (parsed or on
                        // disk) for a full minute is stalled: kill the backend so
                        // the retry wrapper relaunches it (fresh token/edge on
                        // re-resolve), and eventually FAILED instead of hanging.
                        if (now - lastActivity > STALL_TIMEOUT_MS || crawlStalled) {
                            stallKills++
                            android.util.Log.w("AnonDownload", "No download progress for ${t.episodeTitle}, (stall kill $stallKills)")
                            YoutubeDlDownloader.killProcess(task.id)
                            // Turbo runs in OkHttp, not a native process: interrupt
                            // its in-flight calls so a stalled transfer cannot hang
                            // in DOWNLOADING forever (the piece retry policy then
                            // fails the task instead of re-arming the watchdog).
                            TurboDownloader.cancelTask(task.id)
                            // The yt-dlp wrapper retries 3x, then the engine
                            // re-resolves a fresh URL (rotating token/edge) for
                            // another 3 attempts. Allow that recovery chain to
                            // play out; only then give up and fail the task.
                            if (stallKills >= MAX_STALL_KILLS) {
                                repository.update(task.id) {
                                    it.copy(status = TaskStatus.FAILED, errorMessage = "Download stalled — no progress for ${stallTimeoutSec}s across $stallKills attempts")
                                }
                                activeJobs[task.id]?.cancel()
                                break
                            }
                            lastActivity = now
                            windowStartDisk = disk
                            windowStartParsed = parsed
                            windowStartTime = now
                        }
                    }
                }

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
                        onProgress = progressCb,
                        taskId = task.id
                    )

                    if (turboResult is TurboDownloader.TurboResult.Success) {
                        producedFile = turboResult.file
                    } else if (turboResult is TurboDownloader.TurboResult.Failure) {
                        val failure = turboResult
                        turboFailure = failure

                        // Self-healing token refresh: only re-scrape when the CDN rejected the
                        // link outright (expired/token-gated) or the probe proved it serves an
                        // HTML page (locker page misrouted, expired download_token redirecting
                        // to an error page). Transient failures (timeouts, 5xx, 416) skip the
                        // 5-15s resolver chain and fall straight to aria2c.
                        val tokenExpired = failure.httpStatus == 401 || failure.httpStatus == 403 ||
                            failure.httpStatus == 404 || failure.httpStatus == 410 || failure.htmlPage
                        if (tokenExpired && coroutineContext.isActive && !isSocial) {
                            android.util.Log.w("AnonDownload", "Direct link rejected (HTTP ${failure.httpStatus}), refreshing stream token...")
                            repository.update(task.id) { it.copy(status = TaskStatus.RESOLVING) }
                            updateServiceState(force = true)

                            val freshUrl = resolveStreamUrl(permUrl, task.site, task.quality ?: defaultQuality)
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
                                    onProgress = progressCb,
                                    taskId = task.id
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
                            quality = task.quality ?: defaultQuality,
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
                            },
                            magnetMaxAttempts = magnetMaxAttempts,
                            ytdlpMaxAttempts = ytdlpMaxAttempts,
                            hlsFragments = hlsFragmentConcurrency,
                            speedLimitKbs = globalSpeedLimitKbs,
                            torrentPeers = torrentPeers
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
                                    onProgress = progressCb,
                                    taskId = task.id
                                )
                                when (turboResult) {
                                    is TurboDownloader.TurboResult.Success -> producedFile = turboResult.file
                                    is TurboDownloader.TurboResult.Failure -> turboFailure = turboResult
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                } else {
                    suspend fun runYtdlp(url: String): File? = YoutubeDlDownloader.download(
                        context = context,
                        taskId = task.id,
                        sourceUrl = url,
                        targetDir = targetFolder,
                        preferredFilename = File(task.filePath).name,
                        backend = finalBackend,
                        referer = getRefererForUrl(url),
                        ua = HttpClient.DEFAULT_UA,
                        parallelSockets = effectiveSockets.coerceIn(4, 16),
                        quality = task.quality ?: defaultQuality,
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
                        },
                        onTorrentFiles = onTorrentFileSelection,
                        magnetMaxAttempts = magnetMaxAttempts,
                        ytdlpMaxAttempts = ytdlpMaxAttempts,
                        hlsFragments = hlsFragmentConcurrency,
                        speedLimitKbs = globalSpeedLimitKbs,
                        torrentPeers = torrentPeers
                    )

                    producedFile = runYtdlp(streamUrl)
                    // HLS/embed tokenized URLs (master.m3u8?token=...) expire
                    // mid-flight: on failure, re-resolve for a fresh URL and try
                    // once more, mirroring the token refresh on the direct path.
                    if (producedFile == null && coroutineContext.isActive) {
                        val freshUrl = resolveStreamUrl(permUrl, task.site, task.quality ?: defaultQuality)
                        if (!freshUrl.isNullOrBlank() && freshUrl != streamUrl) {
                            android.util.Log.w("AnonDownload", "yt-dlp failed, re-resolving for a fresh URL")
                            streamUrl = freshUrl
                            producedFile = runYtdlp(freshUrl)
                        }
                    }
                }

                // A paused or cancelled job must never flip to a final state: the
                // download may have finished right as pause landed. The exception
                // propagates as CancellationException, which the catch below treats
                // as silent, leaving the PAUSED status intact.
                coroutineContext.ensureActive()

                if (producedFile != null && producedFile.exists()) {
                    repository.update(task.id) { it.copy(status = TaskStatus.VALIDATING) }
                    updateServiceState(force = true)
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
                    // The block above ran without suspension points, so a pause
                    // landing mid-validation could not interrupt it. Re-check
                    // here: a cancelled job must never flip to COMPLETED after
                    // the user pressed pause.
                    coroutineContext.ensureActive()
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

                    if (completionNotifications) {
                        DownloadService.notifyCompleted(context, finalTitle)
                    }
                    com.anonrode.downloader.util.DebugLog.write("completed task=${task.id} file=${producedFile.absolutePath} bytes=$finalBytes")
                } else {
                    val errReason = when {
                        producedFile != null && looksLikeHtml(producedFile) -> "Server returned an HTML page instead of the file"
                        !validation.first -> validation.second
                        producedFile == null && turboFailure?.htmlPage == true -> "Server returned an HTML page instead of the file — the link expired; retry to refresh it"
                        producedFile == null && turboFailure?.httpStatus != null -> "Download rejected by server (HTTP ${turboFailure.httpStatus}) — retry to refresh the link"
                        producedFile == null -> "Download failed — the server never produced a file"
                        else -> "Output file was too small or corrupted"
                    }
                    repository.update(task.id) { it.copy(status = TaskStatus.FAILED, errorMessage = errReason) }
                    com.anonrode.downloader.util.DebugLog.write("failed task=${task.id} reason=$errReason")
                }
            } catch (e: CancellationException) {
                // Cancelled. A pause landing during VALIDATING (after the first
                // ensureActive passed but before the terminal write) leaves the
                // status at VALIDATING — rescue it to PAUSED so the card is not
                // stuck mid-check.
                repository.update(task.id) { t ->
                    if (t.status == TaskStatus.DOWNLOADING || t.status == TaskStatus.RESOLVING || t.status == TaskStatus.VALIDATING) {
                        t.copy(status = TaskStatus.PAUSED, speedBytesPerSec = 0.0)
                    } else t
                }
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
