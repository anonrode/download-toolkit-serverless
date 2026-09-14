package com.anonrode.downloader.pipeline

import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.data.net.TerminalProbe
import com.anonrode.downloader.providers.ProviderRegistry
import com.anonrode.downloader.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque

/**
 * The background search-verify oracle (2026-09-14). Consumes ranked search
 * cards and publishes [Verdict]s — proving each card with EXACTLY the
 * machinery downloads use ([LinkResolver.resolveChain],
 * [HttpClient.probeTerminal]) under a hard mobile-data budget. The UI
 * (MainViewModel/HomeScreen) is a dumb executor of [VerdictPolicy]; this
 * object never hides or shows anything itself, it only records proof.
 *
 * Doctrine recap (measured, not assumed — 70-request probe same day):
 *  - nkiri stubs are already dropped at search time by the new REST gate;
 *    every chain that passed the body gate here was CRACKABLE, so the
 *    oracle's Live proof adds trust + instant-tap, and only ever hides
 *    terminals confirmed 404/410 twice (the wildshare-2.0 class).
 *  - Empty drawer ≠ dead: loadEpisodes cannot distinguish a clean empty
 *    page from a challenge page, so emptiness is Unreachable, NEVER Dead.
 *    Only proof hides.
 *
 * Budgets are intentionally conservative for a metered phone: 6 cards per
 * query (site-fair — one slot per site first, then rank order), 3 episode
 * attempts per card, 20s per card, 4 workers, ~1-2 MB worst case cold, ~0
 * on re-search (verdict cache + the drawer's own loadEpisodes cache make
 * verified cards' taps instant AND free).
 */
object ResultVerifier {

    internal const val MAX_CARDS_PER_QUERY = 6
    internal const val MAX_ATTEMPTS_PER_CARD = 3
    internal const val CONCURRENCY = 4
    private const val HTTP_TAG = "verify"
    private const val QUALITY = "720p"

    internal var perCardTimeoutMs = 20_000L
    /** Backoff before the second 404/410 confirmation. Test hook. */
    internal var deadConfirmDelayMs = 2_000L

    /** Seam over the three network legs so the doctrine is JVM-testable
     *  without a device. Production = [LiveChain]; tests inject fakes. */
    internal interface Chain {
        suspend fun loadEpisodes(card: ShowCard): List<EpisodeItem>
        suspend fun resolve(episodeUrl: String, site: String): String?
        suspend fun probe(direct: String, referer: String): TerminalProbe?
    }

    private object LiveChain : Chain {
        override suspend fun loadEpisodes(card: ShowCard) =
            withContext(Dispatchers.IO) { ProviderRegistry.loadEpisodes(card).episodes }

        // allowCacheHit=true: re-cracking a page the engine cracked minutes
        // ago is exactly the data this feature must not burn. NOT invalidate-
        // first — the download path keeps its fresh-link semantic untouched.
        override suspend fun resolve(episodeUrl: String, site: String) =
            withContext(Dispatchers.IO) {
                LinkResolver.resolveChain(episodeUrl, site, QUALITY, allowCacheHit = true)
            }

        override suspend fun probe(direct: String, referer: String) =
            withContext(Dispatchers.IO) {
                HttpClient.probeTerminal(direct, referer = referer, tag = HTTP_TAG)
            }
    }

    internal var chain: Chain = LiveChain

    // ------------------------------------------------------------------ store

    private val verdicts = ConcurrentHashMap<String, Verdict>()
    private val snapshot = MutableStateFlow<Map<String, Verdict>>(emptyMap())
    // Episode URL -> its proven Live verdict (secondary index): the tap
    // handoff asks "did we JUST prove THIS episode serves bytes?" — the card
    // verdict only covers the one representative episode it cracked.
    private val liveByEpisode = ConcurrentHashMap<String, Verdict.Live>()

    /** The instant-tap lookup: fresh Live direct URL for an episode, else
     *  null (engine resolves normally — this is a shortcut, never a gate). */
    fun verifiedDirect(episodeUrl: String, now: Long = System.currentTimeMillis()): String? {
        val v = liveByEpisode[VerdictPolicy.keyFor(episodeUrl)] ?: return null
        return if (VerdictPolicy.isPreresolvable(v, now)) v.directUrl else null
    }

    internal fun indexLive(v: Verdict.Live) {
        liveByEpisode[VerdictPolicy.keyFor(v.episodeUrl)] = v
    }

    /** Everything the UI needs: one immutable map keyed by [VerdictPolicy.keyFor]. */
    fun updates(): StateFlow<Map<String, Verdict>> = snapshot

    fun verdictFor(url: String): Verdict? = verdicts[VerdictPolicy.keyFor(url)]

    private fun publish() { snapshot.value = HashMap(verdicts) }

    // ------------------------------------------------------------------- work

    private val queue = LinkedBlockingDeque<ShowCard>()
    private val queuedKeys = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var lastQueryId: String? = null
    // Per-QUERY, not per-snapshot: searchStreaming emits one ranked snapshot
    // per provider arrival, and every emission calls submit(). The budget
    // resets only when queryId changes.
    @Volatile private var remainingBudget = MAX_CARDS_PER_QUERY
    private var job: Job? = null

    /**
     * Called from the VM per ranked snapshot. Verifies the top
     * [MAX_CARDS_PER_QUERY] not-yet-proven cards with a site-fair budget
     * (see [selectForBudget]). A different
     * [queryId] swaps the pending queue (un-started work for cards no longer
     * on screen is data we must not spend) and cancels in-flight verify
     * calls via the shared tagged-cancel primitive.
     */
    fun submit(scope: CoroutineScope, queryId: String, ranked: List<ShowCard>) {
        val now = System.currentTimeMillis()
        verdicts.entries.removeIf { !VerdictPolicy.isFresh(it.value, now) }
        val newQuery = queryId != lastQueryId
        if (newQuery) {
            lastQueryId = queryId
            remainingBudget = MAX_CARDS_PER_QUERY
            while (true) {
                val dropped = queue.poll() ?: break
                queuedKeys.remove(VerdictPolicy.keyFor(dropped.url))
            }
            HttpClient.cancelTagged(HTTP_TAG)
        }
        val toAdd = if (remainingBudget > 0) {
            selectForBudget(ranked, remainingBudget) { card ->
                val key = VerdictPolicy.keyFor(card.url)
                val v = verdicts[key]
                queuedKeys.contains(key) || (v != null && VerdictPolicy.isFresh(v, now))
            }
        } else emptyList()
        var enqueued = 0
        for (card in toAdd) {
            // add() returns false for a URL duplicated in the ranked list —
            // a duplicate must not double-spend the budget.
            if (queuedKeys.add(VerdictPolicy.keyFor(card.url))) {
                queue.add(card)
                remainingBudget--
                enqueued++
            }
        }
        if (enqueued == 0) { publish(); return }
        if (job?.isActive != true) {
            job = scope.launch {
                kotlinx.coroutines.coroutineScope {
                    repeat(CONCURRENCY) { launch(Dispatchers.IO) { worker() } }
                }
            }
        }
    }

    /**
     * Site-fair budget selection (pure — [submit] applies it): pass 0
     * claims a slot for the FIRST card of each site in rank order, pass 1
     * spends what remains in straight rank order. The naija sites are gated
     * for free at search time (post bodies carry locker markers); the
     * drama/anime sites (admin-ajax JSON, TMDB-style APIs) have no body to
     * gate — the oracle is their ONLY pre-tap proof, and a straight
     * rank-order budget let one heavily-ranked provider starve every other
     * site out of the 6 slots. [skip] reports cards that are already
     * queued or hold a fresh verdict.
     */
    internal fun selectForBudget(
        ranked: List<ShowCard>,
        budget: Int,
        skip: (ShowCard) -> Boolean
    ): List<ShowCard> {
        val chosen = HashSet<String>()
        val considered = HashSet<String>()
        val seenSites = HashSet<String>()
        for (pass in 0..1) {
            for (card in ranked) {
                if (chosen.size >= budget) break
                val key = VerdictPolicy.keyFor(card.url)
                if (key.isEmpty() || !considered.add(key)) continue
                if (pass == 0 && !seenSites.add(card.site)) continue
                if (skip(card)) continue
                chosen.add(key)
            }
        }
        if (chosen.isEmpty()) return emptyList()
        // Emit in RANK order (selection order would let a late-ranked site
        // win the head of the verify queue over the user's #1 result).
        return ranked.filter { chosen.contains(VerdictPolicy.keyFor(it.url)) }
    }

    /** Move a still-queued card to the head (user tapped — verify it NOW). */
    fun prioritize(card: ShowCard) {
        val key = VerdictPolicy.keyFor(card.url)
        if (!queuedKeys.contains(key)) return
        if (queue.remove(card)) {
            queue.addFirst(card)
            DebugLog.resolve("oracle: prioritized '${card.title.take(40)}'")
        }
    }

    private suspend fun worker() {
        while (true) {
            val card = queue.poll() ?: return
            val key = VerdictPolicy.keyFor(card.url)
            queuedKeys.remove(key)
            val verdict = try {
                withTimeoutOrNull(perCardTimeoutMs) { verifyOne(card) }
                    ?: Verdict.Unreachable("timeout", System.currentTimeMillis())
            } catch (e: CancellationException) {
                // Query swap / teardown: put the card back, record NOTHING —
                // "we didn't finish" is not a verdict about the movie.
                queue.addFirst(card)
                queuedKeys.add(key)
                throw e
            } catch (e: Exception) {
                Verdict.Unreachable("crash:${e.javaClass.simpleName}", System.currentTimeMillis())
            }
            verdicts[key] = verdict
            if (verdict is Verdict.Live) indexLive(verdict)
            publish()
            DebugLog.resolve(
                "oracle: " + when (verdict) {
                    is Verdict.Live -> "LIVE '" + card.title.take(40) + "' " +
                        ((verdict.totalBytes ?: 0L) / 1_048_576L) + "MB eps=" + verdict.episodeCount
                    is Verdict.Dead -> "DEAD '${card.title.take(40)}' ${verdict.reason}"
                    is Verdict.Unreachable -> "UNREACHABLE '${card.title.take(40)}' ${verdict.reason}"
                }
            )
        }
    }

    // ---------------------------------------------------------------- doctrine

    internal sealed class ProbeOutcome {
        data class Live(val bytes: Long) : ProbeOutcome()
        object DeadConfirmed : ProbeOutcome()
        data class NotProven(val reason: String) : ProbeOutcome()
    }

    /** Candidate episode order: complete packages first (one crack proves
     *  the whole show, and RocksProvider already sorts them first), then
     *  lowest episode number — the same order a human would try. */
    internal fun pickCandidates(episodes: List<EpisodeItem>): List<EpisodeItem> =
        episodes.sortedWith(
            compareByDescending<EpisodeItem> { it.title.lowercase().contains("complete") }
                .thenBy { it.episodeNum }
        ).take(MAX_ATTEMPTS_PER_CARD)

    internal suspend fun verifyOne(card: ShowCard): Verdict {
        val episodes = try {
            chain.loadEpisodes(card)
        } catch (e: Exception) {
            return Verdict.Unreachable("page:${e.javaClass.simpleName}", System.currentTimeMillis())
        }
        if (episodes.isEmpty()) {
            // NEVER Dead here: an empty drawer page cannot be distinguished
            // from a challenge/parse casualty by loadEpisodes, and only
            // proof hides. (The nkiri/vault/Rocks/Prey body gates already
            // drop true zero-link stubs at search time, for free.)
            return Verdict.Unreachable("episodes:empty", System.currentTimeMillis())
        }
        val candidates = pickCandidates(episodes)
        var resolvedCount = 0
        var deadCount = 0
        for (ep in candidates) {
            val direct = try {
                chain.resolve(ep.url, card.site)
            } catch (e: Exception) {
                null
            }
            if (direct.isNullOrBlank()) continue // uncrackable ≠ dead; try next
            resolvedCount++
            val outcome = probeWithRetry(direct, card.url)
            when (outcome) {
                is ProbeOutcome.Live -> return Verdict.Live(
                    directUrl = direct,
                    episodeUrl = ep.url,
                    totalBytes = outcome.bytes,
                    episodeCount = episodes.size,
                    verifiedAtMs = System.currentTimeMillis()
                )
                ProbeOutcome.DeadConfirmed -> deadCount++
                is ProbeOutcome.NotProven -> { /* uncertainty: keep trying */ }
            }
        }
        // Dead ONLY when every crackable candidate was individually confirmed
        // 404/410 twice. One 429 anywhere keeps the card visible (Unreachable).
        return if (resolvedCount > 0 && deadCount == resolvedCount) {
            Verdict.Dead("terminal-404x2 x$deadCount", System.currentTimeMillis())
        } else {
            Verdict.Unreachable("chain:${candidates.size}c/$resolvedCount r/$deadCount d", System.currentTimeMillis())
        }
    }

    /** probeTerminal once; 404/410 gets the doctrine's single backoff
     *  re-confirmation before it is allowed to count as dead. */
    private suspend fun probeWithRetry(direct: String, referer: String): ProbeOutcome {
        val first = try { chain.probe(direct, referer) } catch (e: Exception) { null }
            ?: return ProbeOutcome.NotProven("net")
        first.totalBytes?.let { return ProbeOutcome.Live(it) }
        if (first.code != 404 && first.code != 410) {
            return ProbeOutcome.NotProven("http-${first.code}${if (first.contentType.contains("html")) " html" else ""}")
        }
        delay(deadConfirmDelayMs)
        val second = try { chain.probe(direct, referer) } catch (e: Exception) { null }
            ?: return ProbeOutcome.NotProven("retry-net")
        second.totalBytes?.let { return ProbeOutcome.Live(it) }
        return if (second.code == 404 || second.code == 410) ProbeOutcome.DeadConfirmed
        else ProbeOutcome.NotProven("retry-${second.code}")
    }

    /** Tests only — production never clears (process death does). */
    internal fun resetForTests() {
        verdicts.clear(); liveByEpisode.clear(); queue.clear(); queuedKeys.clear(); lastQueryId = null
        remainingBudget = MAX_CARDS_PER_QUERY
        job?.cancel(); job = null
        publish()
    }
}
