package com.anonrode.downloader.util

import com.anonrode.downloader.data.models.DownloadTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the extracted show-identity contract. DownloadsSorter delegates here,
 * so any drift would also change the Downloads library grouping, the player
 * queue and watch-history show keys.
 */
class ShowIdentityTest {

    private fun task(
        id: String,
        show: String,
        episodeNum: Int,
        createdAt: Long = 0L
    ): DownloadTask = DownloadTask(
        id = id,
        showTitle = show,
        episodeNum = episodeNum,
        episodeTitle = "Episode $episodeNum",
        directUrl = "https://example.invalid/$id"
    ).copy(createdAt = createdAt)

    @Test
    fun normalize_blank_becomes_unknown() {
        assertEquals("Unknown", ShowIdentity.normalizeShowKey(""))
        assertEquals("Unknown", ShowIdentity.normalizeShowKey("   "))
    }

    @Test
    fun normalize_strips_site_watermarks_and_release_noise() {
        assertEquals(
            "The General's Daughter",
            ShowIdentity.normalizeShowKey("[9jaRocks.Com] The General's Daughter 540p x265 AAC")
        )
        assertEquals("The Pitt", ShowIdentity.normalizeShowKey("The Pitt S02"))
        assertEquals("President Curtis", ShowIdentity.normalizeShowKey("President Curtis - S01"))
        assertEquals("Crystal Lake", ShowIdentity.normalizeShowKey("Crystal Lake Season 1"))
    }

    @Test
    fun normalize_keeps_plain_titles_verbatim() {
        assertEquals("Crystal Lake", ShowIdentity.normalizeShowKey("Crystal Lake"))
        assertEquals("Demon's Ascension", ShowIdentity.normalizeShowKey("  Demon's Ascension  "))
    }

    @Test
    fun episode_order_numbers_ascending_then_un_numbered() {
        val ep2 = task("e2", "Show", 2, createdAt = 20L)
        val ep1 = task("e1", "Show", 1, createdAt = 10L)
        val movie = task("m", "Show", 0, createdAt = 5L)

        val sorted = listOf(ep2, movie, ep1).sortedWith(ShowIdentity.episodeOrder)
        assertEquals(listOf("e1", "e2", "m"), sorted.map { it.id })
    }

    @Test
    fun episode_order_is_stable_by_created_at_for_equal_keys() {
        val first = task("a", "Show", 0, createdAt = 100L)
        val second = task("b", "Show", 0, createdAt = 200L)

        val sorted = listOf(second, first).sortedWith(ShowIdentity.episodeOrder)
        assertEquals(listOf("a", "b"), sorted.map { it.id })
        assertTrue(sorted[0].createdAt < sorted[1].createdAt)
    }
}
