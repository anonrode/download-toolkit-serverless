package com.anonrode.downloader.engine

import com.anonrode.downloader.data.net.HttpClient
import kotlinx.coroutines.*
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * High-performance multi-socket segmented downloader for direct CDN files.
 *
 * Designed with:
 * 1. Definitive Range verification (probe with fallback bytes=0-0 GET).
 * 2. Decoupled telemetry ticker (250ms cadence) with EMA speed smoothing.
 * 3. Non-blocking parallel worker streams writing to pre-allocated FileChannels.
 * 4. Throttled sidecar resume state commits.
 */
object TurboDownloader {

    private const val BUFFER = 256 * 1024 // 256 KB high-throughput buffer
    private const val MIN_SEGMENTED_SIZE = 8L * 1024 * 1024 // 8 MB

    /**
     * Sockets per download: ensures 4 to 16 concurrent range connections
     * to bypass server-side single-socket 200KB/s throttling.
     */
    fun socketsFor(url: String, configured: Int): Int {
        return configured.coerceIn(4, 16)
    }

    data class Result(val file: File, val bytes: Long, val segmented: Boolean)

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
     * Download [url] to [dest]. Returns null if the transfer did not complete.
     * [onProgress] receives (downloadedBytes, totalBytes, bytesPerSecond).
     */
    suspend fun download(
        url: String,
        dest: File,
        headers: Map<String, String> = emptyMap(),
        configuredSockets: Int = 8,
        onProgress: (Long, Long, Long) -> Unit = { _, _, _ -> }
    ): Result? = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val safe = HttpClient.safeUrl(url)

        val probe = probe(safe, headers)
        val total = probe.first
        val acceptsRanges = probe.second

        val sockets = socketsFor(safe, configuredSockets)
        val useSegmented = acceptsRanges && total > MIN_SEGMENTED_SIZE && sockets > 1

        val partFile = File(dest.absolutePath + ".part")
        val state = TurboState(File(dest.absolutePath + ".turbo"))

        return@withContext if (useSegmented) {
            val ok = segmented(safe, partFile, headers, total, sockets, state, onProgress)
            if (ok) {
                state.delete()
                atomicMove(partFile, dest)
                Result(dest, dest.length(), true)
            } else {
                if (!partFile.exists() || partFile.length() == 0L) {
                    state.delete()
                    if (single(safe, partFile, headers, total, onProgress)) {
                        atomicMove(partFile, dest)
                        Result(dest, dest.length(), false)
                    } else null
                } else null
            }
        } else {
            state.delete()
            if (single(safe, partFile, headers, total, onProgress)) {
                state.delete()
                atomicMove(partFile, dest)
                Result(dest, dest.length(), false)
            } else null
        }
    }

    /**
     * Definitive range probe:
     * 1. Check HEAD. If Content-Length > 0 and Accept-Ranges is explicit, return (len, true).
     * 2. If Accept-Ranges is omitted on HEAD (common on CDNs), test Range: bytes=0-0.
     * 3. If server responds with HTTP 206 Partial Content, return (total, true).
     */
    private fun probe(url: String, headers: Map<String, String>): Pair<Long, Boolean> {
        fun buildReq(head: Boolean) = Request.Builder().url(url).apply {
            header("User-Agent", headers["User-Agent"] ?: HttpClient.DEFAULT_UA)
            headers.forEach { (k, v) -> if (!k.equals("User-Agent", true)) header(k, v) }
            if (head) head() else header("Range", "bytes=0-0")
        }.build()

        var totalLength = -1L

        // 1. Try HEAD request
        try {
            HttpClient.downloadClient.newCall(buildReq(true)).execute().use { r ->
                val len = r.header("Content-Length")?.toLongOrNull() ?: -1L
                val ranges = r.header("Accept-Ranges")?.contains("bytes", true) == true
                if (r.isSuccessful && len > 0) {
                    totalLength = len
                    if (ranges) {
                        return Pair(totalLength, true)
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. If Accept-Ranges was not explicit on HEAD, probe with Range: bytes=0-0
        try {
            HttpClient.downloadClient.newCall(buildReq(false)).execute().use { r ->
                val cr = r.header("Content-Range")
                val totalFromCr = cr?.substringAfter('/')?.trim()?.toLongOrNull() ?: -1L
                val is206 = r.code == 206
                if (is206) {
                    val finalTotal = if (totalFromCr > 0) totalFromCr else totalLength
                    return Pair(finalTotal, true)
                } else if (r.isSuccessful && totalLength <= 0) {
                    val len = r.header("Content-Length")?.toLongOrNull() ?: -1L
                    if (len > 0) totalLength = len
                }
            }
        } catch (_: Exception) {}

        return Pair(totalLength, false)
    }

    private suspend fun segmented(
        url: String,
        dest: File,
        headers: Map<String, String>,
        total: Long,
        sockets: Int,
        state: TurboState,
        onProgress: (Long, Long, Long) -> Unit
    ): Boolean = coroutineScope {
        val plan = state.loadOrCreate(total, sockets)
        val initialBytes = plan.sumOf { (it.current - it.start).coerceAtLeast(0L) }
        val done = AtomicLong(initialBytes)
        val failed = java.util.concurrent.atomic.AtomicBoolean(false)

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
                coroutineScope {
                    for (chunk in plan) {
                        if (chunk.current > chunk.end) continue // already complete
                        launch {
                            try {
                                val req = Request.Builder().url(url).apply {
                                    header("User-Agent", headers["User-Agent"] ?: HttpClient.DEFAULT_UA)
                                    headers.forEach { (k, v) -> if (!k.equals("User-Agent", true)) header(k, v) }
                                    header("Range", "bytes=${chunk.current}-${chunk.end}")
                                }.build()

                                HttpClient.downloadClient.newCall(req).execute().use { res ->
                                    if (res.code != 206) {
                                        failed.set(true)
                                        return@use
                                    }
                                    val src = res.body?.source() ?: run {
                                        failed.set(true)
                                        return@use
                                    }
                                    val buf = ByteArray(BUFFER)
                                    var pos = chunk.current
                                    var bytesSinceLastCommit = 0L
                                    while (pos <= chunk.end) {
                                        if (!coroutineContext.isActive) {
                                            state.commit(plan, total, force = true)
                                            return@use
                                        }
                                        val want = minOf(buf.size.toLong(), chunk.end - pos + 1).toInt()
                                        val n = src.read(buf, 0, want)
                                        if (n == -1) break
                                        
                                        // Synchronized block on channel write
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
                                }
                            } catch (_: CancellationException) {
                                state.commit(plan, total, force = true)
                            } catch (_: Exception) {
                                failed.set(true)
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
        onProgress: (Long, Long, Long) -> Unit
    ): Boolean = coroutineScope {
        val existing = if (dest.exists()) dest.length() else 0L
        val req = Request.Builder().url(url).apply {
            header("User-Agent", headers["User-Agent"] ?: HttpClient.DEFAULT_UA)
            headers.forEach { (k, v) -> if (!k.equals("User-Agent", true)) header(k, v) }
            if (existing > 0) header("Range", "bytes=$existing-")
        }.build()

        val done = AtomicLong(existing)
        val speed = SpeedMeter(existing)
        onProgress(existing, if (total > 0) total else 0L, 0L)

        // Decoupled Telemetry Dispatcher: Ticks every 250ms
        val telemetryTicker = launch(Dispatchers.Default) {
            while (isActive) {
                delay(250)
                val currentDone = done.get()
                val currentSpeed = speed.sample(currentDone)
                onProgress(currentDone, if (total > 0) total else 0L, currentSpeed)
            }
        }

        try {
            HttpClient.downloadClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@coroutineScope false
                val resuming = res.code == 206
                val src = res.body?.source() ?: return@coroutineScope false
                val startAt = if (resuming) existing else 0L
                RandomAccessFile(dest, "rw").use { raf ->
                    raf.seek(startAt)
                    val buf = ByteArray(BUFFER)
                    var written = startAt
                    while (true) {
                        if (!coroutineContext.isActive) return@coroutineScope false
                        val n = src.read(buf)
                        if (n == -1) break
                        raf.write(buf, 0, n)
                        written += n
                        done.set(written)
                    }
                    if (total > 0 && written != total) return@coroutineScope false
                }
            }
        } catch (_: CancellationException) {
            return@coroutineScope false
        } catch (_: Exception) {
            return@coroutineScope false
        } finally {
            telemetryTicker.cancel()
            onProgress(done.get(), if (total > 0) total else 0L, speed.getSpeed())
        }
        return@coroutineScope true
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
