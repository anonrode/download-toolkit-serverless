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
        // title.rendered tag-stripping is the existing behavior; entity
        // escapes (&amp; etc.) and scraper decoration junk are now cleaned
        // at card construction via NameSanitizer (cleanCardTitle).
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

    // ---- cleanCardTitle (NameSanitizer on feed titles) ----------------------

    @Test
    fun `parseWpRestPosts strips scraper junk and decodes entities in card titles`() {
        val json = """[${post("Bloodhounds [Episode 1-16 Complete]", "https://nk.test/bh/", "b")},${
            post("Tom &amp; Jerry &#8211; The Movie", "https://nk.test/tj/", "b")
        }]"""
        val posts = TrendingFeed.parseWpRestPosts(json, "nkiri")
        assertEquals(listOf("Bloodhounds", "Tom & Jerry – The Movie"), posts.map { it.card.title })
    }

    @Test
    fun `parseWpRestPosts falls back to the raw title when cleaning blanks it`() {
        // The whole title IS decoration ("_Watch Online_"): cleanTitle
        // correctly strips it to nothing — the card must keep the raw string
        // rather than vanish from the row.
        val posts = TrendingFeed.parseWpRestPosts("[${post("_Watch Online_", "https://nk.test/wo/", "b")}]", "nkiri")
        assertEquals(listOf("_Watch Online_"), posts.map { it.card.title })
    }

    @Test
    fun `parseWpRestPosts cleans the title but keeps taxonomy terms raw`() {
        val json = "[${postWithTerms(
            "Solo Leveling [Episode 1-8 Added]", "https://nk.test/sl/",
            "links: https://downloadwella.com/f/2", listOf(listOf("K-Drama", "Sci-Fi"))
        )}]"
        val post = TrendingFeed.parseWpRestPosts(json, "nkiri")[0]
        assertEquals("Solo Leveling", post.card.title)
        // Terms feed ExplicitContentFilter + genreConfirmed — never cleaned.
        assertEquals(listOf("K-Drama", "Sci-Fi"), post.terms)
    }

    @Test
    fun `parseRssItems cleans titles while categories stay raw for genre confirmation`() {
        fun item(title: String, link: String, cat: String, img: String, body: String) =
            "<item><title><![CDATA[$title]]></title><link>$link</link>" +
                "<category><![CDATA[$cat]]></category>" +
                "<content:encoded><![CDATA[<img src=\"$img\">$body]]></content:encoded></item>"
        val xml = "<rss><channel>" +
            item("Alien Wave [Episode 1-8 Added]", "https://9ja.test/alien/", "Sci-Fi", "https://9ja.test/a.jpg", "dl https://downloadwella.com/f/9") +
            "</channel></rss>"
        // Ungated: title cleaned, bracket junk gone.
        assertEquals(listOf("Alien Wave"), TrendingFeed.parseRssItems(xml, "9jarocks", "https://9ja.test").map { it.title })
        // Gated: the RAW category still confirms the genre even though the
        // cleaned title no longer carries any genre wording.
        val gated = TrendingFeed.parseRssItems(xml, "9jarocks", "https://9ja.test", setOf("scifi"))
        assertEquals(listOf("Alien Wave"), gated.map { it.title })
    }

    @Test
    fun `parseAsiancResults and parseNepuResults clean card titles`() {
        val asianc = TrendingFeed.parseAsiancResults(
            """[{"url":"/drama/ml/","name":"My Love (K-Drama)","cover":""}]""",
            "asianc", "https://ac.test"
        )
        assertEquals(listOf("My Love"), asianc.map { it.title })
        val nepu = TrendingFeed.parseNepuResults(
            """{"results":[{"id":"1","media_type":"movie","title":"Fight Club 2 _Watch Online_","poster_path":""}]}""",
            "nepu", "https://nepu.test"
        )
        assertEquals(listOf("Fight Club 2"), nepu.map { it.title })
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
        // Three per site, not five: four sites x five cards is 20 > ROW_LIMIT
        // (16), so the "full" merge would be truncated and could not contain
        // the 15-card partials at all — the subset property only means
        // something while nothing is capped. The cap has its own test below.
        val a = (1..3).map { mcard("vault", "A$it") }
        val b = (1..3).map { mcard("nkiri", "B$it") }
        val c = (1..3).map { mcard("prey", "C$it") }
        val d = (1..3).map { mcard("9ja", "D$it") }
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

    // ---- genre confirmation (v3.1.6: "i just want the genre to be accurate")
    // The pollution this fixes: WP search matches BODIES, so a kdrama post
    // mentioning "sci-fi" landed in Sci-Fi. Confirmation uses the site's own
    // taxonomy names (already inside the _embed bytes) or the title.

    private fun postWithTerms(
        title: String,
        link: String,
        body: String,
        termGroups: List<List<String>>
    ): String {
        val groups = termGroups.joinToString(",") { g ->
            "[" + g.joinToString(",") { name -> "{\"name\":\"$name\"}" } + "]"
        }
        return "{\"title\":{\"rendered\":\"$title\"},\"link\":\"$link\",\"content\":{\"rendered\":\"$body\"}," +
            "\"_embedded\":{\"wp:term\":[$groups]}}"
    }

    @Test
    fun `parseWpRestPosts harvests category and tag term names from both embeds`() {
        val json = "[${postWithTerms("Dune 2", "https://nk.test/d2/", "b", listOf(listOf("Sci-Fi", "Movies"), listOf("dune")))}]"
        assertEquals(listOf("Sci-Fi", "Movies", "dune"), TrendingFeed.parseWpRestPosts(json, "nkiri")[0].terms)
        // The no-_embedded shape (jetpack poster field) still parses, terms empty.
        val plain = "[${post("Dune 2", "https://nk.test/d2/", "b", poster = "https://nk.test/p.jpg")}]"
        assertTrue(TrendingFeed.parseWpRestPosts(plain, "nkiri")[0].terms.isEmpty())
    }

    @Test
    fun `normalizeGenre crushes case punctuation and spaces`() {
        // Aliases are stored post-normalization; a term like "Science Fiction"
        // must crush to "sciencefiction" to meet the alias check.
        assertEquals("scifi", TrendingFeed.normalizeGenre("Sci-Fi"))
        assertEquals("sciencefiction", TrendingFeed.normalizeGenre(" Science Fiction "))
        assertEquals("martialarts", TrendingFeed.normalizeGenre("Martial Arts!"))
        assertEquals("", TrendingFeed.normalizeGenre("———"))
    }

    @Test
    fun `genreConfirmed accepts title or taxonomy terms and no-aliases is open`() {
        val scifi = setOf("scifi", "sciencefiction")
        assertTrue(TrendingFeed.genreConfirmed("Sci-Fi Lovers 2026", emptyList(), scifi))
        assertTrue(TrendingFeed.genreConfirmed("Dune Prophecy", listOf("Movies", "Science Fiction"), scifi))
        // Term CONTAINING an alias counts: WP taxonomies get compound names.
        assertTrue(TrendingFeed.genreConfirmed("X", listOf("Science Fiction & Fantasy"), scifi))
        // The exact device complaint: anime with no genre signal in title or terms.
        org.junit.Assert.assertFalse(TrendingFeed.genreConfirmed("Jujutsu Kaisen 03", listOf("Anime"), scifi))
        // No aliases configured = no objection (non-genre callers).
        assertTrue(TrendingFeed.genreConfirmed("Anything", emptyList(), emptySet()))
    }

    @Test
    fun `gateWpRest genre path confirms via taxonomy and never ungates`() {
        val confirmed = postWithTerms(
            "Alien Romulus", "https://nk.test/alien/",
            "links: https://downloadwella.com/f/1", listOf(listOf("Sci-Fi"))
        )
        val bodyNoise = postWithTerms(
            "Solo Leveling S02", "https://nk.test/sl/",
            "a sci-fi themed kdrama https://downloadwella.com/f/2", listOf(listOf("Anime", "Kdrama"))
        )
        val stubNoGenre = postWithTerms(
            "Some Trailer", "https://nk.test/tr/",
            "youtube embed only", listOf(listOf("Sci-Fi"))
        )
        val posts = TrendingFeed.parseWpRestPosts("[$confirmed,$bodyNoise,$stubNoGenre]", "nkiri")
        val aliases = setOf("scifi", "sciencefiction")

        // Ungated (trending/search) behavior UNCHANGED: gate keeps link-holders.
        assertEquals(listOf("Alien Romulus", "Solo Leveling S02"), TrendingFeed.gateWpRest(posts).map { it.title })
        // Genre path: taxonomy confirms the real one; the body-mention dies.
        assertEquals(listOf("Alien Romulus"), TrendingFeed.gateWpRest(posts, aliases).map { it.title })
        // All-stub genre batch → EMPTY, not the ungated fallback: a genre row
        // shows nothing over showing noise; the next site fills.
        assertTrue(TrendingFeed.gateWpRest(listOf(TrendingFeed.parseWpRestPosts("[$stubNoGenre]", "nkiri")[0]), aliases).isEmpty())
    }

    @Test
    fun `parseRssItems confirms genre via category names and shares the link gate`() {
        fun item(title: String, link: String, cat: String, img: String, body: String) =
            "<item><title><![CDATA[$title]]></title><link>$link</link>" +
                "<category><![CDATA[$cat]]></category>" +
                "<content:encoded><![CDATA[<img src=\"$img\">$body]]></content:encoded></item>"
        val xml = "<rss><channel>" +
            item("Alien Wave", "https://9ja.test/alien/", "Sci-Fi", "https://9ja.test/a.jpg", "dl https://downloadwella.com/f/9") +
            item("Kaisen", "https://9ja.test/k/", "Anime", "https://9ja.test/k.jpg", "dl https://downloadwella.com/f/8") +
            "</channel></rss>"
        val aliases = setOf("scifi", "sciencefiction")
        // Ungated (trending-era) parse unaffected: both link-holders pass.
        assertEquals(listOf("Alien Wave", "Kaisen"), TrendingFeed.parseRssItems(xml, "9jarocks", "https://9ja.test").map { it.title })
        val gated = TrendingFeed.parseRssItems(xml, "9jarocks", "https://9ja.test", aliases)
        assertEquals(listOf("Alien Wave"), gated.map { it.title })
        assertEquals("https://9ja.test/a.jpg", gated.first().posterUrl)
    }

    // ---- API-JSON genre sources (v3.1.6: nepu + asianc) --------------------

    @Test
    fun `parseNepuResults builds watch urls and tmdb cdn posters`() {
        val json = """{"results":[
            {"id":"123","media_type":"movie","title":"Fight Club 2","poster_path":"/abc.jpg"},
            {"id":"456","media_type":"tv","name":"War Room","poster_path":null},
            {"id":"","media_type":"movie","title":"No Id","poster_path":"/x.jpg"},
            {"id":"789","media_type":"movie","title":"","poster_path":"/y.jpg"}
        ]}"""
        val cards = TrendingFeed.parseNepuResults(json, "nepu", "https://nepu.test")
        assertEquals(2, cards.size) // blank id / blank title dropped
        assertEquals("Fight Club 2", cards[0].title)
        assertEquals("https://nepu.test/watch/movie/123", cards[0].url)
        assertEquals("https://image.tmdb.org/t/p/w342/abc.jpg", cards[0].posterUrl)
        assertEquals("Movies", cards[0].category)
        assertEquals("https://nepu.test/watch/tv/456", cards[1].url) // media_type honored
        assertEquals("", cards[1].posterUrl) // null poster_path -> no poster, tile falls through
        // Garbage tolerance (deflate/HTML error page arriving as text):
        assertTrue(TrendingFeed.parseNepuResults("<html>nope", "nepu", "https://nepu.test").isEmpty())
    }

    @Test
    fun `parseAsiancResults prefixes relative urls and falls back to value`() {
        val json = """[
            {"url":"/drama/my-love/","name":"My Love","cover":"https://ac.test/c1.jpg"},
            {"url":"https://ac.test/drama/gone/","value":"Gone","cover":""},
            {"url":"","name":"No Url","cover":""}
        ]"""
        val cards = TrendingFeed.parseAsiancResults(json, "asianc", "https://ac.test")
        assertEquals(2, cards.size)
        assertEquals("https://ac.test/drama/my-love/", cards[0].url)
        assertEquals("https://ac.test/c1.jpg", cards[0].posterUrl)
        assertEquals("Asian Drama", cards[0].category)
        assertEquals("Gone", cards[1].title) // name -> value fallback
        assertEquals("https://ac.test/drama/gone/", cards[1].url) // absolute stays absolute
        assertTrue(TrendingFeed.parseAsiancResults("[]", "asianc", "https://ac.test").isEmpty())
    }

    @Test
    fun `wpRestLiteUrl asks for the two fields the tiles actually read`() {
        assertEquals(
            "https://nk.test/wp-json/wp/v2/posts?per_page=1&search=comedy&_fields=title,jetpack_featured_media_url",
            TrendingFeed.wpRestLiteUrl("https://nk.test/", "comedy", 1)
        )
        assertEquals(null, TrendingFeed.wpRestLiteUrl("", "comedy", 1))
    }
}
