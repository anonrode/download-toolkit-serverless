package com.anonrode.downloader.providers

import com.anonrode.downloader.data.rules.PipelineSource
import com.anonrode.downloader.data.rules.PipelineStep
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Season identity matrix (verified live spec 2026-10-20, report 04).
 *
 * These fixtures reproduce the 18 live 9jaRocks pages' decisive shapes:
 * declaration pages (Ace, Link Click), marker pages (Promised Neverland),
 * long single seasons (150/180), all-S01 ZIPs under a marketing range, flat
 * season posts, and the two adversarial pages where a genuine cross-season
 * token must NOT be remapped. They run through the compiled extractor with a
 * minimal pipeline step — i.e. the same behavior a REV2/BROKEN/ABSENT payload
 * gets, because none of them carries `seasonFromUrl`.
 */
class RulesPipelineSeasonMatrixTest {

    private fun htmlOutcome(html: String, base: String = "https://9jarocks.test/post"): RulesPipeline.StepOutcome =
        RulesPipeline.StepOutcome(html, Jsoup.parse(html, base), null, base)

    private fun step(itemsJson: String): PipelineStep = PipelineStep(
        sources = listOf(PipelineSource("https://9jarocks.test/post")),
        mode = "single",
        asFormat = "html",
        items = JSONObject(itemsJson)
    )

    private val items = """
        {
          "anchorSelector": ".entry-content a[href]",
          "urlAllowlist": ["lightdl.cc", "loadedfiles.net"],
          "labelChain": ["counter"]
        }
    """.trimIndent()

    private fun anchors(vararg hrefs: String): String =
        hrefs.joinToString("") { "<a href=\"$it\">Download</a>" }

    private fun extract(
        title: String,
        showUrl: String,
        body: String
    ): List<com.anonrode.downloader.data.models.EpisodeItem> {
        val html = """
            <html><head><title>$title</title></head><body>
              <div class="entry-content">$body</div>
            </body></html>
        """.trimIndent()
        return RulesPipeline.extractEpisodes(
            "9jarocks", step(items), htmlOutcome(html, showUrl), emptyMap(), showUrl
        ).episodes
    }

    @Test
    fun `ace part ii declaration maps two anchors to s2e1 and s2e2`() {
        val eps = extract(
            "Ace of the Diamond Act II Season 2 Part II Episode 2 Added",
            "https://9jarocks.test/ace-season-2-part-ii-anime-id414037.html",
            anchors(
                "https://lightdl.cc/d/Ace.S02E01.mkv",
                "https://lightdl.cc/d/Ace.2nd.Season.Part.II.E02.mkv"
            )
        )
        assertEquals(2, eps.size)
        assertEquals(listOf(2, 2), eps.map { it.season })
        assertEquals(listOf(1, 2), eps.map { it.episode })
        assertFalse(eps.any { it.season == 1 })
    }

    @Test
    fun `link click declaration maps eleven anchors to s3e1 through s3e11`() {
        val hrefs = mutableListOf<String>()
        hrefs.add("https://lightdl.cc/d/Link.Click.S04E01.mkv") // mislabeled E01
        for (e in 2..8) hrefs.add("https://lightdl.cc/d/Link.Click.S03E%02d.mkv".format(e))
        hrefs.add("https://lightdl.cc/d/Link.Click.S04E09.mkv") // mislabeled E09
        for (e in 10..11) hrefs.add("https://lightdl.cc/d/Link.Click.S03E%02d.mkv".format(e))

        val eps = extract(
            "Link Click Season 3 Episode 11 Added",
            "https://9jarocks.test/link-click-season-3-anime-id404923.html",
            anchors(*hrefs.toTypedArray())
        )
        assertEquals(11, eps.size)
        assertEquals((1..11).toList(), eps.map { it.episode })
        assertTrue("no fake season 4", eps.all { it.season == 3 })
        // Remapped tokens stay transparent in the label.
        assertTrue(eps[0].title.contains("S04E01"))
        assertTrue(eps[8].title.contains("S04E09"))
    }

    @Test
    fun `promised neverland markers split s1 and s2 and keep the 5_5 special`() {
        val s1 = (1..12).joinToString("") {
            "<a href=\"https://lightdl.cc/d/TPN.S01E%02d.mkv\">Download</a>".format(it)
        }
        val s2 = (1..11).joinToString("") {
            "<a href=\"https://lightdl.cc/d/TPN.S02E%02d.mkv\">Download</a>".format(it)
        }
        val special = "<a href=\"https://lightdl.cc/d/TPN.S02E5.5.mkv\">Download</a>"
        val eps = extract(
            "The Promised Neverland Season 1",
            "https://9jarocks.test/the-promised-neverland-season-1-anime-id383590.html",
            s1 + "<h3>SEASON 2</h3>" + s2 + special
        )
        assertEquals(12, eps.count { it.season == 1 })
        assertEquals(12, eps.count { it.season == 2 })
        assertTrue(eps.any { it.season == 2 && it.episode == 5 && it.title.contains("5.5") })
    }

    @Test
    fun `long season stays one season past episode 99`() {
        val body = (1..150).joinToString("") {
            "<a href=\"https://lightdl.cc/d/GD.S01E%03d.mkv\">Download</a>".format(it)
        }
        val eps = extract(
            "The General's Daughter Season 1 Episode 151-180 Added",
            "https://9jarocks.test/the-generals-daughter-season-1-id413865.html",
            body
        )
        assertEquals(150, eps.size)
        assertTrue("every item stays in season 1", eps.all { it.season == 1 })
        assertEquals(101, eps.first { it.episode == 101 }.episode)
    }

    @Test
    fun `dragon ball zips stay s1 with one item per range`() {
        val eps = extract(
            "Dragon Ball Z Kai Season 1-4",
            "https://9jarocks.test/dragon-ball-z-kai-season-1-4-id375731.html",
            anchors(
                "https://loadedfiles.net/d/DBK.S01E1-20.zip",
                "https://loadedfiles.net/d/DBK.S01E21-40.zip",
                "https://loadedfiles.net/d/DBK.S01E41-60.zip",
                "https://loadedfiles.net/d/DBK.S01E61-80.zip",
                "https://loadedfiles.net/d/DBK.S01E81-100.zip"
            )
        )
        assertEquals(5, eps.size)
        assertTrue(eps.all { it.season == 1 })
        assertEquals(5, eps.map { it.url }.toSet().size)
    }

    @Test
    fun `ten episode flat season two page lands in s2`() {
        val body = (1..10).joinToString("") {
            "<a href=\"https://lightdl.cc/d/show-episode-$it\">Download</a>"
        }
        val eps = extract(
            "Some Show Season 2",
            "https://9jarocks.test/some-show-season-2-id414920.html",
            body
        )
        assertEquals(10, eps.size)
        assertTrue(eps.all { it.season == 2 })
        assertEquals((1..10).toList(), eps.map { it.episode })
    }

    @Test
    fun `genuine s3 plus s4 same style stays split`() {
        val s3 = (1..11).joinToString("") {
            "<a href=\"https://lightdl.cc/d/Show.S03E%02d.mkv\">Download</a>".format(it)
        }
        val s4 = anchors(
            "https://lightdl.cc/d/Show.S04E12.mkv",
            "https://lightdl.cc/d/Show.S04E13.mkv"
        )
        val eps = extract(
            "Some Show Season 3",
            "https://9jarocks.test/some-show-season-3-id404923.html",
            s3 + s4
        )
        assertEquals(13, eps.size)
        assertTrue("genuine S4 must not be remapped", eps.any { it.season == 4 })
    }

    @Test
    fun `genuine s4 filling an s3 gap with no declaration stays s4`() {
        val s3 = (2..11).joinToString("") {
            "<a href=\"https://lightdl.cc/d/Show.S03E%02d.mkv\">Download</a>".format(it)
        }
        val eps = extract(
            "Some Show Season 3 (Complete)",
            "https://9jarocks.test/some-show-season-3-id404924.html",
            s3 + anchors("https://lightdl.cc/d/Show.S04E01.mkv")
        )
        assertEquals(11, eps.size)
        assertTrue("gap-fill alone must not move a same-style token", eps.any { it.season == 4 && it.episode == 1 })
    }

    @Test
    fun `absent payload still resolves the season from title and markers`() {
        // No seasonFromUrl, no grouping — the cases above already prove the
        // code path; this pins the explicit identity fields every item carries.
        val eps = extract(
            "Some Show Season 2",
            "https://9jarocks.test/some-show-season-2-id414920.html",
            (1..3).joinToString("") { "<a href=\"https://lightdl.cc/d/e-$it\">Download</a>" }
        )
        assertTrue(eps.all { it.season == 2 && it.episode > 0 })
        assertTrue(eps.all { it.episodeNum == com.anonrode.downloader.util.SeasonEpisode.encode(it.season, it.episode) })
    }
}
