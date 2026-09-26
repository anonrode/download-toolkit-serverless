package com.anonrode.downloader.ui.components

import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus

/**
 * Pure sort / bucket logic for the Downloads tab. Lives in `ui/components`
 * (not `ui/screens`) so unit tests under the JVM can hit it without an
 * Android runtime — no Robolectric, no Context, no Compose. The Composables
 * around it own the rendering, this owns the data shape.
 */
object DownloadsSorter {

    const val SORT_DATE = "date"
    const val SORT_LIBRARY = "library"
    const val SORT_STATUS = "status"
    const val SORT_SIZE = "size"

    val ALL_MODES = listOf(SORT_DATE, SORT_LIBRARY, SORT_STATUS, SORT_SIZE)

    fun ageInDays(task: DownloadTask): Long {
        // The production UI passes a precomputed age via [setAgeOverride]
        // (real clock age from task.createdAt, with list-position fallback
        // for pre-upgrade tasks). Tests register deterministic ages; empty
        // overrides in production default to 0 (TODAY bucket).
        TASK_AGE_OVERRIDES[task.id]?.let { return it }
        return 0L
    }

    // Test seam: register deterministic ages per task id for unit tests.
    // Empty in production; live UI calls clearAgeOverrides() and re-seeds
    // from engine.tasks order before each sort.
    private val TASK_AGE_OVERRIDES: MutableMap<String, Long> = mutableMapOf()

    fun setAgeOverride(taskId: String, days: Long) { TASK_AGE_OVERRIDES[taskId] = days }
    fun clearAgeOverrides() { TASK_AGE_OVERRIDES.clear() }

    /** Buckets a task into one of TODAY / YESTERDAY / THIS WEEK / THIS MONTH / EARLIER. */
    fun dateBucketLabel(ageDays: Long): String = when {
        ageDays <= 0L -> "TODAY"
        ageDays == 1L -> "YESTERDAY"
        ageDays < 7L -> "THIS WEEK"
        ageDays < 30L -> "THIS MONTH"
        else -> "EARLIER"
    }

    /** Size bucket boundaries, matching the HTML: >=800 / 400-800 / <400 MB. */
    fun sizeBucketLabel(totalBytes: Long): String {
        val mb = totalBytes / (1024L * 1024L)
        return when {
            mb >= 800L -> "LARGE"
            mb >= 400L -> "MEDIUM"
            else -> "SMALL"
        }
    }

    // -- Order matters: it's the visible order of the group headers in each mode.

    private val DATE_ORDER = listOf("TODAY", "YESTERDAY", "THIS WEEK", "THIS MONTH", "EARLIER")

    private val STATUS_ORDER = listOf(
        TaskStatus.DOWNLOADING,
        TaskStatus.RESOLVING,
        TaskStatus.QUEUED,
        TaskStatus.VALIDATING,
        TaskStatus.PAUSED,
        TaskStatus.FAILED,
        TaskStatus.COMPLETED
    )

    private val SIZE_ORDER = listOf("LARGE", "MEDIUM", "SMALL")

    private val STATUS_HEADER: Map<TaskStatus, String> = mapOf(
        TaskStatus.DOWNLOADING to "DOWNLOADING",
        TaskStatus.RESOLVING to "RESOLVING",
        TaskStatus.QUEUED to "QUEUED",
        TaskStatus.VALIDATING to "VALIDATING",
        TaskStatus.PAUSED to "PAUSED",
        TaskStatus.FAILED to "FAILED",
        TaskStatus.COMPLETED to "DONE"
    )

    /**
     * Group tasks for the Downloads screen.  Returns header -> ordered list
     * pairs in display order.  The first element of each pair is the literal
     * header text the UI should show.  An empty input list returns an empty
     * list — the caller renders the empty state itself.
     */
    fun sortDownloads(
        tasks: List<DownloadTask>,
        mode: String
    ): List<Pair<String, List<DownloadTask>>> {
        if (tasks.isEmpty()) return emptyList()
        return when (mode) {
            SORT_LIBRARY -> groupLibrary(tasks)
            SORT_STATUS -> groupStatus(tasks)
            SORT_SIZE -> groupSize(tasks)
            else -> groupDate(tasks)
        }
    }

    private fun groupDate(tasks: List<DownloadTask>): List<Pair<String, List<DownloadTask>>> {
        // Newest first within each bucket; the bucket itself is the header.
        val byBucket: MutableMap<String, MutableList<DownloadTask>> = linkedMapOf()
        DATE_ORDER.forEach { byBucket[it] = mutableListOf() }
        for (t in tasks) {
            val ageDays = ageInDays(t)
            val bucket = dateBucketLabel(ageDays)
            byBucket.getOrPut(bucket) { mutableListOf() }.add(t)
        }
        return byBucket
            .filter { it.value.isNotEmpty() }
            .toList()
    }

    fun normalizeShowKey(raw: String): String {
        var s = raw.trim()
        if (s.isBlank()) return "Unknown"
        // Strip site watermarks in brackets or parens: [9jaRocks.Com], [NaijaPrey], (NetNaija), etc.
        s = s.replace(Regex("""\[[^\]]*\]"""), "")
        s = s.replace(Regex("""\([^\)]*(?:rocks|prey|naija|netnaija|nkiri)[^\)]*\)""", RegexOption.IGNORE_CASE), "")
        // Strip release/codec/resolution noise: 540p, 720p, 1080p, x265, x264, WEBRip, etc.
        s = s.replace(Regex("""\b(?:\d{3,4}p|x26[45]|hevc|h26[45]|aac|web-?rip|dvd-?rip|bluray|hdrip)\b""", RegexOption.IGNORE_CASE), "")
        // Strip trailing season markers so all seasons of the same show group into one show card:
        // "The Pitt S02" -> "The Pitt", "President Curtis - S01" -> "President Curtis"
        s = s.replace(Regex("""[-–—._\s]+S\d{1,2}(?:E\d{1,3})?.*""", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("""[-–—._\s]+Season\s*\d{1,2}.*""", RegexOption.IGNORE_CASE), "")
        // Clean trailing separators
        s = s.replace(Regex("""[._\-–—\s]+$"""), "").trim()
        return if (s.isBlank()) raw.trim() else s
    }

    private fun groupLibrary(tasks: List<DownloadTask>): List<Pair<String, List<DownloadTask>>> {
        // Group by normalized show key; order shows by count of COMPLETED tasks
        // descending, then by show name ascending for stability. Within a
        // show the episodes run NUMERICALLY (ep1, ep2, ep3…) — aggregating
        // episodes across sites (e.g. ep 1 from 9jaRocks, ep 2 from NaijaPrey).
        val byShow: MutableMap<String, MutableList<DownloadTask>> = linkedMapOf()
        for (t in tasks) {
            val key = normalizeShowKey(t.showTitle.ifBlank { "Unknown" })
            byShow.getOrPut(key) { mutableListOf() }.add(t)
        }
        return byShow.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, List<DownloadTask>>> { entry ->
                    entry.value.count { it.status == TaskStatus.COMPLETED }
                }.thenBy { it.key }
            )
            .map { it.key to it.value.sortedWith(EPISODE_ORDER) }
    }

    /**
     * Shared episode ordering: numbered episodes ascending first (ep1 →
     * ep2 → …), un-numbered tasks (movies, direct links, epNum 0) after
     * them in enqueue order. Stable sort, so equal keys keep the engine
     * snapshot's order — same result as the old insertion order for a
     * movie-only show, numeric for a series.
     */
    internal val EPISODE_ORDER: Comparator<DownloadTask> =
        compareBy<DownloadTask> { if (it.episodeNum > 0) it.episodeNum else Int.MAX_VALUE }
            .thenBy { it.createdAt }

    /**
     * The Next/Previous playlist the in-app player steps through when a task
     * is opened: COMPLETED files of the SAME show only, in episode order
     * (see [EPISODE_ORDER]). Matches on normalized show key across sites.
     */
    fun playerQueueFor(tasks: List<DownloadTask>, showTitle: String): List<String> {
        val normTarget = normalizeShowKey(showTitle.ifBlank { "Unknown" })
        return tasks.filter {
            it.status == TaskStatus.COMPLETED &&
                it.filePath.isNotBlank() &&
                normalizeShowKey(it.showTitle.ifBlank { "Unknown" }) == normTarget
        }
            .sortedWith(EPISODE_ORDER)
            .map { it.filePath }
    }

    /**
     * Sort-aware Next/Previous playlist (v3.1.6). The player used to ALWAYS
     * queue same-show episodes, which contradicted every non-Library sort:
     * with the list segregated by date, "Next" jumped back to today's files
     * instead of walking to the day before (user 2026-09-15: "the only
     * situation where the next should be [restricted] is when i segregate
     * by show, so that when im done, it wont next to another series").
     * So the queue now follows the visible sort:
     *  - DATE / STATUS → every playable completed file, in the engine's
     *    own order (newest-first), crossing show boundaries.
     *  - SIZE → same set ordered largest-first.
     *  - LIBRARY → the [playerQueueFor] series walk — stops at the boundary.
     * Pure; [task] only supplies the show title for the Library branch.
     */
    fun playQueueFor(tasks: List<DownloadTask>, mode: String, task: DownloadTask): List<String> =
        when (mode) {
            SORT_LIBRARY -> playerQueueFor(tasks, task.showTitle)
            SORT_SIZE ->
                tasks.filter { it.status == TaskStatus.COMPLETED && it.filePath.isNotBlank() }
                    .sortedByDescending { it.totalBytes }
                    .map { it.filePath }
            else ->
                tasks.filter { it.status == TaskStatus.COMPLETED && it.filePath.isNotBlank() }
                    .map { it.filePath }
        }

    private fun groupStatus(tasks: List<DownloadTask>): List<Pair<String, List<DownloadTask>>> {
        val byStatus: MutableMap<TaskStatus, MutableList<DownloadTask>> = linkedMapOf()
        STATUS_ORDER.forEach { byStatus[it] = mutableListOf() }
        for (t in tasks) {
            byStatus.getOrPut(t.status) { mutableListOf() }.add(t)
        }
        return byStatus
            .filter { it.value.isNotEmpty() }
            .map { (status, list) -> (STATUS_HEADER[status] ?: status.name) to list.toList() }
    }

    private fun groupSize(tasks: List<DownloadTask>): List<Pair<String, List<DownloadTask>>> {
        val bySize: MutableMap<String, MutableList<DownloadTask>> = linkedMapOf()
        SIZE_ORDER.forEach { bySize[it] = mutableListOf() }
        for (t in tasks) {
            val bucket = sizeBucketLabel(t.totalBytes)
            bySize.getOrPut(bucket) { mutableListOf() }.add(t)
        }
        // Largest first within a bucket so the eye finds the heavy items.
        return bySize
            .filter { it.value.isNotEmpty() }
            .map { (label, list) -> label to list.sortedByDescending { it.totalBytes } }
    }
}

/** Counts surfaced in the Downloads stats strip and the bottom-nav badge.
 *  `active` is the "actually transferring" set: DOWNLOADING / RESOLVING /
 *  VALIDATING.  PAUSED is deliberately excluded — a paused task is parked,
 *  and counting it made the badge read "2 active" while only one download
 *  was moving (user-visible confusion).  Queued tasks are also excluded:
 *  a queued task hasn't claimed a download slot yet. */
data class DownloadsStats(
    val files: Int,
    val done: Int,
    val active: Int,
    val failed: Int
) {
    /** Tasks that should show a red badge on the Downloads bottom-nav icon. */
    val badgeCount: Int get() = active
}

internal fun downloadsStats(tasks: List<DownloadTask>): DownloadsStats {
    var done = 0
    var active = 0
    var failed = 0
    for (t in tasks) {
        when (t.status) {
            TaskStatus.COMPLETED -> done++
            TaskStatus.DOWNLOADING,
            TaskStatus.RESOLVING,
            TaskStatus.VALIDATING -> active++
            TaskStatus.PAUSED -> { /* parked — visible on its own card, not "active" */ }
            TaskStatus.FAILED -> failed++
            TaskStatus.QUEUED -> { /* not in any user-facing bucket — see data class docs */ }
        }
    }
    return DownloadsStats(files = tasks.size, done = done, active = active, failed = failed)
}
