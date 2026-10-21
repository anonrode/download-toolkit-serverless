package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.ShowCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Category page-2+ windows (2026-10-21 trace row 33). The anitaku
 * (ANIME_PROVIDER) and nepu (API_JSON) branches hard-returned emptyList()
 * for page > 1, so the Anime/movie genre rows NEVER grew past the first 12
 * cards no matter how many the sources answered. Both sources answer the
 * WHOLE match set of one query in a single call; [CategoryFeed.pageWindow]
 * slices that stable list into disjoint pages — page 1 byte-identical to
 * the old `.take(PER_ROW_LIMIT)`.
 */
class CategoryFeedPageWindowTest {

    private fun card(i: Int) = ShowCard(title = "Show $i", url = "https://s/ep$i", site = "anitaku")

    private fun deck(n: Int) = (0 until n).map { card(it) }

    @Test
    fun pageOneIsExactlyTheHistoricalTake() {
        assertEquals(deck(12), CategoryFeed.pageWindow(deck(30), page = 1))
        assertEquals(deck(5), CategoryFeed.pageWindow(deck(5), page = 1))
        assertEquals(deck(12), CategoryFeed.pageWindow(deck(12), page = 1))
    }

    @Test
    fun pageWindowEqualsUpstreamFetchLimitShape() {
        // nepu fetches window+perPage rows upstream, so window N sees a
        // monotone superset bounded at start+perPage — drop(start) on that
        // list IS what pageWindow must return for the slice to follow.
        val fetchedForPage2 = deck(24) // limit = 12 + 12
        assertEquals(deck(24).drop(12), CategoryFeed.pageWindow(fetchedForPage2, page = 2))
        val fetchedForPage3 = deck(36) // limit = 24 + 12
        assertEquals(deck(36).drop(24), CategoryFeed.pageWindow(fetchedForPage3, page = 3))
    }

    @Test
    fun windowsAreDisjointAndOrderPreserved() {
        val full = deck(28)
        val p1 = CategoryFeed.pageWindow(full, 1)
        val p2 = CategoryFeed.pageWindow(full, 2)
        val p3 = CategoryFeed.pageWindow(full, 3)
        assertEquals(12, p1.size)
        assertEquals(12, p2.size)
        assertEquals(4, p3.size)
        val p1Urls = p1.map { it.url }.toSet()
        val p2Urls = p2.map { it.url }.toSet()
        val p3Urls = p3.map { it.url }.toSet()
        assertTrue((p1Urls intersect p2Urls).isEmpty())
        assertTrue((p2Urls intersect p3Urls).isEmpty())
        assertEquals(full, p1 + p2 + p3)
    }

    @Test
    fun exhaustedWindowIsEmptyEndOfFeed() {
        assertTrue(CategoryFeed.pageWindow(deck(12), page = 2).isEmpty())
        assertTrue(CategoryFeed.pageWindow(emptyList(), page = 2).isEmpty())
        assertTrue(CategoryFeed.pageWindow(deck(13), page = 3).isEmpty())
    }

    @Test
    fun absurdPageOverflowReturnsEmptyNotCrash() {
        // (page-1)*perPage overflows Int on absurd values — the guard turns
        // the wrap into an empty window, never an IndexOutOfBounds.
        assertTrue(CategoryFeed.pageWindow(deck(30), page = Int.MAX_VALUE).isEmpty())
    }
}
