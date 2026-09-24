package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.ShowCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelevanceScorerTest {
    private fun card(title: String, url: String, site: String = "nkiri") =
        ShowCard(title = title, url = url, site = site)

    @Test
    fun showResultWinsOverSameTitleEpisodeCards() {
        val results = listOf(
            card("The Pitt Episode 1", "https://site.test/watch/tv/1/1/1"),
            card("The Pitt", "https://site.test/series/the-pitt"),
            card("The Pitt Episode 2", "https://site.test/watch/tv/1/1/2")
        )
        val ranked = RelevanceScorer.filterAndSort("The Pitt", results)
        assertEquals(1, ranked.size)
        assertEquals("https://site.test/series/the-pitt", ranked.single().url)
    }

    @Test
    fun episodeOnlyResultsAreNotDiscarded() {
        val results = listOf(
            card("The Pitt Episode 1", "https://site.test/watch/tv/1/1/1"),
            card("The Pitt Episode 2", "https://site.test/watch/tv/1/1/2")
        )
        val ranked = RelevanceScorer.filterAndSort("The Pitt", results)
        assertEquals(2, ranked.size)
    }

    @Test
    fun differentYearsRemainDistinct() {
        val results = listOf(
            card("The Pitt 2024", "https://site.test/series/the-pitt-2024"),
            card("The Pitt 2025", "https://site.test/series/the-pitt-2025")
        )
        val ranked = RelevanceScorer.filterAndSort("The Pitt", results)
        assertEquals(2, ranked.size)
    }

    @Test
    fun seasonResultsRemainDistinct() {
        val results = listOf(
            card("The Pitt Season 1", "https://site.test/series/the-pitt-s01"),
            card("The Pitt Season 2", "https://site.test/series/the-pitt-s02")
        )
        val ranked = RelevanceScorer.filterAndSort("The Pitt", results)
        assertEquals(2, ranked.size)
    }

    @Test
    fun seasonHubIsNotAnEpisode() {
        val results = listOf(
            card("The Pitt Season 1", "https://site.test/watch/tv/1"),
            card("The Pitt Season 1 Episode 1", "https://site.test/watch/tv/1/1/1")
        )
        val ranked = RelevanceScorer.filterAndSort("The Pitt", results)
        assertEquals(1, ranked.size)
        assertEquals("https://site.test/watch/tv/1", ranked.single().url)
    }

    @Test
    fun scoreKeepsExactTitleAbovePartial() {
        val exact = RelevanceScorer.score("The Pitt", "The Pitt")
        val partial = RelevanceScorer.score("The Pitt", "The Pitt Season 2")
        assertTrue(exact > partial)
    }
}
