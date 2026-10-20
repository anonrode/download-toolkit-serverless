package com.anonrode.downloader.providers

import org.jsoup.Jsoup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DramaRain regressions (investigation 03 §2).
 *
 * Live 2026-10-20:
 *  - the search page uses `<h4 class="entry-title"><a>` (the payload's
 *    `.post-title a, h2 a` matches nothing);
 *  - `slugGuess` fabricated `https://dramarain.com/the/` (HTTP 200, a show
 *    <h1>, ZERO episode links) and clicking it yielded "no episode found";
 *  - real show pages publish `https://dramarain.com/download?link=<b64>`
 *    episode gateways (7 "DOWNLOAD EPISODE N" rows on Light the Dark).
 */
class DramaRainJunkGuardTest {

    @Test
    fun oneWordRootsAndCategoryPagesAreNotShows() {
        assertFalse(DramaRainProvider.isPlausibleShowUrl("https://dramarain.com/the/"))
        assertFalse(DramaRainProvider.isPlausibleShowUrl("https://dramarain.com/drama/the/"))
        assertFalse(DramaRainProvider.isPlausibleShowUrl("https://dramarain.com/category/korean-drama/"))
        assertFalse(DramaRainProvider.isPlausibleShowUrl("https://dramarain.com/tag/romance/"))
        assertFalse(DramaRainProvider.isPlausibleShowUrl("https://dramarain.com/"))
    }

    @Test
    fun realShowSlugsAreAccepted() {
        assertTrue(DramaRainProvider.isPlausibleShowUrl("https://dramarain.com/light-the-dark-chinese-drama/"))
        assertTrue(DramaRainProvider.isPlausibleShowUrl("https://dramarain.com/the-promised-neverland-season-2/"))
        assertTrue(DramaRainProvider.isPlausibleShowUrl("https://dramarain.com/joy-of-life-s02-chinese-drama/"))
    }

    @Test
    fun episodeGatewaysAreRecognised() {
        assertTrue(DramaRainProvider.isDownloadGateway("https://dramarain.com/download?link=MTMzMzEsMA=="))
        assertTrue(DramaRainProvider.isDownloadGateway("https://dramarain.com/download/?link=MTMzMzEsMQ=="))
        assertFalse(DramaRainProvider.isDownloadGateway("https://dramarain.com/light-the-dark-chinese-drama/"))
        assertFalse(DramaRainProvider.isDownloadGateway("https://vdl.np-downloader.com/sdm_downloads/x/"))
    }

    @Test
    fun showPageWithGatewayAnchorsHasAnEpisodeSurface() {
        val html = """
            <html><body><div class="entry-content">
            <a href="https://dramarain.com/download?link=MTMzMzEsMA==">DOWNLOAD EPISODE 1</a>
            <a href="https://dramarain.com/download?link=MTMzMzEsMQ==">DOWNLOAD EPISODE 2</a>
            </div></body></html>
        """.trimIndent()
        assertTrue(DramaRainProvider.hasEpisodeSurface(Jsoup.parse(html)))
    }

    @Test
    fun fabricatedRootPageHasNoEpisodeSurface() {
        // Live shape of https://dramarain.com/the/: nav + synopsis only.
        val html = """
            <html><body><div class="entry-content">
            <h1>The 19th Medical Chart (Complete) | Japanese Drama</h1>
            <p>A synopsis with a trailer link.</p>
            <a href="https://dramarain.com/first-love-again-chinese-drama/">Related show</a>
            </div></body></html>
        """.trimIndent()
        assertFalse(DramaRainProvider.hasEpisodeSurface(Jsoup.parse(html)))
    }
}
