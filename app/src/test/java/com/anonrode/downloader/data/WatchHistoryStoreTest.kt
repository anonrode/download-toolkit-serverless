package com.anonrode.downloader.data

import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the watch-history contract from the owner directive: 92%/ENDED
 * transitions, the 20-second accidental-tap guard, RESUME -> UP_NEXT ->
 * FETCH_NEXT resolution, deletion semantics, shelf ordering and formatting.
 */
class WatchHistoryStoreTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun record(
        id: String = "/files/show_s01e01.mp4",
        showKey: String = "Crystal Lake",
        showTitle: String = "Crystal Lake",
        kind: String = WatchHistoryCore.KIND_SERIES,
        filePath: String = id,
        sourceUrl: String = "https://site/ep1",
        seriesUrl: String = "https://site/crystal-lake",
        site: String = "naijaprey",
        posterUrl: String = "",
        episodeTitle: String = "Episode 1",
        season: Int = 1,
        episode: Int = 1,
        positionMs: Long = 0L,
        durationMs: Long = 0L,
        isWatched: Boolean = false,
        updatedAtMs: Long = 0L
    ) = WatchRecord(
        id = id, showKey = showKey, showTitle = showTitle, kind = kind,
        filePath = filePath, sourceUrl = sourceUrl, seriesUrl = seriesUrl,
        site = site, posterUrl = posterUrl, episodeTitle = episodeTitle,
        season = season, episode = episode, positionMs = positionMs,
        durationMs = durationMs, isWatched = isWatched, updatedAtMs = updatedAtMs
    )

    private fun task(
        id: String,
        filePath: String,
        season: Int = 1,
        episode: Int = 1,
        showTitle: String = "Crystal Lake",
        status: TaskStatus = TaskStatus.COMPLETED,
        createdAt: Long = 0L
    ) = DownloadTask(
        id = id,
        showTitle = showTitle,
        episodeNum = episode,
        episodeTitle = "Episode $episode",
        directUrl = "https://site/$id",
        status = status,
        filePath = filePath,
        sourceUrl = "https://source/$id",
        seriesUrl = "https://site/crystal-lake",
        season = season,
        episode = episode,
        createdAt = createdAt
    )

    // ------------------------------------------------------------ serialization

    @Test
    fun record_round_trips_through_json() {
        val original = record(positionMs = 42_000L, durationMs = 100_000L, updatedAtMs = 7L)
        val decoded = json.decodeFromString<WatchRecord>(json.encodeToString(WatchRecord.serializer(), original))
        assertEquals(original, decoded)
    }

    // ---------------------------------------------------------------- recording

    @Test
    fun brief_open_is_ignored_when_no_record_exists() {
        val seed = record().copy(updatedAtMs = 1L)
        val result = WatchHistoryCore.recordProgress(
            records = emptyList(), seed = seed, positionMs = 5_000L,
            durationMs = 600_000L, nowMs = 100L
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun brief_open_updates_an_existing_record() {
        val existing = record(positionMs = 60_000L, durationMs = 600_000L, updatedAtMs = 3L)
        val seed = record()
        val result = WatchHistoryCore.recordProgress(
            records = listOf(existing), seed = seed, positionMs = 8_000L,
            durationMs = 600_000L, nowMs = 50L
        )
        assertEquals(1, result.size)
        assertEquals(8_000L, result[0].positionMs)
        assertEquals(50L, result[0].updatedAtMs)
    }

    @Test
    fun very_short_clip_is_recorded_despite_the_20s_guard() {
        val result = WatchHistoryCore.recordProgress(
            records = emptyList(), seed = record(), positionMs = 4_000L,
            durationMs = 12_000L, nowMs = 9L
        )
        assertEquals(1, result.size)
        assertEquals(4_000L, result[0].positionMs)
    }

    @Test
    fun reaching_92_percent_marks_watched() {
        val result = WatchHistoryCore.recordProgress(
            records = emptyList(), seed = record(), positionMs = 92_000L,
            durationMs = 100_000L, nowMs = 10L
        )
        assertTrue(result[0].isWatched)
    }

    @Test
    fun being_within_15s_of_the_end_marks_watched() {
        val result = WatchHistoryCore.recordProgress(
            records = emptyList(), seed = record(), positionMs = 88_000L,
            durationMs = 100_000L, nowMs = 11L
        )
        assertTrue(result[0].isWatched)
    }

    @Test
    fun watched_never_unwatches_on_rewind() {
        val existing = record(positionMs = 95_000L, durationMs = 100_000L, isWatched = true)
        val result = WatchHistoryCore.recordProgress(
            records = listOf(existing), seed = record(), positionMs = 30_000L,
            durationMs = 100_000L, nowMs = 12L
        )
        assertTrue(result[0].isWatched)
    }

    @Test
    fun mark_ended_records_completion_at_duration() {
        val result = WatchHistoryCore.markEnded(
            records = emptyList(), seed = record(), durationMs = 600_000L, nowMs = 99L
        )
        assertTrue(result[0].isWatched)
        assertEquals(600_000L, result[0].positionMs)
        assertEquals(99L, result[0].updatedAtMs)
    }

    // ---------------------------------------------------------- continue cards

    @Test
    fun mid_episode_resolves_to_resume() {
        val rec = record(positionMs = 30_000L, durationMs = 600_000L, updatedAtMs = 100L)
        val onDisk = task("t1", rec.filePath)
        val cards = WatchHistoryCore.resolveContinueCards(
            records = listOf(rec), tasks = listOf(onDisk), fileExists = { true }
        )
        assertEquals(1, cards.size)
        assertEquals(ContinueState.RESUME, cards[0].state)
        assertEquals("t1", cards[0].task?.id)
    }

    @Test
    fun mid_episode_with_deleted_file_is_not_resume() {
        val rec = record(positionMs = 30_000L, durationMs = 600_000L, isWatched = true, filePath = "")
        val cards = WatchHistoryCore.resolveContinueCards(
            records = listOf(rec),
            tasks = listOf(task("t1", "/gone.mp4", season = 1, episode = 1)),
            fileExists = { false }
        )
        // Up Next exists (E2 on disk) or FETCH_NEXT; either way not RESUME.
        assertTrue(cards.isEmpty() || cards[0].state != ContinueState.RESUME)
    }

    @Test
    fun finished_episode_with_next_file_resolves_to_up_next() {
        val watched = record(
            id = "/files/s01e01.mp4", filePath = "", isWatched = true,
            season = 1, episode = 1, updatedAtMs = 100L
        )
        val e1 = task("t1", "/files/s01e01.mp4", season = 1, episode = 1)
        val e2 = task("t2", "/files/s01e02.mp4", season = 1, episode = 2)
        val cards = WatchHistoryCore.resolveContinueCards(
            records = listOf(watched), tasks = listOf(e2, e1), fileExists = { true }
        )
        assertEquals(1, cards.size)
        assertEquals(ContinueState.UP_NEXT, cards[0].state)
        assertEquals("t2", cards[0].task?.id)
        assertEquals(2, cards[0].episode)
    }

    @Test
    fun finished_episode_without_next_file_resolves_to_fetch_next() {
        val watched = record(
            id = "/files/s01e01.mp4", filePath = "", isWatched = true,
            season = 1, episode = 1, seriesUrl = "https://site/series", updatedAtMs = 100L
        )
        val e1 = task("t1", "/files/s01e01.mp4", season = 1, episode = 1)
        val cards = WatchHistoryCore.resolveContinueCards(
            records = listOf(watched), tasks = listOf(e1), fileExists = { true }
        )
        assertEquals(1, cards.size)
        assertEquals(ContinueState.FETCH_NEXT, cards[0].state)
        assertNull(cards[0].task)
        assertEquals("https://site/series", cards[0].seriesUrl)
    }

    @Test
    fun continue_cards_are_newest_first_and_capped_at_three() {
        val recs = (1..4).map { i ->
            record(
                id = "/files/s$i.mp4", showKey = "Show $i", showTitle = "Show $i",
                filePath = "/files/s$i.mp4", isWatched = false, positionMs = 30_000L,
                durationMs = 600_000L, updatedAtMs = i * 10L
            )
        }
        val cards = WatchHistoryCore.resolveContinueCards(
            records = recs, tasks = emptyList(), fileExists = { true }
        )
        assertEquals(3, cards.size)
        assertEquals(listOf("Show 4", "Show 3", "Show 2"), cards.map { it.showTitle })
    }

    // ---------------------------------------------------------------- deletions

    @Test
    fun watched_only_deletion_keeps_watched_memory_without_path() {
        val watched = record(id = "/files/e1.mp4", filePath = "/files/e1.mp4", isWatched = true)
        val inProgress = record(id = "/files/e2.mp4", filePath = "/files/e2.mp4", isWatched = false)
        val untouched = record(id = "/files/e3.mp4", filePath = "/files/e3.mp4", isWatched = true)
        val result = WatchHistoryCore.onFilesDeleted(
            records = listOf(watched, inProgress, untouched),
            deletedPaths = setOf("/files/e1.mp4", "/files/e2.mp4"),
            clearShowHistory = false
        )
        assertEquals(2, result.size)
        val kept = result.first { it.sourceUrl == watched.sourceUrl }
        assertEquals("", kept.filePath)
        assertTrue(kept.isWatched)
        assertEquals("/files/e3.mp4", result.first { it.sourceUrl == untouched.sourceUrl }.filePath)
        assertTrue(result.none { it.sourceUrl == inProgress.sourceUrl })
    }

    @Test
    fun entire_show_deletion_removes_all_records_for_that_show() {
        val a = record(id = "/f/a.mp4", showKey = "Show A", showTitle = "Show A")
        val b = record(id = "/f/b.mp4", showKey = "Show A", showTitle = "Show A", episode = 2)
        val other = record(id = "/f/c.mp4", showKey = "Show B", showTitle = "Show B")
        val result = WatchHistoryCore.onFilesDeleted(
            records = listOf(a, b, other),
            deletedPaths = setOf("/f/a.mp4", "/f/b.mp4"),
            deletedShowKeys = setOf("Show A"),
            clearShowHistory = true
        )
        assertEquals(1, result.size)
        assertEquals("Show B", result[0].showKey)
    }

    // ------------------------------------------------------------------ helpers

    @Test
    fun format_time_left_matches_the_directive_examples() {
        assertEquals("Resume", WatchHistoryCore.formatTimeLeft(0L, 0L))
        assertEquals("<1m left", WatchHistoryCore.formatTimeLeft(55_000L, 60_000L))
        assertEquals("23m left", WatchHistoryCore.formatTimeLeft(1_000_000L, 2_380_000L))
    }

    @Test
    fun last_watched_social_tile_moves_to_front_stably() {
        val shelf = listOf(
            task("a", "/f/a.mp4", showTitle = "Social/IG", episode = 1),
            task("b", "/f/b.mp4", showTitle = "Social/YT", episode = 1),
            task("c", "/f/c.mp4", showTitle = "Social/TT", episode = 1)
        )
        val records = listOf(
            record(id = "/f/c.mp4", kind = WatchHistoryCore.KIND_SOCIAL, filePath = "/f/c.mp4", updatedAtMs = 50L)
        )
        val ordered = WatchHistoryCore.orderShelfWithLastWatched(shelf, records)
        assertEquals(listOf("c", "a", "b"), ordered.map { it.id })
    }

    @Test
    fun shelf_order_is_unchanged_without_social_history() {
        val shelf = listOf(task("a", "/f/a.mp4"), task("b", "/f/b.mp4"))
        assertEquals(shelf, WatchHistoryCore.orderShelfWithLastWatched(shelf, emptyList()))
    }

    @Test
    fun watched_episode_matching_uses_url_or_coordinates() {
        val rec = record(sourceUrl = "https://site/ep4", season = 1, episode = 4, isWatched = true)
        assertTrue(WatchHistoryCore.isWatchedEpisode(listOf(rec), "https://site/ep4", 0, 0))
        assertTrue(WatchHistoryCore.isWatchedEpisode(listOf(rec), "", 1, 4))
        assertFalse(WatchHistoryCore.isWatchedEpisode(listOf(rec), "https://site/ep5", 1, 5))
    }
}
