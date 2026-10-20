package com.anonrode.downloader.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the 9jaRocks season collapse (2026-10-20).
 *
 * 9jaRocks numbers episodes flat inside a season (1..N) and puts the season in
 * the post title/slug. The old `season * 100 + episode` convention turned
 * S01E101 into 201 (decoded as S02E01) and merged flat 1..10 Season 2 pages
 * into Season 1. Explicit season/episode fields are now authoritative; flat
 * numbering must never be decoded as a packed season code.
 */
class SeasonEpisodeTest {

    @Test
    fun `flat 9jaRocks numbering above 99 stays in season one`() {
        assertEquals(1 to 113, SeasonEpisode.of("9jarocks", 0, 0, 113))
        assertEquals(1 to 150, SeasonEpisode.of("9jarocks", 0, 0, 150))
    }

    @Test
    fun `explicit season wins over legacy numbering`() {
        assertEquals(2 to 10, SeasonEpisode.of("9jarocks", 2, 10, 10))
        assertEquals(2 to 7, SeasonEpisode.of("9jarocks", 2, 7, 2007))
    }

    @Test
    fun `legacy packed sites still decode`() {
        assertEquals(1 to 13, SeasonEpisode.of("dramakey", 0, 0, 113))
        assertEquals(2 to 5, SeasonEpisode.of("nepu", 0, 0, 205))
    }

    @Test
    fun `new thousand base keys decode without ambiguity`() {
        assertEquals(1 to 13, SeasonEpisode.of("9jarocks", 0, 0, 1013))
        assertEquals(1 to 150, SeasonEpisode.of("9jarocks", 0, 0, 1150))
        assertEquals(2 to 1, SeasonEpisode.of("9jarocks", 0, 0, 2001))
    }

    @Test
    fun `season one episode 101 cannot collide with season two episode one`() {
        assertNotEquals(SeasonEpisode.encode(2, 1), SeasonEpisode.encode(1, 101))
        assertEquals(1101, SeasonEpisode.encode(1, 101))
        assertEquals(2001, SeasonEpisode.encode(2, 1))
    }

    @Test
    fun `filename for long flat season keeps the real season and episode`() {
        val longSeason = NameSanitizer.formatEpisodeTitle(
            showTitle = "The General's Daughter",
            episodeNum = 113,
            rawEpisodeLabel = "Episode 113",
            site = "9jarocks"
        )
        assertTrue(longSeason, longSeason.contains("S01E113"))
        assertFalse(longSeason, longSeason.contains("S02"))
    }

    @Test
    fun `filename for legacy packed provider still decodes the token`() {
        val packed = NameSanitizer.formatEpisodeTitle(
            showTitle = "Some Drama",
            episodeNum = 113,
            rawEpisodeLabel = "Episode 13",
            site = "dramakey"
        )
        assertTrue(packed, packed.contains("S01E13"))
    }

    @Test
    fun `filename uses explicit season fields when present`() {
        val explicit = NameSanitizer.formatEpisodeTitle(
            showTitle = "Some Show",
            episodeNum = 2010,
            rawEpisodeLabel = "",
            season = 2,
            episode = 10,
            site = "9jarocks"
        )
        assertTrue(explicit, explicit.contains("S02E10"))
    }
}
