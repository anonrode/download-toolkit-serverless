package com.anonrode.downloader.engine

import android.os.Environment
import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID

class DownloadEngine private constructor() {

    val repository = DownloadRepository()
    val tasks: StateFlow<List<DownloadTask>> = repository.tasks

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeJobs = mutableMapOf<String, Job>()

    var maxConcurrentDownloads: Int = 2
    var parallelSocketsPerFile: Int = 16
    var defaultQuality: String = "720p"
    var autoOrganizeByShow: Boolean = true
    var instantSocialDownload: Boolean = false

    fun initPersistence(dir: File) {
        repository.initPersistence(dir)
    }

    fun enqueue(
        showTitle: String,
        episodeNum: Int,
        episodeTitle: String,
        sourceUrl: String,
        isDirect: Boolean = true,
        headers: Map<String, String> = emptyMap(),
        backend: String = "aria2c",
        parallelSockets: Int = 16
    ): String {
        val taskId = UUID.randomUUID().toString()
        val baseDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Anon")
        val folder = if (autoOrganizeByShow && showTitle.isNotBlank() && showTitle != "Social") {
            File(baseDir, sanitizeFilename(showTitle))
        } else baseDir
        folder.mkdirs()

        val filename = if (showTitle == "Social") {
            "Social_${System.currentTimeMillis()}.mp4"
        } else {
            "${sanitizeFilename(showTitle)}_E${String.format("%02d", episodeNum)}.mp4"
        }
        val targetFile = File(folder, filename)

        val task = DownloadTask(
            id = taskId,
            showTitle = showTitle,
            episodeNum = episodeNum,
            episodeTitle = episodeTitle,
            directUrl = sourceUrl,
            status = TaskStatus.QUEUED,
            filePath = targetFile.absolutePath,
            backend = backend,
            parallelSockets = parallelSockets,
            headers = headers
        )

        repository.addFirst(task)
        pumpQueue()
        return taskId
    }

    fun retry(taskId: String) {
        val task = repository.find(taskId) ?: return
        if (task.status == TaskStatus.DOWNLOADING) return

        repository.update(taskId) {
            it.copy(
                status = TaskStatus.QUEUED,
                downloadedBytes = 0L,
                speedBytesPerSec = 0.0,
                errorMessage = null
            )
        }
        pumpQueue()
    }

    fun pause(taskId: String) {
        activeJobs[taskId]?.cancel()
        activeJobs.remove(taskId)
        YoutubeDlDownloader.cancel(taskId)

        repository.update(taskId) {
            it.copy(status = TaskStatus.PAUSED, speedBytesPerSec = 0.0)
        }
        repository.persist()
        pumpQueue()
    }

    fun cancel(taskId: String) {
        pause(taskId)
        repository.remove(taskId)
    }

    private fun pumpQueue() {
        val currentDownloading = repository.snapshot().count { it.status == TaskStatus.DOWNLOADING }
        val availableSlots = maxConcurrentDownloads - currentDownloading
        if (availableSlots <= 0) return

        val queued = repository.snapshot().filter { it.status == TaskStatus.QUEUED }.take(availableSlots)
        queued.forEach { startDownload(it) }
    }

    private fun startDownload(task: DownloadTask) {
        if (activeJobs.containsKey(task.id)) return

        val job = scope.launch {
            repository.update(task.id) {
                it.copy(status = TaskStatus.DOWNLOADING, speedBytesPerSec = 0.0, errorMessage = null)
            }

            var lastBytes = 0L
            var lastTime = System.currentTimeMillis()

            try {
                YoutubeDlDownloader.download(
                    taskId = task.id,
                    sourceUrl = task.directUrl,
                    targetFilePath = task.filePath,
                    headers = task.headers,
                    backend = task.backend,
                    parallelSockets = task.parallelSockets,
                    onProgress = { percent ->
                        val now = System.currentTimeMillis()
                        val total = YoutubeDlDownloader.scaleTotal()
                        val downloaded = YoutubeDlDownloader.scaleDownloaded(percent)

                        val dt = (now - lastTime).coerceAtLeast(1L)
                        val dBytes = (downloaded - lastBytes).coerceAtLeast(0L)
                        val speed = (dBytes.toDouble() / dt) * 1000.0

                        lastBytes = downloaded
                        lastTime = now

                        repository.update(task.id) {
                            it.copy(
                                downloadedBytes = downloaded,
                                totalBytes = total,
                                speedBytesPerSec = speed
                            )
                        }
                    }
                )

                repository.update(task.id) {
                    it.copy(
                        status = TaskStatus.COMPLETED,
                        downloadedBytes = YoutubeDlDownloader.scaleTotal(),
                        totalBytes = YoutubeDlDownloader.scaleTotal(),
                        speedBytesPerSec = 0.0
                    )
                }
                repository.persist()
            } catch (e: Exception) {
                if (isActive) {
                    repository.update(task.id) {
                        it.copy(
                            status = TaskStatus.FAILED,
                            speedBytesPerSec = 0.0,
                            errorMessage = e.message ?: "Download failed"
                        )
                    }
                    repository.persist()
                }
            } finally {
                activeJobs.remove(task.id)
                pumpQueue()
            }
        }

        activeJobs[task.id] = job
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[\\/:*?\"<>|]"), "_").trim()
    }

    companion object {
        val instance: DownloadEngine by lazy { DownloadEngine() }
    }
}
