package com.anonrode.downloader.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure parse/gate tests for the WP-REST search path shared by trending,
 * genre rows and (since 2026-09-14) NkiriProvider.search — the response
 * shape mirrors what was live-verified on nkiri.top and naijavault.com
 * that day: title.rendered entity-encoded, posters via
 * jetpack_featured_media_url or _embedded wp:featuredmedia, full post
 * body in content.rendered (the DownloadLinkGate input).
 */
class TrendingFeedParseTest {

    private fun post(title: String, link: String, body: String, poster: String? = null, embed: Boolean = false): String {
        val posterField = when {
            poster != null && !embed -> ""","jetpack_featured_media_url":"$poster""""
            embed -> ""","_embedded":{"wp:featuredmedia":[{"source_url":"$poster"}]}"""
            else -> ""
        }
        return """{"title":{"rendered":"$title"},"link":"$link","content":{"rendered":"$body"}$posterField}"""
    }

    @Test
    fun `parseWpRestPosts strips title tags and reads both poster shapes`() {
        // title.rendered tag-stripping is the existing behavior; WP entity
        // escapes (&amp; etc.) are NOT decoded anywhere today.
        val json = """[${
            post("The <b>Movie</b> (2026)", "https://nk.test/scary/", "watch it", poster = "https://nk.test/a.jpg")
        },${
            post("The Rapture", "https://nk.test/rapture/", "series", poster = "https://nk.test/b.jpg", embed = true)
        }]"""
        val posts = TrendingFeed.parseWpRestPosts(json, "nkiri")
        assertEquals(2, posts.size)
        assertEquals("The Movie (2026)", posts[0].card.title)
        assertEquals("https://nk.test/a.jpg", posts[0].card.posterUrl)
        assertEquals("https://nk.test/b.jpg", posts[1].card.posterUrl)
        assertEquals("nkiri", posts[0].card.site)
        assertEquals("watch it", posts[0].body)
    }

    @Test
    fun `parseWpRestPosts skips blank title or link and tolerates malformed json`() {
        val json = """[${post("", "https://nk.test/x/", "body")},${post("No Link", "", "body")}]"""
        assertTrue(TrendingFeed.parseWpRestPosts(json, "nkiri").isEmpty())
        assertTrue(TrendingFeed.parseWpRestPosts("not json", "nkiri").isEmpty())
        assertTrue(TrendingFeed.parseWpRestPosts("""[{"nope":1}]""", "nkiri").isEmpty())
    }

    @Test
    fun `gateWpRest drops stubs but keeps the whole batch when none pass`() {
        val real = post("Action Movie", "https://nk.test/am/", "links: https://downloadwella.com/f/1")
        val stub = post("Action Trailer", "https://nk.test/at/", "youtube embed only")
        val both = TrendingFeed.parseWpRestPosts("[$real,$stub]", "nkiri")
        val gated = TrendingFeed.gateWpRest(both)
        assertEquals(listOf("https://nk.test/am/"), gated.map { it.url })

        // All-stub batch (unknown locker family): the caller-keeps-fallback
        // policy must NOT produce an empty result set.
        val allStubs = TrendingFeed.parseWpRestPosts("[$stub,$stub]", "nkiri")
        assertEquals(2, TrendingFeed.gateWpRest(allStubs).size)
    }

    @Test
    fun `wpRestUrl pins the live-verified param order`() {
        assertEquals(
            "https://nk.test/wp-json/wp/v2/posts?per_page=15&search=sci-fi&orderby=relevance&_embed=1",
            TrendingFeed.wpRestUrl("https://nk.test/", "sci-fi", 15, "&orderby=relevance")
        )
        // Discovery feeds keep date order (no extra params, query null).
        assertEquals(
            "https://nv.test/wp-json/wp/v2/posts?per_page=8&_embed=1",
            TrendingFeed.wpRestUrl("https://nv.test", null, 8)
        )
        assertEquals(
            "https://nk.test/wp-json/wp/v2/posts?per_page=6&search=the%20rapture&_embed=1",
            TrendingFeed.wpRestUrl("https://nk.test", "the rapture", 6)
        )
        // CategoryFeed tiles rely on per_page=1 — pin that no default limit sneaks in.
        assertTrue(
            TrendingFeed.wpRestUrl("https://nk.test", "action", 1)!!.contains("per_page=1&search")
        )
        assertEquals(null, TrendingFeed.wpRestUrl("   ", "q", 5))
    }

    @Test
    fun `nkiri category mapping unchanged from the HTML era`() {
        assertEquals("Asian Drama", NkiriProvider.categoryForTitle("Something S02 (Complete) | Korean Drama"))
        assertEquals("Asian Drama", NkiriProvider.categoryForTitle("Blade The Series 2006"))
        assertEquals("Nollywood", NkiriProvider.categoryForTitle("Lagos Money (Yoruba)"))
        assertEquals("Asian Drama & Movies", NkiriProvider.categoryForTitle("Scary Movie (2026) | Download Hollywood Movie"))
    }
}
