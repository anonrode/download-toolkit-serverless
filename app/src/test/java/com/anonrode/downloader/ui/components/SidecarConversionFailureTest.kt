package com.anonrode.downloader.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Conversion-failure safety (2026-10-21 trace d3 / matrix row 5): when an
 * `.srt`-named WebVTT sidecar fails its vtt→srt conversion, attaching the
 * ORIGINAL under the guessed SubRip mime produces a track the SubRip parser
 * rejects on every read — listed, selectable, zero cues (a silent dead
 * track). The rescue policy must re-sniff the head (the truth-resolver) and
 * either attach as real WebVTT or drop the track; NEVER x-subrip.
 */
class SidecarConversionFailureTest {

    @Test
    fun failedConversionWithWebVttHeadAttachesAsRealVtt() {
        assertEquals(
            "text/vtt",
            failedConversionMime("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi")
        )
    }

    @Test
    fun failedConversionNeverRescuesAsSubRip() {
        // A WEBVTT head is exactly what the SubRip parser rejects — the
        // rescue mime must never be application/x-subrip.
        val mime = failedConversionMime("WEBVTT")
        assertNotEquals("application/x-subrip", mime)
        assertEquals("text/vtt", mime)
    }

    @Test
    fun unreadableOrGarbageBodyDropsTheTrack() {
        // Empty head (readSubtitleHead failed) or garbage: attaching would
        // just re-produce the dead track at parse time — drop instead.
        assertNull(failedConversionMime(""))
        assertNull(failedConversionMime("\u0000\u0001\u0002garbage-not-webvtt"))
    }

    @Test
    fun bomToleratedOnRescue() {
        // looksLikeVtt strips the BOM (SubtitleSurfaces), so a BOM-headed
        // sidecar whose conversion failed rescues as WebVTT, not drop.
        assertEquals("text/vtt", failedConversionMime("\uFEFFWEBVTT"))
    }
}
