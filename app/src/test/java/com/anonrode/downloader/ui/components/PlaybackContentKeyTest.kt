package com.anonrode.downloader.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Cross-variant position migration identity (2026-10-21 trace C6). A quality
 * switch re-points the player at a different FILE whose stem differs only by
 * the quality/codec tag — the per-path saved position then misses and
 * playback restarts at 0:00. [playbackContentKey] must collapse quality/
 * codec variants of one episode to the same key while keeping different
 * episodes/shows apart (season+episode markers are KEPT, unlike
 * DownloadsSorter.normalizeShowKey which erases them for show grouping).
 */
class PlaybackContentKeyTest {

    @Test
    fun qualityVariantsCollapseToTheSameKey() {
        assertEquals(
            playbackContentKey("/Downloads/Show S01E05.mp4"),
            playbackContentKey("/Downloads/Show S01E05 1080p.mp4")
        )
        assertEquals(
            playbackContentKey("/Downloads/Show.S01E05.720p.x265.mkv"),
            playbackContentKey("/Downloads/Show.S01E05.1080p.mkv")
        )
        assertEquals(
            playbackContentKey("/Downloads/Movie (2023).mp4"),
            playbackContentKey("/Downloads/Movie (2023) [2160p HDR].mp4")
        )
        assertEquals(
            playbackContentKey("/Downloads/Ep1.mp4"),
            playbackContentKey("/Downloads/Ep1 - WEBRip AAC.mkv")
        )
    }

    @Test
    fun punctuationAndCaseDoNotChangeIdentity() {
        assertEquals(
            playbackContentKey("/Downloads/Mr.Robot.S01E05.mkv"),
            playbackContentKey("/downloads/mr robot s01e05.mp4")
        )
        assertEquals(
            playbackContentKey("/a/Show_S01E05_WEBRip.mp4"),
            playbackContentKey("/b/Show S01E05.mp4")
        )
    }

    @Test
    fun differentEpisodesNeverShareAKey() {
        assertNotEquals(
            playbackContentKey("/Downloads/Show S01E05.mp4"),
            playbackContentKey("/Downloads/Show S01E06.mp4")
        )
        assertNotEquals(
            playbackContentKey("/Downloads/Show S01E05.mp4"),
            playbackContentKey("/Downloads/Show S02E05.mp4")
        )
        assertNotEquals(
            playbackContentKey("/Downloads/Show S01E05.mp4"),
            playbackContentKey("/Downloads/Show S01E05 1080p S01E06.mp4")
        )
    }

    @Test
    fun titleWordsThatLookLikeReleaseJargonWordsAreKept() {
        // Only the exact unambiguous vocabulary is stripped; tokens that can
        // be title words never merge different shows.
        assertNotEquals(
            playbackContentKey("/Downloads/Web Therapy S01E05.mp4"),
            playbackContentKey("/Downloads/Therapy S01E05.mp4")
        )
        assertNotEquals(
            playbackContentKey("/Downloads/HD Adventures S01E01.mp4"),
            playbackContentKey("/Downloads/Adventures S01E01.mp4")
        )
    }

    @Test
    fun keysAreLowercaseAndWhitespaceNormalized() {
        assertEquals("show s01e05", playbackContentKey("/X/SHOW.S01E05.720p.mp4"))
        assertEquals("show 5", playbackContentKey("Show---5.mkv"))
    }
}
