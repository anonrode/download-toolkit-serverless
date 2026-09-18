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
    private val ASIAN_DRAMA_CLUSTER = setOf("asianc", "dramarain", "dramakey", "pluto", "nepu")
    private val WESTERN_CLUSTER = setOf("nkiri", "9jarocks", "naijavault", "naijaprey")
    private val DEDICATED_ASIAN_SITES = setOf("asianc", "dramarain", "dramakey")
    private val YEAR_REGEX = Regex("""\b(19\d\d|20[0-3]\d)\b""")

    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeJobs = ConcurrentHashMap<String, Job>()
    // Task ids whose NEXT resolution may bypass the HostHealth gate — one-shot
    // manual-retry tokens, consumed (read-and-remove) at the start of the next
    // startTask so a cooling-down host still gets the user's explicit attempt.
    private val retryBypassTasks = ConcurrentHashMap.newKeySet<String>()
    // Task ids cancelled via cancel() (as opposed to paused). Set BEFORE the
    // job is cancelled so the job's CancellationException handler can tell a
    // full cancel from a pause without racing repository.remove — only a full
    // cancel may kill global in-flight HTTP.
    private val fullyCancelledIds = ConcurrentHashMap.newKeySet<String>()

    // Park-cycle counter per task: how many times the cooldown parking path
    // has parked this task. Past MAX_PARK_CYCLES the task is FAILED instead of
    // parked again — a queue must never orbit a dead host forever. In-memory
    // like the cooldown loop itself: an app restart leaves parked tasks PAUSED
    // for manual resume, which is intended.
    private val parkCycleCounts = ConcurrentHashMap<String, Int>()

    // When a task was FIRST parked by the host-backoff path (epoch ms).
    // Powers the parked-lifetime give-up in the cooldown loop: measured from
    // the first park so repeated re-parks cannot reset the clock forever.
    private val parkedAtStamp = ConcurrentHashMap<String, Long>()

    // Earliest auto-re-queue time for a parked task (epoch ms). Every park
    // pushes this further out (60s x park-cycles, capped): a host that keeps
    // rejecting us — the vikingfile 429 storm in activity-log-share-5, where
    // each retry every couple of seconds deepened the rate-limit penalty —
    // gets seen on a decaying cadence instead of a fresh full resolve chain
    // per cooldown tick.
    private val requeueNotBefore = ConcurrentHashMap<String, Long>()

    // Cross-provider failover bookkeeping per task: the set of provider sites
    // already tried for this task (original site + every failover target), so
    // the failover loop walks NEW sites only and cannot ping-pong between two
    // equally dead providers.
    private val failoverSites = ConcurrentHashMap<String, MutableSet<String>>()

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
    var hlsFragmentConcurrency: Int = 16
    var globalSpeedLimitKbs: Int = 0          // 0 = unlimited
    var torrentPeers: Int = -1                // -1 = auto (RAM tier)
    var torrentPrivacyMode: Boolean = false   // qBittorrent anonymous-mode lessons
    var wifiOnlyAll: Boolean = false
    var clipboardDetect: Boolean = true
    var completionNotifications: Boolean = true
    var debugLogging: Boolean = false
    var logRetentionDays: Int = 7
    // Seal-parity subtitle settings (2026-09-14): yt-dlp extractor downloads
    // fetch subtitles in this language (default English) and embed them in
    // the merged file. Both live in downloader_settings like the rest.
    var downloadSubtitles: Boolean = true
    var subtitleLanguage: String = "en"
    var filterExplicitContent: Boolean = true

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
                    repository.update(t.id) { it.copy(status = TaskStatus.PAUSED, speedBytesPerSec = 0.0, errorMessage = null) }
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
                    val parkedAt = parkedAtStamp[t.id] ?: 0L
                    if (parkedAt > 0L && System.currentTimeMillis() - parkedAt > PARKED_LIFETIME_MS &&
                        maxOf(t.downloadedBytes, computeDiskBytes(t)) == 0L
                    ) {
                        parkedAtStamp.remove(t.id)
                        failoverSites.remove(t.id)
                        parkCycleCounts.remove(t.id)
                        requeueNotBefore.remove(t.id)
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
                    if (System.currentTimeMillis() < (requeueNotBefore[t.id] ?: 0L)) return@forEach
                    if (com.anonrode.downloader.pipeline.HostHealth.isUsable(t.directUrl)) {
                        // Max 2 requeues per 10s tick: a 18-task batch comes
                        // back over ~90s instead of one thundering herd.
                        if (requeued >= 2) return@forEach
                        requeued++
                        parkedAtStamp.remove(t.id)
                        requeueNotBefore.remove(t.id)
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
         *  pasted/share links already work - no new engine branch. sourceUrl
         *  stays the episode page, so the 401/403/404/HTML self-heal
         *  re-resolves from scratch if this token aged out between proof and
         *  tap. Null = the normal path, unchanged. */
        verifiedDirectUrl: String? = null,
        mirrorUrls: List<String> = emptyList()
    ): String {
        val downloadFolder = getDownloadDirectory(showTitle, createDirs = false)

        val taskId = synchronized(enqueueLock) {
            // Dedupe: never queue a second task for the same source URL while one
            // is already active or paused. Two tasks sharing a filePath write the
            // same .part concurrently and corrupt the output.
            val active = setOf(TaskStatus.QUEUED, TaskStatus.RESOLVING, TaskStatus.DOWNLOADING, TaskStatus.VALIDATING, TaskStatus.PAUSED)
            val existing = repository.snapshot().firstOrNull { it.sourceUrl == sourceUrl && it.status in active }
            if (existing != null) {
                existing.id
            } else {
                // Scraped names get the full noise cut; a DIRECT download's
                // name is the user's own prose (shared filename / pasted URL)
                // — safety only, never word deletion.
                val cleanTitle = sanitizeComponent(episodeTitle, 80, stripNoise = !isDirect)
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
                    id = UUID.randomUUID().toString(),
                    showTitle = showTitle,
                    episodeNum = episodeNum,
                    episodeTitle = episodeTitle,
                    // Handoff ONLY rides when processQueue's own isDirect
                    // predicate will agree (isDirectMediaUrl + not an
                    // unresolved locker page) — otherwise the task would sit
                    // in RESOLVING on a URL the resolvers can't re-crack.
                    // sourceUrl stays the episode page for the self-heal.
                    directUrl = if (
                        !isDirect &&
                        verifiedDirectUrl != null &&
                        isDirectMediaUrl(verifiedDirectUrl) &&
                        !isKnownLockerHost(verifiedDirectUrl)
                    ) verifiedDirectUrl else sourceUrl,
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
                    mirrorUrls = MirrorPool.sources(sourceUrl, mirrorUrls).filter { it != sourceUrl }
                )

                repository.addFirst(task)
                task.id
            }
        }

        processQueue()
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
                quality = quality
            )
            queued++
        }
        return queued
    }

    fun pause(taskId: String) {
        activeJobs[taskId]?.cancel()
        activeJobs.remove(taskId)
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
        if (activeJobs.isEmpty()) HttpClient.cancelInFlight()
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
            activeJobs[t.id]?.cancel()
            activeJobs.remove(t.id)
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
        activeJobs[taskId]?.cancel()
        activeJobs.remove(taskId)
        YoutubeDlDownloader.killProcess(taskId)
        TurboDownloader.cancelTask(taskId)
        // Same in-flight sweep as pause(): a network park mid-resolve must
        // not leave blocking HTTP draining data until its read timeout.
        if (activeJobs.isEmpty()) HttpClient.cancelInFlight()
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
        if (activeJobs.containsKey(taskId)) fullyCancelledIds.add(taskId)
        activeJobs[taskId]?.cancel()
        activeJobs.remove(taskId)
        YoutubeDlDownloader.killProcess(taskId)
        TurboDownloader.cancelTask(taskId)
        // Kill this task's own in-flight resolver HTTP immediately when it is
        // safe: with no other running task there is nothing to cross-talk
        // into. Otherwise the job's cancellation handler sweeps once this
        // task's current blocking call unwinds (bounded by the read timeout).
        if (activeJobs.isEmpty()) HttpClient.cancelInFlight()
        com.anonrode.downloader.util.DebugLog.user("cancel $taskId")
        if (task != null) {
            purgeTaskArtifacts(task)
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
            val urlExt = task.directUrl.substringBefore('?').substringBefore('#')
                .substringAfterLast('/').substringAfterLast('.', "")
            val sibling = if (urlExt.isNotBlank() && urlExt.length <= 5 && urlExt.all { it.isLetterOrDigit() }) {
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
            }
        } catch (_: Throwable) {}
    }

    fun retry(taskId: String) {
        // Each tap grants ONE bypass of the host health gate: the next
        // resolution attempt runs even while the host sits in HostHealth
        // backoff (consumed in startTask). Bounded so stale ids cannot grow.
        if (retryBypassTasks.size >= 16) retryBypassTasks.clear()
        retryBypassTasks.add(taskId)
        // Explicit resume/retry clears the user-pause mark — this is the ONLY
        // path allowed to undo a user pause (UI resume button and the
        // notification retry action both funnel through here).
        repository.update(taskId) { it.copy(status = TaskStatus.QUEUED, errorMessage = null, userPaused = false) }
        // A manual retry is a fresh start: previous park cycles and failover
        // history no longer apply to the user's explicit second chance.
        parkCycleCounts.remove(taskId)
        parkedAtStamp.remove(taskId)
        failoverSites.remove(taskId)
        requeueNotBefore.remove(taskId)
        processQueue()
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
        val tried = failoverSites.getOrPut(task.id) { ConcurrentHashMap.newKeySet() }
        if (!tried.add(task.site)) return false // already failed over FROM this site once
        if (tried.size > 3) {
            failoverSites.remove(task.id)
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
        // If isAsian: candidates filtered to ASIAN_DRAMA_CLUSTER.
        // If not isAsian: candidates filtered to WESTERN_CLUSTER (must not query DEDICATED_ASIAN_SITES).
        val allowedCluster = if (isAsian) ASIAN_DRAMA_CLUSTER else WESTERN_CLUSTER

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
                    candSite in ASIAN_DRAMA_CLUSTER
                } else {
                    candSite in WESTERN_CLUSTER && candSite !in DEDICATED_ASIAN_SITES
                }
                if (!clusterMatch) return@filter false

                if (card.url.isBlank()) return@filter false
                if (tried.any { it.equals(card.site, ignoreCase = true) }) return@filter false
                if (!com.anonrode.downloader.pipeline.HostHealth.isUsable(card.url)) return@filter false
                if (!titleMatches(query, card.title)) return@filter false

                // Layer 2: Release Year Matching
                val candYear = extractYear(card.year)
                    ?: extractYear(card.title)
                    ?: extractYear(card.url)
                if (origYear != null && candYear != null && origYear != candYear) {
                    com.anonrode.downloader.util.DebugLog.resolve(
                        "task=${task.id} failover: rejected card \"${card.title}\" due to year mismatch (orig=$origYear, cand=$candYear)"
                    )
                    return@filter false
                }

                // Layer 3: Country Tag Matching
                val candCountry = extractCountry("${card.title} ${card.category}", card.site)
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
            // itself in the ledger, then everything else.
            .sortedWith(
                compareByDescending<com.anonrode.downloader.data.models.ShowCard> { normalizeTitleQuery(it.title) == normQuery }
                    .thenByDescending { com.anonrode.downloader.pipeline.HostHealth.hasProvenLocker(it.url) }
            )
            .toList()

        // Hard deadline for the whole failover (episode lookups included).
        val failoverDeadline = System.currentTimeMillis() + 60_000L
        for (card in ranked) {
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
                    val matchingEp = details.episodes.firstOrNull { candEp ->
                        val (candS, candE) = parseCandidateSeasonAndEpisode(card, candEp)
                        candS == origSeason && candE == origEpNum
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
            failoverSites[task.id] = tried // keep the tried-set for the next cycle

            // Safe to delete partial files ONLY after verifying task is actively continuing (not user paused)
            try {
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

    internal fun extractCountry(text: String, site: String = ""): DramaCountry {
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
            WESTERN_CLUSTER.contains(site.lowercase()) -> DramaCountry.WESTERN
            else -> DramaCountry.UNKNOWN
        }
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
                    else if (f.name.endsWith(".ytdl") && f.name.startsWith(base)) f.length()
                    else 0L
                }
            }
            files.sumOf { f ->
                if (!f.isFile) 0L
                // Only this task's own outputs: the previous directory-wide
                // ".part/.ytdl anywhere" rule folded SIBLING tasks' partials
                // into this task's progress (their .part files live in the
                // same folder), corrupting the window the watchdog acts on.
                else if (f.name.startsWith(base) && !f.name.endsWith(".turbo")) f.length()
                else if (f.name.endsWith(".ytdl") && f.name.startsWith(base)) f.length()
                else 0L
            }
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
        val cycles = (parkCycleCounts[task.id] ?: 0) + 1
        parkCycleCounts[task.id] = cycles
        val now = System.currentTimeMillis()
        parkedAtStamp.putIfAbsent(task.id, now)
        val floorMs = parkFloorMs(cycles)
        requeueNotBefore[task.id] = now + floorMs
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
        // URI="..." inside HLS tags (EXT-X-KEY, EXT-X-MAP, EXT-X-MEDIA) — the
        // URI value may be scheme-relative or relative and needs the same
        // absolute-https rewrite as segment URIs.
        private val URI_VALUE = Pattern.compile("""URI="([^"]+)"""")

        private val STREAMING_QUERY_PATTERN = Regex("""[?&][^=&]*=(?:mpd|dash|hls)(?:&|$)""")

        // KNOWN_LOCKER_HOSTS + the isKnownLockerHost/isProvablyDirectFile
        // classifiers moved to pipeline.LinkResolver (2026-09-14) so the
        // search-verify oracle proves results with the SAME ladder downloads
        // use. The thin delegates below keep this file's ~20 call sites intact.

        // Stall handling: a window must move at least this many bytes to count
        // as live progress (HLS CDNs throttle to ~1 KB/s instead of dying; the
        // crawl is a stall in disguise). 64 KiB per 60s window ≈ 1 KiB/s floor.
        private const val CRAWL_WINDOW_BYTES = 64L * 1024

        // Kill the backend this many times before giving up. The yt-dlp wrapper
        // retries 3x, then the engine re-resolves a fresh URL (rotating token
        // and edge node) for another 3 attempts — the recovery chain needs room.
        private const val MAX_STALL_KILLS = 8
        /**
         * How long a live Turbo rate-limit cooldown (TurboDownloader.
         * throttleRemainingMs) holds the stall watchdog's hand. The
         * in-session wait IS the fix for the share-5 storm: bytes freeze for
         * minutes while the host calms down, then the same session resumes —
         * the download never dies mid-stream just because a CDN got polite.
         * This cap keeps a permanently blackholed host from holding a queue
         * slot forever in DOWNLOADING; past it the normal kill→park ladder
         * resumes and THE RULE parks the task with its partials.
         */
        private const val THROTTLE_SESSION_CAP_MS = 15 * 60_000L

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
        // give-up fires it. Measured from the FIRST park (parkedAtStamp uses
        // putIfAbsent), so repeated re-parks cannot reset the clock: a host
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
            //  - live-job count: coroutines actually running in activeJobs
            // The status count alone missed tasks whose status was written by a
            // path outside this scheduler (watchdog restart, post-pause zombie
            // writes) — the live-job count alone misses a task between its
            // status flip and its job registration. Taking the max closes the
            // window both ways: this is THE concurrency choke-point, and no
            // other code may start a backend outside it.
            val statusActive = currentTasks.count {
                it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.RESOLVING || it.status == TaskStatus.VALIDATING
            }
            val jobActive = activeJobs.values.count { it.isActive }
            val activeCount = maxOf(statusActive, jobActive)

            if (activeCount >= maxConcurrentDownloads) return

            // Never start a task the user explicitly paused (engine-audit P1:
            // no legitimate state is QUEUED + userPaused — only retry()/
            // resumeAll() clear the flag, and they set QUEUED themselves, so
            // this predicate can only ever reject a pause that raced a requeue).
            val nextTask = currentTasks.firstOrNull { it.status == TaskStatus.QUEUED && !it.userPaused } ?: return

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
     *  identical behavior to before the move. */
    private suspend fun resolveStreamUrl(permUrl: String, site: String, defaultQual: String, bypassHealth: Boolean = false): String? =
        com.anonrode.downloader.pipeline.LinkResolver.resolveChain(
            permUrl, site, defaultQual, bypassHealth = bypassHealth, allowCacheHit = false
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
                    res.isSuccessful -> playlist = HttpClient.cappedText(res)
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
                        // Segment-Sampling Estimator: measure real segment
                        // sizes instead of trusting BANDWIDTH tags.
                        // Runs on the first variant only (the one yt-dlp
                        // will consume by default).
                        runSizeEstimate(taskId, playlist, currentUrl, vUrl, referer, estimatedTotalBytes)
                    }
                }
            } else {
                probeUrl = firstResolved
                // The URL is a media playlist itself (single-variant stream):
                // estimate directly on it.
                runSizeEstimate(taskId, playlist, currentUrl, currentUrl, referer, estimatedTotalBytes)
            }
        }

        // Probe the segment host exactly as yt-dlp will reach it after the
        // rewrite — https first, http only as a fallback for https-dead CDNs.
        if (probeUrl != null) {
            if (!HttpClient.probe(probeUrl, referer, timeoutMs = 10_000L, tag = "preflight")) {
                val httpVariant = if (probeUrl.startsWith("https:")) {
                    "http:" + probeUrl.substringAfter("https:")
                } else null
                if (httpVariant == null || !HttpClient.probe(httpVariant, referer, timeoutMs = 8_000L, tag = "preflight")) {
                    val host = probeUrl.substringAfter("://").substringBefore('/')
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
                var permUrl = task.selectedMirrorUrl.ifBlank { task.sourceUrl }.ifBlank { streamUrl }

                // A manual retry tap grants ONE bypass of the HostHealth gate
                // for this start's FIRST resolution attempt (consume-on-use;
                // the stale-token / yt-dlp re-resolves later in this coroutine
                // must NOT bypass, so the flag is read here and not re-derived).
                val bypassHealth = retryBypassTasks.remove(task.id)

                // Zero-Latency Resume: Check if streamUrl is already direct
                val isAlreadyDirect = isDirectMediaUrl(streamUrl) && !isKnownLockerHost(streamUrl)

                if (!isMagnet && !isSocial && !isAlreadyDirect) {
                    repository.update(task.id) { it.copy(status = TaskStatus.RESOLVING) }
                    updateServiceState(force = true)

                    // Hard ceiling on link cracking: the resolver chain walks many
                    // hosts with retries, and without a timeout a slow site pinned
                    // the task in RESOLVING forever (user-reported).
                    val resolved = try {
                        kotlinx.coroutines.withTimeout(RESOLVE_TIMEOUT_MS) {
                            val candidates = if (task.mirrorUrls.isEmpty()) listOf(permUrl) else
                                MirrorPool.eligible(task.sourceUrl, task.mirrorUrls, permUrl, bytesLanded(task) > 0L)
                            var result: String? = null
                            for (candidate in candidates) {
                                coroutineContext.ensureActive()
                                val output = try {
                                    resolveStreamUrl(candidate, task.site, task.quality ?: defaultQuality, bypassHealth = bypassHealth)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) { null }
                                if (!output.isNullOrBlank()) {
                                    permUrl = candidate
                                    if (task.mirrorUrls.isNotEmpty()) repository.update(task.id) {
                                        it.copy(selectedMirrorUrl = candidate)
                                    }
                                    result = output
                                    break
                                }
                            }
                            result
                        }
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        com.anonrode.downloader.util.DebugLog.error("task=${task.id} resolution timed out after ${RESOLVE_TIMEOUT_MS / 1000}s")
                        // A site that cannot answer within the ceiling is dead
                        // to this download — but the title may live on another
                        // provider. Fail over before reporting failure.
                        if (attemptCrossProviderFailover(task)) return@launch
                        throw Exception("Link resolution timed out — the site took too long to answer. Retry, or try another server/episode.")
                    }
                    if (!resolved.isNullOrBlank()) {
                        com.anonrode.downloader.util.DebugLog.resolve("task=${task.id} resolved -> ${resolved.take(120)}")
                        // A successful resolve resets the parking bookkeeping:
                        // park-cycle counting starts fresh for the NEXT host
                        // trouble, and the failover tried-set is retired (the
                        // task found a working source).
                        parkCycleCounts.remove(task.id)
                        parkedAtStamp.remove(task.id)
                        failoverSites.remove(task.id)
                        requeueNotBefore.remove(task.id)
                        streamUrl = resolved
                    } else if (isKnownLockerHost(streamUrl) || !isDirectMediaUrl(streamUrl)) {
                        if (task.site.isNotBlank()) {
                            // A locker page whose cracking failed. yt-dlp cannot
                            // parse these sites — handing it the page URL produced
                            // the guaranteed "Unsupported URL" failure (user-reported).
                            // Fail with the real reason instead; retry re-runs the
                            // resolver, which recovers from transient host issues.
                            // Name the ACTUAL failing host (the locker, e.g.
                            // loadedfiles.net), not the provider site (9jarocks) —
                            // the provider is fine; the locker is what refused us.
                            val host = streamUrl.trim().substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':')
                            com.anonrode.downloader.util.DebugLog.resolve("task=${task.id} resolver chain EMPTY for ${streamUrl.take(120)} (host=$host) — failing cleanly")
                            // Also flag it as an ERROR-category line: a resolution
                            // failure is the task's terminal outcome, and the log
                            // audit showed these only as RESOLVE lines (audit
                            // finding: "silent failures, no ERROR line").
                            val hostReason = com.anonrode.downloader.pipeline.HostHealth.lastReason(streamUrl)
                                ?: com.anonrode.downloader.data.net.HttpClient.lastFailure
                            val reasonSuffix = if (!hostReason.isNullOrBlank()) " ($hostReason)" else ""
                            com.anonrode.downloader.util.DebugLog.error("task=${task.id} could not crack stream link (host=$host)$reasonSuffix for ${streamUrl.take(120)}")
                            // Cross-provider auto-failover BEFORE parking or
                            // failing: if another provider can serve this same
                            // title, switch the task to that source and requeue.
                            // This is the "tapped a dead site, downloaded from a
                            // live one anyway" behavior — a dead chain on one
                            // site must never kill a download that the app could
                            // still fetch from another.
                            if (attemptCrossProviderFailover(task)) return@launch
                            // The locker host itself is in HostHealth backoff:
                            // PARK the task instead of failing it — the
                            // cooldown auto-retry loop re-queues it once the
                            // host recovers. No throw, no completion
                            // notification; filePath/directUrl stay as-is.
                            if (!com.anonrode.downloader.pipeline.HostHealth.isUsable(streamUrl)) {
                                val mins = maxOf(1L, com.anonrode.downloader.pipeline.HostHealth.remainingBackoffMs(streamUrl) / 60_000L)
                                com.anonrode.downloader.util.DebugLog.resolve("task=${task.id} host $host in backoff — parking for ~${mins}m")
                                val cycles = (parkCycleCounts[task.id] ?: 0) + 1
                                val landed = bytesLanded(task)
                                // THE RULE: the cycle cap only ever fires for a
                                // task with NOTHING banked. Bytes on disk mean
                                // park forever — the give-up below would cancel
                                // real saved progress, which the user has ruled
                                // out ("if the download ever started there is
                                // no reason to cancel").
                                if (cycles > MAX_PARK_CYCLES && landed == 0L) {
                                    parkCycleCounts.remove(task.id)
                                    failoverSites.remove(task.id)
                                    throw Exception(
                                        "$host has been unreachable across $MAX_PARK_CYCLES cooldown cycles — giving up so the queue is not stuck on it. " +
                                            "Tap retry once the site is back."
                                    )
                                }
                                parkCycleCounts[task.id] = cycles
                                parkedAtStamp.putIfAbsent(task.id, System.currentTimeMillis())
                                requeueNotBefore[task.id] = System.currentTimeMillis() + parkFloorMs(cycles)
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
                        // Social URLs: yt-dlp's generic extractor genuinely cracks
                        // these, so keep the fallback there.
                        com.anonrode.downloader.util.DebugLog.resolve("task=${task.id} resolver chain EMPTY for ${streamUrl.take(120)} — falling back to yt-dlp extractor")
                        android.util.Log.w("AnonDownload", "Resolver chain empty for $streamUrl, handing to yt-dlp")
                    }
                }

                val isHlsStream = isStreamingLink(streamUrl)
                // A URL with a real media extension goes to the DIRECT path even
                // when its host is a known locker: resolver OUTPUT is the cracked
                // file itself, and Turbo's probe rejects any server that lies and
                // serves HTML — a clean failure, never an HTML-as-video download.
                // Routing those through yt-dlp's generic extractor (the old
                // behavior) was slower and could misfire. Only non-media URLs
                // (pages/embeds) are extractor tasks for yt-dlp to crack.
                val isProvablyDirect = isProvablyDirectFile(streamUrl) || isDirectMediaUrl(streamUrl)
                val isEmbedOrPage = !isMagnet && !isProvablyDirect && run {
                    if (!isSocial) {
                        // Non-social URLs: probe unknown links. If the server answers with 200..206
                        // and valid media bytes, it is a direct media file from an unlisted host,
                        // not an embed page.
                        val tp = com.anonrode.downloader.data.net.HttpClient.probeTerminal(streamUrl, referer = getRefererForUrl(streamUrl))
                        tp?.totalBytes == null
                    } else true
                }
                val finalBackend = if (isSocial || isHlsStream || isEmbedOrPage || task.audioOnly) "yt-dlp" else "aria2c"
                val isExtractor = isSocial || task.audioOnly || isEmbedOrPage
                com.anonrode.downloader.util.DebugLog.engine(
                    "task=${task.id} route: social=$isSocial hls=$isHlsStream embedOrPage=$isEmbedOrPage provablyDirect=${isProvablyDirectFile(streamUrl)} mediaExt=${isDirectMediaUrl(streamUrl)} -> backend=$finalBackend url=${streamUrl.take(110)}"
                )

                // kissorgrab.com rejects multi-connection downloads; force a single
                // socket there (monolith parity, downloader.py aria2c forced 1/1).
                // dl.plutomovies.com redirects to kissorgrab (live-verified 2026-08-22
                // activity log: turbo 16-socket → redirect → kissorgrab kills multi → fail
                // → yt-dlp rescue → "Unsupported URL" on -mkv → loop).
                val effectiveSockets = if (streamUrl.lowercase().let { u ->
                        u.contains("kissorgrab.com") || u.contains("dl.plutomovies.com")
                    }) 1 else task.parallelSockets

                coroutineContext.ensureActive()
                // Persist the resolved URL only when it's genuinely downloadable.
                // A failed crack must not persist a source embed page as
                // directUrl — restarting on a page URL made the task loop
                // forever on yt-dlp "Unsupported URL" (user-reported nepu movie
                // stuck on "starting"). Keeping the original directUrl makes a
                // retry re-resolve from the source; real HLS/MP4 outputs are
                // still persisted for zero-latency resume.
                repository.update(task.id) { t ->
                    t.copy(
                        status = TaskStatus.DOWNLOADING,
                        directUrl = if (isDirectMediaUrl(streamUrl) || isProvablyDirectFile(streamUrl)) streamUrl else t.directUrl
                    )
                }
                updateServiceState(force = true)

                // Progress watchdog: keeps the UI honest with filesystem truth and
                // kills a stalled backend so a hung download cannot stay DOWNLOADING
                // forever with 0.0 MB/s. Runs until the job is paused/cancelled or
                // the task leaves DOWNLOADING.
                launch {
                    var lastDisk = 0L
                    var lastParsed = 0L
                    var lastActivity = System.currentTimeMillis()
                    var lastKillTime = 0L
                    var stallKills = 0
                    // Epoch of the CURRENT rate-limit stagnation episode (0 =
                    // none). Reset whenever real bytes move; drives the
                    // THROTTLE_SESSION_CAP_MS grace below.
                    var throttleFirstSeen = 0L
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
                    // Rate-drop detection: some CDNs (vidsrc edge nodes) grant a
                    // fast burst (~40 MB at full speed) then collapse to a
                    // token-bucket trickle (~0.1-1.8 MiB/s, oscillating). The
                    // crawl floor can't see this — bytes DO move — but the drop
                    // from the task's own best window is unmistakable.
                    var bestWindowBps = 0.0
                    var lastProgressLog = 0L
                    // Zombie cap: the crawl floor (64KiB/60s ≈ 1KiB/s) lets a
                    // task trickling just above it escape forever — the rate-drop
                    // detector needs a ≥1MiB/s burst it never had. After 4x the
                    // stall timeout with under 1MiB moved total, the stream is
                    // effectively dead; fail it outright instead of relaunching.
                    val watchdogStart = System.currentTimeMillis()
                    var startBytes: Long? = null
                    while (isActive) {
                        delay(2000)
                        if (!isActive) break
                        val t = repository.find(task.id) ?: break
                        when (t.status) {
                            // DOWNLOADING is the watchdog's home turf: the
                            // stall/crawl/zombie/throttle logic below runs
                            // only while the backend moves (or should move).
                            TaskStatus.DOWNLOADING -> {}
                            // Token-refresh blip: the coroutine owning this
                            // loop flips the task to RESOLVING and back, so
                            // the watchdog must survive the window.
                            TaskStatus.RESOLVING -> continue
                            // Terminal/parked states: this loop must EXIT.
                            // A parked-cooldown task's job is otherwise kept
                            // alive by the loop forever, re-listing the
                            // filesystem every 2s and pinning the task in
                            // activeJobs (the leak this fixes).
                            else -> break
                        }
                        val now = System.currentTimeMillis()
                        val disk = computeDiskBytes(t)
                        val parsed = t.downloadedBytes
                        val diskDelta = disk - lastDisk
                        val diskGrew = disk > lastDisk
                        val parsedGrew = parsed > lastParsed
                        if (diskGrew) lastDisk = disk
                        if (parsedGrew) lastParsed = parsed
                        if (diskGrew || parsedGrew) {
                            lastActivity = now
                            throttleFirstSeen = 0L
                        }
                        // A Turbo rate-limit cooldown (429/503) is INTENTIONAL
                        // idle-waiting, not a stall: bytes will not move because
                        // the transfer is politely waiting — the exact behavior
                        // that keeps 1DM alive on the same host. No kill signal
                        // fires while a cooldown is live; the grace is capped so
                        // a fully-blackholed host cannot hold a queue slot
                        // forever, after which the normal kill→park ladder
                        // resumes and THE RULE parks the task with its partials.
                        if (TurboDownloader.throttleRemainingMs(task.id) > 0L) {
                            if (throttleFirstSeen == 0L) throttleFirstSeen = now
                            lastActivity = now
                        }
                        val throttleGrace = throttleFirstSeen > 0L &&
                            now - throttleFirstSeen <= THROTTLE_SESSION_CAP_MS
                        // Periodic progress beacon: the log recorded nothing
                        // between start and kill, so a stalled task and a slow
                        // one were indistinguishable (audit finding). Emit every
                        // ~10s while bytes actually move.
                        val beaconMoved = (disk - windowStartDisk) + (parsed - windowStartParsed)
                        if (beaconMoved > 0 && now - lastProgressLog >= 10_000L) {
                            lastProgressLog = now
                            com.anonrode.downloader.util.DebugLog.engine(
                                "task=${task.id} progress disk=${disk / 1024}KiB parsed=${parsed / 1024}KiB window=${(beaconMoved / 1024).toInt()}KiB"
                            )
                        }
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
                        // Merge/rename churn: yt-dlp's ffmpeg pass deletes the
                        // .fNNN input shards after writing the merged output, so
                        // a task's own footprint can legitimately DROP mid-run —
                        // and the backend may also reset its parsed counter on a
                        // retry. A shrinking total is active file manipulation,
                        // never a network stall: re-baseline the window instead
                        // of letting the negative delta read as "throttled"
                        // (app-2026-08-31 07:13: window=-140231KiB during the
                        // merge killed a healthy download and the next attempt
                        // shipped the unmerged video-only shard — a mute video).
                        if (moved < 0) {
                            windowStartDisk = disk
                            windowStartParsed = parsed
                            windowStartTime = now
                            lastActivity = now
                        }
                        val windowSecs = ((now - windowStartTime).coerceAtLeast(500L)) / 1000.0
                        val windowBps = if (windowSecs > 0.0) moved / windowSecs else 0.0
                        if (windowBps > bestWindowBps) bestWindowBps = windowBps
                        if (moved >= CRAWL_WINDOW_BYTES) {
                            windowStartDisk = disk
                            windowStartParsed = parsed
                            windowStartTime = now
                            // Reset the kill counter only when the window speed
                            // shows genuine recovery — a throttled relaunch still
                            // moves bytes, just slowly. A task that never had a
                            // fast burst (naturally slow stream) resets on any
                            // progress.
                            if (windowBps >= bestWindowBps * 0.5 || bestWindowBps < 1.0 * 1024 * 1024) {
                                stallKills = 0 // real progress — recovery worked
                            }
                        }
                        val crawlStalled = now - windowStartTime > STALL_TIMEOUT_MS
                        // Zombie cap: far past the stall timeout with almost
                        // nothing moved, the stream is dead — do not relaunch.
                        val totalBytesNow = disk + parsed
                        if (startBytes == null) startBytes = totalBytesNow
                        // Torrents bootstrap slowly: DHT/tracker discovery and
                        // metadata fetch routinely take 1-3 minutes with <64KiB
                        // moved (live log 2026-08-22: magnet killed at 93s with
                        // crawl=true before the swarm ever flowed). Give magnet
                        // tasks a much longer leash and skip the crawl/stall kill.
                        val magnetTask = streamUrl.startsWith("magnet:", ignoreCase = true)
                        val zombie = now - watchdogStart > STALL_TIMEOUT_MS * (if (magnetTask) 20 else 4) &&
                            (totalBytesNow - (startBytes ?: totalBytesNow)) < 1L * 1024 * 1024
                        // Rate-drop detection (corrected): a throttled-but-alive
                        // stream (e.g. downloadwella burst then 50KB/s trickle) must
                        // NOT be killed just because the *current window* is slower
                        // than the burst peak. The previous rule windowBps <
                        // bestWindowBps * 0.4 culled healthy 92MB/s downloads the
                        // moment they dropped to 50KB/s — a server-side throttle
                        // (or just a slower segment of the file) was indistinguishable
                        // from a dead connection, and the wrapper's 3-attempt cycle
                        // never recovered. New rule: kill only when the current
                        // window is BOTH (a) at least 2x slower than the burst peak
                        // AND (b) below a sane absolute floor (8 KiB/s for direct
                        // downloads, 2 KiB/s for HLS — a vidsrc edge that crawls at
                        // 1-1.8 KiB/s is genuinely alive, not throttling). Anything
                        // above the absolute floor is "slow but moving" and the
                        // user explicitly preferred patience over false failures:
                        // a slow ISP line should not lose a 2 GiB file 90% in.
                        val FLOOR_DIRECT_BPS = 8L * 1024L
                        val FLOOR_HLS_BPS = 2L * 1024L
                        val floorBps = if (isHlsStream) FLOOR_HLS_BPS else FLOOR_DIRECT_BPS
                        val throttled = bestWindowBps >= 1.0 * 1024 * 1024 &&
                            windowBps < bestWindowBps * 0.5 &&
                            windowBps < floorBps &&
                            windowSecs >= 30
                        if (zombie && !throttleGrace) {
                            val zombieMsg = "Download made no meaningful progress (${(now - watchdogStart) / 1000}s, under 1 MiB) — the source server is throttling or unreachable. Try again later."
                            com.anonrode.downloader.util.DebugLog.engine(
                                "task=${task.id} zombie cap after ${(now - watchdogStart) / 1000}s with ${((totalBytesNow - (startBytes ?: totalBytesNow)) / 1024)}KiB total"
                            )
                            YoutubeDlDownloader.killProcess(task.id)
                            TurboDownloader.cancelTask(task.id)
                            // Park, don't fail: a task that banked bytes in an
                            // EARLIER run before this run went zombie keeps the
                            // partial and retries later (THE RULE). Only a
                            // task with nothing banked gets the honest failure.
                            if (!parkInsteadOfFail(t, zombieMsg, forcePark = false)) {
                                repository.update(task.id) {
                                    it.copy(
                                        status = TaskStatus.FAILED,
                                        errorMessage = zombieMsg
                                    )
                                }
                                com.anonrode.downloader.service.DownloadService.notifyFailed(context, task.id, t.episodeTitle, zombieMsg)
                            }
                            activeJobs[task.id]?.cancel()
                            break
                        }
                        // A task with no meaningful byte movement (parsed or on
                        // disk) for a full minute is stalled: kill the backend so
                        // the retry wrapper relaunches it (fresh token/edge on
                        // re-resolve), and eventually FAILED instead of hanging.
                        // Magnets are exempt — peer discovery is legitimately quiet.
                        //
                        // Correction: prior to this change, the watchdog killed
                        // after `now - lastActivity > STALL_TIMEOUT_MS` (60s by
                        // default) even when the wrapper had already started a
                        // retry attempt that was making fresh progress — a slow
                        // health probe, a slow token refresh, or a momentary
                        // DNS hiccup was indistinguishable from a true stall,
                        // and the kill-then-rerun-then-kill loop could consume 8
                        // attempts in 4 minutes. The rule now treats a kill as
                        // definitive: a kill is only allowed if BOTH
                        //   (a) the download is dead by one of two signals —
                        //       stalledLong (no bytes at all for 2x the window)
                        //       OR the rate-drop rule above (burst >= 1 MiB/s,
                        //       then a sub-floor trickle for >= 30s: bytes move,
                        //       so lastActivity never goes stale and neither
                        //       signal but this one catches the downloadwella
                        //       burst-then-trickle trap), AND
                        //   (b) the wrapper has not just started a fresh attempt
                        //       (guards against the wrapper being mid-resolve
                        //       when the watchdog fires).
                        // Magnet exemption unchanged.
                        val attemptBoundary = stallKills * STALL_TIMEOUT_MS * 2
                        val stalledLong = now - lastActivity > STALL_TIMEOUT_MS
                        if (!magnetTask && !throttleGrace && (stalledLong || throttled) && (now - watchdogStart) > attemptBoundary && (stallKills == 0 || now - lastKillTime > STALL_TIMEOUT_MS)) {
                            stallKills++
                            com.anonrode.downloader.util.DebugLog.engine(
                                "task=${task.id} watchdog kill #$stallKills (idle=${(now - lastActivity) / 1000}s crawl=$crawlStalled throttled=$throttled window=${(moved / 1024).toInt()}KiB best=${(bestWindowBps / 1024).toInt()}KiB/s)"
                            )
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
                                val stallMsg = if (stalledLong) {
                                    "Download stalled — no progress for ${stallTimeoutSec}s across $stallKills attempts"
                                } else {
                                    "Download throttled to ${(windowBps / 1024).toLong().coerceAtLeast(0L)} KiB/s after a healthy start — killed and re-sourced $stallKills times without recovery"
                                }
                                // THE RULE: the whole point of the park system.
                                // A task that banked bytes through these kills
                                // (the vikingfile 429 case: 64 MiB in, then
                                // every relaunch met a rate-limit) waits with
                                // its partials instead of being failed; the
                                // cooldown loop resumes it when the host heals.
                                // Zero-byte tasks keep the loud failure.
                                if (!parkInsteadOfFail(t, stallMsg, forcePark = isRateLimitedError(stallMsg))) {
                                    repository.update(task.id) {
                                        it.copy(status = TaskStatus.FAILED, errorMessage = stallMsg)
                                    }
                                    com.anonrode.downloader.service.DownloadService.notifyFailed(context, task.id, t.episodeTitle, stallMsg)
                                }
                                activeJobs[task.id]?.cancel()
                                break
                            }
                            lastActivity = now
                            lastKillTime = now
                            windowStartDisk = disk
                            windowStartParsed = parsed
                            windowStartTime = now
                            // Auto-recovery: the next time this task's wrapper
                            // re-resolves (e.g. on the yt-dlp-attempt-2 cycle), it
                            // must NOT hit a still-cooling host and skip silently
                            // (the 9-attempt downloadwella trap in
                            // app-2026-08-29 / 09-01). One bypass token per stall
                            // kill: consumed on the next startTask's first
                            // resolveStreamUrl. Bounded by retryBypassTasks.size
                            // guard in retry() — clear() at 16.
                            if (retryBypassTasks.size >= 16) retryBypassTasks.clear()
                            retryBypassTasks.add(task.id)
                        }
                    }
                }

                val targetFolder = getDownloadDirectory(task.showTitle, createDirs = true)
                // Android 11+ direct writes to public Downloads silently fail
                // without "All files access" (MANAGE_EXTERNAL_STORAGE) — fail
                // fast with the fix instead of a confusing mid-download IO
                // error that looks like the site broke.
                if (!targetFolder.isDirectory || !targetFolder.canWrite()) {
                    throw Exception(
                        "Storage permission missing — downloads are saved to your Downloads folder. " +
                            "Enable \"All files access\" for Anon Downloader (Settings > Apps > Anon Downloader > Permissions)."
                    )
                }
                // var: recomputed whenever a token refresh swaps streamUrl,
                // so the fallback backends never run with a stale referer.
                var refererToPass = getRefererForUrl(streamUrl)

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
                        refererToPass = getRefererForUrl(streamUrl)
                        repository.update(task.id) { t ->
                            t.copy(
                                directUrl = if (isDirectMediaUrl(streamUrl) || isProvablyDirectFile(streamUrl)) streamUrl else t.directUrl
                            )
                        }
                        hlsMasterFile = preflightHls(
                            context, task.id, streamUrl, getRefererForUrl(streamUrl),
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

                if (finalBackend == "aria2c" && !isMagnet) {
                    val hdrs = mutableMapOf("User-Agent" to HttpClient.DEFAULT_UA)
                    if (refererToPass.isNotBlank()) hdrs["Referer"] = refererToPass
                    val dest = File(targetFolder, File(task.filePath).name)

                    // Strict pre-enqueue validation: one ranged 1KB request with
                    // the REAL download headers rejects HTML-decoy/archive URLs
                    // before any byte is persisted (the corrupted-"mp4" bug class).
                    // Failure fails the task loudly instead of queueing garbage.
                    var directRefreshAttempted = false
                    if (!isSocial) {
                        var rejection = StreamValidator.validateResult(streamUrl, hdrs)
                        if (rejection?.refreshable == true) {
                            directRefreshAttempted = true
                            coroutineContext.ensureActive()
                            val fresh = resolveStreamUrl(permUrl, task.site, task.quality ?: defaultQuality)
                            if (!fresh.isNullOrBlank() && fresh != streamUrl &&
                                (isDirectMediaUrl(fresh) || isProvablyDirectFile(fresh))) {
                                streamUrl = fresh
                                refererToPass = getRefererForUrl(fresh)
                                hdrs.remove("Referer")
                                if (refererToPass.isNotBlank()) hdrs["Referer"] = refererToPass
                                rejection = StreamValidator.validateResult(streamUrl, hdrs)
                                coroutineContext.ensureActive()
                                repository.update(task.id) { it.copy(directUrl = streamUrl) }
                            }
                        }
                        rejection?.let { throw PipelineError.ValidationFailed(it.reason) }
                    }

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

                    com.anonrode.downloader.util.DebugLog.engine(
                        "task=${task.id} turbo start sockets=$effectiveSockets url=${streamUrl.take(110)}"
                    )
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
                        if (tokenExpired && !directRefreshAttempted && coroutineContext.isActive && !isSocial) {
                            directRefreshAttempted = true
                            android.util.Log.w("AnonDownload", "Direct link rejected (HTTP ${failure.httpStatus}), refreshing stream token...")
                            repository.update(task.id) { it.copy(status = TaskStatus.RESOLVING) }
                            updateServiceState(force = true)

                            val freshUrl = resolveStreamUrl(permUrl, task.site, task.quality ?: defaultQuality)
                            if (!freshUrl.isNullOrBlank() && freshUrl != streamUrl && (isDirectMediaUrl(freshUrl) || isProvablyDirectFile(freshUrl))) {
                                streamUrl = freshUrl
                                repository.update(task.id) { it.copy(status = TaskStatus.DOWNLOADING, directUrl = streamUrl) }
                                updateServiceState(force = true)

                                val freshReferer = getRefererForUrl(streamUrl)
                                refererToPass = freshReferer
                                val freshHdrs = mutableMapOf("User-Agent" to HttpClient.DEFAULT_UA)
                                if (freshReferer.isNotBlank()) freshHdrs["Referer"] = freshReferer
                                StreamValidator.validateResult(streamUrl, freshHdrs)?.let {
                                    throw PipelineError.ValidationFailed(it.reason)
                                }

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

                    // Rate-limited (429)? Do NOT relaunch the fallback chain.
                    // Every fresh connection to a server that just said "too
                    // many requests" deepens the block and burns mobile data —
                    // this handoff+aria2c retry is what turned the vikingfile
                    // 429 into a 12-requests-in-11-seconds storm (share-5).
                    // The completion gate parks the task instead.
                    if (producedFile == null && turboFailure?.httpStatus != 429 && coroutineContext.isActive) {
                        // Turbo → aria2c resume handoff: hand over the longest contiguous
                        // prefix so the fallback continues instead of restarting. yt-dlp's
                        // aria2c is fed "-o stem.%(ext)s" where ext comes from the URL, so
                        // the prefix must be renamed to the URL-extension name — the task's
                        // filePath extension (always .mkv for direct downloads) can differ
                        // (.mp4 URLs are common), and handing over under the wrong name
                        // made aria2c restart from zero while orphaning the .part. Never
                        // hand over onto an existing file (which may carry its own .aria2
                        // resume state). The sidecar is deleted with the rename: it
                        // describes the .part file, which no longer exists once the prefix
                        // becomes the aria2c target.
                        try {
                            val urlExt = run {
                                val fromPath = streamUrl.substringBefore('?').substringBefore('#')
                                    .substringAfterLast('/').substringAfterLast('.', "")
                                if (fromPath.isNotBlank() && fromPath.length <= 5 && fromPath.all { it.isLetterOrDigit() }) {
                                    fromPath
                                } else {
                                    val query = streamUrl.substringAfter('?', "").lowercase()
                                    val match = Regex("""filename[*]?=[^&;]+\.([a-z0-9]{2,5})""").find(query)
                                    match?.groupValues?.get(1) ?: ""
                                }
                            }
                            if (urlExt.isNotBlank() && urlExt.length <= 5 && urlExt.all { it.isLetterOrDigit() }) {
                                val handoffTarget = File(targetFolder, "${File(task.filePath).nameWithoutExtension}.$urlExt")
                                // Also refuse a target another task has RESERVED: two tasks
                                // for the same episode from different providers differ only
                                // in extension, and a mid-flight sibling has .part yet no
                                // final name, so exists() alone cannot see it
                                // (engine-audit P2 — renaming onto a sibling's target
                                // lets one task adopt the other's bytes).
                                val reservedByOther = repository.tasks.value.any {
                                    it.id != task.id && it.filePath == handoffTarget.absolutePath
                                }
                                if (!handoffTarget.exists() && !reservedByOther &&
                                    !File(handoffTarget.absolutePath + ".aria2").exists()
                                ) {
                                    val partFile = File(dest.absolutePath + ".part")
                                    val prefix = TurboState(File(dest.absolutePath + ".turbo")).contiguousPrefixBytes()
                                    if (prefix != null && prefix > 0 && partFile.exists() && prefix <= partFile.length()) {
                                        // Rename FIRST, truncate the renamed file, then drop
                                        // the sidecar (engine-audit P1): truncating before the
                                        // rename meant a FAILED rename left the .part shortened
                                        // while the .turbo sidecar still claimed the removed
                                        // pieces — the next segmented run skipped them, re-padded
                                        // zeros over the pre-allocated file and passed every
                                        // completion tier. On a failed rename both files stay
                                        // untouched, and the next run resumes them correctly.
                                        if (partFile.renameTo(handoffTarget)) {
                                            RandomAccessFile(handoffTarget, "rw").use { it.setLength(prefix) }
                                            TurboState(File(dest.absolutePath + ".turbo")).delete()
                                            android.util.Log.w("AnonDownload", "Handed ${prefix / 1024 / 1024} MiB prefix to aria2c for resume as ${handoffTarget.name}")
                                        }
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
                            torrentPeers = torrentPeers,
                            privacyMode = torrentPrivacyMode
                        )
                    }

                    // aria2c → Turbo rescue: resume from whichever state is freshest on
                    // disk — Turbo's own sidecar, or aria2c's control file converted to
                    // Turbo's piece map. Only when nothing else produced a file yet, and
                    // never against a 429 (see the handoff gate above).
                    if (producedFile == null && turboFailure?.httpStatus != 429 && coroutineContext.isActive) {
                        try {
                            // The fallback wrote "stem.%(ext)s" with the URL's extension,
                            // which can differ from the task's .mkv target name — look
                            // for resumable state under both names.
                            fun tryMakeResumable(base: File): Boolean {
                                val turboState = TurboState(File(base.absolutePath + ".turbo"))
                                val partFile = File(base.absolutePath + ".part")
                                if (partFile.exists() && partFile.length() > 0 &&
                                    turboState.contiguousPrefixBytes() != null) {
                                    return true
                                }
                                val control = File(base.absolutePath + ".aria2")
                                val parsed = if (control.exists()) Aria2Control.parse(control) else null
                                if (parsed == null || !base.exists() || base.length() <= 0) return false
                                val hadPart = partFile.exists()
                                if (hadPart) partFile.delete()
                                return if (base.renameTo(partFile)) {
                                    turboState.commit(parsed.pieces, parsed.fileLength, force = true)
                                    control.delete()
                                    true
                                } else {
                                    if (hadPart) {
                                        // The sidecar describes the deleted .part: without it,
                                        // the map must not survive or the next run would skip
                                        // bytes that are not actually on disk.
                                        turboState.delete()
                                    }
                                    false
                                }
                            }

                            val baseCandidates = mutableListOf(dest)
                            val rescueExt = streamUrl.substringBefore('?').substringBefore('#')
                                .substringAfterLast('/').substringAfterLast('.', "")
                            if (rescueExt.isNotBlank() && rescueExt.length <= 5 && rescueExt.all { it.isLetterOrDigit() }) {
                                val alt = File(targetFolder, "${File(task.filePath).nameWithoutExtension}.$rescueExt")
                                if (alt.absolutePath != dest.absolutePath) baseCandidates.add(alt)
                            }
                            val resumeBase = baseCandidates.firstOrNull { tryMakeResumable(it) }

                            if (resumeBase != null) {
                                android.util.Log.w("AnonDownload", "Resuming partial download with Turbo")
                                val rescueHdrs = mutableMapOf("User-Agent" to HttpClient.DEFAULT_UA)
                                val rescueReferer = getRefererForUrl(streamUrl)
                                if (rescueReferer.isNotBlank()) rescueHdrs["Referer"] = rescueReferer
                                turboResult = TurboDownloader.download(
                                    url = streamUrl,
                                    dest = resumeBase,
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
                    suspend fun runYtdlp(url: String, masterFile: File?): File? = YoutubeDlDownloader.download(
                        context = context,
                        taskId = task.id,
                        sourceUrl = url,
                        targetDir = targetFolder,
                        preferredFilename = File(task.filePath).name,
                        backend = finalBackend,
                        referer = getRefererForUrl(url),
                        ua = HttpClient.DEFAULT_UA,
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
                        hlsMasterFile = masterFile?.absolutePath
                    )

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
                    if (producedFile == null && coroutineContext.isActive) {
                        val freshUrl = resolveStreamUrl(permUrl, task.site, task.quality ?: defaultQuality)
                        if (!freshUrl.isNullOrBlank() && freshUrl != streamUrl) {
                            android.util.Log.w("AnonDownload", "yt-dlp failed, re-resolving for a fresh URL")
                            streamUrl = freshUrl
                            var freshMaster: File? = null
                            if (isHlsStream) {
                                try {
                                    freshMaster = preflightHls(context, task.id, freshUrl, getRefererForUrl(freshUrl), hlsSizeEstimate, task.quality ?: defaultQuality, hlsResolution)
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
                val minSize = if (isAudio) 10 * 1024L else 50 * 1024L

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
                val verified = transferComplete || structuralOk || decodeOk || hlsComplete
                val path = when {
                    structuralOk -> "structure"
                    transferComplete -> "transfer"
                    decodeOk -> "decode"
                    hlsComplete -> "estimate"
                    else -> "none"
                }
                val note = when {
                    !verified || structuralOk || transferComplete -> null
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

                    try {
                        File(producedFile.absolutePath + ".turbo").delete()
                        File(producedFile.absolutePath + ".part").delete()
                        File(producedFile.absolutePath + ".aria2").delete()
                        // Backends can produce a file whose name differs from the
                        // task's target (aria2c writes the URL's extension, e.g.
                        // .mp4, while the task targets .mkv) — without this the
                        // original target's sidecars are orphaned forever.
                        if (producedFile.absolutePath != task.filePath) {
                            File(task.filePath + ".turbo").delete()
                            File(task.filePath + ".part").delete()
                            File(task.filePath + ".aria2").delete()
                            File(task.filePath + ".ytdl").delete()
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
                    // Main-looper hop inside: from this IO coroutine the old
                    // inline scanFile never bound the scanner service and
                    // silently no-opped — downloads stayed invisible to the
                    // gallery until a system rescan (user report 2026-09-13).
                    com.anonrode.downloader.util.MediaScan.notifyFile(context, producedFile)
                    com.anonrode.downloader.util.DebugLog.write("completed-unverified task=${task.id} file=${producedFile.absolutePath} bytes=$finalBytes validation=none")
                } else {
                    val errReason = when {
                        producedFile != null && looksLikeHtml(producedFile) -> "Server returned an HTML page instead of the file"
                        // Null-check the transfer outcome BEFORE the generic
                        // validation pair: with no file, validation.second is
                        // always "File missing", which used to swallow every
                        // real HTTP status the backends captured (the 429 storm
                        // was logged as a bare "File missing").
                        producedFile == null && turboFailure?.htmlPage == true -> "Server returned an HTML page instead of the file — the link expired; retry to refresh it"
                        producedFile == null && turboFailure?.httpStatus != null -> "Download rejected by server (HTTP ${turboFailure.httpStatus}) — retry to refresh the link"
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
                    val parked = producedFile == null &&
                        parkInsteadOfFail(live, errReason, forcePark = isRateLimitedError(errReason))
                    if (!parked) {
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
                // in fullyCancelledIds before the job was cancelled), not when
                // the user paused or the network parked the task — pause()/
                // pauseForNetwork already killed the specific backends, and a
                // global cancelInFlight would kill OTHER tasks' in-flight
                // requests — the cross-talk bug. The repository check stays
                // as a safety net for removals that bypassed cancel(). Even
                // then, only sweep when no other task is running.
                if (fullyCancelledIds.remove(task.id) || repository.find(task.id) == null) {
                    if (activeJobs.keys.none { it != task.id }) {
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
                val owner = activeJobs[task.id]
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
                val thisJob = coroutineContext[Job]
                if (thisJob != null && activeJobs[task.id] === thisJob) {
                    activeJobs.remove(task.id)
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

        activeJobs[task.id] = job
        // A fast-failing job can finish BEFORE this registration lands: its
        // finally-guard then saw no entry and could not remove itself, so the
        // line above would leave a dead job in activeJobs — a stale key that
        // suppresses the activeJobs.isEmpty()/none{} cancel-sweep heuristics.
        // Conditional remove: never clobbers a newer job for the same task.
        if (!job.isActive) {
            activeJobs.remove(task.id, job)
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
            activeJobs[taskId]?.cancel()
            activeJobs.remove(taskId)
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
        if (activeJobs.isEmpty()) HttpClient.cancelInFlight()
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
            if (retryBypassTasks.size >= 16) retryBypassTasks.clear()
            retryBypassTasks.add(taskId)
            repository.update(taskId) { it.copy(status = TaskStatus.QUEUED, errorMessage = null, userPaused = false) }
            parkCycleCounts.remove(taskId)
            parkedAtStamp.remove(taskId)
            failoverSites.remove(taskId)
            requeueNotBefore.remove(taskId)
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
            if (activeJobs.containsKey(taskId)) fullyCancelledIds.add(taskId)
            activeJobs[taskId]?.cancel()
            activeJobs.remove(taskId)
            YoutubeDlDownloader.killProcess(taskId)
            TurboDownloader.cancelTask(taskId)
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
                purgeTaskArtifacts(live)
                repository.remove(taskId)
            }
        }
        // Every job was cancelled above, so a global in-flight sweep cannot
        // cross-talk into another task — the condition cancel() relies on.
        if (activeJobs.isEmpty()) HttpClient.cancelInFlight()
        updateServiceState(force = true)
        processQueue()
    }
}
