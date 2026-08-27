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
        // DownloadTask has no enqueue timestamp on the data class (and the
        // spec forbids extending the model), so the production UI passes a
        // precomputed age via [setAgeOverride].  Tests register deterministic
        // ages; the live caller (MainScaffold) derives age from the position
        // in engine.tasks (newer entries appear at the tail).
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
        TaskStatus.PAUSED,
        TaskStatus.FAILED,
        TaskStatus.COMPLETED
    )

    private val SIZE_ORDER = listOf("LARGE", "MEDIUM", "SMALL")

    private val STATUS_HEADER: Map<TaskStatus, String> = mapOf(
        TaskStatus.DOWNLOADING to "DOWNLOADING",
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

    private fun groupLibrary(tasks: List<DownloadTask>): List<Pair<String, List<DownloadTask>>> {
        // Group by showTitle; order shows by count of COMPLETED tasks
        // descending, then by show name ascending for stability.
        val byShow: MutableMap<String, MutableList<DownloadTask>> = linkedMapOf()
        for (t in tasks) {
            val key = t.showTitle.ifBlank { "Unknown" }
            byShow.getOrPut(key) { mutableListOf() }.add(t)
        }
        return byShow.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, List<DownloadTask>>> { entry ->
                    entry.value.count { it.status == TaskStatus.COMPLETED }
                }.thenBy { it.key }
            )
            .map { it.key to it.value.toList() }
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
