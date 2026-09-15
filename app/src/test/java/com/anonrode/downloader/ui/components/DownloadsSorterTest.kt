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
        // active = downloading + resolving + validating (3); paused is parked,
        // not "active" — counting it made the badge read "2 active" while one
        // task was paused and only one was moving.
        assertEquals(3, s.active)
        // failed = 2
        assertEquals(2, s.failed)
        // done = 1
        assertEquals(1, s.done)
        // badge = active (transferring only)
        assertEquals(3, s.badgeCount)
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

    // -- episode ordering (2026-09-14: group-by-show ep1 → Next ep2) ---------

    private fun ep(
        id: String,
        showTitle: String = "Squid Game",
        episodeNum: Int,
        status: TaskStatus = TaskStatus.COMPLETED,
        createdAt: Long = 0L,
        path: String = "/storage/emulated/0/Download/Anon/$showTitle/$id.mp4"
    ): DownloadTask = DownloadTask(
        id = id,
        showTitle = showTitle,
        episodeNum = episodeNum,
        episodeTitle = "Episode $episodeNum",
        directUrl = "https://example.invalid/$id.mp4",
        status = status,
        filePath = path,
        createdAt = createdAt
    )

    @Test
    fun libraryMode_ordersEpisodesNumericallyWithinShow() {
        // Enqueued newest-episode-first (a "download all" on a site that
        // lists ep24 at the top): the GROUP must still render 1,2,3.
        val e2 = ep("e2", episodeNum = 2)
        val e1 = ep("e1", episodeNum = 1)
        val e3 = ep("e3", episodeNum = 3)

        val groups = DownloadsSorter.sortDownloads(
            listOf(e2, e1, e3),
            DownloadsSorter.SORT_LIBRARY
        )
        assertEquals(1, groups.size)
        assertEquals(listOf("e1", "e2", "e3"), groups[0].second.map { it.id })
    }

    @Test
    fun libraryMode_unnumberedTasksKeepEnqueueOrder() {
        // Movies / direct links carry episodeNum 0: they sort AFTER numbered
        // episodes, among themselves by createdAt ascending (enqueue order).
        val movieLate = ep("m2", episodeNum = 0, createdAt = 200L)
        val ep5 = ep("x5", episodeNum = 5)
        val movieEarly = ep("m1", episodeNum = 0, createdAt = 100L)

        val groups = DownloadsSorter.sortDownloads(
            listOf(movieLate, ep5, movieEarly),
            DownloadsSorter.SORT_LIBRARY
        )
        assertEquals(listOf("x5", "m1", "m2"), groups[0].second.map { it.id })
    }

    @Test
    fun playerQueueFor_sameShowEpisodeOrderOnly() {
        // The bug this pins: Next on ep1 used to hop into ANOTHER show's
        // file (queue was every completed task in engine order).
        val sg3 = ep("sg3", showTitle = "Squid Game", episodeNum = 3)
        val sg1 = ep("sg1", showTitle = "Squid Game", episodeNum = 1)
        val at2 = ep("at2", showTitle = "Attack Titan", episodeNum = 2)
        val sg2 = ep("sg2", showTitle = "Squid Game", episodeNum = 2)
        val sgPending = ep("sg4", showTitle = "Squid Game", episodeNum = 4,
            status = TaskStatus.DOWNLOADING)

        val queue = DownloadsSorter.playerQueueFor(
            listOf(sg3, sg1, at2, sg2, sgPending),
            "Squid Game"
        )
        assertEquals(
            listOf(sg1.filePath, sg2.filePath, sg3.filePath),
            queue
        )
    }

    @Test
    fun playerQueueFor_blankShowUsesUnknownBucket() {
        val a = ep("a", showTitle = "", episodeNum = 1)
        val b = ep("b", showTitle = "", episodeNum = 2)
        assertEquals(listOf(a.filePath, b.filePath),
            DownloadsSorter.playerQueueFor(listOf(b, a), ""))
    }

    // -- playQueueFor (v3.1.6 sort-aware Next) --------------------------------
    // The bug this API pins: the player ALWAYS queued same-show files, so
    // with the list sorted by date "Next" never walked into yesterday's
    // downloads (user 2026-09-15). Only the LIBRARY sort may restrict.

    private fun playableEp(
        id: String,
        showTitle: String = "Squid Game",
        episodeNum: Int = 1,
        totalBytes: Long = 0L,
        status: TaskStatus = TaskStatus.COMPLETED
    ): DownloadTask = ep(id, showTitle = showTitle, episodeNum = episodeNum, status = status)
        .copy(totalBytes = totalBytes)

    @Test
    fun playQueueFor_dateWalksAllShowsInEngineOrder() {
        // Engine snapshot order (newest-first) IS the date order; the queue
        // preserves it and crosses show boundaries. Non-completed and
        // pathless tasks stay out.
        val todayA = playableEp("todayA", showTitle = "Squid Game")
        val todayB = playableEp("todayB", showTitle = "Attack Titan")
        val yesterday = playableEp("yesterday", showTitle = "One Piece")
        val stillDownloading = playableEp("dl", status = TaskStatus.DOWNLOADING)
        val noPath = playableEp("np").copy(filePath = "")

        val queue = DownloadsSorter.playQueueFor(
            listOf(todayA, todayB, yesterday, stillDownloading, noPath),
            DownloadsSorter.SORT_DATE,
            todayA
        )
        assertEquals(listOf(todayA.filePath, todayB.filePath, yesterday.filePath), queue)
    }

    @Test
    fun playQueueFor_statusSharesTheCrossShowBranch() {
        val x = playableEp("x", showTitle = "A")
        val y = playableEp("y", showTitle = "B")
        assertEquals(listOf(y.filePath, x.filePath),
            DownloadsSorter.playQueueFor(listOf(y, x), DownloadsSorter.SORT_STATUS, x))
    }

    @Test
    fun playQueueFor_sizeOrdersLargestFirstAcrossShows() {
        val small = playableEp("small", showTitle = "A", totalBytes = 100L)
        val big = playableEp("big", showTitle = "B", totalBytes = 900L)
        val mid = playableEp("mid", showTitle = "C", totalBytes = 500L)
        assertEquals(listOf(big.filePath, mid.filePath, small.filePath),
            DownloadsSorter.playQueueFor(listOf(small, big, mid), DownloadsSorter.SORT_SIZE, small))
    }

    @Test
    fun playQueueFor_libraryKeepsTheSeriesWalk() {
        // The one restricted mode: finish ep 2, Next must be ep 3 of THIS
        // series — never the other show sitting beside it in the list.
        val sg2 = playableEp("sg2", episodeNum = 2)
        val other = playableEp("other", showTitle = "Attack Titan")
        val sg3 = playableEp("sg3", episodeNum = 3)
        val sg1 = playableEp("sg1", episodeNum = 1)
        assertEquals(listOf(sg1.filePath, sg2.filePath, sg3.filePath),
            DownloadsSorter.playQueueFor(
                listOf(sg2, other, sg3, sg1),
                DownloadsSorter.SORT_LIBRARY,
                sg2
            ))
    }

    @Test
    fun playQueueFor_unknownModeFallsBackToCrossShow() {
        // A pref written by an older build (or a mode added later) must
        // never produce an empty queue — default is the cross-show walk.
        val x = playableEp("x")
        assertEquals(listOf(x.filePath),
            DownloadsSorter.playQueueFor(listOf(x), "bogus_mode", x))
    }
}
