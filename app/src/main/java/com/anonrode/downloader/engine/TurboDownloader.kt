package com.anonrode.downloader.engine

import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.data.net.isTlsChainFailure
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * High-performance multi-socket segmented downloader for direct CDN files.
 *
 * Designed with:
 * 1. Definitive Range verification (probe with fallback bytes=0-0 GET).
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

    /**
     * Interrupt every in-flight transfer of a task. The affected pieces fail,
     * are retried per the normal piece policy, and the task eventually FAILED
     * instead of hanging in DOWNLOADING forever. Idempotent for unknown ids.
     */
    fun cancelTask(taskId: String) {
        activeCalls.remove(taskId)?.forEach { it.cancel() }
    }

    /**
     * taskId → epoch-millis until which the run is deliberately sitting out a
     * server rate-limit. Bytes will NOT move while a deadline is live — the
     * download is alive and waiting (the "1DM freezes the bar, then resumes"
     * behavior). The engine's stall watchdog consults [throttleRemainingMs]
     * and refuses every kill decision while a cooldown is live, so the
     * in-session wait can never be cut short into a FAILED card. Entries
     * live exactly as long as the run that set them (removed in the
     * segmented()/single() finally blocks).
     */
    private val throttleDeadlines = ConcurrentHashMap<String, Long>()

    /** Milliseconds left on the task's live rate-limit cooldown (0 if none). */
    fun throttleRemainingMs(taskId: String): Long {
        if (taskId.isEmpty()) return 0L
        val deadline = throttleDeadlines[taskId] ?: return 0L
        return (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /** Registers a call against [taskId] (empty = untracked, e.g. tests). */
    private fun trackCall(taskId: String, call: Call) {
        if (taskId.isEmpty()) return
        activeCalls.getOrPut(taskId) { CopyOnWriteArrayList() }.add(call)
    }

    private fun untrackCall(taskId: String, call: Call) {
        if (taskId.isEmpty()) return
        activeCalls[taskId]?.remove(call)
    }

    private fun backoffMillis(attempt: Int): Long {
        val factor = (1L shl (attempt - 1).coerceIn(0, 3)).coerceAtMost(RETRY_CAP)
        return (factor * retryBaseDelayMs).coerceAtMost(RETRY_CAP * retryBaseDelayMs)
    }

    /**
     * Sockets per download: capped at 16, and the 4-way multi-socket default
     * exists to bypass server-side single-socket 200KB/s throttling — but the
     * floor is 1, NOT 4. The engine deliberately forces a single socket for
     * hosts that reject multi-connection downloads (kissorgrab, dl.plutomovies);
     * a 4 floor silently overrode that policy and recreated the documented
     * fail→rescue→fail loop for those hosts.
     */
    fun socketsFor(url: String, configured: Int): Int {
        return configured.coerceIn(1, 16)
    }

    /** Outcome of a transfer: either a completed file or a failure with the server status when known. */
    sealed interface TurboResult {
        data class Success(val file: File, val bytes: Long, val segmented: Boolean) : TurboResult

        /** [htmlPage] is set when the probe proved the URL serves HTML, not a media file. */
        data class Failure(val httpStatus: Int?, val message: String, val htmlPage: Boolean = false) : TurboResult
    }

    /** Outcome of the pre-download probe. */
    internal sealed class ProbeResult {
        /** The URL answers with a downloadable file. */
        data class File(val total: Long, val acceptsRanges: Boolean) : ProbeResult()

        /** The URL answers with an HTML page (locker page, expired-token error page). */
        object HtmlPage : ProbeResult()

        /** The server could not be reached or answered without usable headers. */
        data class Unreachable(val total: Long) : ProbeResult()
    }

    private fun atomicMove(src: File, dest: File): Boolean {
        if (!src.exists()) return false
        try {
            // Android is Linux: rename(2) REPLACES an existing dest
            // atomically, so try the rename first. The old unconditional
            // dest.delete() before the rename opened a window where the
            // completed file was gone and nothing had taken its place yet —
            // a crash there destroyed the previous download for nothing.
            if (src.renameTo(dest)) return true
            if (dest.exists()) {
                if (!dest.delete()) return false
                if (src.renameTo(dest)) return true
            }
            // Cross-device or rename-refused: stream-copy, then remove src.
            src.inputStream().use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            src.delete()
            return true
        } catch (_: Exception) {
            return false
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
            } else if (!partFile.exists() || partFile.length() == 0L) {
                state.delete()
                if (single(safe, partFile, headers, total, failureStatus, failureMessage, onProgress, effectiveClient, taskId)) {
                    if (moveVerified(partFile, dest, total)) TurboResult.Success(dest, dest.length(), false)
                    else failure(failureStatus, failureMessage)
                } else failure(failureStatus, failureMessage)
            } else failure(failureStatus, failureMessage)
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
                if (prefix != null && prefix < partFile.length()) {
                    try {
                        RandomAccessFile(partFile, "rw").use { it.setLength(prefix) }
                    } catch (_: Throwable) {}
                }
            }
            state.delete()
            if (single(safe, partFile, headers, total, failureStatus, failureMessage, onProgress, effectiveClient, taskId)) {
                state.delete()
                if (moveVerified(partFile, dest, total)) TurboResult.Success(dest, dest.length(), false)
                else failure(failureStatus, failureMessage)
            } else failure(failureStatus, failureMessage)
        }
    }

    /**
     * Move the verified .part to its final name and PROVE the move landed.
     * [atomicMove]'s return value used to be ignored, so a failed rename
     * (and a copy fallback killed mid-way, e.g. by disk-full) still reported
     * Success — handing the engine a missing or truncated file that the
     * structure tier could bless (faststart MP4 keeps its moov atom at the
     * head, so a truncated file scans as "structured"). With the length
     * known (Content-Length from the probe), only a byte-exact dest counts;
     * with no known length, existence is all the evidence there is.
     */
    private fun moveVerified(partFile: File, dest: File, total: Long): Boolean {
        if (!atomicMove(partFile, dest)) return false
        if (!dest.exists()) return false
        return total <= 0L || dest.length() == total
    }

    /** Builds a Failure outcome, rethrowing cancellation so a paused job never looks like a server error. */
    private suspend fun failure(status: AtomicInteger, message: AtomicReference<String?>): TurboResult.Failure {
        coroutineContext.ensureActive()
        val code = status.get()
        return TurboResult.Failure(code.takeIf { it != 0 }, message.get() ?: "Download failed")
    }

    /**
     * Definitive range probe:
     * 1. Check HEAD. If Content-Length > 0 and Accept-Ranges is explicit, return (len, true).
     * 2. If Accept-Ranges is omitted on HEAD (common on CDNs), test Range: bytes=0-0.
     * 3. If server responds with HTTP 206 Partial Content, return (total, true).
     * Any step that proves the URL serves an HTML page (Content-Type text/html)
     * short-circuits to HtmlPage so the caller never downloads a locker page or
     * expired-token error page as a video file.
     */
    private fun probe(url: String, headers: Map<String, String>, client: OkHttpClient, onFailure: (Throwable) -> Unit = {}): ProbeResult {
        fun buildReq(head: Boolean) = Request.Builder().url(url).apply {
            header("User-Agent", headers["User-Agent"] ?: HttpClient.DEFAULT_UA)
            headers.forEach { (k, v) -> if (!k.equals("User-Agent", true)) header(k, v) }
            if (head) head() else header("Range", "bytes=0-0")
        }.build()

        fun isHtmlPage(contentType: String?): Boolean {
            val ct = contentType?.lowercase() ?: return false
            return ct.startsWith("text/html") || ct.startsWith("application/xhtml")
        }

        var totalLength = -1L

        // 1. Try HEAD request. Registered probe calls so pause/cancel kills
        // them too (v3.0.4: turborouter traffic outlived a cancel).
        try {
            HttpClient.executeRegistered(client.newCall(buildReq(true))).use { r ->
                if (isHtmlPage(r.header("Content-Type"))) return ProbeResult.HtmlPage
                val len = r.header("Content-Length")?.toLongOrNull() ?: -1L
                val ranges = r.header("Accept-Ranges")?.contains("bytes", true) == true
                if (r.isSuccessful && len > 0) {
                    totalLength = len
                    if (ranges) {
                        return ProbeResult.File(totalLength, true)
                    }
                }
            }
        } catch (e: Exception) {
            onFailure(e)
        }

        // 2. If Accept-Ranges was not explicit on HEAD, probe with Range: bytes=0-0
        try {
            HttpClient.executeRegistered(client.newCall(buildReq(false))).use { r ->
                if (isHtmlPage(r.header("Content-Type"))) return ProbeResult.HtmlPage
                val cr = r.header("Content-Range")
                val totalFromCr = cr?.substringAfter('/')?.trim()?.toLongOrNull() ?: -1L
                val is206 = r.code == 206
                if (is206) {
                    val finalTotal = if (totalFromCr > 0) totalFromCr else totalLength
                    return ProbeResult.File(finalTotal, true)
                } else if (r.isSuccessful && totalLength <= 0) {
                    val len = r.header("Content-Length")?.toLongOrNull() ?: -1L
                    if (len > 0) totalLength = len
                }
            }
        } catch (e: Exception) {
            onFailure(e)
        }

        return ProbeResult.Unreachable(totalLength)
    }

    private suspend fun segmented(
        url: String,
        dest: File,
        headers: Map<String, String>,
        total: Long,
        sockets: Int,
        state: TurboState,
        failureStatus: AtomicInteger,
        failureMessage: AtomicReference<String?>,
        onProgress: (Long, Long, Long) -> Unit,
        client: OkHttpClient,
        taskId: String = ""
    ): Boolean = coroutineScope {
        val plan = state.loadOrCreate(total, sockets)
        val initialBytes = plan.sumOf { (it.current - it.start).coerceAtLeast(0L) }
        val done = AtomicLong(initialBytes)
        val failed = AtomicBoolean(false)
        val nextPiece = AtomicInteger(0)
        // Rate-limit coordination: one shared cooldown deadline every socket
        // sits out (the limiter is per-IP — only a PAUSE heals it; retrying
        // now deepens it), a cycle counter driving the escalating ladder, and
        // a worker cap halved per cycle; the shared piece queue lets the
        // survivors drain everything the departed workers would have claimed.
        val throttleUntil = AtomicLong(0L)
        val throttleCycles = AtomicInteger(0)
        val maxWorkers = AtomicInteger(sockets)
        fun enterRateLimit() {
            val cycle = throttleCycles.incrementAndGet()
            val waitMs = (throttleBaseDelayMs shl (cycle - 1).coerceAtMost(5)).coerceAtMost(THROTTLE_MAX_MS)
            val until = System.currentTimeMillis() + waitMs
            throttleUntil.set(until)
            if (taskId.isNotEmpty()) throttleDeadlines[taskId] = until
            maxWorkers.set(maxOf(2, maxWorkers.get() / 2))
            com.anonrode.downloader.util.DebugLog.backend(
                "task=$taskId rate limited (cycle $cycle): all sockets cooling ${waitMs / 1000}s, workers→${maxWorkers.get()}"
            )
        }

        RandomAccessFile(dest, "rw").use { raf ->
            if (raf.length() != total) raf.setLength(total)
        }

        val speed = SpeedMeter(initialBytes)
        // Progress truth = bytes committed to the file, NOT bytes received.
        // `done` counts every socket's reads; with out-of-order pieces the
        // received total runs ahead of what the resume map holds — the Suits
        // download (activity-log-share-5) showed the bar pinned at 100% for
        // 2+ minutes while one slow socket still held the committed prefix
        // 60 MiB short. The engine's watchdog disk feed (computeDiskBytes)
        // reads exactly this sidecar sum, so reporting it here keeps the bar,
        // the watchdog and a future resume all on the same number. Speed
        // still samples `done`: displayed speed is the real transfer rate.
        fun committed(): Long = plan.sumOf { (it.current - it.start).coerceAtLeast(0L) }
        onProgress(initialBytes, total, 0L)

        // Decoupled Telemetry Dispatcher: Ticks every 250ms with smoothed EMA speed
        val telemetryTicker = launch(Dispatchers.Default) {
            while (isActive) {
                delay(250)
                val currentDone = done.get()
                val currentSpeed = speed.sample(currentDone)
                onProgress(committed(), total, currentSpeed)
            }
        }

        try {
            RandomAccessFile(dest, "rw").use { raf ->
                val channel: FileChannel = raf.channel

                // Shared piece queue (BitTorrent style): workers pull the next unfinished
                // piece, so a slow connection never holds up the download tail.
                fun claimNext(): Int? {
                    while (true) {
                        val idx = nextPiece.getAndIncrement()
                        if (idx >= plan.size) return null
                        if (plan[idx].current <= plan[idx].end) return idx
                    }
                }

                /**
                 * Download one piece. NETWORK failures follow the normal
                 * policy: 5 attempts with exponential backoff, each retry
                 * re-requesting from the piece's committed offset so a mid-body
                 * drop resumes instead of restarting. RATE-LIMIT answers
                 * (429/503) never consume an attempt — they park the whole
                 * socket pool in the shared cooldown (enterRateLimit) and the
                 * piece retries after the host calmed down. The global failure
                 * flag is only set once a piece exhausts attempts on REAL
                 * failures, so a single hiccup never aborts the siblings.
                 */
                suspend fun downloadPiece(chunk: TurboChunk): Boolean {
                    var attempt = 0
                    while (true) {
                        if (failed.get() || !coroutineContext.isActive) return false
                        // Sit out a live cooldown in ≤2s slices so pause/kill
                        // stays responsive; a cancellation here escapes as the
                        // CancellationException the worker handler commits on.
                        while (System.currentTimeMillis() < throttleUntil.get()) {
                            if (failed.get() || !coroutineContext.isActive) return false
                            delay((throttleUntil.get() - System.currentTimeMillis()).coerceIn(0L, 2_000L))
                        }
                        if (attempt >= MAX_ATTEMPTS) break
                        var completed = false
                        var rateLimited = false
                        try {
                            val req = Request.Builder().url(url).apply {
                                header("User-Agent", headers["User-Agent"] ?: HttpClient.DEFAULT_UA)
                                headers.forEach { (k, v) -> if (!k.equals("User-Agent", true)) header(k, v) }
                                header("Range", "bytes=${chunk.current}-${chunk.end}")
                            }.build()
                            val call = client.newCall(req)
                            trackCall(taskId, call)
                            try {
                                call.execute().use { res ->
                                if (res.code == 206) {
                                    val src = res.body?.source() ?: throw IOException("Empty response body from server")
                                    val buf = ByteArray(BUFFER)
                                    var pos = chunk.current
                                    var bytesSinceLastCommit = 0L
                                    while (pos <= chunk.end && !failed.get()) {
                                        if (!coroutineContext.isActive) {
                                            state.commit(plan, total, force = true)
                                            return@use
                                        }
                                        val want = minOf(buf.size.toLong(), chunk.end - pos + 1).toInt()
                                        val n = src.read(buf, 0, want)
                                        if (n == -1) break

                                        synchronized(channel) {
                                            // FileChannel.write MAY write fewer bytes than
                                            // requested (legal per contract). Ignoring the
                                            // return value left permanent zero holes that the
                                            // sidecar still reported as downloaded, so the
                                            // piece was never retried and the run could end
                                            // "Success" over a corrupt file (engine-audit
                                            // P2). Drain the buffer; treat a stall as a real
                                            // failure so the retry policy owns it.
                                            val bb = ByteBuffer.wrap(buf, 0, n)
                                            var w = 0
                                            while (bb.hasRemaining()) {
                                                val k = channel.write(bb, pos + w)
                                                if (k <= 0) throw java.io.IOException(
                                                    "short positional write at ${pos + w}"
                                                )
                                                w += k
                                            }
                                        }

                                        pos += n
                                        chunk.current = pos
                                        done.addAndGet(n.toLong())

                                        bytesSinceLastCommit += n
                                        if (bytesSinceLastCommit >= 2 * 1024 * 1024L || pos > chunk.end) {
                                            state.commit(plan, total, force = false)
                                            bytesSinceLastCommit = 0L
                                        }
                                    }
                                    state.commit(plan, total, force = true)
                                    completed = pos > chunk.end
                                } else {
                                    failureStatus.compareAndSet(0, res.code)
                                    if (res.code == 429 || res.code == 503) rateLimited = true
                                }
                                }
                                } finally {
                                    untrackCall(taskId, call)
                                }
                            if (completed) return true
                            if (rateLimited) {
                                // The server told us to come back later: come
                                // back later. This is NOT one of the piece's
                                // 5 tries — counting 429s against the budget
                                // is what turned a 26 MB/s healthy stream into
                                // a corpse in share-5.
                                enterRateLimit()
                                continue
                            }
                            attempt++
                            if (failed.get() || !coroutineContext.isActive) return false
                            failureMessage.compareAndSet(
                                null,
                                "Piece ${chunk.start}-${chunk.end} incomplete (attempt $attempt of $MAX_ATTEMPTS)"
                            )
                        } catch (_: CancellationException) {
                            state.commit(plan, total, force = true)
                            return false
                        } catch (e: Exception) {
                            attempt++
                            failureMessage.compareAndSet(null, e.message ?: e.javaClass.simpleName)
                            // Persist the mid-piece position so a pause after this
                            // failure resumes from here instead of the piece start.
                            state.commit(plan, total, force = true)
                        }
                        if (attempt < MAX_ATTEMPTS) delay(backoffMillis(attempt))
                    }
                    failed.set(true)
                    return false
                }

                coroutineScope {
                    repeat(minOf(sockets, plan.size)) { i ->
                        launch {
                            try {
                                while (isActive && !failed.get()) {
                                    // Concurrency downshift: after a rate-limit
                                    // cycle only the first maxWorkers sockets
                                    // keep claiming; the shared queue routes the
                                    // rest of the pieces to the survivors.
                                    if (i >= maxWorkers.get()) break
                                    val idx = claimNext() ?: break
                                    if (!downloadPiece(plan[idx])) break
                                }
                            } catch (_: CancellationException) {
                                state.commit(plan, total, force = true)
                            }
                        }
                    }
                }
                channel.force(true)
            }
        } finally {
            telemetryTicker.cancel()
            onProgress(committed(), total, speed.getSpeed())
            if (taskId.isNotEmpty()) throttleDeadlines.remove(taskId)
        }

        if (failed.get()) return@coroutineScope false
        if (plan.any { it.current <= it.end }) return@coroutineScope false
        return@coroutineScope dest.length() == total
    }

    private suspend fun single(
        url: String,
        dest: File,
        headers: Map<String, String>,
        total: Long,
        failureStatus: AtomicInteger,
        failureMessage: AtomicReference<String?>,
        onProgress: (Long, Long, Long) -> Unit,
        client: OkHttpClient,
        taskId: String = ""
    ): Boolean = coroutineScope {
        val speed = SpeedMeter(0L)
        onProgress(0L, if (total > 0) total else 0L, 0L)

        // Decoupled Telemetry Dispatcher: Ticks every 250ms
        val telemetryTicker = launch(Dispatchers.Default) {
            while (isActive) {
                delay(250)
                val currentDone = if (dest.exists()) dest.length() else 0L
                val currentSpeed = speed.sample(currentDone)
                onProgress(currentDone, if (total > 0) total else 0L, currentSpeed)
            }
        }

        var success = false
        var attemptCount = 0
        // Rate-limit state for the single-stream path — same ladder as the
        // segmented worker pool, no cross-worker sharing needed (one socket).
        var lastRateLimited = false
        var throttleUntil = 0L
        var throttleCycles = 0
        try {
            suspend fun attempt(): Boolean {
                lastRateLimited = false
                try {
                    val resumeAt = if (dest.exists()) dest.length() else 0L
                    val req = Request.Builder().url(url).apply {
                        header("User-Agent", headers["User-Agent"] ?: HttpClient.DEFAULT_UA)
                        headers.forEach { (k, v) -> if (!k.equals("User-Agent", true)) header(k, v) }
                        if (resumeAt > 0) header("Range", "bytes=$resumeAt-")
                    }.build()

                    val call = client.newCall(req)
                    trackCall(taskId, call)
                    try {
                        call.execute().use { res ->
                            if (!res.isSuccessful) {
                                failureStatus.compareAndSet(0, res.code)
                                lastRateLimited = res.code == 429 || res.code == 503
                                return false
                            }
                            val resuming = res.code == 206
                            val src = res.body?.source() ?: run {
                                failureMessage.compareAndSet(null, "Empty response body from server")
                                return false
                            }
                            val startAt = if (resuming) resumeAt else 0L
                            RandomAccessFile(dest, "rw").use { raf ->
                                if (raf.length() < startAt) raf.setLength(startAt)
                                raf.seek(startAt)
                                val buf = ByteArray(BUFFER)
                                var written = startAt
                                while (true) {
                                    if (!coroutineContext.isActive) return false
                                    val n = src.read(buf)
                                    if (n == -1) break
                                    raf.write(buf, 0, n)
                                    written += n
                                }
                                if (total > 0 && written != total) return false
                            }
                        }
                    } finally {
                        untrackCall(taskId, call)
                    }
                    return true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // One attempt failing (disconnect, timeout) must not abort the
                    // retry loop: record the cause and let the next attempt resume.
                    failureMessage.compareAndSet(null, e.message ?: e.javaClass.simpleName)
                    return false
                }
            }

            while (!success) {
                // Sit out a live cooldown in ≤2s slices; a pause surfaces as
                // the CancellationException caught below (a paused job never
                // looks like a failure).
                while (System.currentTimeMillis() < throttleUntil) {
                    delay((throttleUntil - System.currentTimeMillis()).coerceIn(0L, 2_000L))
                }
                if (attemptCount >= MAX_ATTEMPTS) break
                if (attempt()) {
                    success = true
                } else if (lastRateLimited) {
                    // "Too Many Requests" is a WAIT instruction, not a failed
                    // attempt: the next request resumes from dest.length() via
                    // the Range header, after an escalating pause.
                    throttleCycles++
                    val waitMs = (throttleBaseDelayMs shl (throttleCycles - 1).coerceAtMost(5))
                        .coerceAtMost(THROTTLE_MAX_MS)
                    throttleUntil = System.currentTimeMillis() + waitMs
                    if (taskId.isNotEmpty()) throttleDeadlines[taskId] = throttleUntil
                    com.anonrode.downloader.util.DebugLog.backend(
                        "task=$taskId single stream rate limited (cycle $throttleCycles): cooling ${waitMs / 1000}s"
                    )
                } else {
                    attemptCount++
                    if (attemptCount < MAX_ATTEMPTS) delay(backoffMillis(attemptCount))
                }
            }
        } catch (_: CancellationException) {
            // A paused job never surfaces as a failure.
        } catch (e: Exception) {
            failureMessage.compareAndSet(null, e.message ?: e.javaClass.simpleName)
        } finally {
            telemetryTicker.cancel()
            onProgress(if (dest.exists()) dest.length() else 0L, if (total > 0) total else 0L, speed.getSpeed())
            if (taskId.isNotEmpty()) throttleDeadlines.remove(taskId)
        }
        return@coroutineScope success
    }
}

/** One segment's byte window plus how far it has been committed to disk. */
class TurboChunk(val start: Long, val end: Long, @Volatile var current: Long)

/**
 * Thread-safe aggregate speed meter utilizing Exponential Moving Average (EMA).
 *
 * Samples at a 250ms cadence. The first ~1 second uses a heavy alpha (fast
 * acquisition) so the readout tracks the real transfer ramp: TCP slow start
 * and fresh sockets make the first windows genuinely slow, and a light EMA
 * from sample one made the displayed speed chase the true speed for ~2.5s —
 * user-visible as "100KB → 400KB → 3MB" long after the transfer was already
 * at full speed. After acquisition it settles to alpha = 0.25 to smooth
 * TCP burst jitter.
 */
class SpeedMeter(initialBytes: Long = 0L) {
    private var lastBytes = initialBytes
    private var lastTime = System.currentTimeMillis()
    private var emaSpeed = 0.0
    private var initialized = false
    private var sampleCount = 0

    @Synchronized
    fun sample(totalBytes: Long): Long {
        val now = System.currentTimeMillis()
        val dt = now - lastTime
        if (dt >= 250) {
            val delta = (totalBytes - lastBytes).coerceAtLeast(0L)
            val instantBps = (delta * 1000.0) / dt
            sampleCount++
            if (!initialized) {
                emaSpeed = instantBps
                initialized = true
            } else {
                val alpha = if (sampleCount <= FAST_ACQUIRE_SAMPLES) 0.6 else 0.25
                emaSpeed = (alpha * instantBps) + ((1.0 - alpha) * emaSpeed)
            }
            lastBytes = totalBytes
            lastTime = now
        }
        return emaSpeed.toLong().coerceAtLeast(0L)
    }

    @Synchronized
    fun getSpeed(): Long = emaSpeed.toLong().coerceAtLeast(0L)

    private companion object {
        /** Samples 2..5 (roughly the first second) run in fast-acquire mode. */
        const val FAST_ACQUIRE_SAMPLES = 5
    }
}
