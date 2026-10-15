package com.anonrode.downloader.engine

import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.pipeline.HostHealth
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Engine-side resolution accountancy for the crack chain (the per-mirror
 * resolver walk the download engine drives from startTask).
 *
 * Evidence behind every constant below is the share-29 device log
 * (activity-log-share-29.txt, crack-stage hops): 690s+ of aggregate resolver
 * wall time in one session, dominated by SERIAL DEAD-MIRROR WALKS —
 *   vidbasic.top one url: 11.0s + 25.4s + 27.9s ERR across three full passes
 *   minochinos.com one url: ~33s across DynamicLocker/Vidhide, several passes
 *   dood.wf one url: 13.7s + 2.3s + 5.7s ERR
 * while healthy cracks are ALSO slow on some hosts (LoadedfilesResolver OK at
 * 14.3s/19.1s, Downloadwella 26.2s ERR then 12.7s OK on retry, naijavault
 * gateway 30.0s OK). The mechanisms therefore split by trust:
 *
 *  1. PER-TASK DEAD-URL MEMORY — after a URL has consumed one FULL crack pass
 *     (every claiming resolver ran and the chain returned nothing), the same
 *     URL is not re-walked at all in this task: later passes, resumed tasks,
 *     and gateway chains re-entering the URL are told "dead" without any
 *     request. Marks are per-task, in-memory, TTL-bounded, and wiped at the
 *     reset points that mean "give this task a fresh chance": success, park
 *     (host-backoff recovery), a manual retry tap, and a cross-provider
 *     failover switch. Nothing global or permanent can form.
 *
 *  2. PER-ATTEMPT TIME BUDGET — one resolver attempt on one URL is hard-capped
 *     with withTimeoutOrNull (the file's own idiom) at ATTEMPT_BUDGET_MS for
 *     unproven hosts; hosts with proven crack history (HostHealth ok >= 1,
 *     which persists across sessions) get the generous cap so slow-but-alive
 *     cracks (19.1s loadedfiles, 30s naijavault) are never clipped. A resolver
 *     that already burned >= FULL_BURN_MS on a URL without success retries at
 *     the reduced budget (never below the generous cap for proven hosts).
 *
 *  3. SESSION HOST FORM — an in-memory per-host success/strike counter (no
 *     persistence; HostHealth keeps the persistent ledger) used ONLY for
 *     candidate ordering: mirrors whose host struck out twice this session
 *     walk last, mirrors with a session success walk first. A strike never
 *     blocks an attempt — that remains HostHealth's gate — it only re-orders.
 */

object ResolveBudget {

    /** Hard cap for ONE resolver attempt on ONE url whose host has never
     *  served us a real crack. Evidence: every dead hop 13-28s (vidbasic
     *  25.4/27.9s, dood 13.7s, minochinos DL 12.4s) produced zero bytes. */
    const val ATTEMPT_BUDGET_MS = 12_000L

    /** Cap for hosts/resolvers with proven slow-but-real crack history.
     *  Calibrated above the slowest observed healthy crack (naijavault 30.0s
     *  OK); only true pathological hangs get clipped here. */
    const val PROVEN_ATTEMPT_BUDGET_MS = 40_000L

    /** Budget for a re-walk of a url that already burned past FULL_BURN_MS and
     *  failed (the "must not be re-walked at FULL cost" tier — other resolvers
     *  and retries still get a cheap look, just not another 25s). */
    const val REDUCED_ATTEMPT_BUDGET_MS = 8_000L

    /** A failed attempt costing at least this much marks the url expensive. */
    const val FULL_BURN_MS = 8_000L

    /**
     * TRUE when a resolver failure reason is TRANSPORT-level (DNS, connect,
     * socket, TLS, cooldown wait, cancellation, or our own budget clip).
     * Transport failures say "this attempt couldn't reach the host" — they
     * recover with the network and are HostHealth's domain (its backoff
     * windows exist exactly for them). They must NEVER feed the dead-URL
     * memo: one Wi-Fi blink or one CDN blip would otherwise mark every
     * mirror on the page dead and turn later passes (and the task's own
     * recovery paths) into no-op skips. Live log example: "ConnectException:
     * Failed to connect to data.vidsrcme.ru ... after 1779ms" — a memo
     * without this guard would black-hole that URL for the whole task.
     * Only CONTENT-level conclusions (page matched dead pattern, no stream
     * extracted after successful fetches, no crypto payload, domain-locale
     * verdicts such as "no live candidate host responded") qualify for the
     * memo — those are proven-dead pages, not sick networks.
     */
    private val TRANSPORT_FAILURE_MARKERS = listOf(
        "UnknownHostException", "ConnectException", "SocketTimeoutException",
        "SocketException", "SSLException", "SSLHandshakeException",
        "InterruptedIOException", "OriginCooldownException", "OriginCooldown",
        "TimeoutCancellationException", "CancellationException",
        "AttemptBudget", "timed out", "timeout"
    )

    fun isTransportLevelFailure(reason: String?): Boolean {
        if (reason.isNullOrBlank()) return false
        val lower = reason.lowercase()
        return TRANSPORT_FAILURE_MARKERS.any { lower.contains(it.lowercase()) }
    }

    /** How long one dead-url mark lives even without an explicit reset. The
     *  resolve phase of a task lasts seconds-to-minutes; 15 minutes of
     *  forgetting is far below the token-rotation cycles resolvers expect,
     *  so stale memory cannot permanently hide a recovered locker. */
    const val MEMO_TTL_MS = 15 * 60_000L

    // ---------------------------------------------------------------
    // 1. Per-task dead-URL memory
    // ---------------------------------------------------------------
    // task id -> (normalized url -> expiry epoch ms). ConcurrentHashMap pair;
    // the expiry doubles as the insert stamp for bounded eviction.
    private val deadUrls = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()

    private fun normalize(url: String): String = url.trim().substringBefore('#')

    fun isDead(taskId: String, url: String): Boolean {
        if (taskId.isBlank() || url.isBlank()) return false
        val set = deadUrls[taskId] ?: return false
        val key = normalize(url)
        val until = set[key] ?: return false
        if (System.currentTimeMillis() >= until) {
            set.remove(key)
            return false
        }
        return true
    }

    /** Marks a url dead for this task (idempotent; refreshes the TTL). */
    fun markDead(taskId: String, url: String) {
        if (taskId.isBlank() || url.isBlank()) return
        val set = deadUrls.computeIfAbsent(taskId) {
            if (deadUrls.size >= 128) evictStaleTasks()
            ConcurrentHashMap()
        }
        val now = System.currentTimeMillis()
        if (set.size >= 64) {
            set.entries.removeIf { now >= it.value }
            if (set.size >= 64) {
                val victim = set.entries.iterator()
                repeat(16) { if (victim.hasNext()) { victim.next(); victim.remove() } }
            }
        }
        set[normalize(url)] = now + MEMO_TTL_MS
    }

    /** A real success on this url in this task: remove any dead mark. */
    fun markAlive(taskId: String, url: String) {
        if (taskId.isBlank() || url.isBlank()) return
        deadUrls[taskId]?.remove(normalize(url))
    }

    /** Wipes the task's memory at a "fresh chance" reset point: resolved
     *  successfully, parked for host cooldown, manual retry tap, or a
     *  cross-provider failover re-point. */
    fun forgetTask(taskId: String) {
        if (taskId.isBlank()) return
        deadUrls.remove(taskId)
    }

    private fun evictStaleTasks() {
        val now = System.currentTimeMillis()
        deadUrls.entries.removeIf { e ->
            e.value.entries.removeIf { now >= it.value }
            e.value.isEmpty()
        }
        if (deadUrls.size >= 128) deadUrls.clear()
    }

    // ---------------------------------------------------------------
    // 2. Failed-burn ledger (feeds the attempt budget)
    // ---------------------------------------------------------------
    // normalized url -> the longest failed attempt ever observed this
    // process session. Session-scope by design: nothing persists.
    private val burns = ConcurrentHashMap<String, Long>()

    /** Records the duration of a FAILED resolver attempt on [url]. */
    fun noteFailedBurn(url: String, ms: Long) {
        if (url.isBlank() || ms <= 0L) return
        val key = normalize(url)
        burns.compute(key) { _, v -> maxOf(v ?: 0L, ms) }
        if (burns.size > 512) burns.clear()
    }

    fun maxFailedBurn(url: String): Long = burns[normalize(url)] ?: 0L

    /**
     * Budget for the next single attempt on [url] by [resolverName].
     * Proven host history (persisted HostHealth) and known slow-but-legit
     * resolvers get the generous cap; everyone else starts tight and drops
     * to the reduced cap once this url has eaten FULL_BURN_MS without success.
     */
    fun attemptBudgetMs(url: String, resolverName: String?): Long {
        val host = HttpClient.parsedHost(url)
        if (host != null && HostHealth.hasProvenLocker(host)) return PROVEN_ATTEMPT_BUDGET_MS
        if (resolverName != null && resolverName in SLOW_BUT_LEGIT_RESOLVERS) return PROVEN_ATTEMPT_BUDGET_MS
        return if (maxFailedBurn(url) >= FULL_BURN_MS) REDUCED_ATTEMPT_BUDGET_MS else ATTEMPT_BUDGET_MS
    }

    /** Resolvers whose healthy cracks are PROVEN slower than the tight cap in
     *  the share-29 log (Loadedfiles 14.3/19.1s OK, Downloadwella 12.7s OK
     *  after a 26.2s hung attempt, naijavault gateway 30.0s OK). Hanging dead
     *  hops on these hosts stay inside [PROVEN_ATTEMPT_BUDGET_MS] instead. */
    private val SLOW_BUT_LEGIT_RESOLVERS = setOf(
        "LoadedfilesResolver",
        "DownloadwellaResolver",
        "NaijaVaultGatewayResolver",
        "DramaGatewayResolver",
        "NpDownloaderGatewayResolver"
    )

    // ---------------------------------------------------------------
    // 3. Session host form (ordering only — never a gate)
    // ---------------------------------------------------------------
    private class Form(
        var ok: Int = 0,
        var strikes: Int = 0,
        var lastOkMs: Long = 0L
    )

    private val hostForm = ConcurrentHashMap<String, Form>()

    private fun hostOf(urlOrHost: String): String? =
        HttpClient.parsedHost(urlOrHost) ?: urlOrHost.trim()
            .substringAfter("://", "").substringBefore('/').takeIf { it.isNotBlank() }

    /** The host cracked a full chain this task-cycle — remove its strikes. */
    fun recordHostOk(urlOrHost: String) {
        val h = hostOf(urlOrHost) ?: return
        if (h.isBlank()) return
        hostForm.compute(h) { _, v -> (v ?: Form()).apply {
            ok++; strikes = 0; lastOkMs = System.currentTimeMillis()
        } }
        pruneHostForm()
    }

    /** One full crack pass on this host's url returned nothing. Two strikes
     *  within the session demote the host's mirrors in candidate ordering
     *  (fail-fast), but never block an attempt. */
    fun recordHostFullPassFail(urlOrHost: String) {
        val h = hostOf(urlOrHost) ?: return
        if (h.isBlank()) return
        hostForm.compute(h) { _, v -> (v ?: Form()).apply { strikes = (strikes + 1).coerceAtMost(16) } }
    }

    private fun pruneHostForm() {
        if (hostForm.size <= 256) return
        val now = System.currentTimeMillis()
        hostForm.entries.removeIf { (_, v) -> v.strikes == 0 && now - v.lastOkMs > 30 * 60_000L }
        if (hostForm.size > 256) hostForm.clear()
    }

    private const val STRIKE_GROUP = 2

    private fun candidateGroup(url: String): Int {
        val h = hostOf(url) ?: return 1
        val f = hostForm[h] ?: return 1
        return when {
            f.strikes >= 2 -> STRIKE_GROUP
            f.ok > 0 -> 0
            else -> 1
        }
    }

    /**
     * Empirical ordering of the engine's mirror candidates: 2-strike hosts
     * walk LAST, hosts with a session success walk ahead of cold hosts, and
     * the engine's selected mirror stays first within its tier (MirrorPool's
     * cooldown-aware order survives — the sort is stable). A demoted host is
     * still attempted when its turn comes; only HostHealth backs hosts off.
     */
    fun orderCandidates(urls: List<String>): List<String> {
        if (urls.size <= 1) return urls
        val primary = urls.first()
        return urls.sortedWith(
            compareBy(
                { candidateGroup(it) == STRIKE_GROUP },
                { it != primary },
                { candidateGroup(it) }
            )
        )
    }

    // ---------------------------------------------------------------
    // Coroutine-context plumbing: one explicit task id at the chain
    // entrance, ambient for every nested registry re-entry (gateway
    // handoffs, embed walks) — suspension-safe, thread-local-free.
    // ---------------------------------------------------------------
    class TaskMemo(val taskId: String) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<TaskMemo>
    }
}
