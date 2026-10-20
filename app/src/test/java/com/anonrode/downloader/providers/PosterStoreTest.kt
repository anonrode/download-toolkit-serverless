package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.ShowCard
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * JVM tests for the persistent last-good poster store. They pin the three
 * contracts a failed refresh must never break:
 *  - blank never overwrites good (tiles AND cards),
 *  - retry backoff is recorded per label without touching other labels,
 *  - a corrupt file degrades to empty instead of bricking the home screen.
 */
class PosterStoreTest {

    private lateinit var dir: File
    private lateinit var file: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("poster-store-test").toFile()
        file = File(dir, "poster_store.json")
        PosterStore.resetForTest()
        PosterStore.init(file)
    }

    @After
    fun tearDown() {
        PosterStore.resetForTest()
        dir.deleteRecursively()
    }

    @Test
    fun blankTileRefreshNeverErasesGoodArt() {
        val a = "https://example.com/horror-a.jpg"
        val b = "https://example.com/horror-b.jpg"
        assertEquals(listOf(a, b), PosterStore.saveTileSuccess("Horror", listOf(a, b)))

        // All-blank result is a no-op, not a wipe.
        assertEquals(listOf(a, b), PosterStore.saveTileSuccess("Horror", listOf("", "")))

        // A partial refresh replaces only the slot it actually filled.
        val c = "https://example.com/horror-c.jpg"
        assertEquals(listOf(c, b), PosterStore.saveTileSuccess("Horror", listOf(c, "")))
    }

    @Test
    fun tileLabelsAreIsolated() {
        val a = "https://example.com/action.jpg"
        val h = "https://example.com/horror.jpg"
        PosterStore.saveTileSuccess("Action", listOf(a))
        PosterStore.saveTileSuccess("Horror", listOf(h))
        PosterStore.recordTileFailure("Horror", failures = 3, nextRetryAtMs = 99L)

        assertEquals(listOf(a), PosterStore.tileUrls("Action"))
        assertEquals(listOf(h), PosterStore.tileUrls("Horror"))
        assertEquals(0, PosterStore.tileFailures("Action"))
        assertEquals(3, PosterStore.tileFailures("Horror"))
        assertEquals(99L, PosterStore.nextTileRetryAt("Horror"))
    }

    @Test
    fun corruptFileDegradesToEmpty() {
        file.writeText("{not-json")
        PosterStore.resetForTest()
        PosterStore.init(file)
        assertTrue(PosterStore.snapshot.tiles.isEmpty())
        assertTrue(PosterStore.snapshot.cards.isEmpty())
        assertTrue(PosterStore.tileUrls("Horror").isEmpty())
    }

    @Test
    fun tileSuccessPersistsAcrossReinit() = runBlocking {
        val a = "https://example.com/anime-a.jpg"
        PosterStore.saveTileSuccess("Anime", listOf(a))
        PosterStore.flush()

        PosterStore.resetForTest()
        PosterStore.init(file)
        assertEquals(listOf(a), PosterStore.tileUrls("Anime"))
    }

    @Test
    fun blankIncomingCardUsesLastLoadedPoster() {
        val site = "nkiri"
        val url = "https://nkiri.example/show/1"
        val good = "https://cdn.example/good.jpg"
        PosterStore.markLoaded(site, url, good)

        val blank = ShowCard(title = "Show", url = url, posterUrl = "", site = site)
        assertEquals(good, PosterStore.enrich(listOf(blank)).single().posterUrl)
        assertEquals(good, PosterStore.persistable(listOf(blank)).single().posterUrl)
    }

    @Test
    fun unloadedIncomingUrlNeverReplacesLoadedPosterOnDisk() {
        val site = "nkiri"
        val url = "https://nkiri.example/show/2"
        val good = "https://cdn.example/good-2.jpg"
        val deadButNonBlank = "https://cdn.example/never-loaded.jpg"
        PosterStore.markLoaded(site, url, good)

        val refreshed = ShowCard(
            title = "Show",
            url = url,
            posterUrl = deadButNonBlank,
            site = site
        )
        // Visible state keeps the new candidate...
        assertEquals(deadButNonBlank, PosterStore.enrich(listOf(refreshed)).single().posterUrl)
        // ...but the on-disk list keeps the known-good URL until the new one
        // has actually decoded.
        assertEquals(good, PosterStore.persistable(listOf(refreshed)).single().posterUrl)

        PosterStore.markLoaded(site, url, deadButNonBlank)
        assertEquals(
            deadButNonBlank,
            PosterStore.persistable(listOf(refreshed)).single().posterUrl
        )
    }

    @Test
    fun offlineEnrichPromotesLastLoadedToPrimary() {
        val site = "nepu"
        val url = "https://nepu.example/watch/1"
        val good = "https://image.tmdb.org/t/p/w500/abc.jpg"
        PosterStore.markLoaded(site, url, good)

        val incoming = ShowCard(
            title = "Show",
            url = url,
            posterUrl = "https://image.tmdb.org/t/p/original/def.jpg",
            site = site
        )
        val offline = PosterStore.enrich(listOf(incoming), offline = true).single()
        assertEquals("https://image.tmdb.org/t/p/w342/abc.jpg", offline.posterUrl)
    }

    @Test
    fun tmdbUrlsCollapseToDisplaySizeAndOthersAreUntouched() {
        assertEquals(
            "https://image.tmdb.org/t/p/w342/abc.jpg",
            PosterStore.normalizePosterUrl("https://image.tmdb.org/t/p/original/abc.jpg")
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w342/abc.jpg",
            PosterStore.normalizePosterUrl("https://image.tmdb.org/t/p/w500/abc.jpg#frag")
        )
        assertEquals(
            "https://cdn.example/site/poster.jpg?token=1",
            PosterStore.normalizePosterUrl(" https://cdn.example/site/poster.jpg?token=1 ")
        )
    }

    @Test
    fun cardStoreIsBounded() {
        repeat(2100) { i ->
            PosterStore.markLoaded(
                "site",
                "https://site.example/show/$i",
                "https://cdn.example/$i.jpg"
            )
        }
        assertTrue(PosterStore.snapshot.cards.size <= 2000)
    }
}
