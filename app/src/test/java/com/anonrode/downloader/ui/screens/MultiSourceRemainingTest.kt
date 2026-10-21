package com.anonrode.downloader.ui.screens

import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.ShowDetails
import com.anonrode.downloader.data.models.TaskStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mechanism 2 of the "Download Remaining (episodes)" trace
 * (investigation/12_remaining_button_trace.md): one Downloads folder can
 * merge SEVERAL series sources — the same clean show title stamped with
 * multiple seriesUrls (two sites, or per-season posts on one site). The
 * exact live count must sum the remaining set across EVERY distinct
 * source instead of consulting only the first task's seriesUrl, where a
 * fully-downloaded first source wrongly zeroed (hid) the whole action.
 */
class MultiSourceRemainingTest {

    private fun ep(url: String) = EpisodeItem(
        title = "E",
        url = url,
        episodeNum = 1,
        site = "test"
    )

    private fun task(
        id: String,
        sourceUrl: String,
        seriesUrl: String,
        status: TaskStatus,
        showTitle: String = "Show",
        site: String = "nepu"
    ) = DownloadTask(
        id = id,
        showTitle = showTitle,
        episodeNum = 1,
        episodeTitle = "E $id",
        directUrl = "https://cdn/$id.bin",
        sourceUrl = sourceUrl,
        status = status,
        site = site,
        seriesUrl = seriesUrl
    )

    // ── THE reported fixture: folder merges a fully-downloaded source with
    //    one that still has episodes. Old behavior loaded only the FIRST
    //    task's seriesUrl (fully downloaded → live 0 → action hidden); the
    //    aggregate must show the other source's 3. ──

    @Test
    fun multiSourceFolderShowsActionForTheSourceWithEpisodesLeft() {
        val tasks = listOf(
            task("t1", sourceUrl = "https://s/a/e1", seriesUrl = "https://s/a", status = TaskStatus.COMPLETED),
            task("t2", sourceUrl = "https://s/a/e2", seriesUrl = "https://s/a", status = TaskStatus.COMPLETED)
        )
        val liveBySource = mapOf(
            // Source A: fully downloaded (live remaining alone would be 0).
            "https://s/a" to listOf(ep("https://s/a/e1"), ep("https://s/a/e2")),
            // Source B: three episodes the user never enqueued.
            "https://s/b" to listOf(
                ep("https://s/b/e1"), ep("https://s/b/e2"), ep("https://s/b/e3")
            )
        )
        assertEquals(3, remainingCountAcrossSources(liveBySource, tasks))
        // The OLD single-source computation proves the bug existed here:
        assertEquals(0, remainingCountAcrossSources(mapOf("https://s/a" to listOf(ep("https://s/a/e1"), ep("https://s/a/e2"))), tasks))
        assertFalse(shouldShowRemainingAction(hasSeriesUrl = true, hasTasks = true, liveRemaining = 0))
        // …and the aggregate turns it back on with the exact count.
        assertTrue(shouldShowRemainingAction(hasSeriesUrl = true, hasTasks = true, liveRemaining = 3))
    }

    @Test
    fun fullyDownloadedSingleSourceStillHides() {
        // Regression guard on the contract the single-source code always
        // had: every source recognized and everything downloaded → hide.
        val tasks = listOf(
            task("t1", sourceUrl = "https://s/a/e1", seriesUrl = "https://s/a", status = TaskStatus.COMPLETED)
        )
        assertEquals(0, remainingCountAcrossSources(mapOf("https://s/a" to listOf(ep("https://s/a/e1"))), tasks))
        assertFalse(shouldShowRemainingAction(hasSeriesUrl = true, hasTasks = true, liveRemaining = 0))
    }

    @Test
    fun failedSourceKeepsCheckRemainingPathWhenAllKnownWorkIsDone() {
        // Source A loaded and says 0; source B failed (empty list = unknown).
        // Hiding would claim the folder is complete on partial knowledge.
        val tasks = listOf(
            task("t1", sourceUrl = "https://s/a/e1", seriesUrl = "https://s/a", status = TaskStatus.COMPLETED),
            task("t2", sourceUrl = "https://s/b/e1", seriesUrl = "https://s/b", status = TaskStatus.COMPLETED)
        )
        assertNull(
            remainingCountAcrossSources(
                mapOf(
                    "https://s/a" to listOf(ep("https://s/a/e1")),
                    "https://s/b" to emptyList()
                ),
                tasks
            )
        )
        // null live remaining keeps the "Check remaining" path.
        assertTrue(shouldShowRemainingAction(hasSeriesUrl = true, hasTasks = true, liveRemaining = null))
        // With nothing known at all the path stays too.
        assertNull(remainingCountAcrossSources(mapOf("https://s/a" to emptyList()), tasks))
    }

    @Test
    fun legacyUnstampedTasksSubtractAgainstEverySource() {
        // Pre-stamp rows carry a blank seriesUrl; their URLs self-scope to
        // whichever source they came from, so they are subtracted everywhere.
        val tasks = listOf(
            task("t1", sourceUrl = "https://s/a/e1", seriesUrl = "https://s/a", status = TaskStatus.COMPLETED),
            task("t2", sourceUrl = "https://s/a/e2", seriesUrl = "", status = TaskStatus.COMPLETED)
        )
        assertEquals(
            1,
            remainingCountAcrossSources(
                mapOf("https://s/a" to listOf(ep("https://s/a/e1"), ep("https://s/a/e2"))),
                tasks
            )
        )
    }

    @Test
    fun crossSourceTasksDoNotZeroAnotherSource() {
        // A task stamped to source A never subtracts source B's rows, even
        // on a pathological shared URL — the count may only inflate (action
        // stays visible), never hide.
        val tasks = listOf(
            task("t1", sourceUrl = "https://s/shared", seriesUrl = "https://s/a", status = TaskStatus.COMPLETED)
        )
        assertEquals(
            2,
            remainingCountAcrossSources(
                mapOf(
                    "https://s/a" to listOf(ep("https://s/shared")),
                    "https://s/b" to listOf(ep("https://s/shared"), ep("https://s/b/e1"))
                ),
                tasks
            )
        )
    }

    @Test
    fun seriesSourcesAreDistinctInFirstSeenOrder() {
        val tasks = listOf(
            task("t1", sourceUrl = "x", seriesUrl = "https://s/a", status = TaskStatus.COMPLETED, site = "nepu"),
            task("t2", sourceUrl = "y", seriesUrl = "https://s/a", status = TaskStatus.COMPLETED, site = "nkiri"),
            task("t3", sourceUrl = "z", seriesUrl = "https://s/b", status = TaskStatus.QUEUED, site = "nkiri"),
            task("t4", sourceUrl = "w", seriesUrl = "https://s/a", status = TaskStatus.PAUSED, site = "naijaprey")
        )
        assertEquals(
            listOf(
                SeriesSourceRef(seriesUrl = "https://s/a", site = "nepu"),
                SeriesSourceRef(seriesUrl = "https://s/b", site = "nkiri")
            ),
            seriesSourcesOf(tasks)
        )
        assertEquals(emptyList<SeriesSourceRef>(), seriesSourcesOf(emptyList()))
    }

    @Test
    fun loaderBoundsParallelScrapesAndAggregatesEverySource() = runBlocking {
        val sources = listOf(
            SeriesSourceRef(seriesUrl = "https://s/a", site = "nepu"),
            SeriesSourceRef(seriesUrl = "https://s/b", site = "nkiri"),
            SeriesSourceRef(seriesUrl = "https://s/c", site = "nepu")
        )
        val tasks = listOf(
            task("t1", sourceUrl = "https://s/a/e1", seriesUrl = "https://s/a", status = TaskStatus.COMPLETED),
            task("t2", sourceUrl = "https://s/b/e1", seriesUrl = "https://s/b", status = TaskStatus.COMPLETED),
            task("t3", sourceUrl = "https://s/c/e1", seriesUrl = "https://s/c", status = TaskStatus.DOWNLOADING)
        )
        val mutex = Mutex()
        var active = 0
        var maxActive = 0
        val count = loadRemainingAcrossSources(
            sources = sources,
            tasks = tasks,
            showTitle = "Show"
        ) { card ->
            mutex.withLock {
                active++
                if (active > maxActive) maxActive = active
            }
            delay(20)
            val rows = when (card.url) {
                "https://s/a" -> listOf(ep("https://s/a/e1"))
                "https://s/b" -> listOf(ep("https://s/b/e1"), ep("https://s/b/e2"))
                else -> listOf(
                    ep("https://s/c/e1"), ep("https://s/c/e2"), ep("https://s/c/e3")
                )
            }
            mutex.withLock { active-- }
            ShowDetails(
                show = ShowCard(title = card.title, url = card.url, site = card.site),
                episodes = rows
            )
        }
        // a: 1 row downloaded → 0 · b: 2 rows, 1 done → 1 · c: 3 rows,
        // 1 in flight → 2. Sum must be 3.
        assertEquals(3, count)
        assertTrue(maxActive <= SOURCE_LOAD_PARALLELISM)
    }

    @Test
    fun loaderDegradesASourceFailureToUnknownNotZero() = runBlocking {
        val sources = listOf(
            SeriesSourceRef(seriesUrl = "https://s/a", site = "nepu"),
            SeriesSourceRef(seriesUrl = "https://s/b", site = "broken")
        )
        val tasks = listOf(
            task("t1", sourceUrl = "https://s/a/e1", seriesUrl = "https://s/a", status = TaskStatus.COMPLETED)
        )
        val count = loadRemainingAcrossSources(
            sources = sources,
            tasks = tasks,
            showTitle = "Show"
        ) { card ->
            if (card.site == "broken") throw IllegalStateException("network down")
            ShowDetails(
                show = card,
                episodes = listOf(ep("https://s/a/e1"))
            )
        }
        // a says 0 but b is unknown → null, keeping "Check remaining".
        assertNull(count)
        // No sources at all → nothing to check.
        assertNull(
            loadRemainingAcrossSources(sources = emptyList(), tasks = tasks, showTitle = "Show") {
                ShowDetails(show = it)
            }
        )
    }
}
