package com.anonrode.downloader.ui.screens

import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowDetails
import com.anonrode.downloader.data.models.TaskStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mechanism 1 of the "Download Remaining (episodes)" trace
 * (investigation/12_remaining_button_trace.md): tasks enqueued before the
 * episode drawer started stamping seriesUrl carry a BLANK stamp, so their
 * folder failed the hasSeriesUrl gate and hid the whole action.
 *
 * The fix backfills the expand-time live check with a GUESS-only pseudo
 * series source built from the tasks themselves (first task's episode-page
 * URL, first non-blank site). Old tasks are never mutated; a guessed live
 * zero must cover every folder task URL before it may hide the action — a
 * wrong guess never removes a "Check remaining" path that the folder
 * already had.
 */
class LegacyUnstampedRemainingTest {

    private fun ep(url: String) = EpisodeItem(
        title = "E",
        url = url,
        episodeNum = 1,
        site = "test"
    )

    private fun task(
        id: String,
        sourceUrl: String,
        status: TaskStatus = TaskStatus.COMPLETED,
        showTitle: String = "Old Build Show",
        site: String = "nkiri",
        seriesUrl: String = ""
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

    @Test
    fun legacyFolderBackfillsPseudoSourceFromItsTasks() {
        val tasks = listOf(
            task("t1", sourceUrl = "https://nkiri/e1", status = TaskStatus.COMPLETED),
            task("t2", sourceUrl = "https://nkiri/e2", status = TaskStatus.PAUSED)
        )
        // No stamp anywhere → pseudo source from the first task's URL/site.
        assertEquals(
            listOf(
                SeriesSourceRef(
                    seriesUrl = "https://nkiri/e1",
                    site = "nkiri",
                    guessed = true
                )
            ),
            folderSeriesSources("Old Build Show", tasks)
        )
        // Stamped helpers stay empty for the pure stamped view.
        assertEquals(emptyList<SeriesSourceRef>(), seriesSourcesOf(tasks))
    }

    @Test
    fun legacyFolderShowsActionAndCountsAgainstItsSource() {
        val tasks = listOf(
            task("t1", sourceUrl = "https://nkiri/e1", status = TaskStatus.COMPLETED)
        )
        val live = listOf(
            ep("https://nkiri/e1"), ep("https://nkiri/e2"), ep("https://nkiri/e3"),
            ep("https://nkiri/e4"), ep("https://nkiri/e5"), ep("https://nkiri/e6")
        )
        // Pre-expand: the guessed source satisfies hasSeriesUrl, so the
        // "Check remaining" action is visible with no live check at all.
        assertTrue(shouldShowRemainingAction(hasSeriesUrl = true, hasTasks = true, liveRemaining = null))
        // The live check counts the pseudo source's remaining episodes:
        // completed e1 is subtracted, e2..e6 stay (5 left).
        assertEquals(
            5,
            remainingCountAcrossSources(
                liveBySource = mapOf("https://nkiri/e1" to live),
                tasks = tasks,
                guessedSources = setOf("https://nkiri/e1")
            )
        )
        assertTrue(shouldShowRemainingAction(hasSeriesUrl = true, hasTasks = true, liveRemaining = 5))
    }

    @Test
    fun unconvincingGuessedZeroKeepsCheckRemainingPath() {
        // A 20-episode folder pre-stamp; the guess (an episode page) resolves
        // to a list holding only that ONE (already-downloaded) episode. That
        // zero is not a confident series zero → unknown → path kept.
        val tasks = (1..20).map { n ->
            task("t$n", sourceUrl = "https://nkiri/e$n", status = TaskStatus.COMPLETED)
        }
        assertNull(
            remainingCountAcrossSources(
                liveBySource = mapOf("https://nkiri/e1" to listOf(ep("https://nkiri/e1"))),
                tasks = tasks,
                guessedSources = setOf("https://nkiri/e1")
            )
        )
        assertTrue(shouldShowRemainingAction(hasSeriesUrl = true, hasTasks = true, liveRemaining = null))
    }

    @Test
    fun confidentGuessedZeroStillHides() {
        // Movie shape: the folder holds ONE task whose URL IS the series page
        // (drawer enqueues for movies do exactly this), downloaded; the live
        // list covers it → a legitimate zero → hide.
        val tasks = listOf(
            task("t1", sourceUrl = "https://nepu/movie", status = TaskStatus.COMPLETED)
        )
        val count = remainingCountAcrossSources(
            liveBySource = mapOf("https://nepu/movie" to listOf(ep("https://nepu/movie"))),
            tasks = tasks,
            guessedSources = setOf("https://nepu/movie")
        )
        assertEquals(0, count)
        assertFalse(shouldShowRemainingAction(hasSeriesUrl = true, hasTasks = true, liveRemaining = 0))
    }

    @Test
    fun nonSeriesFoldersNeverGetAPseudoSource() {
        val tasks = listOf(
            task("t1", sourceUrl = "magnet:?xt=urn:btih:abc", site = "", showTitle = "Torrents"),
            task("t2", sourceUrl = "https://youtu.be/x", site = "youtube", showTitle = "Social/YouTube"),
            task("t3", sourceUrl = "https://cdn/file.mp4", site = "", showTitle = "Direct Downloads")
        )
        assertEquals(emptyList<SeriesSourceRef>(), folderSeriesSources("Torrents", listOf(tasks[0])))
        assertEquals(emptyList<SeriesSourceRef>(), folderSeriesSources("Social/YouTube", listOf(tasks[1])))
        assertEquals(emptyList<SeriesSourceRef>(), folderSeriesSources("Direct Downloads", listOf(tasks[2])))
        // Case-insensitive on the plain names.
        assertEquals(emptyList<SeriesSourceRef>(), folderSeriesSources("torrents", listOf(tasks[0])))
    }

    @Test
    fun stampedFoldersNeverFallBackToPseudo() {
        val tasks = listOf(
            task("t1", sourceUrl = "https://nkiri/e1", seriesUrl = "https://nkiri/s01"),
            task("t2", sourceUrl = "https://nkiri/e2", status = TaskStatus.PAUSED)
        )
        val sources = folderSeriesSources("Show", tasks)
        assertEquals(listOf(SeriesSourceRef(seriesUrl = "https://nkiri/s01", site = "nkiri")), sources)
        // The stamped ref is not marked guessed (regression guard on the flag).
        assertFalse(sources.first().guessed)
    }

    @Test
    fun pseudoNeedsBothSiteAndUrl() {
        // No site anywhere → no pseudo (folder keeps today's no-chip
        // behavior rather than opening a drawer against a blank dispatch).
        assertEquals(
            emptyList<SeriesSourceRef>(),
            folderSeriesSources(
                "Show",
                listOf(task("t1", sourceUrl = "https://nkiri/e1", site = ""))
            )
        )
        // No usable sourceUrl → no pseudo either.
        assertEquals(
            emptyList<SeriesSourceRef>(),
            folderSeriesSources(
                "Show",
                listOf(task("t1", sourceUrl = "", site = "nkiri"))
            )
        )
    }

    @Test
    fun loaderPropagatesTheGuessedFlagIntoTheAggregate() = runBlocking {
        val tasks = (1..10).map { n ->
            task("t$n", sourceUrl = "https://nkiri/e$n", status = TaskStatus.COMPLETED)
        }
        val sources = listOf(
            SeriesSourceRef(seriesUrl = "https://nkiri/e1", site = "nkiri", guessed = true)
        )
        val count = loadRemainingAcrossSources(
            sources = sources,
            tasks = tasks,
            showTitle = "Old Build Show"
        ) { card ->
            // Episode page: resolves to only its own (downloaded) episode.
            ShowDetails(
                show = card,
                episodes = listOf(ep(card.url))
            )
        }
        // Unconvincing guessed zero → null → "Check remaining" stays.
        assertNull(count)
    }
}
