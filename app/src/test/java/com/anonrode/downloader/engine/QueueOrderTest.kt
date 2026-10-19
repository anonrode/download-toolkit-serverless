package com.anonrode.downloader.engine

import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F-12 pin — the queue is LIFO end-to-end, and this test freezes that contract
 * so the code and its comments can never drift apart again:
 *
 *  - [DownloadRepository.addFirst] PREPENDS (DownloadRepository.kt:99-101),
 *    so `snapshot()`/`tasks.value` is NEWEST-FIRST.
 *  - `DownloadEngine.processQueue` picks `firstOrNull { QUEUED … }` on that
 *    list (DownloadEngine.kt:1720-1722) -> the NEWEST queued task starts next.
 *  - `DownloadEngine.enforceConcurrencyLimit` keeps the first N entries in
 *    list order — i.e. the NEWEST actives — and demotes the OLDEST
 *    (DownloadEngine.kt:641-660).
 *
 * Why the processQueue predicate is MIRRORED here instead of driving the real
 * engine: constructing DownloadEngine needs a full Android Context + service
 * wiring, which a plain Tier-0 JVM test cannot provide cleanly. The list order
 * asserted below is exactly what that predicate consumes, so the honest,
 * CI-runnable pin is the repository contract plus the mirrored selection
 * rules (kept verbatim in shape; update BOTH when either changes).
 */
class QueueOrderTest {

    private fun task(id: String, status: TaskStatus = TaskStatus.QUEUED) = DownloadTask(
        id = id,
        showTitle = "Show $id",
        episodeNum = 1,
        episodeTitle = "Ep $id",
        directUrl = "https://example.invalid/$id.mp4",
        status = status
    )

    @Test
    fun addFirstMakesTheSnapshotNewestFirst() {
        val repo = DownloadRepository()
        repo.addFirst(task("oldest"))
        repo.addFirst(task("middle"))
        repo.addFirst(task("newest"))
        assertEquals(listOf("newest", "middle", "oldest"), repo.tasks.value.map { it.id })
    }

    @Test
    fun queuePickStartsTheNewestQueuedTask_notTheOldest() {
        val repo = DownloadRepository()
        // Enqueue order a, b, c -> list order is c, b, a (newest first).
        repo.addFirst(task("a"))
        repo.addFirst(task("b"))
        repo.addFirst(task("c"))
        // Mirror of DownloadEngine.kt:1720-1722:
        //   currentTasks.firstOrNull { it.status == QUEUED && !it.userPaused && it.id !in activeIds }
        val next = repo.tasks.value.firstOrNull { it.status == TaskStatus.QUEUED && !it.userPaused }
        assertEquals("c", next?.id)
    }

    @Test
    fun concurrencyDemotionKeepsTheNewestActivesAndDemotesTheOldest() {
        val repo = DownloadRepository()
        // Enqueue oldest -> newest, then mark all active: list order e4..e1.
        repo.addFirst(task("e1", TaskStatus.DOWNLOADING))
        repo.addFirst(task("e2", TaskStatus.DOWNLOADING))
        repo.addFirst(task("e3", TaskStatus.DOWNLOADING))
        repo.addFirst(task("e4", TaskStatus.DOWNLOADING))
        val actives = repo.tasks.value.filter {
            it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.RESOLVING || it.status == TaskStatus.VALIDATING
        }
        // Mirror of DownloadEngine.kt:641-660: the first maxConcurrent entries
        // stay active; actives.drop(maxConcurrent) is demoted back to QUEUED.
        val kept = actives.take(2).map { it.id }
        val demoted = actives.drop(2).map { it.id }
        assertEquals(listOf("e4", "e3"), kept)     // newest two stay active
        assertEquals(listOf("e2", "e1"), demoted)   // oldest two go back to QUEUED
    }
}
