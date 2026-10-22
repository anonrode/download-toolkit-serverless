package com.anonrode.downloader.engine

import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.data.net.isTlsChainFailure
import com.anonrode.downloader.pipeline.HostPolicyRegistry
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
 * 1. Definitive Range verification (probe with fallback bytes=0-1024 GET).
 * 2. Decoupled telemetry ticker (250ms cadence) with EMA speed smoothing.
 * 3. Non-blocking parallel worker streams writing to pre-allocated FileChannels.
 * 4. Throttled sidecar resume state commits.
 * 5. BitTorrent-style shared piece queue with per-piece retry and exponential
 *    backoff: a flaky connection costs one piece, not the whole download.
 */
object TurboDownloader {

    private const val BUFFER = 256 * 1024 // 256 KB read buffer (okio returns <=8 KiB per read())
    private const val MIN_SEGMENTED_SIZE = 2L * 1024 * 1024 // 2 MB — small files get parallel lanes too
    private const val MAX_ATTEMPTS = 5 // retries per piece / per single-stream attempt

    // Drop-and-replace (1DM behavior): a lane that lags its siblings after
    // warm-up gets replaced with a FRESH connection. NEVER consumes attempts.
    //
    // Field retune (2026-10-19): on a shaped mobile link, 4s/4s windows with a
    // 40 KiB/s floor and a 4-drop budget completed every lane's budget inside
    // ~7s (28-38 handshakes/task), which is itself the request storm that
    // provokes CDN 403s. 8s windows and 15s warm-up make handshakes amortise;
    // the 14 KiB/s floor only catches lanes that are genuinely stuck; two
    // drops per piece bound the damage. The shared best reference now decays
    // (30s half-life) so one early 1 MB/s burst cannot mark every later
    // 20-40 KiB/s window as "severely lagging" for the rest of the download.
    private const val DROP_WINDOW_MS = 8_000L
    private const val DROP_WARMUP_MS = 15_000L
    private const val DROP_FLOOR_BPS = 14L * 1024
    private const val MAX_DROPS_PER_PIECE = 2
    private const val DROP_STRIKE_LIMIT = 2
    private const val DROP_RELATIVE_FRACTION = 0.3
    private const val DROP_RETRY_DELAY_MS = 250L
    // Severe relative lag: when a fast sibling field exists (>= 512 KiB/s),
    // even a lane ABOVE the absolute floor is dropped if it runs below 12% of
    // the best window (the 55KB/s-among-1.5MB/s case the floor alone missed).
    private const val SEVERE_LAG_MIN_BEST_BPS = 512L * 1024
    private const val SEVERE_LAG_FRACTION = 0.12

    // Warm standby (board row 5, 2026-10-21): one PRE-DIALED spare connection
    // per active task so a dropped/throttled lane is replaced by a socket that
    // already completed TCP+TLS, hiding the handshake that used to show as a
    // visible throughput dip. The spare is idle until promoted; active lanes
    // plus the spare never exceed min(per-host cap, lane grant).
    private const val STANDBY_KEEPALIVE_MS = 1_000L
    private const val STANDBY_RETRY_MS = 5_000L
    private const val STANDBY_POOL_KEEPALIVE_SECONDS = 30L

    // Adaptive per-host occupancy (board rows 5/29): aggregate goodput is
    // sampled at this cadence and fed to HostPolicyRegistry's per-host
    // controller (fast halve on 429/403/503, +1 lane only after two
    // consecutive improving samples).
    private const val ADAPT_SAMPLE_MS = 4_000L

    /** Decaying shared best-window reference (30s half-life). */
    private const val BEST_HALF_LIFE_MS = 30_000L

    internal data class BestSample(val bps: Long, val atMs: Long)

    /**
     * Pure drop-decision helpers, extracted so JVM tests can exercise the
     * retuned policy without sockets. Mirrors the inline logic in
     * [segmented] byte-for-byte: absolute floor AND relative lag, a warm-up
     * gate, and a hard per-piece budget.
     */
    internal object DropPolicy {
        fun decayedBestBps(sample: BestSample, nowMs: Long): Double {
            if (sample.bps <= 0L) return 0.0
            val ageMs = (nowMs - sample.atMs).coerceAtLeast(0L)
            return sample.bps * Math.pow(0.5, ageMs.toDouble() / BEST_HALF_LIFE_MS)
        }

        fun observeBest(
            ref: AtomicReference<BestSample>,
            bps: Double,
            nowMs: Long
        ) {
            if (bps <= 0.0) return
            while (true) {
                val cur = ref.get()
                if (bps <= decayedBestBps(cur, nowMs)) return
                if (ref.compareAndSet(cur, BestSample(bps.toLong(), nowMs))) return
            }
        }

        fun isSlowAbsolute(bps: Double, bestBps: Double): Boolean =
            bps < DROP_FLOOR_BPS && bps < bestBps * DROP_RELATIVE_FRACTION

        fun isSeverelyLagging(bps: Double, bestBps: Double): Boolean =
            bestBps >= SEVERE_LAG_MIN_BEST_BPS && bps < bestBps * SEVERE_LAG_FRACTION

        fun warmupElapsed(connAgeMs: Long): Boolean = connAgeMs > DROP_WARMUP_MS

        fun budgetAllows(drops: Int, noMoreDrops: Boolean): Boolean =
            !noMoreDrops && drops < MAX_DROPS_PER_PIECE
    }

    /**
     * Pure standby accounting (board row 5). The runtime pre-dial is in
     * [StandbySocket]; these helpers keep the CAP INVARIANT testable:
     * active worker lanes + idle standbys <= min(per-host cap, lane grant),
     * with at least one lane whenever the task is admitted.
     */
    internal object StandbyPolicy {
        const val MAX_STANDBY_PER_TASK = 1

        /** Connections the task may hold in total. */
        fun capacity(hostCap: Int, laneGrant: Int): Int =
            minOf(hostCap, laneGrant).coerceAtLeast(1)

        /** The task can afford to park one idle connection only when the
         *  capacity leaves at least one lane doing work. */
        fun standbyAllowed(hostCap: Int, laneGrant: Int): Boolean =
            capacity(hostCap, laneGrant) > MAX_STANDBY_PER_TASK

        /** ACTIVE worker lanes after reserving the standby slot. */
        fun activeWorkerBudget(hostCap: Int, laneGrant: Int, standbyActive: Boolean): Int =
            activeWorkerBudgetForTotal(capacity(hostCap, laneGrant), standbyActive)

        /** ACTIVE worker lanes for an already-computed TOTAL connection budget
         *  (host cap AND lane grant AND adaptive occupancy all applied). */
        fun activeWorkerBudgetForTotal(totalConnections: Int, standbyActive: Boolean): Int {
            val total = totalConnections.coerceAtLeast(1)
            return if (standbyActive) (total - MAX_STANDBY_PER_TASK).coerceAtLeast(1) else total
        }

        /** Range a promoted connection may request: it repairs exactly the
         *  not-yet-committed tail of the abandoned piece. Null means the piece
         *  is already complete (promotion must not re-request anything). */
        fun promotionRange(current: Long, end: Long): LongRange? =
            if (current > end) null else current..end
    }

    // Read coalescing (directive Fix 5): Okio returns at most one 8 KiB segment
    // per read(), so a naive loop causes one pwrite64 syscall + atomic update
    // per 8 KiB (131k syscalls/GiB). Coalesce up to 64 KiB per write, stopping
    // early on a short read so we never block for network when data is ready.
    private const val COALESCE_TARGET_BYTES = 64 * 1024
    private const val OKIO_SEGMENT_SIZE = 8192

    // Adaptive socket count (2026-10-21, board rows 5/29) supersedes the old
    // 4→16 startup ramp: a task starts at min(host cap, lane grant, per-host
    // session occupancy) and HostPolicyRegistry.HostSocketOccupancy moves that
    // occupancy with fast halving on 429/403/503 and +1-per-two-improving-
    // samples recovery.

    // Work-stealing (directive Fix 3, corrected design D2a/D2b): when all
    // initial pieces are claimed, a free worker splits the ACTIVE piece with
    // the largest remaining range. Threshold 512 KiB (not 2 MiB: pieces here
    // are 1 MiB, so a 2 MiB threshold could never fire); the cap counts
    // SPLITS (not total pieces: initial plans hold up to 2048 pieces).
    // Validated in scratch/test_work_steal.py before this port.
    private const val STEAL_MIN_REMAINING_BYTES = 512L * 1024
    private const val MAX_DYNAMIC_SPLITS = 32
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

    /** Test/observability hook: drop-and-replace events per task id. */
    internal val dropEventCounts = ConcurrentHashMap<String, AtomicInteger>()

    /**
     * Interrupt every in-flight transfer of a task. The affected pieces fail,
     * are retried per the normal piece policy, and the task eventually FAILED
     * instead of hanging in DOWNLOADING forever. Idempotent for unknown ids.
     */
    fun cancelTask(taskId: String, isFullCancel: Boolean = false) {
        if (isFullCancel) fullyCancelledTaskIds.add(taskId)
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
        // cancelTask() may have removed the map entry between getOrPut and add:
        // its snapshot then missed this call — cancel it here instead.
        if (!activeCalls.containsKey(taskId)) {
            call.cancel()
        }
    }

    private fun canCommit(taskId: String): Boolean =
        taskId.isEmpty() || !fullyCancelledTaskIds.contains(taskId)

    private fun untrackCall(taskId: String, call: Call) {
        if (taskId.isEmpty()) return
        activeCalls[taskId]?.remove(call)
    }

    /** One shared zero-idle pool for every post-drop client (directive Fix 4:
     *  a fresh pool object per drop was wasteful; sharing keeps sockets from
     *  ever being reused while creating no per-drop garbage). */
    private val zeroIdlePool =
        okhttp3.ConnectionPool(0, 1, java.util.concurrent.TimeUnit.NANOSECONDS)

    /** Client for post-drop attempts: zero-idle pool so a fresh connection is
     *  actually fresh (derived from the passed client to keep any permissive
     *  TLS choice the caller made). */
    private fun freshConnectionClient(base: OkHttpClient): OkHttpClient =
        base.newBuilder().connectionPool(zeroIdlePool).build()

    /**
     * One pre-dialed replacement socket per task (board row 5).
     *
     * A tiny ranged GET (`bytes=0-0`, the probe's own shape) opens and then
     * RETURNS a keep-alive connection to this instance's private pool. When a
     * slow lane is dropped, [promote] hands the pooled client to the piece's
     * next attempt: the request skips TCP+TLS (the measured dip after a
     * drop-and-replace), and `Connection: close` on the retry makes it
     * one-shot. The keeper then pre-dials the NEXT spare, but only after
     * [releasePromoted] — so the live promoted socket and a fresh handshake
     * are never counted on top of the full worker budget.
     *
     * Accounting invariant: at most one idle standby exists per task, and it
     * only exists while [StandbyPolicy.activeWorkerBudget] has already
     * subtracted its slot from the worker lanes.
     */
    internal class StandbySocket(
        private val url: String,
        private val headers: Map<String, String>,
        baseClient: OkHttpClient,
        private val taskId: String,
        private val track: (okhttp3.Call) -> Unit = {},
        private val untrack: (okhttp3.Call) -> Unit = {}
    ) {
        private val pool = okhttp3.ConnectionPool(
            StandbyPolicy.MAX_STANDBY_PER_TASK,
            STANDBY_POOL_KEEPALIVE_SECONDS,
            java.util.concurrent.TimeUnit.SECONDS
        )
        private val client = baseClient.newBuilder().connectionPool(pool).build()
        private val ready = AtomicBoolean(false)
        private val preparing = AtomicBoolean(false)
        private val promotedInUse = AtomicBoolean(false)

        val isReady: Boolean get() = ready.get()

        /** A new dial is safe only when no spare is ready AND the previously
         *  promoted socket has been fully released. */
        fun canPrepare(): Boolean = !ready.get() && !promotedInUse.get()

        /**
         * Pre-dial one keep-alive connection. Returns true when a pooled
         * connection is ready to promote. Blocking; call on Dispatchers.IO.
         */
        fun prepare(): Boolean {
            if (!canPrepare() || !preparing.compareAndSet(false, true)) return ready.get()
            try {
                val req = Request.Builder().url(url).apply {
                    header("User-Agent", headers["User-Agent"] ?: HttpClient.DEFAULT_UA)
                    headers.forEach { (k, v) -> if (!k.equals("User-Agent", true)) header(k, v) }
                    header("Range", "bytes=0-0")
                    header("Accept-Encoding", "identity")
                }.build()
                val call = client.newCall(req)
                track(call)
                try {
                    call.execute().use { res ->
                        val body = res.body
                        // Only a complete 1-byte 206 leaves the socket
                        // reusable; anything else (403, 200 full body, HTML)
                        // closes it and the keeper backs off.
                        if (res.code == 206 && body != null && body.contentLength() == 1L) {
                            body.source().readByteArray(1)
                            ready.set(true)
                        }
                    }
                } finally {
                    untrack(call)
                }
            } catch (_: Exception) {
                ready.set(false)
            } finally {
                preparing.set(false)
            }
            return ready.get()
        }

        /** Takes the pre-dialed connection for one piece attempt, or null
         *  when no spare is ready (caller falls back to a fresh dial). */
        fun promote(): OkHttpClient? {
            if (!ready.compareAndSet(true, false)) return null
            promotedInUse.set(true)
            return client
        }

        /** Marks the promoted attempt finished so the keeper may re-dial. */
        fun releasePromoted() {
            promotedInUse.set(false)
        }
    }

    /** Coalesce Okio's <=8 KiB segment reads into one up-to-64 KiB write.
     *  Stops early on a short segment read (socket buffer drained) so the
     *  caller never blocks waiting for packets when bytes are ready. */
    private fun readCoalesced(src: okio.BufferedSource, buf: ByteArray, maxWant: Int): Int {
        val target = minOf(maxWant, COALESCE_TARGET_BYTES)
        var totalRead = 0
        while (totalRead < target) {
            val r = src.read(buf, totalRead, maxWant - totalRead)
            if (r == -1) break
            totalRead += r
            if (r < OKIO_SEGMENT_SIZE) break
        }
        return if (totalRead == 0) -1 else totalRead
    }

    private fun backoffMillis(attempt: Int): Long {
        val factor = (1L shl (attempt - 1).coerceIn(0, 3)).coerceAtMost(RETRY_CAP)
        return (factor * retryBaseDelayMs).coerceAtMost(RETRY_CAP * retryBaseDelayMs)
    }

    /**
     * Sockets per download: clamped 1..16 (floor 1, ceiling 16) and capped per
     * host by HostPolicyRegistry — built-ins start wide (6 on kissorgrab /
     * dl.plutomovies, 12 on the wetafiles/downloadwella/loadedfiles lockers —
     * Fix 7) and are OTA-overridable via contentPolicy.socketCaps. Speed
     * policy: start fast; let the 429 ladder, drop-and-replace, and
     * work-stealing adapt per connection.
     */
    fun socketsFor(url: String, configured: Int): Int =
        HostPolicyRegistry.effectiveSocketsForUrl(url, configured)

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

        /** Terminal server verdict (directive Fix 2): the engine must hear
         *  429/401/403/404/410 immediately instead of watching single() burn
         *  five attempts on a dead or rate-limited link. */
        data class HttpError(val code: Int, val message: String) : ProbeResult()

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
        // Clear any stale cancellation flag from a prior run of this taskId
        if (taskId.isNotBlank()) {
            fullyCancelledTaskIds.remove(taskId)
            dropEventCounts.remove(taskId)
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
                is ProbeResult.HttpError -> {
                    // Terminal verdict: hand the code to the engine immediately
                    // (its self-heal/park logic keys on 401/403/404/410/429).
                    return@withContext TurboResult.Failure(
                        httpStatus = probe.code,
                        message = probe.message,
                        htmlPage = false
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
                // Defense in depth (audit finding 1): a .turbo without its .part
                // means the plan's offsets describe bytes that are no longer in
                // this .part — a sidecar-driven resume would skip those ranges
                // and zero-fill them. A stale plan can never be trusted here;
                // delete it and start a fresh plan (correctness over redo).
                if (!partFile.exists() && turboSidecar.exists()) {
                    TurboState(turboSidecar).delete()
                }
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
                    val canFallbackToSingle = canCommit(taskId) && coroutineContext.isActive &&
                        (!partFile.exists() || partFile.length() == 0L || landedBytes == 0L)
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
     * Single-round-trip fast probe (1DM): the first request is a ranged GET
     * (bytes=0-1023 + Accept-Encoding: identity) returning status, total size,
     * range support AND the first bytes for a best-effort media sniff in ONE
     * round trip. Header verdicts decide first; HEAD remains a fallback for
     * non-206/200 answers (e.g. a rate-limited range server whose HEAD still
     * answers 200 + Accept-Ranges). Decision table validated in
     * scratch/test_probe_logic.py before this port.
     */
    private fun probe(url: String, headers: Map<String, String>, client: OkHttpClient, onFailure: (Throwable) -> Unit = {}): ProbeResult {
        fun buildGet() = Request.Builder().url(url).apply {
            header("User-Agent", headers["User-Agent"] ?: HttpClient.DEFAULT_UA)
            headers.forEach { (k, v) -> if (!k.equals("User-Agent", true)) header(k, v) }
            header("Range", "bytes=0-1023")
            header("Accept-Encoding", "identity")
        }.build()

        fun buildHead() = Request.Builder().url(url).apply {
            header("User-Agent", headers["User-Agent"] ?: HttpClient.DEFAULT_UA)
            headers.forEach { (k, v) -> if (!k.equals("User-Agent", true)) header(k, v) }
            head()
        }.build()

        fun isHtmlPage(contentType: String?): Boolean {
            val ct = contentType?.lowercase() ?: return false
            return ct.startsWith("text/html") || ct.startsWith("application/xhtml")
        }

        fun sniffHead(r: okhttp3.Response): Boolean {
            val head = try {
                r.peekBody(1024).bytes()
            } catch (_: Exception) {
                null // mid-body disconnect: never invalidates a header verdict
            }
            return head != null && head.isNotEmpty() &&
                com.anonrode.downloader.pipeline.StreamValidator.sniff(head) != null
        }

        fun headFallback(carried: Long): ProbeResult {
            var total = carried
            try {
                // Registered so pause/cancel kills the probe too.
                HttpClient.executeRegistered(client.newCall(buildHead())).use { r ->
                    val ct = r.header("Content-Type")
                    val len = r.header("Content-Length")?.toLongOrNull() ?: -1L
                    val ranges = r.header("Accept-Ranges")?.contains("bytes", true) == true
                    if (r.isSuccessful && len > 0 && !isHtmlPage(ct)) {
                        total = len
                        if (ranges) return ProbeResult.File(total, true)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                onFailure(e)
            }
            return ProbeResult.Unreachable(total)
        }

        // 1. Ranged GET first — one round trip for status + total + sniff.
        var getStatus: Int? = null
        try {
            HttpClient.executeRegistered(client.newCall(buildGet())).use { r ->
                getStatus = r.code
                val ct = r.header("Content-Type")
                if (isHtmlPage(ct)) return ProbeResult.HtmlPage

                val cr = r.header("Content-Range")
                val totalFromCr = cr?.substringAfter('/')?.trim()?.toLongOrNull() ?: -1L
                val lenHeader = r.header("Content-Length")?.toLongOrNull() ?: -1L

                if (r.code == 206 && totalFromCr > 0) {
                    // Header-proven range support: sniff is best-effort AFTER the
                    // verdict — a body read failure must not discard it
                    // (TurboDownloaderTest.givesUpAfterMaxAttempts).
                    if (sniffHead(r)) return ProbeResult.HtmlPage
                    return ProbeResult.File(totalFromCr, true)
                }
                if (r.code == 200 && lenHeader > 0) {
                    if (sniffHead(r)) return ProbeResult.HtmlPage
                    return ProbeResult.File(lenHeader, false)
                }
                // Deliberately NO carry of Content-Length/Content-Range from
                // error or unusable responses (audit finding 2): an error
                // page's byte length must never become the tracked total.
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            onFailure(e)
        }

        // 2. HEAD fallback for non-206/200 answers (416/429/403/...) and for
        // responses that did not advertise a usable length — ONE try only.
        // Directive Fix 2, corrected: a 429 whose HEAD still answers 200 +
        // Accept-Ranges keeps the segmented path alive (that is the
        // RateLimitedRangeServer storm-survival constraint); only when HEAD is
        // also unusable does the probe fast-fail.
        val hb = headFallback(-1L)
        if (hb is ProbeResult.File) return hb
        val code = getStatus
        if (code != null) {
            if (code == 429) {
                com.anonrode.downloader.pipeline.HostHealth.recordFail(
                    url, rateLimited = true, reason = "HTTP 429 in Turbo probe"
                )
                return ProbeResult.HttpError(429, "Server rate limited request (HTTP 429)")
            }
            if (code in listOf(401, 403, 404, 410)) {
                return ProbeResult.HttpError(code, "Server rejected link (HTTP $code)")
            }
            // 5xx deliberately falls through to Unreachable: the full
            // 5-attempt retry ladder must stay reachable
            // (TurboDownloaderTest.givesUpAfterMaxAttempts).
        }
        return hb
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
            // Work-stealing appends split pieces mid-run (Fix 3). CopyOnWrite
            // makes every append atomically visible to concurrent commits and
            // telemetry without wrapping a dozen call sites in synchronized —
            // the steal DECISION itself still runs under synchronized(plan)
            // so two workers can never split the same victim.
            val plan = CopyOnWriteArrayList(state.loadOrCreate(total, sockets))
        // Post-admission growth ceiling: the controller may raise this task's
        // grant, but never past the per-host policy.
        val hostCeiling = HostPolicyRegistry.effectiveSocketsForUrl(url, 16)
        val initialBytes = plan.sumOf { (it.current - it.start).coerceAtLeast(0L) }
        val done = AtomicLong(initialBytes)
            val failed = AtomicBoolean(false)
            val nextPiece = AtomicInteger(0)
            var dynamicSplits = 0  // work-steal splits this run (Fix 3 budget)
            // Best window throughput seen by ANY lane this run — the relative
            // drop guard: a lane is dropped only when slow BOTH absolutely
            // (< DROP_FLOOR_BPS) and relatively (< 30% of the best sibling
            // window), so a uniformly-slow link (live-verified at ~6-9 KB/s
            // total on the maintainer connection) never churns lanes and never
            // wastes multi-second handshakes. Shared-best scenarios validated
            // in scratch/test_drop_replace.py.
            val bestWindowBps = AtomicReference(BestSample(0L, 0L))
        // Rate-limit coordination: one shared cooldown deadline every socket
        // sits out (the limiter is per-IP — only a PAUSE heals it; retrying
        // now deepens it), a cycle counter driving the escalating ladder, and
        // a worker cap halved per cycle; the shared piece queue lets the
        // survivors drain everything the departed workers would have claimed.
        val throttleUntil = AtomicLong(0L)
        val throttleCycles = AtomicInteger(0)
        // Startup ramp state (fresh tasks only). `sockets` is the admission
        // grant; the ramp may grow to the per-host ceiling as the global
        // controller rebalances (growth is consumed at piece boundaries).
        fun laneGrant(): Int {
            if (taskId.isBlank()) return sockets
            val grant = HostPolicyRegistry.LaneBudget.grantFor(taskId)
            return if (grant > 0) minOf(grant, hostCeiling) else sockets
        }
        val freshStart = initialBytes == 0L
        // Warm standby (board row 5): one pre-dialed spare per active task.
        // Its slot is subtracted BEFORE any lane starts, so active lanes +
        // the idle spare can never exceed min(per-host cap, lane grant).
        // A host whose session occupancy is down to one socket gets no spare.
        val initialOccupancy = HostPolicyRegistry.HostSocketOccupancy.currentForUrl(url, hostCeiling)
        val standbyEnabled = StandbyPolicy.standbyAllowed(
            hostCeiling,
            minOf(laneGrant(), initialOccupancy)
        )
        // Adaptive occupancy (B2): the host's session memory is the THIRD cap
        // after the static host policy and the global lane grant. It starts at
        // the host cap and is lowered/raised by HostSocketOccupancy; reading it
        // here keeps every claim site on the live value.
        fun workerCeiling(): Int {
            val occupancy = HostPolicyRegistry.HostSocketOccupancy
                .currentForUrl(url, hostCeiling)
            val total = minOf(hostCeiling, laneGrant(), occupancy)
            return StandbyPolicy.activeWorkerBudgetForTotal(total, standbyEnabled)
        }
        val workerBudget = workerCeiling()
        val standby = if (standbyEnabled) {
            StandbySocket(
                url = url,
                headers = headers,
                baseClient = client,
                taskId = taskId,
                track = { call -> trackCall(taskId, call) },
                untrack = { call -> untrackCall(taskId, call) }
            )
        } else null
        val maxWorkers = AtomicInteger(workerBudget)
        // Speed-first startup (B2): open the task at its allowed width (host
        // cap bounded by the lane grant and adaptive occupancy) instead of
        // ramping up from 4. The expected-default case only enters this branch
        // when the grant itself is narrow.
        val rampStart = minOf(sockets, workerBudget)
        val activeWorkers = AtomicInteger(rampStart)
        val rampStartedAt = System.currentTimeMillis()
        val sawFirstByte = AtomicBoolean(false)
        val sawFirstMegabyte = AtomicBoolean(false)
        if (freshStart && taskId.isNotEmpty()) {
            com.anonrode.downloader.util.DebugLog.backend(
                "task=$taskId lane start=$rampStart cap=$hostCeiling " +
                    "occupancy=$initialOccupancy standby=${if (standbyEnabled) 1 else 0} total=0KB/s"
            )
        }
        fun enterRateLimit() {
            val cycle = throttleCycles.incrementAndGet()
            val waitMs = (throttleBaseDelayMs shl (cycle - 1).coerceAtMost(5)).coerceAtMost(THROTTLE_MAX_MS)
            val until = System.currentTimeMillis() + waitMs
            throttleUntil.set(until)
            if (taskId.isNotEmpty()) throttleDeadlines[taskId] = until
            // B2 fast backoff: halve the host's remembered occupancy NOW (a
            // 429/503 is the host telling us the request width is too high).
            val occupancy = HostPolicyRegistry.HostSocketOccupancy
                .recordBackoff(url, hostCeiling)
            maxWorkers.set(maxOf(2, maxWorkers.get() / 2))
            com.anonrode.downloader.util.DebugLog.backend(
                "task=$taskId rate limited (cycle $cycle): all sockets cooling ${waitMs / 1000}s, " +
                    "workers→${maxWorkers.get()} occupancy→$occupancy"
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

        // Adaptive occupancy (B2): aggregate goodput is sampled every few
        // seconds and feeds the per-host controller. Recovery is deliberately
        // slow (+1 lane per two consecutive improving samples) and every
        // raise is clamped by the lane grant, host cap and standby slot.
        var lastAdaptAt = System.currentTimeMillis()
        fun adaptFromGoodput(bps: Double) {
            if (taskId.isBlank()) return
            val occupancy = HostPolicyRegistry.HostSocketOccupancy.recordGoodput(url, hostCeiling, bps)
            val total = minOf(hostCeiling, laneGrant(), occupancy)
            val target = StandbyPolicy.activeWorkerBudgetForTotal(total, standbyEnabled)
            if (target > activeWorkers.get()) {
                activeWorkers.set(target)
                // A recovery may undo the rate-limit worker halving; the
                // throttle ladder itself (cooldown deadline) is untouched.
                maxWorkers.set(maxOf(maxWorkers.get(), target))
                com.anonrode.downloader.util.DebugLog.backend(
                    "task=$taskId lane adapt occupancy=$occupancy target=$target " +
                        "total=${(bps / 1024).toInt()}KB/s"
                )
            }
        }

        // Decoupled Telemetry Dispatcher: Ticks every 250ms with smoothed EMA speed
        val telemetryTicker = launch(Dispatchers.Default) {
            while (isActive) {
                delay(250)
                val currentDone = done.get()
                val currentSpeed = speed.sample(currentDone)
                if (taskId.isNotEmpty()) {
                    HostPolicyRegistry.LaneBudget.reportSpeed(taskId, currentSpeed.toDouble())
                    val now = System.currentTimeMillis()
                    if (now - lastAdaptAt >= ADAPT_SAMPLE_MS) {
                        lastAdaptAt = now
                        adaptFromGoodput(currentSpeed.toDouble())
                    }
                }
                onProgress(committed(), total, currentSpeed)
            }
        }

        try {
            RandomAccessFile(dest, "rw").use { raf ->
                val channel: FileChannel = raf.channel

                // Shared piece queue (BitTorrent style): workers pull the next
                // unfinished piece. When every initial piece is claimed, a free
                // worker STEALS: it splits the active piece with the largest
                // remaining range in half and takes the back half (directive
                // Fix 3, "hurry up"). Threshold/budget corrected per
                // scratch/test_work_steal.py: 512 KiB minimum remainder (pieces
                // are 1 MiB, so a 2 MiB threshold could never fire) and the cap
                // counts SPLITS (initial plans hold up to 2048 pieces).
                fun claimOrStealNext(): TurboChunk? {
                    while (true) {
                        val idx = nextPiece.getAndIncrement()
                        if (idx >= plan.size) break
                        val c = plan[idx]
                        if (c.current <= c.end) return c
                    }
                    synchronized(plan) {
                        if (dynamicSplits >= MAX_DYNAMIC_SPLITS) return null
                        var victim: TurboChunk? = null
                        var victimRemaining = 0L
                        for (c in plan) {
                            if (c.current <= c.end) {
                                val remaining = c.end - c.current + 1L
                                if (remaining >= STEAL_MIN_REMAINING_BYTES && remaining > victimRemaining) {
                                    victim = c
                                    victimRemaining = remaining
                                }
                            }
                        }
                        val v = victim ?: return null
                        val splitOffset = v.current + victimRemaining / 2L
                        if (splitOffset <= v.current || splitOffset > v.end) return null
                        val oldEnd = v.end
                        v.end = splitOffset - 1L
                        val stolen = TurboChunk(start = splitOffset, end = oldEnd, current = splitOffset)
                        plan.add(stolen)
                        dynamicSplits++
                        com.anonrode.downloader.util.DebugLog.backend(
                            "task=$taskId work-steal split piece=${v.start}-$oldEnd -> victimEnds=${v.end} stolen=${stolen.start}-${stolen.end}"
                        )
                        return stolen
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
                    // Drop-and-replace state is PER PIECE (survives retries);
                    // semantics validated in scratch/test_drop_replace.py.
                    var drops = 0
                    var slowAfterDrop = 0
                    var noMoreDrops = false
                    var lastConnWasPostDrop = false
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
                        var hostBackoff = false
                        var dropped = false
                        var connStartedAt = System.currentTimeMillis()
                        var dropWindowStart = connStartedAt
                        var dropWindowBytes = 0L
                        try {
                            // Remember what we ASKED for: if a work-stealer shortens
                            // chunk.end below this while we are mid-read, the call
                            // must be cancelled so OkHttp closes the socket instead
                            // of draining the stolen tail (Fix 3).
                            val requestedRangeEnd = chunk.end
                            val req = Request.Builder().url(url).apply {
                                header("User-Agent", headers["User-Agent"] ?: HttpClient.DEFAULT_UA)
                                headers.forEach { (k, v) -> if (!k.equals("User-Agent", true)) header(k, v) }
                                header("Range", "bytes=${chunk.current}-$requestedRangeEnd")
                                // After a drop, force a brand-new connection: the pool
                                // could otherwise hand back the throttled socket.
                                if (drops > 0) header("Connection", "close")
                            }.build()
                            // A dropped piece promotes the pre-dialed spare
                            // when one is ready: the next attempt starts on an
                            // established connection, hiding the TCP+TLS
                            // handshake the dip used to show. The fallback
                            // stays the old fresh-dial client.
                            val standbyClient = if (drops > 0) standby?.promote() else null
                            val attemptClient = standbyClient
                                ?: if (drops > 0) freshConnectionClient(client) else client
                            val call = attemptClient.newCall(req)
                            trackCall(taskId, call)
                            try {
                                call.execute().use { res ->
                                if (res.code == 206) {
                                    // Audit finding 4: a 206 from the WRONG start
                                    // offset would write the file head at a piece
                                    // offset (silent duplication) — verify the
                                    // server honored the requested Range.
                                    val crStart = res.header("Content-Range")
                                        ?.substringAfter("bytes ", "")?.substringBefore('-')?.trim()?.toLongOrNull()
                                    if (crStart != null && crStart != chunk.current) {
                                        throw java.io.IOException(
                                            "range start mismatch: asked ${chunk.current}, server began at $crStart"
                                        )
                                    }
                                    val src = res.body?.source() ?: throw IOException("Empty response body from server")
                                    val buf = ByteArray(BUFFER)
                                    var pos = chunk.current
                                    var bytesSinceLastCommit = 0L
                                    connStartedAt = System.currentTimeMillis()
                                    dropWindowStart = connStartedAt
                                    dropWindowBytes = 0L
                                    while (pos <= chunk.end && !failed.get()) {
                                        if (!coroutineContext.isActive) {
                                            if (canCommit(taskId)) {
                                                state.commit(plan, total, force = true)
                                            }
                                            return@use
                                        }
                                        val liveEnd = chunk.end
                                        val remainingInChunk = liveEnd - pos + 1L
                                        if (remainingInChunk <= 0L) break
                                        val want = minOf(buf.size.toLong(), remainingInChunk).toInt()
                                        val n = readCoalesced(src, buf, want)
                                        if (n <= 0) break

                                        // Lock-free positional writes: FileChannel.write
                                        // (ByteBuffer, position) is kernel-atomic
                                        // (pwrite64) and each piece owns a DISJOINT byte
                                        // range, so lanes no longer queue behind one
                                        // monitor. The drain loop stays: a short write
                                        // (legal per contract) must surface as a
                                        // retryable failure, never as silent zero holes.
                                        // Re-clamp: a work-stealer may have shortened
                                        // chunk.end while readCoalesced was in flight —
                                        // bytes past the new end belong to the stealer.
                                        val allowedBytes = (chunk.end - pos + 1L).coerceAtLeast(0L).toInt()
                                        val writeLen = minOf(n, allowedBytes)
                                        if (writeLen > 0) {
                                            val bb = ByteBuffer.wrap(buf, 0, writeLen)
                                            var w = 0
                                            while (bb.hasRemaining()) {
                                                val k = channel.write(bb, pos + w)
                                                if (k <= 0) throw java.io.IOException(
                                                    "short positional write at ${pos + w}"
                                                )
                                                w += k
                                            }
                                            pos += writeLen
                                            chunk.current = pos
                                            val nowDone = done.addAndGet(writeLen.toLong())
                                            if (sawFirstByte.compareAndSet(false, true) && taskId.isNotEmpty()) {
                                                com.anonrode.downloader.util.DebugLog.backend(
                                                    "task=$taskId startup ttfb=${System.currentTimeMillis() - rampStartedAt}ms lanes=${activeWorkers.get()}"
                                                )
                                            }
                                            if (nowDone >= 1024L * 1024L &&
                                                sawFirstMegabyte.compareAndSet(false, true) &&
                                                taskId.isNotEmpty()
                                            ) {
                                                com.anonrode.downloader.util.DebugLog.backend(
                                                    "task=$taskId startup first-1MB=${System.currentTimeMillis() - rampStartedAt}ms lanes=${activeWorkers.get()}"
                                                )
                                            }
                                        }
                                        if (pos > chunk.end) {
                                            if (chunk.end < requestedRangeEnd) {
                                                try { call.cancel() } catch (_: Exception) {}
                                            }
                                            break
                                        }

                                        // Drop-and-replace window check (1DM): a lane
                                        // under the floor AFTER warm-up is replaced by
                                        // a fresh connection. Warm-up windows are not
                                        // evidence, and the two-strikes latch is set
                                        // BEFORE the drop decision so IP-wide
                                        // throttling is bounded (test_drop_replace.py).
                                        val nowMs = System.currentTimeMillis()
                                        dropWindowBytes += writeLen
                                        if (nowMs < throttleUntil.get()) {
                                            // Cooldown (audit finding 3): window
                                            // bookkeeping is frozen AND the window
                                            // anchor resets, so parked time can
                                            // never count as slow-lane evidence.
                                            dropWindowBytes = 0L
                                            dropWindowStart = nowMs
                                        } else if (nowMs - dropWindowStart >= DROP_WINDOW_MS) {
                                            val span = (nowMs - dropWindowStart).coerceAtLeast(1L)
                                            val bps = dropWindowBytes * 1000.0 / span
                                            dropWindowBytes = 0L
                                            dropWindowStart = nowMs
                                            // Shared best FIRST (cold start safe: the
                                            // first window seeds it with its own bps,
                                            // so a uniformly slow link never drops).
                                            // The reference decays with a 30s half-life:
                                            // a transient burst must not veto every
                                            // later window for the rest of the run.
                                            DropPolicy.observeBest(bestWindowBps, bps, nowMs)
                                            val bestBps = DropPolicy.decayedBestBps(bestWindowBps.get(), nowMs)
                                            // Retuned: absolute floor AND relative lag.
                                            val isSlowAbsolute = DropPolicy.isSlowAbsolute(bps, bestBps)
                                            // Above-floor but far below the sibling field
                                            // (55KB/s among 1.5MB/s lanes) is still a
                                            // drop candidate — against the DECAYED best.
                                            val isSeverelyLagging = DropPolicy.isSeverelyLagging(bps, bestBps)
                                            if (isSlowAbsolute || isSeverelyLagging) {
                                                val eligible = nowMs - connStartedAt > DROP_WARMUP_MS
                                                if (eligible && lastConnWasPostDrop) {
                                                    slowAfterDrop++
                                                    if (slowAfterDrop >= DROP_STRIKE_LIMIT) noMoreDrops = true
                                                }
                                                if (eligible &&
                                                    chunk.end - pos + 1 > 2L * BUFFER &&
                                                    !noMoreDrops &&
                                                    drops < MAX_DROPS_PER_PIECE &&
                                                    nowMs >= throttleUntil.get()
                                                ) {
                                                    drops++
                                                    lastConnWasPostDrop = true
                                                    dropped = true
                                                    if (taskId.isNotBlank()) {
                                                        dropEventCounts
                                                            .getOrPut(taskId) { AtomicInteger(0) }
                                                            .incrementAndGet()
                                                    }
                                                    com.anonrode.downloader.util.DebugLog.backend(
                                                        "task=$taskId drop slow socket piece=${chunk.start}-${chunk.end} rate=${(bps / 1024).toInt()}kB/s drops=$drops/$MAX_DROPS_PER_PIECE"
                                                    )
                                                    try { call.cancel() } catch (_: Exception) {}
                                                    break
                                                }
                                            } else if (lastConnWasPostDrop && bps >= DROP_FLOOR_BPS &&
                                                !isSeverelyLagging) {
                                                slowAfterDrop = 0 // healthy (not merely non-relative-slow) window
                                            }
                                        }

                                        bytesSinceLastCommit += writeLen
                                        if (bytesSinceLastCommit >= 2 * 1024 * 1024L || pos > chunk.end) {
                                            if (canCommit(taskId)) {
                                                state.commit(plan, total, force = false)
                                            }
                                            bytesSinceLastCommit = 0L
                                        }
                                    }
                                    if (canCommit(taskId)) {
                                        state.commit(plan, total, force = true)
                                    }
                                    completed = pos > chunk.end
                                } else {
                                    failureStatus.compareAndSet(0, res.code)
                                    if (res.code == 429 || res.code == 503) rateLimited = true
                                    // B2 fast backoff on a hard rejection too:
                                    // 403s from a CDN are address/pattern
                                    // rejections; repeating the same width
                                    // deepens them. (Token-expiry 403s still
                                    // end in the engine's re-resolve.)
                                    if (res.code == 403) hostBackoff = true
                                }
                                }
                                } finally {
                                    untrackCall(taskId, call)
                                    // The spare stays "in use" until this
                                    // attempt is fully done; only then may the
                                    // keeper pre-dial its replacement, so a
                                    // promoted socket and a fresh handshake are
                                    // never counted on top of the worker budget.
                                    if (standbyClient != null) standby?.releasePromoted()
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
                                if (hostBackoff) {
                                    // 403: halve the host's occupancy for the
                                    // rest of the session and trim the live
                                    // counters to it at the next claim. No
                                    // cooldown ladder — the attempt policy stays
                                    // unchanged (a 403 may be a token expiry
                                    // that the engine resolves afresh).
                                    val occupancy = HostPolicyRegistry.HostSocketOccupancy
                                        .recordBackoff(url, hostCeiling)
                                    val ceiling = workerCeiling()
                                    maxWorkers.set(minOf(maxWorkers.get(), ceiling))
                                    activeWorkers.set(minOf(activeWorkers.get(), ceiling))
                                    if (taskId.isNotEmpty()) {
                                        com.anonrode.downloader.util.DebugLog.backend(
                                            "task=$taskId host backoff 403: occupancy→$occupancy workers→$ceiling"
                                        )
                                    }
                                }
                                if (dropped) {
                                    // Drop-and-replace: the next attempt resumes from
                                    // chunk.current on a fresh connection. NEVER
                                    // consumes an attempt (mirrors the rateLimited
                                    // path); persist state first so a crash right
                                    // after a drop resumes at the committed offset.
                                    if (canCommit(taskId)) state.commit(plan, total, force = true)
                                    delay(DROP_RETRY_DELAY_MS)
                                    continue
                                }
                                attempt++
                            if (failed.get() || !coroutineContext.isActive) return false
                            failureMessage.compareAndSet(
                                null,
                                "Piece ${chunk.start}-${chunk.end} incomplete (attempt $attempt of $MAX_ATTEMPTS)"
                            )
                        } catch (e: CancellationException) {
                            if (canCommit(taskId)) {
                                state.commit(plan, total, force = true)
                            }
                            throw e
                        } catch (e: Exception) {
                            // Only abort without retry if the coroutine itself was cancelled (user
                            // pause/cancel) or the task was marked for full cancellation. A
                            // SocketTimeoutException (extends InterruptedIOException) or a
                            // watchdog socket reset while the coroutine is still active MUST
                            // count as a normal retryable attempt.
                            if (!coroutineContext.isActive || !canCommit(taskId)) {
                                if (canCommit(taskId)) {
                                    state.commit(plan, total, force = true)
                                }
                                return false
                            }
                            attempt++
                            failureMessage.compareAndSet(null, e.message ?: e.javaClass.simpleName)
                            // Persist the mid-piece position so a pause after this
                            // failure resumes from here instead of the piece start.
                            if (canCommit(taskId)) {
                                state.commit(plan, total, force = true)
                            }
                        }
                        if (attempt < MAX_ATTEMPTS) delay(backoffMillis(attempt))
                    }
                    failed.set(true)
                    return false
                }

                coroutineScope {
                    fun workRemains(): Boolean = plan.any { it.current <= it.end }
                    // Occupancy growth is owned by the telemetry-side adaptive
                    // controller (B2): +1 lane only after two consecutive
                    // improving goodput samples, clamped by host cap, lane
                    // grant and the standby slot. The old additive startup
                    // ramp is gone — a task now STARTS at its allowed width,
                    // and every subsequent change passes the same hysteresis.
                    // Warm-standby keeper (board row 5): keeps exactly one
                    // pre-dialed connection ready for the next drop. It sits
                    // out rate-limit cooldowns and backs off on a refused
                    // pre-dial, so the spare can never become a request storm.
                    if (standby != null) launch(Dispatchers.IO) {
                        while (isActive && !failed.get() && workRemains()) {
                            val cooldownLeft = (throttleUntil.get() - System.currentTimeMillis())
                                .coerceAtLeast(0L)
                            if (cooldownLeft > 0L) {
                                delay(minOf(cooldownLeft, 2_000L))
                                continue
                            }
                            val prepared = if (standby.canPrepare()) {
                                standby.prepare()
                            } else {
                                standby.isReady
                            }
                            delay(if (prepared) STANDBY_KEEPALIVE_MS else STANDBY_RETRY_MS)
                        }
                    }
                    repeat(minOf(hostCeiling, plan.size)) { i ->
                        launch {
                            try {
                                while (isActive && !failed.get()) {
                                    // Concurrency downshift: after a rate-limit
                                    // cycle only the first maxWorkers sockets
                                    // keep claiming; the shared queue routes the
                                    // rest of the pieces to the survivors.
                                    if (i >= maxWorkers.get()) break
                                    // Controller grant: a shrink takes effect
                                    // HERE, at the next piece boundary — an
                                    // in-flight piece is never revoked. The
                                    // standby's reserved slot is already out of
                                    // workerCeiling().
                                    if (i >= activeWorkers.get() || i >= workerCeiling()) {
                                        if (plan.none { it.current <= it.end }) break
                                        delay(100L)
                                        continue
                                    }
                                    val chunk = claimOrStealNext() ?: break
                                    if (!downloadPiece(chunk)) break
                                }
                            } catch (_: CancellationException) {
                                if (canCommit(taskId)) {
                                    state.commit(plan, total, force = true)
                                }
                            }
                        }
                    }
                }
                channel.force(true)
            }
        } finally {
            telemetryTicker.cancel()
            onProgress(committed(), total, speed.getSpeed())
            if (taskId.isNotEmpty()) {
                throttleDeadlines.remove(taskId)
            }
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
        val initialBytes = if (dest.exists()) dest.length() else 0L
        val done = AtomicLong(initialBytes)
        val speed = SpeedMeter(initialBytes)
        onProgress(initialBytes, if (total > 0) total else 0L, 0L)

        // Decoupled Telemetry Dispatcher: Ticks every 250ms
        val telemetryTicker = launch(Dispatchers.Default) {
            while (isActive) {
                delay(250)
                val currentDone = done.get()
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
                            done.set(startAt)
                            RandomAccessFile(dest, "rw").use { raf ->
                                if (!resuming || raf.length() < startAt) raf.setLength(startAt)
                                raf.seek(startAt)
                                val buf = ByteArray(BUFFER)
                                var written = startAt
                                while (true) {
                                    if (!coroutineContext.isActive) return false
                                                val n = readCoalesced(src, buf, buf.size)
                                                if (n <= 0) break
                                                raf.write(buf, 0, n)
                                    written += n
                                    done.set(written)
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
            val finalDone = done.get()
            onProgress(finalDone, if (total > 0) total else 0L, speed.getSpeed())
            if (taskId.isNotEmpty()) {
                throttleDeadlines.remove(taskId)
            }
        }
        return@coroutineScope success
    }
}

/** One segment's byte window plus how far it has been committed to disk. */
/** One segment's byte window plus how far it has been committed to disk.
 *  [end] is @Volatile: a work-stealing sibling may SHORTEN it in flight
 *  (directive Fix 3), and the victim must observe the new end immediately. */
class TurboChunk(val start: Long, @Volatile var end: Long, @Volatile var current: Long)

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
