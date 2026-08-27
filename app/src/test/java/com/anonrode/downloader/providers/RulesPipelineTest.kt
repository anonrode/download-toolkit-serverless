package com.anonrode.downloader.providers

import com.anonrode.downloader.data.rules.PipelineSource
import com.anonrode.downloader.data.rules.PipelineStep
import com.anonrode.downloader.data.rules.parseSitePipeline
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the declarative step-pipeline executor. These exercise the
 * pure extraction layer (HTML/JSON/RSS items, filters, label chains, sorting,
 * templating) against inline fixtures that mirror the four migrated sites'
 * page shapes; the network layer itself is covered by the probe harness.
 */
class RulesPipelineTest {

    private fun htmlOutcome(html: String, baseUri: String = "https://site.test/page"): RulesPipeline.StepOutcome =
        RulesPipeline.StepOutcome(html, Jsoup.parse(html, baseUri), null, baseUri)

    private fun jsonOutcome(json: String): RulesPipeline.StepOutcome =
        RulesPipeline.StepOutcome(json, null, JSONObject(json), "https://site.test/api")

    private fun rssOutcome(xml: String): RulesPipeline.StepOutcome =
        RulesPipeline.StepOutcome(xml, Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser()), null, "https://site.test/feed")

    private fun step(itemsJson: String, asFormat: String = "html"): PipelineStep =
        PipelineStep(
            sources = listOf(PipelineSource("https://site.test/x")),
            mode = "single",
            asFormat = asFormat,
            items = JSONObject(itemsJson)
        )

    private val baseVars = mapOf("base" to "https://nepu.test", "query" to "q", "url" to "")

    // ------------------------------------------------------------- search/html

    @Test
    fun searchHtml_linkFieldsBlacklistStripQueryDedupeAndKeywordCategory() {
        val html = """
            <html><body>
              <article><h2><a href="https://site.test/drama/one?utm=9">Love Drama Season 2</a></h2>
                <img src="/p1.jpg"></article>
              <article><h2><a href="/category/kdrama">Browse KDrama</a></h2></article>
              <article><h2><a href="https://site.test/drama/one">Love Drama Season 2</a></h2></article>
            </body></html>
        """.trimIndent()
        val items = """
            {
              "cardSelector": "article",
              "title": "link:h2 a",
              "url": "link:h2 a",
              "urlStripQuery": true,
              "urlBlacklist": ["/category/"],
              "poster": "attr:img:src",
              "category": {"keywords": [{"contains": ["drama", "season"], "value": "Asian Drama"}], "default": "Movies"}
            }
        """.trimIndent()

        val cards = RulesPipeline.extractSearchCards("nkiri", step(items), htmlOutcome(html), baseVars)

        // /category/ blacklisted; the two /drama/one cards dedupe after query strip
        assertEquals(1, cards.size)
        assertEquals("Love Drama Season 2", cards[0].title)
        assertEquals("https://site.test/drama/one", cards[0].url)
        assertEquals("https://site.test/p1.jpg", cards[0].posterUrl)
        assertEquals("Asian Drama", cards[0].category)
        assertEquals("nkiri", cards[0].site)
    }

    @Test
    fun searchHtml_classPrefixCategoryAndRequiredLink() {
        val html = """
            <html><body>
              <article class="entry category-chinese-drama">
                <h3 class="search-entry-title"><a href="https://site.test/d/x">My Drama</a></h3>
                <div class="thumbnail"><img src="/t.jpg"></div>
              </article>
              <article class="entry"><h3 class="search-entry-title">No link card</h3></article>
            </body></html>
        """.trimIndent()
        val items = """
            {
              "cardSelector": "article.entry",
              "title": "link:.search-entry-title a",
              "url": "link:.search-entry-title a",
              "poster": "attr:.thumbnail img:src",
              "category": {"classPrefix": "category-", "default": "Asian Drama"}
            }
        """.trimIndent()

        val cards = RulesPipeline.extractSearchCards("dramakey", step(items), htmlOutcome(html), baseVars)

        assertEquals(1, cards.size)
        assertEquals("My Drama", cards[0].title)
        assertEquals("Chinese drama", cards[0].category)
        assertEquals("https://site.test/t.jpg", cards[0].posterUrl)
    }

    @Test
    fun searchHtml_rssItems() {
        val rss = """
            <?xml version="1.0"?>
            <rss><channel>
              <item><title>Show One</title><link>https://x.test/1</link></item>
              <item><title>No Link</title></item>
            </channel></rss>
        """.trimIndent()
        val items = """{"title": "selector:title", "url": "selector:link", "category": "literal:Drama"}"""

        val cards = RulesPipeline.extractSearchCards(
            "rocks", step(items, asFormat = "rss"), rssOutcome(rss), baseVars
        )

        assertEquals(1, cards.size)
        assertEquals("Show One", cards[0].title)
        assertEquals("https://x.test/1", cards[0].url)
        assertEquals("Drama", cards[0].category)
    }

    // ------------------------------------------------------------- search/json

    @Test
    fun searchJson_wildcardItemPathAndCategorySuffix() {
        // anitaku shape: dynamic top-level keys -> arrays -> blocks -> "all"[]
        val json = """
            {
              "grp_1": [{"all": [
                {"post_link": "https://x.test/anime/a", "post_title": "Show A", "post_image": "i.jpg", "post_sub": "Dub"},
                {"post_link": "", "post_title": "No Link", "post_image": "", "post_sub": ""}
              ]}],
              "grp_2": [{"all": [
                {"post_link": "https://x.test/anime/b", "post_title": "Show B", "post_image": "", "post_sub": ""}
              ]}]
            }
        """.trimIndent()
        val items = """
            {
              "itemPath": "*.all[]",
              "title": "field:post_title",
              "url": "field:post_link",
              "poster": "field:post_image",
              "category": {"base": "Anime", "suffixField": "post_sub"}
            }
        """.trimIndent()

        val cards = RulesPipeline.extractSearchCards("anitaku", step(items, asFormat = "json"), jsonOutcome(json), baseVars)

        assertEquals(2, cards.size)
        assertEquals("Show A", cards[0].title)
        assertEquals("Anime (Dub)", cards[0].category)
        assertEquals("i.jpg", cards[0].posterUrl)
        assertEquals("Show B", cards[1].title)
        assertEquals("Anime", cards[1].category)
    }

    @Test
    fun searchJson_templatesDefaultsVarsAndCategoryMap() {
        // nepu shape: TMDB-style results with URL synthesis
        val json = """
            {
              "results": [
                {"id": "123", "media_type": "tv", "name": "Show X", "first_air_date": "2024-05-01", "poster_path": "/abc.jpg"},
                {"id": "456", "title": "Movie Y", "release_date": "2023-01-02", "poster_path": "null"}
              ]
            }
        """.trimIndent()
        val items = """
            {
              "itemPath": "results[]",
              "title": ["template:{title|name} ({year})", "field:title|name"],
              "url": "template:{base}/watch/{media_type}/{id}",
              "poster": "template:https://image.tmdb.org/t/p/w342{poster_path}",
              "year": "var:year",
              "category": {"field": "media_type", "map": {"tv": "TV Show"}, "default": "Movie"},
              "vars": {"year": "field:release_date|first_air_date:before(-)"},
              "defaults": {"media_type": "movie"}
            }
        """.trimIndent()

        val cards = RulesPipeline.extractSearchCards("nepu", step(items, asFormat = "json"), jsonOutcome(json), baseVars)

        assertEquals(2, cards.size)
        assertEquals("Show X (2024)", cards[0].title)
        assertEquals("https://nepu.test/watch/tv/123", cards[0].url)
        assertEquals("https://image.tmdb.org/t/p/w342/abc.jpg", cards[0].posterUrl)
        assertEquals("TV Show", cards[0].category)
        assertEquals("2024", cards[0].year)

        assertEquals("Movie Y (2023)", cards[1].title)
        assertEquals("https://nepu.test/watch/movie/456", cards[1].url)
        assertEquals("", cards[1].posterUrl) // poster_path "null" -> template fails -> blank
        assertEquals("Movie", cards[1].category)
    }

    @Test
    fun searchJson_limitApplied() {
        val json = """{"results": [{"id": "1", "title": "A"}, {"id": "2", "title": "B"}, {"id": "3", "title": "C"}]}"""
        val items = """{"itemPath": "results[]", "title": "field:title", "url": "template:https://x/{id}", "limit": 2}"""
        val cards = RulesPipeline.extractSearchCards("s", step(items, asFormat = "json"), jsonOutcome(json), baseVars)
        assertEquals(2, cards.size)
    }

    // ----------------------------------------------------------- episodes/html

    @Test
    fun episodesHtml_allowlistBlacklistSiblingLabelsAndDedupe() {
        // nkiri shape: heading above a wrapped locker link, noise links around
        val html = """
            <html><body>
              <h3>Episode 3 - The Return</h3>
              <div><a href="https://downloadwella.com/f/3">Download Episode</a></div>
              <a href="https://t.me/somechannel">Telegram channel</a>
              <a href="https://downloadwella.com/f/1">Episode 1 File</a>
              <a href="https://downloadwella.com/f/1">Episode 1 File</a>
              <a href="https://otherhost.com/f/9">Not a locker</a>
            </body></html>
        """.trimIndent()
        val items = """
            {
              "anchorSelector": "a[href]",
              "urlAllowlist": ["downloadwella.com"],
              "urlBlacklist": ["telegram"],
              "labelChain": ["sibling:episode", "text", "counter"]
            }
        """.trimIndent()

        val result = RulesPipeline.extractEpisodes(
            "nkiri", step(items), htmlOutcome(html), baseVars, "https://nkiri.test/show/1"
        )

        assertEquals(2, result.episodes.size)
        // sibling heading of the anchor's parent wins over the generic anchor text
        assertEquals("Episode 3 - The Return", result.episodes[0].title)
        assertEquals("https://downloadwella.com/f/3", result.episodes[0].url)
        assertEquals(1, result.episodes[0].episodeNum)
        // anchor text used when valid; duplicate url deduped
        assertEquals("Episode 1 File", result.episodes[1].title)
        assertEquals(2, result.episodes[1].episodeNum)
        // no meta spec -> null meta (provider keeps its own)
        assertNull(result.metaTitle)
    }

    @Test
    fun episodesHtml_derivedNumberingSortAndTextConfig() {
        // anitaku shape: number from text or from url, sorted ascending
        val html = """
            <html><body>
              <a href="https://site.test/show-episode-2">2</a>
              <a href="https://site.test/show-episode-1"><img src="/ep.jpg"></a>
              <a href="https://site.test/anime/">Index</a>
            </body></html>
        """.trimIndent()
        val items = """
            {
              "anchorSelector": "a[href*='episode']",
              "labelChain": [{"text": {"maxLength": 0, "skip": ["#"]}}, "counter"],
              "numbering": {"chain": [{"regexText": "\\d+"}, {"regexUrl": "(?i)episode-(\\d+)"}], "sortByNumber": true}
            }
        """.trimIndent()

        val result = RulesPipeline.extractEpisodes(
            "anitaku", step(items), htmlOutcome(html), baseVars, "https://site.test/anime/show"
        )

        assertEquals(2, result.episodes.size)
        // image-only anchor: number from url regex, label falls back to counter
        assertEquals("Episode 1", result.episodes[0].title)
        assertEquals(1, result.episodes[0].episodeNum)
        assertEquals("https://site.test/show-episode-1", result.episodes[0].url)
        // text anchor keeps its text as label, number derived from text
        assertEquals("2", result.episodes[1].title)
        assertEquals(2, result.episodes[1].episodeNum)
    }

    @Test
    fun episodesHtml_fallbackSingleOnPlayerIframe() {
        val html = """<html><body><iframe src="//player.test/e/1"></iframe></body></html>"""
        val items = """
            {
              "anchorSelector": ".eplister a",
              "labelChain": ["counter"],
              "fallbackSingle": {"when": "hasElement:iframe[src]", "title": "Movie / Episode 1"}
            }
        """.trimIndent()

        val result = RulesPipeline.extractEpisodes(
            "anitaku", step(items), htmlOutcome(html), baseVars, "https://site.test/movie/one"
        )

        assertEquals(1, result.episodes.size)
        assertEquals("Movie / Episode 1", result.episodes[0].title)
        assertEquals("https://site.test/movie/one", result.episodes[0].url)
        assertEquals(1, result.episodes[0].episodeNum)
    }

    @Test
    fun episodesHtml_hrefRegexCapturesSortZeroPadAndFallbackAlways() {
        // nepu shape: season/episode captured from the href, sorted, zero-padded
        val html = """
            <html><body>
              <a href="/watch/tv/10/2/3">x</a>
              <a href="/watch/tv/10/1/1">y</a>
              <a href="/watch/tv/10/1/1?ref=home">z</a>
              <a href="/watch/tv/10/1/2">w</a>
              <a href="/watch/movie/10">movie page</a>
            </body></html>
        """.trimIndent()
        val items = """
            {
              "hrefRegex": "watch/tv/\\d+/(\\d+)/(\\d+)(?:[?&#].*)?$",
              "sortBy": "captures",
              "labelChain": [{"label": "S{1:%02d} E{2:%02d}"}]
            }
        """.trimIndent()

        val result = RulesPipeline.extractEpisodes(
            "nepu", step(items), htmlOutcome(html, "https://nepu.test/watch/tv/10"), baseVars,
            "https://nepu.test/watch/tv/10"
        )

        assertEquals(3, result.episodes.size) // ?ref=home duplicate deduped by captures
        assertEquals("S01 E01", result.episodes[0].title)
        assertEquals("https://nepu.test/watch/tv/10/1/1", result.episodes[0].url)
        assertEquals(1, result.episodes[0].episodeNum)
        assertEquals("S01 E02", result.episodes[1].title)
        assertEquals("S02 E03", result.episodes[2].title)
        assertEquals(3, result.episodes[2].episodeNum)
    }

    @Test
    fun episodesHtml_fallbackAlwaysForMoviePage() {
        val html = """<html><body><h1>Movie</h1></body></html>"""
        val items = """
            {
              "hrefRegex": "watch/tv/\\d+/(\\d+)/(\\d+)$",
              "fallbackSingle": {"when": "always", "title": "Stream / Movie"}
            }
        """.trimIndent()

        val result = RulesPipeline.extractEpisodes(
            "nepu", step(items), htmlOutcome(html), baseVars, "https://nepu.test/watch/movie/10"
        )

        assertEquals(1, result.episodes.size)
        assertEquals("Stream / Movie", result.episodes[0].title)
        assertEquals("https://nepu.test/watch/movie/10", result.episodes[0].url)
    }

    @Test
    fun episodesHtml_metaExtractionWithTitleCleanup() {
        // dramakey shape: DOWNLOAD prefix + "| suffix" boilerplate on titles
        val html = """
            <html><body>
              <h1 class="entry-title">DOWNLOAD My Drama | Chinese Drama</h1>
              <div class="entry-content"><img src="/p.jpg"><p>Synopsis text.</p></div>
              <a href="https://downloadwella.com/dl/Show.S01E02.720p.mkv">Download Episode</a>
            </body></html>
        """.trimIndent()
        val items = """
            {
              "anchorSelector": "a[href]",
              "urlAllowlist": ["downloadwella.com"],
              "labelChain": [{"regexUrl": "(?i)S(\\d+)E(\\d+)", "label": "S{1} E{2}"}, "counter"],
              "meta": {
                "title": "selector:h1.entry-title, h1",
                "titleCleanup": [{"removePrefix": "DOWNLOAD"}, "trim", {"substringBefore": " |"}],
                "poster": "attr:.entry-content img:src",
                "synopsis": "selector:.entry-content p"
              }
            }
        """.trimIndent()

        val result = RulesPipeline.extractEpisodes(
            "dramakey", step(items), htmlOutcome(html), baseVars, "https://dramakey.test/drama/x"
        )

        assertEquals("My Drama", result.metaTitle)
        assertEquals("https://site.test/p.jpg", result.metaPoster)
        assertEquals("Synopsis text.", result.metaSynopsis)
        assertEquals(1, result.episodes.size)
        // captures keep the URL's literal digits (S01E02 -> "S01 E02"),
        // matching the compiled dramakey behavior
        assertEquals("S01 E02", result.episodes[0].title)
    }

    @Test
    fun episodesHtml_parentSectionLinkSkipped() {
        val html = """
            <html><body>
              <a href="https://site.test/anime/">All anime</a>
              <a href="https://site.test/anime/show-episode-1">Ep 1</a>
            </body></html>
        """.trimIndent()
        val items = """{"anchorSelector": "a[href]", "labelChain": ["text", "counter"]}"""

        val result = RulesPipeline.extractEpisodes(
            "anitaku", step(items), htmlOutcome(html), baseVars, "https://site.test/anime/show/"
        )

        assertEquals(1, result.episodes.size)
        assertEquals("Ep 1", result.episodes[0].title)
    }

    @Test
    fun extraction_malformedSpecsYieldEmptyNotThrow() {
        val html = """<html><body><a href="/x">x</a></body></html>"""
        // no cardSelector
        assertEquals(0, RulesPipeline.extractSearchCards("s", step("""{"title": "self"}"""), htmlOutcome(html), baseVars).size)
        // no anchorSelector / hrefRegex
        assertEquals(0, RulesPipeline.extractEpisodes("s", step("""{}"""), htmlOutcome(html), baseVars, "https://s.test/p").episodes.size)
        // invalid regex -> no anchors, not a crash
        val bad = step("""{"hrefRegex": "([", "fallbackSingle": {"when": "always", "title": "T"}}""")
        val r = RulesPipeline.extractEpisodes("s", bad, htmlOutcome(html), baseVars, "https://s.test/p")
        assertEquals(1, r.episodes.size) // fallback still applies
    }

    // ------------------------------------------------------------ primitives

    @Test
    fun queryTransform_stripsSeasonWordsButKeepsShortResults() {
        val spec = JSONObject("""{"stripRegex": "(?i) (season|series|part|s\\d+)\\s*\\d*", "minLength": 2}""")
        assertEquals("naruto", RulesPipeline.applyQueryTransform("naruto season 2", spec))
        assertEquals("attack on titan", RulesPipeline.applyQueryTransform("attack on titan series 3", spec))
        // cleaned result too short -> keep the original query
        assertEquals("s 2", RulesPipeline.applyQueryTransform("s 2", spec))
        // no spec -> untouched
        assertEquals("raw query", RulesPipeline.applyQueryTransform("raw query", null))
    }

    @Test
    fun walkJson_wildcardsExpansionAndNestedArrays() {
        val root = JSONObject(
            """{"k1": [{"all": [{"t": 1}, {"t": 2}]}], "k2": [{"all": [{"t": 3}]}], "plain": {"x": "y"}}"""
        )
        assertEquals(3, RulesPipeline.walkJson(root, "*.all[]").size)
        assertEquals(listOf(1, 2, 3), RulesPipeline.walkJson(root, "*.all[]").map { (it as JSONObject).optInt("t") })

        val nested = JSONObject("""{"data": {"movies": [{"id": "a"}, {"id": "b"}]}}""")
        assertEquals(2, RulesPipeline.walkJson(nested, "data.movies[]").size)

        val arr = JSONObject("""{"results": [{"id": 7}]}""")
        assertEquals(1, RulesPipeline.walkJson(arr, "results[]").size)

        assertEquals(0, RulesPipeline.walkJson(root, "missing.path[]").size)
        assertEquals(0, RulesPipeline.walkJson(null, "a.b").size)
    }

    @Test
    fun jsonField_fallbacksAndBeforeCut() {
        val item = JSONObject("""{"name": "Show", "first_air_date": "2024-05-01", "poster_path": "null"}""")
        assertEquals("Show", RulesPipeline.jsonField(item, "title|name"))
        assertEquals("2024", RulesPipeline.jsonField(item, "release_date|first_air_date:before(-)"))
        // "null" is a real string value here — jsonField returns it; template
        // rendering is what treats "null" as blank.
        assertEquals("null", RulesPipeline.jsonField(item, "poster_path"))
        assertNull(RulesPipeline.jsonField(item, "missing|also_missing"))
        // cut applies to whichever alternative hit, not just the last one
        val earlyHit = JSONObject("""{"release_date": "2023-01-02"}""")
        assertEquals("2023", RulesPipeline.jsonField(earlyHit, "release_date|first_air_date:before(-)"))
        // date without the cut char -> empty (year unknown, not garbage)
        val noDash = JSONObject("""{"release_date": "unknown"}""")
        assertNull(RulesPipeline.jsonField(noDash, "release_date:before(-)"))
    }

    @Test
    fun renderTemplate_substitutionAndBlankVarFailure() {
        val vars = mapOf("base" to "https://x.test", "id" to "42")
        assertEquals("https://x.test/watch/42", RulesPipeline.renderTemplate("{base}/watch/{id}", vars) { vars[it] })
        // missing var fails the whole template (callers fall through to the
        // next alternative spec)
        assertNull(RulesPipeline.renderTemplate("{base}/{missing}", vars) { vars[it] })
        // literal "null" values are treated as blank
        assertNull(RulesPipeline.renderTemplate("p{v}", mapOf("v" to "null")) { m -> mapOf("v" to "null")[m] })
        // no placeholders -> unchanged
        assertEquals("plain", RulesPipeline.renderTemplate("plain", vars) { vars[it] })
    }

    @Test
    fun fillCaptureTemplate_plainAndZeroPadded() {
        assertEquals("S01 E02", RulesPipeline.fillCaptureTemplate("S{1:%02d} E{2:%02d}", listOf("1", "2")))
        assertEquals("Episode 5", RulesPipeline.fillCaptureTemplate("Episode {1}", listOf("5")))
        assertEquals("S12 E34", RulesPipeline.fillCaptureTemplate("S{1} E{2}", listOf("12", "34")))
        // missing capture -> empty substitution, template still renders
        assertEquals("Ep ", RulesPipeline.fillCaptureTemplate("Ep {3}", listOf("1")))
    }

    // ------------------------------------------------------- pipeline parsing

    @Test
    fun parseSitePipeline_acceptsValidAndRejectsUnknownOrOversized() {
        val valid = parseSitePipeline(
            JSONObject(
                """
                {
                  "schema": 1,
                  "search": {"steps": [{"sources": [{"url": "{base}/?s={query}"}], "as": "html",
                             "items": {"cardSelector": "article", "title": "self", "url": "self"}}]},
                  "episodes": {"steps": [{"sources": [{"url": "{url}", "method": "GET"}], "as": "html",
                               "items": {"anchorSelector": "a[href]"}}]}
                }
                """.trimIndent()
            )
        )
        assertNotNull(valid)
        assertNotNull(valid!!.search)
        assertNotNull(valid.episodes)

        // future schema version -> ignored (null), app keeps compiled fallback
        assertNull(parseSitePipeline(JSONObject("""{"schema": 2, "search": {"steps": [{"sources": [{"url": "http://x"}]}]}}""")))
        // empty / oversized steps
        assertNull(parseSitePipeline(JSONObject("""{"schema": 1, "search": {"steps": []}}""")))
        val nineSteps = (1..9).joinToString(",") { """{"sources": [{"url": "http://x"}]}""" }
        assertNull(parseSitePipeline(JSONObject("""{"schema": 1, "search": {"steps": [$nineSteps]}}""")))
        // too many sources in one step
        val fiveSources = (1..5).joinToString(",") { """{"url": "http://x$it"}""" }
        assertNull(parseSitePipeline(JSONObject("""{"schema": 1, "search": {"steps": [{"sources": [$fiveSources]}]}}""")))
        // bad method / mode / as enums (all step-level keys)
        assertNull(parseSitePipeline(JSONObject("""{"schema": 1, "search": {"steps": [{"sources": [{"url": "http://x", "method": "DELETE"}]}]}}""")))
        assertNull(parseSitePipeline(JSONObject("""{"schema": 1, "search": {"steps": [{"sources": [{"url": "http://x"}], "mode": "yolo"}]}}""")))
        assertNull(parseSitePipeline(JSONObject("""{"schema": 1, "search": {"steps": [{"sources": [{"url": "http://x"}], "as": "yaml"}]}}""")))
        // neither search nor episodes -> nothing usable
        assertNull(parseSitePipeline(JSONObject("""{"schema": 1}""")))
    }

    @Test
    fun searchHtml_selfFieldOnAnchorCards() {
        val html = """
            <html><body>
              <h2 class="entry-title"><a href="https://site.test/p/1">Standalone Title</a></h2>
            </body></html>
        """.trimIndent()
        val items = """{"cardSelector": "h2.entry-title a", "title": "self", "url": "self"}"""
        val cards = RulesPipeline.extractSearchCards("s", step(items), htmlOutcome(html), baseVars)
        assertEquals(1, cards.size)
        assertEquals("Standalone Title", cards[0].title)
        assertEquals("https://site.test/p/1", cards[0].url)
    }

    @Test
    fun episodesHtml_genericTextSkipsDownloadLabels() {
        val html = """
            <html><body>
              <a href="https://locker.test/a">Download Episode</a>
              <a href="https://locker.test/b">Real Label Here</a>
            </body></html>
        """.trimIndent()
        val items = """{"anchorSelector": "a[href]", "labelChain": ["text", "counter"]}"""
        val result = RulesPipeline.extractEpisodes("s", step(items), htmlOutcome(html), baseVars, "https://s.test/show")
        assertEquals(2, result.episodes.size)
        assertEquals("Episode 1", result.episodes[0].title) // generic label rejected -> counter
        assertEquals("Real Label Here", result.episodes[1].title)
    }
}
