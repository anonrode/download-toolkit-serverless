package com.anonrode.downloader.engine

import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

class DownloadRepository {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }
    private var stateFile: File? = null
    // Set by initPersistence; used to mirror every persist into public
    // Documents so the history survives an uninstall (see HistoryBackup).
    @Volatile private var appContext: android.content.Context? = null

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    /** Injectable wall clock for the display window (deterministic JVM
     *  tests). Production keeps the system clock. */
    internal var clockMs: () -> Long = { System.currentTimeMillis() }

    // ── Display smoothing (speed readout fix) ────────────────────────────
    // Every backend funnels its progress through updateProgress, so this is
    // the ONE chokepoint where the DISPLAYED speed can be windowed. The raw
    // task.speedBytesPerSec keeps its exact per-backend semantics (the
    // engine's ProgressWatchdog reads it for stall detection); the stable
    // value travels in the display-only fields. A finished/removed task's
    // window is pruned lazily on the next drift check.
    private val displayWindows = ConcurrentHashMap<String, TaskDisplayWindow>()

    private fun pruneDisplayWindows(list: List<DownloadTask>) {
        // Bounded churn check: only materialize the id set once the map has
        // clearly outgrown the task list (RARE — task removals), keeping
        // the 4 Hz hot path allocation-free.
        if (displayWindows.size > list.size + 8) {
            displayWindows.keys.retainAll(list.mapTo(mutableSetOf()) { it.id })
        }
    }

    fun initPersistence(dir: File, context: android.content.Context? = null) {
        if (stateFile != null) return
        val f = File(dir, "download_tasks.json")
        stateFile = f
        appContext = context?.applicationContext
        try {
            if (f.exists()) {
                val raw = f.readText()
                if (raw.isNotBlank()) {
                    _tasks.value = json.decodeFromString<List<DownloadTask>>(raw)
                        .map { parkForRestore(it) }
                    persist()
                }
            }
        } catch (_: Throwable) {
            // engine-audit P1: a corrupt or partially-flushed state file used
            // to be DELETED right here with the list emptied — while an intact
            // Documents mirror sat unused, and the very next persist()
            // overwrote that mirror too, destroying the whole history for good.
            // Set the poison file aside for diagnostics instead; the mirror
            // branch below then recovers the tasks (persist() re-syncs the
            // private file immediately after a successful import).
            try { f.renameTo(File(f.parentFile, f.name + ".corrupt")) } catch (_: Throwable) {}
        }
        if (_tasks.value.isEmpty()) {
            try {
                // Fresh install (filesDir wiped, e.g. by an uninstall) but a
                // Documents mirror exists from before: import it, and the
                // persist() below re-syncs the private file immediately.
                val mirrored = appContext?.let { HistoryBackup.load(it) }
                if (!mirrored.isNullOrBlank()) {
                    _tasks.value = json.decodeFromString<List<DownloadTask>>(mirrored).map { parkForRestore(it) }
                    persist()
                }
            } catch (_: Throwable) {}
        }
    }

    /** Debug-only: re-apply the restore-time parking mapping to the current
     *  in-memory list — the exact `parkForRestore` transformation
     *  [initPersistence] applies to persisted rows when the app boots.
     *  Instrumented tests use it to exercise the v3.0.4 "a reopen never
     *  auto-resumes mid-flight work" contract without an actual process
     *  restart (initPersistence itself is one-shot per process). No-op in
     *  release builds. */
    fun debugSimulateRestoreForTests() {
        if (!com.anonrode.downloader.BuildConfig.DEBUG) return
        _tasks.value = _tasks.value.map { parkForRestore(it) }
        persist()
    }

    private val persistMutex = kotlinx.coroutines.sync.Mutex()
    // Burst coalescing: batch enqueue / pause-all / reconnect sweeps call
    // persist() dozens of times in a row. CONFLATED holds at most one pending
    // wake-up, and the short debounce lets one snapshot cover the burst
    // instead of serializing identical JSON + MediaStore mirrors back-to-back.
    private val persistWake = Channel<Unit>(Channel.CONFLATED)
    private val persistCoalesceMs = 120L

    init {
        scope.launch {
            for (unused in persistWake) {
                // Write immediately, then hold a short coalescing window: a
                // burst of follow-up mutations lands in the next conflated
                // iteration instead of delaying the first durable write.
                persistMutex.withLock { writeSnapshot() }
                delay(persistCoalesceMs)
            }
        }
    }

    fun persist() {
        persistWake.trySend(Unit)
    }

    private fun writeSnapshot() {
        val f = stateFile ?: return
        try {
            val encoded = json.encodeToString(_tasks.value)
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(encoded)
            if (!tmp.renameTo(f)) {
                // Engine-audit: the rename result was unchecked — the
                // one write path with no failure handling. A direct
                // rewrite keeps the newest state on disk even when the
                // rename fails (FUSE volumes, transient EBUSY).
                f.writeText(encoded)
                tmp.delete()
            }
            // Mirror only on meaningful writes (this runs on status
            // transitions, adds, removes — not per progress tick).
            appContext?.let { HistoryBackup.save(it, encoded) }
        } catch (_: Throwable) {}
    }

    fun snapshot(): List<DownloadTask> = _tasks.value

    fun find(taskId: String): DownloadTask? = _tasks.value.find { it.id == taskId }

    fun addFirst(task: DownloadTask) {
        _tasks.update { listOf(task) + it }
        persist()
    }

    fun remove(taskId: String) {
        displayWindows.remove(taskId)
        _tasks.update { list -> list.filterNot { it.id == taskId } }
        persist()
    }

    /** Batch removal with ONE StateFlow update and ONE persist() (and thus
     *  one MediaStore history mirror) for a whole show deletion. */
    fun removeAll(taskIds: Collection<String>) {
        if (taskIds.isEmpty()) return
        val ids = taskIds.toSet()
        ids.forEach { displayWindows.remove(it) }
        _tasks.update { list -> list.filterNot { it.id in ids } }
        persist()
    }

    fun update(taskId: String, transform: (DownloadTask) -> DownloadTask) {
        var clearWindow = false
        _tasks.update { list ->
            list.map {
                if (it.id == taskId) {
                    val updated = transform(it)
                    if (updated.totalBytes == 0L || updated.status != TaskStatus.DOWNLOADING) {
                        clearWindow = true
                    }
                    updated
                } else it
            }
        }
        if (clearWindow) {
            displayWindows.remove(taskId)
        }
        persist()
    }

    fun updateProgress(taskId: String, downloaded: Long, total: Long, speed: Double, eta: Long) {
        val nowMs = clockMs()
        // Cheap drift guard before touching the map (see pruneDisplayWindows).
        pruneDisplayWindows(_tasks.value)
        val window = displayWindows.computeIfAbsent(taskId) { TaskDisplayWindow() }
        // Window record happens BEFORE the state write, from the row as it
        // currently stands plus this tick's bytes: MutableStateFlow.update
        // retries its lambda under contention (progress ticks arrive at
        // 4 Hz per task, several tasks at once), so any side effect inside
        // it would double-append samples. A racing tick self-corrects — the
        // next 250 ms sample re-bases the window, and byte totals are
        // monotonic at both the task level and inside the window.
        var windowReading: Double? = null
        var windowEta: Long? = null
        val current = _tasks.value.firstOrNull { it.id == taskId }
        // Sticky max total: once a size has been reported for this task, no
        // later (reset-shaped) report can shrink the displayed denominator.
        // Tracked on the same per-task window object, raised by the stored
        // row AND this tick's total.
        val stickyTotal = window.maxTotalOf(maxOf(current?.totalBytes ?: 0L, total))
        if (current != null && current.status == TaskStatus.DOWNLOADING) {
            val bytesNow = if (downloaded > 0) maxOf(current.downloadedBytes, downloaded)
            else current.downloadedBytes
            windowReading = window.record(nowMs, bytesNow)
            // Display ETA from the SAME windowed speed: the raw per-backend
            // eta (yt-dlp's literal per-line "ETA 00:05") keeps its raw
            // field, but the card shows the stable value whenever the
            // window knows both a rate and a remaining size.
            if (windowReading != null && windowReading > 0.0 && stickyTotal > 0L && bytesNow < stickyTotal) {
                windowEta = ceil((stickyTotal - bytesNow) / windowReading).toLong().coerceAtLeast(1L)
            }
        }
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
                    // A COMPLETED row is closed: the recorded size is the on-disk
                    // file length, not a backend estimate — a late tick racing the
                    // terminal write must never re-stamp downloaded/total.
                    val finalDl = if (it.status == TaskStatus.COMPLETED) it.downloadedBytes
                    else if (downloaded > 0) maxOf(it.downloadedBytes, downloaded) else it.downloadedBytes
                    // Displayed totals cannot regress mid-download (speed readout
                    // fix): the old ">10 GB rows may switch to a <5 GB total"
                    // allowance let a banked-stream reset (yt-dlp's second
                    // "Destination:" line) SHRINK the denominator between its
                    // video and audio stream, so a huge file's percent crept
                    // backwards. max against the sticky per-task maximum kept in
                    // the same in-memory window map instead — refinements only
                    // ever raise the known total; a COMPLETED row stays frozen.
                    val finalTot = if (it.status == TaskStatus.COMPLETED) it.totalBytes
                    else maxOf(it.totalBytes, stickyTotal)
                    val transferring = status == TaskStatus.DOWNLOADING
                    val finalEta = when {
                        !transferring -> 0L
                        eta > 0L -> eta
                        it.etaSeconds > 0L -> it.etaSeconds
                        else -> 0L
                    }
                    it.copy(
                        status = status,
                        downloadedBytes = finalDl,
                        totalBytes = finalTot,
                        speedBytesPerSec = if (transferring) speed else 0.0,
                        etaSeconds = finalEta,
                        // Display smoothing: populated only while actually
                        // transferring (null = "Starting…" until the window
                        // has ~3 s of samples); paused/queued/validating rows
                        // clear both so no windowed value can go stale.
                        displaySpeedBytesPerSec = if (transferring) windowReading else null,
                        displayEtaSeconds = if (transferring) windowEta else null
                    )
                } else it
            }
        }
    }
}

/**
 * Per-task (time, bytes) ring for the DISPLAYED speed — the speed readout
 * fix at the repository chokepoint (investigation/14_player_trace.md §B).
 *
 * Displayed rate = byte delta across the trailing [DISPLAY_WINDOW_MS],
 * not the raw 250 ms EMA (Turbo) or the verbatim parsed yt-dlp/aria line.
 * Until the window spans [DISPLAY_MIN_WINDOW_MS] the reading is null —
 * the UI renders "Starting…" — which kills both the ramp flicker and the
 * 2.4 TB/s resume-priming burst at every consumer of the display fields.
 * ProgressWatchdog compatibility: the RAW task.speedBytesPerSec is written
 * unchanged beside this; nothing here feeds back into it.
 */
internal const val DISPLAY_WINDOW_MS = 4_000L
internal const val DISPLAY_MIN_WINDOW_MS = 3_000L

/** Physical-cap sanity bound (~2 GB/s) for any displayed rate: impossible
 *  bursts (aria/yt-dlp resume recount lines, parsed at face value with a
 *  TB factor upstream) are clamped against the previous sample, and the
 *  final reading is clamped again. */
internal const val DISPLAY_SPEED_CEILING_BPS = 2_000_000_000.0

internal class TaskDisplayWindow {
    private class Sample(val atMs: Long, val bytes: Long)

    private val lock = Any()
    private val samples = ArrayDeque<Sample>()
    private var stickyTotalBytes = 0L

    /** Monotonic known total: refinements raise it, reset-shaped reports
     *  never shrink it. */
    fun maxTotalOf(total: Long): Long = synchronized(lock) {
        if (total > stickyTotalBytes) stickyTotalBytes = total
        stickyTotalBytes
    }

    /**
     * Append a (time, monotonic-bytes) sample and return the windowed rate
     * in bytes/s — null while the window is younger than
     * [DISPLAY_MIN_WINDOW_MS].
     *
     * An impossible burst between two samples is CLAMPED (not dropped:
     * real progress must still register, at the cap) so the whole window
     * stays bounded; byte values never move backwards inside the window.
     * Samples older than the window edge drop on every record, so a
     * pause/resume gap (or a cold start) begins a fresh window that reads
     * null until ~3 s of new samples exist again.
     *
     * Synchronized: the backend's progress coroutine and the watchdog's
     * filesystem bridge can race into the same task's updateProgress —
     * at 4 Hz the lock cost is nil and the window must stay coherent.
     */
    fun record(nowMs: Long, downloadedBytes: Long): Double? = synchronized(lock) {
        val prev = samples.lastOrNull()
        val bytes = if (prev == null) {
            downloadedBytes.coerceAtLeast(0L)
        } else {
            val dtMs = (nowMs - prev.atMs).coerceAtLeast(0L)
            val ceilingBytes = prev.bytes + (DISPLAY_SPEED_CEILING_BPS * dtMs / 1000.0).toLong()
            downloadedBytes.coerceIn(prev.bytes, ceilingBytes)
        }
        samples.addLast(Sample(atMs = nowMs, bytes = bytes))
        // Prune strictly-before the window edge: the survivor becomes the
        // delta base, so the computed span never exceeds the window and a
        // multi-second gap leaves nothing to dilute the rate with.
        val cutoffMs = nowMs - DISPLAY_WINDOW_MS
        while (samples.isNotEmpty() && samples.first().atMs < cutoffMs) {
            samples.removeFirst()
        }
        val first = samples.first()
        val last = samples.last()
        val spanMs = last.atMs - first.atMs
        if (spanMs < DISPLAY_MIN_WINDOW_MS) return@synchronized null
        val rate = (last.bytes - first.bytes).toDouble() / spanMs * 1000.0
        rate.coerceIn(0.0, DISPLAY_SPEED_CEILING_BPS)
    }
}
