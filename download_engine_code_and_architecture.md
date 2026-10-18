# Mobile Download Engine Architecture & Source Code Specification

## Document Overview
This document contains the complete technical architecture, operational mechanisms, and core source code of the Android download engine (`com.anonrode.downloader.engine`). It is provided as an objective, unbiased reference for architectural review, code auditing, and analysis of concurrency, process management, and protocol handling.

---

## 1. System Architecture & Feature Inventory

### 1.1 Core Responsibilities
The download engine manages media downloads across various network conditions, protocols, and content delivery mechanisms:
1. **Multi-Protocol Download Routing**: Direct media streams (HTTP/HTTPS Range requests), Adaptive Bitrate Streaming (HLS `.m3u8` playlists and DASH `.mpd`), and BitTorrent magnet links.
2. **Multi-Backend Execution Engine**:
   - **Native `aria2c`**: A native binary (`libaria2c.so`) invoked via `ProcessBuilder` for multi-socket segmented file downloads.
   - **`TurboDownloader`**: An in-process pure-Kotlin OkHttp segmented downloader featuring a dynamic piece queue and an exponential HTTP 429 rate-limit backoff ladder.
   - **`yt-dlp`**: A Python CLI subprocess wrapper used for HLS/DASH manifest fetching, fragment downloads, and post-processing/muxing via `ffmpeg`.
3. **Dynamic Stream Resolution & Resolver Cascades**: Multi-step scraping and token-rotation pipelines (`DynamicLockerEngine`, `LoadedfilesResolver`, `VidsrcResolver`).
4. **Lifecycle State Persistence**: Room SQLite database with atomic JSON disk persistence, preserving download states across Android process death, device reboots, and foreground service restarts.
5. **Progress Watchdog & Disk-Truth Bridge**: A background monitor tracking both backend stdout emissions and physical filesystem bytes (`computeDiskBytes`) to bridge progress to the UI.
6. **Cross-Provider Failover**: Automatic secondary-provider search when a primary content locker is missing or dead.

---

## 2. Data Models & State Persistence

### 2.1 Task Model (`DownloadTask.kt`)
The following data model represents the persisted state of each download job:

```kotlin

```

### 2.2 Task Lifecycle States
- `QUEUED`: Enqueued in the repository, waiting for an available concurrent download slot (`maxConcurrentDownloads = 4`).
- `RESOLVING`: Resolving source page URLs, locker hops, or streaming tokens.
- `DOWNLOADING`: Active backend process (`aria2c`, `TurboDownloader`, or `yt-dlp`) transferring bytes.
- `VALIDATING`: Media verification pass confirming non-empty, non-HTML payload.
- `PAUSED`: Explicit user pause or parked state during network offline transitions.
- `COMPLETED`: Verified download finalized in user destination folder.
- `FAILED`: Terminal error after all backend attempts and failover providers are exhausted.

---

## 3. Core Engine Controller (`DownloadEngine.kt`)

### 3.1 Concurrency Gates & Preferences
```kotlin
) {
    private val ASIAN_DRAMA_CLUSTER = setOf("asianc", "dramarain", "dramakey", "pluto", "nepu")
    private val WESTERN_CLUSTER = setOf("nkiri", "9jarocks", "naijavault", "naijaprey")
    private val DEDICATED_ASIAN_SITES = setOf("asianc", "dramarain", "dramakey")
    // Secondary Asian-failover targets: mixed Western-cluster sites with PROVEN
    // Asian catalogs (coverage probe 2026-09-20: 50 random Asian series,
    // search-only — 9jarocks 98%, nkiri 62%, and a 6/6 rescue rate on titles
    // ALL dedicated sites missed). Reached ONLY by Asian tasks, and tried at
    // EQUAL RANK with Asian-cluster cards: exact title match + HostHealth
    // decide (deliberate choice — no secondary-tier penalty). Western tasks
    // never touch the dedicated sites; that one-way door stays shut.
    private val SECONDARY_ASIAN_FALLBACK = setOf("nkiri", "9jarocks")
    private val YEAR_REGEX = Regex("""\b(19\d\d|20[0-3]\d)\b""")

    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val startingTaskIds = ConcurrentHashMap.newKeySet<String>()
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

    var maxConcurrentDownloads: Int = 4
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
    var onTorrentFileSelection: (suspend (List<TorrentSecurityShield.TorrentFileEntry>) -> com.anonrode.downloader.ui.screens.TorrentFilePicker.TorrentPickResult)? = null

    val tasks: StateFlow<List<DownloadTask>> = repository.tasks


```

### 3.2 Task Enqueueing & Initialization (`enqueue`)
```kotlin
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
        posterUrl: String = ""
    ): String {
        val downloadFolder = getDownloadDirectory(showTitle, createDirs = false)

        val taskId = synchronized(enqueueLock) {
            // Dedupe: never queue a second task for the same source URL while one
            // is already active or paused. Two tasks sharing a filePath write the
            // same .part concurrently and corrupt the output.
            val active = setOf(TaskStatus.QUEUED, TaskStatus.RESOLVING, TaskStatus.DOWNLOADING, TaskStatus.VALIDATING, TaskStatus.PAUSED)
            val existing = repository.snapshot().firstOrNull { it.sourceUrl == sourceUrl && it.status in active }
            val failedExisting = if (existing == null) {
                repository.snapshot().firstOrNull { it.sourceUrl == sourceUrl && it.status == TaskStatus.FAILED }
            } else null
            if (existing != null) {
                existing.id
            } else if (failedExisting != null) {
                retry(failedExisting.id)
                failedExisting.id
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
                    mirrorUrls = MirrorPool.sources(sourceUrl, mirrorUrls).filter { it != sourceUrl },
                    seriesUrl = seriesUrl,
                    seriesTotal = seriesTotal,
                    posterUrl = posterUrl
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

```

### 3.3 Protocol Classification & Routing Helpers
```kotlin
    // Adaptive-streaming manifest detection (monolith is_streaming_link parity):
    // DASH/ISM/F4M manifests and player-token URLs must go to yt-dlp, or the
    // segmented downloader grabs manifest XML and produces a corrupt file.
    private fun isStreamingLink(url: String): Boolean {
        val low = url.lowercase()
        if (low.contains(".m3u8") || low.contains("manifest") || low.contains("kickassanime")) return true
        if (low.contains("playlist.php") || low.contains("/api/orion/") || low.contains("stream-proxy")) return true
        val path = low.substringBefore('?').substringBefore('#')
        if (path.endsWith(".mpd") || path.endsWith(".m3u") || path.endsWith(".ism") || path.endsWith(".f4m")) return true
        return STREAMING_QUERY_PATTERN.containsMatchIn(low)
    }

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

        // Stall handling: a window must move at least this many bytes to count
        // as live progress (HLS CDNs throttle to ~1 KB/s instead of dying; the
        // crawl is a stall in disguise). 64 KiB per 60s window ≈ 1 KiB/s floor.
        private const val CRAWL_WINDOW_BYTES = 64L * 1024

        // Kill the backend this many times before giving up. The yt-dlp wrapper
        // retries 3x, then the engine re-resolves a fresh URL (rotating token
        // and edge node) for another 3 attempts — the recovery chain needs room.

```

### 3.4 Task Startup & Backend Selection (`startTask`)
```kotlin
        val existingJob = activeJobs[task.id]
        if (existingJob != null && existingJob.isActive) {
            return
        }
        if (!startingTaskIds.add(task.id)) return
        val live = repository.find(task.id)
        if (live == null || live.status !in setOf(TaskStatus.QUEUED, TaskStatus.RESOLVING, TaskStatus.DOWNLOADING)) {
            startingTaskIds.remove(task.id)
            return
        }
        com.anonrode.downloader.util.DebugLog.write("start task=${task.id} url=${task.directUrl.take(80)} backend=${task.backend}")

        val job = engineScope.launch {
            try {
                if (!isActive) return@launch
                var streamUrl = if (task.directUrl.contains("?preview", ignoreCase = true)) task.directUrl.substringBefore("?preview") else task.directUrl
                val isMagnet = streamUrl.startsWith("magnet:", ignoreCase = true)
                val isSocial = task.showTitle.startsWith("Social/", ignoreCase = true) || task.backend.contains("yt-dlp")
                var permUrl = task.selectedMirrorUrl.ifBlank { task.sourceUrl }.ifBlank { streamUrl }
                if (permUrl.contains("?preview", ignoreCase = true)) {
                    permUrl = permUrl.substringBefore("?preview")
                }

                // A manual retry tap grants ONE bypass of the HostHealth gate
                // for this start's FIRST resolution attempt (consume-on-use;
                // the stale-token / yt-dlp re-resolves later in this coroutine
                // must NOT bypass, so the flag is read here and not re-derived).
                // The tap also resets this task's dead-URL memory: the user
                // explicitly demanded a fresh crack, so no url spent earlier
                // in this task may be answered from ResolveBudget.
                val bypassHealth = retryBypassTasks.remove(task.id)
                if (bypassHealth) ResolveBudget.forgetTask(task.id)

                // Zero-Latency Resume: Check if streamUrl is already direct
                val isAlreadyDirect = isDirectMediaUrl(streamUrl) && !isKnownLockerHost(streamUrl)

                if (!isMagnet && !isSocial && !isAlreadyDirect) {
                    repository.update(task.id) { it.copy(status = TaskStatus.RESOLVING) }
                    updateServiceState(force = true)

                    // Hard ceiling on link cracking: the resolver chain walks many
                    // hosts with retries, and without a timeout a slow site pinned
                    // the task in RESOLVING forever (user-reported).
                    //
                    // SERIAL VERSION (this pass) of the mirror walk, with three
                    // speed mechanisms from the share-29 log evidence (233 crack
                    // hops, ~690s — serial dead-mirror walks were the single
                    // biggest waste):
                    //   1. PER-TASK DEAD-URL MEMORY (ResolveBudget.memoTaskId):
                    //      a url that consumed one full crack pass is refused
                    //      without any request on every later pass/re-entry of
                    //      this task (memoTaskId arms it inside the chain).
                    //   2. PER-MIRROR ATTEMPT BUDGETS: boundedResolverAttempt
                    //      caps one resolver attempt at 12s (40s for hosts with
                    //      proven crack history) — the 25-30s dead hangs are gone.
                    //   3. EMPIRICAL ORDERING: mirrors whose host struck out
                    //      twice this session walk last; hosts with a session
                    //      success walk first (selected mirror keeps priority
                    //      inside its tier; MirrorPool cooldown order survives).
                    // FUTURE PARALLEL VERSION (documented, intentionally NOT
                    // this pass): swap the serial for-loop for a bounded race —
                    // `boundedFirstSuccess`-style: take the ordered candidates,
                    // launch resolveStreamUrl(candidate, ..., memoTaskId) for
                    // the first K, await any, on success cancel the losers and
                    // adopt the winner's mirror exactly as the loop does now.
                    // Constraints to honor in that version: (a) the memo must
                    // be consulted at RACE-ENTRY too so a known-dead mirror
                    // never occupies a race slot; (b) per-host race width is
                    // 1 for capped hosts (wetafiles/downloadwella/loadedfiles/
                    // kissorgrab socket caps — see effectiveSockets below —
                    // and aria2c's own socket caps) so K-lanes don't multiply
                    // sockets at the same front; (c) budgets stay per-attempt,
                    // so a racing lane losing its budget frees the slot instead
                    // of stalling the winner.
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
                                    // Session form for ordering: this mirror's host
                                    // consumed a full crack pass with nothing to show.
                                    ResolveBudget.recordHostFullPassFail(candidate)
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
                        // task found a working source). The dead-URL memo dies
                        // with the resolve phase too — the download itself is
                        // memo-less (token rotation must never see old marks).
                        parkCycleCounts.remove(task.id)
                        parkedAtStamp.remove(task.id)
                        failoverSites.remove(task.id)
                        requeueNotBefore.remove(task.id)
                        ResolveBudget.forgetTask(task.id)
                        streamUrl = if (resolved.contains("?preview", ignoreCase = true)) resolved.substringBefore("?preview") else resolved
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
                                // Park = the backoff window owns the retry rhythm;
                                // wipe the dead-URL memo so the re-queued cycle gets
                                // a genuinely fresh pass (a host that recovered inside
                                // its backoff window must not be answered from memory).
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
                val isProvablyDirect = isProvablyDirectFile(streamUrl) || isDirectMediaUrl(streamUrl) || isHlsStream
                val isEmbedOrPage = !isMagnet && !isProvablyDirect && run {
                    if (task.site.isNotBlank() || task.backend.contains("aria2c")) {
                        // Scraped tasks from supported sites (NaijaVault, Nkiri, 9jaRocks, DramaRain, etc.)
                        // or tasks explicitly targeting aria2c are direct media transfers, NEVER web embed pages!
                        false
                    } else if (!isSocial) {
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

                // Sensitive hosts (kissorgrab, plutomovies) trip 429/connection-drop
                // if overwhelmed by 16 sockets, but collapsing to 1 socket destroys
                // throughput over high-latency cellular networks (crawls to 6-30 KB/s).
                // Port aria2c/1DM safe concurrency: cap at 4 parallel sockets, which
                // stays under server connection limits while delivering 4x throughput.
                val effectiveSockets = if (streamUrl.lowercase().let { u ->
                        u.contains("kissorgrab.com") || u.contains("dl.plutomovies.com")
                    }) minOf(4, task.parallelSockets.coerceAtLeast(2)) else task.parallelSockets

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
                        directUrl = if (isDirectMediaUrl(streamUrl) || isProvablyDirectFile(streamUrl) || isHlsStream) streamUrl else t.directUrl
                    )
                }
                updateServiceState(force = true)


```

### 3.5 The Progress Watchdog & Stall Monitor
The watchdog runs concurrently with the active download job to monitor byte movement and handle hung backend processes:
```kotlin
                // Progress watchdog: keeps the UI honest with filesystem truth and
                // kills a stalled backend so a hung download cannot stay DOWNLOADING
                // forever with 0.0 MB/s. Runs until the job is paused/cancelled or
                // the task leaves DOWNLOADING.
                launch {
                    val initialDisk = computeDiskBytes(task)
                    val initialParsed = task.downloadedBytes
                    var lastDisk = initialDisk
                    var lastParsed = initialParsed
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
                    var windowStartDisk = initialDisk
                    var windowStartParsed = initialParsed
                    var windowStartTime = System.currentTimeMillis()
                    var bestWindowBps = 0.0
                    var lastProgressLog = 0L
                    val watchdogStart = System.currentTimeMillis()
                    var startBytes: Long? = initialDisk + initialParsed
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
                        val beaconMoved = maxOf(disk - windowStartDisk, parsed - windowStartParsed)
                        if (beaconMoved > 0 && now - lastProgressLog >= 10_000L) {
                            lastProgressLog = now
                            com.anonrode.downloader.util.DebugLog.engine(
                                "task=${task.id} progress disk=${disk / 1024}KiB parsed=${parsed / 1024}KiB window=${(beaconMoved / 1024).toInt()}KiB"
                            )
                        }
                        // Feed the UI from the filesystem when the backend reports
                        // nothing (fragment downloads, hung output parsing).
                        if (disk > t.downloadedBytes) {
                            val safeDisk = if (t.totalBytes > 0L && t.status == TaskStatus.DOWNLOADING && disk >= t.totalBytes) {
                                t.downloadedBytes
                            } else {
                                disk
                            }
                            if (safeDisk > t.downloadedBytes) {
                                val speed = if (t.speedBytesPerSec > 0.0) t.speedBytesPerSec
                                else diskDelta.coerceAtLeast(0L) * 1000.0 / 2000.0
                                val eta = if (t.etaSeconds > 0) {
                                    t.etaSeconds
                                } else if (speed > 0.0 && t.totalBytes > safeDisk) {
                                    ((t.totalBytes - safeDisk) / speed).toLong().coerceAtLeast(1L)
                                } else {
                                    0L
                                }
                                repository.updateProgress(
                                    taskId = task.id,
                                    downloaded = safeDisk,
                                    total = t.totalBytes,
                                    speed = speed,
                                    eta = eta
                                )
                            }
                        }
                        // Window progress: healthy downloads blow through the floor
                        // in seconds; a crawl never reaches it.
                        val moved = maxOf(disk - windowStartDisk, parsed - windowStartParsed)
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
                        val crawlStalled = (now - windowStartTime > STALL_TIMEOUT_MS) && (moved < CRAWL_WINDOW_BYTES)
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
                        val attemptBoundary = if (magnetTask) 90_000L else maxOf(stallKills * STALL_TIMEOUT_MS * 2, STALL_TIMEOUT_MS)
                        val stalledLong = now - lastActivity > STALL_TIMEOUT_MS
                        val isStalledOrCrawling = stalledLong || crawlStalled
                        if (!magnetTask && !throttleGrace && isStalledOrCrawling && (now - watchdogStart) > attemptBoundary && (stallKills == 0 || now - lastKillTime > STALL_TIMEOUT_MS)) {
                            stallKills++
                            lastKillTime = now
                            windowStartDisk = disk
                            windowStartParsed = parsed
                            windowStartTime = now
                            lastActivity = now
                            com.anonrode.downloader.util.DebugLog.engine(
                                "task=${task.id} watchdog stall kill #$stallKills (idle=${(now - lastActivity) / 1000}s crawl=$crawlStalled window=${(moved / 1024).toInt()}KiB best=${(bestWindowBps / 1024).toInt()}KiB/s)"
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
                                val stallMsg = "Download stalled — no progress for ${stallTimeoutSec}s across $stallKills attempts"
                                // Track 6: the only engine diagnostic that leaves
                                // the device automatically — a task the watchdog
                                // gave up on after exhausting every relaunch.
                                // Sanitized, ~4 KB-capped, async, OTA-routed
                                // (RemoteLogger); silent no-op until the rules
                                // provision remote_logging_url.
                                com.anonrode.downloader.util.RemoteLogger.report(
                                    type = "stall_watchdog_timeout",
                                    message = stallMsg,
                                    context = mapOf(
                                        "episode" to t.episodeTitle,
                                        "backend" to t.backend,
                                        "site" to t.site,

```

---

## 4. Native Aria2c Backend Execution (`YoutubeDlDownloader.kt`)

### 4.1 `downloadHttpAria2c` Implementation
The native `aria2c` binary (`libaria2c.so`) is executed as a child process via `ProcessBuilder`. It manages stdout parsing, process monitoring, and exit code handling:
```kotlin
    private suspend fun downloadHttpAria2c(
        context: Context,
        taskId: String,
        url: String,
        targetDir: File,
        preferredFilename: String,
        referer: String = "",
        origin: String = "",
        ua: String = "",
        customHeaders: Map<String, String> = emptyMap(),
        parallelSockets: Int = 16,
        speedLimitKbs: Int = 0,
        maxAttempts: Int = 3,
        onProgress: (downloaded: Long, total: Long, speed: Double, eta: Long) -> Unit,
        isActiveCheck: suspend () -> Boolean = { true },
        estimatedTotal: Long = 0L
    ): File? {
        val before = targetDir.listFiles()?.map { it.absolutePath }?.toSet() ?: emptySet()
        val aria2Exec = findAria2Executable(context)
            ?: throw IllegalStateException("aria2c binary missing: libaria2c.so not found in native libs")

        val stem = preferredFilename.substringBeforeLast('.')
        val existingPartial = targetDir.listFiles { f ->
            (f.name == preferredFilename || f.nameWithoutExtension == stem) &&
            f.length() > 0 && !f.name.endsWith(".aria2") && !f.name.endsWith(".part") && !f.name.endsWith(".turbo")
        }?.firstOrNull()
        val effOutputName = existingPartial?.name ?: preferredFilename

        val cmd = mutableListOf(
            aria2Exec.absolutePath,
            "-c",
            "-d", targetDir.absolutePath,
            "-o", effOutputName,
            "--file-allocation=none",
            "--check-certificate=false",
            "--summary-interval=1",
            "--max-tries=5",
            "--retry-wait=2",
            "--connect-timeout=15",
            "--timeout=30",
            "--auto-file-renaming=false",
            "--allow-overwrite=true",
            "--console-log-level=error"
        )

        // Sensitive hosts (kissorgrab, plutomovies) trip 429 if flooded with 16 sockets,
        // but 1 socket chokes to 6-30 KB/s on mobile networks. Cap at safe 4 sockets.
        val isSensitiveHost = url.contains("plutomovies.com", ignoreCase = true) ||
                url.contains("kissorgrab.com", ignoreCase = true)
        val sockets = if (isSensitiveHost) {
            minOf(4, parallelSockets.coerceAtLeast(2))
        } else {
            parallelSockets.coerceIn(1, 16)
        }
        cmd += listOf("-s", "$sockets", "-x", "$sockets", "-j", "$sockets", "--max-connection-per-server=$sockets", "--min-split-size=1M")

        if (ua.isNotBlank()) {
            val cleanUa = ua.replace("\r", "").replace("\n", "").trim()
            cmd += "--user-agent=$cleanUa"
        }
        if (referer.isNotBlank()) {
            val cleanRef = referer.replace("\r", "").replace("\n", "").trim()
            cmd += "--referer=$cleanRef"
        }
        if (origin.isNotBlank()) {
            val cleanOrig = origin.replace("\r", "").replace("\n", "").trim()
            cmd += "--header=Origin: $cleanOrig"
        }
        for ((k, v) in customHeaders) {
            if (k.equals("user-agent", ignoreCase = true) ||
                k.equals("referer", ignoreCase = true) ||
                k.equals("origin", ignoreCase = true)) continue
            val cleanK = k.replace("\r", "").replace("\n", "").trim()
            val cleanV = v.replace("\r", "").replace("\n", "").trim()
            if (cleanK.isNotBlank() && cleanV.isNotBlank()) {
                cmd += "--header=$cleanK: $cleanV"
            }
        }
        if (speedLimitKbs > 0) {
            cmd += "--max-download-limit=${speedLimitKbs}K"
        }
        cmd += url

        val errors = StringBuilder()
        var produced: File? = null
        var attempts = 0

        fun runOnce(): File? {
            val pb = ProcessBuilder(cmd)
            pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
            pb.directory(targetDir)
            pb.redirectErrorStream(true)
            val process = pb.start()

            if (Thread.currentThread().isInterrupted) {
                process.destroy()
                throw InterruptedException("Task was cancelled before process started")
            }

            activeNativeProcesses[taskId] = process

            // aria2c stdout stream: parse with parseProgressTick and smooth ETA
            var lastDl = 0L
            var lastTot = estimatedTotal
            val etaCalc = FlickerFreeEtaCalculator()
            val fallbackRegex = Regex("""\((\d+)%\).*?DL:\s*([\d.]+[KMGT]?i?B)""", RegexOption.IGNORE_CASE)
            val reader = process.inputStream.bufferedReader()
            val logBuffer = mutableListOf<String>()
            var exitCode: Int? = null

            try {
                var line: String? = reader.readLine()
                while (line != null) {
                    if (logBuffer.size < 50) {
                        logBuffer.add(line)
                    } else {
                        logBuffer.removeAt(0)
                        logBuffer.add(line)
                    }
                    val tick = parseProgressTick(line, 0f, lastDl, lastTot)
                    if (tick.downloadedBytes > 0L || tick.totalBytes > 0L || tick.speedBytesPerSec > 0.0) {
                        lastDl = maxOf(lastDl, tick.downloadedBytes)
                        lastTot = maxOf(lastTot, tick.totalBytes)
                        val eta = if (tick.etaSeconds > 0L) tick.etaSeconds else etaCalc.update(lastDl, lastTot, tick.speedBytesPerSec)
                        onProgress(lastDl, lastTot, tick.speedBytesPerSec, eta)
                    } else {
                        val fb = fallbackRegex.find(line)
                        if (fb != null) {
                            val spd = parseSpeedString(fb.groupValues[2])
                            onProgress(0L, 0L, spd, 0L)
                        }
                    }
                    line = reader.readLine()
                }
                exitCode = process.waitFor()
            } catch (e: Exception) {
                process.destroy()
                errors.append("run ").append(attempts).append(": ").append(e.message ?: e.javaClass.simpleName).append('\n')
                return null
            } finally {
                activeNativeProcesses.remove(taskId)
            }

            if (exitCode != 0) {
                val errSummary = logBuffer.takeLast(3).joinToString(" | ")
                errors.append("run ").append(attempts).append(" exit ").append(exitCode).append(": ").append(errSummary).append('\n')
                return null
            }

            fun isFinal(f: File) = (f.length() > 0 || f.isDirectory) &&
                    !f.name.endsWith(".aria2") && !f.name.endsWith(".part") &&
                    !f.name.endsWith(".ytdl")

            val candidates = targetDir.listFiles { f -> isFinal(f) }?.toList() ?: emptyList()
            val fresh = candidates.filter { it.absolutePath !in before }
            val found = fresh.firstOrNull { it.name == effOutputName || it.name == preferredFilename || it.nameWithoutExtension == stem }
                ?: candidates.firstOrNull { it.name == effOutputName || it.name == preferredFilename || it.nameWithoutExtension == stem }
                ?: fresh.maxByOrNull { it.lastModified() }

            if (found != null && !File(found.absolutePath + ".aria2").exists()) {
                return found
            }

            errors.append("run ").append(attempts).append(": no complete file (control file still present)\n")
            return null
        }

        while (attempts < maxAttempts && produced == null) {
            attempts++
            if (!isActiveCheck()) throw CancellationException("Task was cancelled before HTTP aria2c retry")
            produced = runOnce()
            if (produced == null && attempts < maxAttempts) {
                cancellableRetryWait(2_000L * attempts, isActiveCheck = isActiveCheck)
            }
        }
        if (produced == null) {
            throw Exception("aria2c HTTP download failed after $attempts attempt(s): ${errors.toString().trim()}")
        }
        return produced

```

---

## 5. Pure-Kotlin Segmented Engine (`TurboDownloader.kt`)

### 5.1 Architecture & Rate-Limit Ladder
`TurboDownloader` provides in-process segmented HTTP downloading using OkHttp. It features a shared piece queue and rate-limit backoff:
```kotlin
/**
 * High-performance multi-socket segmented downloader for direct CDN files.
 *
 * Designed with:
 * 1. Definitive Range verification (probe with fallback bytes=0-1024 GET).
 * 2. Decoupled telemetry ticker (250ms cadence) with EMA speed smoothing.
 * 3. Non-blocking parallel worker streams writing to pre-allocated FileChannels.
 * 4. Throttled sidecar resume state commits.
 * 5. BitTorrent-style shared piece queue with per-piece retry and exponential
 *    backoff: a flaky connection costs one piece, not the whole download.
 */
object TurboDownloader {

    private const val BUFFER = 256 * 1024 // 256 KB high-throughput buffer
    private const val MIN_SEGMENTED_SIZE = 8L * 1024 * 1024 // 8 MB
    private const val MAX_ATTEMPTS = 5 // retries per piece / per single-stream attempt
    private const val RETRY_CAP = 8L // backoff ceiling, in multiples of the base delay

    /** Test hook: collapses exponential backoff so retry tests run fast. */
    internal var retryBaseDelayMs: Long = 1000L

    /**
     * Rate-limit ladder. HTTP 429 (and 503) are "come back later" answers, and
     * a desktop download manager (IDM/1DM) survives them simply by WAITING.
     * The old policy burned the normal 5-attempt / ~15s piece budget per
     * socket on a 429 — 16 sockets × 5 relaunches is itself a request storm
     * that kept the limiter tripped, and when the budget ran out the WHOLE
     * segmented download died and the engine handed the angry URL to aria2c
     * (activity-log-share-5: 64 MiB banked at ~26 MB/s, then FAILED).
     * Rate-limits are now waits, not attempts: one shared escalating cooldown
     * (base ×2 per cycle, capped at 5 min), every socket sits it out, and the
     * active worker count halves per cycle (16→8→4→2) so the resumed traffic
     * is the gentle shape that never provokes the host again.
     */
    private const val THROTTLE_BASE_MS = 15_000L
    private const val THROTTLE_MAX_MS = 5 * 60_000L

    /** Test hook: collapses rate-limit cooldowns so throttle tests run fast. */
    internal var throttleBaseDelayMs: Long = THROTTLE_BASE_MS

    /**
     * In-flight OkHttp calls per task id. The engine's stall watchdog calls
     * [cancelTask] so a trickling-but-alive transfer can be interrupted even
     * though Turbo has no native process to kill.
     */
    private val activeCalls = ConcurrentHashMap<String, CopyOnWriteArrayList<Call>>()
    private val fullyCancelledTaskIds = ConcurrentHashMap.newKeySet<String>()

    /**
     * Interrupt every in-flight transfer of a task. The affected pieces fail,
     * are retried per the normal piece policy, and the task eventually FAILED
     * instead of hanging in DOWNLOADING forever. Idempotent for unknown ids.
     */
    fun cancelTask(taskId: String, isFullCancel: Boolean = false) {
        if (isFullCancel) fullyCancelledTaskIds.add(taskId)
        activeCalls.remove(taskId)?.forEach { it.cancel() }
    }

    /**

```

### 5.2 Segmented Worker Loop
```kotlin
        }
    }

    /**
     * Download [url] to [dest]. Returns [TurboResult.Success] on completion or
     * [TurboResult.Failure] with the HTTP status (when the server answered) and a
     * human-readable cause. Cancellation propagates as CancellationException so a
     * paused job never surfaces as a failure.
     * [onProgress] receives (downloadedBytes, totalBytes, bytesPerSecond).
     */
    suspend fun download(
        url: String,
        dest: File,
        headers: Map<String, String> = emptyMap(),
        configuredSockets: Int = 8,
        onProgress: (Long, Long, Long) -> Unit = { _, _, _ -> },
        client: OkHttpClient = HttpClient.downloadClient,
        taskId: String = ""
    ): TurboResult = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val safe = HttpClient.safeUrl(url)

        var effectiveClient = client
        var probeError: Throwable? = null
        var probe = probe(safe, headers, effectiveClient) { probeError = it }
        // Broken TLS chain (wetafiles omits its intermediate): the strict
        // client can't even finish the handshake, so the probe reports
        // Unreachable with no total and EVERY segmented socket below would
        // burn a ~18s handshake failure — which is exactly how v3.0.4 fell
        // back to yt-dlp (slow start, MB-only progress). One trust-all retry
        // then runs the whole job on the permissive client.
        if (probe is ProbeResult.Unreachable && probeError != null && isTlsChainFailure(probeError!!)) {
            com.anonrode.downloader.util.DebugLog.backend(
                "task=$taskId probe TLS chain failure (${probeError!!.message?.take(80)}), retrying trust-all"
            )
            effectiveClient = HttpClient.permissiveDownloadClient
            probeError = null
            probe = probe(safe, headers, effectiveClient) { probeError = it }
        }
        val total: Long
        val acceptsRanges: Boolean
        when (probe) {
            is ProbeResult.File -> {
                total = probe.total
                acceptsRanges = probe.acceptsRanges
            }
            ProbeResult.HtmlPage -> {
                // The URL answers with an HTML page (locker page, expired-token
                // error page): fail before downloading any bytes, and mark it so
                // the engine can re-resolve instead of blaming the file.
                return@withContext TurboResult.Failure(null, "Server returned an HTML page instead of a file", htmlPage = true)
            }
            is ProbeResult.Unreachable -> {
                total = probe.total
                acceptsRanges = false
            }
        }

        val sockets = socketsFor(safe, configuredSockets)
        val useSegmented = acceptsRanges && total > MIN_SEGMENTED_SIZE && sockets > 1

        val partFile = File(dest.absolutePath + ".part")
        val state = TurboState(File(dest.absolutePath + ".turbo"))
        val failureStatus = AtomicInteger(0)
        val failureMessage = AtomicReference<String?>(null)

        return@withContext if (useSegmented) {
            val ok = segmented(safe, partFile, headers, total, sockets, state, failureStatus, failureMessage, onProgress, effectiveClient, taskId)
            if (ok) {
                state.delete()
                if (moveVerified(partFile, dest, total)) TurboResult.Success(dest, dest.length(), true)
                else failure(failureStatus, failureMessage)
            } else {
                val landedBytes = state.writtenBytes() ?: 0L
                val canFallbackToSingle = !partFile.exists() || partFile.length() == 0L || landedBytes == 0L
                if (canFallbackToSingle) {
                    partFile.delete()
                    state.delete()
                    if (single(safe, partFile, headers, total, failureStatus, failureMessage, onProgress, effectiveClient, taskId)) {
                        if (moveVerified(partFile, dest, total)) TurboResult.Success(dest, dest.length(), false)
                        else failure(failureStatus, failureMessage)
                    } else failure(failureStatus, failureMessage)
                } else failure(failureStatus, failureMessage)
            }
        } else {
            // Cross-mode resume guard (engine-audit P1): a `.part` left by a
            // SEGMENTED run is pre-allocated to the FULL length, so its length
            // is not a proven prefix — single() resumes from dest.length() and
            // would either 416 forever (conforming server) or append a short
            // tail onto a file of zero holes and report Success. When a valid
            // sidecar describes the file, shrink it to the contiguous prefix
            // the sidecar vouches for; a null prefix (no/unreadable sidecar)
            // leaves the length as the only evidence — the sequential-write
            // case the old behavior was built for.
            if (partFile.exists() && partFile.length() > 0L) {
                val prefix = state.contiguousPrefixBytes()
                    ?: if (File(dest.absolutePath + ".turbo").exists()) 0L else null
                if (prefix != null && prefix < partFile.length()) {
                    try {
                        RandomAccessFile(partFile, "rw").use { it.setLength(prefix) }
                    } catch (e: Exception) {

```

---

## 6. Execution Branches & Fallback Mechanics (`DownloadEngine.kt`)

### 6.1 Aria2c Execution Branch & Turbo Fallback
```kotlin
                if (finalBackend == "aria2c" && !isMagnet) {
                    var hdrs: Map<String, String> = mutableMapOf("User-Agent" to HttpClient.DEFAULT_UA).apply {
                        if (refererToPass.isNotBlank()) put("Referer", refererToPass)
                    }
                    val dest = File(targetFolder, File(task.filePath).name)

                    // Strict pre-enqueue validation: one ranged 1KB request with
                    // the REAL download headers rejects HTML-decoy/archive URLs
                    // before any byte is persisted (the corrupted-"mp4" bug class).
                    // Failure fails the task loudly instead of queueing garbage.
                    var directRefreshAttempted = false
                    if (!isSocial) {
                        // Provably direct media files (.mp4, .mkv, .ts) and tasks with
                        // existing landed bytes bypass this pre-probe: aria2c and
                        // TurboDownloader validate HTTP responses and headers immediately
                        // on connect, eliminating 8-15s of redundant pre-flight lag.
                        val skipPreflight = isProvablyDirectFile(streamUrl) || bytesLanded(task) > 0L
                        var rejection: StreamValidator.Rejection? = null
                        if (!skipPreflight) {
                            rejection = StreamValidator.validateResult(streamUrl, hdrs)
                            if (rejection?.refreshable == true) {
                                directRefreshAttempted = true
                                coroutineContext.ensureActive()
                                val fresh = resolveStreamUrl(permUrl, task.site, task.quality ?: defaultQuality, allowCacheHit = false)
                                if (!fresh.isNullOrBlank() && fresh != streamUrl &&
                                    (isDirectMediaUrl(fresh) || isProvablyDirectFile(fresh))) {
                                    streamUrl = fresh
                                    refererToPass = taskReferer ?: getRefererForUrl(fresh)
                                    uaToPass = taskUa ?: getUserAgentForUrl(fresh)
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
                    }

                    val progressCb: (Long, Long, Long) -> Unit = { got, tot, bps ->
                        val eta = if (bps > 0 && tot > got) (tot - got) / bps else 0L
                        val live = repository.find(task.id)
                        val safeEta = if (eta > 0L) eta else if (live?.etaSeconds != null && live.etaSeconds > 0L) live.etaSeconds else 0L
                        repository.updateProgress(
                            taskId = task.id,
                            downloaded = got,
                            total = tot,
                            speed = bps.toDouble(),
                            eta = safeEta
                        )
                        updateServiceState(force = false)
                    }

                    val partFile = File(dest.absolutePath + ".part")
                    val hasTurboPartial = partFile.exists() && partFile.length() > 0L
                    val aria2Exec = if (!hasTurboPartial) YoutubeDlDownloader.findAria2Executable(context) else null
                    if (aria2Exec != null) {
                        com.anonrode.downloader.util.DebugLog.engine(
                            "task=${task.id} aria2c start sockets=$effectiveSockets url=${streamUrl.take(110)}"
                        )
                        try {
                            producedFile = YoutubeDlDownloader.download(
                                context = context,
                                taskId = task.id,
                                sourceUrl = streamUrl,
                                targetDir = targetFolder,
                                preferredFilename = File(task.filePath).name,
                                backend = "aria2c",
                                referer = refererToPass,
                                origin = if (refererToPass.isNotBlank() && refererToPass.contains("://")) {
                                    refererToPass.substringBefore("://") + "://" + refererToPass.substringAfter("://").substringBefore('/')
                                } else "",
                                customHeaders = hdrs,
                                ua = uaToPass,
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
                                privacyMode = torrentPrivacyMode,
                                estimatedTotal = task.totalBytes
                            )
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            android.util.Log.w("AnonDownload", "aria2c direct run failed (${e.message}), attempting Turbo rescue")
                        }
                    }

                    if (producedFile == null && coroutineContext.isActive) {
                        com.anonrode.downloader.util.DebugLog.engine(
                            "task=${task.id} turbo start sockets=$effectiveSockets url=${streamUrl.take(110)}"
                        )
                        turboResult = TurboDownloader.download(
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

```

### 6.2 Turbo to Aria2c Handoff
```kotlin
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
                                    val turboFile = File(dest.absolutePath + ".turbo")
                                    val prefix = if (turboFile.exists()) {
                                        TurboState(turboFile).contiguousPrefixBytes()
                                    } else if (partFile.exists() && partFile.length() > 0L) {
                                        partFile.length()
                                    } else null
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
                                            if (turboFile.exists()) TurboState(turboFile).delete()
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
                            origin = if (refererToPass.isNotBlank() && refererToPass.contains("://")) {

```

### 6.3 HLS / yt-dlp Execution Branch
```kotlin
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
                            origin = if (runReferer.isNotBlank() && runReferer.contains("://")) {
                                runReferer.substringBefore("://") + "://" + runReferer.substringAfter("://").substringBefore('/')
                            } else "",
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

```

### 6.4 Cross-Provider Failover Architecture
```kotlin
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

```

---

## 7. Operational Characteristics & Invariants Summary

1. **Concurrency Ceiling**: `maxConcurrentDownloads` defaults to 4 active tasks. Queue processing is serialized via `processQueue()`.
2. **Progress Telemetry**: The UI is updated via `repository.updateProgress(taskId, downloaded, total, speed, eta)`. If the backend stdout does not emit parsed ticks, the watchdog polls `computeDiskBytes()` to ensure UI progress movement.
3. **Interruption Mechanics**: User pauses trigger `killProcess(taskId)` for native child processes and `cancelTask(taskId)` for OkHttp coroutines.
4. **Token Refresh & Self-Healing**: Expired stream tokens (401/403/404) trigger `resolveStreamUrl(allowCacheHit = false)` to obtain rotated edge tokens before retrying.
