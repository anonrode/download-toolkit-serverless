package com.anonrode.downloader.engine

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

object YoutubeDlDownloader {

    private val TIER1_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://tracker.bittor.pw:1337/announce",
        "udp://public.popcorn-tracker.org:6969/announce",
        "udp://tracker.dler.org:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://open.demonii.com:1337/announce",
        "http://tracker.openbittorrent.com:80/announce",
        "udp://tracker.openbittorrent.com:6969/announce"
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
        onProgress: (downloaded: Long, total: Long, speed: Double, eta: Long) -> Unit
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
            return downloadMagnetAria2c(context, taskId, sourceUrl, targetDir, preferredFilename, onProgress)
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
                val frags = parallelSockets.coerceIn(4, 16)
                addOption("-N", "$frags")
                addOption("--concurrent-fragments", "$frags")
                addOption("--buffer-size", "1M")
                addOption("--http-chunk-size", "10M")
            } else if (isM3u8) {
                // HLS m3u8 stream variant selection with multi-fragment parallel downloading
                val stem = File(targetDir, preferredFilename.substringBeforeLast('.')).absolutePath
                addOption("-o", "$stem.%(ext)s")
                addOption("-f", "best[height<=$height]/best")
                addOption("-S", "height~$height,+size,+br")
                addOption("--no-playlist")
                val frags = parallelSockets.coerceIn(4, 16)
                addOption("-N", "$frags")
                addOption("--concurrent-fragments", "$frags")
                addOption("--buffer-size", "1M")
                addOption("--http-chunk-size", "10M")
            } else {
                // Direct CDN HTTP multi-socket via aria2c
                val stem = File(targetDir, preferredFilename.substringBeforeLast('.')).absolutePath
                addOption("-o", "$stem.%(ext)s")
                addOption("--downloader", "libaria2c.so")
                val conns = parallelSockets.coerceIn(4, 16)
                val aria2Args = buildString {
                    append("aria2c:-x $conns -s $conns -j $conns -k 1M --max-connection-per-server=$conns --split=$conns --min-split-size=1M --continue=true --disk-cache=64M")
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

            if (referer.isNotBlank()) addOption("--referer", referer)
            if (ua.isNotBlank()) addOption("--user-agent", ua)

            for ((k, v) in customHeaders) {
                if (k.isNotBlank() && v.isNotBlank()) {
                    addOption("--add-header", "$k:$v")
                }
            }
        }

        val before = targetDir.listFiles()?.map { it.absolutePath }?.toSet() ?: emptySet()
        val stem = preferredFilename.substringBeforeLast('.')

        var lastDl = 0L
        var lastTot = 0L

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

        fun isFinal(f: File) = f.length() > 0 &&
            !f.name.endsWith(".aria2") && !f.name.endsWith(".part") && !f.name.endsWith(".ytdl")

        val candidates = targetDir.listFiles { f -> isFinal(f) }?.toList() ?: emptyList()

        val fresh = candidates.filter { it.absolutePath !in before }
        return fresh.firstOrNull { it.nameWithoutExtension == stem || it.name.startsWith("$stem.") }
            ?: fresh.maxByOrNull { it.lastModified() }
            ?: candidates.firstOrNull { it.nameWithoutExtension == stem || it.name.startsWith("$stem.") }
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

    private fun downloadMagnetAria2c(
        context: Context,
        taskId: String,
        magnetUrl: String,
        targetDir: File,
        preferredFilename: String,
        onProgress: (downloaded: Long, total: Long, speed: Double, eta: Long) -> Unit
    ): File? {
        val before = targetDir.listFiles()?.map { it.absolutePath }?.toSet() ?: emptySet()

        val aria2Exec = findAria2Executable(context)
        val cmd = mutableListOf(
            aria2Exec.absolutePath,
            "--enable-dht=true",
            "--bt-enable-lpd=true",
            "--enable-peer-exchange=true",
            "--dht-entry-point=router.bittorrent.com:6881",
            "--seed-time=0",
            "--seed-ratio=0.0",
            "--summary-interval=1",
            "--bt-max-peers=80",
            "--file-allocation=none",
            "--check-certificate=false",
            "--continue=true",
            "--follow-torrent=mem",
            "--bt-save-metadata=false",
            "--bt-tracker=$TIER1_TRACKERS",
            "-d", targetDir.absolutePath,
            magnetUrl
        )

        val pb = ProcessBuilder(cmd)
        try {
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            pb.environment()["LD_LIBRARY_PATH"] = nativeLibDir
            pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
            val currentPath = System.getenv("PATH") ?: ""
            pb.environment()["PATH"] = "$nativeLibDir:$currentPath"
        } catch (_: Exception) {}
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
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                val errSummary = logBuffer.takeLast(3).joinToString(" | ")
                throw Exception("aria2c failed (code $exitCode): $errSummary")
            }
        } catch (e: Exception) {
            process.destroy()
            throw e
        } finally {
            activeNativeProcesses.remove(taskId)
        }

        fun isFinal(f: File) = f.length() > 0 &&
                !f.name.endsWith(".aria2") && !f.name.endsWith(".part") && !f.name.endsWith(".ytdl")

        val candidates = targetDir.listFiles { f -> isFinal(f) }?.toList() ?: emptyList()
        val fresh = candidates.filter { it.absolutePath !in before }
        val stem = preferredFilename.substringBeforeLast('.')
        return fresh.firstOrNull { it.nameWithoutExtension == stem || it.name.startsWith("$stem.") }
            ?: fresh.maxByOrNull { it.lastModified() }
            ?: candidates.maxByOrNull { it.lastModified() }
    }

    private fun findAria2Executable(context: Context): File {
        val libDir = File(context.applicationInfo.nativeLibraryDir, "libaria2c.so")
        if (libDir.exists()) {
            try { libDir.setExecutable(true) } catch (_: Exception) {}
            return libDir
        }

        val binDir = File(context.filesDir, "bin/aria2c")
        if (binDir.exists()) {
            try { binDir.setExecutable(true) } catch (_: Exception) {}
            return binDir
        }

        val packagesDir = File(context.filesDir, "packages/aria2c/usr/bin/aria2c")
        if (packagesDir.exists()) {
            try { packagesDir.setExecutable(true) } catch (_: Exception) {}
            return packagesDir
        }

        return libDir
    }
}
