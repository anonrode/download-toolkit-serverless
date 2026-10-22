package com.anonrode.downloader.data

import android.content.Context
import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus
import com.anonrode.downloader.util.ShowIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persistent watch history (owner directive, Phase 1).
 *
 * Stores in-progress and permanently-watched entries in
 * `filesDir/watch_history.json` using the same atomic pattern as
 * [com.anonrode.downloader.engine.DownloadRepository]: encode → `.tmp` →
 * rename (direct rewrite if rename fails), corrupt files quarantined as
 * `.corrupt`. All policy lives in [WatchHistoryCore]; this object only adds
 * storage, the reactive flow and context bootstrapping.
 *
 * Layering note: this data-layer object deliberately does NOT import
 * `PlaybackPositions` (ui/components). [syncWithTasks] takes a position
 * provider; the app passes `PlaybackPositions::get` at wiring time.
 */
object WatchHistoryStore {

    private const val FILE_NAME = "watch_history.json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val persistMutex = Mutex()
    private val lock = Any()

    private var stateFile: File? = null

    private val _records = MutableStateFlow<List<WatchRecord>>(emptyList())
    val recordsFlow: StateFlow<List<WatchRecord>> = _records.asStateFlow()

    /** One-shot: loads history from [context]'s filesDir. */
    @Synchronized
    fun init(context: Context) {
        if (stateFile != null) return
        val f = File(context.applicationContext.filesDir, FILE_NAME)
        stateFile = f
        val loaded = try {
            if (f.exists() && f.length() > 0) {
                json.decodeFromString<List<WatchRecord>>(f.readText())
            } else {
                emptyList()
            }
        } catch (_: Throwable) {
            try { f.renameTo(File(f.parentFile, f.name + ".corrupt")) } catch (_: Throwable) {}
            emptyList()
        }
        _records.value = WatchHistoryCore.trim(loaded)
    }

    /** Seeds history from existing completed tasks that already have a saved
     *  playback position. Safe to call repeatedly; never duplicates a file. */
    fun syncWithTasks(
        tasks: List<DownloadTask>,
        positionFor: (String) -> Pair<Long, Long>? = { _ -> null }
    ) {
        if (stateFile == null) return
        mutate { records ->
            val known = records.mapTo(mutableSetOf()) { it.filePath.ifBlank { it.id } }
            val now = System.currentTimeMillis()
            val seeds = tasks.mapNotNull { task ->
                if (task.status != TaskStatus.COMPLETED || task.filePath.isBlank()) return@mapNotNull null
                if (task.filePath in known) return@mapNotNull null
                val pos = positionFor(task.filePath) ?: return@mapNotNull null
                WatchHistoryCore.seedFromTask(task)?.copy(
                    positionMs = pos.first,
                    durationMs = pos.second,
                    updatedAtMs = now
                )
            }
            if (seeds.isEmpty()) records
            else WatchHistoryCore.trim(seeds + records)
        }
    }

    /**
     * Player hook: called on the 5 s position poll, on file switch and on
     * dismiss. [task] may be null when the player only knows the file path.
     */
    fun recordProgress(
        task: DownloadTask?,
        filePath: String,
        title: String,
        positionMs: Long,
        durationMs: Long
    ) {
        if (stateFile == null || filePath.isBlank()) return
        val seed = seedFor(task, filePath, title)
        mutate { records ->
            WatchHistoryCore.recordProgress(
                records = records,
                seed = seed,
                positionMs = positionMs,
                durationMs = durationMs,
                nowMs = System.currentTimeMillis()
            )
        }
    }

    /** Player hook for [androidx.media3.common.Player.STATE_ENDED]; MUST be
     *  called before `PlaybackPositions.clear` so completion is recorded. */
    fun markEnded(
        task: DownloadTask?,
        filePath: String,
        title: String,
        durationMs: Long
    ) {
        if (stateFile == null || filePath.isBlank()) return
        val seed = seedFor(task, filePath, title)
        mutate { records ->
            WatchHistoryCore.markEnded(
                records = records,
                seed = seed,
                durationMs = durationMs,
                nowMs = System.currentTimeMillis()
            )
        }
    }

    /**
     * Applies deletions.
     *
     * - Watch-only deletion: pass the deleted file paths and
     *   `clearShowHistory = false`; watched entries survive with `filePath=""`.
     * - Entire-show deletion: also pass the affected show keys and
     *   `clearShowHistory = true`; every record for those shows is removed.
     */
    fun onFilesDeleted(
        deletedPaths: Set<String>,
        deletedShowKeys: Set<String> = emptySet(),
        clearShowHistory: Boolean
    ) {
        if (stateFile == null || (deletedPaths.isEmpty() && deletedShowKeys.isEmpty())) return
        mutate { records ->
            WatchHistoryCore.onFilesDeleted(
                records = records,
                deletedPaths = deletedPaths,
                deletedShowKeys = deletedShowKeys,
                clearShowHistory = clearShowHistory
            )
        }
    }

    // --------------------------------------------------------------- internals

    private fun seedFor(task: DownloadTask?, filePath: String, title: String): WatchRecord {
        val fromTask = task?.let { WatchHistoryCore.seedFromTask(it) }
        if (fromTask != null) {
            return fromTask.copy(
                id = filePath,
                filePath = filePath,
                showKey = ShowIdentity.normalizeShowKey(title.ifBlank { fromTask.showTitle }),
                showTitle = title.ifBlank { fromTask.showTitle }
            )
        }
        val fallbackTitle = title.ifBlank {
            File(filePath).nameWithoutExtension.ifBlank { "Unknown" }
        }
        return WatchRecord(
            id = filePath,
            showKey = ShowIdentity.normalizeShowKey(fallbackTitle),
            showTitle = fallbackTitle,
            kind = WatchHistoryCore.kindForTitle(fallbackTitle),
            filePath = filePath,
            episodeTitle = fallbackTitle
        )
    }

    private fun mutate(transform: (List<WatchRecord>) -> List<WatchRecord>) {
        var toPersist: List<WatchRecord>? = null
        synchronized(lock) {
            val next = transform(_records.value)
            if (next !== _records.value) {
                _records.value = next
                toPersist = next
            }
        }
        toPersist?.let { persist(it) }
    }

    private fun persist(records: List<WatchRecord>) {
        val f = stateFile ?: return
        scope.launch {
            persistMutex.withLock { writeNow(f, records) }
        }
    }

    private fun writeNow(f: File, records: List<WatchRecord>) {
        try {
            val encoded = json.encodeToString(records)
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(encoded)
            if (!tmp.renameTo(f)) {
                f.writeText(encoded)
                tmp.delete()
            }
        } catch (_: Throwable) {
            /* history is a convenience — never let a write break playback */
        }
    }
}
