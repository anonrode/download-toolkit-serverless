package com.anonrode.downloader.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The subtitle auto-pick rule (2026-09-14: "the inbuilt video player
 * should read the subtitles for MKV videos"). Embedded Matroska text
 * tracks surface DISABLED unless something selects them, so this pure
 * rule decides which track turns on by itself and — equally important —
 * which situations must stay OFF (never burn a foreign-language track
 * just because the user asked for English).
 */
class SubtitleAutoPickTest {

    private fun track(lang: String? = null, label: String? = null, def: Boolean = false) =
        SubTrackInfo(language = lang, label = label, isDefault = def)

    @Test
    fun mkvThreeLetterCodeMatchesTwoLetterPreference() {
        // Anime fansub MKV: "eng" language tag, user preference "en".
        val tracks = listOf(track(lang = "jpn"), track(lang = "eng"), track(lang = "spa"))
        assertEquals(1, autoPickSubtitleIndex(tracks, "en"))
    }

    @Test
    fun preferredWinsOverContainerDefaultFlag() {
        val tracks = listOf(
            track(lang = "por", def = true),
            track(lang = "eng")
        )
        assertEquals(1, autoPickSubtitleIndex(tracks, "en"))
    }

    @Test
    fun defaultFlagWinsWhenNoPreferredLanguageExists() {
        // User said English, file only ships Spanish (default) + French:
        // NO auto-on. Showing Spanish against the user's stated language
        // is how players get untrusted.
        val tracks = listOf(track(lang = "spa", def = true), track(lang = "fre"))
        assertEquals(-1, autoPickSubtitleIndex(tracks, "en"))
    }

    @Test
    fun labelNameMatchesWhenLanguageFieldEmpty() {
        // Sidecar attached with language "und" but label carrying the
        // name; and the classic "English (CC)" label form.
        val tracks = listOf(
            track(lang = "und", label = "English (CC)"),
            track(lang = "und", label = "Bahasa Indonesia")
        )
        assertEquals(0, autoPickSubtitleIndex(tracks, "en"))
    }

    @Test
    fun obsoleteBibliographicCodesAreAliases() {
        // MKVs written by older muxers use 639-2/B: "fre" not "fra",
        // "chi" not "zho".
        assertEquals(0, autoPickSubtitleIndex(listOf(track(lang = "fre")), "fr"))
        assertEquals(0, autoPickSubtitleIndex(listOf(track(lang = "chi")), "zh"))
    }

    @Test
    fun regionTaggedIetfLanguageMatches() {
        assertEquals(0, autoPickSubtitleIndex(listOf(track(lang = "pt-BR")), "pt"))
    }

    @Test
    fun allPreferenceTakesDefaultTrackElseFirst() {
        val withDefault = listOf(track(lang = "kor"), track(lang = "jpn", def = true))
        assertEquals(1, autoPickSubtitleIndex(withDefault, "all"))
        val noDefault = listOf(track(lang = "kor"), track(lang = "jpn"))
        assertEquals(0, autoPickSubtitleIndex(noDefault, "all"))
    }

    @Test
    fun emptyTrackListStaysOff() {
        assertEquals(-1, autoPickSubtitleIndex(emptyList(), "en"))
    }

    @Test
    fun unknownPreferenceDoesNotPanic() {
        // A code the alias table does not know still matches its own
        // literal (exact tag) and nothing else.
        assertEquals(0, autoPickSubtitleIndex(listOf(track(lang = "swe")), "swe"))
        assertEquals(-1, autoPickSubtitleIndex(listOf(track(lang = "dan")), "swe"))
    }

    // -- sidecar language tag extraction -------------------------------------

    @Test
    fun sidecarTagFromFileName() {
        assertEquals("en", sidecarLanguageHint("movie.en.srt", "movie"))
    }

    @Test
    fun sidecarTagRules() {
        assertEquals("en", sidecarLanguageHint("movie.en.srt", "movie"))
        assertEquals("pt", sidecarLanguageHint("movie.pt-BR.srt", "movie")) // region dropped
        assertEquals("und", sidecarLanguageHint("movie.final.srt", "movie")) // "final" is 5 letters
        assertEquals("und", sidecarLanguageHint("other.en.srt", "movie"))   // not our video
        assertEquals("eng", sidecarLanguageHint("movie_eng.ass", "movie"))
    }
}
