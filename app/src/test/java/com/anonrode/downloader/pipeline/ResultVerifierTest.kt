package com.anonrode.downloader.pipeline

import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.net.TerminalProbe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Doctrine tests over an injected [ResultVerifier.Chain] — every state
 * transition the oracle can make, including the per-POST mechanism variance
 * the user called out (one site serves one link through a locker that
 * cracks and the next through one that 404s; even episodes of one show
 * may differ): candidate order, retry-per-episode, dead-only-by-double-
 * confirmation, uncertainty-never-hides, crash-safe queue keys.
 */
class ResultVerifierTest {

    private fun ep(n: Int, title: String = "Episode $n") =
        EpisodeItem(title = title, url = "https://site.test/ep$n", episodeNum = n, site = "nkiri")

    private fun card() = ShowCard(title = "Scary Movie (2026)", url = "https://nk.test/post/", site = "nkiri")

    private fun liveP() = TerminalProbe(206, null, "video/mp4", 230_000_000L)
    private fun htmlP() = TerminalProbe(200, null, "text/html", null)
    private fun deadP() = TerminalProbe(404, null, "text/html", null)
    private fun busyP() = TerminalProbe(429, null, "text/html", null)

    /** resolved: epUrl -> direct (null = uncrackable); probes: direct ->
     *  ordered answers, last one repeats when the script runs out. */
    private class Fake(
        val episodes: List<EpisodeItem>,
        val resolved: Map<String, String?> = emptyMap(),
        val probes: Map<String, List<TerminalProbe?>> = emptyMap()
    ) : ResultVerifier.Chain {
        var probeCalls = 0
        private val perDirect = HashMap<String, Int>()
        override suspend fun loadEpisodes(card: ShowCard) = episodes
        override suspend fun resolve(episodeUrl: String, site: String): String? =
            if (resolved.containsKey(episodeUrl)) resolved[episodeUrl] else "$episodeUrl.direct"
        override suspend fun probe(direct: String, referer: String): TerminalProbe? {
            probeCalls++
            val seq = probes[direct] ?: return TerminalProbe(206, null, "video/mp4", 230_000_000L)
            val idx = (perDirect[direct] ?: 0).coerceAtMost(seq.size - 1)
            perDirect[direct] = idx + 1
            return seq[idx]
        }
    }

    private val originalChain = ResultVerifier.chain

    @Before fun setUp() { ResultVerifier.deadConfirmDelayMs = 1L }
    @After fun tearDown() { ResultVerifier.chain = originalChain }

    @Test
    fun movieCrackingToByteServingTerminalIsLive() {
        ResultVerifier.chain = Fake(listOf(ep(1, "Movie 1080p")))
        val v = runBlocking { ResultVerifier.verifyOne(card()) }
        assertTrue(v is Verdict.Live)
        assertEquals(230_000_000L, (v as Verdict.Live).totalBytes)
        assertEquals(1, v.episodeCount)
    }

    @Test
    fun firstEpisodeHtmlTerminalSecondLiveMakesCardLiveNotDead() {
        // The user's exact scenario: same site, same show, per-LINK variance.
        ResultVerifier.chain = Fake(
            listOf(ep(1), ep(2)),
            probes = mapOf(
                "https://site.test/ep1.direct" to listOf(htmlP()),
                "https://site.test/ep2.direct" to listOf(liveP())
            )
        )
        val v = runBlocking { ResultVerifier.verifyOne(card()) }
        assertTrue("html at one terminal must never hide the card", v is Verdict.Live)
    }

    @Test
    fun allCrackableCandidates404TwiceIsTheOnlyDeadPath() {
        val fake = Fake(
            listOf(ep(1), ep(2)),
            probes = mapOf(
                "https://site.test/ep1.direct" to listOf(deadP(), deadP()),
                "https://site.test/ep2.direct" to listOf(deadP(), deadP())
            )
        )
        ResultVerifier.chain = fake
        val v = runBlocking { ResultVerifier.verifyOne(card()) }
        assertTrue(v is Verdict.Dead)
        assertEquals(4, fake.probeCalls) // 2 candidates x (probe + confirm)
    }

    @Test
    fun oneBusyAmongThe404sDowngradesDeadToUnreachable() {
        ResultVerifier.chain = Fake(
            listOf(ep(1), ep(2)),
            probes = mapOf(
                "https://site.test/ep1.direct" to listOf(deadP(), deadP()),
                "https://site.test/ep2.direct" to listOf(busyP())
            )
        )
        val v = runBlocking { ResultVerifier.verifyOne(card()) }
        assertTrue("uncertainty anywhere keeps the card visible", v is Verdict.Unreachable)
    }

    @Test
    fun fortyFourThenResurrectOnRetryIsLive() {
        // The second confirmation is load-bearing: lockers legitimately
        // answer 404 once then serve the file (token rotation, measured
        // 2026-09-14: vault temp tokens age in seconds).
        ResultVerifier.chain = Fake(
            listOf(ep(1)),
            probes = mapOf("https://site.test/ep1.direct" to listOf(deadP(), liveP()))
        )
        val v = runBlocking { ResultVerifier.verifyOne(card()) }
        assertTrue(v is Verdict.Live)
    }

    @Test
    fun emptyEpisodeListIsUnreachableNeverDead() {
        ResultVerifier.chain = Fake(emptyList())
        val v = runBlocking { ResultVerifier.verifyOne(card()) }
        assertTrue(v is Verdict.Unreachable)
    }

    @Test
    fun nothingCrackableIsUnreachableNeverDead() {
        val eps = listOf(ep(1), ep(2), ep(3))
        ResultVerifier.chain = Fake(eps, resolved = eps.associate { it.url to null })
        val v = runBlocking { ResultVerifier.verifyOne(card()) }
        assertTrue(v is Verdict.Unreachable)
    }

    @Test
    fun loadEpisodesThrowingIsUnreachableNeverDead() {
        ResultVerifier.chain = object : ResultVerifier.Chain {
            override suspend fun loadEpisodes(card: ShowCard): List<EpisodeItem> =
                throw java.io.IOException("socket died")
            override suspend fun resolve(episodeUrl: String, site: String): String? = null
            override suspend fun probe(direct: String, referer: String): TerminalProbe? = null
        }
        val v = runBlocking { ResultVerifier.verifyOne(card()) }
        assertTrue(v is Verdict.Unreachable)
    }

    @Test
    fun candidateOrderPrefersCompletePackagesThenLowestEpisodeNumber() {
        val picked = ResultVerifier.pickCandidates(listOf(ep(3), ep(1), ep(2, "Season 2 COMPLETE")))
            .map { it.episodeNum }
        assertEquals(listOf(2, 1, 3), picked)
        assertTrue(ResultVerifier.pickCandidates(List(10) { ep(it + 1) }).size <= ResultVerifier.MAX_ATTEMPTS_PER_CARD)
    }

    @Test
    fun liveVerdictCaptionReachesTheUiStringThroughThePolicy() {
        ResultVerifier.chain = Fake(listOf(ep(1)) + List(23) { ep(it + 2) })
        val v = runBlocking { ResultVerifier.verifyOne(card()) }
        assertEquals("✓ 24 eps · 230 MB/ep", VerdictPolicy.captionFor(v))
    }

    @Test
    fun liveHandoffIndexServesFreshSizedVerdictsOnly() {
        ResultVerifier.resetForTests()
        val fresh = Verdict.Live(
            "https://cdn.x/f.mkv", "https://nk.test/epH", 230_000_000, 1,
            System.currentTimeMillis()
        )
        ResultVerifier.indexLive(fresh)
        assertEquals("https://cdn.x/f.mkv", ResultVerifier.verifiedDirect("https://nk.test/epH"))
        // Past the 120s window the engine must re-resolve (tokens measured
        // aging in seconds - the shortcut may never outrun its proof).
        ResultVerifier.indexLive(fresh.copy(verifiedAtMs = System.currentTimeMillis() - 121_000))
        assertEquals(null, ResultVerifier.verifiedDirect("https://nk.test/epH"))
        // Size-less Live (200-chunked terminal) is not a handoff candidate.
        ResultVerifier.indexLive(fresh.copy(totalBytes = null))
        assertEquals(null, ResultVerifier.verifiedDirect("https://nk.test/epH"))
        ResultVerifier.resetForTests()
        assertEquals(null, ResultVerifier.verifiedDirect("https://nk.test/epH"))
    }

    // -- site-fair budget (2026-09-14) ----------------------------------------

    private fun cardOn(site: String, n: Int) =
        ShowCard(title = "$site pick $n", url = "https://$site.test/post$n/", site = site)

    @Test
    fun budgetReservesAFirstSlotForEverySiteBeforeBackfill() {
        // Six nkiri cards rank above the single drama pick: the straight
        // rank-order budget verified 6 nkiri and 0 drama — yet the drama/
        // anime sites have no free body gate, so the oracle is their ONLY
        // pre-tap proof. Site-fair must spend a slot on the drama card.
        val ranked = (1..6).map { cardOn("nkiri", it) } + cardOn("anitaku", 7)
        val picked = ResultVerifier.selectForBudget(ranked, budget = 6) { false }
        assertEquals(
            listOf(
                "https://nkiri.test/post1/", "https://anitaku.test/post7/",
                "https://nkiri.test/post2/", "https://nkiri.test/post3/",
                "https://nkiri.test/post4/", "https://nkiri.test/post5/"
            ),
            picked.map { it.url }
        )
    }

    @Test
    fun budgetBackfillsRankOrderWithinASingleSite() {
        val ranked = (1..8).map { cardOn("nkiri", it) }
        val picked = ResultVerifier.selectForBudget(ranked, budget = 6) { false }
        assertEquals(ranked.take(6).map { it.url }, picked.map { it.url })
    }

    @Test
    fun budgetSkipsQueuedAndAlreadyProvenCards() {
        val ranked = listOf(cardOn("nkiri", 1), cardOn("nkiri", 2), cardOn("nepu", 3))
        val picked = ResultVerifier.selectForBudget(ranked, budget = 2) { card ->
            card.url == "https://nkiri.test/post1/" // queued/proven marker
        }
        assertEquals(
            listOf("https://nkiri.test/post2/", "https://nepu.test/post3/"),
            picked.map { it.url }
        )
    }
}
