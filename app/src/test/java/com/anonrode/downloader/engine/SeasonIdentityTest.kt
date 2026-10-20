package com.anonrode.downloader.engine

import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.EpisodeItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Season identity migration off the legacy 100-base key (2026-10-20).
 * The authoritative codec is util.SeasonEpisode: 1000-base new data,
 * site-scoped legacy decode (9jaRocks flat), explicit fields first.
 */
class SeasonIdentityTest {

    @Test
    fun `1000-base values decode on every site`() {
        assertEquals(2 to 1, normalizeSeasonEncodedEpisodeNumber("9jarocks", 2001))
        assertEquals(1 to 101, normalizeSeasonEncodedEpisodeNumber("9jarocks", 1101))
        assertEquals(2 to 1, normalizeSeasonEncodedEpisodeNumber("dramakey", 2001))
    }

    @Test
    fun `legacy packed sites decode 100-base but flat 9jarocks does not`() {
        assertEquals(1 to 13, normalizeSeasonEncodedEpisodeNumber("dramakey", 113))
        assertNull("9jaRocks 113 is S01E113, not S01E13", normalizeSeasonEncodedEpisodeNumber("9jarocks", 113))
        assertNull(normalizeSeasonEncodedEpisodeNumber("9jarocks", 99))
    }

    @Test
    fun `explicit task fields win over the encoding`() {
        val task = DownloadTask(
            id = "t", showTitle = "Show", episodeNum = 2001, episodeTitle = "S02E01",
            directUrl = "", season = 2, episode = 1
        )
        val pair = com.anonrode.downloader.util.SeasonEpisode.of(task)
        assertEquals(2, pair.first)
        assertEquals(1, pair.second)
    }

    @Test
    fun `episode item explicit fields survive a 9jarocks long season`() {
        val ep = EpisodeItem(
            title = "S01E180", url = "https://x/180", episodeNum = 1180,
            site = "9jarocks", season = 1, episode = 180
        )
        val pair = com.anonrode.downloader.util.SeasonEpisode.of(ep)
        assertEquals(1, pair.first)
        assertEquals(180, pair.second)
    }

    @Test
    fun `s2e1 and s1e1 have distinct encoded keys`() {
        val s1 = com.anonrode.downloader.util.SeasonEpisode.encode(1, 1)
        val s2 = com.anonrode.downloader.util.SeasonEpisode.encode(2, 1)
        assertEquals(1001, s1)
        assertEquals(2001, s2)
        assertEquals(1101, com.anonrode.downloader.util.SeasonEpisode.encode(1, 101))
    }
}
