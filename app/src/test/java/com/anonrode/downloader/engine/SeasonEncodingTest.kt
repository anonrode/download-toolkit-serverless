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
        assertEquals(3 to 24, normalizeSeasonEncodedEpisodeNumber("9jarocks", 324))
    }

    @Test
    fun ordinaryEpisodeNumbersStayUntouched() {
        assertNull(normalizeSeasonEncodedEpisodeNumber("nepu", 13))
        assertNull(normalizeSeasonEncodedEpisodeNumber("nepu", 99))
        // Episode 00 is not a valid packed value.
        assertNull(normalizeSeasonEncodedEpisodeNumber("nepu", 100))
        assertNull(normalizeSeasonEncodedEpisodeNumber("nepu", 3101))
        assertNull(normalizeSeasonEncodedEpisodeNumber("nepu", 3999))
    }
}
