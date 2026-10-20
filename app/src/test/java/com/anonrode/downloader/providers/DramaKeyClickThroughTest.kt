package com.anonrode.downloader.providers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DramaKey click-through (investigation 03 §2, verified live 2026-10-20).
 *
 * Search returns cards (72 in the device log); the show page publishes 12
 * downloadwella SxxExx locker anchors. The compiled flat sweep must accept
 * every one of them and reject nav/how-to links.
 */
class DramaKeyClickThroughTest {

    // Captured from
    // https://dramakey.com/where-the-mask-ends-s01-complete-chinese-drama/
    private val liveLockerAnchors = listOf(
        "https://downloadwella.com/3izq3qk2m9j0/Where.the.Mask.Ends.S01E01.(DRAMAKEY.COM).mkv.html",
        "https://downloadwella.com/d1uh53t5ppis/Where.the.Mask.Ends.S01E02.(DRAMAKEY.COM).mkv.html",
        "https://downloadwella.com/t5fhovn1jhcl/Where.the.Mask.Ends.S01E03.(DRAMAKEY.COM).mkv.html",
        "https://downloadwella.com/nu4gjongsqh4/Where.the.Mask.Ends.S01E04.(DRAMAKEY.COM).mkv.html",
        "https://downloadwella.com/1wxz1ha79tnt/Where.the.Mask.Ends.S01E05.(DRAMAKEY.COM).mkv.html",
        "https://downloadwella.com/ebc453sdod4e/Where.the.Mask.Ends.S01E06.(DRAMAKEY.COM).mkv.html",
        "https://downloadwella.com/w34xfoajnddh/Where.the.Mask.Ends.S01E07.(DRAMAKEY.COM).mkv.html",
        "https://downloadwella.com/n76tnc417l26/Where.the.Mask.Ends.S01E08.(DRAMAKEY.COM).mkv.html",
        "https://downloadwella.com/r3xyxjd5w336/Where.the.Mask.Ends.S01E09.(DRAMAKEY.COM).mkv.html",
        "https://downloadwella.com/ib0rx14449jk/Where.the.Mask.Ends.S01E10.(DRAMAKEY.COM).mkv.html",
        "https://downloadwella.com/n3igi65nv0fm/Where.the.Mask.Ends.S01E11.(DRAMAKEY.COM).mkv.html",
        "https://downloadwella.com/ij343ls829ij/Where.the.Mask.Ends.S01E12.(DRAMAKEY.COM).mkv.html"
    )

    @Test
    fun everyLiveLockerAnchorIsClickThrough() {
        assertTrue(liveLockerAnchors.all { DramaKeyProvider.isEpisodeTarget(it) })
    }

    @Test
    fun navigationAndHowToLinksAreNotEpisodes() {
        assertFalse(DramaKeyProvider.isEpisodeTarget("https://dramakey.com/how-to-download"))
        assertFalse(DramaKeyProvider.isEpisodeTarget("https://dramakey.com/chinese-drama-menu-dramakey/"))
        assertFalse(DramaKeyProvider.isEpisodeTarget("https://dramakey.com/category/korean/"))
    }
}
