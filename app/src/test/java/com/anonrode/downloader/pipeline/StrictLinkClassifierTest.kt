package com.anonrode.downloader.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictLinkClassifierTest {

    @Test
    fun classify_directMediaExtensions() {
        val mp4 = StrictLinkClassifier.classify("https://cdn.example.com/video.mp4")
        assertTrue(mp4 is StrictLinkClassifier.LinkClass.DirectMedia)
        assertEquals("mp4", (mp4 as StrictLinkClassifier.LinkClass.DirectMedia).ext)
        assertFalse(mp4.isHls)

        val m3u8 = StrictLinkClassifier.classify("https://edge.example.com/master.m3u8?token=xyz")
        assertTrue(m3u8 is StrictLinkClassifier.LinkClass.DirectMedia)
        assertEquals("m3u8", (m3u8 as StrictLinkClassifier.LinkClass.DirectMedia).ext)
        assertTrue(m3u8.isHls)
    }

    @Test
    fun classify_knownLockerHosts() {
        val viking = StrictLinkClassifier.classify("https://vikingfile.com/d/abc12345")
        assertTrue(viking is StrictLinkClassifier.LinkClass.KnownLocker)
        assertEquals("vikingfile.com", (viking as StrictLinkClassifier.LinkClass.KnownLocker).host)

        val wella = StrictLinkClassifier.classify("https://subdomain.downloadwella.com/f/xyz")
        assertTrue(wella is StrictLinkClassifier.LinkClass.KnownLocker)
        assertEquals("downloadwella.com", (wella as StrictLinkClassifier.LinkClass.KnownLocker).host)

        val loaded = StrictLinkClassifier.classify("https://loadedfiles.net/embed/test")
        assertTrue(loaded is StrictLinkClassifier.LinkClass.KnownLocker)

        // Lockers carrying media names in path must be KnownLocker, not DirectMedia
        val loadedMkv = StrictLinkClassifier.classify("https://loadedfiles.net/37b756a81351c952/John.Candy.I.Like.Me.mkv")
        assertTrue(loadedMkv is StrictLinkClassifier.LinkClass.KnownLocker)

        val vikingMkv = StrictLinkClassifier.classify("https://vikingfile.com/d/wtKpY7FHW2/Lanterns.S01E01.mkv")
        assertTrue(vikingMkv is StrictLinkClassifier.LinkClass.KnownLocker)
    }

    @Test
    fun classify_intermediateGatewayUnwrapsNkiri() {
        val wrapper = "https://thenkiri.com/dl/download-17827/?redirect=https%3A%2F%2Fdownloadwella.com%2Ff%2F123"
        val cl = StrictLinkClassifier.classify(wrapper, siteHost = "thenkiri.com")
        assertTrue(cl is StrictLinkClassifier.LinkClass.IntermediateGateway)
        val gw = cl as StrictLinkClassifier.LinkClass.IntermediateGateway
        assertEquals("redirect_wrapper", gw.gatewayType)
        assertEquals("https://downloadwella.com/f/123", gw.targetUrl)

        val unwrapped = StrictLinkClassifier.unwrapRedirect(wrapper)
        assertEquals("https://downloadwella.com/f/123", unwrapped)
    }

    @Test
    fun classify_intermediateGatewayNaijaVault() {
        val nvGw = "https://www.naijavault.com/dl-movie-action-2026/"
        val cl = StrictLinkClassifier.classify(nvGw, siteHost = "naijavault.com")
        assertTrue(cl is StrictLinkClassifier.LinkClass.IntermediateGateway)
        assertEquals("download_gateway", (cl as StrictLinkClassifier.LinkClass.IntermediateGateway).gatewayType)
    }

    @Test
    fun classify_sameSiteNavigationJunk() {
        val site = "naijavault.com"
        assertTrue(StrictLinkClassifier.isNavigationJunk("https://naijavault.com/", siteHost = site))
        assertTrue(StrictLinkClassifier.isNavigationJunk("https://www.naijavault.com/category/nollywood/", siteHost = site))
        assertTrue(StrictLinkClassifier.isNavigationJunk("https://naijavault.com/tag/action/", siteHost = site))
        assertTrue(StrictLinkClassifier.isNavigationJunk("https://naijavault.com/page/2/", siteHost = site))
        assertTrue(StrictLinkClassifier.isNavigationJunk("https://naijavault.com/season-list/", siteHost = site))
        assertTrue(StrictLinkClassifier.isNavigationJunk("https://naijavault.com/dmca/", siteHost = site))
    }

    @Test
    fun classify_selfReferenceJunk() {
        val site = "dramarain.com"
        val path = "/drama/vincenzo-korean-drama"
        assertTrue(StrictLinkClassifier.isNavigationJunk("https://dramarain.com/drama/vincenzo-korean-drama/", siteHost = site, currentPath = path))
    }

    @Test
    fun classify_invalidUrls() {
        assertTrue(StrictLinkClassifier.isNavigationJunk(""))
        assertTrue(StrictLinkClassifier.isNavigationJunk("   "))
        assertTrue(StrictLinkClassifier.isNavigationJunk("javascript:void(0)"))
        assertTrue(StrictLinkClassifier.isNavigationJunk("#download-section"))
        assertTrue(StrictLinkClassifier.isNavigationJunk("mailto:admin@example.com"))
    }
}
