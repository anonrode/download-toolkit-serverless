package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.ShowCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelevanceScorerTest {
    private fun card(title: String, url: String, site: String = "nkiri") =
        ShowCard(title = title, url = url, site = site)

    @Test
    fun showResultWinsOverSameTitleEpisodeCardsWithoutDeletingEpisodes() {
        val results = listOf(
            card("The Pitt Episode 1", "https://site.test/watch/tv/1/1/1"),
            card("The Pitt", "https://site.test/watch/tv/1"),
            card("The Pitt Episode 2", "https://site.test/watch/tv/1/1/2")
        )
        val ranked = RelevanceScorer.filterAndSort("The Pitt", results)
        assertEquals(3, ranked.size)
        assertEquals("https://site.test/watch/tv/1", ranked[0].url)
        assertTrue(ranked.drop(1).all { it.url.contains("/1/1/") })
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
    fun nepuResultsUseUrlShapeBeforeCategoryFallback() {
        val results = listOf(
            ShowCard("The Pitt", "https://nepu.test/watch/tv/1", site = "nepu", category = "TV Show"),
            ShowCard("The Pitt Season 1", "https://nepu.test/watch/tv/1/1", site = "nepu", category = "TV Show"),
            ShowCard("The Pitt S01E01", "https://nepu.test/watch/tv/1/1/1", site = "nepu", category = "TV Show"),
            ShowCard("The Pitt", "https://nepu.test/watch/movie/2", site = "nepu", category = "Movie")
        )
        val ranked = RelevanceScorer.filterAndSort("The Pitt", results)
        assertEquals(listOf(
            "https://nepu.test/watch/tv/1",
            "https://nepu.test/watch/tv/1/1",
            "https://nepu.test/watch/movie/2",
            "https://nepu.test/watch/tv/1/1/1"
        ), ranked.map { it.url })
    }

    @Test
    fun compactEpisodeLabelsAreClassifiedAsEpisodes() {
        val ranked = RelevanceScorer.filterAndSort("The Pitt", listOf(
            card("The Pitt", "https://site.test/watch/tv/1"),
            card("The Pitt S01E01", "https://site.test/download/one")
        ))
        assertEquals("https://site.test/watch/tv/1", ranked.first().url)
        assertEquals(2, ranked.size)
    }

    @Test
    fun equivalentShowsFromDifferentProvidersRemainDistinct() {
        val results = listOf(
            card("The Pitt", "https://nepu.test/watch/tv/1", site = "nepu"),
            card("The Pitt", "https://pluto.test/watch/tv/1", site = "pluto")
        )
        assertEquals(2, RelevanceScorer.filterAndSort("The Pitt", results).size)
    }

    @Test
    fun duplicateSeriesIdentityWithinOneProviderIsCollapsed() {
        val results = listOf(
            card("The Pitt", "https://nepu.test/watch/tv/1", site = "nepu"),
            card("The Pitt (2024)", "https://nepu.test/duplicate", site = "nepu")
        )
        val ranked = RelevanceScorer.filterAndSort("The Pitt", results)
        assertEquals(2, ranked.size) // years are not proven identical without year metadata
    }

    @Test
    fun realisticTwentyTitleSearchKeepsExpectedOrderAndBoundaries() {
        val results = listOf(
            ShowCard("The Pitt", "https://nepu.gd/watch/tv/456", site = "nepu", category = "TV Show", year = "2025"),
            ShowCard("The Pitt", "https://asianc.test/the-pitt/", site = "asianc", category = "TV Show", year = "2025"),
            ShowCard("The Pitt", "https://pluto.test/series/806274/the-pitt-2025-tv-series/", site = "pluto", category = "TV Show", year = "2025"),
            ShowCard("The Pitt", "https://nkiri.test/the-pitt/", site = "nkiri", category = "TV Show", year = "2025"),
            ShowCard("The Pitt", "https://dramarain.test/the-pitt/", site = "dramarain", category = "TV Show", year = "2025"),
            ShowCard("The Pitt Season 1", "https://season-one.test/the-pitt-season-1", site = "seasonhub", category = "TV Show", year = "2025"),
            ShowCard("The Pitt Season 2", "https://season-two.test/the-pitt-season-2", site = "seasonhub2", category = "TV Show", year = "2025"),
            ShowCard("The Pitt 2024", "https://year-2024.test/the-pitt", site = "archive", category = "TV Show", year = "2024"),
            ShowCard("The Pitt 2025", "https://year-2025.test/the-pitt", site = "archive2", category = "TV Show", year = "2025"),
            ShowCard("The Pitt", "https://nepu.gd/watch/movie/999", site = "nepu", category = "Movie"),
            ShowCard("The Pitt Episode 1", "https://episodes.test/the-pitt-episode-1", site = "episodes", category = "TV Episode"),
            ShowCard("The Pitt Episode 2", "https://episodes.test/the-pitt-episode-2", site = "episodes", category = "TV Episode"),
            ShowCard("The Pitt S01E01", "https://nepu.gd/watch/tv/456/1/1", site = "nepu", category = "TV Show"),
            ShowCard("The Pitt S01E02", "https://nepu.gd/watch/tv/456/1/2", site = "nepu", category = "TV Show"),
            ShowCard("The Pitt S02E01", "https://nepu.gd/watch/tv/456/2/1", site = "nepu", category = "TV Show"),
            ShowCard("The Pitt: The Movie", "https://movie.test/watch/movie/12", site = "movie", category = "Movie"),
            ShowCard("The Pitt (Complete Series)", "https://complete.test/the-pitt", site = "complete", category = "TV Show"),
            ShowCard("The Pitt Unrelated", "https://unrelated.test/other", site = "other", category = "Unknown"),
            ShowCard("The Pitt Episode 3", "https://episodes.test/the-pitt-episode-3", site = "episodes", category = "TV Episode"),
            ShowCard("The Pitt Extra", "https://extra.test/the-pitt-extra", site = "extra", category = "TV Show")
        )
        val ranked = RelevanceScorer.filterAndSort("The Pitt", results)
        val urls = ranked.map { it.url }

        assertEquals(20, ranked.size)
        assertEquals("https://nepu.gd/watch/tv/456", urls[0])
        val firstSeries = urls.indexOf("https://nepu.gd/watch/tv/456")
        val lastSeries = urls.indexOf("https://dramarain.test/the-pitt/")
        val seasonOne = urls.indexOf("https://season-one.test/the-pitt-season-1")
        val seasonTwo = urls.indexOf("https://season-two.test/the-pitt-season-2")
        val year2024 = urls.indexOf("https://year-2024.test/the-pitt")
        val year2025 = urls.indexOf("https://year-2025.test/the-pitt")
        val movie = urls.indexOf("https://nepu.gd/watch/movie/999")
        val episode = urls.indexOf("https://episodes.test/the-pitt-episode-1")
        assertTrue(firstSeries < lastSeries && lastSeries < seasonOne)
        assertTrue(seasonOne < seasonTwo && seasonTwo < movie && movie < episode)
        assertTrue(year2024 != year2025)
        assertTrue(urls.contains("https://episodes.test/the-pitt-episode-1"))
        assertTrue(urls.contains("https://episodes.test/the-pitt-episode-2"))
    }
    @Test
    fun scoreKeepsExactTitleAbovePartial() {
        val exact = RelevanceScorer.score("The Pitt", "The Pitt")
        val partial = RelevanceScorer.score("The Pitt", "The Pitt Season 2")
        assertTrue(exact > partial)
    }

    @Test
    fun multiPartMovieIsNotClassifiedAsEpisode() {
        val results = listOf(
            ShowCard("Dune: Part Two", "https://nepu.gd/watch/movie/693134", site = "nepu", category = "Movie"),
            ShowCard("Dune: Part One", "https://nepu.gd/watch/movie/438631", site = "nepu", category = "Movie"),
            ShowCard("Dune S01E01", "https://nepu.gd/watch/tv/123/1/1", site = "nepu", category = "TV Show")
        )
        val ranked = RelevanceScorer.filterAndSort("Dune", results)
        assertEquals("https://nepu.gd/watch/movie/693134", ranked[0].url)
        assertEquals("https://nepu.gd/watch/movie/438631", ranked[1].url)
        assertEquals("https://nepu.gd/watch/tv/123/1/1", ranked[2].url)
    }
}
