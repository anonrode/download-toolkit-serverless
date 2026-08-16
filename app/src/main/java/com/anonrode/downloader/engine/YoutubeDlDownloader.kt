package com.anonrode.downloader.engine

import android.content.Context
import com.yaedd.youtubedl_android.YoutubeDL
import com.yaedd.youtubedl_android.YoutubeDLRequest
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

    fun isConnectionSensitive(url: String): Boolean {
        val lower = url.lowercase()
        return LOCKER_HOSTS.any { lower.contains(it) }
    }

    suspend fun download(
        context: Context,
        taskId: String,
        sourceUrl: String,
        targetFile: File,
        backend: String,
        referer: String = "",
        origin: String = "",
        ua: String = "",
        customHeaders: Map<String, String> = emptyMap(),
        parallelSockets: Int = 16,
        isExtractorTask: Boolean = false,
        onProgress: (Float) -> Unit
    ) {
        val outDir = targetFile.parentFile ?: File(context.filesDir, "downloads")
        if (!outDir.exists()) outDir.mkdirs()

        val stem = File(outDir, targetFile.nameWithoutExtension).absolutePath
        val target = targetFile

        val request = YoutubeDLRequest(sourceUrl).apply {
            addOption("-o", "$stem.%(ext)s")
            addOption("--no-playlist")
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

            if (isExtractorTask) {
                // Social / HLS / Extractor mode: native yt-dlp extraction + ffmpeg muxing
                addOption("-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best")
                addOption("--merge-output-format", "mp4")
            } else {
                // Direct CDN file mode: use aria2c
                addOption("--downloader", "libaria2c.so")
                val conns = if (isConnectionSensitive(sourceUrl)) 1 else parallelSockets.coerceIn(1, 16)
                val aria2Args = buildString {
                    append("aria2c:-x $conns -s $conns --min-split-size=1M --continue=true")
                    if (origin.isNotBlank()) append(" --header=\"Origin: $origin\"")
                    append(" --header=\"Accept: video/mp4,video/x-matroska,video/*,*/*\"")
                    append(" --check-certificate=false")
                    append(" --summary-interval=1")
                }
                addOption("--downloader-args", aria2Args)
            }
        }

        YoutubeDL.getInstance().execute(request, taskId) { progress, _, _ ->
            if (progress >= 0f) {
                onProgress(progress)
            }
        }

        // Ensure output file exists and is normalized
        if (!target.exists()) {
            val produced = target.parentFile
                ?.listFiles { f ->
                    f.name.startsWith(File(stem).name) &&
                    !f.name.endsWith(".aria2") &&
                    !f.name.endsWith(".part") &&
                    !f.name.endsWith(".ytdl")
                }
                ?.maxByOrNull { it.length() }
            if (produced != null && produced.absolutePath != target.absolutePath) {
                produced.renameTo(target)
            }
        }
    }
}
