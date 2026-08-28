package com.anonrode.downloader.engine

import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class DownloadRepository {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }
    private var stateFile: File? = null

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    fun initPersistence(dir: File) {
        if (stateFile != null) return
        val f = File(dir, "download_tasks.json")
        stateFile = f
        try {
            if (f.exists()) {
                val raw = f.readText()
                if (raw.isNotBlank()) {
                _tasks.value = json.decodeFromString<List<DownloadTask>>(raw)
                    .map { parkForRestore(it) }
                }
            }
        } catch (_: Throwable) {
            try { f.delete() } catch (_: Throwable) {}
            _tasks.value = emptyList()
        }
    }

    private val persistMutex = kotlinx.coroutines.sync.Mutex()

    fun persist() {
        val f = stateFile ?: return
        scope.launch {
            persistMutex.withLock {
                try {
                    val encoded = json.encodeToString(_tasks.value)
                    val tmp = File(f.parentFile, "${f.name}.tmp")
                    tmp.writeText(encoded)
                    tmp.renameTo(f)
                } catch (_: Throwable) {}
            }
        }
    }

    fun snapshot(): List<DownloadTask> = _tasks.value

    fun find(taskId: String): DownloadTask? = _tasks.value.find { it.id == taskId }

    fun addFirst(task: DownloadTask) {
        _tasks.update { listOf(task) + it }
        persist()
    }

    fun remove(taskId: String) {
        _tasks.update { list -> list.filterNot { it.id == taskId } }
        persist()
    }

    fun update(taskId: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list ->
            list.map { if (it.id == taskId) transform(it) else it }
        }
        persist()
    }

    fun updateProgress(taskId: String, downloaded: Long, total: Long, speed: Double, eta: Long) {
        _tasks.update { list ->
            list.map {
                if (it.id == taskId) {
                    // First bytes landing flips RESOLVING -> DOWNLOADING here, in
                    // the SAME atomic write as the progress: some backends (Turbo
                    // direct downloads) report progress with no separate status
                    // event, so without this the card sits at "Resolving" with no
                    // bar while the notification already shows live percent.
                    val status = if (it.status == TaskStatus.RESOLVING && downloaded > 0) {
                        TaskStatus.DOWNLOADING
                    } else it.status
                    // Monotonic downloaded bytes: a late telemetry tick racing the
                    // COMPLETED write (or a stale tick after pause) must never
                    // regress the recorded size. Speed/ETA are only meaningful
                    // while actually transferring — writing them on a PAUSED or
                    // COMPLETED task would re-stamp a stale "0.5 MB/s".
                    val finalDl = if (downloaded > 0) maxOf(it.downloadedBytes, downloaded) else it.downloadedBytes
                    // Monotonic total for the same reason: a backend that emits
                    // a synthetic or degraded total late in a transfer (e.g. a
                    // percent-only fallback line) must not overwrite the real
                    // content length — the completion tiers compare file size
                    // against it.
                    val finalTot = if (total > it.totalBytes) total else it.totalBytes
                    val transferring = status == TaskStatus.DOWNLOADING
                    it.copy(
                        status = status,
                        downloadedBytes = finalDl,
                        totalBytes = finalTot,
                        speedBytesPerSec = if (transferring) speed else 0.0,
                        etaSeconds = if (transferring) eta else 0L
                    )
                } else it
            }
        }
    }
}
