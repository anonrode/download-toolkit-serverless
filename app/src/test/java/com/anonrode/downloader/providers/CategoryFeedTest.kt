package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.ShowCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for CategoryFeed's pure mixing + site-priority policy. v3.1.6
 * deleted the per-site row assembly (assembleRows/CategoryRow): genre pages
 * are ONE mixed list now, so these pin mixCards, the probe-verified per-genre
 * candidate order (nepu leads the movie genres, asianc Romance) and the
 * live-probed nepu query terms.
 */
class CategoryFeedTest {

    private fun card(site: String, title: String) =
        ShowCard(title = title, url = "https://$site.example/post/${title.hashCode()}", site = site)

    // -- mixCards: the whole genre page is this function ----------------------

    @Test
    fun mixCards_interleavesRoundRobin_varietyFirst() {
        // Five sites answering 2 cards each must paint A1 B1 C1 D1 E1 A2… —
        // never twelve nepu cards before the first nkiri one.
        val pairs = listOf("nepu", "nkiri", "9jarocks", "naijaprey", "naijavault").map { site ->
            site to listOf(card(site, "$site one"), card(site, "$site two"))
        }
        val mixed = CategoryFeed.mixCards(pairs)
        assertEquals(
            listOf(
                "nepu one", "nkiri one", "9jarocks one", "naijaprey one", "naijavault one",
                "nepu two", "nkiri two", "9jarocks two", "naijaprey two", "naijavault two"
            ),
            mixed.map { it.title }
        )
    }

    @Test
    fun mixCards_skipsEmptySites_withoutLosingPriority() {
        val pairs = listOf(
            "nkiri" to emptyList(),
            "9jarocks" to listOf(card("9jarocks", "Scream 7")),
            "naijaprey" to emptyList(),
            "naijavault" to listOf(card("naijavault", "Project Sacrifice"))
        )
        assertEquals(listOf("Scream 7", "Project Sacrifice"), CategoryFeed.mixCards(pairs).map { it.title })
    }

    @Test
    fun mixCards_dedupeIsNormalizedAcrossSites_andKeepsFirstPriority() {
        // Sites mirror each other; "horror-in-the-high-desert-4!" IS
        // "Horror In The High Desert 4". The FIRST site keeps the slot.
        val pairs = listOf(
            "nkiri" to listOf(card("nkiri", "Horror In The High Desert 4")),
            "9jarocks" to listOf(
                card("9jarocks", "horror-in-the-high-desert-4!"),
                card("9jarocks", "Blade The Series")
            )
        )
        val mixed = CategoryFeed.mixCards(pairs)
        assertEquals(listOf("Horror In The High Desert 4", "Blade The Series"), mixed.map { it.title })
        assertEquals("nkiri", mixed.first().site)
    }

    @Test
    fun mixCards_dropsBlankAndSymbolOnlyTitles() {
        val pairs = listOf(
            "nkiri" to listOf(card("nkiri", "   "), card("nkiri", "???"), card("nkiri", "Real Movie"))
        )
        assertEquals(listOf("Real Movie"), CategoryFeed.mixCards(pairs).map { it.title })
    }

    @Test
    fun mixCards_capsAtGridCap() {
        val pairs = listOf("nepu", "nkiri").map { site ->
            site to (1..30).map { card(site, "$site title $it") }
        }
        assertEquals(CategoryFeed.GRID_CAP, CategoryFeed.mixCards(pairs).size)
    }

    @Test
    fun mixCards_lateSiteAnswerIsOrderPreservingSubset() {
        // Streaming contract: what a mid-flight publish shows must survive
        // into the final mix with the same relative order — a late site
        // interleaves itself in, never reorders or drops painted cards.
        val early = listOf(
            "nepu" to listOf(card("nepu", "n1"), card("nepu", "n2")),
            "nkiri" to emptyList<ShowCard>()
        )
        val final = listOf(
            "nepu" to early[0].second,
            "nkiri" to listOf(card("nkiri", "k1"), card("nkiri", "k2"))
        )
        val preview = CategoryFeed.mixCards(early).map { it.title }
        val finished = CategoryFeed.mixCards(final).map { it.title }
        assertEquals(listOf("n1", "n2"), preview)
        // preview is a subsequence of finished, in order:
        var i = 0
        for (t in finished) if (i < preview.size && t == preview[i]) i++
        assertEquals(preview.size, i)
    }

    // -- probe-verified genre data --------------------------------------------

    @Test
    fun categories_areSixProbeVerifiedGenres() {
        assertEquals(
            listOf("Action", "Comedy", "Horror", "Romance", "Sci-Fi", "Thriller"),
            CategoryFeed.CATEGORIES.map { it.label }
        )
        assertEquals(CategoryFeed.CATEGORIES.size, CategoryFeed.CATEGORIES.map { it.label }.toSet().size)
    }

    @Test
    fun candidateSites_movieGenresLeadWithNepu_romanceLeadsWithAsianc() {
        // 2026-09-15 user decision: nepu is the best movie source (HD, TMDB
        // CDN art), asianc the best C/K-drama source — Romance. The WP sites
        // keep their 09-14 order behind, and the API site of the OTHER
        // bucket never appears in a genre's candidates.
        val movie = listOf("nepu", "nkiri", "9jarocks", "naijaprey", "naijavault")
        for (label in listOf("Action", "Comedy", "Horror", "Sci-Fi", "Thriller")) {
            assertEquals(label, movie, CategoryFeed.CATEGORIES.first { it.label == label }.candidateSites())
        }
        assertEquals(
            listOf("asianc", "nkiri", "9jarocks", "naijaprey", "naijavault"),
            CategoryFeed.CATEGORIES.first { it.label == "Romance" }.candidateSites()
        )
    }

    @Test
    fun nepuQueryTerms_areTheLiveProbedTitleWords() {
        // nepu's search matches TITLES: "action"/"thriller" return ZERO,
        // "fight"/"revenge"/"love" return 16-20 (probed 2026-09-15). Baking
        // the real words here is what makes nepu actually fill the genres.
        fun term(label: String) = CategoryFeed.CATEGORIES.first { it.label == label }.termFor("nepu")
        assertEquals("fight", term("Action"))
        assertEquals("love", term("Romance"))
        assertEquals("revenge", term("Thriller"))
        // Untagged sites fall back to the lowercase label:
        assertEquals("comedy", term("Comedy"))
        assertEquals("horror", term("Horror"))
    }

    @Test
    fun aliases_areNormalizedAndCoverProbeVariants() {
        // genreConfirmed() normalizes the site's terms; aliases are compared
        // POST-normalization, so an alias containing punctuation/spaces can
        // never match — and "sciencefiction"/"scifi" both must be present.
        val byLabel = CategoryFeed.CATEGORIES.associate { it.label to it.aliases }
        assertEquals(setOf("scifi", "sciencefiction"), byLabel["Sci-Fi"])
        assertEquals(setOf("thriller", "suspense"), byLabel["Thriller"])
        assertEquals(setOf("action", "martialarts"), byLabel["Action"])
        assertEquals(setOf("romance", "romantic", "love"), byLabel["Romance"])
        byLabel.values.forEach { aliases ->
            assertTrue(aliases.joinToString(), aliases.all { a -> a.all { c -> c.isLetterOrDigit() } })
        }
    }

    @Test
    fun feedSources_coverSixSites_withProbeVerifiedKinds() {
        // The 2026-09-14 WP/RSS probe + the 2026-09-15 API discovery. Every
        // RSS template must carry the query placeholder.
        assertEquals(
            mapOf(
                "nkiri" to CategoryFeed.FeedKind.WP_REST,
                "9jarocks" to CategoryFeed.FeedKind.RSS,
                "naijaprey" to CategoryFeed.FeedKind.RSS,
                "naijavault" to CategoryFeed.FeedKind.WP_REST,
                "nepu" to CategoryFeed.FeedKind.API_JSON,
                "asianc" to CategoryFeed.FeedKind.API_JSON
            ),
            CategoryFeed.FEED_SOURCES.mapValues { it.value.first }
        )
        assertTrue(CategoryFeed.FEED_SOURCES.filter { it.value.first == CategoryFeed.FeedKind.RSS }.all { "%s" in it.value.second })
    }

    @Test
    fun siteFeeds_globalOrderIsTheFourWpSites() {
        assertEquals(listOf("nkiri", "9jarocks", "naijaprey", "naijavault"), CategoryFeed.SITE_FEEDS)
    }
}
