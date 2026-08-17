package com.anonrode.downloader.engine

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

object YoutubeDlDownloader {

    private val LOCKER_HOSTS = listOf(
        "downloadwella.com",
        "wetafiles.com",
        "kissorgrab.com",
        "streamwish.",
        "sfastwish.",
        "vidhide."
    )

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

    fun isConnectionSensitive(url: String): Boolean {
        val lower = url.lowercase()
        return LOCKER_HOSTS.any { lower.contains(it) }
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
        onProgress: (Float) -> Unit
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
                    addOption("--merge-output-format", "mp4")
                }
                addOption("--no-playlist")
            } else if (isM3u8) {
                // HLS m3u8 stream variant selection based on user quality setting
                val stem = File(targetDir, preferredFilename.substringBeforeLast('.')).absolutePath
                addOption("-o", "$stem.%(ext)s")
                addOption("-f", "best[height<=$height]/best")
                addOption("--no-playlist")
            } else {
                // Direct CDN HTTP multi-socket via aria2c
                val stem = File(targetDir, preferredFilename.substringBeforeLast('.')).absolutePath
                addOption("-o", "$stem.%(ext)s")
                addOption("--downloader", "libaria2c.so")
                val conns = if (isConnectionSensitive(sourceUrl)) 1 else parallelSockets.coerceIn(1, 16)
                val aria2Args = buildString {
                    append("aria2c:-x $conns -s $conns --min-split-size=1M --continue=true --disk-cache=32M")
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

        // Snapshot the folder BEFORE downloading. The target dir is shared across
        // a show's episodes, so "newest file in the folder" (the old logic) would
        // return a previously-downloaded episode when this download failed, or let
        // two concurrent downloads grab each other's files -- both marked COMPLETED
        // pointing at the wrong video.
        val before = targetDir.listFiles()?.map { it.absolutePath }?.toSet() ?: emptySet()

        val stem = preferredFilename.substringBeforeLast('.')

        YoutubeDL.getInstance().execute(request, taskId) { progress, _, _ ->
            if (progress >= 0f) {
                onProgress(progress)
            }
        }

        fun isFinal(f: File) = f.length() > 0 &&
            !f.name.endsWith(".aria2") && !f.name.endsWith(".part") && !f.name.endsWith(".ytdl")

        val candidates = targetDir.listFiles { f -> isFinal(f) }?.toList() ?: emptyList()

        // Prefer a genuinely NEW file (not present before this run). If the run
        // overwrote an existing name (resume), fall back to one matching our stem,
        // then to the newest new file. Never silently return an unrelated episode.
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
        onProgress: (Float) -> Unit
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

        val progressRegex = Regex("""\((\d+)%\).*?DL:\s*([\d.]+[KMGT]?i?B)""", RegexOption.IGNORE_CASE)
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
                    val pct = match.groupValues[1].toFloatOrNull() ?: 0f
                    onProgress(pct)
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
        if (libDir.exists()) return libDir

        val binDir = File(context.filesDir, "bin/aria2c")
        if (binDir.exists()) return binDir

        val packagesDir = File(context.filesDir, "packages/aria2c/usr/bin/aria2c")
        if (packagesDir.exists()) return packagesDir

        return libDir
    }
}
