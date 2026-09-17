package com.anonrode.downloader.providers

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaijaVaultMovieLinksTest {
    private val pageUrl = "https://www.naijavault.com/fixture-movie/"

    // Exercise the production movie-button parser without fetching a page or
    // initializing Android/network state. Movie-page detection is separate.
    private fun movieItems(buttons: String) = NaijaVaultProvider.movieDownloadItems(
        Jsoup.parse(
            """
                <html><body>
                    <article class="post category-download-movies-nollywood">
                        <h1 class="entry-title">Fixture Movie</h1>
                        <div class="entry-content">$buttons</div>
                    </article>
                </body></html>
            """.trimIndent(),
            pageUrl
        ),
        pageUrl
    )

    @Test
    fun `homepage download buttons are not movie downloads`() {
        for (href in listOf("/", "https://www.naijavault.com", "https://www.naijavault.com/", "https://naijavault.com/")) {
            val items = movieItems("""<a id="download-button" href="$href"><b>DOWNLOAD MOVIE</b></a>""")
            assertTrue("Homepage must not become a download: $href", items.isEmpty())
        }
    }

    @Test
    fun `real relative dl gateway survives a preceding homepage button`() {
        val items = movieItems(
            """
                <a id="download-button" href="/">DOWNLOAD MOVIE</a>
                <a id="download-button" href="/dl-fixture-movie/">WATCH &amp; DOWNLOAD MOVIE HERE</a>
            """.trimIndent()
        )

        assertEquals(listOf("https://www.naijavault.com/dl-fixture-movie/"), items.map { it.url })
        assertEquals("WATCH & DOWNLOAD MOVIE HERE", items.single().title)
        assertEquals("naijavault", items.single().site)
    }

    @Test
    fun `cross host locker button is a movie download`() {
        val lockerUrl = "https://www.lulacloud.com/d/fixture-movie"
        val items = movieItems("""<a id="download-button" href="$lockerUrl"><b>DOWNLOAD MOVIE</b></a>""")

        assertEquals(listOf(lockerUrl), items.map { it.url })
        assertEquals("DOWNLOAD MOVIE", items.single().title)
    }

    @Test
    fun `all valid mirrors retain their order and server labels`() {
        val items = movieItems(
            """
                <a id="download-button" href="/dl-fixture-movie/">DOWNLOAD MOVIE SERVER 1</a>
                <a id="download-button" href="/">DOWNLOAD MOVIE</a>
                <a id="download-button" href="https://www.lulacloud.com/d/fixture-movie">DOWNLOAD MOVIE SERVER 2</a>
                <a id="download-button" href="https://vikingfile.com/f/fixture-movie">DOWNLOAD MOVIE SERVER 3</a>
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "https://www.naijavault.com/dl-fixture-movie/",
                "https://www.lulacloud.com/d/fixture-movie",
                "https://vikingfile.com/f/fixture-movie"
            ),
            items.map { it.url }
        )
        assertEquals(listOf("Server 1", "Server 2", "Server 3"), items.map { it.title })
    }

    @Test
    fun `fragment self javascript and empty buttons are rejected`() {
        for (href in listOf("#download", pageUrl, "$pageUrl#download", "javascript:void(0)", "", "   ")) {
            val items = movieItems("""<a id="download-button" href="$href">DOWNLOAD MOVIE</a>""")
            assertTrue("Non-download button must be rejected: '$href'", items.isEmpty())
        }
        assertTrue(movieItems("""<a id="download-button">DOWNLOAD MOVIE</a>""").isEmpty())
    }
}
