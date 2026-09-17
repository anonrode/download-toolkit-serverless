package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.EpisodeItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MovieSizeMetadataTest {
    private val show = "https://www.naijavault.com/movie/"
    private fun item(path: String) = EpisodeItem("Download", "https://www.naijavault.com/$path", 1, "naijavault")

    @Test fun parsesVisibleSizeAndIgnoresScripts() {
        assertEquals("243.23 MB", MovieSizeMetadata.parse("<script>var advert='2 GB'</script><p>File size: <b>243.23 MB</b></p>"))
        assertEquals("1.5 GB", MovieSizeMetadata.parse("<div>Size: 1.5&nbsp;GB</div>"))
    }

    @Test fun refusesAbsentZeroNegativeAndAmbiguousSizes() {
        for (html in listOf("<p>No size</p>", "0 MB", "-12 MB", "720p: 200 MB; 1080p: 1 GB")) {
            assertEquals(html, "", MovieSizeMetadata.parse(html))
        }
        assertEquals("243 MB", MovieSizeMetadata.parse("243 MB and 243 MB"))
    }

    @Test fun onlySameOriginIntermediariesAreFetched() {
        assertTrue(MovieSizeMetadata.isMetadataPage("https://www.naijavault.com/dl-abc/", show))
        assertTrue(MovieSizeMetadata.isMetadataPage("https://www.naijavault.com/temp/abc/", show))
        for (url in listOf("https://vikingfile.com/f/abc", "https://www.naijavault.com/movie.mp4", "http://www.naijavault.com/dl-abc", "https://www.naijavault.com:444/dl-abc", "https://user@www.naijavault.com/dl-abc")) {
            assertFalse(url, MovieSizeMetadata.isMetadataPage(url, show))
        }
    }

    @Test fun enrichmentPreservesDownloadIdentityAndMirrors() = runBlocking {
        val original = item("dl-abc/").copy(mirrorUrls = listOf("https://vikingfile.com/f/other"))
        val result = MovieSizeMetadata.enrich(listOf(original), show) { url, referer, budget ->
            assertEquals(original.url, url)
            assertEquals(show, referer)
            assertTrue(budget in 1..6000)
            "<p>File size: 243.23 MB</p>"
        }.single()
        assertEquals(original.copy(sizeText = "243.23 MB"), result)
    }

    @Test fun optionalFailureNeverRemovesEpisodesAndFetchesAtMostTwo() = runBlocking {
        val inputs = (1..4).map { item("dl-$it/") }
        var calls = 0
        val result = MovieSizeMetadata.enrich(inputs, show) { _, _, _ ->
            calls++
            throw java.io.IOException("offline")
        }
        assertEquals(2, calls)
        assertEquals(inputs, result)
    }

    @Test fun knownSizesAndDirectLockersDoNotFetch() = runBlocking {
        val inputs = listOf(item("dl-one/").copy(sizeText = "12 MB"), item("file.mp4"), item("dl-two/").copy(url = "https://vikingfile.com/f/x"))
        assertEquals(inputs, MovieSizeMetadata.enrich(inputs, show) { _, _, _ -> error("Unexpected fetch") })
    }

    @Test fun cancellationIsNotMetadataFailure() = runBlocking {
        try {
            MovieSizeMetadata.enrich(listOf(item("dl-x/")), show) { _, _, _ -> throw CancellationException("cancel") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
