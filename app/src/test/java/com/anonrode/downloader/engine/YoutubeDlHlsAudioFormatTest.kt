package com.anonrode.downloader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * DASH-derived HLS audio rendition (vidbolt bridge, live 2026-10-22):
 * the master declares `EXT-X-MEDIA:TYPE=AUDIO` and the video variants carry
 * unknown codecs. yt-dlp's stock `bestvideo+bestaudio` then keeps only the
 * video variant (acodec unknown is treated as audio-bearing), silently
 * dropping the audio rendition. The master-aware selector below must pick
 * `best+bestaudio` with `--audio-multistreams` so ffmpeg muxes both.
 */
class YoutubeDlHlsAudioFormatTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun master(body: String): String =
        File(tmp.newFolder(), "master.m3u8").apply { writeText(body) }.absolutePath

    @Test
    fun separateAudioRenditionIsDetected() {
        val path = master(
            """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Audio",DEFAULT=YES,URI="a.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=740984,RESOLUTION=1920x1080,AUDIO="audio"
            v0.m3u8
            """.trimIndent()
        )
        assertTrue(YoutubeDlDownloader.masterDeclaresSeparateAudio(path))
    }

    @Test
    fun subtitleMediaAndPlainMastersKeepTheNormalSelector() {
        val subtitleOnly = master(
            """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",URI="s.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=1920x1080,SUBTITLES="subs"
            v0.m3u8
            """.trimIndent()
        )
        assertFalse(YoutubeDlDownloader.masterDeclaresSeparateAudio(subtitleOnly))
        val plain = master("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=1280x720\nv0.m3u8\n")
        assertFalse(YoutubeDlDownloader.masterDeclaresSeparateAudio(plain))
        assertFalse(YoutubeDlDownloader.masterDeclaresSeparateAudio(null))
    }

    @Test
    fun audioRenditionUsesTheMergeSelector() {
        val flags = YoutubeDlDownloader.hlsFormatOptions(720, separateAudio = true)
        assertTrue(flags.contains("--audio-multistreams"))
        assertEquals("best+bestaudio/best", flags[flags.indexOf("-f") + 1])
    }

    @Test
    fun plainHlsKeepsTheHeightCappedVideoSelector() {
        val flags = YoutubeDlDownloader.hlsFormatOptions(480, separateAudio = false)
        assertFalse(flags.contains("--audio-multistreams"))
        assertEquals(
            "bestvideo[height<=480]+bestaudio/best[height<=480]/best",
            flags[flags.indexOf("-f") + 1]
        )
    }
}
