package com.anonrode.downloader.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Subtitle surfaces (investigation 03 §4).
 *
 * nsrplay's resolve JSON types `data.subtitles` (null for some titles, a
 * list of {url, language, label} for others); vidsrc-family play payloads
 * carry `subtitles`/`tracks`. Pages may also expose `<track>` elements or
 * direct .srt/.vtt/.ass links. All of those are recorded per episode URL for
 * the engine's sidecar step.
 */
class SubtitleSurfacesTest {

    @Before
    fun clear() {
        SubtitleSurfaces.clearForTest()
    }

    @Test
    fun nsrplayResolveJsonListsEveryTrackWithLanguage() {
        val json = """
            {"status":true,"data":{
              "directUrl":"https://p4.vimeos.zip/x/master.m3u8",
              "subtitles":[
                {"url":"https://cdn.example.com/ep1.en.vtt","language":"en","label":"English"},
                {"url":"https://cdn.example.com/ep1.es.vtt","language":"es"}
              ]}}
        """.trimIndent()
        val tracks = SubtitleSurfaces.parseResolveJson(json)
        assertEquals(2, tracks.size)
        assertEquals("en", tracks[0].language)
        assertEquals("vtt", tracks[0].format)
        assertEquals("es", tracks[1].language)
    }

    @Test
    fun nullOrAbsentSubtitlesAreAnEmptyListNotAnError() {
        assertEquals(0, SubtitleSurfaces.parseResolveJson("""{"data":{"subtitles":null}}""").size)
        assertEquals(0, SubtitleSurfaces.parseResolveJson("""{"data":{"directUrl":"x"}}""").size)
        assertEquals(0, SubtitleSurfaces.parseResolveJson("not json").size)
    }

    @Test
    fun vidsrcTopLevelTracksAreParsed() {
        val json = """
            {"stream":"https://cdn/x.m3u8","subtitles":[
              {"file":"https://cdn/x.en.srt","lang":"en"},
              {"src":"https://cdn/x.pt.srt","srclang":"pt","name":"Portugues"}
            ]}
        """.trimIndent()
        val tracks = SubtitleSurfaces.parseResolveJson(json)
        assertEquals(listOf("en", "pt"), tracks.map { it.language })
        assertEquals(listOf("srt", "srt"), tracks.map { it.format })
    }

    @Test
    fun pageTracksAndSubtitleAnchorsAreCollected() {
        val html = """
            <html><body>
            <video><track src="https://cdn.example.com/a.en.vtt" srclang="en" label="English"></video>
            <a href="/subs/show.s02e01.eng.srt">Download Subtitle</a>
            <a href="https://cdn.example.com/b.ass">Indonesian subtitle</a>
            <a href="/download-episode-1-of-show/">Episode 1</a>
            </body></html>
        """.trimIndent()
        val doc = org.jsoup.Jsoup.parse(html, "https://site.example/show/")
        val tracks = SubtitleSurfaces.parsePageTracks(doc, "https://site.example/show/")
        assertEquals(3, tracks.size)
        assertTrue(tracks.any { it.url.endsWith("a.en.vtt") && it.language == "en" })
        assertTrue(tracks.any { it.url.endsWith("show.s02e01.eng.srt") && it.language == "eng" })
        assertTrue(tracks.any { it.url.endsWith("b.ass") && it.format == "ass" })
        assertFalse(tracks.any { it.url.contains("download-episode") })
    }

    @Test
    fun recordedTracksAreFoundByEpisodeUrl() {
        val tracks = listOf(SiteSubtitleTrack("https://cdn/x.en.vtt", "en", "English", "vtt"))
        SubtitleSurfaces.record("https://nepu.gd/watch/tv/1/1/1", tracks)
        assertEquals(tracks, SubtitleSurfaces.tracksFor("https://nepu.gd/watch/tv/1/1/1/"))
        assertTrue(SubtitleSurfaces.tracksFor("https://nepu.gd/watch/tv/1/1/2").isEmpty())
    }

    @Test
    fun resolveJsonConvenienceRecordsForTheEpisodeWithoutTouchingTheNetwork() {
        val json = """{"data":{"subtitles":[{"url":"https://cdn/x.en.srt","language":"en"}]}}"""
        val tracks = SubtitleSurfaces.recordFromResolveJson("https://site/ep/1", json)
        assertEquals(1, tracks.size)
        assertEquals(1, SubtitleSurfaces.tracksFor("https://site/ep/1").size)
    }

    @Test
    fun openSubtitlesHashIsSizePlusLittleEndianWords() {
        val dir = java.nio.file.Files.createTempDirectory("subhash").toFile()
        try {
            val small = java.io.File(dir, "small.mp4").apply { writeBytes(ByteArray(1000)) }
            assertEquals(null, SubtitleSurfaces.opensubtitlesHash(small))

            // All-zero file: hash == file size (0x20000).
            val zeros = java.io.File(dir, "zeros.mp4").apply { writeBytes(ByteArray(131_072)) }
            assertEquals("0000000000020000", SubtitleSurfaces.opensubtitlesHash(zeros))

            // One 0x01 byte at both chunk starts contributes 1 + 1.
            val ones = java.io.File(dir, "ones.mp4").apply {
                val bytes = ByteArray(131_072)
                bytes[0] = 1
                bytes[65_536] = 1
                writeBytes(bytes)
            }
            assertEquals("0000000000020002", SubtitleSurfaces.opensubtitlesHash(ones))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun webVttConvertsToSrtCues() {
        val vtt = """
            WEBVTT
            Kind: captions
            Language: en

            NOTE this is a comment

            cue-1
            00:00:01.000 --> 00:00:03.500 align:start position:10%
            <c.yellow>Hello</c> <v Speaker>world</v>

            00:00:04.000 --> 00:00:05.000
            Second line
            keeps two rows
        """.trimIndent()
        val srt = SubtitleSurfaces.vttToSrt(vtt)
        assertTrue(srt.startsWith("1\n00:00:01,000 --> 00:00:03,500\n"))
        assertTrue(srt.contains("Hello world"))
        assertTrue(!srt.contains("<c.") && !srt.contains("<v "))
        assertTrue(!srt.contains("WEBVTT") && !srt.contains("NOTE"))
        assertTrue(srt.contains("2\n00:00:04,000 --> 00:00:05,000\nSecond line\nkeeps two rows"))
    }

    @Test
    fun vttDetectionToleratesBomAndLeadingWhitespace() {
        assertTrue(SubtitleSurfaces.looksLikeVtt("\uFEFF  WEBVTT\n\n"))
        assertFalse(SubtitleSurfaces.looksLikeVtt("1\n00:00:01,000 --> 00:00:02,000\nHi"))
    }
}
