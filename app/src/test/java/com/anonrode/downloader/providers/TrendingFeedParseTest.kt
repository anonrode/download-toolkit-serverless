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
            poster != null && !embed -> ",\"jetpack_featured_media_url\":\"$poster\""
            embed -> ",\"_embedded\":{\"wp:featuredmedia\":[{\"source_url\":\"$poster\"}]}"
            else -> ""
        }
        return "{\"title\":{\"rendered\":\"$title\"},\"link\":\"$link\",\"content\":{\"rendered\":\"$body\"}$posterField}"
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
        // URLEncoder query semantics encode spaces as '+' (WP/PHP decodes it
        // back to a space — this is the exact form trending/genre have been
        // shipping in production through this builder).
        assertEquals(
            "https://nk.test/wp-json/wp/v2/posts?per_page=6&search=the+rapture&_embed=1",
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

    // ---- mergeRoundRobin — the streaming-trending contract (v3.1.6) --------
    // fetch() publishes partial merges while sites trickle in; the UI is only
    // correct if a partial merge (a) interleaves variety the same way the
    // final one does, and (b) is an ORDER-PRESERVING SUBSET of the final
    // merge — so late sites can only ever ADD cards, never reshuffle or drop
    // what the user already sees mid-scroll.

    private fun mcard(site: String, title: String) =
        com.anonrode.downloader.data.models.ShowCard(title = title, url = "https://$site.example/$title", site = site)

    @Test
    fun `mergeRoundRobin interleaves variety and dedupes by normalized title`() {
        val a = listOf(mcard("vault", "Alpha One"), mcard("vault", "Shared Title"))
        val b = listOf(mcard("nkiri", "Beta One"))
        val d = listOf(mcard("prey", "Shared-Title!")) // same normalization key as A's second
        val merged = TrendingFeed.mergeRoundRobin(listOf(a, b, emptyList(), d))
        // Round-robin output order decides the dedupe winner, NOT slot
        // priority: D0 lands in round 0, before A1's round — so "Shared-Title!"
        // is kept and A's later "Shared Title" is dropped.
        assertEquals(listOf("Alpha One", "Beta One", "Shared-Title!"), merged.map { it.title })
        assertEquals(3, merged.size)
        assertEquals("prey", merged[2].site)
    }

    @Test
    fun `mergeRoundRobin treats unarrived slots as inert`() {
        val a = listOf(mcard("vault", "One"), mcard("vault", "Two"))
        val b = listOf(mcard("nkiri", "Three"))
        assertEquals(
            TrendingFeed.mergeRoundRobin(listOf(a, b)),
            TrendingFeed.mergeRoundRobin(listOf(a, b, emptyList(), emptyList()))
        )
    }

    @Test
    fun `mergeRoundRobin partials are order-preserving subsets of the full merge`() {
        val a = (1..5).map { mcard("vault", "A$it") }
        val b = (1..5).map { mcard("nkiri", "B$it") }
        val c = (1..5).map { mcard("prey", "C$it") }
        val d = (1..5).map { mcard("9ja", "D$it") }
        val full = TrendingFeed.mergeRoundRobin(listOf(a, b, c, d)).map { it.title }
        // Every progressively-grown partial must keep the full merge's
        // relative order of its own cards (no reshuffle under the user).
        for (partial in listOf(
            TrendingFeed.mergeRoundRobin(listOf(a, emptyList(), emptyList(), emptyList())),
            TrendingFeed.mergeRoundRobin(listOf(a, b, emptyList(), emptyList())),
            TrendingFeed.mergeRoundRobin(listOf(a, b, c, emptyList()))
        )) {
            val titles = partial.map { it.title }
            val projected = full.filter { it in titles }
            assertEquals(titles, projected)
        }
        // And the A+B partial really does appear inside the full order.
        assertTrue(full.containsAll(listOf("A1", "B1", "A2", "B2")))
    }

    @Test
    fun `mergeRoundRobin caps the row at 16`() {
        val merged = TrendingFeed.mergeRoundRobin(
            listOf(
                (1..10).map { mcard("vault", "V$it") },
                (1..10).map { mcard("nkiri", "N$it") },
                (1..10).map { mcard("prey", "P$it") },
                (1..10).map { mcard("9ja", "J$it") }
            )
        )
        assertEquals(16, merged.size)
    }
}
