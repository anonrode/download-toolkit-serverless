package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.EpisodeItem
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 9jaRocks season classification against the live fixture matrix
 * (investigation/04_9jarocks_season_matrix.md, 18 real pages).
 *
 * The compiled page parser is authoritative — it takes no OTA payload input,
 * so every case here also proves the broken/absent-payload requirement.
 */
class RocksProviderSeasonTest {

    private fun parse(fixture: String, url: String) =
        RocksProvider.parseCompiledPage(
            Jsoup.parse(resource(fixture), url),
            url
        )

    private fun resource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("season/$name")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun pairs(details: com.anonrode.downloader.data.models.ShowDetails) =
        details.episodes.map { it.season to it.episode }

    @Test
    fun acePartTwoDeclarationMapsBothAnchorsIntoSeasonTwo() {
        val details = parse(
            "ace_part2.html",
            "https://9jarocks.net/videodownload/ace-of-the-diamond-act-ii-season-2-part-ii-anime-id414037.html"
        )
        assertEquals(2, details.episodes.size)
        assertEquals(listOf(2 to 1, 2 to 2), pairs(details))
        assertTrue(details.episodes.all { it.title.contains("S02E") })
    }

    @Test
    fun linkClickIsOneSeasonWithDeclarationOrderAndTransparentLabels() {
        val details = parse(
            "linkclick_s3.html",
            "https://9jarocks.net/videodownload/link-click-season-3-id404923.html"
        )
        assertEquals(11, details.episodes.size)
        assertTrue("no phantom season 4: ${pairs(details)}", details.episodes.all { it.season == 3 })
        assertEquals((1..11).toList(), details.episodes.map { it.episode })
        val remapped = details.episodes.first { it.episode == 9 }
        assertTrue(remapped.title, remapped.title.contains("file labeled S04E09"))
        val remappedFirst = details.episodes.first { it.episode == 1 }
        assertTrue(remappedFirst.title, remappedFirst.title.contains("file labeled S04E01"))
    }

    @Test
    fun generalsDaughterDeclarationMismatchKeepsOneLongSeason() {
        val details = parse(
            "generals_s1.html",
            "https://9jarocks.net/videodownload/the-generals-daughter-season-1-id413865.html"
        )
        assertEquals(180, details.episodes.size)
        assertTrue(details.episodes.all { it.season == 1 })
        assertEquals(180, details.episodes.maxOf { it.episode })
        assertTrue(details.episodes.none { it.season == 2 })
    }

    @Test
    fun soulLandAndJudyKeepMoreThanNinetyNineEpisodesInOneSeason() {
        val soul = parse("soulland_s2.html", "https://9jarocks.net/videodownload/soul-land-season-2-id413367.html")
        assertEquals(170, soul.episodes.size)
        assertTrue(soul.episodes.all { it.season == 2 })

        val judy = parse("judy_s4.html", "https://9jarocks.net/videodownload/judy-justice-season-4-id370468.html")
        assertEquals(117, judy.episodes.size)
        assertTrue(judy.episodes.all { it.season == 4 })
    }

    @Test
    fun promisedNeverlandSplitsOnMarkersAndKeepsTheFractionalSpecial() {
        val details = parse(
            "promised_s1.html",
            "https://9jarocks.net/videodownload/the-promised-neverland-season-1-anime-id383590.html"
        )
        val s1 = details.episodes.filter { it.season == 1 }
        val s2 = details.episodes.filter { it.season == 2 }
        assertEquals(12, s1.size)
        assertEquals((1..12).toList(), s1.map { it.episode })
        assertEquals(12, s2.size)
        assertEquals((1..11).toList(), s2.map { it.episode }.distinct().sorted())
        assertEquals(2, s2.count { it.episode == 5 })
        assertTrue(s2.any { it.title.contains("S02E05.5") })
    }

    @Test
    fun rangeTitlesWithMarkersSplitIntoEverySeason() {
        val faking = parse("faking_1_3.html", "https://9jarocks.net/videodownload/faking-it-season-1-3-id415449.html")
        assertEquals(setOf(1, 2, 3), faking.episodes.map { it.season }.toSet())

        val maria = parse("maria_1_4.html", "https://9jarocks.net/videodownload/maria-watches-over-us-season-1-4-id398085.html")
        assertEquals(setOf(1, 2, 3, 4), maria.episodes.map { it.season }.toSet())
    }

    @Test
    fun dragonBallKaiStaysSeasonOneWithFiveDistinctZipRanges() {
        val details = parse(
            "dbzkai_1_4.html",
            "https://9jarocks.net/videodownload/dragon-ball-z-kai-season-1-4-id375731.html"
        )
        assertTrue(details.episodes.all { it.season == 1 })
        val zips = details.episodes.filter { it.title.contains("ZIP") }
        assertEquals(5, zips.size)
        assertEquals(5, zips.map { it.title }.distinct().size)
        assertFalse(zips.any { Regex("""S01E\d+ ZIP$""").matches(it.title) })
    }

    @Test
    fun echoRangeDeclarationMapsFourAnchorsIntoSeasonOne() {
        val details = parse("echo_s1.html", "https://9jarocks.net/videodownload/echo-of-a-letter-season-1-id414920.html")
        assertEquals(4, details.episodes.size)
        assertEquals(listOf(1 to 1, 1 to 2, 1 to 3, 1 to 4), pairs(details))
    }

    @Test
    fun seniorBrotherDeclarationMismatchFallsBackToTokens() {
        val details = parse("senior_s1.html", "https://9jarocks.net/videodownload/my-senior-brother-season-1-id390545.html")
        // 48 episode files + 2 ZIP packs on the page; the "Episode 41-48
        // Added" line is a changelog (count mismatch) and must be ignored.
        assertEquals(50, details.episodes.size)
        assertTrue(details.episodes.all { it.season == 1 })
        assertEquals(48, details.episodes.filter { !it.title.contains("ZIP") }.maxOf { it.episode })
    }

    @Test
    fun genuineCrossSeasonPageWithSameStyleStaysSplit() {
        // Adversarial test 11: S3E1-E11 + two genuine S4 episodes named in the
        // same style, no count declaration -> NO remap.
        val html = adversarialPage(
            title = "Some Show Season 3 (Complete)",
            files = (1..11).map { "Some.Show.S03E%02d.540p.x265.mkv".format(it) } +
                listOf("Some.Show.S04E01.540p.x265.mkv", "Some.Show.S04E02.540p.x265.mkv")
        )
        val details = RocksProvider.parseCompiledPage(
            Jsoup.parse(html, "https://9jarocks.net/videodownload/some-show-season-3-id1.html"),
            "https://9jarocks.net/videodownload/some-show-season-3-id1.html"
        )
        assertEquals(setOf(3, 4), details.episodes.map { it.season }.toSet())
        assertEquals(11, details.episodes.count { it.season == 3 })
        assertEquals(2, details.episodes.count { it.season == 4 })
    }

    @Test
    fun genuineGapFillingOutlierWithoutStyleEvidenceStaysInItsTokenSeason() {
        // Adversarial test 12: S3 has no E1, one genuine S4E1 named like the
        // S3 files -> gap-fill alone is not allowed.
        val html = adversarialPage(
            title = "Some Show Season 3 (Complete)",
            files = (2..11).map { "Some.Show.S03E%02d.540p.x265.mkv".format(it) } +
                listOf("Some.Show.S04E01.540p.x265.mkv")
        )
        val details = RocksProvider.parseCompiledPage(
            Jsoup.parse(html, "https://9jarocks.net/videodownload/some-show-season-3-id2.html"),
            "https://9jarocks.net/videodownload/some-show-season-3-id2.html"
        )
        assertEquals(1, details.episodes.count { it.season == 4 && it.episode == 1 })
    }

    @Test
    fun stylisticGapFillingOutlierIsRemappedAndKeepsItsRawToken() {
        // Link Click shape in miniature: gap + style + sparse evidence.
        val html = adversarialPage(
            title = "Some Show Season 3 (Complete)",
            files = listOf("Some.Show.S03E02.540p.x265.mkv", "Some.Show.S03E03.540p.x265.mkv") +
                listOf("SOME.SHOW.S04E01.540P.X265.MKV")
        )
        val details = RocksProvider.parseCompiledPage(
            Jsoup.parse(html, "https://9jarocks.net/videodownload/some-show-season-3-id3.html"),
            "https://9jarocks.net/videodownload/some-show-season-3-id3.html"
        )
        assertTrue(details.episodes.all { it.season == 3 })
        assertTrue(details.episodes.any { it.episode == 1 && it.title.contains("file labeled S04E01") })
    }

    @Test
    fun pipelineRowsWithoutExplicitSeasonNeverOverrideCompiledRows() {
        val compiled = listOf(
            EpisodeItem("S03E01", "https://l/1", 3001, "9jarocks", season = 3, episode = 1),
            EpisodeItem("S03E02", "https://l/2", 3002, "9jarocks", season = 3, episode = 2)
        )
        val flatPipeline = listOf(
            EpisodeItem("Episode 1", "https://p/1", 1, "9jarocks"),
            EpisodeItem("Episode 2", "https://p/2", 2, "9jarocks")
        )
        val merged = RocksProvider.mergeExplicitPipeline(compiled, flatPipeline)
        assertEquals(2, merged.size)
        assertTrue(merged.all { it.season == 3 })

        val explicitPipeline = listOf(EpisodeItem("S03E03", "https://p/3", 3003, "9jarocks", season = 3, episode = 3))
        assertEquals(3, RocksProvider.mergeExplicitPipeline(compiled, explicitPipeline).size)
    }

    @Test
    fun declarationParserOnlyAcceptsAddedOrFixedLines() {
        assertNotNull(RocksProvider.episodeDeclaration("Link Click Season 3 (Episode 11 Added)"))
        assertEquals(1 to 11, RocksProvider.episodeDeclaration("Link Click Season 3 (Episode 11 Added)"))
        assertEquals(1 to 4, RocksProvider.episodeDeclaration("Echo of a Letter Season 1 (Episode 1-4 Added)"))
        assertEquals(151 to 180, RocksProvider.episodeDeclaration("The General's Daughter Season 1 (Episode 151-180 Fixed)"))
        assertEquals(null, RocksProvider.episodeDeclaration("Some Show Season 3 (Complete)"))
        assertEquals(null, RocksProvider.episodeDeclaration("Some Show Season 3 (Episode 5 Mentioned)"))
    }

    private fun adversarialPage(title: String, files: List<String>): String {
        val anchors = files.joinToString("\n") { file ->
            "<a href=\"https://downloadwella.com/abc/$file.html\">DOWNLOAD</a>"
        }
        return """
            <html><head><title>$title</title></head><body>
            <h1>$title</h1>
            <div class="entry-content">
            $anchors
            </div>
            </body></html>
        """.trimIndent()
    }
}
