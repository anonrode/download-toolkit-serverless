package com.anonrode.downloader.engine

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.pipeline.PipelineError
import com.anonrode.downloader.pipeline.StreamValidator
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
import java.util.regex.Pattern

enum class DramaCountry {
    KOREAN,
    CHINESE,
    JAPANESE,
    THAI,
    TAIWANESE,
    WESTERN,
    UNKNOWN
}

class DownloadEngine(
    private val context: Context,
    private val repository: DownloadRepository,
    private val networkObserver: NetworkObserver = NetworkObserver(context)
) {
    private val ASIAN_DRAMA_CLUSTER = com.anonrode.downloader.pipeline.HostPolicyRegistry.ASIAN_DRAMA_CLUSTER
    private val WESTERN_CLUSTER = com.anonrode.downloader.pipeline.HostPolicyRegistry.WESTERN_CLUSTER
    private val DEDICATED_ASIAN_SITES = com.anonrode.downloader.pipeline.HostPolicyRegistry.DEDICATED_ASIAN_SITES
    // Secondary Asian-failover targets: mixed Western-cluster sites with PROVEN
    // Asian catalogs (coverage probe 2026-09-20: 50 random Asian series,
    // search-only — 9jarocks 98%, nkiri 62%, and a 6/6 rescue rate on titles
    // ALL dedicated sites missed). Reached ONLY by Asian tasks, and tried at
    // EQUAL RANK with Asian-cluster cards: exact title match + HostHealth
    // decide (deliberate choice — no secondary-tier penalty). Western tasks
    // never touch the dedicated sites; that one-way door stays shut.
    private val SECONDARY_ASIAN_FALLBACK = com.anonrode.downloader.pipeline.HostPolicyRegistry.SECONDARY_ASIAN_FALLBACK
    private val YEAR_REGEX = Regex("""\b(19\d\d|20[0-3]\d)\b""")

    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val runtimeRegistry = TaskRuntimeRegistry()
    private val progressWatchdog by lazy {
        ProgressWatchdog(
            repository = repository,
            computeVerifiedDiskBytes = { t -> computeDiskBytes(t) },
            onStallKill = { taskId, kills, exhausted, stallMsg, t ->
                handleWatchdogStall(taskId, kills, exhausted, stallMsg, t)
            }
        )
    }

    @Volatile var maxConcurrentDownloads: Int = 4
    @Volatile var parallelSocketsPerFile: Int = 16
    @Volatile var defaultQuality: String = "720p"
    @Volatile var autoOrganizeByShow: Boolean = true
    @Volatile var instantSocialDownload: Boolean = false
    @Volatile var wifiOnlyTorrents: Boolean = false
    var downloadTorrentsWifiOnly: Boolean
        get() = wifiOnlyTorrents
        set(value) { wifiOnlyTorrents = value }
    @Volatile var showPostersInResults: Boolean = true
    @Volatile var storageGuardGb: Double = 1.0

    // Tier-A settings (AppSettings-backed): defaults equal the previous
    // hardcoded behavior, so nothing changes until the user opts in.
    @Volatile var stallTimeoutSec: Int = 60
    @Volatile var magnetMaxAttempts: Int = 3
    @Volatile var ytdlpMaxAttempts: Int = 3
    @Volatile var hlsFragmentConcurrency: Int = 16
    @Volatile var globalSpeedLimitKbs: Int = 0          // 0 = unlimited
    @Volatile var torrentPeers: Int = -1                // -1 = auto (RAM tier)
    @Volatile var torrentPrivacyMode: Boolean = false   // qBittorrent anonymous-mode lessons
    @Volatile var wifiOnlyAll: Boolean = false
    @Volatile var clipboardDetect: Boolean = true
    @Volatile var completionNotifications: Boolean = true
    @Volatile var debugLogging: Boolean = false
    @Volatile var logRetentionDays: Int = 7
    // Seal-parity subtitle settings (2026-09-14): yt-dlp extractor downloads
    // fetch subtitles in this language (default English) and embed them in
    // the merged file. Both live in downloader_settings like the rest.
    @Volatile var downloadSubtitles: Boolean = true
    @Volatile var subtitleLanguage: String = "en"
    @Volatile var filterExplicitContent: Boolean = true


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
    var onTorrentFileSelection: (suspend (List<TorrentSecurityShield.TorrentFileEntry>) -> com.anonrode.downloader.ui.screens.TorrentFilePicker.TorrentPickResult)? = null

    val tasks: StateFlow<List<DownloadTask>> = repository.tasks

    init {
        loadPreferences()
        engineScope.launch {
            // Park tasks interrupted by app kill/crash — never re-queue them.
            // The repository restore (initPersistence) already mapped
            // mid-flight statuses to PAUSED before the engine was built, so
            // this is defense in depth for any future init reordering: a
            // reopen must never auto-resume anything, including a task caught
            // mid-VALIDATING (a pause there is a real user pause too, and the
            // file on disk means a manual resume re-validates in seconds).
            val currentTasks = repository.tasks.value
            currentTasks.forEach { t ->
                // QUEUED included: the repository restore parks it too, but a
                // task persisted as QUEUED between enqueue and restore (async
                // persist race) must not reach the network observer below,
                // whose first emission auto-starts every QUEUED task the
                // moment the app opens.
                if (t.status == TaskStatus.DOWNLOADING || t.status == TaskStatus.RESOLVING ||
                    t.status == TaskStatus.VALIDATING || t.status == TaskStatus.QUEUED) {
                    repository.update(t.id) { it.copy(status = TaskStatus.PAUSED, speedBytesPerSec = 0.0, errorMessage = null, userPaused = true) }
                }
            }
            networkObserver.status.collect { net ->
                if (net.isConnected) {
                    if (lastNetworkTag != null && net.networkTag != null && lastNetworkTag != net.networkTag) {
                        // Network identity changed while still connected (Wi-Fi -> mobile, VPN toggle):
                        // restart active jobs losslessly on the new network instead of letting
                        // their sockets stall against the dead one.
                        runtimeRegistry.activeRunningTaskIds().forEach { id -> pauseForNetwork(id) }
                    }
                    lastNetworkTag = net.networkTag
                    // Auto-resume tasks parked by a connectivity drop.
                    // userPaused tasks are excluded everywhere in this
                    // collector: a pause the user asked for must never be
                    // undone by a network event.
                    repository.tasks.value
                        .filter { it.status == TaskStatus.PAUSED && !it.userPaused && it.errorMessage == NETWORK_PAUSE_MESSAGE }
                        .forEach { repository.update(it.id) { t ->
                            // Re-check INSIDE the transform (engine-audit P1): the
                            // filter above froze userPaused at snapshot time, so a
                            // pause landing between snapshot and write would be
                            // overwritten and the download would resume anyway.
                            if (t.userPaused || t.status != TaskStatus.PAUSED) t
                            else t.copy(status = TaskStatus.QUEUED, errorMessage = null)
                        } }
                    // Wi-Fi-gated torrents resume only once an actual Wi-Fi network is back
                    repository.tasks.value
                        .filter { it.status == TaskStatus.PAUSED && !it.userPaused && it.errorMessage?.startsWith("Waiting for Wi-Fi") == true && net.isWifi }
                        .forEach { repository.update(it.id) { t ->
                            if (t.userPaused || t.status != TaskStatus.PAUSED) t
                            else t.copy(status = TaskStatus.QUEUED, errorMessage = null)
                        } }
                    processQueue()
                } else {
                    // Park active jobs so a reconnect resumes them instead of failing them
                    lastNetworkTag = null
                    runtimeRegistry.activeRunningTaskIds().forEach { id -> pauseForNetwork(id) }
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
                    it.status == TaskStatus.PAUSED && !it.userPaused && it.errorMessage?.startsWith("Storage limit reached") == true
                }
                if (parked.isNotEmpty()) {
                    parked.forEach { repository.update(it.id) { t ->
                        if (t.userPaused || t.status != TaskStatus.PAUSED) t
                        else t.copy(status = TaskStatus.QUEUED, errorMessage = null)
                    } }
                    processQueue()
                }
            }
        }
        // Cooldown-parked auto-retry: a task parked because its locker host
        // is in HostHealth backoff re-queues itself once the host recovers —
        // nothing else resumes it (the network collector only matches network
        // messages). In-memory only: an app restart leaves it PAUSED for a
        // manual resume (DownloadRepository restore), which is intended.
        //
        // Give-up cap: a task parked for longer than PARKED_LIFETIME_MS total
        // (measured from its parkedAt timestamp) is FAILED instead of bounced
        // forever — the Suits batch spent 90 minutes orbiting a host that was
        // never coming back. Manual retry always restarts it cleanly.
        // EXEMPTION (user rule 2026-09-14, "if a download ever starts there is
        // no reason to cancel it"): the cap only applies to tasks that NEVER
        // landed a byte. A parked task with bytes on disk is saved progress —
        // it waits out the host instead of being failed and forgotten.
        //
        // Trickle requeue: when MANY tasks are parked on the same host, requeue
        // at most a few per tick. Releasing all 18 at once recreates the exact
        // request storm that triggered the backoff in the first place.
        engineScope.launch {
            while (true) {
                delay(10_000)
                val parked = repository.tasks.value.filter {
                    it.status == TaskStatus.PAUSED && !it.userPaused && it.errorMessage?.startsWith(PARKED_HOST_MESSAGE) == true
                }
                if (parked.isEmpty()) continue
                var requeued = 0
                var anyRequeued = false
                parked.forEach { t ->
                    val entry = runtimeRegistry.get(t.id)
                    val parkedAt = entry?.firstParkedAtMs?.get() ?: 0L
                    if (parkedAt > 0L && System.currentTimeMillis() - parkedAt > PARKED_LIFETIME_MS &&
                        maxOf(t.downloadedBytes, computeDiskBytes(t)) == 0L
                    ) {
                        entry?.resetParkingAndFailoverState()
                        val msg = "Download server stayed unreachable for 30+ minutes — gave up. Tap retry once the site is back."
                        repository.update(t.id) { it.copy(status = TaskStatus.FAILED, errorMessage = msg, speedBytesPerSec = 0.0) }
                        com.anonrode.downloader.util.DebugLog.user("park give-up task=${t.id}")
                        try {
                            com.anonrode.downloader.service.DownloadService.notifyFailed(
                                context, t.id, t.episodeTitle, msg
                            )
                        } catch (_: Exception) {}
                        return@forEach
                    }
                    // Re-queue floor: parks escalate their wait (see parkFloorMs)
                    // so a repeatedly-rejected host is probed at most once per
                    // window instead of every tick.
                    if (System.currentTimeMillis() < (entry?.requeueNotBeforeMs?.get() ?: 0L)) return@forEach
                    if (com.anonrode.downloader.pipeline.HostHealth.isUsable(t.directUrl)) {
                        // Max 2 requeues per 10s tick: a 18-task batch comes
                        // back over ~90s instead of one thundering herd.
                        if (requeued >= 2) return@forEach
                        requeued++
                        entry?.firstParkedAtMs?.set(0L)
                        entry?.requeueNotBeforeMs?.set(0L)
                        repository.update(t.id) { task ->
                            if (task.userPaused || task.status != TaskStatus.PAUSED) task
                            else task.copy(status = TaskStatus.QUEUED, errorMessage = null)
                        }
                        anyRequeued = true
                    }
                }
                if (anyRequeued) processQueue()
            }
        }
    }

    private fun loadPreferences() {
        val prefs = context.getSharedPreferences("downloader_settings", Context.MODE_PRIVATE)
        maxConcurrentDownloads = prefs.getInt("pref_max_downloads", 4).coerceIn(1, 6)
        parallelSocketsPerFile = prefs.getInt("pref_parallel_sockets", 16).coerceIn(1, 16)
        defaultQuality = prefs.getString("pref_default_quality", "720p") ?: "720p"
        autoOrganizeByShow = prefs.getBoolean("pref_auto_organize", true)
        instantSocialDownload = prefs.getBoolean("pref_instant_social", false)
        wifiOnlyTorrents = prefs.getBoolean("pref_torrents_wifi_only", false)
        showPostersInResults = prefs.getBoolean("pref_show_posters", true)
        storageGuardGb = prefs.getFloat("pref_storage_guard", 1.0f).toDouble()

        stallTimeoutSec = prefs.getInt("pref_stall_timeout", 60)
        magnetMaxAttempts = prefs.getInt("pref_magnet_retries", 3)
        ytdlpMaxAttempts = prefs.getInt("pref_ytdlp_retries", 3)
        hlsFragmentConcurrency = prefs.getInt("pref_hls_fragments", 16)
        globalSpeedLimitKbs = prefs.getInt("pref_speed_limit_kbs", 0)
        torrentPeers = prefs.getInt("pref_torrent_peers", -1)
        torrentPrivacyMode = prefs.getBoolean("pref_torrent_privacy_mode", false)
        wifiOnlyAll = prefs.getBoolean("pref_wifi_only_all", false)
        clipboardDetect = prefs.getBoolean("pref_clipboard_detect", true)
        completionNotifications = prefs.getBoolean("pref_completion_notifications", true)
        debugLogging = prefs.getBoolean("pref_debug_logging", false)
        logRetentionDays = prefs.getInt("pref_log_retention_days", 7)
        downloadSubtitles = prefs.getBoolean("pref_download_subtitles", true)
        subtitleLanguage = prefs.getString("pref_subtitle_lang", "en") ?: "en"
        filterExplicitContent = prefs.getBoolean("pref_filter_explicit_content", true)
        // Retention applies at startup, not just when the setting changes.
        com.anonrode.downloader.util.DebugLog.configureRetention(logRetentionDays)
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
        hlsFragments: Int = 16,
        speedLimit: Int = 0,
        peers: Int = -1,
        privacyMode: Boolean = false,
        wifiAll: Boolean = false,
        clipboard: Boolean = true,
        notifications: Boolean = true,
        debugLog: Boolean = false,
        logRetention: Int = 7,
        downloadSubs: Boolean = true,
        subLang: String = "en",
        filterExplicit: Boolean = true
    ) {
        this.maxConcurrentDownloads = maxConcurrent.coerceIn(1, 6)
        this.parallelSocketsPerFile = parallelSockets.coerceIn(1, 16)
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
        this.torrentPrivacyMode = privacyMode
        this.wifiOnlyAll = wifiAll
        this.clipboardDetect = clipboard
        this.completionNotifications = notifications
        this.debugLogging = debugLog
        this.logRetentionDays = logRetention.coerceIn(1, 90)
        this.downloadSubtitles = downloadSubs
        this.subtitleLanguage = subLang.trim().ifBlank { "en" }
        this.filterExplicitContent = filterExplicit
        com.anonrode.downloader.util.DebugLog.configureRetention(this.logRetentionDays)

        // Turning a Wi-Fi gate OFF must release the tasks it parked: the network
        // observer only unparks on an actual Wi-Fi reconnect, so without this the
        // "Waiting for Wi-Fi" tasks would wait for a network change that never
        // comes. Deliberately NOT gated on net.isConnected — a toggle flipped while
        // offline must still queue the tasks; processQueue no-ops until the
        // reconnect collector re-runs it.
        repository.tasks.value.filter {
            it.status == TaskStatus.PAUSED && !it.userPaused && (
                (!this.wifiOnlyAll && it.errorMessage == "Waiting for Wi-Fi (Wi-Fi Only enabled)") ||
                    (!this.wifiOnlyTorrents && !this.wifiOnlyAll && it.errorMessage == "Waiting for Wi-Fi (Torrents Wi-Fi Only enabled)")
                )
        }.forEach { t ->
            repository.update(t.id) { cur ->
                if (cur.userPaused || cur.status != TaskStatus.PAUSED) cur
                else cur.copy(status = TaskStatus.QUEUED, errorMessage = null)
            }
        }

        // A lower limit preempts running tasks NOW (demote back to queue);
        // a higher limit just fills the new slots. This is the only place the
        // user-facing limit change lands, so preemption lives here.
        enforceConcurrencyLimit()

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
            .putBoolean("pref_torrent_privacy_mode", privacyMode)
            .putBoolean("pref_wifi_only_all", wifiAll)
            .putBoolean("pref_clipboard_detect", clipboard)
            .putBoolean("pref_completion_notifications", notifications)
            .putBoolean("pref_debug_logging", debugLog)
            .putInt("pref_log_retention_days", this.logRetentionDays)
            .putBoolean("pref_download_subtitles", this.downloadSubtitles)
            .putString("pref_subtitle_lang", this.subtitleLanguage)
            .putBoolean("pref_filter_explicit_content", this.filterExplicitContent)
            .apply()
    }

    // Dedupe + filename uniquification + add must be ONE atomic unit: two
    // near-simultaneous enqueues of the same URL (double-tap, share intent +
    // clipboard detect) could otherwise both pass the check-then-act gap and
    // queue two tasks writing one .part file.
    private val enqueueLock = Any()

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
        quality: String? = null,
        /** Search-verify oracle handoff (2026-09-14): a direct URL the
         *  ResultVerifier byte-proved <= PRERESOLVED_MAX_AGE_MS ago for THIS
         *  episode URL. Riding the existing direct-file pipeline (status
         *  DOWNLOADING immediately, no RESOLVING round-trip) is exactly how
         *  pasted/share links already work — no new engine branch. sourceUrl
         *  stays the episode page, so the 401/403/404/HTML self-heal
         *  re-resolves from scratch if this token aged out between proof and
         *  tap. Null = the normal path, unchanged. */
        verifiedDirectUrl: String? = null,
        mirrorUrls: List<String> = emptyList(),
        /** Track 4 ("Download Remaining"): the series page the episode was
         *  scraped from and the drawer's total episode count. Stamped on the
         *  task so the Downloads screen can offer a one-tap "grab the rest"
         *  without re-scraping. Defaults keep every other caller (direct
         *  pastes, playlists, subtitles) unchanged. */
        seriesUrl: String = "",
        seriesTotal: Int = 0,
        /** Downloads-card thumbnail art (v6 mock frame 1); "" keeps the
         *  initials tile. Callers without artwork just keep the default. */
        posterUrl: String = "",
        triggerQueueProcessing: Boolean = true
    ): String {
        val downloadFolder = getDownloadDirectory(showTitle, createDirs = false)
        var retryTaskId: String? = null

        val taskId = synchronized(enqueueLock) {
            val currentTasks = repository.snapshot()
            val activeStatuses = setOf(
                TaskStatus.QUEUED,
                TaskStatus.RESOLVING,
                TaskStatus.DOWNLOADING,
                TaskStatus.VALIDATING
            )
            val existing = currentTasks.firstOrNull { it.sourceUrl == sourceUrl && it.status in activeStatuses }
            if (existing != null) {
                return@synchronized existing.id
            }

            // A PAUSED record is resumable, not a duplicate: re-enqueueing an
            // episode whose card shows Paused must resume it (the old dedupe
            // returned the existing id and did nothing, so the tap looked dead).
            val resumableExisting = currentTasks.firstOrNull {
                it.sourceUrl == sourceUrl && (it.status == TaskStatus.FAILED || it.status == TaskStatus.PAUSED)
            }
            if (resumableExisting != null) {
                retryTaskId = resumableExisting.id
                return@synchronized resumableExisting.id
            }

            val cleanTitle = sanitizeComponent(episodeTitle, 80, stripNoise = !isDirect)
            val candidateUrlForExt = verifiedDirectUrl ?: sourceUrl
            val detectedExt = com.anonrode.downloader.pipeline.UrlClassifier.extractMediaExtension(candidateUrlForExt)
            val ext = when {
                audioOnly -> "mp3"
                detectedExt != null -> detectedExt
                backend.contains("yt") || !isDirect -> "mp4"
                else -> "mp4" // Default to .mp4 instead of .mkv when extension is unknown
            }

            // O(1) HashSet lookup + physical disk existence check so we never overwrite existing files
            val reservedPaths = currentTasks.mapTo(HashSet(currentTasks.size * 2)) { it.filePath }
            var targetFile = File(downloadFolder, "$cleanTitle.$ext")
            var counter = 2
            while (
                targetFile.absolutePath in reservedPaths ||
                targetFile.exists() ||
                File(targetFile.absolutePath + ".part").exists() ||
                File(targetFile.absolutePath + ".aria2").exists()
            ) {
                targetFile = File(downloadFolder, "$cleanTitle-$counter.$ext")
                counter++
            }

            val resolvedDirectUrl = if (
                !isDirect &&
                verifiedDirectUrl != null &&
                isDirectMediaUrl(verifiedDirectUrl) &&
                !isKnownLockerHost(verifiedDirectUrl)
            ) verifiedDirectUrl else sourceUrl

            val task = DownloadTask(
                id = UUID.randomUUID().toString(),
                showTitle = showTitle,
                episodeNum = episodeNum,
                episodeTitle = episodeTitle,
                directUrl = resolvedDirectUrl,
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
                quality = quality,
                createdAt = System.currentTimeMillis(),
                mirrorUrls = MirrorPool.sources(sourceUrl, mirrorUrls).filter { it != sourceUrl },
                seriesUrl = seriesUrl,
                seriesTotal = seriesTotal,
                posterUrl = posterUrl
            )

            repository.addFirst(task)
            task.id
        }

        if (retryTaskId != null) {
            retry(retryTaskId!!, processNow = triggerQueueProcessing)
        } else if (triggerQueueProcessing) {
            processQueue()
        }
        return taskId
    }

    /**
     * YouTube playlist batch (2026-09-15): enqueue the selected entries as ONE
     * group — showTitle = playlist title, episodeNum = position in the list —
     * so the Downloads group-by-show ordering and the player's episode-ordered
     * Next queue (DownloadsSorter.EPISODE_ORDER / playerQueueFor) walk the
     * playlist like a series. That grouping is the structural edge over Seal,
     * whose playlist tasks are loose singles. Each entry is a normal yt-dlp
     * extractor task: subtitles, quality, throttle-survival (round 4) and the
     * active-URL dedupe in enqueue() all apply per item, and a failed entry
     * never touches the others. Returns how many were queued.
     */
    fun enqueuePlaylist(
        meta: com.anonrode.downloader.pipeline.PlaylistPicker.PlaylistMeta,
        indices: List<Int>,
        audioOnly: Boolean,
        quality: String?
    ): Int {
        var queued = 0
        for (idx in indices) {
            val entry = meta.entries.getOrNull(idx - 1) ?: continue
            enqueue(
                showTitle = meta.title.ifBlank { "YouTube Playlist" },
                episodeNum = idx,
                episodeTitle = entry.title,
                sourceUrl = entry.watchUrl,
                isDirect = false,
                backend = "yt-dlp",
                parallelSockets = parallelSocketsPerFile,
                audioOnly = audioOnly,
                site = "youtube",
                quality = quality,
                triggerQueueProcessing = false
            )
            queued++
        }
        if (queued > 0) {
            processQueue()
        }
        return queued
    }


    fun pause(taskId: String) {
        val entry = runtimeRegistry.get(taskId)
        entry?.activeJob?.cancel()
        entry?.activeJob = null
        YoutubeDlDownloader.killProcess(taskId)
        // Turbo runs on blocking OkHttp calls that coroutine cancellation does
        // not interrupt, and resolver HTTP calls are plain blocking executes:
        // without these, a paused task kept draining mobile data until the app
        // was killed (user-reported).
        TurboDownloader.cancelTask(taskId)
        // A task paused mid-RESOLVING/mid-probe still has blocking HTTP
        // registered in HttpClient.inFlightCalls that coroutine cancellation
        // cannot interrupt. Sweep exactly like cancel() when it is safe —
        // with other tasks running their requests must stay untouched (the
        // cross-talk rule); the stragglers then die on their read timeout.
        if (!hasOtherActiveWork(taskId)) HttpClient.cancelInFlight()
        com.anonrode.downloader.util.DebugLog.user("pause $taskId")
        // userPaused=true is the authoritative "the user asked for this pause"
        // mark: every auto-resume path (network reconnect, storage self-heal,
        // host cooldown) refuses to re-queue a task carrying it, so the pause
        // survives flaky networks AND full app restarts until an explicit
        // resume. errorMessage is cleared as a second layer — a stale
        // NETWORK_PAUSE_MESSAGE from an earlier blip must not outlive the
        // user's pause and let the reconnect handler re-queue the task (the
        // marker overwrite race that resurrected a paused download ~3s after
        // pause, live-verified in app-2026-08-27.txt).
        repository.update(taskId) { it.copy(status = TaskStatus.PAUSED, speedBytesPerSec = 0.0, errorMessage = null, userPaused = true) }
        updateServiceState(force = true)
        processQueue()
    }

    /**
     * Concurrency preemption: applied when maxConcurrentDownloads CHANGES.
     * Lowering the limit takes effect IMMEDIATELY — running tasks beyond the
     * new limit are demoted back to QUEUED (killing their backends exactly
     * like pause does) and auto-resume when slots free up. The old behavior
     * (limit only gates NEW starts) is what let the user watch 2-3 downloads
     * run after setting the limit to 1 — the log-14 concurrency complaint.
     * Raising the limit just fills the new slots via processQueue.
     *
     * Demotion writes QUEUED, never PAUSED, so the task flows back through
     * the queue in order; the job's CancellationException rescue only rewrites
     * PAUSED over DOWNLOADING/RESOLVING/VALIDATING statuses, and repository
     * updates are atomic per write, so a rescue racing this demotion can never
     * strand the task in a stuck state: either the rescue lands first (PAUSED,
     * overwritten by this QUEUED) or this lands first (QUEUED, rescue no-ops).
     */
    private fun hasOtherActiveWork(ignoredTaskId: String): Boolean {
        return runtimeRegistry.hasOtherActiveWork(ignoredTaskId)
    }

    private fun enforceConcurrencyLimit() {
        val actives = repository.tasks.value.filter {
            it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.RESOLVING || it.status == TaskStatus.VALIDATING
        }
        if (actives.size <= maxConcurrentDownloads) {
            processQueue()
            return
        }
        // Keep the FIRST tasks in list order (the ones the user queued first);
        // demote the rest back to the queue head order.
        actives.drop(maxConcurrentDownloads).forEach { t ->
            com.anonrode.downloader.util.DebugLog.user(
                "concurrency limit now $maxConcurrentDownloads — demoting task=${t.id} (${t.episodeTitle.take(40)}) back to queue"
            )
            val entry = runtimeRegistry.get(t.id)
            entry?.activeJob?.cancel()
            entry?.activeJob = null
            YoutubeDlDownloader.killProcess(t.id)
            TurboDownloader.cancelTask(t.id)
            repository.update(t.id) { task ->
                // Only demote a task that is STILL active (engine-audit P2):
                // the `actives` list is a snapshot, and a task whose completion
                // landed in the window must not be re-queued — that re-downloaded
                // a finished file over the same path on metered data.
                if (task.status == TaskStatus.DOWNLOADING || task.status == TaskStatus.RESOLVING ||
                    task.status == TaskStatus.VALIDATING
                ) task.copy(status = TaskStatus.QUEUED, speedBytesPerSec = 0.0, errorMessage = null)
                else task
            }
        }
        updateServiceState(force = true)
        processQueue()
    }

    private fun pauseForNetwork(taskId: String) {
        val task = repository.find(taskId) ?: return
        if (task.status != TaskStatus.DOWNLOADING && task.status != TaskStatus.RESOLVING) return
        val entry = runtimeRegistry.get(taskId)
        entry?.activeJob?.cancel()
        entry?.activeJob = null
        YoutubeDlDownloader.killProcess(taskId)
        TurboDownloader.cancelTask(taskId)
        // Same in-flight sweep as pause(): a network park mid-resolve must
        // not leave blocking HTTP draining data until its read timeout.
        if (!hasOtherActiveWork(taskId)) HttpClient.cancelInFlight()
        // Atomic re-check inside the StateFlow transform: the find()/status
        // guard above is a non-atomic snapshot, and on a flaky network a
        // disconnect-triggered park races a user pause() — its marker write
        // could land AFTER pause() had cleared errorMessage and set
        // userPaused, leaving NETWORK_PAUSE_MESSAGE behind so the next
        // reconnect re-queued the task the user just paused. Inside the
        // transform the check is CAS-atomic against every other repository
        // write: a user-paused or already-parked task makes it a no-op.
        repository.update(taskId) { t ->
            if (t.userPaused || (t.status != TaskStatus.DOWNLOADING && t.status != TaskStatus.RESOLVING)) t
            else t.copy(status = TaskStatus.PAUSED, speedBytesPerSec = 0.0, errorMessage = NETWORK_PAUSE_MESSAGE)
        }
        updateServiceState(force = true)
    }

    fun cancel(taskId: String) {
        val task = repository.find(taskId)
        // Mark full-cancel intent BEFORE the job cancellation: the job's
        // CancellationException handler kills this task's in-flight resolver
        // HTTP based on this mark. Doing it here (instead of a global
        // cancelInFlight in this method) keeps OTHER tasks' in-flight
        // requests untouched — the cross-talk bug.
        val hasRunningJob = runtimeRegistry.getActiveJob(taskId) != null
        if (hasRunningJob) runtimeRegistry.markFullyCancelled(taskId)
        runtimeRegistry.cancelAndRemove(taskId)
        YoutubeDlDownloader.killProcess(taskId)
        // Full-cancel marking only matters when a Turbo run is unwinding; a queued
        // task has none, and the flag would otherwise linger in the set forever.
        TurboDownloader.cancelTask(taskId, isFullCancel = hasRunningJob)
        // Kill this task's own in-flight resolver HTTP immediately when it is
        // safe: with no other running task there is nothing to cross-talk
        // into. Otherwise the job's cancellation handler sweeps once this
        // task's current blocking call unwinds (bounded by the read timeout).
        if (!hasOtherActiveWork(taskId)) HttpClient.cancelInFlight()
        com.anonrode.downloader.util.DebugLog.user("cancel $taskId")
        if (task != null) {
            engineScope.launch {
                purgeTaskArtifacts(task)
            }
        }
        repository.remove(taskId)
        updateServiceState(force = true)
        processQueue()
    }


    /** Remove every partial artifact a cancelled download may have left.
     *  deleteRecursively: multi-file torrents write a DIRECTORY, and File.delete()
     *  silently refuses non-empty dirs — canceled season torrents left their
     *  preallocated gigabytes behind (user-reported 2026-08-22: 8GB+ stuck after
     *  cancel of a magnet with a dead swarm). The `.work-<taskId>` extractor
     *  dir is the same hazard reintroduced: yt-dlp's `.f140.m4a`/`.f135.mp4`
     *  shards live THERE, not at filePath, and were named after nothing the old
     *  sweep matched — a cancelled 2 GiB YouTube job stranded the whole partial
     *  set in the user's visible folder (audit 2026-09-13). */
    private fun purgeTaskArtifacts(task: DownloadTask) {
        try {
            val target = File(task.filePath)
            // The Turbo→aria2c handoff and the aria2c→Turbo rescue write a
            // URL-extension sibling ("Ep 1.mkv" task, "Ep 1.mp4" artifact +
            // its .aria2/.part/.turbo) — engine-audit P2: purging only the
            // task's own extension family left those partial bytes in the
            // user's folder (the "8 GB left after cancel" shape).
            // Legacy handoff artifacts were named after the URL's raw extension
            // (download.php?token=... → "Ep 1.php" + .aria2). The classifier only
            // knows media extensions, so fall back to the old short-alnum URL
            // extension for those — subtitle/aux extensions stay excluded so a
            // cancel can never purge a sibling .srt family.
            val legacyExt = task.directUrl.substringBefore('?').substringBefore('#')
                .substringAfterLast('/').substringAfterLast('.', "")
            val legacySafe = legacyExt.isNotBlank() && legacyExt.length <= 5 &&
                legacyExt.all { it.isLetterOrDigit() } &&
                legacyExt.lowercase() !in setOf("srt", "vtt", "ass", "ssa", "sub")
            val urlExt = com.anonrode.downloader.pipeline.UrlClassifier.extractMediaExtension(task.directUrl)
                ?: (if (legacySafe) legacyExt else "")
            val sibling = if (urlExt.isNotBlank()) {
                File(target.parentFile, "${target.nameWithoutExtension}.$urlExt")
                    .takeIf { it.absolutePath != target.absolutePath }
            } else null
            // Never touch a path that belongs to a DIFFERENT task: the sibling
            // family can coincide with a sibling task's real target name.
            val otherTargets = repository.tasks.value
                .filter { it.id != task.id }
                .map { it.filePath }
                .toSet()
            val bases = listOfNotNull(target, sibling).filter { it.absolutePath !in otherTargets }
            for (base in bases) {
                base.deleteRecursively()
                File(base.absolutePath + ".part").delete()
                File(base.absolutePath + ".ytdl").delete()
                File(base.absolutePath + ".aria2").delete()
                File(base.absolutePath + ".turbo").delete()
            }
            target.parentFile?.let { parent ->
                File(parent, ".work-${task.id}").deleteRecursively()

                // When a download is cancelled or deleted, if the enclosing show folder
                // is left empty (e.g. 1-episode series, standalone movie, or last remaining episode),
                // delete the folder so empty directory trees do not clutter user storage.
                //
                // Safety guards:
                // 1. Strictly verify the folder is a subfolder inside Downloads/Anon, NEVER Anon itself or Downloads root.
                // 2. Verify no other task in repository (active, paused, completed) targets this folder.
                // 3. File.delete() is atomic and POSIX-safe: it strictly succeeds ONLY if the folder is completely empty.
                try {
                    val rootAnon = File(
                        android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS
                        ),
                        "Anon"
                    )
                    val parentCanon = parent.canonicalFile
                    val anonCanon = rootAnon.canonicalFile
                    val isSubdirOfAnon = parentCanon.absolutePath != anonCanon.absolutePath &&
                        parentCanon.absolutePath.startsWith(anonCanon.absolutePath + File.separator)

                    if (isSubdirOfAnon) {
                        val otherTasksInFolder = repository.tasks.value.any { other ->
                            other.id != task.id && try {
                                File(other.filePath).canonicalFile.parentFile?.absolutePath == parentCanon.absolutePath
                            } catch (_: Throwable) {
                                File(other.filePath).parentFile?.absolutePath == parent.absolutePath
                            }
                        }
                        if (!otherTasksInFolder) {
                            parentCanon.listFiles()?.filter { it.isDirectory && it.name.startsWith(".work-") }?.forEach {
                                try { it.deleteRecursively() } catch (_: Throwable) {}
                            }
                            parentCanon.delete()
                        }
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    fun retry(taskId: String, processNow: Boolean = true) {
        // A stale id (task already removed, e.g. a notification retry after the
        // task was cancelled) must not mint a registry entry that nothing can
        // ever remove.
        val existingTask = repository.find(taskId) ?: return
        try {
            val target = File(existingTask.filePath)
            val isImg = target.name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".webp") }
            val isCorrupt = if (isImg) {
                target.length() < 1024L || looksLikeHtml(target) || !isImageValid(target)
            } else {
                target.length() < 50 * 1024L || looksLikeHtml(target) || !decodeCheck(target)
            }
            // An .aria2 control file marks an aria2c partial that resume will
            // continue (-c): decodeCheck fails on any truncated container, so
            // deleting here destroyed multi-GB banked progress on every
            // resume (and orphaned the control file).
            val hasAria2Partial = File(existingTask.filePath + ".aria2").exists()
            // Never delete while another path owns the file right now: an active
            // run (or a just-completed row) has the artifact under management,
            // and this check races the completion write.
            val liveStatus = repository.find(taskId)?.status
            val activelyOwned = liveStatus == TaskStatus.RESOLVING || liveStatus == TaskStatus.DOWNLOADING ||
                liveStatus == TaskStatus.VALIDATING || liveStatus == TaskStatus.COMPLETED
            if (target.exists() && isCorrupt && !hasAria2Partial && !activelyOwned) {
                target.delete()
            }
        } catch (_: Throwable) {}

        // Each tap grants ONE bypass of the host health gate: the next
        // resolution attempt runs even while the host sits in HostHealth
        // backoff (consumed in startTask). Bounded so stale ids cannot grow.
        runtimeRegistry.markForManualRetry(taskId)
        // Explicit resume/retry clears the user-pause mark — this is the ONLY
        // path allowed to undo a user pause (UI resume button and the
        // notification retry action both funnel through here).
        repository.update(taskId) { it.copy(status = TaskStatus.QUEUED, errorMessage = null, userPaused = false) }
        if (processNow) processQueue()
    }

    /**
     * Cross-provider auto-failover: the task's source site could not serve the
     * title (resolver chain empty, gateway dead, or resolution timed out).
     * Search the OTHER providers for the same title and rewrite this task onto
     * a working site's source, then requeue it — the user tapped a download
     * and should GET the download, not a lesson about which site is broken.
     *
     * Bounds: every site already tried for this task is excluded, so the loop
     * always walks new ground and cannot ping-pong between dead providers;
     * a task may be failed over at most 3 times, then it fails with an honest
     * error. In-memory only, like the park-cycle counter.
     *
     * Series are matched per-episode (loadEpisodes on the candidate show, pick
     * the episode with the same number) so a Suits episode lands on the same
     * episode, not a random one. Movies take the matched show card directly.
     *
     * Returns true when the task was re-pointed at a new source and requeued —
     * the caller must then EXIT its coroutine (the task no longer belongs to
     * this job); false means "no alternative found", and the caller proceeds
     * with the normal park/fail path.
     */
    private suspend fun attemptCrossProviderFailover(task: DownloadTask): Boolean {
        // Progress Banking Guard: failover MUST only run if task.downloadedBytes == 0L && bytesLanded(task) == 0L
        if (task.downloadedBytes > 0L || bytesLanded(task) > 0L) {
            com.anonrode.downloader.util.DebugLog.resolve(
                "task=${task.id} failover: skipped because task already banked ${bytesLanded(task)} bytes"
            )
            return false
        }
        if (task.site.isBlank()) return false
        if (task.directUrl.startsWith("magnet:", ignoreCase = true)) return false
        val tried = runtimeRegistry.getOrCreate(task.id).triedFailoverSites
        if (!tried.add(task.site)) return false // already failed over FROM this site once
        if (tried.size > 3) {
            tried.clear()
            return false
        }
        val query = task.showTitle.ifBlank { task.episodeTitle }
        if (query.isBlank()) return false

        val isAsian = isAsianDramaContext(task)
        val origYear = extractYear(task.showTitle)
            ?: extractYear(task.episodeTitle)
            ?: extractYear(task.sourceUrl)
        val origCountry = extractCountry("${task.showTitle} ${task.episodeTitle} ${task.sourceUrl}", task.site)
        val (origSeason, origEpNum) = parseSeasonAndEpisode(task)

        com.anonrode.downloader.util.DebugLog.resolve(
            "task=${task.id} failover: '${task.site}' cannot serve \"$query\" (isAsian=$isAsian, year=$origYear, country=$origCountry, S${origSeason}E${origEpNum}) — searching other providers"
        )

        // 4-Layer "Suits" Remake Guard - Layer 1: Genre / Cluster Boundary Guard
        // If isAsian: candidates filtered to ASIAN_DRAMA_CLUSTER plus the proven
        // mixed-site secondaries (nkiri/9jarocks). Every candidate still faces
        // Layers 2-4 (year, country tag, season/episode) below.
        // If not isAsian: candidates filtered to WESTERN_CLUSTER (must not query DEDICATED_ASIAN_SITES).
        val allowedCluster = if (isAsian) ASIAN_DRAMA_CLUSTER + SECONDARY_ASIAN_FALLBACK else WESTERN_CLUSTER

        // Search eligible providers in parallel, each bounded; skip hosts
        // currently in backoff so a failover never lands on another dead site.
        val candidates = kotlinx.coroutines.withTimeoutOrNull(25_000L) {
            coroutineScope {
                ProviderRegistry.allProviders
                    .filter { provider ->
                        val pName = provider.name.lowercase()
                        provider.searchEnabled &&
                            pName in allowedCluster &&
                            (isAsian || pName !in DEDICATED_ASIAN_SITES) &&
                            tried.none { it.equals(provider.name, ignoreCase = true) }
                    }
                    .map { provider ->
                        async(Dispatchers.IO) {
                            try {
                                withTimeoutOrNull(20_000L) { provider.search(query) } ?: emptyList()
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                    }
                    .awaitAll()
                    .flatten()
            }
        } ?: emptyList()

        val normQuery = normalizeTitleQuery(query)
        val ranked = candidates
            .asSequence()
            .filter { card ->
                val candSite = card.site.lowercase()
                // Layer 1: Genre / Cluster Boundary Guard on candidate card
                val clusterMatch = if (isAsian) {
                    candSite in ASIAN_DRAMA_CLUSTER || candSite in SECONDARY_ASIAN_FALLBACK
                } else {
                    candSite in WESTERN_CLUSTER && candSite !in DEDICATED_ASIAN_SITES
                }
                if (!clusterMatch) return@filter false

                if (card.url.isBlank()) return@filter false
                if (tried.any { it.equals(card.site, ignoreCase = true) }) return@filter false
                if (!com.anonrode.downloader.pipeline.HostHealth.isUsable(card.url)) return@filter false
                if (!titleMatches(query, card.title)) return@filter false

                // Layer 2: Release Year Matching with ±1 tolerance.
                // Streaming sites label the SAME show off-by-one (original
                // production year vs international premiere): asianc serves
                // "the-promised-neverland-2020" while 9jaRocks lists
                // "The Promised Neverland (2019)" — a hard mismatch reject
                // silently discarded the user's working source and pushed the
                // task onto a failing one (journal 10-15 20:25). ±1 years is
                // tight enough that implausible cross-show matches still lose
                // on season/title guards above and below.
                val candYear = extractYear(card.year)
                    ?: extractYear(card.title)
                    ?: extractYear(card.url)
                if (origYear != null && candYear != null && kotlin.math.abs(origYear - candYear) > 1) {
                    com.anonrode.downloader.util.DebugLog.resolve(
                        "task=${task.id} failover: rejected card \"${card.title}\" due to year mismatch (orig=$origYear, cand=$candYear)"
                    )
                    return@filter false
                }

                // Layer 3: Country Tag Matching.
                // Mixed-site secondaries (nkiri/9jarocks) must prove country by
                // EXPLICIT tag only: extractCountry's site-default WESTERN would
                // auto-reject an untagged K-drama card on nkiri — the exact
                // content this failover exists to rescue. No explicit tag means
                // UNKNOWN here, and UNKNOWN never rejects.
                val candCountry = extractFailoverCountry(isAsian, "${card.title} ${card.category}", card.site)
                if (origCountry != DramaCountry.UNKNOWN && candCountry != DramaCountry.UNKNOWN && origCountry != candCountry) {
                    com.anonrode.downloader.util.DebugLog.resolve(
                        "task=${task.id} failover: rejected card \"${card.title}\" due to country mismatch (orig=$origCountry, cand=$candCountry)"
                    )
                    return@filter false
                }

                // Season guard on title
                if (!seasonConsistent(query, card.title, if (origEpNum > 0) origSeason else null)) {
                    return@filter false
                }

                // Layer 4 pre-check: if candidate specifies totalEpisodes, reject if less than origEpNum
                if (origEpNum > 0 && card.totalEpisodes > 0 && card.totalEpisodes < origEpNum) {
                    com.anonrode.downloader.util.DebugLog.resolve(
                        "task=${task.id} failover: rejected card \"${card.title}\" totalEpisodes (${card.totalEpisodes}) < origEpNum ($origEpNum)"
                    )
                    return@filter false
                }

                true
            }
            // Exact title match first, then a provider whose host has proven
            // itself in the ledger, then country agreement (Asian tasks: an
            // explicitly-tagged same-country card is probed before an
            // untagged one), then everything else.
            val agreeWith: Map<com.anonrode.downloader.data.models.ShowCard, Boolean> =
                ranked.associateWith { card ->
                    isAsian && origCountry != DramaCountry.UNKNOWN &&
                        extractFailoverCountry(true, "${card.title} ${card.category}", card.site) == origCountry
                }
            val ordered = ranked
            .sortedWith(
                compareByDescending<com.anonrode.downloader.data.models.ShowCard> { normalizeTitleQuery(it.title) == normQuery }
                    .thenByDescending { com.anonrode.downloader.pipeline.HostHealth.hasProvenLocker(it.url) }
                    .thenByDescending { agreeWith[it] == true }
            )
            .toList()

        // Hard deadline for the whole failover (episode lookups included).
        val failoverDeadline = System.currentTimeMillis() + 60_000L
        for (card in ordered) {
            if (System.currentTimeMillis() > failoverDeadline) break
            var newMirrors = emptyList<String>()
            val newUrl: String? = if (origEpNum > 0) {
                // Layer 4: Season & Episode Boundary Verification
                try {
                    val details = kotlinx.coroutines.withTimeoutOrNull(20_000L) {
                        ProviderRegistry.loadEpisodes(card)
                    } ?: continue

                    // Total episodes check: candidate with fewer episodes than origEpNum is rejected
                    if (details.episodes.size < origEpNum) {
                        com.anonrode.downloader.util.DebugLog.resolve(
                            "task=${task.id} failover: rejected \"${card.title}\" loaded episodes (${details.episodes.size}) < origEpNum ($origEpNum)"
                        )
                        continue
                    }

                    // Candidate must have matching (origSeason, origEpNum)
                    val origIsExplicitSeries = origEpNum > 0 && (task.episodeTitle.contains("Episode", ignoreCase = true) ||
                        task.episodeTitle.contains("Ep", ignoreCase = true) ||
                        task.episodeTitle.contains("Season", ignoreCase = true) ||
                        task.sourceUrl.isNotBlank())
                    val candIsMovie = card.category.equals("Movies", ignoreCase = true) || card.title.contains("Movie", ignoreCase = true)
                    if (origIsExplicitSeries && candIsMovie && details.episodes.size == 1) {
                        com.anonrode.downloader.util.DebugLog.resolve(
                            "task=${task.id} failover: rejected movie card \"${card.title}\" for series task S${origSeason}E${origEpNum}"
                        )
                        continue
                    }
                    val isSingleMovie = details.episodes.size == 1 && origEpNum <= 1 && origSeason == 1 && !origIsExplicitSeries
                    val matchingEp = details.episodes.firstOrNull { candEp ->
                        if (isSingleMovie) true
                        else {
                            val (candS, candE) = parseCandidateSeasonAndEpisode(card, candEp)
                            candS == origSeason && candE == origEpNum
                        }
                    }
                    if (matchingEp == null) {
                        com.anonrode.downloader.util.DebugLog.resolve(
                            "task=${task.id} failover: no episode matching S${origSeason}E${origEpNum} in \"${card.title}\""
                        )
                        continue
                    }

                    if (!com.anonrode.downloader.pipeline.HostHealth.isUsable(matchingEp.url)) continue
                    newMirrors = matchingEp.mirrorUrls
                    matchingEp.url
                } catch (_: Exception) {
                    continue
                }
            } else {
                card.url
            }
            if (newUrl.isNullOrBlank()) continue

            // Atomic Pause Guard Before Deleting Partials:
            // Verify task.userPaused inside repository.update before deleting partial files on disk.
            var wasUserPaused = false
            repository.update(task.id) { current ->
                if (current.userPaused) {
                    wasUserPaused = true
                    current
                } else {
                    current.copy(
                        sourceUrl = newUrl,
                        directUrl = newUrl,
                        mirrorUrls = newMirrors,
                        selectedMirrorUrl = "",
                        site = card.site,
                        quality = current.quality, // Preserve User Quality
                        status = TaskStatus.QUEUED,
                        errorMessage = null,
                        speedBytesPerSec = 0.0,
                        downloadedBytes = 0L,
                        totalBytes = 0L
                    )
                }
            }

            if (wasUserPaused) {
                com.anonrode.downloader.util.DebugLog.resolve(
                    "task=${task.id} failover: aborted because task was paused by user"
                )
                return false
            }

            com.anonrode.downloader.util.DebugLog.user(
                "task=${task.id} failover: switching source ${task.site} -> ${card.site} for \"$query\"" +
                    (if (origEpNum > 0) " (S${origSeason}E${origEpNum})" else "")
            )
            // The task is re-pointed at a different source: its previous
            // source's dead-URL marks must not answer for the new one.
            ResolveBudget.forgetTask(task.id)

            // Safe to delete partial files ONLY after verifying task is actively continuing (not user paused)
            try {
                File(task.filePath).delete()
                File(task.filePath + ".part").delete()
                File(task.filePath + ".ytdl").delete()
                File(task.filePath + ".aria2").delete()
                File(task.filePath + ".turbo").delete()
                File(task.filePath).parentFile?.let {
                    File(it, ".work-" + task.id).deleteRecursively()
                }
            } catch (_: Throwable) {}

            return true
        }

        // Nothing usable anywhere: leave the tried-set in place so the NEXT
        // failure of this task fails over again from its current site if a
        // manual retry re-points it, but don't loop here.
        com.anonrode.downloader.util.DebugLog.resolve(
            "task=${task.id} failover: no other provider could serve \"$query\""
        )
        return false
    }

    internal fun extractYear(text: String): Int? {
        if (text.isBlank()) return null
        return YEAR_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    // Keyword-only country read: the same tag vocabulary as the historical
    // extractCountry, WITHOUT the site-default tail. Used by failover for
    // mixed-site secondaries, where the WESTERN site default is actively
    // wrong (an nkiri K-drama card is not a Western show). Untagged text is
    // UNKNOWN, and UNKNOWN never rejects in Layer 3 — explicit tags still
    // do (a Hollywood-tagged card on 9jarocks loses to a Korean task).
    internal fun extractExplicitCountry(text: String): DramaCountry {
        val lower = text.lowercase()
        return when {
            lower.contains("korean") || lower.contains("k-drama") || lower.contains("kdrama") ||
                lower.contains("k drama") || lower.contains("korea") -> DramaCountry.KOREAN
            lower.contains("chinese") || lower.contains("c-drama") || lower.contains("cdrama") ||
                lower.contains("c drama") || lower.contains("china") -> DramaCountry.CHINESE
            lower.contains("japanese") || lower.contains("j-drama") || lower.contains("jdrama") ||
                lower.contains("j drama") || lower.contains("japan") -> DramaCountry.JAPANESE
            lower.contains("taiwanese") || lower.contains("tw-drama") || lower.contains("taiwan") -> DramaCountry.TAIWANESE
            lower.contains("thai") || lower.contains("thailand") || lower.contains("lakorn") ||
                lower.contains("th-drama") -> DramaCountry.THAI
            lower.contains("western") || lower.contains("hollywood") || lower.contains("american") ||
                lower.contains("nollywood") -> DramaCountry.WESTERN
            else -> DramaCountry.UNKNOWN
        }
    }

    internal fun extractCountry(text: String, site: String = ""): DramaCountry {
        val explicit = extractExplicitCountry(text)
        if (explicit != DramaCountry.UNKNOWN) return explicit
        return when {
            WESTERN_CLUSTER.contains(site.lowercase()) -> DramaCountry.WESTERN
            else -> DramaCountry.UNKNOWN
        }
    }

    // Layer-3 country read for cross-provider failover: identical to
    // extractCountry EXCEPT an Asian task reading a mixed-site secondary
    // card (nkiri/9jarocks) skips the WESTERN site default.
    internal fun extractFailoverCountry(isAsian: Boolean, text: String, site: String = ""): DramaCountry {
        if (isAsian && site.lowercase() in SECONDARY_ASIAN_FALLBACK) {
            return extractExplicitCountry(text)
        }
        return extractCountry(text, site)
    }

    internal fun isAsianDramaContext(task: DownloadTask): Boolean {
        val site = task.site.lowercase()
        if (site in DEDICATED_ASIAN_SITES) return true
        val country = extractCountry("${task.showTitle} ${task.episodeTitle} ${task.sourceUrl}", task.site)
        if (country in setOf(DramaCountry.KOREAN, DramaCountry.CHINESE, DramaCountry.JAPANESE, DramaCountry.THAI, DramaCountry.TAIWANESE)) {
            return true
        }
        if (site in ASIAN_DRAMA_CLUSTER && site !in WESTERN_CLUSTER) return true
        return false
    }

    internal fun parseSeasonAndEpisode(task: DownloadTask): Pair<Int, Int> {
        if (task.site.equals("9jarocks", ignoreCase = true) && task.episodeNum >= 100) {
            return Pair(task.episodeNum / 100, task.episodeNum % 100)
        }
        if (task.episodeNum >= 100 && !isAsianDramaContext(task)) {
            return Pair(task.episodeNum / 100, task.episodeNum % 100)
        }
        val text = "${task.showTitle} ${task.episodeTitle} ${task.sourceUrl}".lowercase()
        val season = Regex("""(?:s|season)[\s._-]?0*(\d{1,2})\b""")
            .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val ep = if (task.episodeNum > 0) {
            task.episodeNum
        } else {
            Regex("""(?:e|ep|episode)[\s._-]?0*(\d{1,3})\b""")
                .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
        return Pair(season, ep)
    }

    internal fun parseCandidateSeasonAndEpisode(card: com.anonrode.downloader.data.models.ShowCard, ep: com.anonrode.downloader.data.models.EpisodeItem): Pair<Int, Int> {
        if (card.site.equals("9jarocks", ignoreCase = true) && ep.episodeNum >= 100) {
            return Pair(ep.episodeNum / 100, ep.episodeNum % 100)
        }
        val text = "${card.title} ${ep.title} ${ep.url}".lowercase()
        val season = Regex("""(?:s|season)[\s._-]?0*(\d{1,2})\b""")
            .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val epNum = if (ep.episodeNum > 0) {
            if (ep.episodeNum >= 100 && card.site.lowercase() !in ASIAN_DRAMA_CLUSTER) {
                ep.episodeNum % 100
            } else {
                ep.episodeNum
            }
        } else {
            Regex("""(?:e|ep|episode)[\s._-]?0*(\d{1,3})\b""")
                .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
        return Pair(season, epNum)
    }

    /** Lowercase and strip everything but letters/digits — the loose title
     *  comparison used by failover matching ("Sholay (1975)" vs "sholay"). */
    private fun normalizeTitleQuery(q: String): String =
        q.lowercase().filter { it.isLetterOrDigit() }

    // Words too common to help tell two titles apart; dropped by
    // [titleMatches] so "My Name is Khan" doesn't demand the word "is".
    // Site-noise words ("indian movie", "hindi", "web-dl", "480p"…) are
    // appended by scrapers, not part of the title — demanding them makes
    // failover reject the exact movie it's looking for (naijavault's
    // "My Name Is Khan 2010 Indian Movie" vs pluto's
    // "My Name Is Khan 2010 Hindi" — same film).
    private val TITLE_STOPWORDS = setOf(
        "the", "a", "an", "is", "of", "and", "to", "in", "on", "le", "la", "de",
        "movie", "film", "series", "complete", "season", "episode", "download",
        "full", "hd", "hindi", "english", "dubbed", "subbed", "korean", "chinese",
        "drama", "web", "dl", "webdl", "bluray", "brrip", "dvdrip", "hevc", "x264",
        "x265", "aac", "480p", "720p", "1080p", "2160p", "4k"
    )

    /** True when the candidate title covers the query: every meaningful word
     *  of the query must appear in the normalized candidate. Pure numbers
     *  (years like "1975") are ignored — our own titles carry years that
     *  candidate listings often omit. */
    private fun titleMatches(query: String, candidate: String): Boolean {
        val normCard = normalizeTitleQuery(candidate)
        if (normCard.isBlank()) return false
        val words = Regex("[a-z0-9]+").findAll(query.lowercase())
            .map { it.value }
            .filter { it.length >= 2 && !TITLE_STOPWORDS.contains(it) && !it.all { c -> c.isDigit() } }
            .toList()
        if (words.isEmpty()) return false
        if (!words.all { normCard.contains(it) }) return false

        // Year check inside titleMatches if both present
        val qYear = extractYear(query)
        val cYear = extractYear(candidate)
        if (qYear != null && cYear != null && qYear != cYear) return false

        // Sequel / Part check if both present
        val partRegex = Regex("""\b(?:part|chapter|pt)[\s._-]?(\d+|[ivx]+)\b""")
        val qPart = partRegex.find(query.lowercase())?.groupValues?.get(1)
        val cPart = partRegex.find(candidate.lowercase())?.groupValues?.get(1)
        if (qPart != null && cPart != null && qPart != cPart) return false

        return true
    }

    /** Season guard for failover: when the task title declares a season
     *  ("Suits S02", "Alchemy of Souls Season 2"), the candidate must declare
     *  the SAME season. Candidates that declare a different season are
     *  rejected; candidates with no season marker pass (the episode-number
     *  match inside the provider's episode list is the second guard). */
    private fun seasonConsistent(query: String, candidate: String, seasonNum: Int?): Boolean {
        if (seasonNum == null) return true
        val cardLower = candidate.lowercase()
        val cardSeason = Regex("(?:s|season)[\\s._-]?0*(\\d{1,2})\\b")
            .find(cardLower)?.groupValues?.get(1)?.toIntOrNull()
        return cardSeason == null || cardSeason == seasonNum
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

    private fun looksLikeHtml(text: String): Boolean {
        val head = text.trimStart().lowercase()
        return head.startsWith("<!doctype html") || head.startsWith("<html") || head.startsWith("<head") ||
            head.startsWith("<body") || head.startsWith("<!--") || head.startsWith("<script") ||
            head.startsWith("<svg") || head.startsWith("<?xml") || head.startsWith("<style") ||
            head.startsWith("<iframe") || head.startsWith("<meta") || head.startsWith("<form") ||
            head.startsWith("{")
    }

    /**
     * Tier-2 verification: ask the platform codec stack whether the file is
     * actually decodable media. MediaExtractor parses the container and exposes
     * its tracks plus per-track duration; a garbage blob yields no usable
     * track, real media yields at least one with a positive duration. This is a
     * container parse only (no frames decoded), so it is cheap, and it is
     * bounded by the platform's own parser — no manual timeout needed.
     */
    private fun decodeCheck(file: File): Boolean {
        return try {
            val extractor = android.media.MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                var ok = false
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    val mime = fmt.getString(android.media.MediaFormat.KEY_MIME)
                    if (!mime.isNullOrBlank() && fmt.containsKey(android.media.MediaFormat.KEY_DURATION) &&
                        fmt.getLong(android.media.MediaFormat.KEY_DURATION) > 0L
                    ) {
                        ok = true
                        break
                    }
                }
                com.anonrode.downloader.util.DebugLog.write("decodeCheck ${file.name}: tracks=${extractor.trackCount} decodable=$ok")
                ok
            } finally {
                try { extractor.release() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            com.anonrode.downloader.util.DebugLog.write("decodeCheck ${file.name}: ${e.message}")
            false
        }
    }

    private fun isImageValid(file: File): Boolean {
        if (!file.isFile || file.length() < 1024L) return false
        return try {
            file.inputStream().use { ins ->
                val head = ByteArray(16)
                val read = ins.read(head)
                if (read < 4) false
                // JPEG: FF D8 FF
                else if (head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()) true
                // PNG: 89 50 4E 47 (\x89PNG)
                else if (head[0] == 0x89.toByte() && head[1] == 0x50.toByte() && head[2] == 0x4E.toByte() && head[3] == 0x47.toByte()) true
                // WebP: RIFF....WEBP
                else if (read >= 12 && head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte() &&
                    head[8] == 'W'.code.toByte() && head[9] == 'E'.code.toByte() && head[10] == 'B'.code.toByte() && head[11] == 'P'.code.toByte()) true
                else false
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Image-artifact predicate (jpg/jpeg/png/webp) for the O1 completion check. */
    private fun isImageFile(file: File): Boolean =
        file.name.lowercase().let {
            it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".webp")
        }

    /**
     * Actual bytes this task has written to disk: the final file, its .part /
     * .ytdl partials, and yt-dlp's concurrent fragment files. The UI progress
     * watchdog uses this as the source of truth when backend output parsing
     * reports nothing, and it is what stall detection compares against.
     *
     * yt-dlp extractor tasks write into a private per-task workdir
     * (.work-<taskId> inside the target folder) — see YoutubeDlDownloader.
     * While that workdir exists it IS the task's footprint and scanning it
     * keeps sibling jobs in the same folder (two YouTube videos at once,
     * app-2026-08-31) from counting each other's bytes; once the artifact is
     * moved back the workdir is deleted, and the task is one tick away from
     * a terminal status anyway (extractor outputs are named by yt-dlp's
     * metadata template, not by task.filePath).
     */
    private fun computeDiskBytes(task: DownloadTask): Long {
        return try {
            val targetDir = File(task.filePath).parentFile ?: return 0L
            val workDir = File(targetDir, ".work-${task.id}")
            if (workDir.isDirectory) {
                return workDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }
            val dir = targetDir
            dir.listFiles() ?: return 0L
            val base = File(task.filePath).name
            // Segmented Turbo downloads pre-allocate the .part file to its full
            // size before a single byte is written, so file.length() is not a
            // trustworthy progress source while a download is in flight.
            // When a .turbo sidecar exists, the piece map IS the truth:
            // sum committed offsets, ignore pre-allocated zeros. Never sum with destFile.
            val sidecar = File(dir, base + ".turbo")
            val ariaControl = File(dir, "$base.aria2")
            // O2b precedence: the engine only starts Turbo when NO base.aria2
            // exists, so a base.aria2 control file is ALWAYS the live transfer —
            // its bitfield outranks a .turbo plan stranded by a failed handoff.
            // (Aria2 pre-allocates the dest to full size, so the bitfield is the
            // only honest source of committed bytes.)
            if (ariaControl.exists()) {
                val ctrl = Aria2Control.parse(ariaControl)
                return ctrl?.verifiedLandedBytes() ?: ctrl?.pieces?.sumOf { it.current - it.start } ?: 0L
            }
            if (sidecar.exists()) {
                return TurboState(sidecar).writtenBytes() ?: 0L
            }
            // Legacy handoff control ("X.part" + "X.part.aria2"): the engine's
            // resume gate does not see this name, so Turbo may legitimately own
            // the .part — keep this read AFTER the Turbo plan.
            val legacyAria2 = File(dir, "$base.part.aria2")
            if (legacyAria2.exists()) {
                val ctrl = Aria2Control.parse(legacyAria2)
                return ctrl?.verifiedLandedBytes() ?: ctrl?.pieces?.sumOf { it.current - it.start } ?: 0L
            }
            // Non-turbo transfers: use strict single-file priority.
            // Guard against unverified pre-allocated files: if the task is actively downloading
            // and the raw file length equals the expected totalBytes, do NOT trust the unverified
            // preallocation without a validating sidecar/control file.
            val partFile = File(dir, "$base.part")
            if (partFile.exists()) {
                val len = partFile.length()
                if (task.status == TaskStatus.DOWNLOADING && task.totalBytes > 0L && len >= task.totalBytes && task.downloadedBytes < task.totalBytes) {
                    return task.downloadedBytes
                }
                return len
            }
            val ytdlFile = File(dir, "$base.ytdl")
            if (ytdlFile.exists()) return ytdlFile.length()
            val destFile = File(dir, base)
            if (destFile.exists()) {
                val len = destFile.length()
                if (task.status == TaskStatus.DOWNLOADING && task.totalBytes > 0L && len >= task.totalBytes && task.downloadedBytes < task.totalBytes) {
                    return task.downloadedBytes
                }
                return len
            }
            0L
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Bytes this task has actually banked: what the backends reported OR what
     * is on disk (partials included). The park-vs-fail rule below keys on this.
     */
    private fun bytesLanded(task: DownloadTask): Long =
        maxOf(task.downloadedBytes, computeDiskBytes(task))

    /**
     * A 429 is an explicit "come back later" — never a reason to destroy a
     * task. Matches the engine's own vocabulary: the validator rejection
     * ("Server rejected the link (HTTP 429) — ..."), the Turbo failure
     * message, and the aria2c/yt-dlp chains that quote the status.
     */
    private fun isRateLimitedError(msg: String?): Boolean =
        msg != null && (msg.contains("429") || msg.contains("Too Many Requests", ignoreCase = true))

    private fun parkFloorMs(cycles: Int): Long =
        (cycles.toLong() * PARK_REQUEUE_FLOOR_MS).coerceAtMost(PARK_REQUEUE_MAX_MS)

    /**
     * THE RULE (user, 2026-09-14): a download that has ever moved bytes may
     * never be FAILED by a host-side hiccup — rate-limit, stall, dead link,
     * backend teardown. It parks instead: PAUSED with the cooldown-loop
     * message, partial files untouched (Turbo's .part/.turbo sidecar and
     * aria2c's control file both resume from where they stopped), and the
     * existing auto-retry loop re-queues it when the host is usable, on a
     * per-cycle escalating floor. Tasks that never started (zero banked
     * bytes and no rate-limit signal) keep the honest-error behavior so a
     * permanently dead source cannot hold a queue slot hostage forever.
     *
     * Returns true when the task was parked (caller must NOT fail/notify).
     */
    private fun parkInsteadOfFail(task: DownloadTask, err: String, forcePark: Boolean): Boolean {
        val landed = bytesLanded(task)
        if (landed == 0L && !forcePark) return false
        val entry = runtimeRegistry.getOrCreate(task.id)
        val cycles = entry.parkCycleCount.incrementAndGet()
        val now = System.currentTimeMillis()
        entry.firstParkedAtMs.compareAndSet(0L, now)
        val floorMs = parkFloorMs(cycles)
        entry.requeueNotBeforeMs.set(now + floorMs)
        val mins = maxOf(1L, floorMs / 60_000L)
        val saved = if (landed > 0) " — ${(landed / (1024L * 1024L)).coerceAtLeast(1)} MB already saved, it will resume" else ""
        repository.update(task.id) {
            it.copy(
                status = TaskStatus.PAUSED,
                speedBytesPerSec = 0.0,
                errorMessage = "$PARKED_HOST_MESSAGE — will retry in ~${mins}m$saved"
            )
        }
        com.anonrode.downloader.util.DebugLog.user(
            "park task=${task.id} (cycle=$cycles, landed=${landed / 1024}KiB, forced=$forcePark) reason=${err.take(140)}"
        )
        updateServiceState(force = true)
        return true
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
                File(base, "Social/${sanitizeComponent(platform, 40)}")
            }
            showTitle.equals("Torrents", ignoreCase = true) -> File(base, "Torrents")
            autoOrganizeByShow && showTitle.isNotBlank() && showTitle != "Direct Downloads" -> {
                File(base, sanitizeComponent(showTitle, 60))
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

    /**
     * Turn an arbitrary scraped title into a safe single filesystem
     * component. THE policy lives in util/NameSanitizer (the app-wide name
     * standard: noise-phrase removal, full entity decoding, native script
     * preserved, control/forbidden chars replaced, traversal/reserved-name/
     * trailing-dot guards, byte-aware cap). Kept as the engine's local
     * doorway so folder/stem/social call sites all share one entry.
     */
    private fun sanitizeComponent(
        raw: String, maxChars: Int, stripNoise: Boolean = true
    ): String = com.anonrode.downloader.util.NameSanitizer.savedName(raw, maxChars, stripNoise)

    private fun getRefererForUrl(url: String): String {
        // Single source of truth is now the OTA playbook (DynamicRulesManager:
        // hostPolicies first, built-in defaults second). The old per-host
        // when-map lived here and drifted from the monolith + probe copies;
        // its exact entries survive as the manager's DEFAULT_HOST_POLICIES.
        return com.anonrode.downloader.data.rules.DynamicRulesManager.resolveReferer(url)
    }

    private fun getUserAgentForUrl(url: String): String {
        val policyUa = com.anonrode.downloader.data.rules.DynamicRulesManager.resolveUserAgent(url)
        return if (policyUa.isNotBlank()) policyUa else com.anonrode.downloader.data.net.HttpClient.DEFAULT_UA
    }

    // Adaptive-streaming manifest detection (monolith is_streaming_link parity):
    // DASH/ISM/F4M manifests and player-token URLs must go to yt-dlp, or the
    // segmented downloader grabs manifest XML and produces a corrupt file.
    private fun isStreamingLink(url: String): Boolean =
        com.anonrode.downloader.pipeline.UrlClassifier.isStreamingLink(url)

    companion object {
        // URI="..." inside HLS tags (EXT-X-KEY, EXT-X-MAP, EXT-X-MEDIA) — the
        // URI value may be scheme-relative or relative and needs the same
        // absolute-https rewrite as segment URIs.
        private val URI_VALUE = Pattern.compile("URI=\"([^\"]+)\"")

        private val STREAMING_QUERY_PATTERN = Regex("""[?&][^=&]*=(?:mpd|dash|hls|m3u8)(?:&|$)""")

        // KNOWN_LOCKER_HOSTS + the isKnownLockerHost/isProvablyDirectFile
        // classifiers moved to pipeline.LinkResolver (2026-09-14) so the
        // search-verify oracle proves results with the SAME ladder downloads
        // use. The thin delegates below keep this file's ~20 call sites intact.

        // Stall-watchdog tuning constants live in ProgressWatchdog.kt (single source of truth).

        // Hard ceiling on the link-cracking phase. Without it a slow site kept
        // a task in RESOLVING forever while the user's mobile data trickled
        // away on retries.
        private const val RESOLVE_TIMEOUT_MS = 90_000L

        // Prefix of the errorMessage set when a task is parked because its
        // locker host is in HostHealth backoff ("cooling down"). The
        // auto-retry loop matches this prefix and re-queues the task once the
        // host is usable again.
        private const val PARKED_HOST_MESSAGE = "Download server cooling down"

        // A parked task may cycle QUEUED → RESOLVING → park at most this many
        // times before it is FAILED outright. Without a cap, a host that never
        // recovers keeps the batch bouncing every backoff window forever (the
        // Suits batch churned for 90 minutes on a host that was never coming
        // back). Generous: at 2 windows each, 6 attempts ≈ 12 minutes of real
        // chances before the task gives up with an honest error.
        private const val MAX_PARK_CYCLES = 6

        // How long ONE task may sit parked by the cooldown loop before the
        // give-up fires it. Measured from the FIRST park (firstParkedAtMs uses
        // compareAndSet), so repeated re-parks cannot reset the clock: a host
        // that stays dead for 30 minutes of cycling loses the task with an
        // honest error instead of holding the queue hostage all afternoon.
        private const val PARKED_LIFETIME_MS = 30 * 60_000L

        // Re-queue floor per park cycle: the 1st park waits 60s, the 2nd 2m,
        // ... capped at 15m. Rate-limits on these lockers are minutes-long
        // penalties, and every premature bounce re-triggers them (share-5
        // log: the 429 storm retried every 2-4 seconds, each retry extending
        // the block and burning data on failed resolves).
        private const val PARK_REQUEUE_FLOOR_MS = 60_000L
        private const val PARK_REQUEUE_MAX_MS = 15 * 60_000L
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

        // Fill EVERY free slot in one pass, not just one: the reconnect /
        // storage-self-heal / cooldown loops re-queue many parked tasks and
        // then call processQueue() exactly once, so single-step starts left
        // maxConcurrentDownloads-1 slots empty until the next completion.
        while (true) {
            val currentTasks = tasks.value
            // Two counters, and the LIMIT uses the LARGER of the two:
            //  - status count: what the repository claims (DOWNLOADING/RESOLVING/VALIDATING)
            //  - live-job count: coroutines actually running in runtimeRegistry
            // The status count alone missed tasks whose status was written by a
            // path outside this scheduler (watchdog restart, post-pause zombie
            // writes) — the live-job count alone misses a task between its
            // status flip and its job registration. Taking the max closes the
            // window both ways: this is THE concurrency choke-point, and no
            // other code may start a backend outside it.
            val activeIds = buildSet {
                currentTasks.filter {
                    it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.RESOLVING || it.status == TaskStatus.VALIDATING
                }.forEach { add(it.id) }
                addAll(runtimeRegistry.activeRunningTaskIds())
            }
            val activeCount = activeIds.size

            if (activeCount >= maxConcurrentDownloads) return

            // Never start a task the user explicitly paused (engine-audit P1:
            // no legitimate state is QUEUED + userPaused — only retry()/
            // resumeAll() clear the flag, and they set QUEUED themselves, so
            // this predicate can only ever reject a pause that raced a requeue).
            // Never pick a task the runtime registry still owns: a failover flips a
            // task QUEUED milliseconds before its dying coroutine's finally runs, and
            // starting it here would flip it to RESOLVING/DOWNLOADING only to lose
            // the start-lock race and strand it (audit: queue↔start race).
            val nextTask = currentTasks.firstOrNull {
                it.status == TaskStatus.QUEUED && !it.userPaused && it.id !in activeIds
            } ?: return

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
                // Continue instead of return: a gated magnet must not block
                // non-torrent HTTP tasks queued behind it from starting.
                continue
            }
            // Wi-Fi-only for ALL downloads (not just torrents)
            if (wifiOnlyAll && !net.isWifi) {
                currentTasks.filter { it.status == TaskStatus.QUEUED && !it.userPaused }.forEach { queued ->
                    repository.update(queued.id) { it.copy(status = TaskStatus.PAUSED, errorMessage = "Waiting for Wi-Fi (Wi-Fi Only enabled)") }
                }
                return
            }

            // Status may only be flipped once the start lock is held, so no queue
            // path can strand a task in RESOLVING/DOWNLOADING without a coroutine.
            if (!runtimeRegistry.tryAcquireStartLock(nextTask.id)) return

            val isDirect = isDirectMediaUrl(nextTask.directUrl) && !isKnownLockerHost(nextTask.directUrl)
            val initialStatus = if (isDirect) TaskStatus.DOWNLOADING else TaskStatus.RESOLVING
            // Conditional flip: a pause landing between the selection and this
            // write must not be overwritten (a paused row is never QUEUED).
            repository.update(nextTask.id) { t ->
                if (t.userPaused || t.status != TaskStatus.QUEUED) t else t.copy(status = initialStatus)
            }
            updateServiceState(force = true)

            startTask(nextTask, lockAlreadyHeld = true)
            // Loop on: the status flip above is synchronous on the StateFlow,
            // so the next iteration counts this task as active and picks the
            // following QUEUED one until all slots are full.
        }
    }

    /** Moved to pipeline.LinkResolver (2026-09-14, oracle round); delegates
     *  here so the routing/startTask call sites below are untouched. */
    private fun isProvablyDirectFile(url: String): Boolean =
        com.anonrode.downloader.pipeline.LinkResolver.isProvablyDirectFile(url)

    private fun isKnownLockerHost(url: String): Boolean =
        com.anonrode.downloader.pipeline.LinkResolver.isKnownLockerHost(url)

    /** Moved verbatim to pipeline.LinkResolver.resolveChain (2026-09-14,
     *  oracle round). allowCacheHit=false preserves this caller's documented
     *  semantic: "fetch a FRESH link" — invalidate-first, never serve a
     *  cached (possibly dead-token) URL on the refresh path. All four
     *  call sites (startTask + the 401/403/404 self-heal re-resolves) get
     *  identical behavior to before the move.
     *
     *  [memoTaskId] is NON-NULL only on the resolve-phase call in startTask:
     *  it arms the per-task dead-URL memory (engine.ResolveBudget) + per-
     *  mirror attempt budgets for the engine-driven walk. The mid-download
     *  self-heal re-resolves deliberately stay memo-less — a 403-refresh
     *  re-mints a rotating token and must never be refused by a stale mark.
     *  (Attempt budgets still apply registry-wide, self-heal included:
     *  a hung refresh is clipped and moves on, while a healthy refresh on
     *  a proven-history host keeps the generous 40s cap. Budget expiry is
     *  a discarded result inside the chain — it never cancels a task.) */
    private suspend fun resolveStreamUrl(
        permUrl: String,
        site: String,
        defaultQual: String,
        bypassHealth: Boolean = false,
        allowCacheHit: Boolean = false,
        memoTaskId: String? = null
    ): String? =
        com.anonrode.downloader.pipeline.LinkResolver.resolveChain(
            permUrl, site, defaultQual, bypassHealth = bypassHealth, allowCacheHit = allowCacheHit,
            memoTaskId = memoTaskId
        )

    /**
     * Thrown by [preflightHls] when the CDN rejects the playlist with 401/403:
     * the token stamped into the persisted URL has expired or been invalidated.
     * The engine answers by re-resolving from the source page (fresh token)
     * instead of failing — a persisted HLS URL must never be trusted twice.
     */
    private class StaleStreamLinkException(message: String) : Exception(message)

    /**
     * HLS pre-flight: fetch the master playlist, probe the first segment host,
     * and — when the playlist carries scheme-relative (//cdn/...) or relative
     * segment URIs — rewrite them to absolute https into a local file that
     * yt-dlp consumes instead of the original URL.
     *
     * Why the rewrite is load-bearing: yt-dlp joins a scheme-relative segment
     * URI to http:// (port 80) while browsers (hls.js) use https:// — these
     * CDNs answer 443 and hang port 80. Live-verified: the same vidbasic
     * master stalled forever at port 80 and downloaded from a rewritten
     * https master. A dead segment CDN additionally used to pin the task in
     * DOWNLOADING at 0 bytes for minutes (watchdog kills + 3 attempts =
     * user-reported "stuck on starting"); the probe turns that into a clean
     * failure in seconds.
     *
     * Returns the rewritten master file (null = original URL is fine), and
     * throws a user-facing message when the stream is unreachable.
     *
     * Also runs the Segment-Sampling Estimator (measured segment sizes, not
     * BANDWIDTH tags) and exposes the predicted total through
     * [estimatedTotalBytes] — a one-element out-holder (0 = no estimate) —
     * for future progress plumbing. The estimate is diagnostic-only: its
     * every failure logs hls-size-estimate=null and never disturbs the
     * preflight outcome.
     */
    private suspend fun preflightHls(
        context: Context,
        taskId: String,
        masterUrl: String,
        referer: String?,
        estimatedTotalBytes: LongArray = LongArray(1),
        requestedQuality: String? = null,
        resolutionOut: Array<String?> = arrayOfNulls(1)
    ): File? {
        val cacheDir = File(context.cacheDir, "hls").apply { mkdirs() }
        val created = mutableListOf<File>()
        var currentUrl = masterUrl
        var playlist: String? = null
        var staleToken = false
        try {
            HttpClient.get(currentUrl, referer = referer, tag = "preflight").use { res ->
                when {
                    // 401/403 on the master means the token stamped into the
                    // persisted URL is dead — a stale link, not a dead server.
                    // The engine re-resolves from the source page for a fresh one.
                    res.code == 401 || res.code == 403 -> staleToken = true
                    res.isSuccessful -> {
                        val text = HttpClient.cappedText(res)
                        if (text != null) {
                            if (text.startsWith("#EXTM3U") || text.contains("#EXT-X-STREAM-INF") || text.contains("#EXTINF")) {
                                playlist = text
                            } else if (looksLikeHtml(text) || text.contains("<!doctype", ignoreCase = true) || text.contains("<html", ignoreCase = true)) {
                                staleToken = true
                            } else {
                                playlist = text
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        when {
            staleToken -> throw StaleStreamLinkException("Stream link expired — fetching a fresh one")
            playlist == null -> throw Exception("Stream server unreachable — the playlist did not load. Try again later.")
        }
        var masterFile = rewriteHlsMaster(playlist, currentUrl, "hls-$taskId.m3u8", cacheDir)
        if (masterFile != null) {
            created.add(masterFile)
            // What yt-dlp is actually fed — the 0-byte HLS stall was
            // undiagnosable without seeing the rewritten playlist.
            com.anonrode.downloader.util.DebugLog.resolve(
                "task=$taskId rewritten master: ${masterFile.readText().take(300).replace("\n", " | ")}"
            )
        }

        // The master's RESOLUTION attributes are the REAL stream quality —
        // a requested "720p" can resolve to 1080p if that's all the host
        // offers. Mirror yt-dlp's height-limited format selection: the
        // highest variant at or below the requested height (or the smallest
        // when nothing fits). Exposed for the download card's quality chip.
        resolutionOut[0] = pickHlsResolution(playlist, requestedQuality)

        // Walk one variant level: rewrite referenced variant playlists to
        // local files too and repoint the master at them (relative name),
        // so their segments also go over https.
        val mediaLines = playlist.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .toList()
        var probeUrl: String? = null
        if (mediaLines.isNotEmpty()) {
            var firstResolved = resolveSegmentUrl(currentUrl, mediaLines[0])
            if (firstResolved != null && firstResolved.contains(".m3u8")) {
                // Fetch every variant playlist CONCURRENTLY: the old sequential
                // loop let one slow variant stall the whole preflight. Each
                // result keeps its original index so the first-variant probe /
                // size-estimate logic still runs in stream order afterwards.
                val variantResults = coroutineScope {
                    mediaLines.mapIndexedNotNull { idx, line ->
                        val vUrl = resolveSegmentUrl(currentUrl, line) ?: return@mapIndexedNotNull null
                        if (!vUrl.contains(".m3u8")) return@mapIndexedNotNull null
                        async {
                            var variant: String? = null
                            var variantStale = false
                            try {
                                HttpClient.get(vUrl, referer = referer, tag = "preflight").use { res ->
                                    when {
                                        res.code == 401 || res.code == 403 -> variantStale = true
                                        res.isSuccessful -> variant = HttpClient.cappedText(res)
                                    }
                                }
                            } catch (_: Exception) {}
                            idx to Triple(vUrl, variant, variantStale)
                        }
                    }
                }.awaitAll()
                // All fetches complete: a stale token surfaces first (in stream
                // order), then the variant-level rewrite/repoint/estimate runs.
                for ((idx, res) in variantResults) {
                    val (vUrl, variant, variantStale) = res
                    if (variantStale) throw StaleStreamLinkException("Stream link expired — fetching a fresh one")
                    if (variant == null) continue
                    val vFile = rewriteHlsMaster(variant, vUrl, "hls-$taskId-v$idx.m3u8", cacheDir)
                    if (vFile != null) created.add(vFile)
                    if (vFile != null && masterFile != null) {
                        // Repoint this variant's URI in the local master at the
                        // local variant file (same directory → relative name).
                        try {
                            masterFile.writeText(
                                masterFile.readText().replace(vUrl, vFile.name)
                            )
                        } catch (_: Exception) {}
                    }
                    if (idx == 0) {
                        val vSeg = variant.lineSequence().map { it.trim() }
                            .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                        if (vSeg != null) probeUrl = resolveSegmentUrl(vUrl, vSeg)
                        // Segment-Sampling Estimator: runs asynchronously in the
                        // background without blocking yt-dlp's launch.
                        triggerAsyncHlsSizeEstimate(taskId, playlist, currentUrl, vUrl, referer)
                    }
                }
            } else {
                probeUrl = firstResolved
                // The URL is a media playlist itself (single-variant stream):
                // estimate asynchronously in the background.
                triggerAsyncHlsSizeEstimate(taskId, playlist, currentUrl, currentUrl, referer)
            }
        }

        // Probe the segment host exactly as yt-dlp will reach it after the
        // rewrite — https first, http only as a fallback for https-dead CDNs.
        if (probeUrl != null) {
            if (!HttpClient.probe(probeUrl, referer, timeoutMs = 2_500L, tag = "preflight")) {
                val httpVariant = if (probeUrl.startsWith("https:")) {
                    "http:" + probeUrl.substringAfter("https:")
                } else null
                if (httpVariant == null || !HttpClient.probe(httpVariant, referer, timeoutMs = 2_500L, tag = "preflight")) {
                    val host = com.anonrode.downloader.pipeline.UrlClassifier.extractHost(probeUrl)
                    for (f in created) {
                        try { f.delete() } catch (_: Exception) {}
                    }
                    throw Exception("Stream CDN not responding ($host) — the source server is down or blocking this network. Try again later.")
                }
            }
        }
        return masterFile
    }

    /**
     * Segment-Sampling Estimator hook: probe a few real segments and log the
     * HLS size estimate (hls-size-estimate=...). Purely diagnostic — every
     * failure yields null and never disturbs preflightHls' outcome.
     */
    /**
     * Best-effort pick of the variant height a download will land on, from
     * the master playlist's EXT-X-STREAM-INF RESOLUTION attributes. Mirrors
     * yt-dlp's height-limited format selection (highest <= requested;
     * smallest if nothing fits). Null when the master declares no heights.
     */
    private fun pickHlsResolution(masterText: String, requestedQuality: String?): String? {
        val heights = mutableListOf<Int>()
        val re = Pattern.compile("""#EXT-X-STREAM-INF:[^\n]*RESOLUTION=\d+x(\d+)""")
        val m = re.matcher(masterText)
        while (m.find()) heights.add(m.group(1).toInt())
        if (heights.isEmpty()) return null
        val requested = requestedQuality?.filter { it.isDigit() }?.toIntOrNull()
        val chosen = when {
            requested == null -> heights.maxOrNull() ?: return null
            else -> heights.filter { it <= requested }.maxOrNull() ?: heights.min()
        }
        return "${chosen}p"
    }

    private suspend fun runSizeEstimate(
        taskId: String,
        masterText: String,
        masterUrl: String,
        variantUrl: String,
        referer: String?,
        estimatedTotalBytes: LongArray
    ) {
        val estimate = try {
            HlsSizeEstimator.estimate(masterText, masterUrl, variantUrl, referer)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        estimatedTotalBytes[0] = estimate ?: 0L
        com.anonrode.downloader.util.DebugLog.resolve(
            if (estimate != null) {
                "task=$taskId hls-size-estimate=$estimate (${estimate / (1024 * 1024)} MiB)"
            } else {
                "task=$taskId hls-size-estimate=null (no estimate)"
            }
        )
    }

    private fun triggerAsyncHlsSizeEstimate(
        taskId: String,
        masterText: String,
        masterUrl: String,
        variantUrl: String,
        referer: String?
    ) {
        engineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val estimate = try {
                HlsSizeEstimator.estimate(masterText, masterUrl, variantUrl, referer)
            } catch (_: kotlinx.coroutines.CancellationException) {
                null
            } catch (_: Exception) {
                null
            }
            if (estimate != null && estimate > 0L) {
                com.anonrode.downloader.util.DebugLog.resolve(
                    "task=$taskId async hls-size-estimate=$estimate (${estimate / (1024 * 1024)} MiB)"
                )
                val live = repository.find(taskId)
                if (live != null && live.status == TaskStatus.DOWNLOADING && live.totalBytes <= 0L) {
                    repository.updateProgress(
                        taskId = taskId,
                        downloaded = live.downloadedBytes,
                        total = estimate,
                        speed = live.speedBytesPerSec,
                        eta = live.etaSeconds
                    )
                }
            }
        }
    }

    /**
     * Rewrite a playlist's scheme-relative (//host/...) and relative segment
     * URIs to absolute https. Returns the rewritten file, or null when the
     * playlist already uses absolute URLs (nothing to fix).
     */
    private fun rewriteHlsMaster(playlist: String, baseUrl: String, outName: String, dir: File): File? {
        val sb = StringBuilder()
        var changed = false
        for (rawLine in playlist.lineSequence()) {
            val line = rawLine.trimEnd('\r')
            when {
                line.startsWith("//") -> {
                    sb.append("https:").append(line).append('\n')
                    changed = true
                }
                line.startsWith("#") -> {
                    var l = line
                    val m = URI_VALUE.matcher(l)
                    if (m.find()) {
                        val out = StringBuffer()
                        do {
                            val u = m.group(1) ?: ""
                            val repl = when {
                                u.startsWith("//") -> "https:$u"
                                u.startsWith("http://") || u.startsWith("https://") -> u
                                else -> resolveSegmentUrl(baseUrl, u) ?: u
                            }
                            m.appendReplacement(out, "URI=\"" + java.util.regex.Matcher.quoteReplacement(repl) + "\"")
                        } while (m.find())
                        m.appendTail(out)
                        l = out.toString()
                        if (l != line) changed = true
                    }
                    sb.append(l).append('\n')
                }
                else -> {
                    val resolved = resolveSegmentUrl(baseUrl, line)
                    if (resolved != null && resolved != line) {
                        sb.append(resolved).append('\n')
                        changed = true
                    } else {
                        sb.append(line).append('\n')
                    }
                }
            }
        }
        if (!changed) return null
        return try {
            val f = File(dir, outName)
            f.writeText(sb.toString())
            f
        } catch (_: Exception) {
            null
        }
    }

    /** Resolve a playlist-relative or protocol-relative URI against [base]. */
    private fun resolveSegmentUrl(base: String, uri: String): String? {
        if (uri.startsWith("http://") || uri.startsWith("https://")) return uri
        if (uri.startsWith("//")) return "https:$uri"
        return try {
            val b = java.net.URI(base)
            val qIdx = uri.indexOf('?')
            val pathPart = if (qIdx >= 0) uri.substring(0, qIdx) else uri
            val queryPart = if (qIdx >= 0) uri.substring(qIdx + 1) else null
            val joined = if (pathPart.startsWith("/")) {
                pathPart
            } else {
                b.path.substringBeforeLast('/') + "/" + pathPart
            }
            java.net.URI(b.scheme, null, b.host, b.port, joined, queryPart, null).toString()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Size of the produced artifact. A single-file download is a plain File,
     * but a multi-file torrent (season pack) lands as a DIRECTORY whose
     * File.length() is ~0 (or a block size) — the completion gate must sum
     * the tree instead of trusting the directory's own stat size.
     */
    private fun fileSize(file: File): Long {
        return if (file.isFile) file.length()
        else file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private suspend fun handleWatchdogStall(
        taskId: String,
        kills: Int,
        exhausted: Boolean,
        stallMsg: String,
        t: DownloadTask
    ) {
        android.util.Log.w("AnonDownload", "No download progress for ${t.episodeTitle}, (stall kill $kills)")
        YoutubeDlDownloader.killProcess(taskId)
        TurboDownloader.cancelTask(taskId)
        if (exhausted) {
            com.anonrode.downloader.util.RemoteLogger.report(
                type = "stall_watchdog_timeout",
                message = stallMsg,
                context = mapOf(
                    "episode" to t.episodeTitle,
                    "backend" to t.backend,
                    "site" to t.site,
                    "host" to (try { java.net.URI(t.directUrl).host } catch (_: Exception) { null }),
                    "attempts" to kills,
                    "downloaded_bytes" to t.downloadedBytes,
                    "total_bytes" to t.totalBytes
                )
            )
            // A user pause racing this kill already wrote its own terminal state —
            // never overwrite it with park/fail or a failure notification.
            val liveRow = repository.find(taskId)
            if (liveRow == null || liveRow.userPaused) {
                runtimeRegistry.getActiveJob(taskId)?.cancel()
                return
            }
            if (t.downloadedBytes == 0L && bytesLanded(t) == 0L && attemptCrossProviderFailover(t)) {
                runtimeRegistry.getActiveJob(taskId)?.cancel()
                return
            }
            if (!parkInsteadOfFail(t, stallMsg, forcePark = isRateLimitedError(stallMsg))) {
                repository.update(taskId) {
                    it.copy(status = TaskStatus.FAILED, errorMessage = stallMsg)
                }
                com.anonrode.downloader.service.DownloadService.notifyFailed(context, taskId, t.episodeTitle, stallMsg)
            }
            runtimeRegistry.getActiveJob(taskId)?.cancel()
        } else {
            // Preserve park counters across stall kills: markForManualRetry's
            // resetParkingAndFailoverState would wipe parkCycleCount/firstParkedAtMs
            // and defeat the anti-loop parking budget. Grant the bypass only.
            runtimeRegistry.grantRetryBypass(taskId)
        }
    }

    /**
     * Safely converts a TurboDownloader `.part` + `.turbo` pair into a contiguous prefix
     * file at [dest] so native `aria2c -c` can resume from the exact byte offset without
     * extension mismatches or orphaned files.
     */
    private fun handoffTurboPrefixToAria2c(dest: File) {
        try {
            val partFile = File(dest.absolutePath + ".part")
            val turboFile = File(dest.absolutePath + ".turbo")
            val ariaControl = File(dest.absolutePath + ".aria2")
            if (!partFile.exists()) return

            val prefix = if (turboFile.exists()) {
                TurboState(turboFile).contiguousPrefixBytes() ?: 0L
            } else {
                partFile.length()
            }

            if (prefix > 0L && prefix <= partFile.length() && !ariaControl.exists()) {
                // A stale dest must not block the prefix handoff — but delete it
                // ONLY when smaller than the prefix being placed, so a finished
                // file can never be destroyed by a stale .part (O2 hardening).
                if (dest.exists() && dest.length() < prefix) dest.delete()
                if (!dest.exists() && partFile.renameTo(dest)) {
                    RandomAccessFile(dest, "rw").use { it.setLength(prefix) }
                } else {
                    partFile.delete()
                }
            } else if (prefix == 0L) {
                partFile.delete()
            }
            // aria2c owns the transfer now: a stranded .turbo would shadow the
            // .aria2 progress read (O2b). Delete it whenever .part is gone.
            if (!partFile.exists() && turboFile.exists()) TurboState(turboFile).delete()
        } catch (e: Exception) {
            com.anonrode.downloader.util.DebugLog.error("handoffTurboPrefixToAria2c failed: ${e.message}")
        }
    }

    private fun startTask(initialTask: DownloadTask, lockAlreadyHeld: Boolean = false) {
        if (!lockAlreadyHeld && !runtimeRegistry.tryAcquireStartLock(initialTask.id)) return
        val live = repository.find(initialTask.id)
        if (live == null || live.status !in setOf(TaskStatus.QUEUED, TaskStatus.RESOLVING, TaskStatus.DOWNLOADING)) {
            runtimeRegistry.releaseStartLock(initialTask.id)
            return
        }
        com.anonrode.downloader.util.DebugLog.write("start task=${initialTask.id} url=${initialTask.directUrl.take(80)} backend=${initialTask.backend}")

        val job = engineScope.launch(start = CoroutineStart.LAZY) {
            var watchdogJob: Job? = null
            // Re-read the task row: the enqueue/extension-sync paths may have moved
            // filePath while this task waited in the queue, and every later decision
            // (dest name, rename guard, sidecar cleanup) must use the current row.
            var task = repository.find(initialTask.id) ?: initialTask
            val originalFilePath = initialTask.filePath
            try {
                if (!isActive) return@launch
                var streamUrl = com.anonrode.downloader.pipeline.UrlClassifier.stripPreviewParam(task.directUrl)
                val isMagnet = streamUrl.startsWith("magnet:", ignoreCase = true)
                val isSocial = task.showTitle.startsWith("Social/", ignoreCase = true) || task.backend.contains("yt-dlp")
                var permUrl = com.anonrode.downloader.pipeline.UrlClassifier.stripPreviewParam(
                    task.selectedMirrorUrl.ifBlank { task.sourceUrl }.ifBlank { streamUrl }
                )

                val bypassHealth = runtimeRegistry.consumeRetryBypass(task.id)
                if (bypassHealth) ResolveBudget.forgetTask(task.id)

                // Zero-Latency Resume: Check if streamUrl is already direct
                val isAlreadyDirect = isDirectMediaUrl(streamUrl) && !isKnownLockerHost(streamUrl)

                if (!isMagnet && !isSocial && !isAlreadyDirect) {
                    repository.update(task.id) { it.copy(status = TaskStatus.RESOLVING) }
                    updateServiceState(force = true)

                    val resolved = try {
                        kotlinx.coroutines.withTimeout(RESOLVE_TIMEOUT_MS) {
                            val candidates = if (task.mirrorUrls.isEmpty()) listOf(permUrl) else
                                MirrorPool.eligible(task.sourceUrl, task.mirrorUrls, permUrl, bytesLanded(task) > 0L)
                            val ordered = ResolveBudget.orderCandidates(candidates)
                            var result: String? = null
                            for (candidate in ordered) {
                                coroutineContext.ensureActive()
                                val output = try {
                                    resolveStreamUrl(candidate, task.site, task.quality ?: defaultQuality, bypassHealth = bypassHealth, allowCacheHit = true, memoTaskId = task.id)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) { null }
                                if (!output.isNullOrBlank()) {
                                    ResolveBudget.recordHostOk(candidate)
                                    permUrl = candidate
                                    if (task.mirrorUrls.isNotEmpty()) repository.update(task.id) {
                                        it.copy(selectedMirrorUrl = candidate)
                                    }
                                    result = output
                                    break
                                } else {
                                    ResolveBudget.recordHostFullPassFail(candidate)
                                }
                            }
                            result
                        }
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        com.anonrode.downloader.util.DebugLog.error("task=${task.id} resolution timed out after ${RESOLVE_TIMEOUT_MS / 1000}s")
                        if (attemptCrossProviderFailover(task)) return@launch
                        throw Exception("Link resolution timed out — the site took too long to answer. Retry, or try another server/episode.")
                    }
                    if (!resolved.isNullOrBlank()) {
                        com.anonrode.downloader.util.DebugLog.resolve("task=${task.id} resolved -> ${resolved.take(120)}")
                        runtimeRegistry.getOrCreate(task.id).resetParkingAndFailoverState()
                        ResolveBudget.forgetTask(task.id)
                        streamUrl = com.anonrode.downloader.pipeline.UrlClassifier.stripPreviewParam(resolved)
                    } else if (isKnownLockerHost(streamUrl) || !isDirectMediaUrl(streamUrl)) {
                        if (task.site.isNotBlank()) {
                            val host = com.anonrode.downloader.pipeline.UrlClassifier.extractHost(streamUrl)
                            com.anonrode.downloader.util.DebugLog.resolve("task=${task.id} resolver chain EMPTY for ${streamUrl.take(120)} (host=$host) — failing cleanly")
                            val hostReason = com.anonrode.downloader.pipeline.HostHealth.lastReason(streamUrl)
                            val reasonSuffix = if (!hostReason.isNullOrBlank()) " ($hostReason)" else ""
                            com.anonrode.downloader.util.DebugLog.error("task=${task.id} could not crack stream link (host=$host)$reasonSuffix for ${streamUrl.take(120)}")
                            if (attemptCrossProviderFailover(task)) return@launch
                            if (!com.anonrode.downloader.pipeline.HostHealth.isUsable(streamUrl)) {
                                val mins = maxOf(1L, com.anonrode.downloader.pipeline.HostHealth.remainingBackoffMs(streamUrl) / 60_000L)
                                com.anonrode.downloader.util.DebugLog.resolve("task=${task.id} host $host in backoff — parking for ~${mins}m")
                                val entry = runtimeRegistry.getOrCreate(task.id)
                                val cycles = entry.parkCycleCount.incrementAndGet()
                                val landed = bytesLanded(task)
                                if (cycles > MAX_PARK_CYCLES && landed == 0L) {
                                    entry.resetParkingAndFailoverState()
                                    throw Exception(
                                        "$host has been unreachable across $MAX_PARK_CYCLES cooldown cycles — giving up so the queue is not stuck on it. " +
                                            "Tap retry once the site is back."
                                    )
                                }
                                entry.firstParkedAtMs.compareAndSet(0L, System.currentTimeMillis())
                                entry.requeueNotBeforeMs.set(System.currentTimeMillis() + parkFloorMs(cycles))
                                ResolveBudget.forgetTask(task.id)
                                val saved = if (landed > 0) " — ${(landed / (1024L * 1024L)).coerceAtLeast(1)} MB already saved, it will resume" else ""
                                repository.update(task.id) {
                                    it.copy(
                                        status = TaskStatus.PAUSED,
                                        speedBytesPerSec = 0.0,
                                        errorMessage = "$PARKED_HOST_MESSAGE — will retry in ~${mins}m$saved"
                                    )
                                }
                                updateServiceState(force = true)
                                return@launch
                            }
                            throw Exception("Could not get a download link from the file host ($host) — it is not responding right now or the link expired. Try again in a few minutes, or choose another server/episode.")
                        }
                        com.anonrode.downloader.util.DebugLog.resolve("task=${task.id} resolver chain EMPTY for ${streamUrl.take(120)} — falling back to yt-dlp extractor")
                        android.util.Log.w("AnonDownload", "Resolver chain empty for $streamUrl, handing to yt-dlp")
                    }
                }

                val isHlsStream = com.anonrode.downloader.pipeline.UrlClassifier.isStreamingLink(streamUrl)
                val isProvablyDirect = isProvablyDirectFile(streamUrl) || isDirectMediaUrl(streamUrl) || isHlsStream
                val isEmbedOrPage = !isMagnet && !isProvablyDirect && run {
                    if (task.site.isNotBlank()) {
                        false
                    } else if (!isSocial) {
                        val tp = com.anonrode.downloader.data.net.HttpClient.probeTerminal(
                            streamUrl,
                            referer = getRefererForUrl(streamUrl)
                        )
                        tp?.totalBytes == null
                    } else {
                        true
                    }
                }
                val finalBackend = if (isSocial || isHlsStream || isEmbedOrPage || task.audioOnly) "yt-dlp" else "aria2c"
                val isExtractor = isSocial || task.audioOnly || isEmbedOrPage
                val effectiveSockets = com.anonrode.downloader.pipeline.HostPolicyRegistry.effectiveSocketsForUrl(
                    streamUrl,
                    task.parallelSockets
                )

                coroutineContext.ensureActive()

                // Synchronize file extension with the resolved direct media URL if no bytes have landed yet
                task = repository.find(task.id) ?: task
                if (!isMagnet && finalBackend == "aria2c" && bytesLanded(task) == 0L) {
                    val resolvedExt = com.anonrode.downloader.pipeline.UrlClassifier.extractMediaExtension(streamUrl)
                    val currentFile = File(task.filePath)
                    if (resolvedExt != null && !currentFile.extension.equals(resolvedExt, ignoreCase = true)) {
                        val updatedFile = File(currentFile.parentFile, "${currentFile.nameWithoutExtension}.$resolvedExt")
                        // Same reservation lock as enqueue(): without it a concurrent
                        // enqueue can pick this exact path between our snapshot and
                        // our update, and two tasks end up owning one destination.
                        val renamed = synchronized(enqueueLock) {
                            val pathTaken = repository.snapshot().any { it.id != task.id && it.filePath == updatedFile.absolutePath }
                            if (!pathTaken && !updatedFile.exists()) {
                                repository.update(task.id) { it.copy(filePath = updatedFile.absolutePath) }
                                true
                            } else false
                        }
                        if (renamed) {
                            task = repository.find(task.id) ?: task.copy(filePath = updatedFile.absolutePath)
                        }
                    }
                }

                repository.update(task.id) { t ->
                    t.copy(
                        status = TaskStatus.DOWNLOADING,
                        directUrl = if (isDirectMediaUrl(streamUrl) || isProvablyDirectFile(streamUrl) || isHlsStream) streamUrl else t.directUrl
                    )
                }
                updateServiceState(force = true)

                // Launch unified ProgressWatchdog
                watchdogJob = progressWatchdog.launchForTask(
                    scope = this,
                    initialTask = task,
                    streamUrl = streamUrl,
                    stallTimeoutSecProvider = { stallTimeoutSec }
                )


                val targetFolder = getDownloadDirectory(task.showTitle, createDirs = true)
                // Android 11+ direct writes to public Downloads silently fail
                // without "All files access" (MANAGE_EXTERNAL_STORAGE) — fail
                // fast with the fix instead of a confusing mid-download IO
                // error that looks like the site broke.
                if (!targetFolder.isDirectory || !targetFolder.canWrite()) {
                    throw Exception(
                        "Storage permission missing — downloads are saved to your Downloads folder. " +
                            "Enable \"All files access\" for Anonrode (Settings > Apps > Anonrode > Permissions)."
                    )
                }
                // Header propagation: honor task headers first (from queue/recipe),
                // falling back to host policy rules from OTA playbook, and finally default UA.
                val taskReferer = task.headers.entries.firstOrNull { it.key.equals("referer", ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }
                val taskUa = task.headers.entries.firstOrNull { it.key.equals("user-agent", ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }
                var refererToPass = taskReferer ?: getRefererForUrl(streamUrl)
                var uaToPass = taskUa ?: getUserAgentForUrl(streamUrl)
                fun buildHeaders(ref: String, ua: String): MutableMap<String, String> {
                    val m = mutableMapOf("User-Agent" to ua)
                    if (ref.isNotBlank()) m["Referer"] = ref
                    for ((k, v) in task.headers) {
                        if (!k.equals("user-agent", ignoreCase = true) && !k.equals("referer", ignoreCase = true)) {
                            m[k] = v
                        }
                    }
                    return m
                }
                var hdrs = buildHeaders(refererToPass, uaToPass)

                // HLS pre-flight: probe the segment CDN before yt-dlp so a dead
                // stream host fails cleanly in seconds instead of pinning the
                // task at 0 bytes for minutes (watchdog kills + 3 attempts).
                // Also returns a locally rewritten master (scheme-relative
                // segment URLs fixed to absolute https) for yt-dlp to consume.
                var hlsMasterFile: File? = null
                // Out-holder for the preflight's HLS size estimate (bytes,
                // 0 = none). Kept current across every preflight invocation
                // so a future consumer always sees the latest estimate.
                val hlsSizeEstimate = LongArray(1)
                // Out-holder for the real stream resolution from the master
                // playlist (e.g. "1080p"), shown on the download card.
                val hlsResolution = arrayOfNulls<String>(1)
                if (finalBackend == "yt-dlp" && isHlsStream) {
                    try {
                        hlsMasterFile = preflightHls(
                            context, task.id, streamUrl, refererToPass, hlsSizeEstimate,
                            task.quality ?: defaultQuality, hlsResolution
                        )
                    } catch (e: StaleStreamLinkException) {
                        // A persisted HLS URL carries a token that dies within
                        // hours — and the router trusts .m3u8 as "already
                        // direct", so a resumed/retried task NEVER re-resolved
                        // and looped 403s forever (live log 18:39: five starts,
                        // all 403). Go back to the source page for a fresh one.
                        com.anonrode.downloader.util.DebugLog.resolve("task=${task.id} ${e.message}")
                        val fresh = resolveStreamUrl(permUrl, task.site, task.quality ?: defaultQuality)
                        if (fresh.isNullOrBlank() || fresh == streamUrl) {
                            throw Exception("Stream link expired and no fresh link could be fetched — try again later.")
                        }
                        streamUrl = fresh
                        refererToPass = taskReferer ?: getRefererForUrl(streamUrl)
                        uaToPass = taskUa ?: getUserAgentForUrl(streamUrl)
                        hdrs = buildHeaders(refererToPass, uaToPass)
                        repository.update(task.id) { t ->
                            t.copy(
                                directUrl = if (isDirectMediaUrl(streamUrl) || isProvablyDirectFile(streamUrl) || isHlsStream) streamUrl else t.directUrl
                            )
                        }
                        hlsMasterFile = preflightHls(
                            context, task.id, streamUrl, refererToPass,
                            hlsSizeEstimate, task.quality ?: defaultQuality, hlsResolution
                        )
                    }
                }

                // Seed the estimated total into the progress display so the
                // UI shows "3.2 GB" immediately instead of "0 B" until yt-dlp
                // emits its first parsed line (the segment-sampling estimator
                // is the most accurate pre-download projection available).
                if (hlsSizeEstimate[0] > 0L) {
                    repository.updateProgress(task.id, downloaded = 0L, total = hlsSizeEstimate[0], speed = 0.0, eta = 0L)
                }
                // Same for the resolution: show the ACTUAL stream quality the
                // master offers at/below the requested height.
                hlsResolution[0]?.let { r ->
                    repository.update(task.id) { t -> t.copy(resolution = r) }
                }

                var producedFile: File? = null
                var turboFailure: TurboDownloader.TurboResult.Failure? = null
                var turboResult: TurboDownloader.TurboResult? = null
                var aria2Error: String? = null

                if (finalBackend == "aria2c" && !isMagnet) {
                    var dest = File(targetFolder, File(task.filePath).name)
                    var hdrs = buildHeaders(refererToPass, uaToPass)

                    // Sidecar state is needed BEFORE the pre-flight: a 100%
                    // complete file (paused during VALIDATING after Turbo moved
                    // .part -> dest) must never be re-fed to aria2c — `-c` on a
                    // finished file is HTTP 416 on Range-honoring servers or a
                    // 200-truncate-and-rewrite on Range-ignoring ones (O1).
                    val partFile = File(dest.absolutePath + ".part")
                    val turboSidecar = File(dest.absolutePath + ".turbo")
                    val ariaControlFile = File(dest.absolutePath + ".aria2")

                    // Repair an interrupted prefix handoff (audit finding 1): a
                    // .turbo WITHOUT its .part means the sidecar's plan refers to
                    // bytes that were renamed into dest — a Turbo sidecar resume
                    // would skip those ranges while writing a fresh .part and
                    // zero-fill the completed prefix. Truncate dest to the
                    // sidecar's contiguous prefix (the handoff's lost step) and
                    // drop the stale sidecar, so the state falls through to the
                    // dest-partial (aria2c) resume below — or to O1 if complete.
                    if (!partFile.exists() && turboSidecar.exists() && dest.exists()) {
                        val staleSidecar = turboSidecar
                        val handoffPrefix = try {
                            TurboState(staleSidecar).contiguousPrefixBytes()
                        } catch (_: Exception) {
                            null
                        }
                        if (handoffPrefix != null && handoffPrefix >= 0L && handoffPrefix < dest.length()) {
                            try {
                                RandomAccessFile(dest, "rw").use { it.setLength(handoffPrefix) }
                            } catch (_: Exception) {}
                        }
                        try { TurboState(staleSidecar).delete() } catch (_: Exception) {}
                        com.anonrode.downloader.util.DebugLog.engine(
                            "task=${task.id} repaired interrupted handoff: dest+.turbo state, prefix=$handoffPrefix"
                        )
                    }

                    val alreadyCompleteOnDisk = dest.exists() &&
                        !partFile.exists() && !turboSidecar.exists() && !ariaControlFile.exists() &&
                        ((task.totalBytes > 0L && dest.length() == task.totalBytes) ||
                            (task.totalBytes <= 0L && isImageFile(dest) && dest.length() >= 1024L &&
                                !looksLikeHtml(dest) && isImageValid(dest)) ||
                            (task.totalBytes <= 0L && dest.length() >= 50 * 1024L &&
                                !looksLikeHtml(dest) && decodeCheck(dest)))
                    if (alreadyCompleteOnDisk) {
                        com.anonrode.downloader.util.DebugLog.engine(
                            "task=${task.id} alreadyCompleteOnDisk bytes=${dest.length()} — skipping both engines (zero redo)"
                        )
                        producedFile = dest
                    }

                    // A real aria2c partial resumes via its control file; a
                    // dest-with-bytes partial (no .part) also resumes via aria2c.
                    // A Turbo .part keeps Turbo's own sidecar-driven resume; a
                    // stale .turbo WITHOUT its .part was repaired above.
                    val hasExistingAria2Partial = !alreadyCompleteOnDisk && (ariaControlFile.exists() ||
                        (dest.exists() && dest.length() > 0L && !partFile.exists()))

                    // Pre-flight runs ONLY for an actual resume: on the fresh path
                    // Turbo's single-RT probe (status + magic-byte sniff) IS the
                    // pre-flight, and a complete-on-disk file needs no URL
                    // validation at all — an expired token must never kill a
                    // finished download (O4 closure + O1).
                    if (!isSocial && !alreadyCompleteOnDisk && hasExistingAria2Partial) {
                        var rejection = StreamValidator.validateResult(streamUrl, hdrs)
                            if (rejection?.refreshable == true) {
                                coroutineContext.ensureActive()
                                val fresh = resolveStreamUrl(permUrl, task.site, task.quality ?: defaultQuality, allowCacheHit = false)
                                if (!fresh.isNullOrBlank() && fresh != streamUrl &&
                                    (isDirectMediaUrl(fresh) || isProvablyDirectFile(fresh))) {
                                    streamUrl = com.anonrode.downloader.pipeline.UrlClassifier.stripPreviewParam(fresh)
                                    refererToPass = taskReferer ?: getRefererForUrl(streamUrl)
                                    uaToPass = taskUa ?: getUserAgentForUrl(streamUrl)
                                    hdrs = buildHeaders(refererToPass, uaToPass)
                                    rejection = StreamValidator.validateResult(streamUrl, hdrs)
                                    coroutineContext.ensureActive()
                                    repository.update(task.id) { it.copy(directUrl = streamUrl) }
                                }
                            }
                        rejection?.let {
                            if (task.downloadedBytes == 0L && bytesLanded(task) == 0L && attemptCrossProviderFailover(task)) {
                                return@launch
                            }
                            throw PipelineError.ValidationFailed(it.reason)
                        }
                    }

                    val progressCb: (Long, Long, Long) -> Unit = { got, tot, bps ->
                        val eta = if (bps > 0 && tot > got) (tot - got) / bps else 0L
                        val liveTask = repository.find(task.id)
                        val safeEta = if (eta > 0L) eta else liveTask?.etaSeconds?.takeIf { it > 0L } ?: 0L
                        repository.updateProgress(
                            taskId = task.id,
                            downloaded = got,
                            total = tot,
                            speed = bps.toDouble(),
                            eta = safeEta
                        )
                        updateServiceState(force = false)
                    }

                    if (!alreadyCompleteOnDisk && !hasExistingAria2Partial) {
                        // Step 1: Primary execution via TurboDownloader
                        com.anonrode.downloader.util.DebugLog.engine(
                            "task=${task.id} turbo primary start sockets=$effectiveSockets url=${streamUrl.take(110)}"
                        )
                        turboResult = TurboDownloader.download(
                            url = streamUrl,
                            dest = dest,
                            headers = hdrs,
                            configuredSockets = effectiveSockets,
                            onProgress = progressCb,
                            taskId = task.id
                        )

                        // Step 2: Self-healing token refresh if CDN returned 401/403/404 or HTML decoy page
                        if (turboResult is TurboDownloader.TurboResult.Failure && coroutineContext.isActive) {
                            val tokenExpired = turboResult.httpStatus in listOf(401, 403, 404, 410) || turboResult.htmlPage
                            if (tokenExpired && !isSocial) {
                                val fresh = resolveStreamUrl(permUrl, task.site, task.quality ?: defaultQuality, allowCacheHit = false)
                                if (!fresh.isNullOrBlank() && fresh != streamUrl) {
                                    streamUrl = com.anonrode.downloader.pipeline.UrlClassifier.stripPreviewParam(fresh)
                                    refererToPass = taskReferer ?: getRefererForUrl(streamUrl)
                                    uaToPass = taskUa ?: getUserAgentForUrl(streamUrl)
                                    hdrs = buildHeaders(refererToPass, uaToPass)
                                    repository.update(task.id) { it.copy(directUrl = streamUrl) }

                                    turboResult = TurboDownloader.download(
                                        url = streamUrl,
                                        dest = dest,
                                        headers = hdrs,
                                        configuredSockets = effectiveSockets,
                                        onProgress = progressCb,
                                        taskId = task.id
                                    )
                                }
                            }
                        }

                        when (turboResult) {
                            is TurboDownloader.TurboResult.Success -> {
                                producedFile = turboResult.file
                            }
                            is TurboDownloader.TurboResult.Failure -> {
                                turboFailure = turboResult
                            }
                        }
                    }

                    // Step 3: Run aria2c when resuming an existing .aria2 partial OR
                    // as a single fallback after a RECOVERABLE Turbo failure.
                    // Never hand aria2c a link Turbo proved is an HTML page or a
                    // terminal 401/403/404/410/429 rejection (Fix 1B): aria2c
                    // sniffs nothing, so it would download the error page as
                    // media or burn retries hammering a dead URL.
                    val unrecoverableHttpOrHtml = turboFailure != null && (
                        turboFailure.htmlPage ||
                            turboFailure.httpStatus in listOf(401, 403, 404, 410, 429)
                        )
                    if (producedFile == null && !unrecoverableHttpOrHtml && coroutineContext.isActive) {
                        if (!hasExistingAria2Partial) {
                            handoffTurboPrefixToAria2c(dest)
                            com.anonrode.downloader.util.DebugLog.engine(
                                "task=${task.id} turbo failed (${turboFailure?.message}), falling back once to aria2c"
                            )
                        } else {
                            com.anonrode.downloader.util.DebugLog.engine(
                                "task=${task.id} resuming existing aria2c partial for ${dest.name}"
                            )
                        }
                        try {
                            producedFile = YoutubeDlDownloader.download(
                                context = context,
                                taskId = task.id,
                                sourceUrl = streamUrl,
                                targetDir = targetFolder,
                                preferredFilename = dest.name,
                                backend = "aria2c",
                                referer = refererToPass,
                                origin = com.anonrode.downloader.pipeline.UrlClassifier.extractOrigin(refererToPass),
                                customHeaders = hdrs,
                                ua = uaToPass,
                                parallelSockets = effectiveSockets,
                                quality = task.quality ?: defaultQuality,
                                isExtractorTask = false,
                                audioOnly = task.audioOnly,
                                onProgress = { dl, tot, spd, eta ->
                                    repository.updateProgress(task.id, dl, tot, spd, eta)
                                    updateServiceState(force = false)
                                },
                                magnetMaxAttempts = magnetMaxAttempts,
                                ytdlpMaxAttempts = ytdlpMaxAttempts,
                                hlsFragments = hlsFragmentConcurrency,
                                speedLimitKbs = globalSpeedLimitKbs,
                                torrentPeers = torrentPeers,
                                privacyMode = torrentPrivacyMode,
                                estimatedTotal = repository.find(task.id)?.totalBytes ?: task.totalBytes
                            )
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            aria2Error = e.message
                        }
                    }
                } else {

                    suspend fun runYtdlp(url: String, masterFile: File?): File? {
                        val runReferer = taskReferer ?: getRefererForUrl(url)
                        val runUa = taskUa ?: getUserAgentForUrl(url)
                        val runHdrs = buildHeaders(runReferer, runUa)
                        return YoutubeDlDownloader.download(
                            context = context,
                            taskId = task.id,
                            sourceUrl = url,
                            targetDir = targetFolder,
                            preferredFilename = File(task.filePath).name,
                            backend = finalBackend,
                            referer = runReferer,
                            origin = com.anonrode.downloader.pipeline.UrlClassifier.extractOrigin(runReferer),
                            ua = runUa,
                            customHeaders = runHdrs,
                            parallelSockets = effectiveSockets.coerceIn(1, 16),
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
                            torrentPeers = torrentPeers,
                            privacyMode = torrentPrivacyMode,
                            downloadSubs = downloadSubtitles && isExtractor,
                            subLang = subtitleLanguage,
                            hlsMasterFile = masterFile?.absolutePath,
                            estimatedTotal = if (hlsSizeEstimate[0] > 0L) hlsSizeEstimate[0] else task.totalBytes
                        )
                    }

                    // YoutubeDlDownloader.download throws on final failure (after
                    // ytdlpMaxAttempts), so the re-resolve below must catch it —
                    // otherwise a failed chain skips the fresh-token recovery and
                    // the task dies with the first URL's errors.
                    var firstError: Exception? = null
                    producedFile = try {
                        runYtdlp(streamUrl, hlsMasterFile)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        firstError = e
                        null
                    }
                    // HLS/embed tokenized URLs (master.m3u8?token=...) expire
                    // mid-flight: on failure, re-resolve for a fresh URL and try
                    // once more, mirroring the token refresh on the direct path.
                    // A fresh resolve also lands a fresh edge node/session, which
                    // resets a CDN byte-quota throttle (the observed recovery).
                    // The re-resolved URL invalidates the old rewritten master —
                    // run its own preflight (probe + rewrite) on the retry.
                    if (producedFile == null && coroutineContext.isActive && !isSocial) {
                        val freshUrl = resolveStreamUrl(permUrl, task.site, task.quality ?: defaultQuality)
                        if (!freshUrl.isNullOrBlank() && freshUrl != streamUrl) {
                            android.util.Log.w("AnonDownload", "yt-dlp failed, re-resolving for a fresh URL")
                            streamUrl = freshUrl
                            var freshMaster: File? = null
                            if (isHlsStream) {
                                try {
                                    freshMaster = preflightHls(context, task.id, freshUrl, taskReferer ?: getRefererForUrl(freshUrl), hlsSizeEstimate, task.quality ?: defaultQuality, hlsResolution)
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    // Fresh stream unreachable — report the first
                                    // chain's error instead.
                                    throw firstError ?: e
                                }
                            }
                            try {
                                producedFile = runYtdlp(freshUrl, freshMaster)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // Both attempt chains failed; report the first
                                // chain's error (it carries the attempt detail).
                                throw firstError ?: e
                            }
                        }
                    }
                    // Rewritten masters are scratch files in the cache dir —
                    // remove them once the chain is done (they can be large:
                    // 950+ rewritten segment lines).
                    if (hlsMasterFile != null) {
                        try {
                            val cacheDir = File(context.cacheDir, "hls")
                            cacheDir.listFiles { f -> f.name.startsWith("hls-${task.id}") }?.forEach { it.delete() }
                        } catch (_: Exception) {}
                        hlsMasterFile = null
                    }
                    if (producedFile == null && firstError != null) throw firstError
                }

                // A paused or cancelled job must never flip to a final state: the
                // download may have finished right as pause landed. The exception
                // propagates as CancellationException, which the catch below treats
                // as silent, leaving the PAUSED status intact.
                coroutineContext.ensureActive()

                if (producedFile != null && producedFile.exists()) {
                    // Normalize .unknown_video or .matroska extension if present
                    if (producedFile.extension.equals("unknown_video", ignoreCase = true) ||
                        producedFile.extension.equals("matroska", ignoreCase = true) ||
                        producedFile.extension.isBlank()) {
                        val properExt = if (task.filePath.lowercase().endsWith(".mkv") || producedFile.extension.equals("matroska", ignoreCase = true)) "mkv" else "mp4"
                        val corrected = File(producedFile.parentFile, "${producedFile.nameWithoutExtension}.$properExt")
                        if (!corrected.exists() && producedFile.renameTo(corrected)) {
                            producedFile = corrected
                        }
                    }
                    // For non-social downloads, ensure the artifact adopts the clean target filename
                    // if it was mangled by an extractor backend template.
                    if (!isSocial && producedFile.name != File(task.filePath).name) {
                        val targetDest = File(task.filePath)
                        if (targetDest.exists()) {
                            val isImg = targetDest.name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".webp") }
                            val isCorrupt = if (isImg) {
                                targetDest.length() < 1024L || looksLikeHtml(targetDest) || !isImageValid(targetDest)
                            } else {
                                targetDest.length() < 50 * 1024L || looksLikeHtml(targetDest) || !decodeCheck(targetDest)
                            }
                            if (isCorrupt) {
                                targetDest.delete()
                            }
                        }
                        if (!targetDest.exists() && producedFile.renameTo(targetDest)) {
                            producedFile = targetDest
                        }
                    }
                    repository.update(task.id) { it.copy(status = TaskStatus.VALIDATING) }
                    updateServiceState(force = true)
                }

                val validation = if (producedFile != null && producedFile.exists()) {
                    TorrentSecurityShield.validateDownloadedFile(producedFile, targetFolder)
                } else {
                    Pair(false, "File missing")
                }

                val isAudio = task.filePath.lowercase().let { it.endsWith(".mp3") || it.endsWith(".m4a") || it.endsWith(".aac") }
                val isImage = (producedFile?.name ?: task.filePath).lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".webp") }
                val minSize = if (isImage) 1024L else if (isAudio) 10 * 1024L else 50 * 1024L

                // ---- Tiered verification: answer "is it media / complete /
                // playable" with the strongest evidence available, instead of
                // the old ">5MB => trust it" size guess.
                // Tier 0 — transfer truth: non-HLS backends got the exact
                //           content length from the server; matching bytes =
                //           complete by protocol guarantee.
                // Tier 1 — structure: shield magic + atom scan (any known
                //           media atom in the head/tail windows proves a
                //           container, whatever its first box was).
                // Tier 2 — platform decode: MediaExtractor finds decodable
                //           tracks with a duration -> the OS itself confirms
                //           the file is playable media.
                // Tier 3 — HLS estimate: size within 90% of the
                //           segment-sampling estimate.
                val taskTotalBytes = repository.find(task.id)?.totalBytes ?: 0L
                val transferComplete = !isHlsStream && task.backend != "yt-dlp" &&
                    taskTotalBytes > 0 &&
                    producedFile != null && producedFile.exists() &&
                    fileSize(producedFile) >= taskTotalBytes
                val structuralOk = validation.first
                val decodeOk = !structuralOk && !transferComplete &&
                    producedFile != null && producedFile.exists() &&
                    producedFile.length() >= minSize &&
                    !looksLikeHtml(producedFile) && decodeCheck(producedFile)
                val hlsComplete = isHlsStream && hlsSizeEstimate[0] > 0 &&
                    producedFile != null && producedFile.exists() &&
                    producedFile.length() >= 0.9 * hlsSizeEstimate[0]
                val imageOk = isImage && producedFile != null && producedFile.exists() &&
                    producedFile.length() >= minSize && !looksLikeHtml(producedFile) && isImageValid(producedFile)
                val verified = transferComplete || structuralOk || decodeOk || hlsComplete || imageOk
                val path = when {
                    imageOk -> "image"
                    structuralOk -> "structure"
                    transferComplete -> "transfer"
                    decodeOk -> "decode"
                    hlsComplete -> "estimate"
                    else -> "none"
                }
                val note = when {
                    !verified || structuralOk || transferComplete || imageOk -> null
                    decodeOk -> "Downloaded — format not in the known container list, but the system decoder verified it plays."
                    else -> "Downloaded — file size matches the stream estimate."
                }

                if (producedFile != null && producedFile.exists() && fileSize(producedFile) >= minSize
                    && !looksLikeHtml(producedFile) && verified) {
                    // The block above ran without suspension points, so a pause
                    // landing mid-validation could not interrupt it. Re-check
                    // here: a cancelled job must never flip to COMPLETED after
                    // the user pressed pause.
                    coroutineContext.ensureActive()
                    val finalTitle = producedFile.nameWithoutExtension
                    val finalBytes = producedFile.length()
                    val targetPathBeforeCompletion = task.filePath
                    repository.update(task.id) {
                        it.copy(
                            filePath = producedFile.absolutePath,
                            episodeTitle = if (isSocial) finalTitle else it.episodeTitle,
                            downloadedBytes = finalBytes,
                            totalBytes = finalBytes,
                            speedBytesPerSec = 0.0,
                            etaSeconds = 0L,
                            status = TaskStatus.COMPLETED,
                            validationNote = note
                        )
                    }
                    task = repository.find(task.id) ?: task.copy(filePath = producedFile.absolutePath)

                    try {
                        File(producedFile.absolutePath + ".turbo").delete()
                        File(producedFile.absolutePath + ".part").delete()
                        File(producedFile.absolutePath + ".aria2").delete()
                        // Backends can produce a file whose name differs from the
                        // task's target (aria2c writes the URL's extension, e.g.
                        // .mp4, while the task targets .mkv) — without this the
                        // original target's sidecars are orphaned forever. Compare
                        // against the path captured BEFORE the completion write
                        // (task.filePath is the produced path by now). Never sweep
                        // a path another task now owns: the ext-sync rename frees
                        // the old name and a later enqueue may have taken it.
                        val otherTaskPaths = repository.snapshot()
                            .filter { it.id != task.id }
                            .mapTo(HashSet()) { it.filePath }
                        if (producedFile.absolutePath != targetPathBeforeCompletion &&
                            targetPathBeforeCompletion !in otherTaskPaths) {
                            File(targetPathBeforeCompletion + ".turbo").delete()
                            File(targetPathBeforeCompletion + ".part").delete()
                            File(targetPathBeforeCompletion + ".aria2").delete()
                            File(targetPathBeforeCompletion + ".ytdl").delete()
                        }
                        // The extension sync may have renamed the task's target
                        // (.mp4 → .mkv) before download start: the ORIGINAL path's
                        // sidecars are still orphaned unless swept too.
                        if (originalFilePath != task.filePath && originalFilePath != producedFile.absolutePath &&
                            originalFilePath != targetPathBeforeCompletion &&
                            originalFilePath !in otherTaskPaths) {
                            File(originalFilePath + ".turbo").delete()
                            File(originalFilePath + ".part").delete()
                            File(originalFilePath + ".aria2").delete()
                            File(originalFilePath + ".ytdl").delete()
                        }
                        // O3: bounded stem-family sweep — one directory listing
                        // clears every abandoned sidecar generation (a task whose
                        // extension alternated across 3+ runs could strand the
                        // oldest family forever). Sidecars only; data files and
                        // another task's live paths are never selected.
                        // Selection proven in scratch/test_zero_redo.py (section D).
                        val familyStems = setOf(
                            producedFile.nameWithoutExtension,
                            File(targetPathBeforeCompletion).nameWithoutExtension,
                            File(originalFilePath).nameWithoutExtension
                        )
                        val sidecarExts = setOf("part", "turbo", "aria2", "ytdl")
                        producedFile.parentFile?.listFiles()?.forEach { f ->
                            if (!f.isFile) return@forEach
                            val scExt = f.name.substringAfterLast('.', "")
                            if (scExt !in sidecarExts) return@forEach
                            val baseName = f.name.removeSuffix(".$scExt")
                            val basePath = File(f.parentFile, baseName).absolutePath
                            if (basePath == producedFile.absolutePath) return@forEach
                            if (basePath in otherTaskPaths || f.absolutePath in otherTaskPaths) return@forEach
                            var stem = baseName.substringBeforeLast('.', baseName)
                            if (baseName.endsWith(".part")) {
                                // Legacy two-level generation ("X.part.aria2" /
                                // "X.part.turbo"): strip the inner ".part" so the
                                // stem resolves to the data file's stem — but
                                // never touch it when the INNER data path is a
                                // live path of another task (that control file is
                                // its resume bookkeeping).
                                val legacyDataName = baseName.removeSuffix(".part")
                                val legacyDataPath = File(f.parentFile, legacyDataName).absolutePath
                                val legacyStem = legacyDataName.substringBeforeLast('.', "")
                                if (legacyStem.isNotBlank() && legacyStem in familyStems &&
                                    legacyDataPath !in otherTaskPaths) stem = legacyStem
                            }
                            if (stem in familyStems) f.delete()
                        }
                    } catch (_: Throwable) {}

                    // Main-looper hop inside: from this IO coroutine the old
                    // inline scanFile never bound the scanner service and
                    // silently no-opped — downloads stayed invisible to the
                    // gallery until a system rescan (user report 2026-09-13).
                    com.anonrode.downloader.util.MediaScan.notifyFile(context, producedFile)

                    if (completionNotifications) {
                        DownloadService.notifyCompleted(context, if (isSocial) finalTitle else task.episodeTitle)
                    }
                    com.anonrode.downloader.util.DebugLog.write("completed task=${task.id} file=${producedFile.absolutePath} bytes=$finalBytes validation=$path")
                    // Terminal cleanup: drop the registry entry so long queues do not
                    // accumulate keys. Guarded so a newer job for this task (a retry
                    // landing mid-completion) is never clobbered, and non-cancelling
                    // so this coroutine can finish its own tail (remove() would
                    // cancel it mid-flight).
                    val selfJob = coroutineContext[Job]
                    val reg = runtimeRegistry.get(task.id)
                    if (reg == null || reg.activeJob === selfJob) {
                        runtimeRegistry.forget(task.id)
                    }
                } else if (producedFile != null && producedFile.exists() && fileSize(producedFile) >= minSize
                    && !looksLikeHtml(producedFile)) {
                    // A real file IS on disk but no tier could verify it. Keep
                    // the file and say so honestly — never auto-delete it and
                    // never re-download over it (that is exactly what the old
                    // FAILED + retry path did, silently destroying the file).
                    coroutineContext.ensureActive()
                    val finalTitle = producedFile.nameWithoutExtension
                    val finalBytes = producedFile.length()
                    repository.update(task.id) {
                        it.copy(
                            filePath = producedFile.absolutePath,
                            episodeTitle = if (isSocial) finalTitle else it.episodeTitle,
                            downloadedBytes = finalBytes,
                            totalBytes = finalBytes,
                            speedBytesPerSec = 0.0,
                            etaSeconds = 0L,
                            status = TaskStatus.COMPLETED,
                            validationNote = "File saved, but its format could not be verified — check that it plays before keeping."
                        )
                    }
                    task = repository.find(task.id) ?: task.copy(filePath = producedFile.absolutePath)
                    // Main-looper hop inside: from this IO coroutine the old
                    // inline scanFile never bound the scanner service and
                    // silently no-opped — downloads stayed invisible to the
                    // gallery until a system rescan (user report 2026-09-13).
                    com.anonrode.downloader.util.MediaScan.notifyFile(context, producedFile)
                    com.anonrode.downloader.util.DebugLog.write("completed-unverified task=${task.id} file=${producedFile.absolutePath} bytes=$finalBytes validation=none")
                    val selfJob = coroutineContext[Job]
                    val reg = runtimeRegistry.get(task.id)
                    if (reg == null || reg.activeJob === selfJob) {
                        runtimeRegistry.forget(task.id)
                    }
                } else {
                    val errReason = when {
                        producedFile != null && looksLikeHtml(producedFile) -> "Server returned an HTML page instead of the file"
                        // Null-check the transfer outcome BEFORE the generic
                        // validation pair: with no file, validation.second is
                        // always "File missing", which used to swallow every
                        // real HTTP status the backends captured (the 429 storm
                        // was logged as a bare "File missing").
                        // An aria2c rate-limit outranks Turbo's earlier non-429
                        // status: THE RULE parks on 429, and parking keys off this
                        // text reaching isRateLimitedError (the aria2c message
                        // carries the real "status=429" line).
                        producedFile == null && !aria2Error.isNullOrBlank() && isRateLimitedError(aria2Error) -> aria2Error!!
                        producedFile == null && turboFailure?.htmlPage == true -> "Server returned an HTML page instead of the file — the link expired; retry to refresh it"
                        producedFile == null && turboFailure?.httpStatus != null -> "Download rejected by server (HTTP ${turboFailure.httpStatus}) — retry to refresh the link"
                        // The aria2c fallback no longer swallows its exceptions
                        // (Fix 4): its message carries the real failure cause.
                        producedFile == null && !aria2Error.isNullOrBlank() -> aria2Error!!
                        producedFile == null -> "Download failed — the server never produced a file"
                        !validation.first -> validation.second
                        else -> "Output file was too small or corrupted"
                    }
                    // THE RULE: a transfer that never produced a file parks
                    // when bytes are banked (partials stay on disk, the
                    // cooldown loop resumes them) or when the server explicitly
                    // said 429. A file that DID land but is garbage (HTML
                    // decoy, too-small output) must still fail loudly — its
                    // bytes are not banked progress, and parking on them would
                    // re-fetch the same garbage forever.
                    val live = repository.find(task.id) ?: task
                    // Restore cross-provider failover for the producedFile==null
                    // path (Fix 1A): a Turbo Failure whose aria2c fallback threw
                    // lands HERE (the aria2c try/catch stores into aria2Error),
                    // and this branch previously went straight to park/fail —
                    // silently disabling failover on broken direct downloads.
                    if (producedFile == null && live.downloadedBytes == 0L && bytesLanded(live) == 0L &&
                        attemptCrossProviderFailover(live)) {
                        return@launch
                    }
                    val parked = producedFile == null &&
                        parkInsteadOfFail(live, errReason, forcePark = isRateLimitedError(errReason))
                    if (!parked) {
                        if (producedFile != null && producedFile.exists() && (looksLikeHtml(producedFile) || fileSize(producedFile) < minSize || !validation.first)) {
                            try {
                                producedFile.delete()
                                File(producedFile.absolutePath + ".part").delete()
                                File(producedFile.absolutePath + ".turbo").delete()
                                File(producedFile.absolutePath + ".aria2").delete()
                            } catch (_: Throwable) {}
                        }
                        repository.update(task.id) { it.copy(status = TaskStatus.FAILED, errorMessage = errReason) }
                        com.anonrode.downloader.util.DebugLog.write("failed task=${task.id} reason=$errReason")
                        com.anonrode.downloader.service.DownloadService.notifyFailed(context, task.id, task.episodeTitle, errReason)
                    }
                }
            } catch (e: CancellationException) {
                // Cancelled. Nothing may outlive its job: kill the native
                // backend and any in-flight Turbo/resolver HTTP so a cancelled
                // task cannot keep draining data (defense in depth — pause()
                // and cancel() already do this; job cancellation can also come
                // from the queue processor or scope shutdown).
                YoutubeDlDownloader.killProcess(task.id)
                TurboDownloader.cancelTask(task.id)
                // Only cancel global in-flight HTTP for a FULL cancel (marked
                // in runtimeRegistry before the job was cancelled), not when
                // the user paused or the network parked the task — pause()/
                // pauseForNetwork already killed the specific backends, and a
                // global cancelInFlight would kill OTHER tasks' in-flight
                // requests — the cross-talk bug. The repository check stays
                // as a safety net for removals that bypassed cancel(). Even
                // then, only sweep when no other task is running.
                if (runtimeRegistry.isFullyCancelled(task.id) || repository.find(task.id) == null) {
                    if (!hasOtherActiveWork(task.id)) {
                        HttpClient.cancelInFlight()
                    }
                }
                // A pause landing during VALIDATING (after the first
                // ensureActive passed but before the terminal write) leaves the
                // status at VALIDATING — rescue it to PAUSED so the card is not
                // stuck mid-check.
                //
                // Ownership guard (engine-audit P2): a dying job unwinds AFTER
                // the queue may already have started its replacement (network
                // re-tag parks then requeues within the same tick). Writing
                // PAUSED over the live replacement's DOWNLOADING lied to the
                // user and silently disabled that run's watchdog. Rescue only
                // when no NEWER live job owns the task.
                val owner = runtimeRegistry.getActiveJob(task.id)
                if (owner == null || owner === coroutineContext[Job]) {
                    repository.update(task.id) { t ->
                        if (t.status == TaskStatus.DOWNLOADING || t.status == TaskStatus.RESOLVING || t.status == TaskStatus.VALIDATING) {
                            t.copy(status = TaskStatus.PAUSED, speedBytesPerSec = 0.0)
                        } else t
                    }
                }
            } catch (e: Exception) {
                if (coroutineContext.isActive) {
                    val errMsg = e.message ?: "Download error"
                    // THE RULE again: any backend chain that finally threw —
                    // including the "yt-dlp failed after 3 attempt(s)" wrap of
                    // a 429 storm — parks when bytes are banked or the server
                    // explicitly asked us to wait (StreamValidator's
                    // "HTTP 429" rejection throws into this catch).
                    val live = repository.find(task.id) ?: task
                    if (live.downloadedBytes == 0L && bytesLanded(live) == 0L && attemptCrossProviderFailover(live)) {
                        return@launch
                    }
                    if (!parkInsteadOfFail(live, errMsg, forcePark = isRateLimitedError(errMsg))) {
                        repository.update(task.id) { it.copy(status = TaskStatus.FAILED, errorMessage = errMsg) }
                        com.anonrode.downloader.service.DownloadService.notifyFailed(context, task.id, task.episodeTitle, errMsg)
                    }
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
                watchdogJob?.cancel()
                val thisJob = coroutineContext[Job]
                val entry = runtimeRegistry.get(task.id)
                val ownedByThisJob = entry != null && thisJob != null && entry.activeJob === thisJob
                if (ownedByThisJob && entry != null) {
                    entry.activeJob = null
                }
                // Never clear a newer job's start flag: release (and drop the
                // full-cancel flag) only when this job still owns the entry or
                // nobody does.
                if (entry == null || entry.activeJob == null) {
                    runtimeRegistry.releaseStartLock(task.id)
                    // This job's Turbo interaction has fully unwound by now; the
                    // flag must not linger for tasks that never re-enter Turbo.
                    TurboDownloader.clearCancelledFlag(task.id)
                }
                // A non-Exception throwable (Error) skips both catch blocks above:
                // repair a still-active row we own so a dead job can never hold a
                // concurrency slot forever. A row owned by a NEWER job is untouched.
                if (ownedByThisJob) {
                    val liveRow = repository.find(task.id)
                    if (liveRow != null && (liveRow.status == TaskStatus.DOWNLOADING || liveRow.status == TaskStatus.RESOLVING || liveRow.status == TaskStatus.VALIDATING)) {
                        repository.update(task.id) { t ->
                            if (t.status == TaskStatus.DOWNLOADING || t.status == TaskStatus.RESOLVING || t.status == TaskStatus.VALIDATING) {
                                t.copy(status = TaskStatus.PAUSED, speedBytesPerSec = 0.0)
                            } else t
                        }
                    }
                }
                // Rewritten HLS masters live in cacheDir/hls as scratch files
                // (950+ lines each). The inline sweep only ran on the success
                // path; a throw or cancel anywhere in the chain leaked them
                // until the OS reclaimed the cache (engine-audit P2). This
                // finally covers every exit; scoped by taskId so a concurrent
                // job for another task is never touched.
                try {
                    File(context.cacheDir, "hls").listFiles { f -> f.name.startsWith("hls-${task.id}") }
                        ?.forEach { it.delete() }
                } catch (_: Exception) {}
                updateServiceState(force = true)
                processQueue()
            }
        }

        runtimeRegistry.attachJob(initialTask.id, job)
        val current = repository.find(initialTask.id)
        if (current == null || current.userPaused || current.status !in setOf(TaskStatus.RESOLVING, TaskStatus.DOWNLOADING, TaskStatus.VALIDATING)) {
            job.cancel()
            // A pause landing after processQueue's status flip leaves the row in
            // RESOLVING/DOWNLOADING while this never-started LAZY job is killed
            // (its finally will never run): repair the row here, otherwise the
            // phantom active status holds a concurrency slot forever.
            if (current != null && current.userPaused &&
                (current.status == TaskStatus.RESOLVING || current.status == TaskStatus.DOWNLOADING)) {
                repository.update(initialTask.id) { t ->
                    if (t.userPaused && (t.status == TaskStatus.RESOLVING || t.status == TaskStatus.DOWNLOADING)) {
                        t.copy(status = TaskStatus.PAUSED, speedBytesPerSec = 0.0)
                    } else t
                }
            }
        } else {
            // LAZY + attachJob-first: the coroutine cannot execute before it is
            // registered, closing the window where a running job was invisible
            // to runtimeRegistry.activeRunningTaskIds().
            job.start()
        }
        // A job cancelled before it ever started (or one that finished this fast)
        // would otherwise leave a dead entry in runtimeRegistry — a stale key that
        // suppresses the runtimeRegistry.isEmpty()/none{} cancel-sweep heuristics.
        // Conditional remove: never clobbers a newer job for the same task.
        if (!job.isActive) {
            val entry = runtimeRegistry.get(initialTask.id)
            if (entry != null && entry.activeJob === job) {
                entry.activeJob = null
            }
            // The task row itself is gone (cancelled while this start was being
            // registered): drop the entry — no future run can ever clean it.
            if (current == null) {
                runtimeRegistry.forget(initialTask.id)
            }
        }
    }

    // ------------------------------------------------------------------
    // Bulk queue actions (Pause all / Resume all / Cancel all).
    //
    // The activity log showed users hand-tapping pause/cancel on card after
    // card (13 cancels in 2 seconds) because the queue screen had no bulk
    // actions. These run the SAME per-task teardown as the single-task
    // pause()/retry()/cancel() paths above — deliberately line-for-line,
    // so a bulk action can never diverge from what one card tap does —
    // with the service-state/processQueue churn batched to once per action
    // instead of once per task.

    /** Pause every task the user could pause card-by-card: running
     *  (DOWNLOADING/RESOLVING/VALIDATING) via pause()'s exact teardown, and
     *  QUEUED via pause()'s status write (a card tap on a queued card does
     *  the same). QUEUED tasks are included deliberately: each dying job's
     *  finally-guard calls processQueue(), which would otherwise start the
     *  queued tasks behind the user's back right after "Pause all".
     *  userPaused=true marks every one, so no auto-resume path (network
     *  reconnect, storage self-heal, host cooldown) will touch them. */
    fun pauseAll() {
        val pausable = repository.tasks.value.filter {
            it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.RESOLVING ||
                it.status == TaskStatus.VALIDATING || it.status == TaskStatus.QUEUED
        }
        if (pausable.isEmpty()) return
        com.anonrode.downloader.util.DebugLog.user("pause ALL (${pausable.size} tasks)")
        pausable.forEach { task ->
            val taskId = task.id
            // Same teardown as pause() — active jobs are cancelled, backends
            // killed, blocking HTTP swept once no task is left running.
            runtimeRegistry.getActiveJob(taskId)?.cancel()
            val entry = runtimeRegistry.get(taskId)
            if (entry != null) {
                entry.activeJob = null
                entry.isStarting.set(false)
            }
            YoutubeDlDownloader.killProcess(taskId)
            TurboDownloader.cancelTask(taskId)
            com.anonrode.downloader.util.DebugLog.user("pause $taskId")
            repository.update(taskId) { t ->
                // Status-guarded (engine-audit P2): a task that COMPLETED inside
                // the snapshot window must not be stamped PAUSED+userPaused —
                // that produced a "Paused at 100%" card with no auto-resume.
                if (t.status == TaskStatus.DOWNLOADING || t.status == TaskStatus.RESOLVING ||
                    t.status == TaskStatus.VALIDATING || t.status == TaskStatus.QUEUED
                ) t.copy(status = TaskStatus.PAUSED, speedBytesPerSec = 0.0, errorMessage = null, userPaused = true)
                else t
            }
        }
        // pause() sweeps in-flight HTTP only when the last job is gone; with
        // everything paused that condition holds by definition.
        if (runtimeRegistry.activeRunningTaskIds().isEmpty()) HttpClient.cancelInFlight()
        // One service/notification update for the whole batch, and NO
        // processQueue(): "Pause all" means everything stops, so the free
        // slots must not be refilled from the (now also paused) queue.
        updateServiceState(force = true)
    }

    /** Resume every PAUSED task via retry()'s exact path — including the ones
     *  carrying the userPaused mark: this IS the explicit user resume, the
     *  one path allowed to clear it. One processQueue() fills the slots. */
    fun resumeAll() {
        val paused = repository.tasks.value.filter { it.status == TaskStatus.PAUSED }
        if (paused.isEmpty()) return
        com.anonrode.downloader.util.DebugLog.user("resume ALL (${paused.size} tasks)")
        paused.forEach { task ->
            val taskId = task.id
            // Same per-task body as retry(): one host-health bypass each, a
            // fresh park/failover history, and the userPaused mark cleared.
            runtimeRegistry.markForManualRetry(taskId)
            repository.update(taskId) { it.copy(status = TaskStatus.QUEUED, errorMessage = null, userPaused = false) }
        }
        processQueue()
        updateServiceState(force = true)
    }

    /** Cancel every non-terminal task (QUEUED/DOWNLOADING/RESOLVING/VALIDATING/
     *  PAUSED) via cancel()'s exact teardown. Destructive — the UI confirms
     *  before calling this. Partial files are removed per task exactly as a
     *  single cancel does. */
    fun cancelAll() {
        val cancellable = repository.tasks.value.filter {
            it.status == TaskStatus.QUEUED || it.status == TaskStatus.DOWNLOADING ||
                it.status == TaskStatus.RESOLVING || it.status == TaskStatus.VALIDATING ||
                it.status == TaskStatus.PAUSED
        }
        if (cancellable.isEmpty()) return
        com.anonrode.downloader.util.DebugLog.user("cancel ALL (${cancellable.size} tasks)")
        cancellable.forEach { task ->
            val taskId = task.id
            // Same teardown as cancel(): mark full-cancel intent BEFORE job
            // cancellation, kill the backends, remove partial artifacts.
            val hadLiveJob = runtimeRegistry.getActiveJob(taskId) != null
            runtimeRegistry.markFullyCancelled(taskId)
            runtimeRegistry.getActiveJob(taskId)?.cancel()
            val entry = runtimeRegistry.get(taskId)
            if (entry != null) {
                entry.activeJob = null
                entry.isStarting.set(false)
            }
            YoutubeDlDownloader.killProcess(taskId)
            // Only gate a Turbo unwind; a queued task's flag would linger forever.
            TurboDownloader.cancelTask(taskId, isFullCancel = hadLiveJob)
            com.anonrode.downloader.util.DebugLog.user("cancel $taskId")
            val live = repository.find(taskId)
            // Cancel only what is STILL cancellable (engine-audit P2): if the
            // task completed between the snapshot and here, purging its
            // artifacts would delete a finished download and remove() would
            // drop its history entry.
            if (live != null && (live.status == TaskStatus.QUEUED || live.status == TaskStatus.DOWNLOADING ||
                    live.status == TaskStatus.RESOLVING || live.status == TaskStatus.VALIDATING ||
                    live.status == TaskStatus.PAUSED)
            ) {
                engineScope.launch {
                    purgeTaskArtifacts(live)
                }
                repository.remove(taskId)
            }
            runtimeRegistry.remove(taskId)
        }
        // Every job was cancelled above, so a global in-flight sweep cannot
        // cross-talk into another task — the condition cancel() relies on.
        if (runtimeRegistry.activeRunningTaskIds().isEmpty()) HttpClient.cancelInFlight()
        updateServiceState(force = true)
        processQueue()
    }
}
