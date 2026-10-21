package com.anonrode.downloader.resolvers

import com.anonrode.downloader.pipeline.ResolvedHints
import com.anonrode.downloader.providers.SubtitleSurfaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * megaplay getSources `tracks[]` caption surface (investigation 11 / share-43,
 * owner subtitle directive for Demon's Ascension + Anitaku).
 *
 * LIVE payload captured 2026-10-21 (getSources?id=160545, Demon's Ascension
 * E3): tracks[] = English captions VTT (default:true) + a thumbnails preview
 * VTT, sources under `enc` (AES). Until this surface existed the resolver
 * consumed only sources.file/enc, so every megaplay-lane download shipped
 * without subtitles even though the engine's sidecar pass and the megaplay
 * referer policy were already correct (live referer flip: the VTT host 200s
 * ONLY with `Referer: https://megaplay.buzz/` — 403, 4553B HTML without).
 */
class MegaplayCaptionsTest {

    /** Shape-faithful excerpt of the live getSources body (URLs swapped to a
     *  fixed test host; structure, label, kind, defaults preserved). */
    private val liveShapeJson = """
        {"tracks":[
          {"file":"https://j1i9s.example-cdn.world/anime/2743214d/fd18cd94/subtitles/c40663f42054f5279bc47cda1b5066f5.vtt",
           "label":"English","kind":"captions","default":true},
          {"file":"https://j1i9s.example-cdn.world/anime/2743214d/fd18cd94/preview/thumbnails.vtt",
           "kind":"thumbnails"}],
         "t":1,"intro":{"start":0,"end":0},"server":4,
         "enc":"wdeBruh3qqn_i5wUNnyaPcXqidp1UWP84FfPHzGyKXC6P8Y_A8gu1t0miB6N0KbsrhHfM2pvqhDhN2742N_Y91mKO_dhY9Juv-1mVz7EQ9bkulhyxWUtd_5pHU6CLptss7ObLjAJ-Pcmch-LBvyilAKK-iWHzdk8EIUmJMC1mTU"}
    """.trimIndent()

    private val masterUrl = "https://fetch.nexabloom.top/anime/2743214d/fd18cd94/master.m3u8"
    private val sourcePage = "https://megaplay.buzz/stream/s-2/141104/sub?autostart=true"

    @Before
    fun clearSurfaces() {
        SubtitleSurfaces.clearForTest()
        ResolvedHints.clearForTests()
    }

    @Test
    fun liveTrackShapeYieldsExactlyTheCaptionsTrack() {
        val tracks = KisskhMegaplayResolver.parseMegaplayCaptions(liveShapeJson)
        assertEquals(1, tracks.size)
        val track = tracks.single()
        assertEquals(
            "https://j1i9s.example-cdn.world/anime/2743214d/fd18cd94/subtitles/c40663f42054f5279bc47cda1b5066f5.vtt",
            track.url
        )
        // "thumbnails" preview must never leak into sidecars.
        assertTrue(track.url.contains("/subtitles/"))
        assertEquals("en", track.language) // label "English" translated
        assertEquals("English", track.label)
        assertEquals("vtt", track.format)
    }

    @Test
    fun recordedUnderEveryKeyTheEngineQueries() {
        KisskhMegaplayResolver.recordMegaplayCaptions(masterUrl, liveShapeJson, sourcePage)
        val wanted = "https://j1i9s.example-cdn.world/anime/2743214d/fd18cd94/subtitles/c40663f42054f5279bc47cda1b5066f5.vtt"
        // task.directUrl key (SubitleSurfaces) ...
        assertEquals(listOf(wanted), SubtitleSurfaces.tracksFor(masterUrl).map { it.url })
        // ... and the crack source page as the secondary surface.
        assertEquals(listOf(wanted), SubtitleSurfaces.tracksFor(sourcePage).map { it.url })
        // task.directUrl key (ResolvedHints) with the REFERER the live flip proved.
        val hints = ResolvedHints.subtitlesFor(masterUrl)
        assertEquals(listOf(wanted), hints.map { it.url })
        assertEquals("vtt", hints.single().kind)
        assertEquals("https://megaplay.buzz/", ResolvedHints.refererFor(masterUrl))
        // The engine fetch branch reads refererFor(directUrl) FIRST; this is
        // exactly the value the live 200/403 flip demands.
    }

    @Test
    fun labelLanguagesTranslateAndUnknownLabelsFallBackToUnd() {
        assertEquals("en", KisskhMegaplayResolver.megaplayLangFromLabel("English"))
        assertEquals("es", KisskhMegaplayResolver.megaplayLangFromLabel("español"))
        assertEquals("pt-BR", KisskhMegaplayResolver.megaplayLangFromLabel("Brazilian Portuguese"))
        // Already an ISO-code-style label passes through sanitized.
        assertEquals("pt-br", KisskhMegaplayResolver.megaplayLangFromLabel("pt BR"))
        assertEquals("und", KisskhMegaplayResolver.megaplayLangFromLabel("Klingon添加剂"))
        assertEquals("und", KisskhMegaplayResolver.megaplayLangFromLabel(""))
        assertEquals("ja", KisskhMegaplayResolver.megaplayLangFromLabel("日本語"))
    }

    @Test
    fun payloadWithoutTracksRecordsNothing() {
        val noTracks = """{"enc":"AAAAAAAAAA==","server":4}"""
        assertTrue(KisskhMegaplayResolver.parseMegaplayCaptions(noTracks).isEmpty())
        KisskhMegaplayResolver.recordMegaplayCaptions(masterUrl, noTracks, sourcePage)
        // Negative contract: nothing recorded anywhere — a trackless download
        // must be byte-identical to the pre-fix flow (no hints, no surfaces).
        assertTrue(SubtitleSurfaces.tracksFor(masterUrl).isEmpty())
        assertTrue(SubtitleSurfaces.tracksFor(sourcePage).isEmpty())
        assertTrue(ResolvedHints.subtitlesFor(masterUrl).isEmpty())
        assertNull(ResolvedHints.refererFor(masterUrl))
    }

    @Test
    fun malformedAndEmptyPayloadsAreQuietNoops() {
        assertTrue(KisskhMegaplayResolver.parseMegaplayCaptions("").isEmpty())
        assertTrue(KisskhMegaplayResolver.parseMegaplayCaptions("not json at all").isEmpty())
        KisskhMegaplayResolver.recordMegaplayCaptions(masterUrl, "not json", sourcePage)
        assertTrue(SubtitleSurfaces.tracksFor(masterUrl).isEmpty())
        assertNull(ResolvedHints.refererFor(masterUrl))
    }
}
