package com.anonrode.downloader.resolvers

import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.pipeline.LinkResolver
import com.anonrode.downloader.pipeline.PipelineError
import com.anonrode.downloader.pipeline.PipelineJournal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import com.anonrode.downloader.engine.ResolveBudget
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import com.anonrode.downloader.data.rules.DynamicRulesManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Matcher
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

interface BaseResolver {
    fun canResolve(url: String): Boolean
    suspend fun resolve(url: String, quality: String = "720p", depth: Int = 0): String?

    /** Legacy diagnostic only; concurrent registry attempts never infer outcomes from it. */
    fun lastResolveFailure(): String? = null
}

/**
 * TRUE-HOST dispatch gate for resolver claims (parsedHost retrofit, 2026-09-12).
 *
 * The historical form `url.lowercase().contains("locker.com")` claims a URL as
 * soon as the host STRING appears anywhere in it — and a URL can be crafted so
 * okhttp fetches a DIFFERENT host than the string shows:
 *   `https://vikingfile.com:443@evil.com/f` (userinfo — host ends at '@')
 *   `https://evil\.downloadwella.com/f`     (authority stops at the backslash)
 * Both pass the substring test, get claimed by the locker's resolver, and are
 * then fetched — and POSTed to — at evil. This gate compares each claim entry
 * against the host `HttpClient.parsedHost()` computes (okhttp3.HttpUrl's own
 * parse): gate and fetcher can no longer disagree about where bytes go. The
 * OTA terminal gate (RulesPipeline) already uses the same source of truth.
 *
 * Entry semantics (matching how the resolver lists are written):
 *  - contains '/'          -> host equals-or-subdomain of the host part AND
 *                             the raw URL contains the path part; a path-only
 *                             entry (empty host part, e.g. "/embed/") claims
 *                             on the path alone — legacy semantics, kept
 *                             honest rather than silently never-matching;
 *  - ends with '.'         -> label-prefix claim: the host starts with it or
 *                             contains ".<entry>" (dood. -> dood.to, sub.dood.to);
 *  - dotted domain         -> host equals it or is a sub-domain suffix of it;
 *  - dot-free fragment     -> substring of the HOST only (no path/query spoof).
 *
 * Unparseable URLs fall back to the legacy whole-string test on purpose: a URL
 * okhttp cannot parse cannot be fetched either, so this keeps dispatch behavior
 * identical for legacy edge inputs, while every FORGED URL (which parses fine
 * — that is the whole class) now hits the true-host rule.
 */
internal fun hostClaim(url: String, hosts: List<String>): Boolean {
    val lower = url.lowercase()
    val host = HttpClient.parsedHost(url)
    if (host == null) return hosts.any { it.isNotBlank() && lower.contains(it) }
    return hosts.any { e ->
        val entry = e.lowercase().trim()
        when {
            entry.isBlank() -> false
            entry.contains('/') -> {
                val h = entry.substringBefore('/')
                val path = "/" + entry.substringAfter('/')
                if (h.isEmpty()) lower.contains(path) // path-only entry
                else (host == h || host.endsWith(".$h")) && lower.contains(path)
            }
            entry.endsWith(".") -> host.startsWith(entry) || host.contains(".$entry")
            entry.contains('.') -> host == entry || host.endsWith(".$entry")
            else -> host.contains(entry)
        }
    }
}

/**
 * Resolve-chain lineage — the recursion budget of ONE user-facing resolve,
 * carried in the coroutine context so EVERY nested registry re-entry inherits
 * it (same propagation contract as ResolveBudget.TaskMemo).
 *
 * Why this exists (device crash evidence 2026-10-15, Downloads/Anon/
 * anon_crash.txt): one trace nested ~969 RulesPipeline.runResolveForSite
 * frames / ~486 DynamicLockerEngine.resolve / ~243 DynamicLockerResolver
 * frames — a resolver cycle whose guards leaked. The OTA handoff corridor
 * (RulesPipeline.runResolveForSite -> ResolverRegistry.resolve) threads
 * depth + visitedSites correctly, but a leg that re-enters the registry with
 * DEFAULT arguments — NaijaVaultGatewayResolver's `ResolverRegistry.resolve(
 * url, quality)` calls, Resolvers.kt NaijaVaultGatewayResolver.resolve —
 * zeroed BOTH guards on every turn through the gateway: depth back to 0 and
 * visitedSites back to empty. A -> gateway -> A then recursed forever:
 * DynamicLockerEngine's cycle check never saw site A again (visited was
 * re-seeded each leg) and no depth check could fire (depth was re-zeroed).
 *
 * [depth]        deepest registry depth this chain reached. A caller that
 *                passes a smaller value (including the reset-to-0 class
 *                above) can never pull the chain below it + 1 — monotone.
 * [visitedSites] union of every site key/host seen on this chain, so
 *                DynamicLockerEngine's cycle check sees the WHOLE path, not
 *                just the current leg.
 * [entries]      hard backstop: how many registry entries this chain made.
 *                Even a chain that defeated both guards cannot pass
 *                [MAX_ENTRIES].
 *
 * Legitimate chains never observe a behavior change: an outermost resolve
 * has no ambient lineage (effective values == caller arguments), and a chain
 * that threads depth/visited correctly computes the same values here.
 */
internal class ResolveLineage(
    val depth: Int,
    val visitedSites: Set<String>,
    val entries: Int
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ResolveLineage> {
        /**
         * Hard re-entry backstop: 2x the depth limit. A chain that respects
         * the limit makes at most RESOLVE_DEPTH_LIMIT + 1 registry entries
         * (depth grows by >= 1 per entry); 2x is slack for multi-leg
         * resolvers, and anything beyond it is a cycle by construction.
         */
        const val MAX_ENTRIES = ResolverRegistry.RESOLVE_DEPTH_LIMIT * 2
    }
}

/**
 * Pure lineage math for ONE registry entry — JVM-testable.
 *  - depth can only grow: max(callerDepth, ambient.depth + 1). A caller that
 *    dropped or reset its depth (exactly the NaijaVaultGatewayResolver
 *    `ResolverRegistry.resolve(x, quality)` class) cannot shrink the budget,
 *    so every handoff loop walks toward ResolverRegistry.RESOLVE_DEPTH_LIMIT.
 *  - visitedSites is the UNION of the caller's set and the ambient chain's,
 *    so cycle detection (DynamicLockerEngine) sees the whole path.
 *  - entries counts each nested registry entry for the hard backstop.
 * With no ambient lineage (the outermost, user-facing resolve) the caller's
 * arguments pass through: depth unchanged, visited = caller's set, entries 1.
 */
internal fun advanceResolveLineage(
    ambient: ResolveLineage?,
    callerDepth: Int,
    callerVisitedSites: Set<String>
): ResolveLineage = ResolveLineage(
    depth = maxOf(callerDepth, (ambient?.depth ?: -1) + 1),
    visitedSites = callerVisitedSites + (ambient?.visitedSites ?: emptySet()),
    entries = (ambient?.entries ?: 0) + 1
)

object ResolverRegistry {
    const val RESOLVE_DEPTH_LIMIT = 6
    private const val NETWORK_RETRY_DELAY_MS = 1500L

    val RESOLVERS: List<BaseResolver> = listOf(
        DynamicLockerResolver,
        VidbasicResolver,
        KissasianResolver,
        KisskhMegaplayResolver,
        VidmolyResolver,
        DownloadwellaResolver,
        LoadedfilesResolver,
        WildshareResolver,
        WaffiCloudResolver,
        StreamwishResolver,
        VidhideResolver,
        DoodstreamResolver,
        MixdropResolver,
        StreamtapeResolver,
        PixelDrainResolver,
        PlutoMoviesResolver,
        LightDLResolver,
        FivePlayResolver,
        VikingFileResolver,
        LulaCloudResolver,
        DramaGatewayResolver,
        NaijaVaultGatewayResolver,
        NpDownloaderGatewayResolver,
        BloggerResolver,
        VidsrcResolver,
        EmbedResolver,
        GenericLockerResolver
    )

    suspend fun resolve(
        url: String,
        quality: String = "720p",
        depth: Int = 0,
        bypassHealth: Boolean = false,
        recordHealth: Boolean = true,
        visitedSites: Set<String> = emptySet(),
        memoTaskId: String? = null
    ): String? = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        // Per-task dead-URL memory (engine.ResolveBudget — engine-owned). The
        // download engine passes its task id at the resolve phase entrance;
        // resolver-internal re-entries of the registry (gateway handoffs,
        // embed walks, depth-1 recursion) inherit the ambient memo through
        // the coroutine context, so OTA and non-OTA resolvers both stop
        // re-walking a url that already consumed one FULL crack pass in
        // this task. Marks die with the task (forgetTask), with the TTL,
        // and at the engine's fresh-chance reset points (park / manual
        // retry / failover switch) — nothing global or permanent forms.
        val memoTid = memoTaskId ?: currentCoroutineContext()[ResolveBudget.TaskMemo]?.taskId
        if (memoTid != null && ResolveBudget.isDead(memoTid, url)) {
            com.anonrode.downloader.util.DebugLog.resolve(
                "task-memo: ${url.take(90)} already failed a full crack pass in this task — skipped without a request"
            )
            return@withContext null
        }
        if (memoTid != null) {
            withContext(ResolveBudget.TaskMemo(memoTid)) {
                resolveCore(url, quality, depth, bypassHealth, recordHealth, visitedSites, memoTid)
            }
        } else {
            resolveCore(url, quality, depth, bypassHealth, recordHealth, visitedSites, null)
        }
    }

    private suspend fun resolveCore(
        url: String,
        quality: String,
        depth: Int,
        bypassHealth: Boolean,
        recordHealth: Boolean,
        visitedSites: Set<String>,
        memoTaskId: String?
    ): String? = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        // knownDead is unconditional (also under bypassHealth): a defunct host
        // must fail in microseconds with an honest reason, not burn a resolver
        // pass. The reason is recorded for the engine's existing error path.
        knownDeadReason(url)?.let { reason ->
            noteKnownDead(url, reason)
            return@withContext null
        }
        // Chain lineage (see ResolveLineage): merges this call's arguments
        // with the ambient chain state. A nested leg that reset depth or
        // visited (NaijaVaultGatewayResolver's default-argument registry
        // re-entries — the 2026-10-15 recursion leak) can no longer reboot
        // the budget: effective depth only grows, visited only unions, and a
        // per-chain entry counter is the hard backstop. Outermost calls have
        // no ambient lineage and pass through unchanged.
        val lineage = advanceResolveLineage(currentCoroutineContext()[ResolveLineage.Key], depth, visitedSites)
        if (lineage.entries > ResolveLineage.MAX_ENTRIES) {
            com.anonrode.downloader.util.DebugLog.resolve(
                "chain lineage: re-entry budget exhausted (${lineage.entries} registry entries on this chain, ${lineage.visitedSites.size} sites visited) — refusing ${url.take(90)}"
            )
            return@withContext null
        }
        // Cache + health apply ONCE per user-facing resolve (lineage depth 0 —
        // an inherited lineage means a nested leg, never a fresh user-facing
        // attempt, even when its caller passed depth 0); the recursive
        // descent below stays uncached so gateway chains work.
        val cacheKey = com.anonrode.downloader.pipeline.ResolveCache.keyFor(url, quality)
        if (lineage.depth == 0) {
            com.anonrode.downloader.pipeline.ResolveCache.get(cacheKey)?.let { cached ->
                // A resolved URL that a stub gate has since proven garbage must
                // never be served from cache: the engine's replay/resume path
                // calls resolve with allowCacheHit=true, which is exactly how a
                // 1-byte vidsrc _stream URL was replayed task after task.
                if (com.anonrode.downloader.pipeline.ResolveCache.isPoisoned(cached)) {
                    com.anonrode.downloader.pipeline.ResolveCache.invalidate(cacheKey)
                    com.anonrode.downloader.util.DebugLog.resolve(
                        "resolve cache: dropped poisoned URL ${cached.take(120)} — re-resolving fresh"
                    )
                } else {
                    com.anonrode.downloader.pipeline.PipelineJournal.hop(
                        site = "", stage = "cache", url = url, ok = true, ms = 0
                    )
                    return@withContext cached
                }
            }
            // Manual retry taps grant ONE bypass of the health gate: the user
            // explicitly asked for a fresh attempt at a cooling-down host, and
            // the gate must not answer "skipped" before the request even fires.
            if (!bypassHealth && !com.anonrode.downloader.pipeline.HostHealth.isUsable(url)) {
                com.anonrode.downloader.pipeline.PipelineJournal.hop(
                    site = "", stage = "health-gate", url = url, ok = false, ms = 0,
                    detail = "host dead or in backoff window — skipped without a request"
                )
                return@withContext null
            }
        }
        // Publish the lineage for the whole subtree: resolver-internal
        // registry re-entries (gateway handoffs, embed walks, OTA handoffs)
        // inherit it via the coroutine context with zero signature changes.
        withContext(lineage) {
            val outcome = resolveInternal(url, quality, lineage.depth, lineage.visitedSites, memoTaskId)
            currentCoroutineContext().ensureActive()
            val rawResult = (outcome as? ResolverOutcome.Success)?.url
                ?.let { com.anonrode.downloader.pipeline.UrlClassifier.repairConcatenatedUrl(it) }
            // Evidence gate (2026-10-20): a resolver output that is a
            // self-referential manifest or a tiny mpegurl decoy is NOT a
            // success. Screen it before caching/health so the stream-proxy
            // class can never enter ResolveCache and be replayed.
            val evidenceRejected = rawResult != null && !screenTerminalEvidence(rawResult)
            val result = if (evidenceRejected) null else rawResult
            if (evidenceRejected) {
                com.anonrode.downloader.pipeline.ResolveCache.poison(rawResult!!)
                com.anonrode.downloader.pipeline.PipelineJournal.hop(
                    site = "", stage = "terminal-evidence", url = url, ok = false, ms = 0,
                    detail = "self-referential/stub terminal from resolver — rejected and poisoned"
                )
            }
            if (memoTaskId != null) {
                // Success here PROVES the host can still crack — a dead mark for
                // this url (e.g. left from a gateway handoff) must not survive it.
                if (result != null) ResolveBudget.markAlive(memoTaskId, url)
                else if ((outcome is ResolverOutcome.Failure &&
                        // Same content-vs-transport rule as the registry loop-end mark:
                        // a transport-sick pass re-walks next pass; a proven-dead page skips.
                        !ResolveBudget.isTransportLevelFailure(outcome.reason)) || evidenceRejected
                ) ResolveBudget.markDead(memoTaskId, url)
            }
            if (lineage.depth == 0 && recordHealth) {
                // Health must be keyed on the TRUE host (same source of truth as
                // hostClaim/the terminal gate): the old string-split host recorded
                // `vikingfile.com:443@evil.com` as a key for forged URLs, leaving
                // the real victim host's backoff stale while garbage keys grew.
                val host = HttpClient.parsedHost(url)
                    ?: url.trim().substringAfter("://").substringBefore('/').substringBefore('?')
                if (result != null) {
                    com.anonrode.downloader.pipeline.ResolveCache.put(cacheKey, result)
                    com.anonrode.downloader.pipeline.HostHealth.recordOk(host)
                } else if (outcome is ResolverOutcome.Failure || evidenceRejected) {
                    val reason = if (evidenceRejected) "terminal evidence: self-referential/stub manifest"
                        else (outcome as? ResolverOutcome.Failure)?.reason
                    com.anonrode.downloader.pipeline.HostHealth.recordFail(
                        host,
                        rateLimited = reason?.contains("429") == true || HttpClient.remainingCooldownMs(url) > 0L,
                        reason = reason
                    )
                }
            }
            result
        }
    }

    /** True if any registered resolver claims this URL. */
    fun canResolve(url: String): Boolean {
        val trimmed = url.trim()
        return RESOLVERS.any { it.canResolve(trimmed) }
    }

    /** Try all usable candidates in a bounded window; settle briefly and pick
     *  the best PROVEN terminal instead of the first one to answer. */
    suspend fun resolveAny(
        urls: List<String>,
        quality: String = "720p",
        maxConcurrency: Int = 3,
        bypassHealth: Boolean = false,
        episode: EpisodeRef? = null,
        settleMs: Long = 1_200L
    ): String? {
        val candidates = urls.asSequence()
            .filter { it.isNotBlank() }
            // Episode pinning (2026-10-20): a mirror whose URL names a
            // DIFFERENT season/episode/tmdb can never be a candidate for this
            // task. This is the Servamp S01E01==S01E02 class (an ep-1 mirror
            // entered every episode's list through srvMap) and the general
            // cross-episode ResolveCache replay.
            .filter { episodeCandidateAllowed(it, episode) }
            .map { com.anonrode.downloader.pipeline.UrlClassifier.repairConcatenatedUrl(it) }
            .distinct()
            // OTA knownDead is a CONTENT/ownership verdict, not a transient
            // health backoff: bypassHealth may lift stale cooldowns, but never
            // re-attempts a host the playbook has declared dead (zippyshare,
            // data.vidsrcme.ru, nepu.to/tv, ...). Record the reason so the
            // engine's resolver-chain-EMPTY error can quote it.
            .filter { url ->
                val reason = knownDeadReason(url)
                if (reason != null) {
                    noteKnownDead(url, reason)
                    false
                } else true
            }
            .let { seq -> seq.filter { candidateAllowedByHealth(it, bypassHealth) } }
            .toList()
        // Provider-issued mirror fan-outs (nepu resolveEpisode — share-31 log,
        // 2026-10-16 12:05, Servamp): the candidate list was handed to us by
        // the provider THIS episode, but HostHealth's cross-session backoff
        // ledger still pre-skipped EVERY one of them (2,189 health-gate ERR
        // hops in one share — stale marks earned in old sessions gated the
        // whole fan-out into a no-op and the task died on "known unavailable"
        // without firing ONE request). bypassHealth=true drops only the
        // PRE-JUDGE skips: this pre-filter and resolveCore's health gate.
        // Nothing else relaxes — per-attempt ResolveBudget caps, retry and
        // transport-failure handling run exactly as before, and HostHealth
        // keeps recording every real outcome (recordOk/recordFail are
        // untouched in resolveCore), so a mirror that fails again re-earns
        // its backoff immediately while one that recovered proof-clears
        // (clearIfAlive on the 2xx). Engine/provider ordering paths keep the
        // default (health-gated) behavior.
        if (bypassHealth) {
            candidates.forEach { url ->
                if (!com.anonrode.downloader.pipeline.HostHealth.isUsable(url)) {
                    com.anonrode.downloader.util.DebugLog.resolve(
                        "mirror fan-out: attempting ${url.take(90)} despite stale HostHealth backoff " +
                            "(provider-endorsed candidate list — backoff re-earned from fresh outcomes only)"
                    )
                }
            }
        }
        return settleAndScore(candidates, maxConcurrency, episode, settleMs) {
            resolve(it, quality, bypassHealth = bypassHealth)
        }
    }

    /**
     * Candidate gate for resolveAny (investigation 11 R1 / fix row 10). A
     * FRESH named edge rejection — HostHealth.hardBlockActive: the last
     * recorded verdict was an HTTP 403/436 within its session TTL — skips
     * the host EVEN under bypassHealth. bypassHealth exists to lift STALE
     * cross-session backoffs on provider-endorsed fan-outs (share-31), never
     * to re-fetch a host this session just proved hostile: the mayonaka-punch
     * resolve 436'd the same 2anime.xyz embed five times and died "chain
     * EMPTY" because every hop re-learned the rejection from scratch. The
     * candidate list then FALLS THROUGH to the remaining lanes.
     * Pure decision surface so JVM tests can pin the contract without
     * network.
     */
    internal fun candidateAllowedByHealth(url: String, bypassHealth: Boolean): Boolean {
        if (com.anonrode.downloader.pipeline.HostHealth.hardBlockActive(url)) return false
        return bypassHealth || com.anonrode.downloader.pipeline.HostHealth.isUsable(url)
    }

    /** User-facing reason for an OTA knownDead host, or null when live. */
    internal fun knownDeadReason(url: String): String? {        if (!DynamicRulesManager.isKnownDead(url)) return null
        val low = url.lowercase()
        return if (low.contains("zippyshare")) {
            "Zippyshare shut down in 2023 — this link cannot be downloaded"
        } else {
            "This host is marked dead in the download rules — no resolver will be attempted"
        }
    }

    /** Record the knownDead reason where the engine already reads host reasons
     *  (DownloadEngine's resolver-chain-EMPTY path appends HostHealth.lastReason). */
    private fun noteKnownDead(url: String, reason: String) {
        com.anonrode.downloader.pipeline.HostHealth.recordFail(url, reason = reason)
        com.anonrode.downloader.pipeline.PipelineJournal.hop(
            site = "", stage = "known-dead", url = url, ok = false, ms = 0, detail = reason
        )
        com.anonrode.downloader.util.DebugLog.resolve("knownDead fast-fail: ${url.take(100)} — $reason")
    }

    private suspend fun resolveInternal(
        url: String,
        quality: String = "720p",
        depth: Int = 0,
        visitedSites: Set<String> = emptySet(),
        memoTaskId: String? = null
    ): ResolverOutcome {
        currentCoroutineContext().ensureActive()
        if (depth > RESOLVE_DEPTH_LIMIT) {
            com.anonrode.downloader.pipeline.PipelineJournal.hop(
                site = "", stage = "registry", url = url, ok = false, ms = 0,
                detail = PipelineError.BudgetExceeded("depth", 0).message ?: "depth limit"
            )
            return ResolverOutcome.Failure("depth limit")
        }
        val trimmed = url.trim()
        // Per-task dead-URL memory, registry side: a recursion or handoff that
        // re-enters a url whose full crack pass already failed in this task is
        // refused BEFORE any resolver claims it or any byte is fetched.
        if (memoTaskId != null && ResolveBudget.isDead(memoTaskId, trimmed)) {
            com.anonrode.downloader.util.DebugLog.resolve(
                "task-memo: chain re-entered ${trimmed.take(90)} — already failed a full crack pass in this task, skipping subtree"
            )
            return ResolverOutcome.Failure("task memo: url already burned a full crack pass in this task")
        }
        var failure: ResolverOutcome = ResolverOutcome.NoMatch
        var anyClaim = false
        for (resolver in RESOLVERS) {
            currentCoroutineContext().ensureActive()
            if (resolver.canResolve(trimmed)) {
                anyClaim = true
                val start = System.currentTimeMillis()
                val outcome = if (resolver === DynamicLockerResolver) {
                    boundedResolverAttempt(trimmed, "DynamicLockerResolver") {
                        DynamicLockerResolver.resolveOutcome(trimmed, quality, depth, visitedSites)
                    }
                } else {
                    resolveWithRetry(resolver, trimmed, quality, depth)
                }
                val direct = (outcome as? ResolverOutcome.Success)?.url
                val elapsed = System.currentTimeMillis() - start
                if (!direct.isNullOrBlank()) {
                    com.anonrode.downloader.pipeline.PipelineJournal.hop(
                        site = "", stage = "crack:${resolver::class.simpleName}",
                        url = trimmed, ok = true, ms = elapsed,
                        detail = "-> ${direct.take(80)}"
                    )
                    // Reference parity (resolvers.py:2185): an intermediate
                    // result that differs from the input flows BACK through
                    // the registry — gateway chains (dramarain download?link=
                    // -> waffi.cloud?preview) need the second pass to crack
                    // the real file URL. Skipped only when the SAME resolver
                    // re-claims a clean media path (its final answer).
                    if (direct != trimmed && depth < RESOLVE_DEPTH_LIMIT) {
                        val path = direct.substringBefore('?').substringBefore('#').lowercase()
                        val mediaPath = isDirectMediaUrl(path)
                        val sameResolverReclaims = resolver.canResolve(direct)
                        // Universal direct-stream guard: if the resolver output
                        // is already a provably-direct CDN endpoint (kissorgrab
                        // /dl/, downloadwella /d/, vikingfile /d/, token-signed
                        // R2/S3, etc.) the recursion STOPS HERE. No subsequent
                        // resolver can claim and POST to a finished media file.
                        // This closes the entire class of bugs where a different
                        // resolver hijacked a working stream and killed it with
                        // HTTP 405 (root case: Pluto → kissorgrab, bd93aae;
                        // guard now covers all future cross-provider handoffs).
                        if (!com.anonrode.downloader.pipeline.LinkResolver.isProvablyDirectFile(direct) &&
                            !(sameResolverReclaims && mediaPath)) {
                            val deeper = resolveInternal(direct, quality, depth + 1, visitedSites, memoTaskId)
                            if (deeper is ResolverOutcome.Success) return deeper
                            // Reference parity (resolvers.py:2473-2475): a failed
                            // deeper pass must NOT fall back to returning the
                            // uncracked intermediary as Success -- the old code
                            // did, handing web locker pages to aria2c as if they
                            // were media (ghost-file class). Terminal CDNs are
                            // exempted above: a media-path result the SAME
                            // resolver re-claims is its final answer, kept even
                            // when a hypothetical deeper pass would fail.
                            if (deeper is ResolverOutcome.Failure) return deeper
                        }
                    }
                    if (depth >= RESOLVE_DEPTH_LIMIT && !isDirectMediaUrl(direct) && !com.anonrode.downloader.pipeline.LinkResolver.isProvablyDirectFile(direct)) {
                        return ResolverOutcome.Failure("Resolve depth limit ($RESOLVE_DEPTH_LIMIT) reached without producing direct media: $direct")
                    }
                    return ResolverOutcome.Success(direct)
                }
                val failureDetail = (outcome as? ResolverOutcome.Failure)?.reason
                    ?: resolver.lastResolveFailure()
                    ?: "no playable stream or token extracted"
                com.anonrode.downloader.pipeline.PipelineJournal.hop(
                    site = "", stage = "crack:${resolver::class.simpleName}",
                    url = trimmed, ok = false, ms = elapsed,
                    detail = failureDetail.take(160)
                )
                failure = if (outcome is ResolverOutcome.Failure) outcome else ResolverOutcome.Failure(failureDetail)
            }
        }
        return failure.also {
            // Full crack pass consumed without any resolver producing anything:
            // this url is dead for the remainder of THIS task (engine.ResolveBudget
            // owns the marks and their reset points; per-session, never global).
            // CONTENT-level failures only: transport failures (DNS/connect/
            // socket/timeout — the network was sick, not the page) must not
            // poison the memo, or one Wi-Fi blink converts later passes into
            // no-op skips. HostHealth owns sick networks; the memo owns dead pages.
            if (memoTaskId != null && anyClaim && it !is ResolverOutcome.Success &&
                !ResolveBudget.isTransportLevelFailure((it as? ResolverOutcome.Failure)?.reason)
            ) {
                ResolveBudget.markDead(memoTaskId, trimmed)
            }
        }
    }

    // Retry only request-local connectivity evidence, never singleton diagnostic strings.
    private suspend fun resolveWithRetry(resolver: BaseResolver, url: String, quality: String, depth: Int): ResolverOutcome {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            HttpClient.awaitOriginCooldown(url)
            val attemptStart = System.currentTimeMillis()
            val outcome = boundedResolverAttempt(url, resolver::class.simpleName ?: "Resolver") {
                if (resolver === LoadedfilesResolver) {
                    LoadedfilesResolver.resolveOutcome(url, quality, depth)
                } else {
                    captureResolverOutcome { resolver.resolve(url, quality, depth) }
                }
            }
            if (outcome !is ResolverOutcome.Success) {
                ResolveBudget.noteFailedBurn(url, System.currentTimeMillis() - attemptStart)
            }
            currentCoroutineContext().ensureActive()
            val cooldownUrl = (outcome as? ResolverOutcome.Failure)?.cooldownUrl
                ?: url.takeIf { HttpClient.remainingCooldownMs(it) > 0L }
            val retryable = (outcome as? ResolverOutcome.Failure)?.retryable == true || cooldownUrl != null
            if (attempt >= 2 || outcome is ResolverOutcome.Success || !retryable) return outcome
            attempt++
            com.anonrode.downloader.util.DebugLog.resolve("transient failure, retry #$attempt ${resolver::class.simpleName}")
            delay(NETWORK_RETRY_DELAY_MS)
            if (cooldownUrl != null && cooldownUrl != url) HttpClient.awaitOriginCooldown(cooldownUrl)
        }
    }

    /**
     * Per-mirror time budget for ONE resolver attempt on ONE url
     * (engine.ResolveBudget). VERIFIED SEMANTICS (deliverable to review):
     * withTimeoutOrNull cancels the attempt, and because the HTTP transport
     * below the resolvers is blocking (HttpClient executes okhttp calls
     * synchronously, no invokeOnCancellation hooks), cancellation is
     * honored at the attempt's next suspension point — the budget can
     * always STOP FURTHER CHAIN WORK (later steps of a multi-fetch
     * resolver, later resolvers, later hops) and always DISCARDS the late
     * result, but it cannot hard-kill a socket already in flight; the
     * worst wall time for one hop is therefore budget + the tail of one
     * in-flight call (socket timeouts 10-30s), never unbounded series.
     * ...
     * (share-29 log: vidbasic 25.4s/27.9s ERR, dood 13.7s ERR, Downloadwella
     * 26.2s ERR). Budget sizing is evidence-based: hosts with proven crack
     * history and known slow-but-legit resolvers get the generous cap so
     * Loadedfiles 14.3/19.1s OK and the naijavault gateway 30.0s OK are
     * never clipped — only unproven hosts. Budget expiry is a synthetic
     * NON-retryable failure: a hopeless attempt is not re-run at full cost;
     * the burn ledger instead prices any later look at 8s.
     */
    private suspend fun <T : ResolverOutcome> boundedResolverAttempt(
        url: String,
        resolverName: String,
        block: suspend () -> T
    ): ResolverOutcome {
        val budget = ResolveBudget.attemptBudgetMs(url, resolverName)
        return withTimeoutOrNull(budget) { block() } ?: ResolverOutcome.Failure(
            reason = "AttemptBudget: $resolverName exceeded ${budget / 1000}s on $url",
            retryable = false
        )
    }

    /**
     * Settle-and-score mirror race (replaces first-success-wins for
     * provider-issued fan-outs).
     *
     * The 2026-10-20 NEPU timeline: the stream-proxy leg validated a
     * 390-byte self-loop at 11:26:11.296 and the genuine 3,347-byte HLS
     * master from the vidbolt leg validated at 11:26:15.401 — already
     * cancelled, because the old race returned the FIRST success. Here the
     * first validated result opens a short [settleMs] window: in-flight legs
     * may finish, no NEW leg is launched, and the winner is selected by
     * structural score (self-referential/stub rejected outright, genuine
     * playlists and direct media outrank opaque endpoints, candidate order
     * breaks ties).
     */
    internal suspend fun settleAndScore(
        candidates: List<String>,
        maxConcurrency: Int,
        episode: EpisodeRef?,
        settleMs: Long = 1_200L,
        attempt: suspend (String) -> String?
    ): String? = supervisorScope {
        currentCoroutineContext().ensureActive()
        if (candidates.isEmpty()) return@supervisorScope null
        val pending = mutableListOf<Deferred<Pair<Int, String?>>>()
        val collected = mutableListOf<Pair<Int, String>>()
        var nextIndex = 0
        val window = maxConcurrency.coerceIn(1, 6)
        fun launchNext() {
            if (nextIndex >= candidates.size) return
            val idx = nextIndex
            val candidate = candidates[idx]
            nextIndex++
            pending += async {
                currentCoroutineContext().ensureActive()
                val result = try {
                    attempt(candidate)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                currentCoroutineContext().ensureActive()
                idx to result
            }
        }
        var settleDeadline = 0L
        try {
            repeat(minOf(candidates.size, window)) { launchNext() }
            while (pending.isNotEmpty()) {
                val pair = (if (settleDeadline > 0L) {
                    val remaining = settleDeadline - System.currentTimeMillis()
                    if (remaining <= 0L) break
                    withTimeoutOrNull(remaining) {
                        select<Pair<Int, String?>> {
                            pending.forEach { work -> work.onAwait { it } }
                        }
                    }
                } else {
                    select<Pair<Int, String?>> {
                        pending.forEach { work -> work.onAwait { it } }
                    }
                }) ?: break
                val done = pending.firstOrNull { it.isCompleted && it.getCompleted() == pair }
                if (done != null) pending.remove(done)
                val result = pair.second
                if (!result.isNullOrBlank()) {
                    val repaired = com.anonrode.downloader.pipeline.UrlClassifier.repairConcatenatedUrl(result)
                    if (episodeResultAllowed(repaired, episode)) {
                        collected.add(pair.first to repaired)
                        if (settleDeadline == 0L) {
                            settleDeadline = System.currentTimeMillis() + settleMs.coerceIn(0L, 10_000L)
                        }
                    } else {
                        com.anonrode.downloader.util.DebugLog.resolve(
                            "resolveAny: rejected cross-episode terminal ${repaired.take(120)}"
                        )
                    }
                }
                if (settleDeadline == 0L) launchNext()
            }
        } finally {
            pending.forEach { it.cancel() }
        }
        if (collected.isEmpty()) return@supervisorScope null
        var best: String? = null
        var bestScore = Int.MIN_VALUE
        for ((idx, url) in collected) {
            if (!screenTerminalEvidence(url)) continue
            val evidence = com.anonrode.downloader.pipeline.UrlClassifier.cachedTerminalEvidence(url)
            val score = terminalScore(url, evidence, idx)
            com.anonrode.downloader.util.DebugLog.resolve(
                "resolveAny: candidate #$idx score=$score ${url.take(120)}"
            )
            if (score > bestScore) {
                bestScore = score
                best = url
            }
        }
        best
    }
}

/**
 * Explicit episode identity for mirror pinning. [season]/[episode] come from
 * the task or the episode URL; [tmdbId] is optional (URL-level checks only
 * when present).
 */
data class EpisodeRef(val season: Int, val episode: Int, val tmdbId: String = "")

private val TV_PATH_REF = Regex("""/(?:watch/)?tv/(\d+)/(\d+)/(\d+)(?:\b|/)""", RegexOption.IGNORE_CASE)
private val QUERY_SEASON = Regex("""[?&](?:season|s)=(\d{1,3})""", RegexOption.IGNORE_CASE)
private val QUERY_EPISODE = Regex("""[?&](?:episode|ep|e)=(\d{1,4})""", RegexOption.IGNORE_CASE)
private val TOKEN_REF = Regex("""(?i)(?:^|[^a-z0-9])s(\d{1,2})[._-]?e(\d{1,4})(?:[^a-z0-9]|$)""")

/** Parsed (tmdb?, season, episode) from a mirror/terminal URL, or null. */
internal fun parseEpisodeFromUrl(url: String): Triple<String, Int, Int>? {
    TV_PATH_REF.find(url)?.let { m ->
        val s = m.groupValues[2].toIntOrNull()
        val e = m.groupValues[3].toIntOrNull()
        if (s != null && e != null && s in 1..99 && e in 1..999) {
            return Triple(m.groupValues[1], s, e)
        }
    }
    TOKEN_REF.find(url)?.let { m ->
        val s = m.groupValues[1].toIntOrNull()
        val e = m.groupValues[2].toIntOrNull()
        if (s != null && e != null && s in 1..99 && e in 1..999) return Triple("", s, e)
    }
    val s = QUERY_SEASON.find(url)?.groupValues?.get(1)?.toIntOrNull()
    val e = QUERY_EPISODE.find(url)?.groupValues?.get(1)?.toIntOrNull()
    if (s != null && e != null && s in 1..99 && e in 1..999) return Triple("", s, e)
    return null
}

/** True when the URL can name this task's episode (unparseable = unknown, allowed). */
internal fun episodeCandidateAllowed(url: String, ref: EpisodeRef?): Boolean {
    if (ref == null || ref.season <= 0 || ref.episode <= 0) return true
    val parsed = parseEpisodeFromUrl(url) ?: return true
    if (parsed.first.isNotBlank() && ref.tmdbId.isNotBlank() && parsed.first != ref.tmdbId) return false
    return parsed.second == ref.season && parsed.third == ref.episode
}

/** True when a RESOLVED terminal names this episode (CDN URLs carry no identity). */
internal fun episodeResultAllowed(url: String, ref: EpisodeRef?): Boolean = episodeCandidateAllowed(url, ref)

/**
 * One evidence check for a resolver's output. Only manifest-shaped or
 * proxy-shaped URLs are inspected (a direct .mp4/.mkv costs nothing). A
 * self-referential playlist or a sub-1KiB mpegurl decoy is poisoned so no
 * later cache lookup can replay it.
 */
internal suspend fun screenTerminalEvidence(url: String): Boolean {
    if (url.isBlank()) return false
    val classifier = com.anonrode.downloader.pipeline.UrlClassifier
    if (classifier.isDirectMediaPath(url) && !classifier.isStreamingLink(url)) return true
    if (!classifier.isManifestShaped(url) && !url.contains("proxy", ignoreCase = true)) return true
    val evidence = classifier.inspectTerminal(
        url,
        referer = DynamicRulesManager.resolveReferer(url),
        tag = "resolve-evidence"
    )
    return when (evidence.kind) {
        com.anonrode.downloader.pipeline.UrlClassifier.TerminalKind.SELF_REFERENTIAL,
        com.anonrode.downloader.pipeline.UrlClassifier.TerminalKind.STUB -> {
            com.anonrode.downloader.pipeline.ResolveCache.poison(url)
            com.anonrode.downloader.util.DebugLog.resolve(
                "terminal evidence: rejected ${evidence.kind} (${evidence.reason}) ${url.take(140)}"
            )
            false
        }
        else -> true
    }
}

/** Structural score; candidate order is the final tiebreak (earlier = better). */
internal fun terminalScore(
    url: String,
    evidence: com.anonrode.downloader.pipeline.UrlClassifier.TerminalEvidence?,
    candidateIndex: Int
): Int {
    val classifier = com.anonrode.downloader.pipeline.UrlClassifier
    var score = when (evidence?.kind) {
        com.anonrode.downloader.pipeline.UrlClassifier.TerminalKind.DIRECT_MEDIA -> 100
        com.anonrode.downloader.pipeline.UrlClassifier.TerminalKind.HLS_PLAYLIST -> 90
        com.anonrode.downloader.pipeline.UrlClassifier.TerminalKind.UNKNOWN -> 20
        com.anonrode.downloader.pipeline.UrlClassifier.TerminalKind.SELF_REFERENTIAL -> -1000
        com.anonrode.downloader.pipeline.UrlClassifier.TerminalKind.STUB -> -1000
        null -> if (classifier.isDirectMediaPath(url)) 100 else if (classifier.isStreamingLink(url)) 80 else 30
    }
    // A resolved .mp4/.mkv URL is structurally better than an opaque endpoint
    // even when its probe provided no content-type.
    if (classifier.isDirectMediaPath(url)) score += 10
    if (url.startsWith("https://", ignoreCase = true)) score += 2
    // Playlist width: a master that declares variants is worth more than a
    // media playlist (the probe's size is a weak but real structural signal).
    val bytes = evidence?.totalBytes ?: 0L
    if (bytes >= 1024L) score += 3
    if (bytes >= 250_000L) score += 2
    return score - candidateIndex
}

// -------------------------------------------------------------
// 1. VidbasicResolver (AES-256-CBC Decryptor)
// -------------------------------------------------------------
object VidbasicResolver : BaseResolver {
    private val KEY = "94588293375053432799222445521289".toByteArray(Charsets.UTF_8)
    private val IV = "5259228356829423".toByteArray(Charsets.UTF_8)
    private val HOSTS = listOf("vidbasic.", "vidb.top", "embedload.cfd")
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        val clean = url.substringBefore('?').substringBefore('#').lowercase()
        return hostClaim(url, HOSTS) && !clean.endsWith(".m3u8") && !clean.endsWith(".mp4")
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            // Reference parity (resolvers.py:557): the page is fetched WITHOUT a
            // Referer header.
            val html = HttpClient.getText(url) ?: run {
                lastFailure = "Vidbasic: empty HTTP response for $url"
                return null
            }

            // 1) this page already carries the encrypted payload (3rdplayer.html)
            val direct = decryptPayload(html)
            if (!direct.isNullOrBlank()) {
                recordCaptions(direct, url)
                return direct
            }

            // 1a) fast-path for 3rdplayer: if page contains data-video or iframe src pointing to 3rdplayer,
            // fetch and decrypt directly
            val playerMatcher = Pattern.compile("""(?:data-video|<iframe[^>]+src)=["']([^"']*3rdplayer[^"']*)["']""").matcher(html)
            if (playerMatcher.find()) {
                val playerUrl = HttpClient.safeResolveUri(url, playerMatcher.group(1)?.replace("&amp;", "&") ?: "")
                if (playerUrl.startsWith("http")) {
                    val playerHtml = HttpClient.getText(playerUrl, referer = url)
                    if (!playerHtml.isNullOrBlank()) {
                        val pDirect = decryptPayload(playerHtml)
                        if (!pDirect.isNullOrBlank()) {
                            recordCaptions(pDirect, url, playerUrl)
                            return pDirect
                        }
                    }
                }
            }

            // 1b) server-selector layout: vidb.top serves a multi-server page whose
            // data-video / data-src / iframe attrs point at EXTERNAL mirror embeds
            // (streamwish, vidhide, doodstream, streamtape). Try resolving the
            // candidates via the registry, falling through to the next mirror if
            // one is dead/expired (resolvers.py:571-589).
            val cands = mutableListOf<String>()
            val attrMatcher = Pattern.compile("""data-(?:video|src|embed|link)=["']([^"']+)["']""").matcher(html)
            while (attrMatcher.find()) cands.add(attrMatcher.group(1) ?: "")
            val iframeSrcMatcher = Pattern.compile("""<iframe[^>]+src=["']([^"']+)["']""").matcher(html)
            while (iframeSrcMatcher.find()) cands.add(iframeSrcMatcher.group(1) ?: "")
            val seen = mutableSetOf<String>()
            for (raw in cands) {
                var cand = raw.trim().replace("&amp;", "&")
                cand = HttpClient.safeResolveUri(url, cand)
                if (!cand.startsWith("http") || cand == url || !seen.add(cand)) continue

                if (cand.contains("3rdplayer.html")) {
                    val playerHtml = HttpClient.getText(cand, referer = url)
                    if (!playerHtml.isNullOrBlank()) {
                        val pDirect = decryptPayload(playerHtml)
                        if (!pDirect.isNullOrBlank()) {
                            recordCaptions(pDirect, url, cand)
                            return pDirect
                        }
                    }
                    continue
                }

                for (other in ResolverRegistry.RESOLVERS) {
                    if (other is VidbasicResolver) continue
                    try {
                        if (other.canResolve(cand)) {
                            val resolved = ResolverRegistry.resolve(cand, quality, depth + 1)
                            if (!resolved.isNullOrBlank()) return resolved
                            break
                        }
                    } catch (_: Exception) {
                        continue
                    }
                }
            }

            // 2) embedload.cfd wrapper iframes the real vidbasic host. This
            // recursion is our own counter capped at 3 so A->B->A cycles end
            // instead of spinning through the registry depth limit
            // (resolvers.py:606-611).
            val miMatcher = Pattern.compile("""<iframe[^>]+src=["']([^"']*(?:vidbasic|vidb\.top|3rdplayer|/embed/)[^"']*)["']""").matcher(html)
            if (miMatcher.find() && depth < 3) {
                var inner = miMatcher.group(1)?.replace("&amp;", "&") ?: ""
                inner = HttpClient.safeResolveUri(url, inner)
                if (inner != url && inner.startsWith("http")) {
                    val innerDirect = resolve(inner, quality, depth + 1)
                    if (!innerDirect.isNullOrBlank()) return innerDirect
                }
            }
            lastFailure = "Vidbasic: no crypto payload, mirror candidates, or 3rdplayer found in HTML (len=${html.length})"
            // Last-chance captions recording: the stream crack failed, but the
            // player URL's sub= captions track may still be valid — record it
            // anyway (a subtitle failure never fails the video).
            recordCaptions(null, url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "Vidbasic: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }

    /**
     * Owner subtitle directive (2026-10-21): the 3rdplayer player URL carries
     * an AES-encrypted sub= parameter that the page's player JS decrypts with
     * the same CryptoJS.AES.decrypt(payload, key, {iv}) family as the stream
     * payload — live-verified 2026-10-21 on
     * vidbasic.top/embed/gq18kua9je3: the jwplayer setup() call passes
     * tracks=[{'kind':'captions','file': CryptoJS.AES.decrypt(sub, key,
     * {'iv':iv})…}] (investigation/live/thirdplayer_e9.html). Decrypt it with
     * the resolver's secret (AES/CBC/PKCS5, same KEY/IV) and extract the
     * captions URL. Pure and side-effect-free: production callers hand the
     * result to [recordCaptions]; JVM tests exercise it with synthetic
     * ciphertext (no file-local state is read, only the hard-coded KEY/IV the
     * reference resolvers.py already carries).
     */
    internal fun decryptCaptionsUrl(playerUrl: String): String? {
        val raw = playerUrl.substringAfter("sub=", "").substringBefore('&').trim()
        if (raw.isBlank()) return null
        // Sub is base64: a literal '+' is base64's plus, not a form-space.
        // Re-escape it before URLDecoder so BOTH shapes on the wire (%2B from
        // the encoded player URL and a bare + from a sloppy emitter) decode to
        // the same 64-char ciphertext (live sub was %2B-encoded).
        val b64 = try {
            URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            return null
        }
        if (b64.isBlank()) return null
        return try {
            // android.util.Base64 on device; java.util fallback keeps the
            // decrypt JVM-testable (decryptMegaplayEnc pattern).
            val cipherBytes = try {
                android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            } catch (_: Exception) {
                java.util.Base64.getDecoder().decode(b64)
            }
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY, "AES"), IvParameterSpec(IV))
            val decrypted = String(cipher.doFinal(cipherBytes), Charsets.UTF_8).trim()
            if (decrypted.startsWith("http") && !decrypted.contains(" ")) decrypted else null
        } catch (_: Exception) {
            // Wrong key/payload shape → no captions for this episode. A
            // subtitle loss must never fail the stream (recipe contract).
            null
        }
    }

    /**
     * Record the sub= captions track under every key the engine's
     * downloadSubtitleSidecars pass will query — task.sourceUrl and
     * task.directUrl (engine keys: SubtitleSurfaces.tracksFor(sourceUrl) and
     * .tracksFor(directUrl)). The direct stream URL is known only on the
     * success paths, candidate player URLs on all paths. Best-effort: never
     * throws, never influences the returned stream URL.
     * internal: JVM tests must exercise the REAL key set (task.sourceUrl +
     * task.directUrl), not a mirrored copy of it.
     */
    internal fun recordCaptions(streamDirect: String?, vararg candidateUrls: String) {
        val captionUrl = candidateUrls.mapNotNull { decryptCaptionsUrl(it) }.firstOrNull() ?: return
        val track = com.anonrode.downloader.providers.SiteSubtitleTrack(
            url = captionUrl,
            language = "und",
            label = "",
            format = com.anonrode.downloader.providers.SubtitleSurfaces.formatOf(captionUrl)
        )
        val keys = LinkedHashSet<String>()
        candidateUrls.forEach { if (it.isNotBlank()) keys.add(it) }
        if (!streamDirect.isNullOrBlank()) keys.add(streamDirect)
        for (key in keys) {
            com.anonrode.downloader.providers.SubtitleSurfaces.record(key, listOf(track))
        }
    }

    private fun decryptPayload(html: String): String? {
        // Attribute order varies between deployments -- try crypto-first,
        // then a bare data-value tag.
        var m = Pattern.compile("""data-name=["']crypto["'][^>]*?data-value=["']([^"']+)["']""").matcher(html)
        if (!m.find()) {
            m = Pattern.compile("""data-value=["']([^"']+)["']""").matcher(html)
            if (!m.find()) return null
        }
        val b64 = m.group(1) ?: return null
        try {
            val cipherBytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(KEY, "AES")
            val ivSpec = IvParameterSpec(IV)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decrypted = String(cipher.doFinal(cipherBytes), Charsets.UTF_8).trim()
            if (decrypted.startsWith("http") && !decrypted.contains(" ")) {
                return decrypted
            }
        } catch (_: Exception) {}
        return null
    }
}

// -------------------------------------------------------------
// 2. KissasianResolver
// -------------------------------------------------------------
object KissasianResolver : BaseResolver {
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        val lower = url.lowercase()
        return hostClaim(url, listOf("kissasian9.ro")) && lower.contains("/kisskh/") && !lower.endsWith(".m3u8")
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            val html = HttpClient.getText(url, referer = url) ?: run {
                lastFailure = "Kissasian: empty HTTP response for $url"
                return null
            }
            val m = Pattern.compile("""sourceUrl"\s*:\s*"([^"]+)""").matcher(html)
            if (m.find()) {
                val apiPath = m.group(1) ?: run {
                    lastFailure = "Kissasian: sourceUrl match group 1 is null"
                    return null
                }
                val apiUrl = HttpClient.safeResolveUri(url, apiPath)
                val apiJson = HttpClient.getText(apiUrl, referer = url) ?: run {
                    lastFailure = "Kissasian: API fetch failed for $apiUrl"
                    return null
                }
                val obj = JSONObject(apiJson)
                val src = obj.optString("source")
                if (src.startsWith("http") && !src.contains(" ")) {
                    return src
                }
            }
            lastFailure = "Kissasian: sourceUrl not found in HTML (len=${html.length})"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "Kissasian: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 3. KisskhMegaplayResolver
// -------------------------------------------------------------
object KisskhMegaplayResolver : BaseResolver {
    private val HOSTS = listOf(
        "kisskh.megaplay.", "megaplays.se", "embtaku.", "takuembed.",
        "anihdplay.", "gogohd.", "megaplay.", "animesama.", "tamilembed.",
        "gogoanime.me.uk", "vkspeed.com", "ansembed.net", "sibnet.ru"
    )
    @Volatile private var lastFailure: String? = null
    private val MEGAPLAY_KEY = ByteArray(32).also { target ->
        val src = "i?LMTAx0Q6,:}50U".toByteArray(Charsets.UTF_8)
        System.arraycopy(src, 0, target, 0, minOf(32, src.size))
    }
    private val MEGAPLAY_IV = "W0;27ToaUpl_P%'c".toByteArray(Charsets.UTF_8)
    /** window.MP_CFG = {…};|< — lazy DOTALL body (megaplays.se 2026-10-15, anitaku mal pages). */
    private val MP_CFG_PATTERN = Pattern.compile("""window\.MP_CFG\s*=\s*(\{.*?\})\s*[;<]""", Pattern.DOTALL)

    override fun lastResolveFailure(): String? = lastFailure

    private fun decryptMegaplayEnc(enc: String): String? {
        return try {
            val clean = enc.replace('-', '+').replace('_', '/').replace(Regex("""\s"""), "")
            val pad = clean.length % 4
            val padded = if (pad > 0) clean + "=".repeat(4 - pad) else clean
            val cipherBytes = try {
                android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
            } catch (_: Exception) {
                java.util.Base64.getDecoder().decode(padded)
            }
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(MEGAPLAY_KEY, "AES")
            val ivSpec = IvParameterSpec(MEGAPLAY_IV)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decryptedBytes = cipher.doFinal(cipherBytes)
            val jsonStr = String(decryptedBytes, Charsets.UTF_8)
            val jsonObj = JSONObject(jsonStr)
            jsonObj.optString("file").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * TRUE quality semantics for the MP_CFG token map — mirrors the app's
     * download-links picker (AnitakuProvider fetch_download_links ordering:
     * best height <= request first, then nearest). Pure, JVM-testable:
     *   1. exact key == [quality] (case-insensitive, live key "720p");
     *   2. nearest key by numeric height parsed from `(\d{3,4})` (prefer
     *      at/below the request, then smallest distance);
     *   3. the key named by [defaultQuality] (window.MP_CFG.defaultQuality);
     *   4. the first qualities key (org.json preserves insertion order).
     * Returns the TOKEN VALUE for the winning key, or null when no key maps
     * to a non-blank string.
     */
    internal fun pickMpQualityToken(qualities: JSONObject, quality: String, defaultQuality: String? = null): String? {
        val keys = mutableListOf<String>()
        val iter = qualities.keys()
        while (iter.hasNext()) keys.add(iter.next())
        if (keys.isEmpty()) return null
        fun tokenOf(key: String?): String? =
            key?.takeIf { it.isNotBlank() && keys.contains(it) }
                ?.let { qualities.optString(it).takeIf { v -> v.isNotBlank() } }
        // 1. exact
        tokenOf(keys.firstOrNull { it.equals(quality.trim(), ignoreCase = true) })?.let { return it }
        // 2. nearest by numeric height
        val reqHeight = quality.filter { it.isDigit() }.toIntOrNull()
        if (reqHeight != null) {
            val parsed = keys.mapNotNull { k ->
                val h = Regex("""(\d{3,4})""").find(k)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                Triple(k, h, Math.abs(h - reqHeight))
            }
            if (parsed.isNotEmpty()) {
                val best = parsed.minWithOrNull(
                    compareBy({ (_, h, _) -> if (h <= reqHeight) 0 else 1 }, { (_, _, d) -> d })
                )
                tokenOf(best?.first)?.let { return it }
            }
        }
        // 3. the key window.MP_CFG.defaultQuality names
        tokenOf(defaultQuality?.trim())?.let { return it }
        // 4. first key
        tokenOf(keys.first())?.let { return it }
        return null
    }

    override fun canResolve(url: String): Boolean {
        if (url.contains("/playlist.php") || url.contains("/api/")) return false
        val lower = url.lowercase()
        if (hostClaim(url, HOSTS)) return true
        // Legacy path-shape claim: kisskh mirrors ride rotating hosts whose
        // only stable marker IS the /kisskh/ path, so the clause stays — but
        // path/authority only, never query/fragment: `evil.test/x?u=/kisskh/`
        // must not be pulled into this resolver by a spoofed parameter.
        val pathPart = lower.substringAfter("://").substringBefore('#').substringBefore('?')
        return pathPart.contains("/kisskh/")
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            // tamilembed serves the real player under HTTP 404 — accept the body
            // (monolith parity, resolvers.py:693-698). LIVE (2026-08): tamilembed
            // now 403s when the fetch carries a referer; it needs referer=null.
            val html = HttpClient.getText(
                url,
                referer = if (url.contains("tamilembed")) null else url,
                acceptStatus = if (url.contains("tamilembed")) setOf(404) else emptySet()
            ) ?: run {
                lastFailure = "KisskhMegaplay: empty HTTP response for $url"
                return null
            }

            // megaplay MP_CFG config object — LIVE (2026-10-15, anitaku →
            // megaplays.se/stream/mal/<mal>/<ep>/sub): the page now carries
            //   window.MP_CFG = {"qualities":{"1080p":"<tok>","720p":"<tok>",...},
            //                     "defaultQuality":"720p",
            //                     "proxyBase":"https://megaplays.se/api/playlist.php?t=",...}
            // and NO legacy markers (no data-id / getSources / defaultUrl).
            // Mint = proxyBase + qualities[<q>] — returned url is real HLS VOD
            // (proven to TS bytes live). This page 200s with the standard
            // header set, so the fetch above already sends Referer = url
            // (tamilembed keeps its no-referer/404-accept special case).
            // Checked FIRST: legacy data-id/getSources below never matches an
            // MP_CFG page, but an MP_CFG page must not spend the inner-iframe
            // walk either.
            val cfgMatcher = MP_CFG_PATTERN.matcher(html)
            if (cfgMatcher.find()) {
                try {
                    val cfg = JSONObject(cfgMatcher.group(1) ?: "")
                    val qualities = cfg.optJSONObject("qualities")
                    if (qualities != null && qualities.length() > 0) {
                        val proxyBase = cfg.optString("proxyBase").takeIf { it.isNotBlank() }
                        if (proxyBase != null) {
                            val token = pickMpQualityToken(
                                qualities,
                                quality,
                                cfg.optString("defaultQuality").takeIf { it.isNotBlank() }
                            )
                            if (!token.isNullOrBlank()) return proxyBase + token
                        }
                    } else if (cfg.optString("enc").isNotBlank()) {
                        // Some deployments ship {"enc":"..."} instead of the
                        // token map: same AES blob the legacy getSources path
                        // decrypts below.
                        val decrypted = decryptMegaplayEnc(cfg.optString("enc"))
                        if (!decrypted.isNullOrBlank()) return decrypted
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {}
                // MP_CFG present but shape-unusable: fall through to the
                // legacy branches unchanged.
            }

            // Inner iframe (tamilembed / blogger)
            val doc = Jsoup.parse(html, url)
            val innerIframe = doc.selectFirst("iframe[src]")
            if (innerIframe != null) {
                val rawSrc = innerIframe.attr("src")
                val src = HttpClient.safeResolveUri(url, rawSrc)
                if (src != url && !src.startsWith("javascript:")) {
                    if (src.contains("blogger.com")) return src
                    val nested = ResolverRegistry.resolve(src, quality, depth + 1)
                    if (!nested.isNullOrBlank()) return nested
                }
            }

            // animesama layout
            val smMatcher = Pattern.compile("""const\s+STREAM\s*=\s*["']([^"']+)["']""").matcher(html)
            if (smMatcher.find()) return smMatcher.group(1)?.replace("\\/", "/")

            // megaplays / takuembed layout
            val defMatcher = Pattern.compile("""var\s+defaultUrl\s*=\s*["']([^"']+)["']""").matcher(html)
            if (defMatcher.find()) {
                val targetUrl = defMatcher.group(1)?.replace("\\/", "/") ?: ""
                val pbMatcher = Pattern.compile("""var\s+proxyBase\s*=\s*["']([^"']+)["']""").matcher(html)
                if (pbMatcher.find()) {
                    val proxyBase = pbMatcher.group(1) ?: ""
                    return proxyBase + URLEncoder.encode(targetUrl, "UTF-8")
                }
                return targetUrl
            }

            // megaplay.buzz getSources API
            // LIVE (2026-08): the API keys on data-id (the file id), NOT
            // data-realid / the /stream/ URL id, and 403s without the
            // X-Requested-With: XMLHttpRequest header.
            if (url.contains("megaplay")) {
                val dataIdMatcher = Pattern.compile("""data-id=["'](\d+)["']""").matcher(html)
                val realIdMatcher = Pattern.compile("""data-realid=["'](\d+)["']""").matcher(html)
                val epIdMatcher = Pattern.compile("""/stream/(?:s-\d+/)?(\d+)""").matcher(url)
                val streamId = when {
                    dataIdMatcher.find() -> dataIdMatcher.group(1)
                    realIdMatcher.find() -> realIdMatcher.group(1)
                    epIdMatcher.find() -> epIdMatcher.group(1)
                    else -> null
                }
                if (!streamId.isNullOrBlank()) {
                    val apiUrl = "https://megaplay.buzz/stream/getSources?id=$streamId"
                    val apiJson = HttpClient.getText(
                        apiUrl,
                        referer = url,
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                    )
                    if (!apiJson.isNullOrBlank()) {
                        val data = JSONObject(apiJson)
                        val fileUrl = data.optJSONObject("sources")?.optString("file")
                            ?.takeIf { it.isNotBlank() }
                        val resolved = fileUrl ?: decryptMegaplayEnc(data.optString("enc"))
                        if (!resolved.isNullOrBlank()) {
                            // Captions surface BEFORE the return: the getSources
                            // payload carries `tracks[]` alongside sources/enc and
                            // it was silently dropped since the lane existed
                            // (share-43 / investigation 11 — Demon's Ascension and
                            // every Anitaku download shipped without subtitles).
                            // Best-effort: a subtitle loss must never fail the
                            // stream (recipe contract).
                            recordMegaplayCaptions(resolved, apiJson, url)
                            return resolved
                        }
                    }
                }
            }

            // sibnet.ru shell: the source is a RELATIVE path in inline JS --
            // player.src([{src: "/v/<hash>/<id>.mp4", ...}]) -- which 302s to a
            // signed CDN URL. Absolute-URL regexes never see it.
            val sibMatcher = Pattern.compile("""player\.src\(\[\{src:\s*["']([^"']+)["']""").matcher(html)
            if (sibMatcher.find() && sibMatcher.group(1)?.startsWith("/") == true) {
                return "https://video.sibnet.ru" + sibMatcher.group(1)
            }

            val direct = extractM3u8FromHtml(html) ?: extractMp4FromHtml(html)
            if (!direct.isNullOrBlank()) return direct

            val unpacked = JsUnpacker.unpack(html)
            if (!unpacked.isNullOrBlank()) {
                val unpDirect = extractM3u8FromHtml(unpacked) ?: extractMp4FromHtml(unpacked)
                if (!unpDirect.isNullOrBlank()) return unpDirect
            }
            lastFailure = "KisskhMegaplay: no stream extracted from HTML (len=${html.length})"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "KisskhMegaplay: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }

    /**
     * megaplay getSources `tracks[]` → caption surfaces (investigation 11,
     * live 2026-10-21). The JSON shape is:
     *   {"tracks":[{"file":"https://<cdn>/....vtt","label":"English",
     *               "kind":"captions","default":true},
     *              {"file":".../preview/thumbnails.vtt","kind":"thumbnails"}],
     *    "enc":"<AES blob whose plaintext is {\"file\":\"<master.m3u8>\"}>"}
     * Only `kind == "captions"` entries are subtitles — the thumbnails
     * preview is a scrubber sprite sheet and must NOT leak into sidecars.
     * Pure (no I/O), JVM-tested against the captured payload.
     */
    internal fun parseMegaplayCaptions(apiJson: String): List<com.anonrode.downloader.providers.SiteSubtitleTrack> {
        if (apiJson.isBlank()) return emptyList()
        val root = try {
            JSONObject(apiJson)
        } catch (_: Exception) {
            return emptyList()
        }
        val tracks = root.optJSONArray("tracks") ?: return emptyList()
        val out = mutableListOf<com.anonrode.downloader.providers.SiteSubtitleTrack>()
        for (i in 0 until tracks.length()) {
            val item = tracks.optJSONObject(i) ?: continue
            if (!item.optString("kind").trim().equals("captions", ignoreCase = true)) continue
            val file = item.optString("file").trim()
            if (file.isBlank() || !file.startsWith("http", ignoreCase = true)) continue
            val label = item.optString("label").trim()
            val format = com.anonrode.downloader.providers.SubtitleSurfaces.formatOf(file)
                .ifBlank { "vtt" } // megaplay captions are always WebVTT on the wire
            out.add(
                com.anonrode.downloader.providers.SiteSubtitleTrack(
                    url = file,
                    language = megaplayLangFromLabel(label),
                    label = label,
                    format = format
                )
            )
        }
        return out.distinctBy { it.url.lowercase() }
    }

    /**
     * Record the captions for the stream we are about to return, under every
     * key the engine's sidecar pass queries:
     *  - SubtitleSurfaces: the RETURNED master URL (task.directUrl — the key
     *    the engine guarantees) plus the cracked source page as a secondary;
     *  - ResolvedHints: [com.anonrode.downloader.data.models.SubtitleTrack]s
     *    for the same directUrl, so downloadSubtitleSidecars' lookup
     *    (ResolvedHints.subtitlesFor(task.directUrl)) sees them even if the
     *    SubtitleSurfaces entry aged out (30 min merged TTL there vs 6 h).
     * The Referer contract is pinned with the record (live referer flip,
     * 2026-10-21: the VTT host 403s without `Referer: https://megaplay.buzz/`,
     * and rotating CDN hosts like j1i9s.embermeadow.world match NO referer
     * host-policy, so the sidecar fetch must take it from the hint, not the
     * policy fallback). Never throws, never influences the returned URL.
     * internal: JVM tests must exercise the REAL key set.
     */
    internal fun recordMegaplayCaptions(masterUrl: String, apiJson: String, sourcePageUrl: String = "") {
        val tracks = parseMegaplayCaptions(apiJson)
        if (tracks.isEmpty()) return
        for (key in LinkedHashSet(listOf(masterUrl, sourcePageUrl))) {
            if (key.isNotBlank()) {
                com.anonrode.downloader.providers.SubtitleSurfaces.record(key, tracks)
            }
        }
        com.anonrode.downloader.pipeline.ResolvedHints.remember(
            episodeUrl = "",
            directUrl = masterUrl,
            referer = "https://megaplay.buzz/",
            subtitles = tracks.map {
                com.anonrode.downloader.data.models.SubtitleTrack(
                    url = it.url,
                    lang = it.language,
                    kind = it.format
                )
            }
        )
    }

    /** megaplay labels captions ("English") instead of sending an ISO code;
     *  translate the common labels, pass through real codes, else "und". */
    internal fun megaplayLangFromLabel(label: String): String {
        val mapped = MEGAPLAY_LABEL_TO_LANG[label.trim().lowercase()]
        if (mapped != null) return mapped
        val cleaned = label.trim().lowercase().replace(' ', '-')
        return if (Regex("""^[a-z]{2,3}(-[a-z0-9]{2,8})*$""").matches(cleaned)) cleaned else "und"
    }

    private val MEGAPLAY_LABEL_TO_LANG = mapOf(
        "english" to "en", "spanish" to "es", "español" to "es",
        "portuguese" to "pt", "português" to "pt", "brazilian portuguese" to "pt-BR",
        "french" to "fr", "français" to "fr", "german" to "de", "deutsch" to "de",
        "italian" to "it", "italiano" to "it", "japanese" to "ja", "日本語" to "ja",
        "korean" to "ko", "한국어" to "ko", "chinese" to "zh", "中文" to "zh",
        "arabic" to "ar", "hindi" to "hi", "russian" to "ru", "turkish" to "tr",
        "thai" to "th", "vietnamese" to "vi", "indonesian" to "id",
        "malay" to "ms", "polish" to "pl", "ukrainian" to "uk", "filipino" to "fil"
    )
}

// -------------------------------------------------------------
// 4. BloggerResolver (batchexecute RPC -> direct googlevideo MP4)
// -------------------------------------------------------------
object BloggerResolver : BaseResolver {
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        val low = url.lowercase()
        return hostClaim(url, listOf("blogger.com")) && (low.contains("video.g") || low.contains("token="))
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            val html = HttpClient.getText(url) ?: run {
                lastFailure = "Blogger: empty HTTP response for $url"
                return null
            }

            val fsidMatcher = Pattern.compile("""FdrFJe":"([^"]+)""").matcher(html)
            if (!fsidMatcher.find()) {
                lastFailure = "Blogger: FdrFJe sid token not found in HTML"
                return null
            }
            val fSid = fsidMatcher.group(1) ?: run {
                lastFailure = "Blogger: FdrFJe match group 1 is null"
                return null
            }

            val blMatcher = Pattern.compile("""boq_bloggeruiserver_[^'", ]+""").matcher(html)
            if (!blMatcher.find()) {
                lastFailure = "Blogger: boq_bloggeruiserver bl token not found in HTML"
                return null
            }
            val bl = blMatcher.group(0)

            val tokenMatcher = Pattern.compile("""[?&]token=([^&]+)""").matcher(url)
            if (!tokenMatcher.find()) {
                lastFailure = "Blogger: token param not found in URL"
                return null
            }
            val token = tokenMatcher.group(1) ?: run {
                lastFailure = "Blogger: token match group 1 is null"
                return null
            }

            val rpcUrl = "https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute?rpcids=WcwnYd&source-path=%2Fvideo.g&f.sid=${URLEncoder.encode(fSid, "UTF-8")}&bl=${URLEncoder.encode(bl, "UTF-8")}&hl=en-US&rt=c"
            val fReq = """[[["WcwnYd","[\"$token\"]",null,"generic"]]]"""

            val form = FormBody.Builder()
                .add("f.req", fReq)
                .build()

            val req = Request.Builder()
                .url(rpcUrl)
                .header("User-Agent", HttpClient.DEFAULT_UA)
                .header("Referer", "https://www.blogger.com/")
                .header("Origin", "https://www.blogger.com")
                .header("X-Same-Domain", "1")
                .post(form)
                .build()

            HttpClient.executeCancellable(HttpClient.shared, req) use@{ res ->
                if (!res.isSuccessful) {
                    lastFailure = "Blogger: RPC returned HTTP ${res.code}"
                    return null
                }
                val body = HttpClient.cappedText(res) ?: run {
                    lastFailure = "Blogger: empty RPC body"
                    return null
                }

                // JSON-in-JSON wire (WcwnYd payload): URLs are closed by \" and
                // interior chars are DOUBLE-escaped (\\u003d). The old greedy
                // regex swallowed the terminator backslash and the single-layer
                // unescape left a stray \ before every '='/'&' — okhttp then
                // percent-encoded them and googlevideo 403'd every request
                // (signature/lsig mismatch). Lazy match up to the terminator +
                // two-layer unescape; live A/B: clean URL 206+ftyp, old replay 403
                // (scratch/anime100/INVESTIGATION403.md).
                val urlMatches = Pattern.compile("""(https://[^"]+?googlevideo\.com[^"]+?(?=\\\"))""").matcher(body)
                val urls = mutableListOf<String>()
                while (urlMatches.find()) {
                    var u = urlMatches.group(1) ?: continue
                    u = u.replace("\\\\u003d", "=").replace("\\\\u0026", "&").replace("\\\\/", "/").replace("\\\"", "")
                    urls.add(u)
                }

                // Prefer 720p (itag 22), then 360p (itag 18)
                val itag22 = urls.find { it.contains("itag=22") }
                if (itag22 != null) return itag22
                val itag18 = urls.find { it.contains("itag=18") }
                if (itag18 != null) return itag18
                if (urls.isNotEmpty()) return urls.first()
                lastFailure = "Blogger: no googlevideo stream URL in RPC response"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "Blogger: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 5. VidsrcResolver (vidsrc / nepu watch pages -> token-stamped HLS)
// -------------------------------------------------------------
object VidsrcResolver : BaseResolver {
    private val HOSTS = listOf(
        "vidsrc.mov", "vidsrc.me", "vidsrc.net", "vidsrc.cc", "vidsrc.to",
        "vidsrc.in", "vidsrc.pm", "vidsrc.xyz", "vsembed.ru",
        "cloudorchestranova.com", "data.vidsrcme.ru",
        "nepu.gd/watch", "nepu.to/watch",
        "vidsrc.fyi", "vidrock.net", "vidnest.fun", "vidking.net",
        "vidlink.pro", "vidfast.pro", "vidup.to", "player.videasy.net",
        "111movies.com", "2embed.cc", "vidsrc.buzz", "multiembed.mov",
        "superflixapi.co", "peachify.top"
    )
    private val TMDB_PATTERN = Pattern.compile("""/(?:movie|tv|filme|serie|embed|embedtv)/(\d+)(?:[/_&?=-](\d+)[/_&?=-](\d+))?""")
    private val MULTIEMBED_PATTERN = Pattern.compile("""(?:video_id|tmdb|id)=(\d+)(?:.*?[&?]s=(\d+)&e=(\d+))?""")
    private val ORIGIN_PATTERN = Pattern.compile("""https?://[^/]+""")
    internal val missingTmdb = ConcurrentHashMap<String, Long>()
    internal val availableTmdb = ConcurrentHashMap<String, Long>()
    internal val transientMissingTmdb = ConcurrentHashMap<String, Long>()
    private const val MISSING_TMDB_TTL_MS = 15 * 60_000L
    private const val AVAILABLE_TMDB_TTL_MS = 60 * 60_000L
    private const val TRANSIENT_BACKOFF_TTL_MS = 30_000L
    @Volatile private var lastFailure: String? = null

    // ------------------------------------------------------------------
    // LIVE vidsrc extraction path — recreated from the working site
    // (probe-verified 2026-10-15, ~4-10s total to a playable playlist):
    //   GET vidsrc.buzz/embed/{movie|tv}/{tmdb}[/{s}/{e}]
    //        -> the embed page ships its bootstrap OBJECT inline:
    //           Q = {"type":..,"id":"tt..","t":"<signed token>",
    //                "ssr":{"status":"ok","servers":[
    //                  {"ref":"<signed mint ref>","name":"Server VNH",..}]}}
    //        -- "ssr" is NULL on many episode pages (live: tmdb 83097 S2E9);
    //           the player then fills the list from its own endpoint, and we
    //           must too (see the sources-fill fallback below):
    //   GET vidsrc.buzz/pl/api.php?a=sources&type=<Q.type>&id=<Q.id>
    //       &s=<Q.s>&e=<Q.e>&t=<t>
    //        -> 200 {"status":"ok","servers":[{"ref":"<signed ref>",..}]}
    //   GET vidsrc.buzz/pl/api.php?a=play&ref=<ref>&t=<t>
    //        -> 200 {"url":"/_stream?id=<id>","type":"hls"}
    //        -> GET vidsrc.buzz/_stream?id=<id> = live #EXTM3U (VOD).
    // NOTE: /pl/ is the player's <base href>; a bare /api.php 404s — the
    // browser resolves api.php relative to that base, we must match.
    // The old data.vidsrcme.ru upstream (691 ConnectExceptions vs 31 OK
    // in log 29 — TCP-refused from the device AND desktop networks) is
    // kept only as a dead-flagged legacy fallback below, and availability
    // checks fail open against it without any request while dead.
    // ------------------------------------------------------------------
    private const val BUZZ_HOST = "https://vidsrc.buzz"
    private val Q_BLOCK = Pattern.compile("""Q\s*=\s*(\{.*?\});""", Pattern.DOTALL)

    // OTA escape hatch — same channel as the Downloadwella/Loadedfiles
    // resolver configs (getResolverConfig): scraper_rules.json can carry a
    // "vidsrc" resolver config and re-point this flow when the family
    // rotates domains or the player base path again. A rules-file push,
    // no APK. Keys (all https origins or origin+path, length-capped):
    //   "embedBase"    — default https://vidsrc.buzz           (page host)
    //   "mintBase"     — default https://vidsrc.buzz/pl        (/pl/ = the
    //                     player's <base href>; api.php resolves under it)
    //   "legacyApiHost"— default https://data.vidsrcme.ru      (legacy intake)
    private fun otaStr(key: String, default: String): String = try {
        DynamicRulesManager.getResolverConfig("vidsrc")?.optString(key)
            ?.takeIf { it.isNotBlank() && it.length <= 200 && it.startsWith("https://") && !it.contains(' ') }
            ?: default
    } catch (_: Exception) { default }

    private fun otaEmbedBase(): String = otaStr("embedBase", BUZZ_HOST)
    private fun otaMintBase(): String = otaStr("mintBase", "$BUZZ_HOST/pl")
    private fun otaLegacyApiHost(): String = otaStr("legacyApiHost", "https://data.vidsrcme.ru")

    /** One transport failure of data.vidsrcme.ru parks legacy-API probes
     *  for this long; availability and the legacy resolve path honor it. */
    private val LEGACY_API_DEAD_TTL_MS = 10 * 60_000L

    @Volatile private var legacyApiDeadUntilMs: Long = 0L

    /** In-session dead-flag AND the persisted health plane. The compiled
     *  HostHealth seed for data.vidsrcme.ru (2026-10-18 audit §3.3) is what
     *  actually kills the ~30-ConnectException cold-start wave: the in-memory
     *  flag alone starts empty on every process. Payload knownDead entries
     *  for the legacy host bite here too, and an OTA re-point of
     *  legacyApiHost is judged by ITS own host. Callers keep the fail-open
     *  semantic: "unusable" = skip WITHOUT a request, never a content
     *  verdict. */
    private fun legacyApiUsable(): Boolean =
        System.currentTimeMillis() >= legacyApiDeadUntilMs &&
            com.anonrode.downloader.pipeline.HostHealth.isUsable(otaLegacyApiHost())

    private fun markLegacyApiDead() {
        legacyApiDeadUntilMs = System.currentTimeMillis() + LEGACY_API_DEAD_TTL_MS
    }

    /**
     * Primary extraction: mint from the vidsrc.buzz player's own relay
     * (live-verified flow above). Failure reasons are deliberate:
     * null-with-content-reason ("no mintable sources") is memo-eligible per
     * the task dead-URL memory; page/bootstrap transport misses return
     * without a content verdict so network hiccups re-walk next pass.
     */
    private suspend fun resolveViaBuzz(mediaType: String, tmdb: String, season: String?, episode: String?): String? {
        val embedBase = otaEmbedBase()
        val mintBase = otaMintBase()
        val pageUrl = if (mediaType == "tv")
            "$embedBase/embed/tv/$tmdb/${season ?: "1"}/${episode ?: "1"}"
        else
            "$embedBase/embed/movie/$tmdb"
        // vidsrc.buzz 429s are recorded by HttpClient (log 29 lesson: walk
        // all mirrors into a 429'd origin = 17 x ~1.6s of nothing) — honor
        // the cooldown before any fetch.
        if (com.anonrode.downloader.data.net.HttpClient.isOriginCoolingDown(pageUrl)) {
            lastFailure = "Vidsrc: vidsrc.buzz origin rate-limited (cooldown active) for tmdb=$tmdb"
            return null
        }
        val html = try {
            HttpClient.getText(pageUrl, referer = "https://www.2embed.cc/")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: run {
            // Page miss without a body: NOT a content verdict (could be the
            // network or a 4xx) — mirrors still walk next pass.
            lastFailure = "Vidsrc.buzz: embed page not served for tmdb=$tmdb"
            return null
        }
        val qm = Q_BLOCK.matcher(html)
        if (!qm.find()) {
            // Page answered but carries no player bootstrap: layout change or
            // interstitial. Content-level, memo-eligible.
            lastFailure = "Vidsrc: bootstrap object missing on vidsrc.buzz embed page (tmdb=$tmdb)"
            return null
        }
        var token: String? = null
        var servers: org.json.JSONArray? = null
        var sourcesFillFailed = false
        var sourcesFillWaitTimedOut = false
        try {
            val q = JSONObject(qm.group(1))
            token = q.optString("t").takeIf { it.isNotBlank() }
            servers = q.optJSONObject("ssr")?.optJSONArray("servers")
            // Episode pages routinely ship ssr:null (live: tmdb 83097 S2E9)
            // while the player's own fill call still returns the server list
            // — the page JS runs exactly this before playing. Without it the
            // resolver declared a live episode "no mintable sources" and
            // memoized it dead for 15 minutes ("known unavailable").
            if ((servers == null || servers.length() == 0) && token != null) {
                val qs = "type=" + URLEncoder.encode(q.optString("type"), "UTF-8") +
                    "&id=" + URLEncoder.encode(q.optString("id"), "UTF-8") +
                    "&s=" + URLEncoder.encode(q.optString("s"), "UTF-8") +
                    "&e=" + URLEncoder.encode(q.optString("e"), "UTF-8") +
                    "&t=" + URLEncoder.encode(token, "UTF-8")
                // LIVE (2026-10-16, tmdb 67710 = Servamp): while the vault
                // prepares the server list the fill call answers
                // {"status":"wait"} and held it for >25s — the code below
                // used to treat a wait+empty exactly like content-empty and
                // memoized the title dead for 15 minutes. Retry the SAME url
                // up to 2 more times (3s apart) before giving up; a wait that
                // never resolves is transport-class (no memo), while a real
                // {"status":"none", servers:[]} keeps today's verdict.
                var waitRetries = 0
                var fillSaidWait = false
                while (true) {
                    val filled = try {
                        HttpClient.getText("$mintBase/api.php?a=sources&$qs", referer = pageUrl)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    if (filled == null) {
                        sourcesFillFailed = true
                        break
                    }
                    val fillObj = try { JSONObject(filled) } catch (_: Exception) { null }
                    servers = fillObj?.optJSONArray("servers")
                    fillSaidWait = fillObj?.optString("status") == "wait"
                    if ((servers?.length() ?: 0) > 0 || !fillSaidWait) break
                    if (waitRetries >= 2) break
                    waitRetries++
                    delay(3000)
                }
                if (fillSaidWait && (servers == null || servers.length() == 0)) {
                    sourcesFillWaitTimedOut = true
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {}
        if (token == null || servers == null || servers.length() == 0) {
            if (sourcesFillFailed) {
                // The fill call never answered: transport-class, NOT a content
                // verdict — no memo, so the next pass re-walks.
                lastFailure = "Vidsrc: vidsrc.buzz sources fill failed for tmdb=$tmdb"
                return null
            }
            if (sourcesFillWaitTimedOut) {
                // The vault kept answering {"status":"wait"} past the retry
                // budget: the server list is still being prepared, so this is
                // a TRANSPORT-class stall like [sourcesFillFailed] — return
                // without a memo (a content verdict here would park a live
                // title as "known unavailable" for 15 minutes). No mint was
                // attempted and no server was judged, so nothing is proven
                // about the content itself.
                lastFailure = "Vidsrc: sources fill wait timeout for tmdb=$tmdb"
                return null
            }
            // Empty SSR or no mintable servers: transient response decoupled
            // from permanent negative caching. Do NOT write to missingTmdb
            // (15m TTL). Sibling mirrors and retry passes can still proceed.
            lastFailure = "Vidsrc: vidsrc.buzz has no mintable sources for tmdb=$tmdb (ssr empty)"
            return null
        }
        // Relative minted paths absolutize against the MINT origin (the relay
        // that produced them), never the embed origin — an OTA resolverConfig
        // may re-point embedBase and mintBase separately, and gluing a mint
        // path onto the embed host would 404 outside the default same-host
        // configuration.
        val mintOrigin = Regex("""https?://[^/]+""").find(mintBase)?.value ?: embedBase
        // LIVE (2026-10-16, tmdb 680 = Pulp Fiction): the FIRST listed server
        // mints a perfectly playable playlist whose segments are a TikTok ad
        // (p1x-ad-site-sign host, image/jpeg) while a later server is the real
        // TS — the app took the first hit and served 2s of ad PNGs. Before
        // returning a minted url, verify its first media unit is actually
        // media; a confirmed ad server is skipped for the next of the 3.
        var adClassifiedServers = 0
        var stubClassifiedServers = 0
        for (i in 0 until minOf(3, servers.length())) {
            val sv = servers.optJSONObject(i) ?: continue
            val ref = sv.optString("ref").takeIf { it.isNotBlank() } ?: continue
            val mintUrl = "$mintBase/api.php?a=play&ref=" +
                URLEncoder.encode(ref, "UTF-8") + "&t=" + URLEncoder.encode(token, "UTF-8")
            val mintJson = try {
                HttpClient.getText(mintUrl, referer = pageUrl)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: continue
            val streamUrl = try {
                JSONObject(mintJson).optString("url")
            } catch (_: Exception) { "" }
            if (streamUrl.isNotBlank()) {
                // The player sets video.src to this URL directly (ip-gated by
                // the mint) — no generate.php restamp; a token-bearing input
                // must flow through untouched (same doctrine as below).
                val absolute = if (streamUrl.startsWith("/")) "$mintOrigin$streamUrl" else streamUrl
                if (absolute.contains("_stream") || absolute.contains(".m3u8") ||
                    absolute.contains(".mpd") || absolute.contains(".mp4") ||
                    isDirectMediaUrl(absolute)
                ) {
                    when (classifyMintedStream(absolute, pageUrl)) {
                        MintHeadVerdict.AD -> {
                            // AD verdict needs POSITIVE payload evidence
                            // (image-type or PNG magic) — a transport miss
                            // on the probe is UNKNOWN and must NOT veto the
                            // mint (same transport-vs-content doctrine the
                            // failure branches above follow).
                            adClassifiedServers++
                            com.anonrode.downloader.util.DebugLog.resolve(
                                "Vidsrc: minted server #$i is an ad payload — skipping to next server for tmdb=$tmdb"
                            )
                            continue
                        }
                        MintHeadVerdict.STUB -> {
                            // A 1-byte/empty mpegurl answer is the vidsrc
                            // _stream decoy. Poison it so the engine's replay
                            // path cannot reuse it, then try the next server.
                            stubClassifiedServers++
                            com.anonrode.downloader.pipeline.ResolveCache.poison(absolute)
                            com.anonrode.downloader.util.DebugLog.resolve(
                                "Vidsrc: minted server #$i is a stub (1-byte/non-playlist) — poisoned and skipping for tmdb=$tmdb"
                            )
                            continue
                        }
                        // MEDIA confirmed, or UNKNOWN (probe transport miss):
                        // both keep today's return-the-mint behavior.
                        else -> return absolute
                    }
                }
            }
        }
        if (stubClassifiedServers > 0) {
            // Stub-only line-up: content-class but deliberately NOT memoized
            // (the mint rotates between passes, exactly like the ad list).
            lastFailure = "Vidsrc: stub-only server list (${stubClassifiedServers}x 1-byte/non-playlist) for tmdb=$tmdb"
            return null
        }
        if (adClassifiedServers > 0) {
            // Every minted server we could classify served an ad payload: an
            // ad-only list is a CONTENT verdict about this server line-up, but
            // deliberately NOT memoized (like the mint-refused verdict below) —
            // the ad rotation changes between passes.
            lastFailure = "Vidsrc: ad-only server list for tmdb=$tmdb"
            return null
        }
        lastFailure = "Vidsrc: vidsrc.buzz mint refused to produce a playable url for tmdb=$tmdb"
        return null
    }

    // ------------------------------------------------------------------
    // Minted-server head verification (ad-first mint skip, 2026-10-16).
    // ------------------------------------------------------------------

    internal enum class MintHeadVerdict { MEDIA, AD, MANIFEST, STUB, UNKNOWN }

    /** Derived clients keep the shared pool/DNS/cookies/SSRF floor but bound
     *  the WHOLE call: playlist fetch ≤ 2s, segment head ≤ 1.5s. HttpClient.probe
     *  does per-call bounding with call.timeout(), but its in-flight registry is
     *  private to HttpClient — a callTimeout-capped newBuilder() client bounds
     *  the call identically without modifying HttpClient. */
    private val mintPlaylistClient: OkHttpClient by lazy {
        HttpClient.shared.newBuilder()
            .callTimeout(2000, TimeUnit.MILLISECONDS)
            .build()
    }
    private val mintSegmentClient: OkHttpClient by lazy {
        HttpClient.shared.newBuilder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(1500, TimeUnit.MILLISECONDS)
            .callTimeout(1500, TimeUnit.MILLISECONDS)
            .build()
    }

    /** Bounded GET returning (code, Content-Type, first bytes): null on ANY
     *  transport miss (network, cooldown throw, unsafe target). Head reads
     *  use peekBody so a server that ignores Range on a big body is capped
     *  at [maxPeek] bytes regardless (the response is closed on return). */
    private fun rangedHeadGet(url: String, referer: String, client: OkHttpClient, maxPeek: Int, withRange: Boolean): Triple<Int, String, ByteArray>? {
        return try {
            val builder = Request.Builder()
                .url(HttpClient.safeUrl(url))
                .header("User-Agent", HttpClient.DEFAULT_UA)
            if (withRange) builder.header("Range", "bytes=0-512")
            if (referer.isNotBlank()) builder.header("Referer", referer)
            HttpClient.executeCancellable(client, builder.build()) { res ->
                // A sub-1KB announcement means a stub candidate: never pull a
                // multi-MB body just to classify a 1-byte decoy. 512B is
                // enough for "#EXTM3U" plus the first segment URI.
                val announced = res.body?.contentLength() ?: -1L
                val peekCap = if (announced in 0L..1023L) minOf(maxPeek.toLong(), 512L) else maxPeek.toLong()
                val bytes = try { res.peekBody(peekCap).bytes() } catch (_: Exception) { ByteArray(0) }
                Triple(res.code, res.header("Content-Type")?.lowercase() ?: "", bytes)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Payload verdict on ONE probed head. AD: PNG magic regardless of claimed
     * type, or an image-typed Content-Type over non-media bytes (the ad segments are
     * image/jpeg|png). MEDIA: MPEG-TS sync byte, EBML, MP4 'ftyp'@4. MANIFEST:
     * playlist/XML text ("#EXT…" or mpegurl/dash Content-Type). BODY EVIDENCE
     * IS CHECKED BEFORE HEADER CLAIMS: the megaplays.se preview proxy serves
     * REAL MPEG-TS with a lying "Content-Type: image/jpeg" (live 2026-10-15),
     * so a media magic must win over an image type. Everything else — HTML
     * interstitials, empty bodies, unknown types — is UNKNOWN, never silently
     * promoted to AD: skipping real servers on a probe hiccup trades proven
     * streams for a guess.
     */
    internal fun headPayloadVerdict(contentType: String, bytes: ByteArray): MintHeadVerdict {
        val ct = contentType.lowercase()
        if (bytes.size >= 4 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) return MintHeadVerdict.AD // PNG magic beats any header claim
        if (bytes.isNotEmpty() && bytes[0] == 0x47.toByte()) return MintHeadVerdict.MEDIA // MPEG-TS
        if (bytes.size >= 4 &&
            bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() &&
            bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte()
        ) return MintHeadVerdict.MEDIA // EBML/Matroska
        if (bytes.size >= 8 &&
            bytes[4] == 0x66.toByte() && bytes[5] == 0x74.toByte() &&
            bytes[6] == 0x79.toByte() && bytes[7] == 0x70.toByte()
        ) return MintHeadVerdict.MEDIA // MP4 'ftyp' at offset 4
        if (bytes.isEmpty()) return MintHeadVerdict.STUB
        if (ct.contains("mpegurl")) {
            // A real playlist ALWAYS starts with the #EXTM3U tag. A 1-byte
            // "1" answer under an mpegurl Content-Type is the vidsrc.buzz
            // _stream decoy, not a manifest.
            val text = String(bytes, Charsets.UTF_8).trimStart()
            return if (text.startsWith("#EXTM3U")) MintHeadVerdict.MANIFEST else MintHeadVerdict.STUB
        }
        if (ct.startsWith("image/")) return MintHeadVerdict.AD
        if (ct.contains("mp2t")) return MintHeadVerdict.MANIFEST
        val text = String(bytes, Charsets.UTF_8).trimStart()
        if (text.startsWith("#EXT")) return MintHeadVerdict.MANIFEST
        if (bytes.size < 4) return MintHeadVerdict.STUB // cannot be a media container
        if (ct.contains("dash") || text.startsWith("<?xml") || text.startsWith("<MPD")) return MintHeadVerdict.MANIFEST
        return MintHeadVerdict.UNKNOWN
    }

    /**
     * Verifies a minted stream url serves real media and not the ad: fetches
     * the url's head; for a playlist, walks to the FIRST media unit and
     * judges that head (ranged bytes=0-512 ≤ 1.5s). Latency bounded: ≤ 2s
     * playlist + ≤ 1.5s segment per mint. The first non-'#' line of a live
     * m3u8 is the segment URI; an in-manifest nested playlist (first line is
     * itself another m3u8, live-verified on vidsrc.buzz VOD) counts as
     * media-ish, per "m3u8/HTML-less binary beginning" evidence. */
    internal fun classifyMintedStream(streamUrl: String, referer: String): MintHeadVerdict {
        // megaplays.se/api/playlist.php is a working proxy (yt-dlp consumes
        // its playlist directly). Never apply the HLS stub heuristics there.
        if (streamUrl.contains("megaplays.se/api/playlist.php", ignoreCase = true)) {
            return MintHeadVerdict.MANIFEST
        }
        val head = rangedHeadGet(streamUrl, referer, mintPlaylistClient, 64 * 1024, withRange = false)
            ?: return MintHeadVerdict.UNKNOWN
        if (head.first !in 200..299) return MintHeadVerdict.UNKNOWN
        val verdict = headPayloadVerdict(head.second, head.third)
        if (verdict == MintHeadVerdict.STUB) return MintHeadVerdict.STUB
        if (verdict != MintHeadVerdict.MANIFEST) return verdict
        val text = String(head.third, Charsets.UTF_8)
        if (!text.trimStart().startsWith("#EXTM3U")) return MintHeadVerdict.STUB
        val firstLine = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: return MintHeadVerdict.STUB
        // "1" is not a segment URI; a real first media line carries a scheme,
        // a slash, a dot, or a query.
        val looksSegmented = firstLine.startsWith("http", ignoreCase = true) ||
            firstLine.startsWith("/") || firstLine.contains('/') ||
            firstLine.contains('.') || firstLine.contains('?')
        if (!looksSegmented) return MintHeadVerdict.STUB
        val segmentUrl = if (firstLine.startsWith("http", ignoreCase = true)) firstLine
        else HttpClient.safeResolveUri(streamUrl, firstLine)
        val seg = rangedHeadGet(segmentUrl, streamUrl, mintSegmentClient, 513, withRange = true)
            ?: return MintHeadVerdict.UNKNOWN
        if (seg.first !in 200..299) return MintHeadVerdict.UNKNOWN
        val segVerdict = headPayloadVerdict(seg.second, seg.third)
        if (segVerdict == MintHeadVerdict.STUB) return MintHeadVerdict.STUB
        // A nested playlist at the segment slot is the same media-ish
        // beginning class the direct case already accepts.
        return if (segVerdict == MintHeadVerdict.MANIFEST) MintHeadVerdict.MEDIA else segVerdict
    }

    override fun lastResolveFailure(): String? = lastFailure

    fun isKnownUnavailable(mediaType: String, tmdbId: String, season: String = "", episode: String = ""): Boolean {
        val cacheKey = "$mediaType:$tmdbId:$season:$episode"
        val cachedMissingAt = missingTmdb[cacheKey] ?: return false
        if (System.currentTimeMillis() - cachedMissingAt < MISSING_TMDB_TTL_MS) {
            return true
        }
        missingTmdb.remove(cacheKey, cachedMissingAt)
        return false
    }

    fun isKnownAvailable(mediaType: String, tmdbId: String, season: String = "", episode: String = ""): Boolean {
        val cacheKey = "$mediaType:$tmdbId:$season:$episode"
        val cachedAvailableAt = availableTmdb[cacheKey] ?: return false
        if (System.currentTimeMillis() - cachedAvailableAt < AVAILABLE_TMDB_TTL_MS) {
            return true
        }
        availableTmdb.remove(cacheKey, cachedAvailableAt)
        return false
    }

    fun markUnavailable(mediaType: String, tmdbId: String, season: String = "", episode: String = "") {
        val cacheKey = "$mediaType:$tmdbId:$season:$episode"
        missingTmdb[cacheKey] = System.currentTimeMillis()
    }

    fun markAvailable(mediaType: String, tmdbId: String, season: String = "", episode: String = "") {
        val cacheKey = "$mediaType:$tmdbId:$season:$episode"
        availableTmdb[cacheKey] = System.currentTimeMillis()
    }

    suspend fun checkAvailability(mediaType: String, tmdbId: String, tag: String? = "search"): Boolean {
        if (tmdbId.isBlank()) return false
        if (isKnownUnavailable(mediaType, tmdbId)) return false
        if (isKnownAvailable(mediaType, tmdbId)) return true
        // data.vidsrcme.ru dead-flag: log 29 burned 691 ConnectExceptions
        // (1-5s each) re-proving a TCP-refused host on every trending item.
        // While dead, fail open WITHOUT any request — availability is never
        // proven offline anyway, so the semantic is unchanged except the cost.
        if (!legacyApiUsable()) return true

        val cacheKey = "$mediaType:$tmdbId::"
        return try {
            val apiUrl = "${otaLegacyApiHost()}/api.php?type=$mediaType&tmdb=$tmdbId&stream_urls"
            val json = HttpClient.getText(apiUrl, referer = "https://cloudorchestranova.com/", tag = tag) ?: run {
                markLegacyApiDead()
                return true // Fail open on network/timeout error so offline/flaky connection doesn't drop items
            }
            val root = JSONObject(json)
            val sc = root.optInt("status_code", 0)
            val data = root.optJSONObject("data")
            if (sc == 404) {
                missingTmdb[cacheKey] = System.currentTimeMillis()
                false
            } else if (data != null || sc == 200) {
                availableTmdb[cacheKey] = System.currentTimeMillis()
                true
            } else {
                true
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            markLegacyApiDead()
            true // Fail open on transient error
        }
    }

    override fun canResolve(url: String): Boolean {
        return hostClaim(url, HOSTS)
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            var embedUrl = url.replace("nepu.to/", "nepu.gd/")
            // Garbage-TMDB guard (2026-10-20): a player MINT/PROXY endpoint is
            // not an embed page and carries no TMDB id. Parsing digits out of
            // `_stream?id=<opaque>` (or a stream-proxy token) fabricated
            // requests like embed/movie/97, /0, /1, id=0905801 — 12s of
            // attempt budget per junk id. Refuse before any regex runs.
            val inputPath = embedUrl.substringBefore('?').substringBefore('#').lowercase()
            if (inputPath.contains("/_stream") || inputPath.contains("/pl/api.php") ||
                embedUrl.contains("stream-proxy", ignoreCase = true)
            ) {
                lastFailure = "Vidsrc: input is a player mint/proxy endpoint, not an embed page — refusing to extract a TMDB id"
                return null
            }
            if (embedUrl.contains("nepu.gd/watch")) {
                // Watch pages require Cookie: hv=1 to bypass JS gate and hide
                // the player in iframe#playerFrame (or vidsrc/vsembed iframe).
                try {
                    val html = HttpClient.getText(
                        embedUrl,
                        referer = "https://nepu.gd/",
                        headers = mapOf("Cookie" to "hv=1")
                    )
                    if (!html.isNullOrBlank()) {
                        val doc = Jsoup.parse(html, embedUrl)
                        val iframe = doc.selectFirst("iframe#playerFrame")
                            ?: doc.selectFirst("iframe[src*=vidsrc]")
                            ?: doc.selectFirst("iframe[src*=vsembed]")
                            ?: doc.selectFirst("iframe[src]")
                        if (iframe != null && iframe.attr("src").isNotBlank()) {
                            embedUrl = HttpClient.safeResolveUri(embedUrl, iframe.attr("src"))
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {}
            }

            // vsembed.ru embeds relay through an internal data-api (vs_src.php)
            // which mints the signed cloudorchestranova player URL.
            if (embedUrl.contains("vsembed.ru")) {
                try {
                    val vHtml = HttpClient.getText(embedUrl, referer = "https://vidsrc.mov/")
                    if (!vHtml.isNullOrBlank()) {
                        val apiMatcher = Pattern.compile("""data-api=["']([^"']+)["']""").matcher(vHtml)
                        val apiPath = if (apiMatcher.find()) apiMatcher.group(1) else null
                        if (!apiPath.isNullOrBlank()) {
                            val fullApi = HttpClient.safeResolveUri(embedUrl, apiPath.replace("&amp;", "&"))
                            val apiJson = HttpClient.getText(fullApi, referer = embedUrl)
                            if (!apiJson.isNullOrBlank()) {
                                val src = JSONObject(apiJson).optString("src")
                                if (src.startsWith("http")) embedUrl = src
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {}
            }

            // 2embed wrapper unwrap (contains vidsrc.buzz / 2embed player)
            if (embedUrl.contains("2embed.cc") || embedUrl.contains("vidsrc.buzz")) {
                try {
                    val twoHtml = HttpClient.getText(embedUrl, referer = "https://nepu.gd/")
                    if (!twoHtml.isNullOrBlank()) {
                        val twoDoc = Jsoup.parse(twoHtml, embedUrl)
                        val buzzIframe = twoDoc.selectFirst("iframe[src*=vidsrc]")
                            ?: twoDoc.selectFirst("iframe[src*=vsembed]")
                            ?: twoDoc.selectFirst("iframe[src]")
                        if (buzzIframe != null && buzzIframe.attr("src").isNotBlank()) {
                            embedUrl = HttpClient.safeResolveUri(embedUrl, buzzIframe.attr("src"))
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {}
            }

            // The embed URL carries the TMDB id (and season/episode for TV).
            var tmdb: String? = null
            var season: String? = null
            var episode: String? = null
            val m = TMDB_PATTERN.matcher(embedUrl)
            if (m.find()) {
                tmdb = m.group(1)
                season = m.group(2)
                episode = m.group(3)
            } else {
                val mm = MULTIEMBED_PATTERN.matcher(embedUrl)
                if (mm.find()) {
                    tmdb = mm.group(1)
                    season = mm.group(2)
                    episode = mm.group(3)
                }
            }

            if (tmdb.isNullOrBlank()) {
                lastFailure = "Vidsrc: no TMDB id pattern matched in $embedUrl"
                return null
            }
            // TMDB ids are positive decimal, no leading zero. A matched token
            // failing this is an opaque id/token, not a title — refuse instead
            // of minting a nonsense embed request.
            val cleanTmdb = tmdb.trim()
            if (cleanTmdb.isEmpty() || cleanTmdb.length > 9 || !cleanTmdb.all { it in '0'..'9' } ||
                cleanTmdb == "0" || (cleanTmdb.length > 1 && cleanTmdb.startsWith("0"))
            ) {
                lastFailure = "Vidsrc: implausible TMDB id '$cleanTmdb' in $embedUrl — refusing"
                return null
            }
            tmdb = cleanTmdb
            val mediaType = if (embedUrl.contains("/tv/") || embedUrl.contains("/serie/") || embedUrl.contains("embedtv") || season != null) "tv" else "movie"
            val cacheKey = "$mediaType:$tmdb:${season.orEmpty()}:${episode.orEmpty()}"
            val mirrorHost = HttpClient.parsedHost(embedUrl) ?: ""
            val mirrorKey = if (mirrorHost.isNotBlank()) "$mirrorHost:$cacheKey" else cacheKey

            val cachedMissingAt = missingTmdb[cacheKey]
            if (cachedMissingAt != null) {
                if (System.currentTimeMillis() - cachedMissingAt < MISSING_TMDB_TTL_MS) {
                    lastFailure = "Vidsrc: known unavailable tmdb=$tmdb"
                    return null
                }
                missingTmdb.remove(cacheKey, cachedMissingAt)
            }
            val cachedTransientAt = transientMissingTmdb[mirrorKey]
            if (cachedTransientAt != null) {
                if (System.currentTimeMillis() - cachedTransientAt < TRANSIENT_BACKOFF_TTL_MS) {
                    lastFailure = "Vidsrc: mirror $mirrorHost in transient backoff for tmdb=$tmdb"
                    return null
                }
                transientMissingTmdb.remove(mirrorKey, cachedTransientAt)
            }
            // LIVE PATH FIRST — vidsrc.buzz player's own relay, verified live
            // 2026-10-15 (~4-10s to a playable playlist; page carries the SSR
            // servers + signed token, /pl/api.php mints, /_stream serves HLS).
            // The dead legacy API below only runs when the live path cannot.
            resolveViaBuzz(mediaType, tmdb, season, episode)?.let { minted ->
                availableTmdb[cacheKey] = System.currentTimeMillis()
                transientMissingTmdb.remove(mirrorKey)
                return minted
            }
            // Transient backoff isolated per-mirror so sibling mirrors in a multi-mirror race proceed
            transientMissingTmdb[mirrorKey] = System.currentTimeMillis()

            val legacyApi = otaLegacyApiHost()
            val apiUrl = if (mediaType == "tv") {
                val s = season ?: "1"
                val ep = episode ?: "1"
                "$legacyApi/api.php?type=tv&tmdb=$tmdb&season=$s&episode=$ep&stream_urls"
            } else {
                "$legacyApi/api.php?type=movie&tmdb=$tmdb&stream_urls"
            }

            // Dead-flag (TCP-refused host) + origin cooldown both cut the walk
            // short WITHOUT a request. The live path's own failure reason is
            // preserved above this legacy gate — it is the informative one.
            if (!legacyApiUsable() || com.anonrode.downloader.data.net.HttpClient.isOriginCoolingDown(apiUrl)) {
                if (lastFailure == null) {
                    lastFailure = "Vidsrc: legacy API skipped (dead-flagged/cooldown) for tmdb=$tmdb"
                }
                return null
            }
            val json = HttpClient.getText(apiUrl, referer = "https://cloudorchestranova.com/") ?: run {
                markLegacyApiDead()
                lastFailure = "Vidsrc: API request failed for $apiUrl"
                return null
            }
            val root = JSONObject(json)
            val data = root.optJSONObject("data") ?: run {
                val sc = root.optInt("status_code", 0)
                if (sc == 404) missingTmdb[cacheKey] = System.currentTimeMillis()
                lastFailure = "Vidsrc: API returned status $sc for tmdb=$tmdb"
                return null
            }
            availableTmdb[cacheKey] = System.currentTimeMillis()
            transientMissingTmdb.remove(mirrorKey)
            val streamUrl: String = when (val su = data.opt("stream_urls")) {
                is JSONArray -> if (su.length() > 0) su.getString(0) else null
                is String -> {
                    // Encrypted: the key lives in a freshly-built wasm module
                    // that rotates every ~5 minutes, so fetch it and extract
                    // the key from its own instruction stream.
                    val wasmUrl = root.optJSONObject("vs")?.optString("wasm_url") ?: run {
                        lastFailure = "Vidsrc: missing wasm_url in vs"
                        return null
                    }
                    val wasm = HttpClient.get(wasmUrl, referer = "https://cloudorchestranova.com/").use { res ->
                        if (res.isSuccessful) HttpClient.cappedBytes(res) else null
                    } ?: run {
                        lastFailure = "Vidsrc: failed to fetch wasm binary"
                        return null
                    }
                    val key = VidsrcWasmCrypto.extractKey(wasm) ?: run {
                        lastFailure = "Vidsrc: failed to extract wasm key"
                        return null
                    }
                    VidsrcWasmCrypto.decrypt(su, key).firstOrNull()
                }
                else -> null
            } ?: run {
                lastFailure = "Vidsrc: no valid stream_urls extracted from data"
                return null
            }

            // Playlist URLs are CDN-gated by an IP-bound JWT issued by the
            // origin's generate.php; without it the CDN answers 401. A URL that
            // already carries a token is authoritative — stripping and
            // re-stamping rotates a valid token into a dead one.
            if (streamUrl.contains("token=")) return streamUrl
            val om = ORIGIN_PATTERN.matcher(streamUrl)
            val origin = if (om.find()) om.group() else return null
            val token = HttpClient.getText(
                "$origin/generate.php",
                referer = "$origin/",
                headers = mapOf("Origin" to origin)
            )?.trim().orEmpty()
            // Empty means generate.php refused us (rate-limit/window) — the
            // tokenless master is a guaranteed CDN 401, so fail this candidate
            // outright and let another mirror win instead of handing the player
            // a URL it cannot open.
            if (token.isEmpty()) {
                lastFailure = "Vidsrc: generate.php returned empty token for $origin"
                return null
            }
            return if (streamUrl.contains("__TOKEN__")) streamUrl.replace("__TOKEN__", token)
            else streamUrl + (if (streamUrl.contains("?")) "&" else "?") + "token=$token"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "Vidsrc: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 6. LightDLResolver
// -------------------------------------------------------------
object LightDLResolver : BaseResolver {
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        if (url.contains("/api/download/")) return false
        return hostClaim(url, listOf("lightdl.cc"))
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            val code = url.trimEnd('/').substringAfterLast('/')
            if (code.isBlank()) {
                lastFailure = "LightDL: code blank in $url"
                return null
            }
            val fileJson = HttpClient.getText("https://lightdl.cc/api/files/code/$code", referer = url) ?: run {
                lastFailure = "LightDL: file query failed for code $code"
                return null
            }
            val fileId = JSONObject(fileJson).optJSONObject("file")?.optString("id") ?: run {
                lastFailure = "LightDL: file id not found in response"
                return null
            }

            val req = Request.Builder()
                .url("https://lightdl.cc/api/files/$fileId/download-token")
                .header("User-Agent", HttpClient.DEFAULT_UA)
                .header("Referer", url)
                .post(FormBody.Builder().build())
                .build()

            HttpClient.executeCancellable(HttpClient.shared, req) use@{ res ->
                if (!res.isSuccessful) {
                    lastFailure = "LightDL: download-token HTTP ${res.code}"
                    return null
                }
                val body = HttpClient.cappedText(res) ?: run {
                    lastFailure = "LightDL: empty download-token body"
                    return null
                }
                val obj = JSONObject(body)
                val dlUrl = obj.optString("downloadUrl")
                if (dlUrl.isNotBlank()) return dlUrl
                lastFailure = "LightDL: no downloadUrl in token response"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "LightDL: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 7. FivePlayResolver
// -------------------------------------------------------------
object FivePlayResolver : BaseResolver {
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        return hostClaim(url, listOf("5play.cc"))
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            val html = HttpClient.getText(url, referer = "https://dramakey.cc/") ?: run {
                lastFailure = "FivePlay: empty HTTP response for $url"
                return null
            }
            val direct = extractM3u8FromHtml(html) ?: extractMp4FromHtml(html)
            if (!direct.isNullOrBlank()) return direct

            val unpacked = JsUnpacker.unpack(html)
            if (!unpacked.isNullOrBlank()) {
                val unpDirect = extractM3u8FromHtml(unpacked) ?: extractMp4FromHtml(unpacked)
                if (!unpDirect.isNullOrBlank()) return unpDirect
            }
            lastFailure = "FivePlay: no stream extracted from HTML (len=${html.length})"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "FivePlay: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 8. VikingFileResolver
// -------------------------------------------------------------
object VikingFileResolver : BaseResolver {
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        val low = url.lowercase()
        return hostClaim(url, listOf("vikingfile.com")) && (low.contains("/d/") || (!low.endsWith(".mp4") && !low.endsWith(".mkv") && !low.endsWith(".m3u8")))
    }

    // The Sep-2 hardening blanket-rejected every redirect whose Location sat
    // on an R2 storage host, because the original misdirect (an unsigned
    // storage URL) answered 401 and burned 3MB bodies. vikingfile has since
    // made the presigned R2 URL the REAL serving path for /d/ links
    // (live-verified: 302 → R2 → 206 video/matroska, MKV magic), so the
    // hostname alone is no longer evidence of a misdirect. Decide by
    // probing, not by name: accept the Location only when it actually
    // serves media bytes (see HttpClient.probeTerminal). A 401/HTML target
    // — the original misdirect — still fails here and is rejected.
    // (Formerly a hand-copied no-redirect probe; unified onto probeTerminal
    // so all terminal checks share ONE accept matrix, ONE timeout, and the
    // cancellable in-flight registry — the old copy was raw execute(): not
    // cancellable by pause and had no call timeout.)
    private fun probeStorageLocation(loc: String): String? {
        val target = HttpClient.safeUrl(loc)
        val tp = HttpClient.probeTerminal(target)
        if (tp != null && tp.totalBytes != null) {
            com.anonrode.downloader.util.DebugLog.resolve(
                "VikingFileResolver: storage Location serves media (${tp.code}, ct=${tp.contentType}, size=${tp.totalBytes}) — accepting as direct file"
            )
            return target
        }
        com.anonrode.downloader.util.DebugLog.resolve(
            "VikingFileResolver: rejected misdirect to storage backend (probe=${tp?.code ?: "network-fail"}): ${loc.take(120)}"
        )
        return null
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            // vikingfile.com has TWO URL shapes:
            //
            // 1. /f/<id>  → 302 redirect to /d/<token>/<filename>.mkv
            //    The /f/<id> page itself is just a token-mint; the actual
            //    file is at the /d/<token>/<file> URL. Live-verified: the
            //    page returns 302 with Location: /d/ZlVDRbze6i/...mkv
            //
            // 2. /d/<token>/<file>  → 302 redirect to Cloudflare R2 presigned URL
            //    which serves 206 Partial Content on a Range probe.
            //    Resolving follows hops until reaching the terminal R2 storage URL,
            //    preventing 16 parallel download sockets from hammering Vikingfile's
            //    Nginx frontend with 429 Too Many Requests.
            var curr = url
            for (hop in 1..5) {
                val tp = HttpClient.probeTerminal(curr, referer = "https://www.naijavault.com/")
                if (tp == null) {
                    lastFailure = "VikingFile: probeTerminal failed for $curr"
                    return null
                }
                // Case (a): curr is already the direct storage/media URL (e.g. Cloudflare R2)
                if (tp.totalBytes != null) {
                    val lowCurr = curr.lowercase()
                    if (lowCurr.contains("r2.cloudflarestorage.com") ||
                        lowCurr.contains(".r2.dev/") ||
                        lowCurr.contains("cloudflarestorage.com/")) {
                        val verified = probeStorageLocation(curr)
                        if (verified != null) return verified
                    }
                    if (tp.code in 200..206) {
                        val isMediaContent = tp.contentType?.contains("video") == true ||
                            tp.contentType?.contains("octet-stream") == true ||
                            tp.contentType?.contains("matroska") == true ||
                            isDirectMediaUrl(curr)
                        if (!hostClaim(curr, listOf("vikingfile.com")) || isMediaContent) {
                            com.anonrode.downloader.util.DebugLog.resolve(
                                "VikingFileResolver: $curr is the direct file (Range probe ${tp.code}, size=${tp.totalBytes}) — returning as-is"
                            )
                            return curr
                        }
                    }
                }
                // Case (b): 30x redirect
                val rawLoc = tp.location
                if (tp.code in 301..308 && !rawLoc.isNullOrBlank()) {
                    val loc = HttpClient.safeResolveUri(curr, rawLoc)
                    val lowLoc = loc.lowercase()
                    if (lowLoc.contains("r2.cloudflarestorage.com") ||
                        lowLoc.contains(".r2.dev/") ||
                        lowLoc.contains("cloudflarestorage.com/")) {
                        val verified = probeStorageLocation(loc)
                        if (verified != null) return verified
                    }
                    com.anonrode.downloader.util.DebugLog.resolve(
                        "VikingFileResolver: followed ${tp.code} → ${loc.take(120)}"
                    )
                    curr = HttpClient.safeUrl(loc)
                    continue
                }
                // Case (c): HTML page that doesn't redirect
                val html = HttpClient.getText(curr, referer = "https://www.naijavault.com/") ?: run {
                    lastFailure = "VikingFile: empty HTTP response at hop $hop for $curr"
                    return null
                }
                val m = Pattern.compile("""(?:window\.location|location\.href)\s*=\s*["']([^"']+)["']""").matcher(html)
                if (m.find()) {
                    val loc = m.group(1) ?: run {
                        lastFailure = "VikingFile: location match group 1 is null at hop $hop"
                        return null
                    }
                    val lowLoc = loc.lowercase()
                    if (lowLoc.contains("r2.cloudflarestorage.com") ||
                        lowLoc.contains(".r2.dev/") ||
                        lowLoc.contains("cloudflarestorage.com/")) {
                        val verified = probeStorageLocation(loc)
                        if (verified != null) return verified
                    }
                    curr = HttpClient.safeUrl(HttpClient.safeResolveUri(curr, loc))
                    continue
                }
                val direct = extractMp4FromHtml(html) ?: extractM3u8FromHtml(html)
                if (!direct.isNullOrBlank()) return direct
                lastFailure = "VikingFile: no redirect or media found at hop $hop ($curr)"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "VikingFile: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 9. LulaCloudResolver
// -------------------------------------------------------------
object LulaCloudResolver : BaseResolver {
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        return hostClaim(url, listOf("lulacloud.com"))
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            // lulacloud.com serves a broken CA chain (live-verified verify
            // code 20) — permissive client, same bounded scope as the
            // downloadwella family.
            val html = HttpClient.getText(url, referer = "https://www.naijavault.com/", permissive = true) ?: run {
                lastFailure = "LulaCloud: empty HTTP response for $url"
                return null
            }
            val m = Pattern.compile("""(?:window\.location(?:\.href)?|location\.href)\s*=\s*["']([^"']+)["']""").matcher(html)
            if (m.find()) {
                return m.group(1)
            }
            val direct = extractMp4FromHtml(html) ?: extractM3u8FromHtml(html)
            if (!direct.isNullOrBlank()) return direct

            val doc = Jsoup.parse(html, url)
            val btn = doc.selectFirst("a.download-btn, a[href*='download'], a[href*='/d/']")
            if (btn != null) {
                val href = btn.attr("abs:href")
                if (href.isNotBlank() && href != url) return href
            }

            val unpacked = JsUnpacker.unpack(html)
            if (!unpacked.isNullOrBlank()) {
                val unpDirect = extractMp4FromHtml(unpacked) ?: extractM3u8FromHtml(unpacked)
                if (!unpDirect.isNullOrBlank()) return unpDirect
            }
            lastFailure = "LulaCloud: no media or redirect found in HTML (len=${html.length})"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "LulaCloud: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 10. DramaGatewayResolver
// -------------------------------------------------------------
object DramaGatewayResolver : BaseResolver {
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        val low = url.lowercase()
        return hostClaim(url, listOf("dramarain.com", "dramakey.cc")) && low.contains("/download")
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            val host = HttpClient.safeHost(url, "dramarain.com")
            val html = HttpClient.getText(url, referer = "https://$host/") ?: run {
                lastFailure = "DramaGateway: empty HTTP response for $url"
                return null
            }
            val m = Pattern.compile("""(?:window\.location(?:\.href)?|location\.href)\s*=\s*["']([^"']+)["']""").matcher(html)
            if (m.find()) {
                val dest = m.group(1)
                if (!dest.isNullOrBlank()) {
                    val clean = if (dest.contains("?preview")) dest.substringBefore("?preview") else dest
                    return clean
                }
            }

            val doc = Jsoup.parse(html, url)
            val btn = doc.selectFirst("a.download-btn, a[href*='waffi'], a[href*='download'], a[href*='stream']")
            if (btn != null) {
                val href = btn.attr("abs:href")
                if (href.isNotBlank() && href != url) {
                    val clean = if (href.contains("?preview")) href.substringBefore("?preview") else href
                    return clean
                }
            }
            val lockerAnchor = doc.select("a[href]").map { it.attr("abs:href") }
                .firstOrNull { LinkResolver.isKnownLockerHost(it) && it != url }
            if (!lockerAnchor.isNullOrBlank()) {
                val clean = if (lockerAnchor.contains("?preview")) lockerAnchor.substringBefore("?preview") else lockerAnchor
                return clean
            }

            lastFailure = "DramaGateway: no window.location or download link found in HTML (len=${html.length})"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "DramaGateway: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 11. NaijaVaultGatewayResolver
// -------------------------------------------------------------
object NaijaVaultGatewayResolver : BaseResolver {
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        val low = url.lowercase()
        return hostClaim(url, listOf("naijavault.com")) && (low.contains("/dl-") || low.contains("/temp/") || low.contains("/sdm_downloads/"))
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            val html = HttpClient.getText(url, referer = "https://www.naijavault.com/") ?: run {
                lastFailure = "NaijaVault: empty HTTP response for $url"
                return null
            }
            val soup = Jsoup.parse(html, url)
            val btn = soup.selectFirst("a.download-btn, a.btn-download, a.sdm_download, a[href*='sdm_process_download'], a[href*='vikingfile'], a[href*='lulacloud'], a[href*='loadedfiles'], a[href*='downloadwella'], a[href*='wetafiles'], a[href*='pixeldrain'], a[href*='wildshare']")
            if (btn != null) {
                val href = btn.attr("abs:href").ifBlank { btn.attr("href") }
                if (href.isNotBlank()) {
                    // Re-entry budget: these registry callbacks used to pass
                    // NEITHER depth nor visitedSites (depth defaulted to 0),
                    // zeroing the OTA handoff chain's guard on every turn
                    // through this gateway — the 2026-10-15 recursive-crash
                    // class (see ResolveLineage). depth + 1 keeps the chain
                    // monotone at the exact reset point; the ambient lineage
                    // adds the visited-set/counter backstops.
                    if (href.contains("sdm_process_download") || href.contains("/sdm_downloads/")) {
                        val redirected = HttpClient.probeTerminal(href, referer = url)?.location ?: href
                        if (redirected != href && redirected.isNotBlank()) {
                            val cracked = ResolverRegistry.resolve(redirected, quality, depth + 1) ?: redirected
                            return cracked
                        }
                    }
                    val cracked = ResolverRegistry.resolve(href, quality, depth + 1) ?: href
                    return cracked
                }
            }
            val lockerAnchor = soup.select("a[href]").map { it.attr("abs:href").ifBlank { it.attr("href") } }
                .firstOrNull { LinkResolver.isKnownLockerHost(it) && it != url }
            if (!lockerAnchor.isNullOrBlank()) {
                return ResolverRegistry.resolve(lockerAnchor, quality, depth + 1) ?: lockerAnchor
            }

            val directMedia = soup.select("a[href]").map { it.attr("abs:href").ifBlank { it.attr("href") } }
                .firstOrNull { isDirectMediaUrl(it) }
            if (!directMedia.isNullOrBlank()) return directMedia

            val m = Pattern.compile("""var\s+downloadURL\s*=\s*["']([^"']+)["']""").matcher(html)
            if (m.find()) {
                val u = m.group(1)
                if (!u.isNullOrBlank()) return u
            }
            lastFailure = "NaijaVault: no download anchor or var downloadURL found in HTML (len=${html.length})"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "NaijaVault: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// NpDownloaderGatewayResolver
// -------------------------------------------------------------
object NpDownloaderGatewayResolver : BaseResolver {
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        val low = url.lowercase()
        val isDirectFile = low.contains("/file/") || low.contains("/d/") ||
            low.endsWith(".mkv") || low.endsWith(".mp4") || low.endsWith(".avi")
        if (isDirectFile) return false
        return (hostClaim(url, listOf("np-downloader.com", "vdl.np-downloader.com"))) ||
               (low.contains("np-downloader.com") && low.contains("/sdm_downloads/"))
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            // A gateway page is a directory hop, not media: cap the whole hop
            // at 8s so a dead chain hands the slot to the next mirror instead
            // of eating the registry's 12s attempt budget (live: 16s dead-end).
            val deadlineMs = System.currentTimeMillis() + 10_000L
            val boundedClient = HttpClient.shared.newBuilder()
                .callTimeout(8, TimeUnit.SECONDS)
                .build()
            val html = HttpClient.getTextWithClient(
                boundedClient, url, referer = url, tag = "np-downloader"
            ) ?: run {
                lastFailure = "NpDownloader: empty HTTP response for $url"
                return null
            }
            val soup = Jsoup.parse(html, url)
            val sdmAnchor = soup.selectFirst("a.sdm_download, a[href*='wildshare'], a[href*='downloadwella'], a[href*='vikingfile'], a[href*='loadedfiles']")
            if (sdmAnchor != null) {
                val href = sdmAnchor.attr("abs:href").takeIf { it.isNotBlank() }
                if (href != null && href != url) {
                    if (System.currentTimeMillis() > deadlineMs) {
                        lastFailure = "NpDownloader: gateway budget exhausted before target hop"
                        return null
                    }
                    return if (depth < ResolverRegistry.RESOLVE_DEPTH_LIMIT) {
                        ResolverRegistry.resolve(href, quality, depth + 1)
                            ?: if (isDirectMediaUrl(href) || LinkResolver.isProvablyDirectFile(href)) href else null
                    } else if (isDirectMediaUrl(href) || LinkResolver.isProvablyDirectFile(href)) href else null
                }
            }
            val anyLocker = soup.select("a[href]").map { it.attr("abs:href") }
                .firstOrNull { LinkResolver.isKnownLockerHost(it) && it != url }
            if (!anyLocker.isNullOrBlank()) {
                if (System.currentTimeMillis() > deadlineMs) {
                    lastFailure = "NpDownloader: gateway budget exhausted before mirror hop"
                    return null
                }
                return if (depth < ResolverRegistry.RESOLVE_DEPTH_LIMIT) {
                    ResolverRegistry.resolve(anyLocker, quality, depth + 1)
                        ?: if (isDirectMediaUrl(anyLocker) || LinkResolver.isProvablyDirectFile(anyLocker)) anyLocker else null
                } else if (isDirectMediaUrl(anyLocker) || LinkResolver.isProvablyDirectFile(anyLocker)) anyLocker else null
            }
            findDirectMediaUrl(html)?.let { return it }
            lastFailure = "NpDownloader: no target anchor found in HTML (len=${html.length})"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "NpDownloader: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 12. EmbedResolver
// -------------------------------------------------------------
object EmbedResolver : BaseResolver {
    private val KNOWN = listOf("megaplay.buzz", "megaplay.cc", "tamilembed.lol", "embedsito.com")
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        return hostClaim(url, KNOWN)
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            val html = HttpClient.getText(url, referer = url) ?: run {
                lastFailure = "EmbedResolver: empty HTTP response for $url"
                return null
            }
            val direct = extractM3u8FromHtml(html) ?: extractMp4FromHtml(html)
            if (!direct.isNullOrBlank()) return direct

            val unpacked = JsUnpacker.unpack(html)
            if (!unpacked.isNullOrBlank()) {
                val unpDirect = extractM3u8FromHtml(unpacked) ?: extractMp4FromHtml(unpacked)
                if (!unpDirect.isNullOrBlank()) return unpDirect
            }
            lastFailure = "EmbedResolver: no stream extracted from HTML (len=${html.length})"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "EmbedResolver: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 13. PlutoMoviesResolver
// -------------------------------------------------------------
object PlutoMoviesResolver : BaseResolver {
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        // /series/ episode pages carry the same download link as movies —
        // without them the Vincenzo-style episode taps failed with
        // "resolver chain EMPTY" (live-verified 2026-08-21).
        return hostClaim(url, listOf(
            "dl.plutomovies.com",
            "plutomovies.com/movie/",
            "plutomovies.com/series/"
        ))
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            val html = HttpClient.getText(url, referer = "https://plutomovies.com/") ?: run {
                lastFailure = "PlutoMovies: empty HTTP response for $url"
                return null
            }
            val m = Pattern.compile("""location\.href\s*=\s*['"](https?://[^'"]+)['"]""").matcher(html)
            if (m.find()) {
                val dest = m.group(1) ?: ""
                if (dest.isNotBlank() && !dest.equals(url, ignoreCase = true) && !isRootLockerDomain(dest)) return dest
            }

            val soup = Jsoup.parse(html, url)
            // /series/ episode pages embed the download as a plain
            // dl.plutomovies.com anchor (live-verified: Vincenzo S01E05..E20)
            val dlAnchor = soup.selectFirst("a[href*='dl.plutomovies.com']")
            if (dlAnchor != null) {
                val href = dlAnchor.attr("abs:href")
                if (href.isNotBlank() && !href.equals(url, ignoreCase = true)) {
                    // The dl anchor often lands on an intermediate page whose
                    // own locker/download link is the real file — descend a
                    // bounded depth (monolith parity: PlutoMoviesResolver
                    // follows the anchor chain).
                    if (depth < 3) {
                        val nested = resolve(href, quality, depth + 1)
                        if (!nested.isNullOrBlank()) return nested
                    }
                    if (isDirectMediaUrl(href) || LinkResolver.isProvablyDirectFile(href)) return href
                    lastFailure = "PlutoMovies: intermediate page did not resolve to direct media: $href"
                    return null
                }
            }
            val btn = soup.selectFirst("a[href*='kissorgrab.com'], a[href*='/download/'], a.download-btn, a[href*='download'], a[href*='.mp4'], a[href*='.mkv']")
            if (btn != null) {
                val href = btn.attr("abs:href")
                if (href.isNotBlank() && !href.equals(url, ignoreCase = true) && !isRootLockerDomain(href)) return href
            }

            findDirectMediaUrl(html)?.let { return it }

            // Directory pages expose no download link at all — only links to
            // child /series/ pages. Descend into the most specific child and
            // let the registry recursion walk hub → season → episode page →
            // dl anchor instead of failing cleanly (live-verified 2026-08-22:
            // /series/324767/all-american-2018-tv-series → season-7 listing →
            // s07-e01 → dl.plutomovies.com/...-mkv).
            val children = soup.select("a[href*='/series/']")
                .map { it.attr("abs:href").substringBefore('#') }
                .filter { href -> href.isNotBlank() && !href.equals(url, ignoreCase = true) }
                .distinct()
            if (children.isNotEmpty()) {
                val episodeish = children.firstOrNull { child ->
                    child.contains(Regex("""s\d{1,2}[-_]?e\d{1,2}|episode-\d{1,3}""", RegexOption.IGNORE_CASE))
                }
                val seasonish = children.firstOrNull { child ->
                    child.contains(Regex("""season-\d{1,2}""", RegexOption.IGNORE_CASE))
                }
                val targetChild = episodeish ?: seasonish ?: children.first()
                if (depth < ResolverRegistry.RESOLVE_DEPTH_LIMIT) {
                    return resolve(targetChild, quality, depth + 1)
                }
                lastFailure = "PlutoMovies: recursion depth limit reached descending series directory: $targetChild"
                return null
            }
            lastFailure = "PlutoMovies: no dl anchor or direct media found in HTML (len=${html.length})"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "PlutoMovies: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 14. DownloadwellaResolver
// -------------------------------------------------------------
object DownloadwellaResolver : BaseResolver {
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        // kissorgrab.com/dl/ and downloadwella/wetafiles /d/ URLs are
        // already-cracked direct media files (live-verified: HTTP 206, MKV/MP4).
        // The resolver must NOT claim them — POSTing a web form to a direct file
        // returns HTTP 405/400 and kills the entire download chain.
        val lower = url.lowercase()
        if (lower.contains("kissorgrab.com") && lower.contains("/dl/")) return false
        if ((lower.contains("downloadwella.com") || lower.contains("wetafiles.com")) && lower.contains("/d/")) return false
        return hostClaim(url, listOf("downloadwella.com", "wetafiles.com", "kissorgrab.com"))
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            // Permissive client: wetafiles.com omits its TLS intermediate and
            // kissorgrab.com serves an invalid cert — strict verification
            // fails every crack on those hosts (live-verified). Scoped here
            // only; the shared client stays strict for everything else.
            val html = HttpClient.getText(url, referer = url, permissive = true) ?: run {
                lastFailure = "Downloadwella: empty HTTP response for $url"
                return null
            }
            val doc = Jsoup.parse(html, url)
            val cfg = DynamicRulesManager.getResolverConfig("downloadwella")
            val formSelector = cfg?.optString("formActionSelector")
                ?.takeIf { it.isNotBlank() && it.length <= 600 }
                ?: "form"
            val formEl = try {
                doc.selectFirst(formSelector)
            } catch (_: Exception) {
                null
            }

            // Movie pages of this locker family sometimes render with NO form
            // at all (live-verified 2026-09-11: downloadwella.com/9mktxnflcqtc/
            // …DC.(THENKIRI.COM).mkv.html?preview has zero <form>/<input>
            // tags, while series pages carry the classic F1 form). The server
            // still cracks them when the standard F1 body is POSTed with the
            // file id taken from the URL's first path segment — so fall back
            // to the synthetic body instead of returning null; the response
            // scan below finds the /d/ direct anchor either way.
            val formAction = formEl?.attr("abs:action").orEmpty().ifBlank { url }
            fun configuredDirect(bodyText: String): String? {
                val tokenRegex = cfg?.optString("tokenRegex")?.takeIf { it.isNotBlank() && it.length <= 600 } ?: return null
                return try {
                    val matcher = Pattern.compile(tokenRegex, Pattern.CASE_INSENSITIVE).matcher(bodyText)
                    if (!matcher.find()) return null
                    for (group in 1..matcher.groupCount()) {
                        val value = matcher.group(group)?.replace("\\\\/", "/")?.trim().orEmpty()
                        if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
                            val safe = HttpClient.safeUrl(HttpClient.safeResolveUri(url, value))
                            if (isDirectMediaUrl(safe) || safe.contains("/d/") || safe.contains("/token/download/")) return safe
                        }
                    }
                    null
                } catch (_: Exception) {
                    null
                }
            }

            // LIVE (2026-08): the server rejects the POST when method_free is
            // forced to "Free Download" — submit every form input verbatim
            // (including method_free="" as rendered), exactly like a browser.
            val formBuilder = FormBody.Builder()
            var hasInputs = false
            if (formEl != null) {
                for (inp in formEl.select("input[name]")) {
                    formBuilder.add(inp.attr("name"), inp.attr("value"))
                    hasInputs = true
                }
            }
            if (!hasInputs) {
                val fileId = try {
                    URI(url).path.trim('/').split('/').firstOrNull { it.isNotBlank() }
                } catch (_: Exception) {
                    null
                }
                if (fileId.isNullOrBlank()) {
                    lastFailure = "Downloadwella: fileId could not be extracted from path $url"
                    return null
                }
                formBuilder.add("op", "download2")
                formBuilder.add("id", fileId)
                formBuilder.add("rand", "")
                formBuilder.add("referer", url)
                formBuilder.add("method_free", "")
                formBuilder.add("method_premium", "")
            }

            val form = formBuilder.build()
            val req = Request.Builder()
                .url(formAction)
                .header("User-Agent", HttpClient.DEFAULT_UA)
                .header("Referer", url)
                .post(form)
                .build()

            HttpClient.executeCancellable(HttpClient.permissiveClient, req) use@{ res ->
                if (!res.isSuccessful) {
                    lastFailure = "Downloadwella: step 1 POST HTTP ${res.code}"
                    return null
                }
                val body = HttpClient.cappedText(res) ?: run {
                    lastFailure = "Downloadwella: step 1 empty POST body"
                    return null
                }

                val directMedia = findDirectMediaUrl(body) ?: configuredDirect(body)
                if (!directMedia.isNullOrBlank() && !directMedia.equals(url, ignoreCase = true) && !isRootLockerDomain(directMedia)) {
                    return directMedia
                }

                val postDoc = Jsoup.parse(body, url)
                val directAnchor = postDoc.select("a[href]").mapNotNull { a ->
                    val href = a.attr("abs:href")
                    if (isDirectMediaUrl(href) || (href.contains("/d/") && !href.endsWith(".html"))) href else null
                }.firstOrNull { !it.equals(url, ignoreCase = true) && !isRootLockerDomain(it) }

                if (!directAnchor.isNullOrBlank()) return directAnchor

                // Step 2 form if present
                val step2Form = try {
                    postDoc.selectFirst(formSelector)
                } catch (_: Exception) {
                    null
                } ?: postDoc.selectFirst("form[name='F1'], form")
                if (step2Form != null && step2Form.select("input[name='op']").isNotEmpty()) {
                    val step2Builder = FormBody.Builder()
                    for (inp in step2Form.select("input[name]")) {
                        step2Builder.add(inp.attr("name"), inp.attr("value"))
                    }
                    val step2Req = Request.Builder()
                        .url(step2Form.attr("abs:action").ifBlank { formAction })
                        .header("User-Agent", HttpClient.DEFAULT_UA)
                        .header("Referer", url)
                        .post(step2Builder.build())
                        .build()
                    HttpClient.executeCancellable(HttpClient.permissiveClient, step2Req) use@{ res2 ->
                        if (res2.isSuccessful) {
                            val body2 = HttpClient.cappedText(res2) ?: ""
                            val direct2 = findDirectMediaUrl(body2) ?: configuredDirect(body2)
                            if (!direct2.isNullOrBlank() && !isRootLockerDomain(direct2)) return direct2
                            val postDoc2 = Jsoup.parse(body2, url)
                            val directAnchor2 = postDoc2.select("a[href]").mapNotNull { a ->
                                val href = a.attr("abs:href")
                                if (isDirectMediaUrl(href) || (href.contains("/d/") && !href.endsWith(".html"))) href else null
                            }.firstOrNull { !it.equals(url, ignoreCase = true) && !isRootLockerDomain(it) }
                            if (!directAnchor2.isNullOrBlank()) return directAnchor2
                        }
                    }
                }
                lastFailure = "Downloadwella: no direct media link found after form submit (body len=${body.length})"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "Downloadwella: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 15. LoadedfilesResolver
// -------------------------------------------------------------
object LoadedfilesResolver : BaseResolver {
    private val HOST_RE = Pattern.compile("""loadedfiles\.[a-z0-9-]+""", Pattern.CASE_INSENSITIVE)

    // Legacy diagnostic retained for callers; the registry uses resolveOutcome instead.
    @Volatile private var lastResolveError: String? = null

    override fun lastResolveFailure(): String? = lastResolveError

    // loadedfiles keeps switching TLDs (.st / .net / .org / ...) while every host
    // serves the same file hashes. Pinning one TLD breaks whenever that host goes
    // dark, so try the last host that worked, then the link's own TLD, then the
    // known fallbacks. Ported from the monolith's _candidate_hosts (resolvers.py).
    private val FALLBACK_TLDS = listOf("st", "net", "org", "to", "com")
    @Volatile private var lastWorkingHost: String? = null

    private fun rewriteHost(text: String, host: String): String =
        HOST_RE.matcher(text).replaceAll(host)

    private fun candidateHosts(url: String): List<String> {
        val m = HOST_RE.matcher(url.lowercase())
        val urlHost = if (m.find()) m.group() else null
        val hosts = LinkedHashSet<String>()
        urlHost?.let { hosts.add(it) }
        lastWorkingHost?.let { hosts.add(it) }
        FALLBACK_TLDS.forEach { hosts.add("loadedfiles.$it") }
        return hosts.toList()
    }

    override fun canResolve(url: String): Boolean {
        val host = HttpClient.parsedHost(url)
        return if (host != null) HOST_RE.matcher(host).find()
        else HOST_RE.matcher(url.lowercase()).find()
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? =
        (resolveOutcome(url, quality, depth) as? ResolverOutcome.Success)?.url

    internal suspend fun resolveOutcome(url: String, quality: String, depth: Int): ResolverOutcome = withContext(Dispatchers.IO) {
        var failure: ResolverOutcome.Failure? = null
        val outcome = captureResolverOutcome { resolveAttempt(url) { failure = it } }
        val result = if (outcome == ResolverOutcome.NoMatch) failure ?: outcome else outcome
        lastResolveError = (result as? ResolverOutcome.Failure)?.reason
        result
    }

    private suspend fun resolveAttempt(url: String, onFailure: (ResolverOutcome.Failure?) -> Unit): String? {
        try {
            // Internal deadline below the registry's 12s unproven-host budget:
            // a chain that keeps minting fresh tokens but never reaches media
            // must yield the slot to the next mirror instead of consuming the
            // whole pass (live: "token chain exhausted" at 11s).
            val deadlineMs = System.currentTimeMillis() + 10_000L
            // The token chain needs the shared client's longer read timeout (a
            // slow wait page must not abort the hop), but the host-candidate
            // probe is pure liveness: a dead host should fail in 6s instead of
            // burning the shared 15s per candidate before the next TLD is tried.
            val noRedirectClient = HttpClient.shared.newBuilder().followRedirects(false).build()
            val probeClient = HttpClient.shared.newBuilder()
                .followRedirects(false)
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build()
            val slowProbeClient = HttpClient.shared.newBuilder()
                .followRedirects(false)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .build()

            // Find a host that actually answers, then run the token chain on it.
            // The probe must record the host that ANSWERED, not the one
            // requested: loadedfiles.org is a 301 shell for loadedfiles.net,
            // and recording .org poisoned every later ?pt= referer and host
            // rewrite — the server then rotates tokens forever and the whole
            // 9jarocks path died for hours (live-verified 2026-08-21).
            val hosts = candidateHosts(url)
            var currUrl: String? = null
            var fastTimedOut = false
            for (host in hosts) {
                val candidate = HttpClient.safeUrl(rewriteHost(url, host))
                val effective = try {
                    probeEffectiveUrl(probeClient, candidate)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (e is java.net.SocketTimeoutException ||
                        (e.message ?: "").contains("timeout", ignoreCase = true)
                    ) fastTimedOut = true
                    onFailure(resolverFailure(e))
                    android.util.Log.w("AnonDownload", "Loadedfiles fast-probe failed for $host: ${e.message}")
                    null
                }
                if (effective != null) {
                    lastWorkingHost = effective.substringAfter("://").substringBefore('/').lowercase()
                    currUrl = effective
                    break
                }
            }
            // Every candidate failed the fast probe. A slow host can still
            // answer after 6-8s, but a transport-dead one (ConnectException /
            // UnknownHost) will not get better in the slow pass: skip it and
            // fail over immediately.
            if (currUrl == null && !fastTimedOut) {
                onFailure(ResolverOutcome.Failure("Loadedfiles: all candidate hosts transport-dead (${hosts.joinToString()})"))
                android.util.Log.w("AnonDownload", "Loadedfiles: all fast probes transport-dead")
                return null
            }
            if (currUrl == null) {
                for (host in hosts) {
                    if (System.currentTimeMillis() > deadlineMs) break
                    val candidate = HttpClient.safeUrl(rewriteHost(url, host))
                    val effective = try {
                        probeEffectiveUrl(slowProbeClient, candidate)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        currentCoroutineContext().ensureActive()
                        onFailure(resolverFailure(e))
                        android.util.Log.w("AnonDownload", "Loadedfiles slow-probe failed for $host: ${e.message}")
                        null
                    }
                    if (effective != null) {
                        lastWorkingHost = effective.substringAfter("://").substringBefore('/').lowercase()
                        currUrl = effective
                        break
                    }
                }
            }
            if (currUrl == null) {
                onFailure(ResolverOutcome.Failure("Loadedfiles: no live candidate host responded (${hosts.joinToString()})"))
                android.util.Log.w("AnonDownload", "Loadedfiles: no live host")
                return null
            }

            onFailure(null)
            val workingHost = HttpClient.parsedHost(currUrl!!)
            val cookieMap = mutableMapOf<String, String>()
            var ptHops = 0
            var lastFetchedUrl: String? = null
            val seenChain = mutableSetOf<String>()
            var chainAborted = false
            for (step in 1..10) {
                currentCoroutineContext().ensureActive()
                if (System.currentTimeMillis() > deadlineMs) {
                    onFailure(ResolverOutcome.Failure("Loadedfiles token chain deadline (10s) on $currUrl"))
                    chainAborted = true
                    break
                }
                if (!seenChain.add(currUrl!!.substringBefore('#'))) {
                    onFailure(ResolverOutcome.Failure("Loadedfiles token chain loop on $currUrl"))
                    chainAborted = true
                    break
                }
                val pageBeforeStep = currUrl
                val referer = when {
                    lastFetchedUrl != null -> lastFetchedUrl
                    step == 1 -> "https://9jarocks.net/"
                    workingHost != null -> "https://$workingHost/"
                    else -> "https://loadedfiles.net/"
                }
                val cookieHeader = if (cookieMap.isNotEmpty()) {
                    cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
                } else null
                val req = Request.Builder()
                    .url(HttpClient.safeUrl(currUrl!!))
                    .header("User-Agent", HttpClient.DEFAULT_UA)
                    .apply {
                        referer?.let { header("Referer", it) }
                        cookieHeader?.let { header("Cookie", it) }
                    }
                    .build()

                var waitSecs = 0
                HttpClient.executeCancellable(noRedirectClient, req) use@{ res ->
                    lastFetchedUrl = currUrl
                    val setCookies = res.headers("Set-Cookie")
                    for (sc in setCookies) {
                        val cookiePart = sc.substringBefore(";").trim()
                        val eq = cookiePart.indexOf('=')
                        if (eq > 0) {
                            val k = cookiePart.substring(0, eq).trim()
                            val v = cookiePart.substring(eq + 1).trim()
                            if (k.isNotBlank() && v.isNotBlank()) {
                                cookieMap[k] = v
                            }
                        }
                    }

                    val loc = res.header("Location")
                    if (!loc.isNullOrBlank()) {
                        val safeLoc = HttpClient.safeUrl(HttpClient.safeResolveUri(res.request.url.toString(), loc))
                        // Second-or-later ?pt= hop: the Location IS the answer
                        // (monolith parity, resolvers.py hop-3:
                        // `return r3.headers.get('location')`) -- no gating.
                        if (ptHops >= 1 || safeLoc.contains("gfrdaseazzs.com") || isDirectMediaUrl(safeLoc) || safeLoc.contains("/token/download/") || safeLoc.contains("/d/")) {
                            if (!safeLoc.contains("?pt=")) {
                                android.util.Log.d("AnonDownload", "Loadedfiles cracked redirect URL: $safeLoc")
                                return safeLoc
                            }
                        }
                        currUrl = safeLoc
                        return@use
                    }

                    if (res.isSuccessful) {
                        // The site changed its chain (2026-08-21): after ~2 token
                        // rotations the ?pt= hop answers 200 and serves THE FILE
                        // ITSELF (video/* body) — no Location, no downloadUrl.
                        // Detect it from headers BEFORE reading any body, or a
                        // 114MB response would be read as "no match".
                        val ct = res.header("Content-Type")?.lowercase() ?: ""
                        if (ct.startsWith("video/") || ct.contains("octet-stream") ||
                            ct.contains("matroska") || ct.contains("mpegurl")
                        ) {
                            android.util.Log.d("AnonDownload", "Loadedfiles token hop served the media directly ($ct)")
                            return currUrl
                        }

                        val body = HttpClient.cappedText(res) ?: return@use
                        val direct = findDirectMediaUrl(body)
                        if (!direct.isNullOrBlank() && !isRootLockerDomain(direct)) {
                            val safeDirect = HttpClient.safeUrl(direct)
                            android.util.Log.d("AnonDownload", "Loadedfiles found direct media in body: $safeDirect")
                            return safeDirect
                        }

                        var next: String? = null
                        val mDlTimer = Pattern.compile(
                            """dlTimer\(\{\s*seconds:\s*(\d+),\s*link:\s*['"]([^'"]+)['"]""",
                            Pattern.CASE_INSENSITIVE
                        ).matcher(body)
                        if (mDlTimer.find()) {
                            waitSecs = mDlTimer.group(1)?.toIntOrNull() ?: 5
                            val raw = mDlTimer.group(2)
                            if (!raw.isNullOrBlank()) {
                                next = unescapeJsUrl(raw)
                            }
                        }
                        if (next == null) {
                            val mLegacy = Pattern.compile(
                                """var downloadUrl = '(https://loadedfiles\.[a-z0-9-]+/[^']+)'""",
                                Pattern.CASE_INSENSITIVE
                            ).matcher(body)
                            if (mLegacy.find()) {
                                next = mLegacy.group(1)
                            }
                        }
                        if (next == null) {
                            val mLink = Pattern.compile(
                                """link\s*:\s*['"]([^'"]+)['"]""",
                                Pattern.CASE_INSENSITIVE
                            ).matcher(body)
                            if (mLink.find()) {
                                val raw = mLink.group(1)
                                if (!raw.isNullOrBlank()) {
                                    next = unescapeJsUrl(raw)
                                }
                            }
                        }
                        if (next == null) {
                            val cfg = DynamicRulesManager.getResolverConfig("loadedfiles")
                            val customRegex = cfg?.optString("tokenRegex")
                                ?.takeIf { it.isNotBlank() && it.length <= 600 }
                            if (!customRegex.isNullOrBlank()) {
                                val mCustom = Pattern.compile(customRegex, Pattern.CASE_INSENSITIVE).matcher(body)
                                if (mCustom.find()) {
                                    for (g in 1..mCustom.groupCount()) {
                                        val v = mCustom.group(g)
                                        if (!v.isNullOrBlank()) {
                                            next = unescapeJsUrl(v)
                                            break
                                        }
                                    }
                                }
                            }
                        }
                        if (next == null) {
                            try {
                                val doc = Jsoup.parse(body, currUrl!!)
                                val btn = doc.selectFirst(
                                    "a[href*='/d/'], a[href*='/token/download/'], a[href*='?pt='], a.download-btn, a.btn-download, a.nv-btn--primary, button.nv-btn--primary, [class*='nv-btn--primary']"
                                )
                                if (btn != null) {
                                    val href = btn.attr("abs:href").ifBlank { btn.attr("href") }
                                    if (href.isNotBlank() && href != currUrl) {
                                        next = HttpClient.safeResolveUri(currUrl!!, href)
                                        if (href.contains("?pt=")) {
                                            waitSecs = 5
                                        }
                                    }
                                }
                                if (next == null) {
                                    val xDataEl = doc.selectFirst("[x-data*='link']")
                                    if (xDataEl != null) {
                                        val xData = xDataEl.attr("x-data")
                                        val m = Pattern.compile("""link\s*:\s*['"]([^'"]+)['"]""", Pattern.CASE_INSENSITIVE).matcher(xData)
                                        if (m.find()) {
                                            val raw = m.group(1)
                                            if (!raw.isNullOrBlank()) {
                                                next = HttpClient.safeResolveUri(currUrl!!, unescapeJsUrl(raw))
                                            }
                                        }
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                        if (!next.isNullOrBlank()) {
                            currUrl = HttpClient.safeUrl(next)
                            ptHops++
                        }
                    }
                }
                if (waitSecs > 0) {
                    android.util.Log.d("AnonDownload", "Loadedfiles waiting ${waitSecs}s for cooldown/timer")
                    delay(waitSecs * 1000L)
                }
                if (currUrl == pageBeforeStep) {
                    onFailure(ResolverOutcome.Failure("Loadedfiles token chain stalled on step $step ($currUrl) — neither dlTimer nor downloadUrl found"))
                    // Same page came back with nothing actionable: further steps
                    // would repeat it verbatim. Give up on this host here; the
                    // mirror-fallthrough below lets the next candidate try.
                    android.util.Log.w("AnonDownload", "Loadedfiles token chain stalled (no progress) on $currUrl")
                    break
                }
            }
            if (!chainAborted && currUrl != null && !currUrl.contains("/token/download/") && !currUrl.contains("gfrdaseazzs.com")) {
                onFailure(ResolverOutcome.Failure("Loadedfiles token chain exhausted on $currUrl"))
            }
            // The token chain failed on the reachable-first host (it answered
            // the probe but the chain dead-ended). reachable-first would pin
            // THIS host for every later call, so the other mirror TLDs would
            // never run their own chain. Release the pin: the next resolve
            // (retry, next episode) starts from the link's own TLD again.
            lastWorkingHost = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            onFailure(resolverFailure(e))
            android.util.Log.e("AnonDownload", "LoadedfilesResolver error: ${e.message}", e)
        }
        return null
    }

    /**
     * Follow up to 3 redirects manually and return the URL that actually
     * served a page. loadedfiles.org is a 301 shell for loadedfiles.net —
     * treating the shell as the working host poisoned the whole chain.
     */
    private suspend fun probeEffectiveUrl(client: okhttp3.OkHttpClient, startUrl: String): String? {
        var url = startUrl
        repeat(3) {
            currentCoroutineContext().ensureActive()
            val req = Request.Builder()
                .url(HttpClient.safeUrl(url))
                .header("User-Agent", HttpClient.DEFAULT_UA)
                .header("Referer", "https://my9jarocks.bz/")
                .build()
            HttpClient.executeCancellable(client, req) use@{ res ->
                val loc = res.header("Location")
                if (res.code in 300..399 && !loc.isNullOrBlank()) {
                    url = HttpClient.safeUrl(HttpClient.safeResolveUri(res.request.url.toString(), loc))
                } else if (res.code in 200..299) {
                    return url
                } else {
                    return null
                }
            }
        }
        return null
    }

    private fun unescapeJsUrl(s: String): String {
        var str = s.replace("\\/", "/")
        if (str.contains("\\u")) {
            val m = Pattern.compile("""\\u([0-9a-fA-F]{4})""").matcher(str)
            val sb = StringBuffer()
            while (m.find()) {
                val hex = m.group(1)
                val code = hex?.toIntOrNull(16)
                if (code != null) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(code.toChar().toString()))
                }
            }
            m.appendTail(sb)
            str = sb.toString()
        }
        return str
    }
}

// -------------------------------------------------------------
// 16. WildshareResolver
// -------------------------------------------------------------
object WildshareResolver : BaseResolver {
    override fun canResolve(url: String): Boolean {
        return hostClaim(url, listOf("wildshare.net"))
    }

    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            val html = HttpClient.getText(url, referer = url) ?: run {
                lastFailure = "Wildshare: empty HTTP response for $url"
                return null
            }
            val btnMatcher = Pattern.compile("""window\.location\s*=\s*['"](https?://wildshare\.net/[^'"]+)['"]""").matcher(html)
            val ptUrl = if (btnMatcher.find()) {
                btnMatcher.group(1)
            } else {
                val ptMatcher = Pattern.compile("""[?&'"]pt(?:=|["']\s*:\s*["'])([A-Za-z0-9%+=/]+)""").matcher(html)
                if (ptMatcher.find()) {
                    val pt = ptMatcher.group(1)
                    val parts = url.substringBefore('?').trimEnd('/').split('/')
                    val fileId = parts.lastOrNull { !it.endsWith(".mkv") && !it.endsWith(".mp4") && it.isNotBlank() } ?: parts.last()
                    "https://wildshare.net/$fileId?pt=$pt"
                } else null
            }
            if (!ptUrl.isNullOrBlank()) {
                val noRedirectClient = HttpClient.shared.newBuilder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build()
                val req = Request.Builder()
                    .url(HttpClient.safeUrl(ptUrl))
                    .header("User-Agent", HttpClient.DEFAULT_UA)
                    .header("Referer", url)
                    .build()
                HttpClient.executeCancellable(noRedirectClient, req) use@{ res ->
                    if (res.code !in 200..399) {
                        lastFailure = "Wildshare: pt request returned HTTP ${res.code} for $ptUrl"
                        com.anonrode.downloader.util.DebugLog.resolve(
                            "WildshareResolver: pt request returned HTTP ${res.code} for $ptUrl"
                        )
                        return null
                    }
                    val loc = res.header("Location")
                    if (!loc.isNullOrBlank()) {
                        return HttpClient.safeUrl(loc)
                    }
                    val ct = res.header("Content-Type")?.lowercase() ?: ""
                    if (ct.contains("video/") || ct.contains("application/octet-stream")) {
                        return ptUrl
                    }
                    val body = res.body?.string() ?: ""
                    val secondBtn = Pattern.compile("""window\.location\s*=\s*['"](https?://[^'"]+)['"]""").matcher(body)
                    if (secondBtn.find()) {
                        val nextLoc = secondBtn.group(1)
                        if (!nextLoc.isNullOrBlank() && nextLoc != ptUrl) {
                            return HttpClient.safeUrl(nextLoc)
                        }
                    }
                    findDirectMediaUrl(body)?.let { return it }
                    extractMp4FromHtml(body)?.let { return it }
                    lastFailure = "Wildshare: no Location header or media returned on HTTP ${res.code}"
                    return null
                }
            }
            findDirectMediaUrl(html)?.let { return it }
            extractMp4FromHtml(html)?.let { return it }
            lastFailure = "Wildshare: no pt token or direct media found in HTML (len=${html.length})"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "Wildshare: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 17. WaffiCloudResolver
// -------------------------------------------------------------
object WaffiCloudResolver : BaseResolver {
    override fun canResolve(url: String): Boolean {
        return hostClaim(url, listOf("waffi.cloud"))
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        // Reference parity (resolvers.py:275): a pure string strip with zero
        // network I/O. Stripping ?preview yields the direct file link; when the
        // URL was already clean this is an identity success.
        return url.substringBefore("?preview")
    }
}

// -------------------------------------------------------------
// 18. VidmolyResolver
// -------------------------------------------------------------
object VidmolyResolver : BaseResolver {
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        return hostClaim(url, listOf("vidmoly."))
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            val html = HttpClient.getText(url, referer = url) ?: run {
                lastFailure = "Vidmoly: empty HTTP response for $url"
                return null
            }
            val m = Pattern.compile("""(?:file|source|src)\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").matcher(html)
            if (m.find()) {
                val stream = m.group(1)
                if (!stream.isNullOrBlank()) return stream
            }

            val direct = extractM3u8FromHtml(html) ?: extractMp4FromHtml(html)
            if (!direct.isNullOrBlank()) return direct

            val unpacked = JsUnpacker.unpack(html)
            if (!unpacked.isNullOrBlank()) {
                val um = Pattern.compile("""(?:file|source|src)\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").matcher(unpacked)
                if (um.find()) {
                    val stream = um.group(1)
                    if (!stream.isNullOrBlank()) return stream
                }
                val unpDirect = extractM3u8FromHtml(unpacked) ?: extractMp4FromHtml(unpacked)
                if (!unpDirect.isNullOrBlank()) return unpDirect
            }
            lastFailure = "Vidmoly: no m3u8/mp4 stream found in HTML or unpacked JS (len=${html.length})"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "Vidmoly: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 19. StreamwishResolver
// -------------------------------------------------------------
object StreamwishResolver : BaseResolver {
    private val HOSTS = listOf(
        "hglink.to", "streamwish.", "strwsh.", "stwish.", "wishembed.",
        "mwish.", "awish.", "sfastwish.", "swishsrv.", "ajmidyad", "khadhnayad",
        "obeywish.com", "jodwish.com", "streamwish.to", "embedwish.", "filelions."
    )
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        return hostClaim(url, HOSTS)
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            val vid = url.trimEnd('/').substringAfterLast('/')
            val candidates = if (vid.length >= 6) {
                listOf(
                    url,
                    "https://sfastwish.com/e/$vid",
                    "https://embedwish.com/e/$vid"
                )
            } else {
                listOf(url)
            }

            for (cand in candidates) {
                val html = HttpClient.getText(cand, referer = "https://asianc.id/") ?: continue
                if (looksLikeDeadPage(html)) continue
                val m3u8 = extractM3u8FromHtml(html) ?: extractMp4FromHtml(html)
                if (!m3u8.isNullOrBlank()) return m3u8

                val unpacked = JsUnpacker.unpack(html)
                if (!unpacked.isNullOrBlank()) {
                    val direct = extractM3u8FromHtml(unpacked) ?: extractMp4FromHtml(unpacked)
                    if (!direct.isNullOrBlank()) return direct
                }
            }
            lastFailure = "Streamwish: no playable stream extracted from ${candidates.size} candidate(s)"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "Streamwish: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 20. VidhideResolver
// -------------------------------------------------------------
object VidhideResolver : BaseResolver {
    // filelions network: frontend domains rotate (vidhidepro.com -> vidhidefast.com,
    // 2026-08) and every frontend serves the same file-id space under the same
    // /e/ /f/ /d/ paths. A pasted link on a dead frontend (522 / conn error) is
    // retried on the live mirrors instead of failing outright.
    private val HOSTS = listOf(
        "vidhidefast.", "minochinos.com", "vidhide.", "vidhidepro.", "vidhidevip.",
        "filelions.", "vid-guard.", "nining.", "peytonepre.com",
        "techradar.ink", "ryderjet.com"
    )
    private val MIRROR_HOSTS = listOf(
        "vidhide.com", "ryderjet.com", "minochinos.com", "vidhidefast.com",
        "vidhidevip.com", "vidhidepro.com", "filelions.to"
    )
    @Volatile private var lastWorkingHost: String? = null
    @Volatile private var lastFailure: String? = null
    private val HOST_PART = Pattern.compile("""(https?://)([^/:]+)""", Pattern.CASE_INSENSITIVE)

    override fun lastResolveFailure(): String? = lastFailure

    private fun rewriteHost(url: String, host: String): String {
        val m = HOST_PART.matcher(url)
        return if (m.find()) {
            HttpClient.safeUrl(url.substring(0, m.start(2)) + host + url.substring(m.end(2)))
        } else url
    }

    override fun canResolve(url: String): Boolean {
        return hostClaim(url, HOSTS)
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            val urlHost = HttpClient.safeHost(url).lowercase()
            val candidates = LinkedHashSet<String>()
            lastWorkingHost?.let { candidates.add(it) }
            candidates.add(urlHost)
            candidates.addAll(MIRROR_HOSTS)
            for (host in candidates) {
                val cand = if (host == urlHost) url else rewriteHost(url, host)
                val html = HttpClient.getText(cand, referer = cand) ?: continue
                if (looksLikeDeadPage(html)) continue
                val m3u8 = extractM3u8FromHtml(html) ?: extractMp4FromHtml(html)
                if (!m3u8.isNullOrBlank()) {
                    lastWorkingHost = host
                    return m3u8
                }
                val unpacked = JsUnpacker.unpack(html)
                if (!unpacked.isNullOrBlank()) {
                    val direct = extractM3u8FromHtml(unpacked) ?: extractMp4FromHtml(unpacked)
                    if (!direct.isNullOrBlank()) {
                        lastWorkingHost = host
                        return direct
                    }
                }
            }
            lastFailure = "Vidhide: no stream extracted across ${candidates.size} mirror(s)"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "Vidhide: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 21. DoodstreamResolver (DYNAMIC HOST FIX)
// -------------------------------------------------------------
object DoodstreamResolver : BaseResolver {
    private val HOSTS = listOf(
        "dood.", "doodstream.", "ds2play.com", "dooood.com", "d0000d.com",
        "d000d.com", "vidply.com", "do0od.com", "dood.re"
    )
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        return hostClaim(url, HOSTS)
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            val urlHost = HttpClient.safeHost(url, "dood.to")
            val rawPath = url.replace("https://$urlHost", "").replace("http://$urlHost", "")
            val embedPath = rawPath.replace("/d/", "/e/").replace("/f/", "/e/")
            val candidates = LinkedHashSet<String>().apply {
                add(urlHost)
                add("doodstream.com")
                add("dood.to")
                add("d000d.com")
            }
            var html: String? = null
            var activeHost = urlHost
            var activeEmbedUrl = "https://$urlHost$embedPath"
            for (h in candidates) {
                val candidateUrl = "https://$h$embedPath"
                val res = HttpClient.getText(candidateUrl, referer = "https://$h/")
                if (!res.isNullOrBlank() && !looksLikeDeadPage(res)) {
                    html = res
                    activeHost = h
                    activeEmbedUrl = candidateUrl
                    break
                }
            }
            if (html == null) {
                lastFailure = "Doodstream: empty or blocked response across candidate mirrors (${candidates.joinToString()})"
                return null
            }
            val passPattern = Pattern.compile("""/pass_md5/([^"'\s]+)""")
            val matcher = passPattern.matcher(html)
            if (matcher.find()) {
                val passPath = matcher.group(1)
                val passUrl = "https://$activeHost/pass_md5/$passPath"
                val token = HttpClient.getText(passUrl, referer = activeEmbedUrl)
                if (!token.isNullOrBlank()) {
                    val tokenSlug = passPath.trimEnd('/').substringAfterLast('/')
                    val randomStr = (1..10).map { ('a'..'z').random() }.joinToString("")
                    val expiry = System.currentTimeMillis()
                    // /pass_md5/ returns a stream URL or direct path prefix; the playable URL is
                    // <prefix><random>?token=<slug>&expiry=<ts>.
                    val rawPrefix = token.trim()
                    val streamPrefix = if (rawPrefix.startsWith("http://") || rawPrefix.startsWith("https://")) {
                        rawPrefix
                    } else if (rawPrefix.startsWith("/")) {
                        "https://$activeHost$rawPrefix"
                    } else {
                        "https://$activeHost/$rawPrefix"
                    }
                    return "$streamPrefix$randomStr?token=$tokenSlug&expiry=$expiry"
                }
                lastFailure = "Doodstream: pass_md5 token fetch returned empty from $passUrl"
            } else {
                lastFailure = "Doodstream: pass_md5 pattern not found in HTML (len=${html.length})"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "Doodstream: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 22. MixdropResolver
// -------------------------------------------------------------
object MixdropResolver : BaseResolver {
    private val HOSTS = listOf("mixdrop.", "mixdrp.", "mdfx9dc8n.net", "mixdroop.")
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        return hostClaim(url, HOSTS)
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            val embedUrl = url.replace("/f/", "/e/")
            val host = HttpClient.safeHost(url, "mixdrop.co")
            // Permissive SSL client: mixdrop.co frequently serves mismatched or broken CA chains
            val html = HttpClient.getText(embedUrl, referer = "https://$host/", permissive = true) ?: run {
                lastFailure = "Mixdrop: empty HTTP response for $embedUrl"
                return null
            }
            if (looksLikeDeadPage(html)) {
                lastFailure = "Mixdrop: page matches dead file markers"
                return null
            }
            val unpacked = JsUnpacker.unpack(html)
            val source = if (!unpacked.isNullOrBlank()) unpacked else html
            var matcher = Pattern.compile("""MDCore\.wurl\s*=\s*["']([^"']+)["']""").matcher(source)
            if (matcher.find()) {
                var streamUrl = matcher.group(1) ?: ""
                if (streamUrl.startsWith("//")) streamUrl = "https:$streamUrl"
                return streamUrl
            }
            matcher = Pattern.compile("""(?:wurl|player\.src)\s*=\s*["']([^"']+)["']""").matcher(source)
            if (matcher.find()) {
                var streamUrl = matcher.group(1) ?: ""
                if (streamUrl.startsWith("//")) streamUrl = "https:$streamUrl"
                return streamUrl
            }
            lastFailure = "Mixdrop: MDCore.wurl pattern not found in HTML/unpacked JS"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "Mixdrop: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 23. StreamtapeResolver
// -------------------------------------------------------------
object StreamtapeResolver : BaseResolver {
    private val HOSTS = listOf("streamtape.", "watchadsontape.", "strtape.tech")
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        return hostClaim(url, HOSTS)
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            val html = HttpClient.getText(url, referer = url) ?: run {
                lastFailure = "Streamtape: empty HTTP response for $url"
                return null
            }
            if (looksLikeDeadPage(html)) {
                lastFailure = "Streamtape: page matches dead file markers"
                return null
            }
            var matcher = Pattern.compile("""document\.getElementById\(["']robotlink["']\)\.innerHTML\s*=\s*["']([^"']+)["']\s*\+\s*(?:\(["']|["'])([^"'\)]+)(?:["']\)|["'])""").matcher(html)
            if (matcher.find()) {
                val part1 = matcher.group(1) ?: ""
                val part2 = matcher.group(2) ?: ""
                var stream = "$part1$part2"
                if (stream.startsWith("//")) stream = "https:$stream"
                return stream
            }
            matcher = Pattern.compile("""["']robotlink["']\s*\)\s*\.innerHTML\s*=\s*["']([^"']+)["']""").matcher(html)
            if (matcher.find()) {
                var stream = matcher.group(1) ?: ""
                if (stream.startsWith("//")) stream = "https:$stream"
                if (stream.startsWith("http")) return stream
            }
            lastFailure = "Streamtape: robotlink pattern not matched in HTML (len=${html.length})"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "Streamtape: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 24. PixelDrainResolver
// -------------------------------------------------------------
object PixelDrainResolver : BaseResolver {
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        return hostClaim(url, listOf("pixeldrain.com"))
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            val clean = url.trimEnd('/').substringBefore('?')
            val fileId = clean.substringAfterLast('/')
            if (fileId.isNotBlank() && fileId != "u" && fileId != "d" && fileId != "pixeldrain.com") {
                return "https://pixeldrain.com/api/file/$fileId?download"
            }
            lastFailure = "PixelDrain: could not extract fileId from $url"
        } catch (e: Exception) {
            lastFailure = "PixelDrain: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 25. GenericLockerResolver
// -------------------------------------------------------------
object GenericLockerResolver : BaseResolver {
    private val HOSTS = listOf("lulacloud.com", "filemoon.sx", "streamhide.to", "filelions.to")
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        return hostClaim(url, HOSTS)
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            // Covers lulacloud.com (broken CA chain) — permissive, bounded
            // to these locker page fetches only.
            val html = HttpClient.getText(url, referer = url, permissive = true) ?: run {
                lastFailure = "GenericLocker: empty HTTP response for $url"
                return null
            }
            val m3u8 = extractM3u8FromHtml(html)
            if (!m3u8.isNullOrBlank()) return m3u8

            val mp4 = extractMp4FromHtml(html)
            if (!mp4.isNullOrBlank()) return mp4

            val unpacked = JsUnpacker.unpack(html)
            if (!unpacked.isNullOrBlank()) {
                val direct = extractM3u8FromHtml(unpacked) ?: extractMp4FromHtml(unpacked)
                if (!direct.isNullOrBlank()) return direct
            }
            lastFailure = "GenericLocker: no media extracted from HTML (len=${html.length})"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "GenericLocker: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 26. DynamicLockerResolver
// -------------------------------------------------------------
object DynamicLockerResolver : BaseResolver {
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean = DynamicLockerEngine.canResolve(url)

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        val host = HttpClient.parsedHost(url)?.removePrefix("www.")?.lowercase()
        val seed = if (host != null) setOf(host) else emptySet()
        // The typed BaseResolver entry can be reached without resolveCore's
        // lineage scope: merge the ambient chain lineage here so this call
        // can never re-seed visitedSites/depth and reboot cycle detection
        // mid-chain (the 2026-10-15 leak class). No ambient lineage (a
        // top-level caller) => arguments pass through unchanged.
        val lineage = advanceResolveLineage(currentCoroutineContext()[ResolveLineage.Key], depth, seed)
        return (resolveOutcome(url, quality, lineage.depth, lineage.visitedSites) as? ResolverOutcome.Success)?.url
    }

    internal suspend fun resolveOutcome(
        url: String,
        quality: String,
        depth: Int,
        visitedSites: Set<String>
    ): ResolverOutcome {
        lastFailure = null
        val result = DynamicLockerEngine.resolve(url, quality, depth, visitedSites)
        return if (!result.isNullOrBlank()) {
            ResolverOutcome.Success(result)
        } else {
            val fail = "DynamicLocker: OTA pipeline yielded no stream for $url"
            lastFailure = fail
            ResolverOutcome.Failure(fail)
        }
    }
}

private val DEAD_FILE_MARKERS = listOf(
    "file is no longer available", "file was deleted", "file deleted",
    "file not found", "video not found", "this file was deleted",
    "has been removed", "no longer exists"
)

private fun looksLikeDeadPage(html: String): Boolean {
    val low = html.lowercase()
    return DEAD_FILE_MARKERS.any { low.contains(it) }
}

private fun extractM3u8FromHtml(html: String): String? {
    val matcher = Pattern.compile("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").matcher(html)
    if (matcher.find()) {
        return matcher.group(0)
    }
    return null
}

private fun extractMp4FromHtml(html: String): String? {
    // (?![a-zA-Z0-9]) so "site.webmanifest" (a common WP favicon link) is not
    // matched as ".webm"; HTML-escaped quotes are stripped off the tail.
    val matcher = Pattern.compile("""https?://[^\s"'<>]+\.(?:mp4|mkv)(?![a-zA-Z0-9])[^\s"'<>]*""").matcher(html)
    if (matcher.find()) {
        return matcher.group(0)?.substringBefore("&quot;")?.substringBefore("&amp;")
    }
    return null
}

fun isDirectMediaUrl(url: String): Boolean {
    if (url.isBlank()) return false
    val query = url.substringAfter('?', "").lowercase()
    if (query.contains("preview")) return false
    val clean = url.substringBefore('?').substringBefore('#').lowercase()
    val exts = com.anonrode.downloader.data.rules.DynamicRulesManager.getDirectMediaExtensions()
    if (exts.any { clean.endsWith(it) }) return true
    if (query.contains("response-content-disposition=") ||
        query.contains("filename=") ||
        query.contains("filename*=") ||
        query.contains("file=")) {
        return exts.any { query.contains(it) }
    }
    return false
}

fun isRootLockerDomain(url: String): Boolean {
    val clean = url.trimEnd('/')
    return listOf(
        "https://downloadwella.com",
        "http://downloadwella.com",
        "https://wetafiles.com",
        "http://wetafiles.com",
        "https://loadedfiles.net",
        "https://loadedfiles.st",
        "https://loadedfiles.to",
        "https://loadedfiles.org",
        "https://loadedfiles.com",
        "https://kissorgrab.com"
    ).any { clean.equals(it, ignoreCase = true) }
}

fun findDirectMediaUrl(text: String): String? {
    for (ext in listOf("m3u8", "mp4", "mkv", "webm", "avi")) {
        val matcher = Pattern.compile("""https?://[^\s"'<>,\\)]+\.$ext(?:[^\s"'<>,\\)]*)?""", Pattern.CASE_INSENSITIVE).matcher(text)
        while (matcher.find()) {
            val cand = matcher.group(0)?.trimEnd('.', ',', ';', ')') ?: continue
            val clean = cand.substringBefore('?').substringBefore('#').lowercase()
            if (listOf(".mp4", ".mkv", ".m3u8", ".webm", ".avi", ".ts").any { clean.endsWith(it) }) {
                return cand
            }
        }
    }
    return null
}

