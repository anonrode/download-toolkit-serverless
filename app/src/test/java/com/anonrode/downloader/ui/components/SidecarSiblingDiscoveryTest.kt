package com.anonrode.downloader.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Player sidecar DISCOVERY (2026-10-21 trace d1/d2). The old filter —
 * `startsWith("stem.")` + a case-SENSITIVE `endsWith(".ext")` — hid two real
 * shapes: exact-name siblings ("Show.srt", no `.<lang>.` segment, e.g.
 * manual VLC-style copies) and any uppercase spelling from case-preserving
 * FAT/FUSE volumes ("Show.EN.SRT"). It also false-positived on prefix
 * collisions ("Showtime.en.srt" as a sidecar of "Show.mkv"). Each rule is
 * pinned here.
 */
class SidecarSiblingDiscoveryTest {

    @Test
    fun languageTaggedSidecarsStillDiscover() {
        assertTrue(isSidecarSubtitleSibling("Show.en.srt", "Show"))
        assertTrue(isSidecarSubtitleSibling("Show.pt-BR.srt", "Show"))
        assertTrue(isSidecarSubtitleSibling("Show.en-2.vtt", "Show"))
        assertTrue(isSidecarSubtitleSibling("Show.english.ass", "Show"))
        assertTrue(isSidecarSubtitleSibling("Mr.Robot.S01E05.srt", "Mr.Robot.S01E05"))
    }

    @Test
    fun exactNameSiblingWithoutLanguageSegmentDiscovers() {
        assertTrue(isSidecarSubtitleSibling("Show.srt", "Show"))
        assertTrue(isSidecarSubtitleSibling("Show.vtt", "Show"))
        assertTrue(isSidecarSubtitleSibling("Movie.ass", "Movie"))
        // The video file itself and other media are never sidecars.
        assertFalse(isSidecarSubtitleSibling("Show.mkv", "Show"))
        assertFalse(isSidecarSubtitleSibling("Show.mp4", "Show"))
        assertFalse(isSidecarSubtitleSibling("Show.mp3", "Show"))
    }

    @Test
    fun letterCaseAnywhereStillDiscovers() {
        assertTrue(isSidecarSubtitleSibling("show.EN.SRT", "Show"))
        assertTrue(isSidecarSubtitleSibling("SHOW.en.srt", "Show"))
        assertTrue(isSidecarSubtitleSibling("Show.VTT", "Show"))
        assertTrue(isSidecarSubtitleSibling("Show.PT-br.SSA", "Show"))
    }

    @Test
    fun prefixCollisionIsNotASidecar() {
        // Starts with the stem but the tail is a real word, not tag+ext.
        assertFalse(isSidecarSubtitleSibling("Showtime.en.srt", "Show"))
        assertFalse(isSidecarSubtitleSibling("Showcase.srt", "Show"))
        // No dot separator straight after the stem.
        assertFalse(isSidecarSubtitleSibling("Showtime.srt", "Show"))
    }

    @Test
    fun unanchoredAndDegenerateNamesRejected() {
        assertFalse(isSidecarSubtitleSibling("Show.srt.bak", "Show"))
        assertFalse(isSidecarSubtitleSibling("Show..", "Show"))
        assertFalse(isSidecarSubtitleSibling("Show", "Show"))
        assertFalse(isSidecarSubtitleSibling("Show.en.srt.vtt", "Show"))
        assertFalse(isSidecarSubtitleSibling("other.en.srt", "Show"))
        assertFalse(isSidecarSubtitleSibling("", "Show"))
        // Empty stem never matches anything (avoids a bare "x.srt" wildcard).
        assertFalse(isSidecarSubtitleSibling("x.srt", ""))
    }

    @Test
    fun unknownNonSubtitleExtensionRejected() {
        assertFalse(isSidecarSubtitleSibling("Show.en.nfo", "Show"))
        assertFalse(isSidecarSubtitleSibling("Show.annotations", "Show"))
    }
}
