package com.anonrode.downloader.resolvers

import com.anonrode.downloader.data.rules.DynamicRulesManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the LockerRegistry: classify() (evidence-based, never gates
 * on unknown hosts), findLockerLinksInHtml() (Jsoup + regex fallback with
 * nav-junk filtering), and the OTA lockerHosts seeding.
 */
class LockerRegistryTest {

    @After
    fun tearDown() {
        DynamicRulesManager.parseRulesJson("""{"version":"t.reset"}""")
    }

    // ---------------- classify() ----------------

    @Test
    fun classify_directMediaExtensions() {
        assertEquals(LockerRegistry.MediaKind.Direct,
            LockerRegistry.classify("https://cdn.example.com/movie.mp4"))
        assertEquals(LockerRegistry.MediaKind.Direct,
            LockerRegistry.classify("https://cdn.example.com/show.mkv"))
        assertEquals(LockerRegistry.MediaKind.Direct,
            LockerRegistry.classify("https://cdn.example.com/stream.m3u8"))
    }

    @Test
    fun classify_knownLockerHosts() {
        assertEquals(LockerRegistry.MediaKind.Locker("streamsss.net"),
            LockerRegistry.classify("https://streamsss.net/play/abc123"))
        assertEquals(LockerRegistry.MediaKind.Locker("streamwish.com"),
            LockerRegistry.classify("https://streamwish.com/embed/xyz"))
        // Subdomain of a known locker host (no media extension — the ext
        // check fires first, so this must be a non-media path to test the
        // hostname-boundary match)
        assertEquals(LockerRegistry.MediaKind.Locker("downloadwella.com"),
            LockerRegistry.classify("https://fsmc02.downloadwella.com/watch/xyz"))
    }

    @Test
    fun classify_unknownHostsAreUnknownNotNone() {
        // The core contract: never refuse an unfamiliar link.
        assertEquals(LockerRegistry.MediaKind.Unknown::class.java,
            LockerRegistry.classify("https://brand-new-locker.example/embed/1").javaClass)
    }

    @Test
    fun classify_navJunkIsNone() {
        assertEquals(LockerRegistry.MediaKind.None,
            LockerRegistry.classify("https://naijaprey.tv/"))
        assertEquals(LockerRegistry.MediaKind.None,
            LockerRegistry.classify("https://naijaprey.tv/category/movies/"))
        assertEquals(LockerRegistry.MediaKind.None,
            LockerRegistry.classify("https://dramarain.com/chinese-drama/"))
        assertEquals(LockerRegistry.MediaKind.None,
            LockerRegistry.classify("https://9jarocks.net/date/2026/08/01"))
        assertEquals(LockerRegistry.MediaKind.None,
            LockerRegistry.classify("https://nkiri.ink/dmca/"))
    }

    @Test
    fun classify_shallowShowSlugsAreUnknown() {
        // Single-segment paths survive only when they carry media markers:
        // a show-style slug (>= 2 dashes) or an episode/movie marker.
        assertEquals(LockerRegistry.MediaKind.Unknown::class.java,
            LockerRegistry.classify("https://thenkiri.com/vincenzo-korean-drama/").javaClass)
        assertEquals(LockerRegistry.MediaKind.Unknown::class.java,
            LockerRegistry.classify("https://naijaprey.tv/download-a-wish-for-the-stars-nollywood-movie-2022/").javaClass)
        assertEquals(LockerRegistry.MediaKind.Unknown::class.java,
            LockerRegistry.classify("https://thenkiri.com/vincenzo-episode-1/").javaClass)
    }

    @Test
    fun classify_blankAndJunkUrls() {
        assertEquals(LockerRegistry.MediaKind.None, LockerRegistry.classify(""))
        assertEquals(LockerRegistry.MediaKind.None, LockerRegistry.classify("javascript:void(0)"))
        assertEquals(LockerRegistry.MediaKind.None, LockerRegistry.classify("#"))
    }

    // ---------------- findLockerLinksInHtml() ----------------

    @Test
    fun findLockerLinks_extractsHrefsAndSkipsNavJunk() {
        val html = """
            <html><body>
              <a href="/category/movies/">Movies</a>
              <a href="https://streamsss.net/play/abc">Streamsss</a>
              <a href="https://downloadwella.com/file.mkv">Download</a>
              <a href="https://naijaprey.tv/download-movies-vxi/">Nav</a>
            </body></html>
        """.trimIndent()
        val links = LockerRegistry.findLockerLinksInHtml(html)
        assertTrue(links.any { it.contains("streamsss") })
        assertTrue(links.any { it.contains("downloadwella") })
        assertTrue(links.none { it.contains("/category/") })
        assertTrue(links.none { it.contains("download-movies") })
    }

    @Test
    fun findLockerLinks_dataAttributesFallback() {
        val html = """<div class="ep" data-video="https://streamwish.com/embed/xyz">Ep</div>"""
        val links = LockerRegistry.findLockerLinksInHtml(html)
        assertTrue(links.any { it.contains("streamwish") })
    }

    // ---------------- OTA seeding ----------------

    @Test
    fun lockerHosts_otaParsed() {
        DynamicRulesManager.parseRulesJson(
            """{"version":"t.lockers","lockerHosts":["myhost.example","other.example"]}"""
        )
        val hosts = DynamicRulesManager.getLockerHosts()
        assertTrue(hosts.contains("myhost.example"))
        assertTrue(hosts.contains("other.example"))
    }
}
