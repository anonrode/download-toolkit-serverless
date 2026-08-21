package com.anonrode.downloader.engine

import android.content.Context
import com.anonrode.downloader.security.TorrentSecurityShield
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import java.io.File

object YoutubeDlDownloader {

    // Task-level retry counts are now AppSettings-backed (pref_magnet_retries /
    // pref_ytdlp_retries), threaded in via download()'s maxAttempts params.

    // Trackers probed live (2026-08): the 3 dropped entries below were dead
    // (no reply / DNS fail); explodie.org and anirena.com verified alive.
    // anirena is an anime tracker -- relevant for this app's content.
    private val TIER1_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.bittor.pw:1337/announce",
        "udp://tracker.dler.org:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://explodie.org:6969/announce",
        "http://tracker.anirena.com:80/announce"
    ).joinToString(",")

    private fun parseByteString(str: String): Long {
        val clean = str.trim().uppercase()
        val numPart = clean.takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return 0L
        return when {
            clean.endsWith("GIB") || clean.endsWith("GB") -> (numPart * 1024 * 1024 * 1024).toLong()
            clean.endsWith("MIB") || clean.endsWith("MB") -> (numPart * 1024 * 1024).toLong()
            clean.endsWith("KIB") || clean.endsWith("KB") -> (numPart * 1024).toLong()
            clean.endsWith("B") -> numPart.toLong()
            else -> (numPart * 1024 * 1024).toLong()
        }
    }

    private fun parseSpeedString(str: String): Double {
        val clean = str.trim().uppercase()
        val numPart = clean.takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return 0.0
        return when {
            clean.contains("GB") || clean.contains("GIB") -> numPart * 1024 * 1024 * 1024
            clean.contains("MB") || clean.contains("MIB") -> numPart * 1024 * 1024
            clean.contains("KB") || clean.contains("KIB") -> numPart * 1024
            clean.contains("B/S") || clean.contains("BPS") -> numPart
            else -> numPart * 1024 * 1024
        }
    }

    suspend fun download(
        context: Context,
        taskId: String,
        sourceUrl: String,
        targetDir: File,
        preferredFilename: String,
        backend: String,
        referer: String = "",
        origin: String = "",
        ua: String = "",
        customHeaders: Map<String, String> = emptyMap(),
        parallelSockets: Int = 16,
        quality: String = "720p",
        isExtractorTask: Boolean = false,
        audioOnly: Boolean = false,
        onProgress: (downloaded: Long, total: Long, speed: Double, eta: Long) -> Unit,
        onTorrentFiles: (suspend (List<TorrentSecurityShield.TorrentFileEntry>) -> List<Int>?)? = null,
        // Tier-A settings (defaults = previous hardcoded behavior)
        magnetMaxAttempts: Int = 3,
        ytdlpMaxAttempts: Int = 3,
        hlsFragments: Int = -1,
        speedLimitKbs: Int = 0,
        torrentPeers: Int = -1
    ): File? {
        if (!targetDir.exists()) targetDir.mkdirs()

        val isMagnet = sourceUrl.startsWith("magnet:", ignoreCase = true)
        val isM3u8 = sourceUrl.lowercase().contains(".m3u8")

        val height = when (quality.lowercase()) {
            "480p", "480" -> 480
            "1080p", "1080" -> 1080
            "4k", "2160p" -> 2160
            else -> 720
        }

        if (isMagnet) {
            // Selective-file pass: probe the swarm for its file list; when more
            // than one safe file exists, ask the UI which to download. A single
            // safe file is auto-selected (no picker). Null/empty selection or a
            // failed probe falls back to the full download.
            var selectIndexes: List<Int>? = null
            if (onTorrentFiles != null) {
                val files = listTorrentFiles(context, sourceUrl, File(preferredFilename).nameWithoutExtension)
                if (files != null) {
                    val safe = files.filter { it.isSafe }
                    when {
                        safe.size > 1 -> selectIndexes = onTorrentFiles(files)
                        safe.size == 1 -> selectIndexes = listOf(safe.first().index)
                    }
                    if (safe.size != files.size) {
                        android.util.Log.w("AnonDownload",
                            "listTorrentFiles: ${files.size - safe.size} of ${files.size} entries blocked by shield")
                    }
                }
            }
            // isActiveCheck keeps the task-level retry loop from relaunching
            // aria2c after the user paused or cancelled the job.
            return downloadMagnetAria2c(
                context, taskId, sourceUrl, targetDir, preferredFilename, parallelSockets, onProgress,
                selectIndexes = selectIndexes,
                maxAttempts = magnetMaxAttempts,
                peersOverride = torrentPeers,
                speedLimitKbs = speedLimitKbs
            ) { coroutineContext.isActive }
        }

        val request = YoutubeDLRequest(sourceUrl).apply {
            if (isExtractorTask) {
                // True Monolith Metadata Naming Template with user-configured quality
                val outTemplate = File(targetDir, "%(uploader,creator,channel)s - %(title).80s [%(id)s].%(ext)s").absolutePath
                addOption("-o", outTemplate)
                if (audioOnly) {
                    addOption("-f", "bestaudio/best")
                    addOption("--extract-audio")
                    addOption("--audio-format", "mp3")
                } else {
                    addOption("-f", "bestvideo[height<=$height][ext=mp4]+bestaudio[ext=m4a]/best[height<=$height][ext=mp4]/best[height<=$height]/best")
                    addOption("-S", "height~$height,+size,+br")
                    addOption("--merge-output-format", "mp4")
                }
                addOption("--no-playlist")
                // Embed/watch-page cracks (nepu, social, etc.) often resolve to HLS.
                // Parallel fragments keep multi-socket speed instead of a single
                // rate-capped connection.
                val frags = if (hlsFragments > 0) hlsFragments.coerceIn(1, 16) else parallelSockets.coerceIn(4, 16)
                addOption("-N", "$frags")
                addOption("--concurrent-fragments", "$frags")
                addOption("--buffer-size", "1M")
                addOption("--http-chunk-size", "10M")
                addOption("--socket-timeout", "15")
                addOption("--retries", "10")
                addOption("--fragment-retries", "10")
                addOption("--retry-sleep", "10")
                addOption("--retry-sleep", "fragment:exp=1:20")
                // Fail loudly instead of letting yt-dlp skip missing fragments
                // and silently produce a video with gaps in it.
                addOption("--abort-on-unavailable-fragments")
                if (speedLimitKbs > 0) addOption("--limit-rate", "${speedLimitKbs}K")
            } else if (isM3u8) {
                // HLS m3u8 stream variant selection with multi-fragment parallel downloading
                val stem = File(targetDir, preferredFilename.substringBeforeLast('.')).absolutePath
                addOption("-o", "$stem.%(ext)s")
                addOption("-f", "bestvideo[height<=$height]+bestaudio/best[height<=$height]/best")
                addOption("-S", "height~$height,+size,+br")
                addOption("--merge-output-format", "mp4")
                addOption("--no-playlist")
                val frags = if (hlsFragments > 0) hlsFragments.coerceIn(1, 16) else parallelSockets.coerceIn(4, 16)
                addOption("-N", "$frags")
                addOption("--concurrent-fragments", "$frags")
                addOption("--buffer-size", "1M")
                addOption("--http-chunk-size", "10M")
                addOption("--socket-timeout", "15")
                addOption("--retries", "10")
                addOption("--fragment-retries", "10")
                addOption("--retry-sleep", "10")
                addOption("--retry-sleep", "fragment:exp=1:20")
                addOption("--abort-on-unavailable-fragments")
                if (speedLimitKbs > 0) addOption("--limit-rate", "${speedLimitKbs}K")
            } else {
                // Direct CDN HTTP multi-socket via aria2c
                val stem = File(targetDir, preferredFilename.substringBeforeLast('.')).absolutePath
                addOption("-o", "$stem.%(ext)s")
                addOption("--downloader", "libaria2c.so")
                val conns = parallelSockets.coerceIn(4, 16)
                val aria2Args = buildString {
                    // --max-tries/--retry-wait mirror the magnet path: without them
                    // aria2c hammers a flaky connection 5x with zero wait.
                    append("aria2c:-x $conns -s $conns -j $conns -k 1M --max-connection-per-server=$conns --split=$conns --min-split-size=1M --continue=true --max-tries=10 --retry-wait=1 --disk-cache=64M")
                    if (origin.isNotBlank()) append(" --header=\"Origin: $origin\"")
                    if (referer.isNotBlank()) append(" --header=\"Referer: $referer\"")
                    if (ua.isNotBlank()) append(" --header=\"User-Agent: $ua\"")
                    append(" --header=\"Accept: video/mp4,video/x-matroska,video/*,*/*\"")
                    append(" --check-certificate=false")
                    append(" --summary-interval=1")
                }
                addOption("--downloader-args", aria2Args)
            }

            addOption("--no-mtime")
            addOption("--no-warnings")
            addOption("--no-check-certificate")
            addOption("--newline")
            addOption("--progress")
            // Refuse outside config files: a stray yt-dlp.conf could override
            // -o/-f and silently break the output path (monolith parity).
            addOption("--ignore-config")

            if (referer.isNotBlank()) addOption("--referer", referer)
            if (ua.isNotBlank()) addOption("--user-agent", ua)

            // Origin header from the referer's base domain, like the monolith's
            // HLS path always sends it.
            val originToPass = origin.ifBlank {
                referer.takeIf { it.isNotBlank() }?.let { r ->
                    Regex("""https?://[^/]+""").find(r)?.value ?: ""
                } ?: ""
            }
            if (originToPass.isNotBlank()) addOption("--add-header", "Origin: $originToPass")

            for ((k, v) in customHeaders) {
                if (k.isNotBlank() && v.isNotBlank()) {
                    addOption("--add-header", "$k:$v")
                }
            }
        }

        val before = targetDir.listFiles()?.map { it.absolutePath }?.toSet() ?: emptySet()
        val stem = preferredFilename.substringBeforeLast('.')

        val errors = StringBuilder()
        var produced: File? = null
        var attempts = 0

        fun attemptOnce(): File? {
            var lastDl = 0L
            var lastTot = 0L

            try {
                YoutubeDL.getInstance().execute(request, taskId) { progress, etaInSeconds, line ->
                    if (progress >= 0f) {
                        var dlBytes = lastDl
                        var totBytes = lastTot
                        var spdBps = 0.0
                        val eta = if (etaInSeconds > 0) etaInSeconds else 0L

                        if (!line.isNullOrBlank()) {
                            // Check aria2c format: [#123456 45MiB/65MiB(69%) CN:4 DL:3.8MiB ETA:5s]
                            val ariaMatch = Regex("""\s*([\d.]+[KMGT]?i?B)/([\d.]+[KMGT]?i?B).*?DL:\s*([\d.]+[KMGT]?i?B(?:/s)?)""", RegexOption.IGNORE_CASE).find(line)
                            if (ariaMatch != null) {
                                dlBytes = parseByteString(ariaMatch.groupValues[1])
                                totBytes = parseByteString(ariaMatch.groupValues[2])
                                spdBps = parseSpeedString(ariaMatch.groupValues[3])
                            } else {
                                // Check yt-dlp format: [download]  45.2% of ~65.00MiB at 4.20MiB/s ETA 00:08
                                val ytdlMatch = Regex("""([\d.]+)%\s+of\s+~?([\d.]+[KMGT]?i?B).*?at\s+([\d.]+[KMGT]?i?B/s)""", RegexOption.IGNORE_CASE).find(line)
                                if (ytdlMatch != null) {
                                    val pct = ytdlMatch.groupValues[1].toDoubleOrNull() ?: progress.toDouble()
                                    totBytes = parseByteString(ytdlMatch.groupValues[2])
                                    dlBytes = if (totBytes > 0) (totBytes * (pct / 100.0)).toLong() else 0L
                                    spdBps = parseSpeedString(ytdlMatch.groupValues[3])
                                }
                            }
                        }

                        lastDl = dlBytes
                        lastTot = totBytes
                        onProgress(dlBytes, totBytes, spdBps, eta)
                    }
                }
            } catch (e: Exception) {
                errors.append("run ").append(attempts).append(": ").append(e.message ?: e.javaClass.simpleName).append('\n')
                return null
            }

            fun isFinal(f: File) = f.length() > 0 &&
                !f.name.endsWith(".aria2") && !f.name.endsWith(".part") && !f.name.endsWith(".ytdl")

            val candidates = targetDir.listFiles { f -> isFinal(f) }?.toList() ?: emptyList()

            val fresh = candidates.filter { it.absolutePath !in before }
            return fresh.firstOrNull { it.nameWithoutExtension == stem || it.name.startsWith("$stem.") }
                ?: fresh.maxByOrNull { it.lastModified() }
                ?: candidates.firstOrNull { it.nameWithoutExtension == stem || it.name.startsWith("$stem.") }
        }

        // Same-engine retry with resume: yt-dlp's .part (native) and aria2c's
        // .aria2 control file (external downloader) keep progress across runs,
        // so a retry continues instead of restarting. Never relaunch after the
        // job was paused or cancelled.
        while (attempts < ytdlpMaxAttempts && produced == null) {
            attempts++
            if (!coroutineContext.isActive) throw CancellationException("Task was cancelled before yt-dlp retry")
            produced = attemptOnce()
            if (produced == null && attempts < ytdlpMaxAttempts) {
                try {
                    Thread.sleep(2_000L * attempts)
                } catch (_: InterruptedException) {
                    throw InterruptedException("Task was cancelled during yt-dlp retry wait")
                }
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("Task was cancelled before yt-dlp retry")
                }
            }
        }
        if (produced == null && errors.isNotBlank()) {
            throw Exception("yt-dlp failed after $attempts attempt(s): ${errors.toString().trim()}")
        }
        return produced
    }

    private val activeNativeProcesses = java.util.concurrent.ConcurrentHashMap<String, Process>()

    fun killProcess(taskId: String) {
        try {
            YoutubeDL.getInstance().destroyProcessById(taskId)
        } catch (_: Exception) {}
        activeNativeProcesses.remove(taskId)?.let { p ->
            try {
                p.destroy()
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    p.destroyForcibly()
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Fetch a magnet's file list from the swarm WITHOUT downloading any data.
     *
     * Two phases (--show-files only reads real .torrent files, not magnets):
     *   1. `--bt-metadata-only --bt-save-metadata` fetches metadata and writes
     *      `<hex-infohash>.torrent` into a cache dir (no data downloaded).
     *   2. `--show-files <torrent>` prints the table; each entry is run through
     *      [TorrentSecurityShield.checkTorrentFileEntry] (Layers 2/6 + traversal),
     *      so blocked/unsafe files are flagged and never selectable.
     *
     * Returns null when metadata can't be fetched (dead swarm, timeout) — the
     * caller should fall back to a full download rather than fail the task.
     */
    suspend fun listTorrentFiles(
        context: Context,
        magnetUrl: String,
        parentTitle: String = ""
    ): List<TorrentSecurityShield.TorrentFileEntry>? {
        val aria2Exec = findAria2Executable(context)
            ?: throw IllegalStateException("aria2c binary missing: libaria2c.so not found in native libs")

        // ---- Phase 1: metadata -> .torrent (no data) ----
        val metaDir = File(context.cacheDir, "torrent_meta").apply { mkdirs() }
        val infoHash = Regex("""(?i)btih:([a-f0-9]{40})""").find(magnetUrl)?.groupValues?.get(1)
            ?: return null
        val torrentFile = File(metaDir, "$infoHash.torrent")

        // Reuse an already-cached .torrent when present (instant list, no swarm contact).
        if (!torrentFile.exists()) {
            val fetchCmd = mutableListOf(
                aria2Exec.absolutePath,
                "--enable-dht=true",
                "--bt-enable-lpd=true",
                "--enable-peer-exchange=true",
                "--dht-entry-point=router.bittorrent.com:6881",
                "--dht-entry-point=dht.transmissionbt.com:6881",
                "--dht-entry-point=dht.libtorrent.org:25401",
                "--dht-entry-point6=[2400:cb00:2049:1::a29f:9877]:6881",
                "--dht-file-path=${context.cacheDir.absolutePath}/dht.dat",
                "--seed-time=0",
                "--seed-ratio=0.0",
                "--summary-interval=0",
                "--bt-max-peers=64",
                "--bt-request-peer-speed-limit=50M",
                "--bt-stop-timeout=45",
                "--bt-tracker-connect-timeout=10",
                "--bt-tracker-timeout=10",
                "--bt-tracker=$TIER1_TRACKERS",
                "--bt-metadata-only=true",
                "--bt-save-metadata=true",
                "-d", metaDir.absolutePath,
                magnetUrl
            )
            val fetchPb = ProcessBuilder(fetchCmd)
            fetchPb.environment()["TMPDIR"] = context.cacheDir.absolutePath
            fetchPb.redirectErrorStream(true)
            val fetchProc = try {
                fetchPb.start()
            } catch (e: Exception) {
                android.util.Log.w("AnonDownload", "listTorrentFiles: launch failed: ${e.message}")
                return null
            }
            val fetchOk = withTimeoutOrNull(45_000) {
                fetchProc.waitFor()
            } ?: run {
                fetchProc.destroy()
                return null
            }
            if (fetchOk != 0 || !torrentFile.exists()) {
                android.util.Log.w("AnonDownload", "listTorrentFiles: metadata fetch failed (code=$fetchOk)")
                return null
            }
        }

        // ---- Phase 2: --show-files on the .torrent ----
        val showCmd = listOf(
            aria2Exec.absolutePath,
            "--show-files=true",
            torrentFile.absolutePath
        )
        val showPb = ProcessBuilder(showCmd)
        showPb.redirectErrorStream(true)
        val showProc = try {
            showPb.start()
        } catch (e: Exception) {
            android.util.Log.w("AnonDownload", "listTorrentFiles: show-files launch failed: ${e.message}")
            return null
        }
        val output = withTimeoutOrNull(15_000) {
            showProc.inputStream.bufferedReader().readText()
        } ?: run {
            showProc.destroy()
            return null
        }

        // aria2c --show-files prints two lines per entry (verified live):
        //   "  1|./Sintel/Sintel.de.srt"      then "   |1.6KiB (1,652)"
        val entries = mutableListOf<TorrentSecurityShield.TorrentFileEntry>()
        val pathRe = Regex("""^\s*(\d+)\|(.+)$""")
        val sizeRe = Regex("""\(\s*([\d,]+)\s*\)$""")
        val lines = output.lineSequence().toList()
        var i = 0
        while (i < lines.size) {
            val pm = pathRe.matchEntire(lines[i].trimEnd())
            if (pm != null) {
                val index = pm.groupValues[1].toInt()
                val path = pm.groupValues[2]
                var length = 0L
                if (i + 1 < lines.size) {
                    sizeRe.find(lines[i + 1])?.let { m ->
                        length = m.groupValues[1].replace(",", "").toLongOrNull() ?: 0L
                    }
                }
                val checked = TorrentSecurityShield.checkTorrentFileEntry(path, length, parentTitle)
                entries.add(checked.copy(index = index))
                i += 2
                continue
            }
            i += 1
        }

        if (entries.isEmpty()) {
            android.util.Log.w("AnonDownload", "listTorrentFiles: no entries parsed")
            return null
        }
        return entries
    }

    private suspend fun downloadMagnetAria2c(
        context: Context,
        taskId: String,
        magnetUrl: String,
        targetDir: File,
        preferredFilename: String,
        parallelSockets: Int = 16,
        onProgress: (downloaded: Long, total: Long, speed: Double, eta: Long) -> Unit,
        isActiveCheck: suspend () -> Boolean = { true },
        selectIndexes: List<Int>? = null,
        maxAttempts: Int = 3,
        peersOverride: Int = -1,
        speedLimitKbs: Int = 0
    ): File? {
        val before = targetDir.listFiles()?.map { it.absolutePath }?.toSet() ?: emptySet()

        val aria2Exec = findAria2Executable(context)
            ?: throw IllegalStateException("aria2c binary missing: libaria2c.so not found in native libs")
        val conns = parallelSockets
        val cmd = mutableListOf(
            aria2Exec.absolutePath,
            "--enable-dht=true",
            "--bt-enable-lpd=true",
            "--enable-peer-exchange=true",
            // Live-verified 2026-08: router.bittorrent.com does not answer DHT
            // pings from some networks; transmissionbt + libtorrent.org do.
            "--dht-entry-point=router.bittorrent.com:6881",
            "--dht-entry-point=dht.transmissionbt.com:6881",
            "--dht-entry-point=dht.libtorrent.org:25401",
            "--dht-entry-point6=[2400:cb00:2049:1::a29f:9877]:6881",
            // Android has no $HOME, so aria2c's default ~/.aria2/dht.dat never
            // persists: every launch cold-starts an empty DHT table. Pin it into
            // cacheDir so the routing table survives across downloads AND runs.
            "--dht-file-path=${context.cacheDir.absolutePath}/dht.dat",
            "--seed-time=0",
            "--seed-ratio=0.0",
            "--summary-interval=1",
            // RAM-tier override when the user set one; otherwise auto formula.
            "--bt-max-peers=${if (peersOverride > 0) peersOverride else (conns * 8).coerceIn(60, 500)}",
            "--bt-request-peer-speed-limit=50M",
            "--bt-stop-timeout=300",
            // Fail fast on dead trackers: 30s connect + 60s default announce
            // timeout burned a minute per dead tracker before.
            "--bt-tracker-connect-timeout=10",
            "--bt-tracker-timeout=10",
            "--file-allocation=falloc",
            "--check-certificate=false",
            "--continue=true",
            "--max-tries=10",
            "--retry-wait=1",
            "--follow-torrent=mem",
            "--bt-save-metadata=true",
            "--bt-load-saved-metadata=true",
            "--auto-save-interval=15",
            "--allow-overwrite=false",
            "--console-log-level=error",
            "--bt-tracker=$TIER1_TRACKERS",
            "-d", targetDir.absolutePath,
        ]
        // Selective download (season packs): --select-file takes only numeric
        // indexes, never names -- the swarm's file paths never reach the command
        // line, so there is no injection surface from untrusted torrent names.
        if (!selectIndexes.isNullOrEmpty()) {
            cmd += "--select-file=" + selectIndexes.joinToString(",")
        }
        if (speedLimitKbs > 0) {
            cmd += "--max-download-limit=${speedLimitKbs}K"
        }
        cmd += magnetUrl

        val errors = StringBuilder()
        var produced: File? = null
        var attempts = 0

        fun runOnce(): File? {
            val pb = ProcessBuilder(cmd)
            // Statically linked binary (see NOTICE): no LD_LIBRARY_PATH/PATH needed.
            pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
            pb.directory(targetDir)
            pb.redirectErrorStream(true)
            val process = pb.start()

            if (Thread.currentThread().isInterrupted) {
                process.destroy()
                throw InterruptedException("Task was cancelled before process started")
            }

            activeNativeProcesses[taskId] = process

            // aria2c summary lines put sizes before the percentage: [#d5f4b1 45MiB/65MiB(69%) CN:8 DL:3.8MiB ETA:5s]
            val progressRegex = Regex("""([\d.]+[KMGT]?i?B)/([\d.]+[KMGT]?i?B)\([\d.]+%\).*?DL:\s*([\d.]+[KMGT]?i?B(?:/s)?)""", RegexOption.IGNORE_CASE)
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
                    val match = progressRegex.find(line)
                    if (match != null) {
                        val dl = parseByteString(match.groupValues[1])
                        val tot = parseByteString(match.groupValues[2])
                        val spd = parseSpeedString(match.groupValues[3])
                        val eta = if (spd > 0 && tot > dl) ((tot - dl) / spd).toLong() else 0L
                        onProgress(dl, tot, spd, eta)
                    } else {
                        val fb = fallbackRegex.find(line)
                        if (fb != null) {
                            val pct = fb.groupValues[1].toLongOrNull() ?: 0L
                            val spd = parseSpeedString(fb.groupValues[2])
                            onProgress(pct, 100L, spd, 0L)
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

            fun isFinal(f: File) = f.length() > 0 &&
                    !f.name.endsWith(".aria2") && !f.name.endsWith(".part") &&
                    !f.name.endsWith(".ytdl") && !f.name.endsWith(".torrent")

            val candidates = targetDir.listFiles { f -> isFinal(f) }?.toList() ?: emptyList()
            val fresh = candidates.filter { it.absolutePath !in before }
            val stem = preferredFilename.substringBeforeLast('.')
            val found = fresh.firstOrNull { it.nameWithoutExtension == stem || it.name.startsWith("$stem.") }
                ?: fresh.maxByOrNull { it.lastModified() }
                ?: candidates.maxByOrNull { it.lastModified() }

            // A sibling .aria2 control file marks the data file as still partial
            // (resume bookkeeping), so never treat it as a finished download.
            if (found != null && !File(found.absolutePath + ".aria2").exists()) return found

            errors.append("run ").append(attempts).append(": no complete file (control file still present)\n")
            return null
        }

        // Task-level retry with resume: the .aria2 control file keeps aria2c's
        // per-piece progress across runs, so a retry continues instead of
        // restarting (BitTorrent piece-queue parity). Never relaunch after the
        // job was paused or cancelled.
        while (attempts < maxAttempts && produced == null) {
            attempts++
            if (!isActiveCheck()) throw CancellationException("Task was cancelled before magnet retry")
            produced = runOnce()
            if (produced == null && attempts < maxAttempts) {
                try {
                    Thread.sleep(2_000L * attempts)
                } catch (_: InterruptedException) {
                    throw InterruptedException("Task was cancelled during magnet retry wait")
                }
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("Task was cancelled before magnet retry")
                }
            }
        }
        if (produced == null) {
            throw Exception("aria2c failed after $attempts attempt(s): ${errors.toString().trim()}")
        }
        return produced
    }

    private fun findAria2Executable(context: Context): File? {
        val libFile = File(context.applicationInfo.nativeLibraryDir, "libaria2c.so")
        if (libFile.exists()) {
            try { libFile.setExecutable(true) } catch (_: Exception) {}
            return libFile
        }
        return null
    }
}
