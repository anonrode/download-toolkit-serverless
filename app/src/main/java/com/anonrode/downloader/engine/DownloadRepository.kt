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
                            if (it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.RESOLVING) {
                                it.copy(status = TaskStatus.QUEUED, speedBytesPerSec = 0.0)
                            } else it
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
                    it.copy(
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        speedBytesPerSec = speed,
                        etaSeconds = eta
                    )
                } else it
            }
        }
    }
}
