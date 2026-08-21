package com.anonrode.downloader.engine

import com.anonrode.downloader.data.net.HttpClient
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
     * Sockets per download: ensures 4 to 16 concurrent range connections
     * to bypass server-side single-socket 200KB/s throttling.
     */
    fun socketsFor(url: String, configured: Int): Int {
        return configured.coerceIn(4, 16)
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
            if (dest.exists()) dest.delete()
            if (src.renameTo(dest)) return true
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

        val probe = probe(safe, headers, client)
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
            val ok = segmented(safe, partFile, headers, total, sockets, state, failureStatus, failureMessage, onProgress, client, taskId)
            if (ok) {
                state.delete()
                atomicMove(partFile, dest)
                TurboResult.Success(dest, dest.length(), true)
            } else if (!partFile.exists() || partFile.length() == 0L) {
                state.delete()
                if (single(safe, partFile, headers, total, failureStatus, failureMessage, onProgress, client, taskId)) {
                    atomicMove(partFile, dest)
                    TurboResult.Success(dest, dest.length(), false)
                } else failure(failureStatus, failureMessage)
            } else failure(failureStatus, failureMessage)
        } else {
            state.delete()
            if (single(safe, partFile, headers, total, failureStatus, failureMessage, onProgress, client, taskId)) {
                state.delete()
                atomicMove(partFile, dest)
                TurboResult.Success(dest, dest.length(), false)
            } else failure(failureStatus, failureMessage)
        }
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
    private fun probe(url: String, headers: Map<String, String>, client: OkHttpClient): ProbeResult {
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

        // 1. Try HEAD request
        try {
            client.newCall(buildReq(true)).execute().use { r ->
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
        } catch (_: Exception) {}

        // 2. If Accept-Ranges was not explicit on HEAD, probe with Range: bytes=0-0
        try {
            client.newCall(buildReq(false)).execute().use { r ->
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
        } catch (_: Exception) {}

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

        RandomAccessFile(dest, "rw").use { raf ->
            if (raf.length() != total) raf.setLength(total)
        }

        val speed = SpeedMeter(initialBytes)
        onProgress(initialBytes, total, 0L)

        // Decoupled Telemetry Dispatcher: Ticks every 250ms with smoothed EMA speed
        val telemetryTicker = launch(Dispatchers.Default) {
            while (isActive) {
                delay(250)
                val currentDone = done.get()
                val currentSpeed = speed.sample(currentDone)
                onProgress(currentDone, total, currentSpeed)
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
                 * Download one piece, retrying with exponential backoff. Each retry
                 * re-requests from the piece's committed offset, so a mid-body drop
                 * resumes the piece instead of restarting it. The global failure flag
                 * is only set once a piece exhausts all attempts, so a single hiccup
                 * never aborts the siblings.
                 */
                suspend fun downloadPiece(chunk: TurboChunk): Boolean {
                    var attempt = 0
                    while (attempt < MAX_ATTEMPTS) {
                        attempt++
                        if (failed.get() || !coroutineContext.isActive) return false
                        var completed = false
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
                                            channel.write(ByteBuffer.wrap(buf, 0, n), pos)
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
                                }
                                }
                                } finally {
                                    untrackCall(taskId, call)
                                }
                            if (completed) return true
                            if (failed.get() || !coroutineContext.isActive) return false
                            failureMessage.compareAndSet(
                                null,
                                "Piece ${chunk.start}-${chunk.end} incomplete (attempt $attempt of $MAX_ATTEMPTS)"
                            )
                        } catch (_: CancellationException) {
                            state.commit(plan, total, force = true)
                            return false
                        } catch (e: Exception) {
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
                    repeat(minOf(sockets, plan.size)) {
                        launch {
                            try {
                                while (isActive && !failed.get()) {
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
            onProgress(done.get(), total, speed.getSpeed())
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
        try {
            suspend fun attempt(): Boolean {
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

            while (attemptCount < MAX_ATTEMPTS && !success) {
                attemptCount++
                if (attempt()) {
                    success = true
                } else if (attemptCount < MAX_ATTEMPTS) {
                    delay(backoffMillis(attemptCount))
                }
            }
        } catch (_: CancellationException) {
            // A paused job never surfaces as a failure.
        } catch (e: Exception) {
            failureMessage.compareAndSet(null, e.message ?: e.javaClass.simpleName)
        } finally {
            telemetryTicker.cancel()
            onProgress(if (dest.exists()) dest.length() else 0L, if (total > 0) total else 0L, speed.getSpeed())
        }
        return@coroutineScope success
    }
}

/** One segment's byte window plus how far it has been committed to disk. */
class TurboChunk(val start: Long, val end: Long, @Volatile var current: Long)

/**
 * Thread-safe aggregate speed meter utilizing Exponential Moving Average (EMA).
 *
 * Uses alpha = 0.25 across 250ms measurement samples to eliminate TCP burst jitter
 * while faithfully tracking true transfer throughput.
 */
class SpeedMeter(initialBytes: Long = 0L) {
    private var lastBytes = initialBytes
    private var lastTime = System.currentTimeMillis()
    private var emaSpeed = 0.0
    private var initialized = false

    @Synchronized
    fun sample(totalBytes: Long): Long {
        val now = System.currentTimeMillis()
        val dt = now - lastTime
        if (dt >= 250) {
            val delta = (totalBytes - lastBytes).coerceAtLeast(0L)
            val instantBps = (delta * 1000.0) / dt
            if (!initialized) {
                emaSpeed = instantBps
                initialized = true
            } else {
                val alpha = 0.25
                emaSpeed = (alpha * instantBps) + ((1.0 - alpha) * emaSpeed)
            }
            lastBytes = totalBytes
            lastTime = now
        }
        return emaSpeed.toLong().coerceAtLeast(0L)
    }

    @Synchronized
    fun getSpeed(): Long = emaSpeed.toLong().coerceAtLeast(0L)
}
