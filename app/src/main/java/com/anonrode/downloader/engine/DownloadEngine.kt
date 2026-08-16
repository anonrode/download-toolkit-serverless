package com.anonrode.downloader.engine

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus
import com.anonrode.downloader.resolvers.ResolverRegistry
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID

class DownloadEngine(
    private val context: Context,
    private val repository: DownloadRepository
) {
    val tasks: StateFlow<List<DownloadTask>> = repository.tasks

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeJobs = mutableMapOf<String, Job>()

    var maxConcurrentDownloads = 3
    var parallelSocketsPerFile = 16
    var defaultQuality = "720p"
    var autoOrganizeByShow = true
    var instantSocialDownload = true

    init {
        repository.initPersistence(context.filesDir)
        scope.launch {
            processQueue()
        }
    }

    fun enqueue(
        showTitle: String,
        episodeNum: Int,
        episodeTitle: String,
        sourceUrl: String,
        isDirect: Boolean,
        backend: String = "aria2c",
        parallelSockets: Int = 16
    ): String {
        val taskId = UUID.randomUUID().toString()
        val downloadFolder = getDownloadDirectory(showTitle)

        val cleanTitle = episodeTitle.replace(Regex("""[^a-zA-Z0-9._ -]"""), "_").trim()
        val filename = if (backend == "yt-dlp" || !isDirect) {
            "${cleanTitle}.mp4"
        } else {
            "${cleanTitle}.mkv"
        }
        val targetFile = File(downloadFolder, filename)

        val task = DownloadTask(
            id = taskId,
            showTitle = showTitle,
            episodeNum = episodeNum,
            episodeTitle = episodeTitle,
            directUrl = sourceUrl,
            filePath = targetFile.absolutePath,
            status = TaskStatus.QUEUED,
            downloadedBytes = 0L,
            totalBytes = 100L,
            speedBytesPerSec = 0.0,
            backend = backend,
            parallelSockets = parallelSockets
        )

        repository.addFirst(task)
        processQueue()
        return taskId
    }

    fun pause(taskId: String) {
        activeJobs[taskId]?.cancel()
        activeJobs.remove(taskId)
        try {
            YoutubeDL.getInstance().destroyProcessById(taskId)
        } catch (_: Exception) {}
        repository.update(taskId) { it.copy(status = TaskStatus.PAUSED, speedBytesPerSec = 0.0) }
        processQueue()
    }

    fun cancel(taskId: String) {
        activeJobs[taskId]?.cancel()
        activeJobs.remove(taskId)
        try {
            YoutubeDL.getInstance().destroyProcessById(taskId)
        } catch (_: Exception) {}
        repository.remove(taskId)
        processQueue()
    }

    fun retry(taskId: String) {
        repository.update(taskId) { it.copy(status = TaskStatus.QUEUED, errorMessage = null) }
        processQueue()
    }

    private fun getDownloadDirectory(showTitle: String): File {
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val base = File(root, "Anon")
        val dest = if (autoOrganizeByShow && showTitle.isNotBlank() && showTitle != "Social") {
            val safe = showTitle.replace(Regex("""[^a-zA-Z0-9.-]"""), "_")
            File(base, safe)
        } else {
            base
        }
        if (!dest.exists()) dest.mkdirs()
        return dest
    }

    @Synchronized
    private fun processQueue() {
        val currentTasks = tasks.value
        val activeCount = currentTasks.count { it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.RESOLVING }

        if (activeCount >= maxConcurrentDownloads) return

        val queued = currentTasks.firstOrNull { it.status == TaskStatus.QUEUED } ?: return
        startTask(queued)
    }

    private fun startTask(task: DownloadTask) {
        val job = scope.launch {
            try {
                var streamUrl = task.directUrl

                if (task.backend != "yt-dlp") {
                    repository.update(task.id) { it.copy(status = TaskStatus.RESOLVING) }
                    val resolved = ResolverRegistry.resolve(task.directUrl, defaultQuality)
                    if (resolved.isNullOrBlank()) {
                        repository.update(task.id) { it.copy(status = TaskStatus.FAILED, errorMessage = "Could not resolve stream link") }
                        processQueue()
                        return@launch
                    }
                    streamUrl = resolved
                }

                repository.update(task.id) { it.copy(status = TaskStatus.DOWNLOADING) }

                val file = File(task.filePath)
                val isExtractor = task.backend == "yt-dlp"

                YoutubeDlDownloader.download(
                    context = context,
                    taskId = task.id,
                    sourceUrl = streamUrl,
                    targetFile = file,
                    backend = task.backend,
                    referer = "",
                    parallelSockets = if (task.backend == "yt-dlp") 1 else task.parallelSockets,
                    isExtractorTask = isExtractor,
                    onProgress = { progressFloat ->
                        val pct = progressFloat.toLong().coerceIn(0L, 100L)
                        repository.update(task.id) { it.copy(downloadedBytes = pct, totalBytes = 100L) }
                    }
                )

                val actualFile = if (file.exists()) file else {
                    file.parentFile?.listFiles { f ->
                        f.name.startsWith(file.nameWithoutExtension) &&
                        !f.name.endsWith(".aria2") &&
                        !f.name.endsWith(".part") &&
                        !f.name.endsWith(".ytdl")
                    }?.maxByOrNull { it.length() } ?: file
                }

                if (actualFile.exists() && actualFile.length() > 1024) {
                    repository.update(task.id) { it.copy(downloadedBytes = 100L, totalBytes = 100L, status = TaskStatus.COMPLETED) }

                    try {
                        MediaScannerConnection.scanFile(
                            context,
                            arrayOf(actualFile.absolutePath),
                            arrayOf("video/*"),
                            null
                        )
                    } catch (_: Exception) {}
                } else {
                    repository.update(task.id) { it.copy(status = TaskStatus.FAILED, errorMessage = "Output file was empty") }
                }
            } catch (e: CancellationException) {
                // Cancelled
            } catch (e: Exception) {
                repository.update(task.id) { it.copy(status = TaskStatus.FAILED, errorMessage = e.message ?: "Download error") }
            } finally {
                activeJobs.remove(task.id)
                processQueue()
            }
        }

        activeJobs[task.id] = job
    }
}
