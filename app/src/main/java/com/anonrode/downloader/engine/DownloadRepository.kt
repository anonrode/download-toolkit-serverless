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
                    .map {
                        // Reopen never auto-resumes, period. Mid-flight statuses
                        // (DOWNLOADING/RESOLVING/VALIDATING) park as PAUSED:
                        // silently re-consuming mobile data on reopen
                        // (user-reported) is the wrong surprise. VALIDATING is
                        // included deliberately — a pause landing during the
                        // integrity check (which now does real work: atom
                        // scans, decoder probes) must not resurrect as QUEUED,
                        // and the file on disk means a manual resume
                        // re-validates in seconds. errorMessage is cleared for
                        // every parked task: the network observer auto-resumes
                        // tasks carrying NETWORK_PAUSE_MESSAGE / "Waiting for
                        // Wi-Fi" markers, and those markers survived a restart
                        // — so a download parked right before the app died
                        // silently resumed on reopen (user-reported). Reopen
                        // never auto-resumes; in-session network recovery
                        // still works.
                        when (it.status) {
                            // QUEUED joins the parked set: an enqueue that
                            // never got to start must not survive a restart as
                            // QUEUED — the network observer's first emission
                            // (processQueue within ~32ms of app open) would
                            // otherwise auto-start it and drain data while the
                            // user is in another app (user-reported v3.0.4).
                            TaskStatus.DOWNLOADING, TaskStatus.RESOLVING, TaskStatus.VALIDATING, TaskStatus.QUEUED ->
                                it.copy(status = TaskStatus.PAUSED, speedBytesPerSec = 0.0, errorMessage = null)
                            TaskStatus.PAUSED ->
                                it.copy(errorMessage = null)
                            else -> it
                        }
                    }
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
                    // Monotonic downloaded bytes: a late telemetry tick racing the
                    // COMPLETED write (or a stale tick after pause) must never
                    // regress the recorded size. Speed/ETA are only meaningful
                    // while actually transferring — writing them on a PAUSED or
                    // COMPLETED task would re-stamp a stale "0.5 MB/s".
                    val finalDl = if (downloaded > 0) maxOf(it.downloadedBytes, downloaded) else it.downloadedBytes
                    val finalTot = if (total > 0) total else it.totalBytes
                    val transferring = it.status == TaskStatus.DOWNLOADING
                    it.copy(
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
