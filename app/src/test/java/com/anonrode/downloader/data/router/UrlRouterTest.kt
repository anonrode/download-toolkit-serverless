package com.anonrode.downloader.data.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlRouterTest {
    @Test
    fun nepuTvUrlsAreNotCategorizedAsMovies() {
        val parsed = UrlRouter.parse("https://nepu.gd/watch/tv/12345")
        assertTrue(parsed is ParsedUrl.DramaUrl)
        assertEquals("TV Show", (parsed as ParsedUrl.DramaUrl).showCard.category)
    }

    @Test
    fun nepuMovieUrlsRemainMovies() {
        val parsed = UrlRouter.parse("https://nepu.gd/watch/movie/12345")
        assertTrue(parsed is ParsedUrl.DramaUrl)
        assertEquals("Movie", (parsed as ParsedUrl.DramaUrl).showCard.category)
    }
}
