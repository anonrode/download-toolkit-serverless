package com.anonrode.downloader.ui.screens

import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shared remaining-work semantics for the drawer chip and the Downloads
 * series action. PAUSED is remaining (not in flight); FAILED is remaining;
 * only ACTIVE/QUEUED + COMPLETED are excluded.
 */
class PausedRemainingSemanticsTest {

    private fun ep(n: Int) = EpisodeItem(
        title = "E$n",
        url = "https://example.com/e/$n",
        episodeNum = n,
        site = "test"
    )

    private fun remainingFor(statuses: List<TaskStatus>): List<EpisodeItem> {
        val episodes = statuses.indices.map { ep(it + 1) }
        val completed = episodes.filterIndexed { i, _ -> statuses[i] == TaskStatus.COMPLETED }
            .map { it.url }.toSet()
        val inFlight = episodes.filterIndexed { i, _ -> isInFlightStatus(statuses[i]) }
            .map { it.url }.toSet()
        return remainingEpisodesOf(episodes, completed, inFlight)
    }

    @Test
    fun pausedAndFailedCountAsRemaining() {
        val remaining = remainingFor(
            listOf(
                TaskStatus.COMPLETED,
                TaskStatus.PAUSED,
                TaskStatus.FAILED
            )
        )
        assertEquals(listOf("https://example.com/e/2", "https://example.com/e/3"), remaining.map { it.url })
    }

    @Test
    fun activeAndQueuedAreExcludedFromRemaining() {
        val remaining = remainingFor(
            listOf(
                TaskStatus.QUEUED,
                TaskStatus.RESOLVING,
                TaskStatus.DOWNLOADING,
                TaskStatus.VALIDATING,
                TaskStatus.PAUSED
            )
        )
        assertEquals(listOf("https://example.com/e/5"), remaining.map { it.url })
    }

    @Test
    fun inFlightPredicateIsExactlyTheFourWorkingStates() {
        assertTrue(isInFlightStatus(TaskStatus.QUEUED))
        assertTrue(isInFlightStatus(TaskStatus.RESOLVING))
        assertTrue(isInFlightStatus(TaskStatus.DOWNLOADING))
        assertTrue(isInFlightStatus(TaskStatus.VALIDATING))
        assertFalse(isInFlightStatus(TaskStatus.PAUSED))
        assertFalse(isInFlightStatus(TaskStatus.FAILED))
        assertFalse(isInFlightStatus(TaskStatus.COMPLETED))
    }

    @Test
    fun downloadsActionStaysVisibleWithPausedTasks() {
        // All non-failed tasks paused: the old `status != FAILED` math read
        // zero remaining and removed the action. It must stay at 2.
        assertEquals(
            2,
            seriesRemainingCount(2, listOf(TaskStatus.PAUSED, TaskStatus.PAUSED))
        )
        assertEquals(
            2,
            seriesRemainingCount(3, listOf(TaskStatus.COMPLETED, TaskStatus.PAUSED, TaskStatus.PAUSED))
        )
    }

    @Test
    fun downloadsActionCountsFailedAsRemaining() {
        assertEquals(
            2,
            seriesRemainingCount(3, listOf(TaskStatus.COMPLETED, TaskStatus.FAILED))
        )
    }

    @Test
    fun downloadsActionExcludesActiveAndQueued() {
        // Three known episodes, two already queued/downloading: only the
        // third is still missing.
        assertEquals(
            1,
            seriesRemainingCount(3, listOf(TaskStatus.QUEUED, TaskStatus.DOWNLOADING))
        )
        // Every episode active => nothing remaining, action hidden.
        assertEquals(
            0,
            seriesRemainingCount(2, listOf(TaskStatus.QUEUED, TaskStatus.DOWNLOADING))
        )
    }

    @Test
    fun staleStoredTotalKeepsActionUntilLiveCheckConfirmsZero() {
        // A Certain Dark Item: seriesTotal=2 and both COMPLETED. The stored
        // estimate is not authoritative, so before a live check the action
        // remains as "Check remaining".
        assertTrue(shouldShowRemainingAction(hasSeriesUrl = true, hasTasks = true, liveRemaining = null))
        // The live provider still lists only those 2 episodes => 0, hide.
        assertFalse(shouldShowRemainingAction(hasSeriesUrl = true, hasTasks = true, liveRemaining = 0))
        // The live provider gained episodes => show the exact count.
        assertTrue(shouldShowRemainingAction(hasSeriesUrl = true, hasTasks = true, liveRemaining = 3))
        // No series URL / no tasks => nothing to open.
        assertFalse(shouldShowRemainingAction(hasSeriesUrl = false, hasTasks = true, liveRemaining = null))
        assertFalse(shouldShowRemainingAction(hasSeriesUrl = true, hasTasks = false, liveRemaining = null))
    }

    @Test
    fun liveProviderListGrowsPastTheStoredTotal() {
        // Stored total at enqueue: 2. Provider now lists 5; 2 are COMPLETED.
        val liveEpisodes = (1..5).map(::ep)
        val completed = setOf(ep(1).url, ep(2).url)
        val remaining = remainingEpisodesOf(liveEpisodes, completed, emptySet())
        assertEquals(3, remaining.size)
        assertTrue(shouldShowRemainingAction(hasSeriesUrl = true, hasTasks = true, liveRemaining = remaining.size))
    }
}
