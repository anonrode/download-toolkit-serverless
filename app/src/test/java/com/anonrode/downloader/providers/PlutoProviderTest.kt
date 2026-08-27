package com.anonrode.downloader.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for Pluto slug-stem matching, using slugs verified live on
 * plutomovies.com 2026-08-27: season hubs link dash-separated episode
 * slugs (-s01-e01), dl filenames use the compact shape (-s01e01), every
 * page has its own numeric /series/<id>, and search links are truncated
 * site-wide at ~30 chars ("sofia-the-first-royal-magic-s0", "…-se",
 * "house-of-the-dragon-2022-tv-s").
 */
class PlutoProviderTest {

    @Test
    fun stemStripsSeasonSuffix() {
        assertEquals("love-on-the-menu", PlutoProvider.slugStem("love-on-the-menu-season-1"))
    }

    @Test
    fun stemStripsDashEpisodeSuffix() {
        assertEquals("love-on-the-menu", PlutoProvider.slugStem("love-on-the-menu-s01-e01"))
    }

    @Test
    fun stemStripsCompactEpisodeSuffixFromDlFilename() {
        assertEquals(
            "sofia-the-first-royal-magic",
            PlutoProvider.slugStem("sofia-the-first-royal-magic-s01e03-32693-mkv")
        )
    }

    @Test
    fun stemStripsTruncatedEpisodeFragments() {
        assertEquals("sofia-the-first-royal-magic", PlutoProvider.slugStem("sofia-the-first-royal-magic-s0"))
        assertEquals("sofia-the-first-royal-magic", PlutoProvider.slugStem("sofia-the-first-royal-magic-se"))
        assertEquals("house-of-the-dragon-2022-tv", PlutoProvider.slugStem("house-of-the-dragon-2022-tv-s"))
    }

    @Test
    fun stemKeepsPlainShowSlugs() {
        assertEquals("all-american-2018-tv-series", PlutoProvider.slugStem("all-american-2018-tv-series"))
        assertEquals("power-book-iii-raising-kanan", PlutoProvider.slugStem("power-book-iii-raising-kanan"))
        assertEquals("beyond-the-gates-2025-tv-seri", PlutoProvider.slugStem("beyond-the-gates-2025-tv-seri"))
    }

    @Test
    fun truncatedStemMatchesFullStem() {
        assertTrue(
            PlutoProvider.sameSlugStem(
                PlutoProvider.slugStem("beyond-the-gates-2025-tv-seri"),
                PlutoProvider.slugStem("beyond-the-gates-2025-tv-series-s01-e01")
            )
        )
        assertTrue(
            PlutoProvider.sameSlugStem(
                PlutoProvider.slugStem("sofia-the-first-royal-magic-s0"),
                PlutoProvider.slugStem("sofia-the-first-royal-magic-s01e03-32693-mkv")
            )
        )
    }

    @Test
    fun unrelatedShowsDoNotMatch() {
        assertFalse(
            PlutoProvider.sameSlugStem(
                PlutoProvider.slugStem("love-on-the-menu-season-1"),
                PlutoProvider.slugStem("all-american-2018-tv-series")
            )
        )
        assertFalse(
            PlutoProvider.sameSlugStem(
                PlutoProvider.slugStem("sofia-the-first-royal-magic-s0"),
                PlutoProvider.slugStem("tyler-perrys-ruthless-2020-tv")
            )
        )
    }

    @Test
    fun tooShortStemsTrustThePage() {
        assertTrue(PlutoProvider.sameSlugStem("abc", "love-on-the-menu"))
    }
}
