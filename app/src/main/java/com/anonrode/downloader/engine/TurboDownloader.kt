package com.anonrode.downloader.engine

import com.anonrode.downloader.data.net.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * Multi-socket segmented downloader for direct CDN files (IDM/1DM style).
 *
 * Why this exists: free lockers throttle PER CONNECTION, not per client. Measured
 * against the loadedfiles CDN: one socket sustained ~155 KB/s while four parallel
 * ranges over the same file moved ~355 KB/s (2.3x). Gains are real but SUB-LINEAR,
 * so the socket count is deliberately modest and per-host capped rather than a
 * blanket 16 -- the previous 16-socket engine collapsed to ~40 KB/s on
 * downloadwella because that host punishes high concurrency.
 *
 * Correctness notes (these are the parts that bit us before):
 *
 * 1. RESUME IS EXPLICIT. Every worker writes at its absolute file offset into one
 *    pre-allocated file, so there are no .partN fragments to merge -- but a
 *    pre-allocated file is zero-filled, which makes "how far did chunk 3 get?"
 *    unanswerable from the file alone. A sidecar `<file>.turbo` records each
 *    chunk's committed offset (the same job aria2c's .aria2 control file does).
 *    Without it, resume either restarts or silently corrupts.
 *
 * 2. OFFSETS ARE COMMITTED ONLY AFTER THE WRITE LANDS, so a kill mid-write can
 *    re-download a few KB but can never skip bytes.
 *
 * 3. RANGE SUPPORT IS VERIFIED, NOT ASSUMED. A server that ignores Range replies
 *    200 with the whole body; writing that at a chunk offset would corrupt the
 *    file. Any worker that does not get 206 aborts the segmented attempt and we
 *    fall back to a single stream.
 */
object TurboDownloader {

    private const val BUFFER = 128 * 1024
    private const val MIN_SEGMENTED_SIZE = 8L * 1024 * 1024   // below this, 1 socket is fine

    /** Hosts that throttle or drop many-connection clients (monolith's lesson). */
    private val SINGLE_SOCKET_HOSTS = listOf(
        "kissorgrab.com", "downloadwella.com", "wetafiles.com"
    )
    private val LOW_SOCKET_HOSTS = listOf(
        "streamwish.", "vidhide.", "filelions.", "lulacloud.com", "vikingfile.com"
    )

    /** Per-host socket cap. Never a blanket 16 -- see the class note. */
    fun socketsFor(url: String, configured: Int): Int {
        val u = url.lowercase()
        return when {
            SINGLE_SOCKET_HOSTS.any { u.contains(it) } -> 1
            LOW_SOCKET_HOSTS.any { u.contains(it) } -> 2
            else -> configured.coerceIn(1, 8)
        }
    }

    data class Result(val file: File, val bytes: Long, val segmented: Boolean)

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

        val state = TurboState(File(dest.absolutePath + ".turbo"))

        return@withContext if (useSegmented) {
            val ok = segmented(safe, dest, headers, total, sockets, state, onProgress)
            if (ok) {
                state.delete()
                Result(dest, dest.length(), true)
            } else {
                // Range was advertised but not honoured (or a worker failed):
                // start clean rather than trusting a partially-correct file.
                state.delete()
                dest.delete()
                if (single(safe, dest, headers, total, onProgress)) Result(dest, dest.length(), false) else null
            }
        } else {
            if (single(safe, dest, headers, total, onProgress)) Result(dest, dest.length(), false) else null
        }
    }

    /** HEAD first (cheap); some hosts only answer a ranged GET, so fall back. */
    private fun probe(url: String, headers: Map<String, String>): Pair<Long, Boolean> {
        fun build(head: Boolean) = Request.Builder().url(url).apply {
            header("User-Agent", headers["User-Agent"] ?: HttpClient.DEFAULT_UA)
            headers.forEach { (k, v) -> if (!k.equals("User-Agent", true)) header(k, v) }
            if (head) head() else header("Range", "bytes=0-0")
        }.build()

        try {
            HttpClient.shared.newCall(build(true)).execute().use { r ->
                val len = r.header("Content-Length")?.toLongOrNull() ?: -1L
                val ranges = r.header("Accept-Ranges")?.contains("bytes", true) == true
                if (r.isSuccessful && len > 0) return Pair(len, ranges)
            }
        } catch (_: Exception) {}

        try {
            HttpClient.shared.newCall(build(false)).execute().use { r ->
                // "bytes 0-0/12345" -> total is after the slash.
                val cr = r.header("Content-Range")
                val total = cr?.substringAfter('/')?.toLongOrNull() ?: -1L
                return Pair(total, r.code == 206)
            }
        } catch (_: Exception) {}

        return Pair(-1L, false)
    }

    private suspend fun segmented(
        url: String,
        dest: File,
        headers: Map<String, String>,
        total: Long,
        sockets: Int,
        state: TurboState,
        onProgress: (Long, Long, Long) -> Unit
    ): Boolean {
        val plan = state.loadOrCreate(total, sockets)
        // Bytes already committed by a previous run, so progress resumes truthfully.
        val done = AtomicLong(plan.sumOf { it.current - it.start })
        val failed = java.util.concurrent.atomic.AtomicBoolean(false)

        RandomAccessFile(dest, "rw").use { raf ->
            if (raf.length() != total) raf.setLength(total)
        }

        val speed = SpeedMeter()
        RandomAccessFile(dest, "rw").use { raf ->
            val channel: FileChannel = raf.channel
            coroutineScope {
                for (chunk in plan) {
                    if (chunk.current > chunk.end) continue      // already complete
                    launch {
                        try {
                            val req = Request.Builder().url(url).apply {
                                header("User-Agent", headers["User-Agent"] ?: HttpClient.DEFAULT_UA)
                                headers.forEach { (k, v) -> if (!k.equals("User-Agent", true)) header(k, v) }
                                header("Range", "bytes=${chunk.current}-${chunk.end}")
                            }.build()

                            HttpClient.shared.newCall(req).execute().use { res ->
                                // A 200 means Range was ignored: the body is the WHOLE
                                // file, and writing it at this offset would corrupt.
                                if (res.code != 206) { failed.set(true); return@use }
                                val src = res.body?.source() ?: run { failed.set(true); return@use }
                                val buf = ByteArray(BUFFER)
                                var pos = chunk.current
                                while (pos <= chunk.end) {
                                    if (!coroutineContext.isActive) return@use
                                    val want = minOf(buf.size.toLong(), chunk.end - pos + 1).toInt()
                                    val n = src.read(buf, 0, want)
                                    if (n == -1) break
                                    channel.write(ByteBuffer.wrap(buf, 0, n), pos)
                                    pos += n
                                    // Commit AFTER the write lands: a kill here can
                                    // re-fetch a few KB but can never skip bytes.
                                    chunk.current = pos
                                    state.commit(plan)
                                    val d = done.addAndGet(n.toLong())
                                    onProgress(d, total, speed.sample(d))
                                }
                            }
                        } catch (_: CancellationException) {
                            throw CancellationException()
                        } catch (_: Exception) {
                            failed.set(true)
                        }
                    }
                }
            }
            channel.force(true)
        }

        if (failed.get()) return false
        // Every chunk must have reached its end, and the file must be exactly the
        // advertised size -- otherwise we'd hand back a truncated video.
        if (plan.any { it.current <= it.end }) return false
        return dest.length() == total
    }

    private suspend fun single(
        url: String,
        dest: File,
        headers: Map<String, String>,
        total: Long,
        onProgress: (Long, Long, Long) -> Unit
    ): Boolean {
        val existing = if (dest.exists()) dest.length() else 0L
        val req = Request.Builder().url(url).apply {
            header("User-Agent", headers["User-Agent"] ?: HttpClient.DEFAULT_UA)
            headers.forEach { (k, v) -> if (!k.equals("User-Agent", true)) header(k, v) }
            if (existing > 0) header("Range", "bytes=$existing-")
        }.build()

        val speed = SpeedMeter()
        try {
            HttpClient.shared.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return false
                val resuming = res.code == 206
                val src = res.body?.source() ?: return false
                val startAt = if (resuming) existing else 0L
                RandomAccessFile(dest, "rw").use { raf ->
                    raf.seek(startAt)
                    val buf = ByteArray(BUFFER)
                    var written = startAt
                    while (true) {
                        if (!coroutineContext.isActive) return false
                        val n = src.read(buf)
                        if (n == -1) break
                        raf.write(buf, 0, n)
                        written += n
                        onProgress(written, if (total > 0) total else written, speed.sample(written))
                    }
                    if (total > 0 && written != total) return false
                }
            }
        } catch (_: CancellationException) {
            throw CancellationException()
        } catch (_: Exception) {
            return false
        }
        return true
    }
}

/** One segment's byte window plus how far it has been committed to disk. */
class TurboChunk(val start: Long, val end: Long, @Volatile var current: Long)

/** Speed over a short window; a whole-transfer average hides real slowdowns. */
class SpeedMeter {
    private var lastBytes = 0L
    private var lastTime = System.currentTimeMillis()
    private var last = 0L

    fun sample(totalBytes: Long): Long {
        val now = System.currentTimeMillis()
        val dt = now - lastTime
        if (dt >= 700) {
            last = ((totalBytes - lastBytes) * 1000 / dt).coerceAtLeast(0)
            lastBytes = totalBytes
            lastTime = now
        }
        return last
    }
}
