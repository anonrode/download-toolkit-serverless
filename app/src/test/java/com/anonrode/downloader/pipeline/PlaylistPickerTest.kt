package com.anonrode.downloader.pipeline

import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure half of YouTube playlist support (2026-09-15).
 * PlaylistPicker has zero Android imports and zero Regex, so the whole
 * surface — URL detection, the flat-playlist JSON contract, range specs,
 * dedupe and the size estimate — is exercised here, on the real JVM org.json.
 */
class PlaylistPickerTest {

    // ---- detect --------------------------------------------------------------

    @Test
    fun plainPlaylistUrl() {
        val p = PlaylistPicker.detect("https://www.youtube.com/playlist?list=PLabc123")!!
        assertEquals(PlaylistPicker.ListKind.PLAYLIST, p.kind)
        assertEquals("PLabc123", p.listId)
        assertEquals("", p.videoId)
        assertEquals("https://www.youtube.com/playlist?list=PLabc123", p.playlistUrl)
    }

    @Test
    fun watchUrlInsidePlaylistKeepsVideoId() {
        val p = PlaylistPicker.detect(
            "https://www.youtube.com/watch?v=VID1&list=PLxyz&index=4"
        )!!
        assertEquals("PLxyz", p.listId)
        assertEquals("VID1", p.videoId)
        // The picker is fed the CLEAN playlist URL, never the watch link.
        assertEquals("https://www.youtube.com/playlist?list=PLxyz", p.playlistUrl)
    }

    @Test
    fun youtuBeWithList() {
        val p = PlaylistPicker.detect("https://youtu.be/AbCdEf12345?list=RDqwer")!!
        assertEquals("AbCdEf12345", p.videoId)
        assertEquals(PlaylistPicker.ListKind.MIX, p.kind)
    }

    @Test
    fun mixPrefixesAreMix() {
        for (id in listOf("RDkMmYki7m2m4", "RDEXO1a2b", "OMy8uT", "PMzz")) {
            val p = PlaylistPicker.detect("https://www.youtube.com/watch?v=V&list=$id")!!
            assertEquals("list $id", PlaylistPicker.ListKind.MIX, p.kind)
        }
        for (id in listOf("PLzz", "UUzz", "OUzz", "LCzz", "UCLzz")) {
            val p = PlaylistPicker.detect("https://www.youtube.com/playlist?list=$id")!!
            assertEquals("list $id", PlaylistPicker.ListKind.PLAYLIST, p.kind)
        }
    }

    @Test
    fun fragmentAfterListIdIsCut() {
        val p = PlaylistPicker.detect("https://www.youtube.com/watch?v=V&list=PLabc#t=30")!!
        assertEquals("PLabc", p.listId)
    }

    @Test
    fun nonYoutubeAndNoListAreRejected() {
        assertNull(PlaylistPicker.detect("https://vimeo.com/playlist?list=PLabc"))
        assertNull(PlaylistPicker.detect("https://www.youtube.com/watch?v=ABC123"))
        assertNull(PlaylistPicker.detect("https://www.youtube.com/playlist?list="))
        assertNull(PlaylistPicker.detect("https://example.com/?list=PLabc"))
    }

    // ---- parseFlatJson ---------------------------------------------------------

    private val flatFixture = """
    {
      "id": "PLabc", "title": "My Playlist", "channel": "Some Channel",
      "entries": [
        {
          "id": "v1", "title": "First Video", "duration": 61.5,
          "url": "/watch?v=v1",
          "thumbnails": [ {"url": "https://i.ytimg.com/a1"}, {"url": "https://i.ytimg.com/maxres1"} ]
        },
        {
          "id": "v2", "title": "Second Video", "duration": 7264,
          "url": "//www.youtube.com/watch?v=v2", "channel": "Other Channel"
        },
        { "id": "v3", "webpage_url": "https://www.youtube.com/watch?v=v3" },
        { "id": "", "title": "skipped, no id" },
        "not-an-object"
      ]
    }
    """.trimIndent()

    @Test
    fun flatJsonContract() {
        val meta = PlaylistPicker.parseFlatJson(flatFixture)!!
        assertEquals("My Playlist", meta.title)
        assertEquals("Some Channel", meta.uploader)
        assertEquals(3, meta.entries.size) // empty-id and non-object dropped

        val first = meta.entries[0]
        assertEquals("v1", first.videoId)
        assertEquals("https://www.youtube.com/watch?v=v1", first.watchUrl) // relative normalised
        assertEquals(61, first.durationSec) // double truncated, never rounded up
        assertEquals("https://i.ytimg.com/maxres1", first.thumbnailUrl) // last = highest res
        assertEquals("Some Channel", first.uploader) // falls back to playlist channel

        assertEquals("https://www.youtube.com/watch?v=v2", meta.entries[1].watchUrl) // protocol-relative
        assertEquals("Other Channel", meta.entries[1].uploader)

        val third = meta.entries[2]
        assertEquals("https://www.youtube.com/watch?v=v3", third.watchUrl) // webpage_url accepted
        assertEquals("Video 3", third.title) // absent title -> positional placeholder
        assertEquals(0, third.durationSec)
        assertEquals("", third.thumbnailUrl)
    }

    @Test
    fun singleVideoJsonHasNoEntries() {
        // yt-dlp -J on a bare video returns an object with no entries array:
        // parseable, but zero entries — the caller shows the friendly error.
        val meta = PlaylistPicker.parseFlatJson("""{"id":"v1","title":"One Video"}""")!!
        assertTrue(meta.entries.isEmpty())
    }

    @Test
    fun garbageIsUnparseable() {
        assertNull(PlaylistPicker.parseFlatJson(""))
        assertNull(PlaylistPicker.parseFlatJson("not json at all"))
        assertNull(PlaylistPicker.parseFlatJson("""[1,2,3]""")) // array root is not a playlist dump
    }

    // ---- parseRanges -----------------------------------------------------------

    @Test
    fun rangesExpandAndDedupe() {
        assertEquals(
            listOf(1, 2, 3, 4, 5, 8, 10, 11, 12),
            PlaylistPicker.parseRanges("1-5,8,10-12", 12)
        )
        assertEquals(listOf(2, 3, 4), PlaylistPicker.parseRanges("2-4,3", 10))
        assertEquals(listOf(1, 2, 3), PlaylistPicker.parseRanges(" 1 - 3 ", 10))
    }

    @Test
    fun rangeOrderIsSpecOrder() {
        // "8,1-3" must select 8 FIRST — the user's stated order is honored.
        assertEquals(listOf(8, 1, 2, 3), PlaylistPicker.parseRanges("8,1-3", 12))
    }

    @Test
    fun malformedRangeRejectsEverything() {
        // A silently-partial range would queue the wrong videos: any bad
        // token rejects the WHOLE spec.
        assertNull(PlaylistPicker.parseRanges("1-5,abc", 12))
        assertNull(PlaylistPicker.parseRanges("0-3", 12)) // 0 is not 1-based
        assertNull(PlaylistPicker.parseRanges("10-13", 12)) // past the end
        assertNull(PlaylistPicker.parseRanges("5-2", 12)) // inverted
        assertNull(PlaylistPicker.parseRanges("", 12))
        assertNull(PlaylistPicker.parseRanges("13", 12))
    }

    // ---- filterIndices -----------------------------------------------------------

    private fun entries3() = listOf(
        PlaylistPicker.Entry("a", "https://youtu.be/a", "Pilot Episode", 600, "", "Show Channel"),
        PlaylistPicker.Entry("b", "https://youtu.be/b", "The Chase", 500, "", "Other Channel"),
        PlaylistPicker.Entry("c", "https://youtu.be/c", "Finale", 400, "", "Show Channel")
    )

    @Test
    fun filterMatchesTitleAndUploaderCaseInsensitively() {
        assertEquals(listOf(1, 2, 3), PlaylistPicker.filterIndices(entries3(), ""))
        assertEquals(listOf(1), PlaylistPicker.filterIndices(entries3(), "pilot"))
        assertEquals(listOf(1, 3), PlaylistPicker.filterIndices(entries3(), "show channel"))
        assertEquals(emptyList<Int>(), PlaylistPicker.filterIndices(entries3(), "zzz"))
    }

    // ---- videoIdOf ---------------------------------------------------------------

    @Test
    fun videoIdShapes() {
        assertEquals("ABC", PlaylistPicker.videoIdOf("https://www.youtube.com/watch?v=ABC&list=PLx"))
        assertEquals("ABC", PlaylistPicker.videoIdOf("https://youtu.be/ABC?t=10"))
        assertEquals("ABC", PlaylistPicker.videoIdOf("https://www.youtube.com/embed/ABC"))
        assertEquals("", PlaylistPicker.videoIdOf("https://example.com/watch?v=ABC"))
        assertEquals("", PlaylistPicker.videoIdOf("magnet:?xt=urn:btih:xyz"))
    }

    // ---- downloadedIds -------------------------------------------------------------

    private fun ytTask(id: String, url: String, status: TaskStatus) = DownloadTask(
        id = id, showTitle = "S", episodeNum = 1, episodeTitle = "E",
        directUrl = url, sourceUrl = url, status = status
    )

    @Test
    fun onlyCompletedYoutubeTasksCountAsOnDevice() {
        val tasks = listOf(
            ytTask("1", "https://www.youtube.com/watch?v=v1", TaskStatus.COMPLETED),
            ytTask("2", "https://www.youtube.com/watch?v=v2", TaskStatus.DOWNLOADING),
            ytTask("3", "https://www.youtube.com/watch?v=v3", TaskStatus.FAILED),
            ytTask("4", "https://example.com/x.mp4", TaskStatus.COMPLETED)
        )
        assertEquals(setOf("v1"), PlaylistPicker.downloadedIds(tasks))
    }

    // ---- estimateBytes --------------------------------------------------------------

    @Test
    fun estimateUsesPerQualityBitrate() {
        val e = listOf(PlaylistPicker.Entry("a", "u", "t", 3600, "", ""))
        // 1 h at 128 kbps MP3 = 128_000/8 * 3600 = 57.6 MB
        assertEquals(57_600_000L, PlaylistPicker.estimateBytes(e, listOf(1), "Best", true))
        // 1 h at 4500 kbps 1080p = 4_500_000/8 * 3600 = 2_025_000_000
        assertEquals(2_025_000_000L, PlaylistPicker.estimateBytes(e, listOf(1), "1080p", false))
        // Unknown/"Best" video falls to the 720p assumption (2500 kbps).
        assertEquals(1_125_000_000L, PlaylistPicker.estimateBytes(e, listOf(1), "Best", false))
        assertEquals(7_650_000_000L, PlaylistPicker.estimateBytes(e, listOf(1), "2160p", false))
        // Out-of-range indices are skipped, not fatal.
        assertEquals(0L, PlaylistPicker.estimateBytes(e, listOf(99), "480p", false))
    }

    // ---- formatters ------------------------------------------------------------------

    @Test
    fun durationFormatting() {
        assertEquals("", PlaylistPicker.formatDuration(0))
        assertEquals("1:01", PlaylistPicker.formatDuration(61))
        assertEquals("2:04:24", PlaylistPicker.formatDuration(7464))
    }

    @Test
    fun bytesFormatting() {
        assertEquals("58 MB", PlaylistPicker.formatBytes(57_600_000L))
        assertEquals("2.0 GB", PlaylistPicker.formatBytes(2_025_000_000L))
        assertEquals("12 KB", PlaylistPicker.formatBytes(12_345L))
    }
}
