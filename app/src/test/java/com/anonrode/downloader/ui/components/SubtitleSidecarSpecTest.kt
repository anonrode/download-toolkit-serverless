package com.anonrode.downloader.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Player sidecar handling for `<stem>.<lang>.srt/.vtt/.ass` (investigation
 * 03 §4). Mirrors occasionally ship the wrong container: a `.vtt` that is
 * really SRT and an `.srt` that is really WebVTT. ExoPlayer's SubRip parser
 * rejects a WEBVTT header, so the attachment decision and the VTT→SRT
 * conversion are pinned here.
 */
class SubtitleSidecarSpecTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun plainSrtAttachesAsSubRip() {
        assertEquals(SidecarSpec("application/x-subrip", false), sidecarSpec("srt", "1\n00:00:01,000 --> 00:00:02,000\nHi"))
    }

    @Test
    fun realVttAttachesAsWebVtt() {
        assertEquals(SidecarSpec("text/vtt", false), sidecarSpec("vtt", "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi"))
    }

    @Test
    fun vttExtensionWithSrtBodyIsAttachedAsSubRip() {
        assertEquals(
            SidecarSpec("application/x-subrip", false),
            sidecarSpec("vtt", "1\n00:00:01,000 --> 00:00:02,000\nHi")
        )
    }

    @Test
    fun srtExtensionWithWebVttBodyIsConverted() {
        assertEquals(
            SidecarSpec("application/x-subrip", true),
            sidecarSpec("srt", "\uFEFFWEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi")
        )
        assertTrue(sidecarSpec("srt", "WEBVTT").convertVttToSrt)
    }

    @Test
    fun assAndSsaKeepTheirMimeType() {
        assertEquals(SidecarSpec("application/x-ssa", false), sidecarSpec("ass", "[Script Info]"))
        assertEquals(SidecarSpec("application/x-ssa", false), sidecarSpec("ssa", "[Script Info]"))
    }

    @Test
    fun languageNamesMapToIsoCodes() {
        assertEquals("en", sidecarLanguageHint("movie.english.srt", "movie"))
        assertEquals("en", sidecarLanguageHint("movie.EN.srt", "movie"))
        assertEquals("pt", sidecarLanguageHint("movie.pt-BR.srt", "movie"))
        assertEquals("und", sidecarLanguageHint("movie.final.srt", "movie"))
        assertEquals("und", sidecarLanguageHint("other.en.srt", "movie"))
    }

    @Test
    fun subtitleHeadReaderToleratesMissingFile() {
        assertEquals("", readSubtitleHead(File(temp.root, "does-not-exist.srt")))
        val file = temp.newFile("movie.en.srt").apply { writeText("1\n00:00:01,000 --> 00:00:02,000\nHi") }
        assertTrue(readSubtitleHead(file).startsWith("1"))
        assertFalse(readSubtitleHead(file).startsWith("WEBVTT"))
    }
}
