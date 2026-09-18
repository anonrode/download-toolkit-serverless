package com.anonrode.downloader.util

import com.anonrode.downloader.data.models.ShowCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for ExplicitContentFilter.
 * Verifies zero false positives on mainstream studio movies/series (including 18+, R, Unrated)
 * and 100% detection of pornographic/adult titles and taxonomy categories.
 */
class ExplicitContentFilterTest {

    @Test
    fun mainstreamTitles_mustNotBeBlocked() {
        val safeTitles = listOf(
            "Deadpool (2016) 1080p",
            "Deadpool 2 (2018) [R-Rated]",
            "Game of Thrones S01 (18+)",
            "The Boys S03 18+",
            "Fifty Shades of Grey (2015)",
            "Fifty Shades Darker (2017)",
            "365 Days (2020)",
            "Basic Instinct (1992)",
            "Sex Education S01",
            "Sex and the City (2008)",
            "Masters of Sex S01",
            "Adult Beginners (2014)",
            "Young Adult (2011)",
            "The Adults (2023)",
            "Grown Ups (2010)",
            "American Pie (1999) Unrated",
            "The Wolf of Wall Street (2013)",
            "R-Point (2004)",
            "Scary Movie (2000) R",
            "Saw X (2023) 18+",
            "Terrifier 2 (2022) Unrated",
            "BLOOD BROTHERS (2026)",
            "ADUKE FITINA (2026)",
            "Outlander: Blood of My Blood S02",
            "25 Years of You (2026) Season 1",
            "HELL MODE (2026) Season 2",
            "xXx: Return of Xander Cage (2017)",
            "xXx (2002) 1080p",
            "xXx: State of the Union (2005)",
            "The Sex Lives of College Girls S02",
            "Nude Tuesday (2022)",
            "18+ Journey of Love (2023)"
        )

        for (title in safeTitles) {
            assertFalse(
                "Mainstream title should NOT be filtered as explicit: '$title'",
                ExplicitContentFilter.isExplicit(title)
            )
        }
    }

    @Test
    fun explicitTitles_mustBeBlocked() {
        val adultTitles = listOf(
            "Hot Girl Nude Leaks 2026",
            "Celebrity Leaked Nudes Compilation",
            "XXX JAV HD Episode 1",
            "Brazzers - Big Tits In Action",
            "Step Sister Sex Tape Leak",
            "Hentai Uncensored Episode 04",
            "Erotica 18+ Adult Movie",
            "X-Rated Adult Cinema (2025)",
            "OnlyFans Leaked Videos Pack",
            "Japanese JAV Star Uncensored",
            "Pornstar Hardcore Compilation",
            "Blowjob & Deepthroat Special",
            "Nude Sex Scenes Collection 18+",
            "How We Fuck In The Shadows XXX (2024) [+18]",
            "BBC Threesomes 5 (2025) [+18]",
            "Enjoy Your Ride (2026) [+18]",
            "Sex: 3 erotic positions to try when you are bored in the bedroom",
            "Maheeda: 10 times singer showed off her erotic hottest photos!"
        )

        for (title in adultTitles) {
            assertTrue(
                "Explicit title MUST be filtered: '$title'",
                ExplicitContentFilter.isExplicit(title)
            )
        }
    }

    @Test
    fun taxonomyCategories_mustBeBlocked() {
        // Safe title, but explicit taxonomy category (observed on NaijaVault and 9jaRocks)
        assertTrue(
            ExplicitContentFilter.isExplicit("Random Innocent Title", listOf("FULL Adult Video"))
        )
        assertTrue(
            ExplicitContentFilter.isExplicit("Episode 1", listOf("XXX"))
        )
        assertTrue(
            ExplicitContentFilter.isExplicit("Special Post", listOf("[+18] Section"))
        )
        assertTrue(
            ExplicitContentFilter.isExplicit("Video Clip", listOf("Porn"))
        )
        assertTrue(
            ExplicitContentFilter.isExplicit("Short Movie", listOf("Hentai"))
        )
        assertTrue(
            ExplicitContentFilter.isExplicit("Drama", listOf("Erotica"))
        )
    }

    @Test
    fun filterSafe_dropsExplicitCardsOnly() {
        val cards = listOf(
            ShowCard(title = "Deadpool (2016)", url = "http://a", site = "test"),
            ShowCard(title = "Hot XXX JAV Clip", url = "http://b", site = "test"),
            ShowCard(title = "Game of Thrones S01 (18+)", url = "http://c", site = "test"),
            ShowCard(title = "OnlyFans Leaked Pack", url = "http://d", site = "test")
        )

        val safe = ExplicitContentFilter.filterSafe(cards)
        val titles = safe.map { it.title }
        assertTrue(titles.contains("Deadpool (2016)"))
        assertTrue(titles.contains("Game of Thrones S01 (18+)"))
        assertFalse(titles.contains("Hot XXX JAV Clip"))
        assertFalse(titles.contains("OnlyFans Leaked Pack"))
    }

    @Test
    fun trendingFeed_mergeRoundRobin_respectsExplicitFilterFlag() {
        val siteCards = listOf(
            listOf(
                ShowCard(title = "Safe Movie", url = "http://1", site = "nkiri"),
                ShowCard(title = "Hot XXX Adult Movie", url = "http://2", site = "naijavault")
            )
        )

        val filtered = com.anonrode.downloader.providers.TrendingFeed.mergeRoundRobin(siteCards, filterExplicit = true)
        assertEquals(listOf("Safe Movie"), filtered.map { it.title })

        val unfiltered = com.anonrode.downloader.providers.TrendingFeed.mergeRoundRobin(siteCards, filterExplicit = false)
        assertEquals(listOf("Safe Movie", "Hot XXX Adult Movie"), unfiltered.map { it.title })
    }

    @Test
    fun categoryFeed_mixCards_respectsExplicitFilterFlag() {
        val pairs = listOf(
            "naijavault" to listOf(
                ShowCard(title = "Action Hero (2026)", url = "http://1", site = "naijavault"),
                ShowCard(title = "Sex Tape Leak 18+", url = "http://2", site = "naijavault")
            )
        )

        val filtered = com.anonrode.downloader.providers.CategoryFeed.mixCards(pairs, filterExplicit = true)
        assertEquals(listOf("Action Hero (2026)"), filtered.map { it.title })

        val unfiltered = com.anonrode.downloader.providers.CategoryFeed.mixCards(pairs, filterExplicit = false)
        assertEquals(listOf("Action Hero (2026)", "Sex Tape Leak 18+"), unfiltered.map { it.title })
    }
}
