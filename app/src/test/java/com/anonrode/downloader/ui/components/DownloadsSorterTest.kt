package com.anonrode.downloader.ui.components

import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [DownloadsSorter] and [downloadsStats].  These are
 * pure-function tests so they run without Robolectric — no Android Context
 * is needed and the [DownloadTask] data class is fully constructable on the
 * JVM.
 */
class DownloadsSorterTest {

    @After
    fun tearDown() {
        // The sorter has a per-id age override map; reset between tests so
        // ordering assertions aren't contaminated by another test's seeds.
        DownloadsSorter.clearAgeOverrides()
    }

    // -- helpers --------------------------------------------------------------

    private fun task(
        id: String,
        showTitle: String = "Show",
        status: TaskStatus = TaskStatus.QUEUED,
        totalBytes: Long = 0L,
        daysAgo: Long = 0L
    ): DownloadTask = DownloadTask(
        id = id,
        showTitle = showTitle,
        episodeNum = 1,
        episodeTitle = "Episode 1",
        directUrl = "https://example.invalid/$id.mp4",
        totalBytes = totalBytes,
        status = status
    ).also { DownloadsSorter.setAgeOverride(it.id, daysAgo) }

    // -- date mode ------------------------------------------------------------

    @Test
    fun dateMode_groupsByRecencyBucket() {
        val today = task("today", daysAgo = 0L)
        val yesterday = task("yesterday", daysAgo = 1L)
        val week = task("week", daysAgo = 3L)
        val month = task("month", daysAgo = 14L)
        val earlier = task("earlier", daysAgo = 60L)

        val groups = DownloadsSorter.sortDownloads(
            listOf(earlier, today, month, yesterday, week),
            DownloadsSorter.SORT_DATE
        )

        // Header order is the prototype's date order, not the input order.
        assertEquals(
            listOf("TODAY", "YESTERDAY", "THIS WEEK", "THIS MONTH", "EARLIER"),
            groups.map { it.first }
        )
        // Each bucket ends up with the right single task.
        assertEquals(listOf(today), groups[0].second)
        assertEquals(listOf(yesterday), groups[1].second)
        assertEquals(listOf(week), groups[2].second)
        assertEquals(listOf(month), groups[3].second)
        assertEquals(listOf(earlier), groups[4].second)
    }

    @Test
    fun dateMode_emptyInputReturnsEmpty() {
        val groups = DownloadsSorter.sortDownloads(emptyList(), DownloadsSorter.SORT_DATE)
        assertTrue(groups.isEmpty())
    }

    @Test
    fun dateMode_multiplePerBucket_stayInInputOrder() {
        val t1 = task("t1", daysAgo = 0L)
        val t2 = task("t2", daysAgo = 0L)
        val t3 = task("t3", daysAgo = 1L)
        val groups = DownloadsSorter.sortDownloads(
            listOf(t1, t2, t3),
            DownloadsSorter.SORT_DATE
        )
        // Only two buckets contain items.
        assertEquals(2, groups.size)
        assertEquals("TODAY", groups[0].first)
        assertEquals(listOf(t1, t2), groups[0].second)
        assertEquals("YESTERDAY", groups[1].first)
        assertEquals(listOf(t3), groups[1].second)
    }

    // -- library mode ---------------------------------------------------------

    @Test
    fun libraryMode_groupsByShowOrderedByCompletedCountDesc() {
        // Three shows.  Squid Game has the most done, then Attack Titan
        // (one done + one failed), then Wedding Season (none done).
        val sg1 = task("sg1", showTitle = "Squid Game", status = TaskStatus.COMPLETED)
        val sg2 = task("sg2", showTitle = "Squid Game", status = TaskStatus.COMPLETED)
        val at1 = task("at1", showTitle = "Attack Titan", status = TaskStatus.COMPLETED)
        val at2 = task("at2", showTitle = "Attack Titan", status = TaskStatus.FAILED)
        val ws1 = task("ws1", showTitle = "Wedding Season", status = TaskStatus.DOWNLOADING)

        val groups = DownloadsSorter.sortDownloads(
            listOf(ws1, sg1, at1, sg2, at2),
            DownloadsSorter.SORT_LIBRARY
        )

        // Order: most-completed first, tiebreak alphabetical.
        assertEquals(
            listOf("Squid Game", "Attack Titan", "Wedding Season"),
            groups.map { it.first }
        )
        assertEquals(listOf(sg1, sg2), groups[0].second)
        assertEquals(listOf(at1, at2), groups[1].second)
        assertEquals(listOf(ws1), groups[2].second)
    }

    @Test
    fun libraryMode_unknownShowGroupsUnderUnknownBucket() {
        val t = task("u1", showTitle = "")
        val groups = DownloadsSorter.sortDownloads(
            listOf(t),
            DownloadsSorter.SORT_LIBRARY
        )
        assertEquals(1, groups.size)
        assertEquals("Unknown", groups[0].first)
    }

    // -- status mode ----------------------------------------------------------

    @Test
    fun statusMode_groupsByStatusInPrototypeOrder() {
        val downloading = task("d1", status = TaskStatus.DOWNLOADING)
        val paused = task("p1", status = TaskStatus.PAUSED)
        val failed = task("f1", status = TaskStatus.FAILED)
        val done1 = task("c1", status = TaskStatus.COMPLETED)
        val done2 = task("c2", status = TaskStatus.COMPLETED)

        val groups = DownloadsSorter.sortDownloads(
            listOf(done1, failed, paused, downloading, done2),
            DownloadsSorter.SORT_STATUS
        )

        // Prototype order: DOWNLOADING, PAUSED, FAILED, DONE.
        assertEquals(
            listOf("DOWNLOADING", "PAUSED", "FAILED", "DONE"),
            groups.map { it.first }
        )
        assertEquals(listOf(downloading), groups[0].second)
        assertEquals(listOf(paused), groups[1].second)
        assertEquals(listOf(failed), groups[2].second)
        assertEquals(listOf(done1, done2), groups[3].second)
    }

    @Test
    fun statusMode_dropsEmptyBuckets() {
        val done = task("c1", status = TaskStatus.COMPLETED)
        val groups = DownloadsSorter.sortDownloads(
            listOf(done),
            DownloadsSorter.SORT_STATUS
        )
        // No downloading/paused/failed tasks: only the DONE bucket appears.
        assertEquals(listOf("DONE"), groups.map { it.first })
    }

    // -- size mode ------------------------------------------------------------

    @Test
    fun sizeMode_groupsBySizeThreshold() {
        val large = task("L", totalBytes = 900L * 1024L * 1024L)       // 900 MB
        val medium = task("M", totalBytes = 500L * 1024L * 1024L)      // 500 MB
        val small = task("S", totalBytes = 200L * 1024L * 1024L)       // 200 MB
        val edge = task("E", totalBytes = 800L * 1024L * 1024L)        // exactly 800 MB -> LARGE

        val groups = DownloadsSorter.sortDownloads(
            listOf(small, medium, large, edge),
            DownloadsSorter.SORT_SIZE
        )

        assertEquals(
            listOf("LARGE", "MEDIUM", "SMALL"),
            groups.map { it.first }
        )
        // Within LARGE: edge (exactly 800) and large (900) — largest first.
        assertEquals(listOf(large, edge), groups[0].second)
        assertEquals(listOf(medium), groups[1].second)
        assertEquals(listOf(small), groups[2].second)
    }

    // -- stats ----------------------------------------------------------------

    @Test
    fun stats_countsInFlightPausedFailedDone() {
        val tasks = listOf(
            task("d1", status = TaskStatus.DOWNLOADING),
            task("d2", status = TaskStatus.RESOLVING),
            task("d3", status = TaskStatus.VALIDATING),
            task("p1", status = TaskStatus.PAUSED),
            task("f1", status = TaskStatus.FAILED),
            task("f2", status = TaskStatus.FAILED),
            task("c1", status = TaskStatus.COMPLETED),
            task("q1", status = TaskStatus.QUEUED)  // queued does not affect the badge
        )
        val s = downloadsStats(tasks)
        assertEquals(8, s.files)
        // active = downloading + resolving + validating + paused (4)
        assertEquals(4, s.active)
        // failed = 2
        assertEquals(2, s.failed)
        // done = 1
        assertEquals(1, s.done)
        // badge = active (in-flight + paused)
        assertEquals(4, s.badgeCount)
    }

    @Test
    fun stats_emptyInputIsAllZeros() {
        val s = downloadsStats(emptyList())
        assertEquals(0, s.files)
        assertEquals(0, s.active)
        assertEquals(0, s.failed)
        assertEquals(0, s.done)
        assertEquals(0, s.badgeCount)
    }

    @Test
    fun stats_badgeHidesWhenZero() {
        val s = downloadsStats(listOf(task("c", status = TaskStatus.COMPLETED)))
        assertEquals(0, s.badgeCount)
    }

    // -- bucket boundaries ----------------------------------------------------

    @Test
    fun dateBucket_boundaries() {
        // Probe the boundary edges: 0/1, 6/7, 29/30.
        assertEquals("TODAY", DownloadsSorter.dateBucketLabel(0L))
        assertEquals("YESTERDAY", DownloadsSorter.dateBucketLabel(1L))
        assertEquals("THIS WEEK", DownloadsSorter.dateBucketLabel(6L))
        assertEquals("THIS MONTH", DownloadsSorter.dateBucketLabel(29L))
        assertEquals("EARLIER", DownloadsSorter.dateBucketLabel(30L))
        assertEquals("EARLIER", DownloadsSorter.dateBucketLabel(365L))
    }

    @Test
    fun sizeBucket_boundaries() {
        // 799 MB is still MEDIUM; 800 is the LARGE cutover.
        assertEquals("MEDIUM", DownloadsSorter.sizeBucketLabel(799L * 1024L * 1024L))
        assertEquals("LARGE", DownloadsSorter.sizeBucketLabel(800L * 1024L * 1024L))
        // 399 is still SMALL; 400 is the MEDIUM cutover.
        assertEquals("SMALL", DownloadsSorter.sizeBucketLabel(399L * 1024L * 1024L))
        assertEquals("MEDIUM", DownloadsSorter.sizeBucketLabel(400L * 1024L * 1024L))
    }
}
