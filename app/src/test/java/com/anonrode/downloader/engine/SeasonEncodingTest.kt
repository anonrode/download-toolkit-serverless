package com.anonrode.downloader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Season-encoded episode numbers (2026-10-19): nepu's Avatar-style catalog
 * numbers S01E13 as 113. Failover used to compare a 6-item season list
 * against 113 and reject the only correct provider.
 */
class SeasonEncodingTest {

    @Test
    fun decodesSxxEyyPackedNumbers() {
        assertEquals(1 to 13, normalizeSeasonEncodedEpisodeNumber("nepu", 113))
        assertEquals(2 to 1, normalizeSeasonEncodedEpisodeNumber("nepu", 201))
        // Legacy packed providers keep the historical 100-base decode.
        assertEquals(3 to 24, normalizeSeasonEncodedEpisodeNumber("dramakey", 324))
        // New parses use the collision-free 1000-base codec on every site.
        assertEquals(2 to 1, normalizeSeasonEncodedEpisodeNumber("9jarocks", 2001))
        assertEquals(3 to 101, normalizeSeasonEncodedEpisodeNumber("9jarocks", 3101))
    }

    @Test
    fun ordinaryEpisodeNumbersStayUntouched() {
        assertNull(normalizeSeasonEncodedEpisodeNumber("nepu", 13))
        assertNull(normalizeSeasonEncodedEpisodeNumber("nepu", 99))
        // Episode 00 is not a valid packed value.
        assertNull(normalizeSeasonEncodedEpisodeNumber("nepu", 100))
        // 9jaRocks numbers flat: 113 is S01E113, never S01E13.
        assertNull(normalizeSeasonEncodedEpisodeNumber("9jarocks", 113))
        assertEquals(3 to 999, normalizeSeasonEncodedEpisodeNumber("nepu", 3999))
    }
}
