package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.ShowCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for CategoryFeed's pure row-assembly policy (the network fetch
 * itself mirrors TrendingFeed's already-probed feed machinery and the site
 * paths were live-verified 2026-09-14 for all six genres).
 */
class CategoryFeedTest {

    private fun card(site: String, title: String) =
        ShowCard(title = title, url = "https://$site.example/post/${title.hashCode()}", site = site)

    @Test
    fun assembleRows_skipsEmptySites() {
        val rows = CategoryFeed.assembleRows(
            listOf(
                "nkiri" to emptyList(),
                "9jarocks" to listOf(card("9jarocks", "Scream 7")),
                "naijaprey" to emptyList(),
                "naijavault" to listOf(card("naijavault", "Project Sacrifice"))
            )
        )
        assertEquals(listOf("9jarocks", "naijavault"), rows.map { it.site })
    }

    @Test
    fun assembleRows_capsAtThreeRows_inPriorityOrder() {
        val pairs = listOf("nkiri", "9jarocks", "naijaprey", "naijavault").map { site ->
            site to listOf(card(site, "Title A $site"))
        }
        val rows = CategoryFeed.assembleRows(pairs)
        assertEquals(3, rows.size)
        assertEquals(listOf("nkiri", "9jarocks", "naijaprey"), rows.map { it.site })
    }

    @Test
    fun assembleRows_crossSiteDedupeKeepsHighestPrioritySite() {
        // The sites mirror each other's posts; a reposted title must surface
        // once, at the first site that has it.
        val rows = CategoryFeed.assembleRows(
            listOf(
                "nkiri" to listOf(card("nkiri", "Project Sacrifice (2026)")),
                "9jarocks" to listOf(
                    card("9jarocks", "Project Sacrifice (2026)"),   // dup, punctuation differs below
                    card("9jarocks", "Blade The Series")
                )
            )
        )
        assertEquals(listOf("nkiri", "9jarocks"), rows.map { it.site })
        assertEquals(listOf("Project Sacrifice (2026)"), rows[0].items.map { it.title })
        assertEquals(listOf("Blade The Series"), rows[1].items.map { it.title })
    }

    @Test
    fun assembleRows_dedupeNormalizesPunctuationAndCase() {
        val rows = CategoryFeed.assembleRows(
            listOf(
                "nkiri" to listOf(card("nkiri", "Horror In The High Desert 4")),
                "9jarocks" to listOf(card("9jarocks", "horror-in-the-high-desert-4!"))
            )
        )
        assertEquals(1, rows.size)
        assertEquals("nkiri", rows[0].site)
    }

    @Test
    fun assembleRows_dropsBlankAndSymbolOnlyTitles() {
        val rows = CategoryFeed.assembleRows(
            listOf("nkiri" to listOf(card("nkiri", "   "), card("nkiri", "???"), card("nkiri", "Real Movie")))
        )
        assertEquals(listOf("Real Movie"), rows.single().items.map { it.title })
    }

    @Test
    fun assembleRows_allEmpty_isEmpty() {
        assertEquals(emptyList<CategoryFeed.CategoryRow>(), CategoryFeed.assembleRows(listOf("nkiri" to emptyList())))
    }

    @Test
    fun categories_areSixProbeVerifiedGenres() {
        assertEquals(
            listOf("Action", "Comedy", "Horror", "Romance", "Sci-Fi", "Thriller"),
            CategoryFeed.CATEGORIES.map { it.label }
        )
        assertEquals(CategoryFeed.CATEGORIES.size, CategoryFeed.CATEGORIES.map { it.label }.toSet().size)
    }

    @Test
    fun termFor_defaultsToLowercaseLabel_withPerSiteOverrideHook() {
        val comedy = CategoryFeed.Category("Comedy")
        assertEquals("comedy", comedy.termFor("nkiri"))
        val custom = CategoryFeed.Category("Weird", mapOf("nkiri" to "science fiction"))
        assertEquals("science fiction", custom.termFor("nkiri"))
        assertEquals("weird", custom.termFor("9jarocks"))
    }

    @Test
    fun sitePaths_coverAllFourFeedSites_inPriorityOrder() {
        // Guarded against silent edits: the four sites whose search feeds were
        // live-verified 2026-09-14, in the order that fills rows.
        assertTrue(CategoryFeed.SITE_PATHS.size >= 3)
        assertEquals(listOf("nkiri", "9jarocks", "naijaprey", "naijavault"), CategoryFeed.SITE_PATHS.map { it.first })
        // Every path must carry the query placeholder.
        assertTrue(CategoryFeed.SITE_PATHS.all { "%s" in it.second })
    }
}
