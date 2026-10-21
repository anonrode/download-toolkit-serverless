package com.anonrode.downloader.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

/**
 * RC-9 root fix (board row 35, 2026-10-21): an in-process, PLAYER-LIKE HLS
 * VOD lane. Live evidence (activity logs 18/19 + workstation probes): a
 * stream limiter on the anime embed chain (fetch.nexabloom.top manifests,
 * mo6mo.stellarfrontier.space segments) trips on BYTES PER WINDOW per IP
 * (~87.7 MiB observed: every Demon's Ascension retry failed at the identical
 * 89,794 KiB boundary and next-day resume finished in seconds), while a
 * single sequential connection streamed clean through the same host family —
 * exactly how a media player consumes the same content all day.
 *
 * The released path was `yt-dlp -N 16` (`--concurrent-fragments`): 16 lanes ×
 * ~1 MB segments crossed the byte budget in seconds, then every lane 403'd on
 * the next fragment and `--abort-on-unavailable-fragments` failed the whole
 * ladder (blank `ERROR: ` → park; see RC-8). This lane replaces that shape
 * with:
 *   1. ONE manifest lifecycle — the master and the chosen variant are each
 *      fetched once per attempt;
 *   2. ONE pooled keep-alive connection with the task's persisted headers
 *      (Referer/UA/tokens — commit 0c4a35a) on every request;
 *   3. sequential segment fetch, appending in order at an exact water mark;
 *   4. byte-resume via a per-segment sidecar (`.hlsrs`), so a rate-limited
 *      403 parks and RESUMES at the failed boundary when the window resets
 *      instead of refighting the same quota from zero;
 *   5. one ffmpeg `-c copy` remux (no re-encode) through the bundled
 *      youtubedl-android ffmpeg binary (InstagramPhotoMuxer's extraction
 *      pattern).
 *
 * Anything that is NOT a plain unencrypted VOD (AES keys, byte-range maps,
 * live windows without `#EXT-X-ENDLIST`) returns `null` from [attempt] before
 * a single segment is fetched, and the engine falls back to yt-dlp unchanged.
 * The pure parsing/resume helpers and [downloadSegments] carry no Android
 * imports so they are JVM-testable.
 */
object HlsVodDownloader {

    /** Parsed single-variant media playlist. */
    data class ParsedVariant(
        val segments: List<String>,
        val durationSec: Double,
        val encrypted: Boolean,
        val byterange: Boolean,
        val hasEndlist: Boolean
    ) {
        /** Downloadable by this lane: VOD, unencrypted, no byte-range maps. */
        val supported: Boolean
            get() = segments.isNotEmpty() && !encrypted && !byterange && hasEndlist
    }

    /** Typed rate-limit escape: carries the REAL status and progress (RC-8
     *  charter: no empty error text ever reaches a park banner). */
    class RateLimited(
        val bytesDone: Long,
        val segmentsDone: Int,
        val lastStatus: Int
    ) : Exception(
        "HLS segment fetch blocked (HTTP $lastStatus) after $bytesDone bytes across $segmentsDone " +
            "segments — the stream host is rate-limiting; the download resumes at this exact " +
            "boundary later instead of restarting"
    )

    /** Cumulative segmentsDone+bytes recorded after each completed segment. */
    data class ResumeState(val segmentsDone: Int, val bytes: Long)

    /** Segment fetch shape shared by pacing/unit tests. */
    data class SegmentOutcome(val index: Int, val bytes: Long, val status: Int)

    // ──────────────────────────── pure parsing ────────────────────────────

    /** Absolute-URL resolve (okhttp resolves relative and `//host/…`). */
    internal fun resolveUri(base: String, uri: String): String? {
        if (uri.isBlank()) return null
        return base.toHttpUrlOrNull()?.resolve(uri.trim())?.toString()
    }

    /** Extract the 1080/720/… height from an EXT-X-STREAM-INF line, 0 if none. */
    internal fun variantHeight(tag: String): Int =
        Regex("""RESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(2)?.toIntOrNull() ?: 0

    /**
     * Multi-variant master → the variant URL at-or-below [maxHeight] with the
     * highest resolution (yt-dlp height-selection parity; when nothing fits,
     * the SMALLEST variant, never up-resolve). Returns null when the body is
     * not a multivariant master — the caller then parses it as the variant.
     */
    internal fun parseMaster(body: String, baseUrl: String, maxHeight: Int): String? {
        val lines = body.lineSequence().map { it.trim() }.toList()
        if (lines.none { it.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) }) return null
        var bestUrl: String? = null
        var bestHeight = Int.MAX_VALUE
        var i = 1
        while (i < lines.size) {
            val tag = lines[i - 1]
            val uri = lines[i]
            if (tag.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) &&
                !uri.startsWith("#") && uri.isNotBlank()
            ) {
                val height = variantHeight(tag)
                val resolved = resolveUri(baseUrl, uri)
                if (resolved != null) {
                    if (height in 1..maxHeight) {
                        // At-or-below the cap: prefer the LARGEST such height.
                        if (bestUrl == null || bestHeight > maxHeight || height > bestHeight) {
                            bestUrl = resolved
                            bestHeight = height
                        }
                    } else if (bestUrl == null || (bestHeight > maxHeight && height < bestHeight)) {
                        // Nothing fits yet: keep the SMALLEST as last resort.
                        bestUrl = resolved
                        bestHeight = height
                    }
                }
            }
            i++
        }
        return bestUrl
    }

    /** Single-variant media playlist → segments, metadata, support flags. */
    internal fun parseVariant(body: String, baseUrl: String): ParsedVariant {
        val raw = body.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val segments = mutableListOf<String>()
        var duration = 0.0
        var encrypted = false
        var byterange = false
        var endlist = false
        for (line in raw) {
            when {
                line.startsWith("#EXT-X-KEY", ignoreCase = true) -> {
                    if (!line.contains("METHOD=NONE", ignoreCase = true)) encrypted = true
                }
                line.startsWith("#EXT-X-BYTERANGE", ignoreCase = true) -> byterange = true
                line.startsWith("#EXT-X-ENDLIST", ignoreCase = true) -> endlist = true
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val d = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull()
                    if (d != null) duration += d
                }
                !line.startsWith("#") -> resolveUri(baseUrl, line)?.let { segments.add(it) }
            }
        }
        return ParsedVariant(segments, duration, encrypted, byterange, endlist)
    }

    // ──────────────────────────── resume state ────────────────────────────

    private const val SIDECAR_MAGIC = "hlsrs1"

    fun readResume(sidecar: File): ResumeState {
        if (!sidecar.isFile) return ResumeState(0, 0L)
        return try {
            val lines = sidecar.readLines()
            val head = lines.firstOrNull()?.trim().orEmpty()
            if (head != SIDECAR_MAGIC || lines.size < 3) return ResumeState(0, 0L)
            val n = lines[1].trim().toIntOrNull() ?: 0
            val b = lines[2].trim().toLongOrNull() ?: 0L
            if (n < 0 || b < 0) ResumeState(0, 0L)
            else ResumeState(min(n, Int.MAX_VALUE), b)
        } catch (_: Exception) {
            ResumeState(0, 0L)
        }
    }

    /** Atomic-ish sidecar write (tmp + rename) so a crash never forks the
     *  byte offset from the segment index. */
    fun writeResume(sidecar: File, segmentsDone: Int, bytes: Long) {
        try {
            val tmp = File(sidecar.absolutePath + ".tmp")
            tmp.writeText("$SIDECAR_MAGIC\n$segmentsDone\n$bytes\n")
            if (!tmp.renameTo(sidecar)) {
                tmp.copyTo(sidecar, overwrite = true)
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    fun deleteResume(sidecar: File) {
        try { sidecar.delete() } catch (_: Exception) {}
        try { File(sidecar.absolutePath + ".tmp").delete() } catch (_: Exception) {}
    }

    // ──────────────────────────── core fetch loop ────────────────────────────

    /**
     * Sequential, keep-alive segment fetch appending to [dest] in order at an
     * exact water mark. Returns [dest] fully written (raw segment
     * concatenation — remux is the caller's job). Throws [RateLimited] when
     * the host keeps rejecting a segment after small per-segment retries, so
     * the ENGINE can park with a real reason and re-queue; the next attempt
     * resumes from the sidecar position. Pure okhttp: JVM-testable.
     */
    suspend fun downloadSegments(
        client: OkHttpClient,
        segments: List<String>,
        headers: Map<String, String>,
        dest: File,
        sidecar: File,
        estimatedTotalBytes: Long,
        onProgress: (downloaded: Long, total: Long, speed: Long, eta: Long) -> Unit,
        isCancelled: () -> Boolean
    ): File = withContext(Dispatchers.IO) {
        val resume = readResume(sidecar)
        var startIndex = resume.segmentsDone.coerceIn(0, segments.size)
        val alreadyBytes = if (startIndex > 0) resume.bytes else 0L
        var cumulative = alreadyBytes
        val speedMeter = SpeedMeter(alreadyBytes)
        var avgSegment = 0L
        dest.parentFile?.mkdirs()

        if (startIndex > 0) {
            // The staging file is our append log; the sidecar is the truth.
            val len = if (dest.isFile) dest.length() else 0L
            if (len > alreadyBytes) {
                // A kill between "append" and "sidecar write" leaves garbage
                // past the water mark — trim back to the exact boundary.
                try { java.io.RandomAccessFile(dest, "rw").use { it.setLength(alreadyBytes) } } catch (_: Exception) {}
            } else if (len < alreadyBytes) {
                // Staging truncated/lost (cache clean): restart the stream.
                writeResume(sidecar, 0, 0L)
                startIndex = 0
                cumulative = 0L
                speedMeter = SpeedMeter(0L)
            }
        }
        if (startIndex == 0) {
            try { dest.delete() } catch (_: Exception) {}
        } else if (estimatedTotalBytes <= 0) {
            avgSegment = max(1L, alreadyBytes / max(1, startIndex))
        }

        val sizesSeen = mutableListOf<Long>()
        for (idx in startIndex until segments.size) {
            ensureActive()
            check(!isCancelled()) { "HLS lane cancelled" }
            val url = segments[idx]
            var lastStatus = 0
            var appended = false
            for (attemptNo in 1..3) {
                ensureActive()
                var bytesThisSegment = 0L
                try {
                    client.newCall(buildRequest(url, headers)).execute().use { res ->
                        lastStatus = res.code
                        if (res.code == 403 || res.code == 429) {
                            // Quota family: stop cleanly. Every committed
                            // segment is already in the sidecar, so the
                            // resumed attempt continues at THIS boundary.
                            throw RateLimited(cumulative, idx, res.code)
                        }
                        if (!res.isSuccessful) {
                            throw java.io.IOException("HTTP ${res.code}")
                        }
                        val body = res.body ?: throw java.io.IOException("empty body")
                        val src = body.byteStream()
                        val buf = ByteArray(256 * 1024)
                        java.io.RandomAccessFile(dest, "rw").use { raf ->
                            raf.seek(cumulative)
                            while (true) {
                                if (isCancelled()) throw CancellationException("HLS lane cancelled")
                                ensureActive()
                                val n = src.read(buf)
                                if (n < 0) break
                                if (n > 0) raf.write(buf, 0, n)
                                bytesThisSegment += max(0, n)
                            }
                        }
                    }
                    appended = true
                    if (bytesThisSegment > 0) {
                        // A partially-written previous attempt is overwritten
                        // from the water mark, so the file never forks.
                        cumulative += bytesThisSegment
                        sizesSeen.add(bytesThisSegment)
                        avgSegment = sizesSeen.sum() / sizesSeen.size
                        writeResume(sidecar, idx + 1, cumulative)
                        val spd = speedMeter.sample(cumulative)
                        val remaining = (segments.size - (idx + 1)).coerceAtLeast(0)
                        val total = if (estimatedTotalBytes > 0)
                            max(estimatedTotalBytes, cumulative)
                        else
                            max(cumulative + avgSegment * remaining, cumulative + 1)
                        val eta = if (spd > 0) (total - cumulative) * 1000L / spd else 0L
                        onProgress(cumulative, total, spd, eta)
                    }
                    break
                } catch (e: RateLimited) {
                    throw e
                } catch (ce: CancellationException) {
                    // State is already committed per-segment; propagate.
                    throw ce
                } catch (e: Exception) {
                    // Partial bytes past the water mark are overwritten by the
                    // retry (seek(cumulative) above), so integrity holds.
                    if (attemptNo == 3) {
                        throw java.io.IOException(
                            "HLS segment $idx failed after 3 attempts (status $lastStatus): ${e.message}"
                        )
                    }
                    delay(attemptNo * 800L)
                }
            }
            if (!appended) throw java.io.IOException("HLS segment $idx failed (status $lastStatus)")
        }
        dest
    }

    internal fun buildRequest(url: String, headers: Map<String, String>): Request {
        val b = Request.Builder().url(url)
        for ((k, v) in headers) {
            if (v.isBlank()) continue
            when (k.lowercase()) {
                "user-agent" -> b.header("User-Agent", v)
                "referer" -> b.header("Referer", v)
                "origin" -> b.header("Origin", v)
                "cookie" -> b.header("Cookie", v)
                "range" -> b.header("Range", v)
                else -> if (!k.contains(':')) b.header(k, v)
            }
        }
        if (headers.entries.none { it.key.equals("User-Agent", true) && it.value.isNotBlank() }) {
            b.header("User-Agent", com.anonrode.downloader.data.net.HttpClient.DEFAULT_UA)
        }
        return b.build()
    }

    // ──────────────────────────── engine-facing driver ────────────────────────────

    /**
     * One paced attempt, manifest to remuxed file. Returns the produced media
     * file, or null when the playlist is not a supported plain VOD (caller
     * falls back to yt-dlp). Throws [RateLimited] for the byte-limiter escape
     * (engine parks and resumes); other failures throw IOException.
     */
    suspend fun attempt(
        masterUrl: String,
        outPath: File,
        sidecar: File,
        headers: Map<String, String>,
        maxHeight: Int,
        estimatedTotalBytes: Long,
        onProgress: (downloaded: Long, total: Long, speed: Long, eta: Long) -> Unit,
        isCancelled: () -> Boolean
    ): File? = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            // ONE pooled connection by design: the limiter counts bytes per
            // window; a single keep-alive connection is the browser shape.
            .connectionPool(ConnectionPool(2, 5, TimeUnit.MINUTES))
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

        // Manifest lifecycle (1/2): master, fetched once.
        val masterBody = fetchText(client, masterUrl, headers) ?: return@withContext null
        val variantUrl = parseMaster(masterBody, masterUrl, maxHeight) ?: masterUrl
        // Manifest lifecycle (2/2): the chosen variant, fetched exactly once.
        val variantBody = if (variantUrl == masterUrl) masterBody
        else fetchText(client, variantUrl, headers) ?: return@withContext null
        val variant = parseVariant(variantBody, variantUrl)
        if (!variant.supported) return@withContext null

        com.anonrode.downloader.util.DebugLog.backend(
            "task=${outPath.nameWithoutExtension} paced HLS lane: ${variant.segments.size} segments, " +
                "%.0fs".format(variant.durationSec) + ", resume=${readResume(sidecar).segmentsDone}"
        )

        val staging = File(outPath.absolutePath + ".hts")
        try {
            downloadSegments(client, variant.segments, headers, staging, sidecar, estimatedTotalBytes, onProgress, isCancelled)
        } catch (e: Exception) {
            // Staged bytes + sidecar are intentionally kept for the resumed
            // attempt (byte-resume, not restart).
            throw e
        }

        // Remux: single -c copy pass (no re-encode), yt-dlp merge parity.
        val remuxed = remuxTo(staging, outPath)
        if (remuxed) {
            deleteResume(sidecar)
            try { staging.delete() } catch (_: Exception) {}
            outPath
        } else {
            null
        }
    }

    private suspend fun fetchText(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>
    ): String? = withContext(Dispatchers.IO) {
        try {
            client.newCall(buildRequest(url, headers)).execute().use { res ->
                if (!res.isSuccessful) return@use null
                res.body?.string()
            }
        } catch (_: Exception) {
            null
        }
    }

    // ═════════════════════════════ remux (ffmpeg) ═════════════════════════════

    /**
     * Resolve the youtubedl-android-extracted ffmpeg binary (same precedence
     * as InstagramPhotoMuxer: the extracted archive — whose versioned shared
     * libraries are complete — first, bare jniLibs fallback second).
     */
    internal fun resolveFfmpegBinary(context: android.content.Context): File? {
        try {
            com.yausername.ffmpeg.FFmpeg.getInstance().init(context)
        } catch (_: Throwable) {
        }
        val extracted = File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg")
        extracted.walkTopDown()
            .firstOrNull { it.isFile && it.name in setOf("libffmpeg.so", "libffmpeg.bin.so", "ffmpeg") }
            ?.let { return it }
        return File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").takeIf { it.isFile }
    }

    /**
     * Concat-demux `-c copy` remux of the staged segment stream into
     * [outPath]. Returns false (never throws) when ffmpeg is unavailable or
     * fails; the caller falls back to yt-dlp with the stage + sidecar intact
     * (progress preserved either way).
     */
    internal fun remuxTo(staging: File, outPath: File): Boolean {
        return try {
            val context = appContextRef?.get() ?: return false
            val ff = resolveFfmpegBinary(context) ?: return false
            val listFile = File(outPath.parentFile, outPath.nameWithoutExtension + ".concat.m3u")
            val escaped = staging.absolutePath.replace("'", "'\\''")
            listFile.writeText("ffconcat version 1.0\nfile '$escaped'\n")
            val tmpOut = File(outPath.absolutePath + ".remux")
            try { tmpOut.delete() } catch (_: Exception) {}
            val cmd = mutableListOf(
                ff.absolutePath,
                "-y", "-loglevel", "error",
                "-f", "concat", "-safe", "0",
                "-i", listFile.absolutePath,
                "-c", "copy",
                "-movflags", "+faststart",
                tmpOut.absolutePath
            )
            val pb = ProcessBuilder(cmd)
            pb.environment()["TMPDIR"] = outPath.parentFile?.absolutePath ?: "."
            val nativeDir = ff.parentFile?.absolutePath
            if (!nativeDir.isNullOrBlank()) {
                val existing = pb.environment()["LD_LIBRARY_PATH"]
                pb.environment()["LD_LIBRARY_PATH"] =
                    if (existing.isNullOrBlank()) nativeDir else "$nativeDir:$existing"
            }
            pb.redirectErrorStream(true)
            val p = pb.start()
            // Drain stdout so a chatty build cannot block on a full pipe.
            Thread {
                try { p.inputStream.readBytes() } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }
            val ok = p.waitFor(10, TimeUnit.MINUTES) && p.exitValue() == 0 && tmpOut.length() > 0
            try { p.destroyForcibly() } catch (_: Exception) {}
            try { listFile.delete() } catch (_: Exception) {}
            if (!ok) {
                try { tmpOut.delete() } catch (_: Exception) {}
                return false
            }
            if (outPath.exists()) try { outPath.delete() } catch (_: Exception) {}
            tmpOut.renameTo(outPath)
        } catch (_: Exception) {
            false
        }
    }

    /** Context hand-off set once by the engine (avoids threading a Context
     *  through every call signature; application-scoped, weakly held). */
    @Volatile
    internal var appContextRef: java.lang.ref.WeakReference<android.content.Context>? = null

    fun attachContext(context: android.content.Context) {
        appContextRef = java.lang.ref.WeakReference(context.applicationContext)
    }
}
