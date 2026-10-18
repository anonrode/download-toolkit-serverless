Copy and send the following Master Engineering Directive & Code Refactoring Specification directly to your developer or coding agent. It contains the full cross-file root-cause audit of your current codebase, the target architecture, the complete production-ready Kotlin code for every component, and a strict verification checklist.

MASTER ENGINEERING PROMPT: Complete Download Engine Audit, Bug Remediation & Architectural Overhaul
0. Directive & Execution Rules
You are tasked with conducting a 100% comprehensive bug remediation and architectural refactoring of the Android Download Engine (com.anonrode.downloader.engine).

Mandatory Operating Rules:

Do Not Patch Symptoms—Fix Root Causes: Every bug documented below stems from broken state handoffs between DownloadEngine.kt, YoutubeDlDownloader.kt, and TurboDownloader.kt. You must fix the underlying mechanisms, not add more boolean flags or ConcurrentHashMap band-aids.
Trace Every Cross-File Interaction: Before modifying any file, verify how DownloadTask paths, .part / .turbo / .aria2 sidecars, progress callbacks, and cancellation signals flow across DownloadEngine, TurboDownloader, YoutubeDlDownloader, and DownloadRepository.
Full Implementation Required: Implement all code provided in Section 3 completely. Do not leave TODO placeholders, swallowed catch (_: Throwable) {} blocks, or dead fallback branches.
Mandatory Recheck Pass: After implementing the changes, execute every verification step in Section 4 across the entire repository to ensure zero regressions.
1. Deep-Dive Cross-File Bug Audit (16 Critical Bugs Identified)
Bug 1: The Triple-Backend Ping-Pong (aria2c → Turbo → aria2c) & Orphaned Partials
Location: DownloadEngine.kt (Sections 6.1 & 6.2)
How It Breaks:
When finalBackend == "aria2c" && !isMagnet, Section 6.1 checks val hasTurboPartial = partFile.exists() && partFile.length() > 0L.
For any fresh download (hasTurboPartial == false), it executes YoutubeDlDownloader.download(..., backend = "aria2c") first.
If aria2c fails (e.g., HTTP 429 rate-limit storm caused by 16 sockets × 5 retries), producedFile is null, so it falls through and runs TurboDownloader.download(...) second.
If TurboDownloader then fails on a non-429 error, Section 6.2 renames .part and calls YoutubeDlDownloader.download(..., backend = finalBackend)—which runs aria2c a second time (third full backend attempt)!
Cross-File Corruption: When aria2c runs first in Step 2 and fails at 50 MB, it leaves Movie.mkv and Movie.mkv.aria2 on disk. When TurboDownloader runs in Step 3, it ignores Movie.mkv and starts from 0 bytes into Movie.mkv.part + Movie.mkv.turbo. You now have two competing partial downloads on disk for the same task, wasting bandwidth and breaking disk-byte accounting!
Required Fix: TurboDownloader (in-process OkHttp with dynamic piece queue and 429 backoff ladder) MUST be the primary engine for direct HTTP/HTTPS downloads. aria2c should only run for BitTorrent/Magnets or as a single fallback after TurboDownloader hands off its contiguous prefix.
Bug 2: isEmbedOrPage Dead Code Due to Default backend = "aria2c"
Location: DownloadEngine.kt (enqueue in Section 3.2 vs startTask in Section 3.4)
How It Breaks: In enqueue(), the default parameter is backend: String = "aria2c". In startTask(), isEmbedOrPage evaluates:
val isEmbedOrPage = !isMagnet && !isProvablyDirect && run {
    if (task.site.isNotBlank() || task.backend.contains("aria2c")) {
        false
    } else if (!isSocial) {
        val tp = HttpClient.probeTerminal(streamUrl, referer = getRefererForUrl(streamUrl))
        tp?.totalBytes == null
    } else true
}
Because task.backend defaults to "aria2c" for every task that isn't explicitly created as "yt-dlp", task.backend.contains("aria2c") is always true! Consequently, the else if (!isSocial) branch calling HttpClient.probeTerminal(...) is 100% dead code and never executes for pasted links. Unlisted web embed pages with default backend = "aria2c" skip probeTerminal and get routed to aria2c instead of yt-dlp.
Required Fix: Remove || task.backend.contains("aria2c") from that check, or introduce an explicit BackendPreference.AUTO vs BackendPreference.FORCED_ARIA2C enum.
Bug 3: Watchdog if (moved < 0) Math Flaw Kills Healthy ffmpeg Merges
Location: DownloadEngine.kt (Section 3.5 - Progress Watchdog)
How It Breaks:
val moved = maxOf(disk - windowStartDisk, parsed - windowStartParsed)
if (moved < 0) {
    windowStartDisk = disk
    windowStartParsed = parsed
    windowStartTime = now
    lastActivity = now
}
During a yt-dlp + ffmpeg merge pass, ffmpeg deletes .fNNN input shards after muxing, causing disk - windowStartDisk to become negative (e.g., -140 MB). However, parsed (t.downloadedBytes) remains constant at 100% during the merge, meaning parsed - windowStartParsed == 0. Because moved = maxOf(diskDelta, parsedDelta), maxOf(-140_000_000L, 0L) evaluates to 0L, never < 0! For maxOf(a, b) to be negative, both disk and parsed would have to shrink simultaneously. Thus, if (moved < 0) never fires during an ffmpeg shard cleanup, moved stays 0L during long merges, and the watchdog kills the healthy ffmpeg muxing process after STALL_TIMEOUT_MS!
Required Fix: Check if (disk < windowStartDisk || parsed < windowStartParsed) directly before computing moved.
Bug 4: Watchdog False-Stall & Double-Counting on TurboDownloader Pre-Allocated Files
Location: DownloadEngine.kt (Section 3.5) & TurboDownloader.kt (Section 5.2)
How It Breaks:
TurboDownloader pre-allocates .part files to the full totalBytes on disk immediately when starting segmented downloads.
Therefore, computeDiskBytes(t) immediately returns totalBytes on tick 1 and never increases again (diskGrew is permanently false).
Worse, in Section 3.5, var startBytes: Long? = initialDisk + initialParsed and val totalBytesNow = disk + parsed add disk and parsed together—double-counting the exact same bytes!
Required Fix: computeDiskBytes(t) must inspect TurboState(File(t.filePath + ".turbo")).writtenBytes() when a .turbo sidecar exists, rather than calling File.length() on a pre-allocated sparse .part file.
Bug 5: Watchdog Stall Log Always Prints idle=0s
Location: DownloadEngine.kt (Section 3.5)
How It Breaks:
lastActivity = now
com.anonrode.downloader.util.DebugLog.engine(
    "task=${task.id} watchdog stall kill #$stallKills (idle=${(now - lastActivity) / 1000}s ...)"
)
lastActivity is overwritten with now on the line right above the log call, so (now - lastActivity) / 1000 is permanently 0s in every log report.
Bug 6: TurboDownloader Cross-Mode Resume Destroys Newly Downloaded Bytes
Location: TurboDownloader.kt (Section 5.2)
How It Breaks: When useSegmented is false (e.g., server stopped accepting ranges or socket count dropped to 1 on resume), TurboDownloader reads the .turbo sidecar, truncates partFile to state.contiguousPrefixBytes(), and proceeds with single(...):
val prefix = state.contiguousPrefixBytes()
    ?: if (File(dest.absolutePath + ".turbo").exists()) 0L else null
if (prefix != null && prefix < partFile.length()) {
    RandomAccessFile(partFile, "rw").use { it.setLength(prefix) }
}
It forgets to delete the .turbo sidecar file after truncating partFile for single-stream mode! If single() then downloads another 200 MB into partFile and the user pauses or drops connection, the stale .turbo sidecar is still sitting on disk claiming only the old prefix is valid. On the next resume, TurboDownloader reads the stale .turbo file and truncates the newly downloaded 200 MB right off the file!
Required Fix: Immediately call state.delete() as soon as partFile is truncated to prefix before entering single().
Bug 7: fallbackRegex in downloadHttpAria2c Wipes Out Progress to 0L
Location: YoutubeDlDownloader.kt (Section 4.1)
How It Breaks:
val fb = fallbackRegex.find(line)
if (fb != null) {
    val spd = parseSpeedString(fb.groupValues[2])
    onProgress(0L, 0L, spd, 0L)
}
Passing 0L, 0L invokes repository.updateProgress(taskId, downloaded = 0L, total = 0L, speed = spd, eta = 0L). This overwrites the task's downloadedBytes and totalBytes in the repository back to 0, causing the UI progress bar to jump to 0% and resetting parsed tracking in the watchdog.
Required Fix: Pass lastDl and lastTot: onProgress(lastDl, lastTot, spd, etaCalc.update(lastDl, lastTot, spd)).
Bug 8: existingPartial Stem Collision Overwrites Unrelated Files in downloadHttpAria2c
Location: YoutubeDlDownloader.kt (Section 4.1)
How It Breaks:
val stem = preferredFilename.substringBeforeLast('.')
val existingPartial = targetDir.listFiles { f ->
    (f.name == preferredFilename || f.nameWithoutExtension == stem) &&
    f.length() > 0 && !f.name.endsWith(".aria2") && !f.name.endsWith(".part") && !f.name.endsWith(".turbo")
}?.firstOrNull()
val effOutputName = existingPartial?.name ?: preferredFilename
It matches ANY file with f.nameWithoutExtension == stem even if no .aria2 control file exists for it! If Episode 1.srt (a subtitle file) or an already-completed Episode 1.mp4 exists in targetDir, existingPartial matches it, sets effOutputName = "Episode 1.srt", and because --allow-overwrite=true is passed to aria2c, aria2c overwrites and destroys that file!
Required Fix: A file is only a resumable aria2c partial if File(f.absolutePath + ".aria2").exists(), OR if it was explicitly handed off at preferredFilename. Never match arbitrary extensions by stem unless a corresponding .aria2 control file exists alongside it.
Bug 9: isFinal(f) in downloadHttpAria2c Accepts Directories & Scans Blindly
Location: YoutubeDlDownloader.kt (Section 4.1)
How It Breaks: fun isFinal(f: File) = (f.length() > 0 || f.isDirectory) && .... For a single-file HTTP download where -d targetDir -o effOutputName was explicitly passed to aria2c, accepting f.isDirectory and falling back to fresh.maxByOrNull { it.lastModified() } can return a newly created show subfolder instead of a media file!
Bug 10: Native Process Orphaning & Non-Interruptible readLine()
Location: YoutubeDlDownloader.kt (Section 4.1)
How It Breaks:
if (Thread.currentThread().isInterrupted) inside a suspend function does nothing on coroutine cancellation.
There is a race window between val process = pb.start() and activeNativeProcesses[taskId] = process.
reader.readLine() is a blocking Java I/O call. If aria2c hangs without closing stdout, cancelling the coroutine will hang forever waiting on readLine() unless the process is forcibly destroyed via coroutineContext.job.invokeOnCompletion.
Bug 11: fullyCancelledTaskIds Set Leak & Retry Poisoning
Location: TurboDownloader.kt (Section 5.1) & DownloadEngine.kt (Section 3.1)
How It Breaks: TurboDownloader.cancelTask(taskId, isFullCancel = true) adds taskId to fullyCancelledTaskIds, and never removes it. Similarly, DownloadEngine has 8 separate ConcurrentHashMap / Set instances (activeJobs, startingTaskIds, retryBypassTasks, fullyCancelledIds, parkCycleCounts, parkedAtStamp, requeueNotBefore, failoverSites) that are not cleaned up from a single lifecycle point. If a task is retried with the same taskId, stale entries poison subsequent runs.
Bug 12: Naive URL String Slicing Corrupts Query Tokens & Fails on "manifest" Titles
Location: DownloadEngine.kt (Sections 3.3, 3.4, 6.1, 6.2)
How It Breaks:
streamUrl.substringBefore("?preview") strips all query parameters that appear after ?preview (e.g., https://cdn.site.com/video.mp4?preview=0&token=abc loses &token=abc and fails with 403)!
isStreamingLink(url) checks if (low.contains("manifest")) return true. Any episode of the TV series Manifest (e.g., https://cdn.site.com/Manifest.S01E01.mp4) is misrouted to yt-dlp as an HLS/DASH manifest!
Bug 13: Hardcoded .mkv Extension in enqueue() vs .mp4 Reality
Location: DownloadEngine.kt (Section 3.2 & Section 6.2)
How It Breaks: val ext = if (audioOnly) "mp3" else if (backend.contains("yt") || !isDirect) "mp4" else "mkv". Every direct file gets .mkv, even when the URL is explicitly .mp4. Later, Section 6.2 renames .part to .mp4 for aria2c handoff, desynchronizing the physical file on disk from task.filePath (.mkv).
Bug 14: Uniquification Loop in enqueue() Ignores Physical Disk & Runs O(N^2) Scans
Location: DownloadEngine.kt (Section 3.2)
How It Breaks: while (repository.snapshot().any { it.filePath == targetFile.absolutePath }) calls repository.snapshot() on every loop iteration inside synchronized(enqueueLock) and never checks targetFile.exists() on disk. If a user previously cleared completed tasks from the app list, repository.snapshot() is empty, so targetFile collides with the existing file on disk and overwrites it. Also, enqueuePlaylist() calls enqueue() → processQueue() inside a for loop instead of batching.
Bug 15: Unsynchronized Mutable Engine Preferences Across Threads
Location: DownloadEngine.kt (Section 3.1)
How It Breaks: Over 20 var properties (maxConcurrentDownloads, parallelSocketsPerFile, stallTimeoutSec, globalSpeedLimitKbs, etc.) are mutated from the UI thread and read on Dispatchers.IO coroutines without @Volatile or atomic snapshot isolation.
Bug 16: Global --check-certificate=false Security Vulnerability
Location: YoutubeDlDownloader.kt (Section 4.1) & TurboDownloader.kt (Section 5.2)
How It Breaks: --check-certificate=false is passed unconditionally to aria2c for every download, and TurboDownloader switches to a trust-all permissiveDownloadClient on any TLS chain error regardless of domain, exposing all downloads to trivial MitM interception on public Wi-Fi.
2. Target Clean Architecture
Replace the monolithic God-Object pattern in DownloadEngine.kt with five focused, single-responsibility components:

UrlClassifier (pipeline/UrlClassifier.kt):
Safe okhttp3.HttpUrl-based URL sanitization, query parameter removal (preview), extension extraction, origin extraction, and strict path-based manifest detection (eliminating the "manifest" TV show false-positive).
TaskRuntimeRegistry (engine/TaskRuntimeRegistry.kt):
Replaces the 8 scattered ConcurrentHashMap / Set fields in DownloadEngine and fullyCancelledTaskIds in TurboDownloader with a single thread-safe per-task state container with deterministic lifecycle cleanup.
HostPolicyRegistry (pipeline/HostPolicyRegistry.kt):
Centralizes per-host socket caps (kissorgrab.com, dl.plutomovies.com), broken TLS chain allowlists (wetafiles), and cluster definitions (ASIAN_DRAMA_CLUSTER, WESTERN_CLUSTER) out of the core engine.
ProgressWatchdog (engine/ProgressWatchdog.kt):
Encapsulates disk + sidecar + parsed byte tracking, fixes the ffmpeg merge negative-delta bug, fixes pre-allocated .part file inspection via TurboState, and fixes the idle=0s logging bug.
Unified Execution Pipeline (TurboDownloader → Aria2c Fallback):
Runs TurboDownloader first for all direct HTTP/HTTPS downloads.
Only falls back to aria2c once if TurboDownloader fails on a non-429 error, cleanly handing off the contiguous byte prefix and updating task.filePath atomically.
3. Complete Production-Ready Code Implementations
3.1 UrlClassifier.kt (New File — Safe URL Parsing & Classification)
Create com/anonrode/downloader/pipeline/UrlClassifier.kt:

package com.anonrode.downloader.pipeline

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

object UrlClassifier {

    private val STREAMING_EXTENSIONS = setOf("m3u8", "mpd", "m3u", "ism", "f4m")
    private val DIRECT_MEDIA_EXTENSIONS = setOf(
        "mp4", "mkv", "webm", "avi", "mov", "flv", "ts", "m4v",
        "mp3", "m4a", "aac", "flac", "opus", "ogg", "wav"
    )
    private val STREAMING_QUERY_REGEX = Regex("""(?:^|& )[^=&]*=(?:mpd|dash|hls|m3u8)(?:&|$)""", RegexOption.IGNORE_CASE)

    /**
     * Safely strips the `preview` query parameter ONLY if it is an actual query parameter,
     * preserving all other query parameters (such as auth tokens or signatures).
     */
    fun stripPreviewParam(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        val httpUrl = trimmed.toHttpUrlOrNull() ?: return trimmed
        if (httpUrl.queryParameter("preview") == null && ! trimmed.endsWith("?preview", ignoreCase = true)) {
            return trimmed
        }
        return httpUrl.newBuilder()
            .removeAllQueryParameters("preview")
            .build()
            .toString()
    }

    /**
     * Safely extracts the lowercase hostname without credentials, ports, or path segments.
     */
    fun extractHost(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        trimmed.toHttpUrlOrNull()?.host?.let { return it.lowercase() }
        return try {
            URI(trimmed).host?.lowercase() ?: ""
        } catch (_: Exception) {
            trimmed.substringAfter("://", "")
                .substringAfter('@')
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
                .substringBefore(':')
                .lowercase()
        }
    }

    /**
     * Safely extracts `scheme://host[:port]` for Origin headers.
     */
    fun extractOrigin(rawUrl: String): String {
        val httpUrl = rawUrl.trim().toHttpUrlOrNull() ?: return ""
        val defaultPort = (httpUrl.scheme == "http" && httpUrl.port == 80) ||
            (httpUrl.scheme == "https" && httpUrl.port == 443)
        return if (defaultPort) {
            "${httpUrl.scheme}://${httpUrl.host}"
        } else {
            "${httpUrl.scheme}://${httpUrl.host}:${httpUrl.port}"
        }
    }

    /**
     * Extracts a clean file extension (2..5 alphanumeric chars) from the URL path or
     * `filename=` query parameter, returning null if none is found.
     */
    fun extractMediaExtension(rawUrl: String): String? {
        val httpUrl = rawUrl.trim().toHttpUrlOrNull()
        val lastSegment = httpUrl?.pathSegments?.lastOrNull { it.isNotBlank() }
            ?: rawUrl.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val pathExt = lastSegment.substringAfterLast('.', "").lowercase()
        if (pathExt in DIRECT_MEDIA_EXTENSIONS) {
            return pathExt
        }
        val query = httpUrl?.encodedQuery ?: rawUrl.substringAfter('?', "")
        val match = Regex("""filename[*]?=[^&;]+\.([a-zA-Z0-9]{2,5})""").find(query)
        val queryExt = match?.groupValues?.get(1)?.lowercase()
        return if (queryExt != null && queryExt in DIRECT_MEDIA_EXTENSIONS) queryExt else null
    }

    /**
     * Strict adaptive-streaming manifest check.
     * Fixes the bug where a direct video file named "Manifest.S01E01.mp4" matched `low.contains("manifest")`.
     */
    fun isStreamingLink(rawUrl: String): Boolean {
        val low = rawUrl.trim().lowercase()
        val path = low.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "")

        // If the path explicitly ends with a direct media file extension (.mp4, .mkv, etc.),
        // it is NOT a manifest unless the query string explicitly requests an HLS/DASH format.
        if (ext in DIRECT_MEDIA_EXTENSIONS && ext != "ts") {
            val query = low.substringAfter('?', "")
            return STREAMING_QUERY_REGEX.containsMatchIn(query)
        }

        if (ext in STREAMING_EXTENSIONS) return true
        if (path.endsWith("/manifest") || path.contains("/manifest.") || path.contains("/hls/") || path.contains(".m3u8")) {
            return true
        }
        if (low.contains("kickassanime") || low.contains("playlist.php") ||
            low.contains("/api/orion/") || low.contains("stream-proxy")
        ) {
            return true
        }
        val query = low.substringAfter('?', "")
        return STREAMING_QUERY_REGEX.containsMatchIn(query)
    }
}
3.2 HostPolicyRegistry.kt (New File — Centralizes Host Caps, TLS Exceptions & Site Clusters)
Create com/anonrode/downloader/pipeline/HostPolicyRegistry.kt:

package com.anonrode.downloader.pipeline

object HostPolicyRegistry {

    val ASIAN_DRAMA_CLUSTER = setOf("asianc", "dramarain", "dramakey", "pluto", "nepu")
    val WESTERN_CLUSTER = setOf("nkiri", "9jarocks", "naijavault", "naijaprey")
    val DEDICATED_ASIAN_SITES = setOf("asianc", "dramarain", "dramakey")
    val SECONDARY_ASIAN_FALLBACK = setOf("nkiri", "9jarocks")

    private val SENSITIVE_SOCKET_CAP_HOSTS = mapOf(
        "kissorgrab.com" to 4,
        "dl.plutomovies.com" to 4,
        "plutomovies.com" to 4,
        "wetafiles.com" to 4,
        "downloadwella.com" to 4,
        "loadedfiles.org" to 4,
        "loadedfiles.net" to 4
    )

    // Explicit allowlist of locker hosts known to omit intermediate CA certificates.
    // Never disable TLS verification globally for untrusted/arbitrary hosts.
    private val BROKEN_TLS_INTERMEDIATE_HOSTS = setOf(
        "wetafiles.com",
        "downloadwella.com",
        "loadedfiles.org",
        "loadedfiles.net",
        "kissorgrab.com",
        "plutomovies.com"
    )

    fun effectiveSocketsForUrl(url: String, requestedSockets: Int): Int {
        val host = UrlClassifier.extractHost(url)
        val matchedCap = SENSITIVE_SOCKET_CAP_HOSTS.entries
            .firstOrNull { (domain, _) -> host == domain || host.endsWith(".$domain") }
            ?.value
        return if (matchedCap != null) {
            minOf(matchedCap, requestedSockets.coerceAtLeast(2))
        } else {
            requestedSockets.coerceIn(1, 16)
        }
    }

    fun allowsPermissiveTlsFallback(url: String): Boolean {
        val host = UrlClassifier.extractHost(url)
        return BROKEN_TLS_INTERMEDIATE_HOSTS.any { domain ->
            host == domain || host.endsWith(".$domain")
        }
    }
}
3.3 TaskRuntimeRegistry.kt (New File — Eliminates Map/Set Leaks)
Create com/anonrode/downloader/engine/TaskRuntimeRegistry.kt:

package com.anonrode.downloader.engine

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Consolidates all per-task runtime bookkeeping into a single lifecycle-managed entry,
 * replacing the 8 separate ConcurrentHashMaps/Sets in DownloadEngine and TurboDownloader.
 */
class TaskRuntimeRegistry {

    class TaskRuntimeEntry(val taskId: String) {
        @Volatile var activeJob: Job? = null
        val isStarting = AtomicBoolean(false)
        val retryBypassHealth = AtomicBoolean(false)
        val isFullyCancelled = AtomicBoolean(false)
        val parkCycleCount = AtomicInteger(0)
        val firstParkedAtMs = AtomicLong(0L)
        val requeueNotBeforeMs = AtomicLong(0L)
        val triedFailoverSites: MutableSet<String> = ConcurrentHashMap.newKeySet()

        fun resetParkingAndFailoverState() {
            parkCycleCount.set(0)
            firstParkedAtMs.set(0L)
            requeueNotBeforeMs.set(0L)
            triedFailoverSites.clear()
        }
    }

    private val entries = ConcurrentHashMap<String, TaskRuntimeEntry>()

    fun getOrCreate(taskId: String): TaskRuntimeEntry =
        entries.computeIfAbsent(taskId) { TaskRuntimeEntry(it) }

    fun get(taskId: String): TaskRuntimeEntry? = entries[taskId]

    fun tryAcquireStartLock(taskId: String): Boolean {
        val entry = getOrCreate(taskId)
        val existingJob = entry.activeJob
        if (existingJob != null && existingJob.isActive) return false
        return entry.isStarting.compareAndSet(false, true)
    }

    fun attachJob(taskId: String, job: Job) {
        val entry = getOrCreate(taskId)
        entry.activeJob = job
        entry.isStarting.set(false)
    }

    fun releaseStartLock(taskId: String) {
        entries[taskId]?.isStarting?.set(false)
    }

    fun markForManualRetry(taskId: String) {
        val entry = getOrCreate(taskId)
        entry.isFullyCancelled.set(false)
        entry.retryBypassHealth.set(true)
        entry.resetParkingAndFailoverState()
    }

    fun consumeRetryBypass(taskId: String): Boolean =
        entries[taskId]?.retryBypassHealth?.getAndSet(false) ?: false

    fun markFullyCancelled(taskId: String) {
        getOrCreate(taskId).isFullyCancelled.set(true)
    }

    fun isFullyCancelled(taskId: String): Boolean =
        entries[taskId]?.isFullyCancelled?.get() ?: false

    /**
     * Called when a task is permanently removed or completed to prevent memory leaks.
     */
    fun remove(taskId: String) {
        entries.remove(taskId)?.activeJob?.cancel()
    }
}
3.4 Refactored TurboDownloader.kt (Fixes Cross-Mode Truncation, TLS Leak & Cancel Set Leak)
Update TurboDownloader.kt with the following fixes:

package com.anonrode.downloader.engine

import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.pipeline.HostPolicyRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

object TurboDownloader {

    private const val BUFFER = 256 * 1024
    private const val MIN_SEGMENTED_SIZE = 8L * 1024 * 1024
    private const val MAX_ATTEMPTS = 5
    private const val RETRY_CAP = 8L
    private const val THROTTLE_BASE_MS = 15_000L
    private const val THROTTLE_MAX_MS = 5 * 60_000L

    internal var retryBaseDelayMs: Long = 1000L
    internal var throttleBaseDelayMs: Long = THROTTLE_BASE_MS

    private val activeCalls = ConcurrentHashMap<String, CopyOnWriteArrayList<Call>>()
    private val fullyCancelledTaskIds = ConcurrentHashMap.newKeySet<String>()

    fun cancelTask(taskId: String, isFullCancel: Boolean = false) {
        if (isFullCancel) {
            fullyCancelledTaskIds.add(taskId)
        }
        activeCalls.remove(taskId)?.forEach { it.cancel() }
    }

    fun clearCancelledFlag(taskId: String) {
        fullyCancelledTaskIds.remove(taskId)
    }

    /**
     * Returns actual verified bytes written to disk for [dest], properly handling
     * pre-allocated segmented `.part` files by reading the `.turbo` sidecar first.
     */
    fun computeVerifiedDiskBytes(dest: File): Long {
        val turboFile = File(dest.absolutePath + ".turbo")
        val partFile = File(dest.absolutePath + ".part")
        if (turboFile.exists()) {
            val state = TurboState(turboFile)
            state.writtenBytes()?.let { return it }
            state.contiguousPrefixBytes()?.let { return it }
            return 0L // Pre-allocated .part file with unreadable sidecar has 0 verified bytes
        }
        if (partFile.exists()) {
            return partFile.length()
        }
        if (dest.exists()) {
            return dest.length()
        }
        return 0L
    }

    suspend fun download(
        url: String,
        dest: File,
        headers: Map<String, String> = emptyMap(),
        configuredSockets: Int = 8,
        onProgress: (Long, Long, Long) -> Unit = { _, _, _ -> },
        client: OkHttpClient = HttpClient.downloadClient,
        taskId: String = ""
    ): TurboResult = withContext(Dispatchers.IO) {
        // Clear any stale cancellation flag from a prior run of this taskId
        if (taskId.isNotBlank()) {
            fullyCancelledTaskIds.remove(taskId)
        }

        try {
            dest.parentFile?.mkdirs()
            val safe = HttpClient.safeUrl(url)

            var effectiveClient = client
            var probeError: Throwable? = null
            var probe = probe(safe, headers, effectiveClient) { probeError = it }

            // SECURITY FIX: Only fallback to permissiveDownloadClient if the host is on
            // the known broken-intermediate allowlist in HostPolicyRegistry.
            if (probe is ProbeResult.Unreachable &&
                probeError != null &&
                isTlsChainFailure(probeError!!) &&
                HostPolicyRegistry.allowsPermissiveTlsFallback(safe)
            ) {
                com.anonrode.downloader.util.DebugLog.backend(
                    "task=$taskId probe TLS chain failure on allowlisted host (${probeError!!.message?.take(80)}), retrying with permissive client"
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
                    return@withContext TurboResult.Failure(
                        httpStatus = null,
                        message = "Server returned an HTML page instead of a media file",
                        htmlPage = true
                    )
                }
                is ProbeResult.Unreachable -> {
                    total = probe.total
                    acceptsRanges = false
                }
            }

            val sockets = HostPolicyRegistry.effectiveSocketsForUrl(safe, configuredSockets)
            val useSegmented = acceptsRanges && total > MIN_SEGMENTED_SIZE && sockets > 1

            val partFile = File(dest.absolutePath + ".part")
            val turboSidecar = File(dest.absolutePath + ".turbo")
            val state = TurboState(turboSidecar)
            val failureStatus = AtomicInteger(0)
            val failureMessage = AtomicReference<String?>(null)

            if (useSegmented) {
                val ok = segmented(
                    safe, partFile, headers, total, sockets, state,
                    failureStatus, failureMessage, onProgress, effectiveClient, taskId
                )
                if (ok) {
                    state.delete()
                    if (moveVerified(partFile, dest, total)) {
                        TurboResult.Success(dest, dest.length(), true)
                    } else {
                        failure(failureStatus, failureMessage)
                    }
                } else {
                    val landedBytes = state.writtenBytes() ?: 0L
                    val canFallbackToSingle = !partFile.exists() || partFile.length() == 0L || landedBytes == 0L
                    if (canFallbackToSingle) {
                        partFile.delete()
                        state.delete()
                        if (single(safe, partFile, headers, total, failureStatus, failureMessage, onProgress, effectiveClient, taskId)) {
                            if (moveVerified(partFile, dest, total)) {
                                TurboResult.Success(dest, dest.length(), false)
                            } else {
                                failure(failureStatus, failureMessage)
                            }
                        } else {
                            failure(failureStatus, failureMessage)
                        }
                    } else {
                        failure(failureStatus, failureMessage)
                    }
                }
            } else {
                // BUG FIX (Cross-Mode Resume): If transitioning from a pre-allocated segmented
                // `.part` file to single-stream mode, truncate `.part` to the contiguous prefix
                // AND immediately delete the `.turbo` sidecar so future resumes do not re-truncate
                // newly appended single-stream bytes!
                if (partFile.exists() && partFile.length() > 0L && turboSidecar.exists()) {
                    val prefix = state.contiguousPrefixBytes() ?: 0L
                    try {
                        if (prefix < partFile.length()) {
                            RandomAccessFile(partFile, "rw").use { it.setLength(prefix) }
                        }
                        state.delete()
                    } catch (e: Exception) {
                        partFile.delete()
                        state.delete()
                    }
                }
                if (single(safe, partFile, headers, total, failureStatus, failureMessage, onProgress, effectiveClient, taskId)) {
                    state.delete()
                    if (moveVerified(partFile, dest, total)) {
                        TurboResult.Success(dest, dest.length(), false)
                    } else {
                        failure(failureStatus, failureMessage)
                    }
                } else {
                    failure(failureStatus, failureMessage)
                }
            }
        } finally {
            if (taskId.isNotBlank()) {
                activeCalls.remove(taskId)
                fullyCancelledTaskIds.remove(taskId)
            }
        }
    }
}
3.5 Refactored YoutubeDlDownloader.kt (downloadHttpAria2c Fixes)
Replace downloadHttpAria2c in YoutubeDlDownloader.kt with this hardened implementation:

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
    ): File? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val aria2Exec = findAria2Executable(context)
            ?: throw IllegalStateException("aria2c binary missing: libaria2c.so not found in native libs")

        targetDir.mkdirs()

        // BUG FIX: Only resume an existing file if it matches preferredFilename directly
        // OR has an active `.aria2` control file next to it. Never match arbitrary files
        // by stem alone (which previously matched and overwrote .srt subtitles or finished files).
        val stem = preferredFilename.substringBeforeLast('.')
        val existingPartial = targetDir.listFiles { f ->
            f.isFile && f.length() > 0 && (
                f.name == preferredFilename ||
                (f.nameWithoutExtension == stem && File(f.absolutePath + ".aria2").exists())
            )
        }?.firstOrNull()
        val effOutputName = existingPartial?.name ?: preferredFilename
        val expectedOutputFile = File(targetDir, effOutputName)
        val expectedControlFile = File(expectedOutputFile.absolutePath + ".aria2")

        val sockets = com.anonrode.downloader.pipeline.HostPolicyRegistry.effectiveSocketsForUrl(url, parallelSockets)
        val allowInsecure = com.anonrode.downloader.pipeline.HostPolicyRegistry.allowsPermissiveTlsFallback(url)

        val cmd = mutableListOf(
            aria2Exec.absolutePath,
            "-c",
            "-d", targetDir.absolutePath,
            "-o", effOutputName,
            "--file-allocation=none",
            "--check-certificate=${!allowInsecure}",
            "--summary-interval=1",
            "--max-tries=5",
            "--retry-wait=2",
            "--connect-timeout=15",
            "--timeout=30",
            "--auto-file-renaming=false",
            // Only allow overwrite if an aria2 control file exists or we are resuming a partial
            "--allow-overwrite=${expectedControlFile.exists() || existingPartial != null}",
            "--console-log-level=error",
            "-s", "$sockets",
            "-x", "$sockets",
            "-j", "$sockets",
            "--max-connection-per-server=$sockets",
            "--min-split-size=1M"
        )

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
                k.equals("origin", ignoreCase = true)
            ) continue
            val cleanK = k.replace("\r", "").replace("\n", "").trim()
            val cleanV = v.replace("\r", "").replace("\n", "").trim()
            if (cleanK.isNotBlank() && cleanV.isNotBlank()) {
                cmd += "--header=$cleanK:$cleanV"
            }
        }
        if (speedLimitKbs > 0) {
            cmd += "--max-download-limit=${speedLimitKbs}K"
        }
        cmd += url

        val errors = StringBuilder()
        var produced: File? = null
        var attempts = 0

        suspend fun runOnce(): File? {
            coroutineContext.ensureActive()
            // Ensure any previous process for this taskId is terminated before starting a new one
            activeNativeProcesses.remove(taskId)?.destroyForcibly()

            val pb = ProcessBuilder(cmd).apply {
                environment()["TMPDIR"] = context.cacheDir.absolutePath
                directory(targetDir)
                redirectErrorStream(true)
            }

            val process = pb.start()
            activeNativeProcesses[taskId] = process

            // Guarantee process termination if the parent coroutine is cancelled while blocked on I/O
            val completionHandle = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion {
                if (process.isAlive) {
                    process.destroyForcibly()
                }
            }

            var lastDl = if (expectedOutputFile.exists()) expectedOutputFile.length() else 0L
            var lastTot = estimatedTotal
            val etaCalc = FlickerFreeEtaCalculator()
            val fallbackRegex = Regex("""\((\d+)\%\).*?DL:\s*([\d.]+[KMGT]?i?B)""", RegexOption.IGNORE_CASE)
            val logBuffer = ArrayDeque<String>(50)
            var exitCode: Int? = null

            try {
                process.inputStream.bufferedReader().use { reader ->
                    var line: String? = reader.readLine()
                    while (line != null) {
                        coroutineContext.ensureActive()
                        if (logBuffer.size >= 50) logBuffer.removeFirst()
                        logBuffer.addLast(line)

                        val tick = parseProgressTick(line, 0f, lastDl, lastTot)
                        if (tick.downloadedBytes > 0L || tick.totalBytes > 0L || tick.speedBytesPerSec > 0.0) {
                            lastDl = maxOf(lastDl, tick.downloadedBytes)
                            lastTot = maxOf(lastTot, tick.totalBytes)
                            val eta = if (tick.etaSeconds > 0L) {
                                tick.etaSeconds
                            } else {
                                etaCalc.update(lastDl, lastTot, tick.speedBytesPerSec)
                            }
                            onProgress(lastDl, lastTot, tick.speedBytesPerSec, eta)
                        } else {
                            val fb = fallbackRegex.find(line)
                            if (fb != null) {
                                val spd = parseSpeedString(fb.groupValues[2])
                                val pct = fb.groupValues[1].toLongOrNull() ?: 0L
                                val inferredDl = if (lastDl > 0L) lastDl else if (lastTot > 0L && pct in 1..100) (lastTot * pct) / 100L else 0L
                                val eta = etaCalc.update(inferredDl, lastTot, spd)
                                // BUG FIX: Never pass (0L, 0L, spd, 0L) which wipes out DB/UI progress!
                                onProgress(inferredDl, lastTot, spd, eta)
                            }
                        }
                        line = reader.readLine()
                    }
                }
                exitCode = process.waitFor()
            } catch (e: kotlinx.coroutines.CancellationException) {
                process.destroyForcibly()
                throw e
            } catch (e: Exception) {
                process.destroyForcibly()
                errors.append("run ").append(attempts).append(": ")
                    .append(e.message ?: e.javaClass.simpleName).append('\n')
                return null
            } finally {
                completionHandle?.dispose()
                activeNativeProcesses.remove(taskId, process)
                if (process.isAlive) {
                    process.destroyForcibly()
                }
            }

            if (exitCode != 0) {
                val errSummary = logBuffer.takeLast(3).joinToString(" | ")
                errors.append("run ").append(attempts).append(" exit ").append(exitCode)
                    .append(": ").append(errSummary).append('\n')
                return null
            }

            // BUG FIX: Check the exact expected output file and verify it is a file (never a directory)
            // and has no lingering .aria2 control file.
            if (expectedOutputFile.isFile && expectedOutputFile.length() > 0L && !expectedControlFile.exists()) {
                return expectedOutputFile
            }

            errors.append("run ").append(attempts)
                .append(": output file missing or incomplete (.aria2 control file still present)\n")
            return null
        }

        while (attempts < maxAttempts && produced == null) {
            attempts++
            if (!isActiveCheck()) throw kotlinx.coroutines.CancellationException("Task cancelled before aria2c retry")
            produced = runOnce()
            if (produced == null && attempts < maxAttempts) {
                cancellableRetryWait(2_000L * attempts, isActiveCheck = isActiveCheck)
            }
        }

        if (produced == null) {
            throw Exception("aria2c HTTP download failed after $attempts attempt(s):${errors.toString().trim()}")
        }
        produced
    }
3.6 ProgressWatchdog.kt (New File — Replaces the Broken Inline Watchdog in DownloadEngine.kt)
Create com/anonrode/downloader/engine/ProgressWatchdog.kt:

package com.anonrode.downloader.engine

import com.anonrode.downloader.model.DownloadTask
import com.anonrode.downloader.model.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ProgressWatchdog(
    private val repository: DownloadRepository,
    private val computeVerifiedDiskBytes: (DownloadTask) -> Long,
    private val onStallKill: (taskId: String, killCount: Int, exhausted: Boolean, stallMessage: String, task: DownloadTask) -> Unit
) {
    companion object {
        private const val CRAWL_WINDOW_BYTES = 64L * 1024 // 64 KiB floor per stall window
        private const val THROTTLE_SESSION_CAP_MS = 15 * 60_000L
        private const val MAX_STALL_KILLS = 6
    }

    fun launchForTask(
        scope: CoroutineScope,
        initialTask: DownloadTask,
        streamUrl: String,
        stallTimeoutSecProvider: () -> Int
    ): Job = scope.launch {
        val initialDisk = computeVerifiedDiskBytes(initialTask)
        val initialParsed = initialTask.downloadedBytes
        var lastDisk = initialDisk
        var lastParsed = initialParsed
        var lastActivity = System.currentTimeMillis()
        var lastKillTime = 0L
        var stallKills = 0
        var throttleFirstSeen = 0L

        var windowStartDisk = initialDisk
        var windowStartParsed = initialParsed
        var windowStartTime = System.currentTimeMillis()
        var bestWindowBps = 0.0
        var lastProgressLog = 0L
        val watchdogStart = System.currentTimeMillis()

        while (isActive) {
            delay(2000L)
            if (!isActive) break
            val t = repository.find(initialTask.id) ?: break
            when (t.status) {
                TaskStatus.DOWNLOADING -> {}
                TaskStatus.RESOLVING -> continue
                else -> break
            }

            val stallTimeoutSec = stallTimeoutSecProvider().coerceAtLeast(15)
            val stallTimeoutMs = stallTimeoutSec * 1000L
            val now = System.currentTimeMillis()

            // BUG FIX: computeVerifiedDiskBytes reads TurboState sidecar for pre-allocated .part files
            val disk = computeVerifiedDiskBytes(t)
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

            if (TurboDownloader.throttleRemainingMs(initialTask.id) > 0L) {
                if (throttleFirstSeen == 0L) throttleFirstSeen = now
                lastActivity = now
            }
            val throttleGrace = throttleFirstSeen > 0L && (now - throttleFirstSeen) <= THROTTLE_SESSION_CAP_MS

            // BUG FIX: Detect shrinking disk footprint (e.g. yt-dlp/ffmpeg deleting .fNNN shards
            // during muxing) or reset parsed counter BEFORE taking maxOf(diskDelta, parsedDelta).
            if (disk < windowStartDisk || parsed < windowStartParsed) {
                windowStartDisk = disk
                windowStartParsed = parsed
                windowStartTime = now
                lastActivity = now
            }

            val moved = maxOf(disk - windowStartDisk, parsed - windowStartParsed)

            if (moved > 0 && now - lastProgressLog >= 10_000L) {
                lastProgressLog = now
                com.anonrode.downloader.util.DebugLog.engine(
                    "task=${initialTask.id} progress disk=${disk / 1024}KiB parsed=${parsed / 1024}KiB window=${moved / 1024}KiB"
                )
            }

            // Bridge verified filesystem progress to UI when stdout parser lags
            if (disk > t.downloadedBytes) {
                val safeDisk = if (t.totalBytes > 0L && disk >= t.totalBytes) {
                    t.downloadedBytes
                } else {
                    disk
                }
                if (safeDisk > t.downloadedBytes) {
                    val speed = if (t.speedBytesPerSec > 0.0) {
                        t.speedBytesPerSec
                    } else {
                        diskDelta.coerceAtLeast(0L) * 1000.0 / 2000.0
                    }
                    val eta = if (t.etaSeconds > 0L) {
                        t.etaSeconds
                    } else if (speed > 0.0 && t.totalBytes > safeDisk) {
                        ((t.totalBytes - safeDisk) / speed).toLong().coerceAtLeast(1L)
                    } else {
                        0L
                    }
                    repository.updateProgress(
                        taskId = initialTask.id,
                        downloaded = safeDisk,
                        total = t.totalBytes,
                        speed = speed,
                        eta = eta
                    )
                }
            }

            val windowSecs = ((now - windowStartTime).coerceAtLeast(500L)) / 1000.0
            val windowBps = if (windowSecs > 0.0) moved / windowSecs else 0.0
            if (windowBps > bestWindowBps) bestWindowBps = windowBps

            if (moved >= CRAWL_WINDOW_BYTES) {
                windowStartDisk = disk
                windowStartParsed = parsed
                windowStartTime = now
                if (windowBps >= bestWindowBps * 0.5 || bestWindowBps < 1.0 * 1024 * 1024) {
                    stallKills = 0
                }
            }

            val crawlStalled = (now - windowStartTime > stallTimeoutMs) && (moved < CRAWL_WINDOW_BYTES)
            val magnetTask = streamUrl.startsWith("magnet:", ignoreCase = true)
            val attemptBoundary = if (magnetTask) 90_000L else maxOf(stallKills * stallTimeoutMs * 2, stallTimeoutMs)
            val stalledLong = (now - lastActivity) > stallTimeoutMs
            val isStalledOrCrawling = stalledLong || crawlStalled

            if (!magnetTask &&
                !throttleGrace &&
                isStalledOrCrawling &&
                (now - watchdogStart) > attemptBoundary &&
                (stallKills == 0 || (now - lastKillTime) > stallTimeoutMs)
            ) {
                // BUG FIX: Compute idleSeconds BEFORE resetting lastActivity = now!
                val idleSeconds = (now - lastActivity) / 1000L
                stallKills++
                lastKillTime = now
                windowStartDisk = disk
                windowStartParsed = parsed
                windowStartTime = now
                lastActivity = now

                com.anonrode.downloader.util.DebugLog.engine(
                    "task=${initialTask.id} watchdog stall kill #$stallKills (idle=${idleSeconds}s crawl=$crawlStalled window=${moved / 1024}KiB best=${(bestWindowBps / 1024).toInt()}KiB/s)"
                )

                val exhausted = stallKills >= MAX_STALL_KILLS
                val stallMsg = "Download stalled — no progress for ${stallTimeoutSec}s across $stallKills attempts"
                onStallKill(initialTask.id, stallKills, exhausted, stallMsg, t)
                if (exhausted) break
            }
        }
    }
}
3.7 Refactored DownloadEngine.kt (Fixes Enqueue, Routing, Execution Order & Handoff)
Replace the corresponding sections in DownloadEngine.kt with the following implementation:

    // 1. Replace the 8 scattered maps/sets with TaskRuntimeRegistry:
    private val runtimeRegistry = TaskRuntimeRegistry()

    // 2. Make all cross-thread configuration variables @Volatile:
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
    @Volatile var stallTimeoutSec: Int = 60
    @Volatile var magnetMaxAttempts: Int = 3
    @Volatile var ytdlpMaxAttempts: Int = 3
    @Volatile var hlsFragmentConcurrency: Int = 16
    @Volatile var globalSpeedLimitKbs: Int = 0
    @Volatile var torrentPeers: Int = -1
    @Volatile var torrentPrivacyMode: Boolean = false
    @Volatile var wifiOnlyAll: Boolean = false
    @Volatile var clipboardDetect: Boolean = true
    @Volatile var completionNotifications: Boolean = true
    @Volatile var debugLogging: Boolean = false
    @Volatile var logRetentionDays: Int = 7
    @Volatile var downloadSubtitles: Boolean = true
    @Volatile var subtitleLanguage: String = "en"
    @Volatile var filterExplicitContent: Boolean = true

    // 3. Updated enqueue() with single snapshot read, physical disk collision check,
    //    smart extension detection, and lock-free retry triggering:
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
        verifiedDirectUrl: String? = null,
        mirrorUrls: List<String> = emptyList(),
        seriesUrl: String = "",
        seriesTotal: Int = 0,
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
                TaskStatus.VALIDATING,
                TaskStatus.PAUSED
            )
            val existing = currentTasks.firstOrNull { it.sourceUrl == sourceUrl && it.status in activeStatuses }
            if (existing != null) {
                return@synchronized existing.id
            }

            val failedExisting = currentTasks.firstOrNull { it.sourceUrl == sourceUrl && it.status == TaskStatus.FAILED }
            if (failedExisting != null) {
                retryTaskId = failedExisting.id
                return@synchronized failedExisting.id
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
            retry(retryTaskId!!)
        } else if (triggerQueueProcessing) {
            processQueue()
        }
        return taskId
    }

    // 4. Batched enqueuePlaylist() — calls processQueue() ONCE at the end instead of N times:
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
Refactored startTask & Direct Execution Branch (TurboDownloader First → aria2c Fallback Once):
        if (!runtimeRegistry.tryAcquireStartLock(task.id)) return
        val live = repository.find(task.id)
        if (live == null || live.status !in setOf(TaskStatus.QUEUED, TaskStatus.RESOLVING, TaskStatus.DOWNLOADING)) {
            runtimeRegistry.releaseStartLock(task.id)
            return
        }

        val job = engineScope.launch {
            var watchdogJob: Job? = null
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
                                    resolveStreamUrl(
                                        candidate,
                                        task.site,
                                        task.quality ?: defaultQuality,
                                        bypassHealth = bypassHealth,
                                        allowCacheHit = true,
                                        memoTaskId = task.id
                                    )
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    null
                                }
                                if (!output.isNullOrBlank()) {
                                    ResolveBudget.recordHostOk(candidate)
                                    permUrl = candidate
                                    if (task.mirrorUrls.isNotEmpty()) {
                                        repository.update(task.id) { it.copy(selectedMirrorUrl = candidate) }
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
                        com.anonrode.downloader.util.DebugLog.error("task=${task.id} resolution timed out after${RESOLVE_TIMEOUT_MS / 1000}s")
                        if (attemptCrossProviderFailover(task)) return@launch
                        throw Exception("Link resolution timed out — the site took too long to answer. Retry, or try another server/episode.")
                    }

                    if (!resolved.isNullOrBlank()) {
                        runtimeRegistry.getOrCreate(task.id).resetParkingAndFailoverState()
                        ResolveBudget.forgetTask(task.id)
                        streamUrl = com.anonrode.downloader.pipeline.UrlClassifier.stripPreviewParam(resolved)
                    } else if (isKnownLockerHost(streamUrl) || !isDirectMediaUrl(streamUrl)) {
                        if (task.site.isNotBlank()) {
                            val host = com.anonrode.downloader.pipeline.UrlClassifier.extractHost(streamUrl)
                            val hostReason = com.anonrode.downloader.pipeline.HostHealth.lastReason(streamUrl)
                            val reasonSuffix = if (!hostReason.isNullOrBlank()) " ($hostReason)" else ""
                            com.anonrode.downloader.util.DebugLog.error("task=${task.id} could not crack stream link (host=$host)$reasonSuffix for${streamUrl.take(120)}")

                            if (attemptCrossProviderFailover(task)) return@launch

                            if (!com.anonrode.downloader.pipeline.HostHealth.isUsable(streamUrl)) {
                                val mins = maxOf(1L, com.anonrode.downloader.pipeline.HostHealth.remainingBackoffMs(streamUrl) / 60_000L)
                                val entry = runtimeRegistry.getOrCreate(task.id)
                                val cycles = entry.parkCycleCount.incrementAndGet()
                                val landed = bytesLanded(task)
                                if (cycles > MAX_PARK_CYCLES && landed == 0L) {
                                    entry.resetParkingAndFailoverState()
                                    throw Exception(
                                        "$host has been unreachable across$MAX_PARK_CYCLES cooldown cycles — giving up so the queue is not stuck on it."
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
                            throw Exception("Could not get a download link from the file host ($host) — try again in a few minutes.")
                        }
                    }
                }

                val isHlsStream = com.anonrode.downloader.pipeline.UrlClassifier.isStreamingLink(streamUrl)
                val isProvablyDirect = isProvablyDirectFile(streamUrl) || isDirectMediaUrl(streamUrl) || isHlsStream

                // BUG FIX: Removed `|| task.backend.contains("aria2c")` which previously short-circuited
                // every task using the default backend="aria2c" parameter and made probeTerminal dead code!
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
                var currentTask = repository.find(task.id) ?: task
                if (!isMagnet && finalBackend == "aria2c" && bytesLanded(currentTask) == 0L) {
                    val resolvedExt = com.anonrode.downloader.pipeline.UrlClassifier.extractMediaExtension(streamUrl)
                    val currentFile = File(currentTask.filePath)
                    if (resolvedExt != null && !currentFile.extension.equals(resolvedExt, ignoreCase = true)) {
                        val updatedFile = File(currentFile.parentFile, "${currentFile.nameWithoutExtension}.$resolvedExt")
                        val pathTaken = repository.snapshot().any { it.id != task.id && it.filePath == updatedFile.absolutePath }
                        if (!pathTaken && !updatedFile.exists()) {
                            repository.update(task.id) { it.copy(filePath = updatedFile.absolutePath) }
                            currentTask = repository.find(task.id) ?: currentTask
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
                    initialTask = currentTask,
                    streamUrl = streamUrl,
                    stallTimeoutSecProvider = { stallTimeoutSec }
                )

                // FIXED EXECUTION ORDER FOR DIRECT HTTP DOWNLOADS:
                // 1. Run TurboDownloader FIRST (handles segmented Range probes, 429 rate-limit backoff, and piece retries).
                // 2. If token expired (401/403/404/HTML), self-heal by re-resolving streamUrl once and retrying TurboDownloader.
                // 3. Only if TurboDownloader still fails on a non-429 error, hand off the contiguous prefix to aria2c ONCE.
                if (finalBackend == "aria2c" && !isMagnet) {
                    var dest = File(targetFolder, File(currentTask.filePath).name)
                    var hdrs = buildHeaders(refererToPass, uaToPass)

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

                    // Step 1: Primary execution via TurboDownloader
                    com.anonrode.downloader.util.DebugLog.engine(
                        "task=${task.id} turbo primary start sockets=$effectiveSockets url=${streamUrl.take(110)}"
                    )
                    var turboResult = TurboDownloader.download(
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
                            // Step 3: Single fallback to native aria2c (never fallback on 429 rate-limit)
                            if (turboResult.httpStatus != 429 && coroutineContext.isActive) {
                                handoffTurboPrefixToAria2c(dest)
                                com.anonrode.downloader.util.DebugLog.engine(
                                    "task=${task.id} turbo failed (${turboResult.message}), falling back once to aria2c"
                                )
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
                            }
                        }
                    }
                }
Clean handoffTurboPrefixToAria2c Helper (Add to DownloadEngine.kt):
    /**
     * Safely converts a TurboDownloader `.part` + `.turbo` pair into a contiguous prefix
     * file at [dest] so native `aria2c -c` can resume from the exact byte offset without
     * extension mismatches or orphaned files.
     */
    private fun handoffTurboPrefixToAria2c(dest: File) {
        try {
            val partFile = File(dest.absolutePath + ".part")
            val turboFile = File(dest.absolutePath + ".turbo")
            if (!partFile.exists()) return

            val prefix = if (turboFile.exists()) {
                TurboState(turboFile).contiguousPrefixBytes() ?: 0L
            } else {
                partFile.length()
            }

            if (prefix > 0L && prefix <= partFile.length() && !dest.exists() && !File(dest.absolutePath + ".aria2").exists()) {
                if (partFile.renameTo(dest)) {
                    RandomAccessFile(dest, "rw").use { it.setLength(prefix) }
                    if (turboFile.exists()) TurboState(turboFile).delete()
                }
            } else if (prefix == 0L) {
                partFile.delete()
                if (turboFile.exists()) TurboState(turboFile).delete()
            }
        } catch (e: Exception) {
            com.anonrode.downloader.util.DebugLog.error("handoffTurboPrefixToAria2c failed: ${e.message}")
        }
    }
4. Mandatory Verification & Recheck Checklist
Before marking this task complete, you must perform a full audit across all modified and interacting files and verify every item below:

[ ] 1. Execution Order Check (DownloadEngine.kt): Confirm that for finalBackend == "aria2c" && !isMagnet, TurboDownloader.download(...) runs first, and YoutubeDlDownloader.download(..., backend = "aria2c") runs at most once as a fallback when turboResult.httpStatus != 429. Confirm the old first-pass aria2c block before TurboDownloader is completely removed.
[ ] 2. Probe Terminal Reachability (DownloadEngine.kt): Confirm that isEmbedOrPage no longer checks || task.backend.contains("aria2c"), and write/run a test verifying that an unknown non-media URL with default backend = "aria2c" triggers HttpClient.probeTerminal(...).
[ ] 3. Watchdog Merge & Pre-Allocation Check (ProgressWatchdog.kt & TurboDownloader.kt):
Confirm computeVerifiedDiskBytes reads TurboState.writtenBytes() when .turbo exists, never raw partFile.length().
Confirm if (disk < windowStartDisk || parsed < windowStartParsed) runs before val moved = maxOf(...) so ffmpeg shard deletions do not trigger stall kills.
Confirm val idleSeconds = (now - lastActivity) / 1000L is calculated before lastActivity = now.
[ ] 4. Cross-Mode Truncation Check (TurboDownloader.kt): Confirm that when useSegmented == false and a .turbo sidecar exists, state.delete() is called immediately after truncating partFile to prefix so subsequent single-stream resumes never truncate newly downloaded bytes.
[ ] 5. aria2c Progress & Stem Collision Check (YoutubeDlDownloader.kt):
Confirm fallbackRegex passes inferredDl and lastTot to onProgress(...), never (0L, 0L, spd, 0L).
Confirm existingPartial never matches a file by nameWithoutExtension == stem unless File(f.absolutePath + ".aria2").exists() is true.
Confirm process.destroyForcibly() is bound to coroutineContext[Job]?.invokeOnCompletion.
[ ] 6. URL Slicing & "manifest" False-Positive Check (UrlClassifier.kt):
Confirm all .substringBefore("?preview") calls are replaced with UrlClassifier.stripPreviewParam(...).
Confirm UrlClassifier.isStreamingLink("https://cdn.example.com/Manifest.S01E01.720p.mp4") returns false.
Confirm UrlClassifier.isStreamingLink("https://cdn.example.com/live/master.m3u8") and UrlClassifier.isStreamingLink("https://cdn.example.com/stream/manifest.mpd") return true.
[ ] 7. Memory & Concurrency Audit: Confirm all 8 task maps/sets are replaced by TaskRuntimeRegistry, fullyCancelledTaskIds is cleaned up in finally, and all mutable settings on DownloadEngine are annotated with @Volatile.
