package com.anonrode.downloader.pipeline

import android.content.Context
import com.anonrode.downloader.BuildConfig
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.data.rules.FamilyFlag
import com.anonrode.downloader.data.rules.SitePipeline
import com.anonrode.downloader.providers.RulesPipeline
import com.anonrode.downloader.util.DebugLog
import com.anonrode.downloader.util.RemoteLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shadow-run harness for the OTA behavior family (Phase 2, plan slice P2-4;
 * the GitHub Scientist two-engine pattern, design doc §3.5 / §5 step 5).
 *
 * Scientist rule, load-bearing and absolute: THE USER ALWAYS GETS THE COMPILED
 * CONTROL RESULT. Implementation: when the payload flags the `pipelines` family
 * `shadow: true`, DynamicRulesManager publishes that family section EMPTY —
 * every provider then runs its compiled code path and shows the user that —
 * while the parsed candidate set is handed to [updateCandidates] for
 * measurement. A candidate NEVER replaces, pre-empts, delays, or feeds the
 * user-facing result; it runs deferred, on a background dispatcher, and its
 * output is discarded except for a capped comparison record.
 *
 * Discipline (every item is a hard gate, not a preference):
 *  - candidate runs execute the RESOLVE stage only tonight. The resolve entry
 *    (`RulesPipeline.runResolveForSite`) is the one owned seam that providers
 *    already call with real inputs on every site task before falling back to
 *    compiled logic; search/episodes trigger requires a provider-side seam
 *    that is out of this phase's file ownership. DEVIATION from plan P2-4
 *    ("both sides" comparison): control-side metrics need that same provider
 *    seam, so tonight's records carry candidate-side outcome/wall-time/
 *    request-count plus an explicit `comparison: cand_only` marker — the
 *    record schema already reserves control fields for that follow-up.
 *  - sampling: <= ~10% of eligible runs/site/day persisted in prefs keyed by
 *    day, with a conservative floor of 1 and hard cap of 3 runs/site/day (a
 *    low-traffic device would otherwise never earn a sample; 3 extra requests
 *    against a healthy site is negligible — the real hammer-guard is the
 *    health skip below).
 *  - skip when the host is under a 429 cooldown, a HostHealth backoff window,
 *    or playbook-known-dead (never double-hammer a fragile site; skipped runs
 *    don't even count as eligible).
 *  - <= 1 candidate run per site concurrently (per-site mutex, try-lock — a
 *    busy site is simply skipped), <= 6 runs and <= 3 telemetry records per
 *    session.
 *  - bounded execution: whole candidate run under [SHADOW_RUN_BUDGET_MS]; the
 *    handoff into the compiled registry goes out with recordHealth = false so
 *    shadow measurements never mutate user-facing health state; ResolveBudget
 *    caps inside the registry apply unchanged.
 *  - telemetry is candidate-agnostic and content-free: site key, family,
 *    stage, coarse outcome classes, timings, counts — never URLs or bodies.
 *
 * Telemetry routing: through RemoteLogger (the OTA-relocatable client-error
 * sink used by OtaFetchDiagnostics) when the sink is alive; shadow records are
 * NEVER direct-fallback-posted — they are measurements, not emergencies, and
 * a device with no OTA sink has no candidates to measure anyway.
 */
object ShadowRunner {

    /** Wall budget for ONE deferred candidate run (the user path is never
     *  held for it; this only stops a zombie measurement). */
    private const val SHADOW_RUN_BUDGET_MS = 30_000L

    /** Session ceilings (process-lifetime; a session = one app run). */
    private const val MAX_RUNS_PER_SESSION = 6
    private const val MAX_RECORDS_PER_SESSION = 3

    /** Per-site/day sampler: allowed = min(CAP, max(FLOOR, eligible/10)) —
     *  the plan's 10% rule with a small-traffic floor and a hard cap. */
    private const val SAMPLE_DIVISOR = 10
    private const val SAMPLE_FLOOR = 1
    private const val SAMPLE_DAILY_CAP = 3

    private const val PREFS_NAME = "downloader_settings"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val siteLocks = ConcurrentHashMap<String, Mutex>()
    private val sessionRuns = AtomicInteger(0)
    private val sessionRecords = AtomicInteger(0)

    @Volatile private var appContext: Context? = null

    /** Armed candidate set: lowercase site key -> parsed pipeline. Empty when
     *  the active payload is not in shadow mode (or killed the family). */
    @Volatile private var candidates: Map<String, SitePipeline> = emptyMap()

    /** The shadow flag as published (used for telemetry context only). */
    @Volatile private var shadowFlag: FamilyFlag? = null

    /** Day formatter is NOT thread-safe — one per thread, like DebugLog. */
    private val dayFormat = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMdd", Locale.US) }

    /** Called from DynamicRulesManager.init(): prefs home for the sampler. */
    fun attach(context: Context) {
        try {
            appContext = context.applicationContext
        } catch (_: Exception) {}
    }

    /**
     * Arms/disarms the candidate set (called from the atomic publish block in
     * DynamicRulesManager.parseRulesJson on every activation — including with
     * an empty map, which is the disarm path for every non-shadow payload,
     * family kill switch, or minApp-gated family).
     */
    fun updateCandidates(pipelines: Map<String, SitePipeline>, flag: FamilyFlag?) {
        try {
            candidates = pipelines
            shadowFlag = flag
            if (pipelines.isEmpty()) {
                DebugLog.trace("ShadowRunner: no candidates armed (shadow order inactive)")
            } else {
                DebugLog.engine(
                    "ShadowRunner: ${pipelines.size} candidate pipeline(s) armed " +
                        "(shadow=${flag?.shadow == true}, percent=${flag?.percent ?: 100})"
                )
            }
        } catch (_: Exception) {}
    }

    /** True when a shadow candidate is armed for [site] exactly, or via
     *  terminal-host suffix match on [episodeHost] (same matching rule
     *  DynamicRulesManager.findPipelineKey applies to the published set). */
    fun armedCandidateKey(site: String, episodeHost: String?): String? {
        val armed = candidates
        if (armed.isEmpty()) return null
        val siteKey = site.lowercase()
        if (armed.containsKey(siteKey)) return siteKey
        val cleanHost = episodeHost?.lowercase()?.removePrefix("www.") ?: return null
        if (cleanHost.isBlank()) return null
        for ((key, sp) in armed) {
            val t = sp.terminal ?: continue
            if (t.hosts.any { cleanHost == it || cleanHost.endsWith(".$it") }) return key
        }
        return null
    }

    /**
     * The P2-4 trigger, called from RulesPipeline.runResolveForSite when the
     * PUBLISHED pipeline is absent — i.e. exactly when the provider is about
     * to run its compiled control for the same site task. Returns immediately:
     * the candidate (if sampled) runs detached and deferred, and the caller
     * gets `null` (=> compiled control runs => the user's result is control).
     */
    fun considerResolve(site: String, episodeUrl: String, quality: String, depth: Int, visitedSites: Set<String>) {
        try {
            if (site.isBlank() || episodeUrl.isBlank()) return
            val episodeHost = HttpClient.parsedHost(episodeUrl)
            val candidateKey = armedCandidateKey(site, episodeHost) ?: return
            val sp = candidates[candidateKey] ?: return
            if (sp.terminal == null) return

            // Health gate FIRST: a host under 429 cooldown, backoff, or
            // playbook-known-dead is skipped WITHOUT consuming eligibility —
            // measurements must never double-hammer a fragile site.
            if (!hostHealthy(episodeUrl, episodeHost, sp)) return

            // One candidate per site at a time (try-lock: a busy site is
            // simply skipped, never queued against the site's health).
            val lock = siteLocks.getOrPut(candidateKey) { Mutex() }
            if (!lock.tryLock()) return

            // Session + per-site/day sampling (plan P2-4: <=10% of eligible
            // runs/site/day; caps see [SAMPLE_DAILY_CAP]/[MAX_RUNS_PER_SESSION]).
            // Consumed only once the site lock is held, so skipped (busy)
            // tasks never burn quota.
            if (sessionRuns.get() >= MAX_RUNS_PER_SESSION || !tryConsumeRunQuota(candidateKey)) {
                try { lock.unlock() } catch (_: Exception) {}
                return
            }
            sessionRuns.incrementAndGet()

            var launched = false
            try {
                scope.launch {
                    try {
                        runCandidate(candidateKey, sp, episodeUrl, quality, depth, visitedSites)
                    } finally {
                        try { lock.unlock() } catch (_: Exception) {}
                    }
                }
                launched = true
                DebugLog.trace("ShadowRunner: candidate launched for $candidateKey (deferred, sampled)")
            } finally {
                if (!launched) {
                    // launch itself failed (scope torn down): release the slot
                    // so the site cannot stay locked for the whole session.
                    try { lock.unlock() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {
            // The shadow harness must never be a crash source.
        }
    }

    /** False when any host this candidate would touch is currently penalized. */
    private fun hostHealthy(episodeUrl: String, episodeHost: String?, sp: SitePipeline): Boolean {
        try {
            val probe = { h: String ->
                h.isNotBlank() &&
                    (!HostHealth.isUsable(h) || HostHealth.remainingBackoffMs(h) > 0L ||
                        DynamicRulesManager.isKnownDead(h))
            }
            if (episodeHost != null && probe(episodeHost)) return false
            val terminalHosts = sp.terminal?.hosts ?: emptyList()
            if (terminalHosts.any { probe(it) }) return false
        } catch (_: Exception) {
            return false // unsure state -> skip: measurements are optional
        }
        return true
    }

    /** Consume one sampling slot for [siteKey] today. Returns false when the
     *  10%/day budget is exhausted. Prefs keys are day-scoped so counters
     *  self-expire; totals are bounded by retaining a handful of day keys per
     *  site (stale ones are freed on the next write). */
    private fun tryConsumeRunQuota(siteKey: String): Boolean {
        val ctx = appContext ?: return false // no context (JVM tests): never run
        return try {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val day = dayFormat.get().format(Date())
            val eligKey = "shadow_elig_${siteKey}_$day"
            val runKey = "shadow_run_${siteKey}_$day"
            val eligible = prefs.getInt(eligKey, 0) + 1
            val allowed = minOf(SAMPLE_DAILY_CAP, maxOf(SAMPLE_FLOOR, eligible / SAMPLE_DIVISOR))
            val used = prefs.getInt(runKey, 0)
            val editor = prefs.edit().putInt(eligKey, eligible)
            // Free stale day buckets for this site (keep today + 1 previous).
            pruneStaleKeys(prefs, editor, siteKey, day)
            if (used >= allowed) {
                editor.apply()
                false
            } else {
                editor.putInt(runKey, used + 1).apply()
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Removes this site's day-scoped counter keys older than yesterday. */
    private fun pruneStaleKeys(prefs: android.content.SharedPreferences, editor: android.content.SharedPreferences.Editor, siteKey: String, today: String) {
        try {
            val keys = prefs.all.keys.iterator()
            while (keys.hasNext()) {
                val k = keys.next()
                if (!(k.startsWith("shadow_elig_${siteKey}_") || k.startsWith("shadow_run_${siteKey}_"))) continue
                val keyDay = k.substringAfterLast('_')
                if (keyDay != today) editor.remove(k)
            }
        } catch (_: Exception) {}
    }

    // Control-side seam (the P2-4 follow-up): the compiled control finishes in
    // the provider right after considerResolve() fires; the provider reports
    // its outcome class here (never URLs or content) so candidate records
    // become real both-engine comparisons. Entries older than 10 minutes are
    // never attached — a stale control may not be attributed to a later run.
    private val ctrlObservations = java.util.concurrent.ConcurrentHashMap<String, Triple<String, Long, Long>>()

    fun observeControl(site: String, success: Boolean, wallMs: Long = 0L) {
        val key = site.lowercase().trim()
        if (key.isBlank()) return
        ctrlObservations[key] = Triple(
            if (success) "success" else "no-result",
            wallMs,
            System.currentTimeMillis()
        )
    }

    /** Runs the deferred candidate and (budget permitting) emits one record. */
    private suspend fun runCandidate(siteKey: String, sp: SitePipeline, episodeUrl: String, quality: String, depth: Int, visitedSites: Set<String>) {
        val started = System.currentTimeMillis()
        var outcome = "error"
        var requests = 0
        var direct: String? = null
        try {
            val result = withTimeoutOrNull(SHADOW_RUN_BUDGET_MS) {
                RulesPipeline.runShadowResolve(siteKey, sp, episodeUrl, quality, depth, visitedSites)
            }
            val res = result
            if (res == null) {
                outcome = "error_timeout"
            } else {
                outcome = res.outcomeClass
                requests = res.fetchAttempts
                direct = res.direct
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            outcome = "error"
        }
        val elapsed = System.currentTimeMillis() - started
        try {
            DebugLog.resolve(
                "ShadowRunner[$siteKey] resolve: candidate=$outcome direct=${direct != null} in ${elapsed}ms (requests≈$requests) — user result untouched (compiled control)"
            )
        } catch (_: Exception) {}

        // Record: <=3/session, sink-gated, content-free.
        try {
            if (sessionRecords.incrementAndGet() > MAX_RECORDS_PER_SESSION) return
            if (DynamicRulesManager.getRemoteLoggingUrl().isBlank()) return
            // Consume the provider's control observation when fresh (10 min):
            // present -> a REAL both-engine comparison record (Scientist's
            // whole point); absent -> cand_only per the P2-4 note.
            val ctrl = ctrlObservations.remove(siteKey)
                ?.takeIf { System.currentTimeMillis() - it.third < 10 * 60_000L }
            val flag = shadowFlag
            RemoteLogger.report(
                type = "shadow_compare",
                message = "shadow resolve candidate $siteKey: $outcome in ${elapsed}ms",
                context = mapOf(
                    "site" to siteKey,
                    "family" to "pipelines",
                    "stage" to "resolve",
                    "cand_outcome" to outcome,
                    "cand_ms" to elapsed,
                    "cand_requests" to requests,
                    "cand_direct" to (direct != null),
                    "comparison" to if (ctrl != null) "both" else "cand_only",
                    "ctrl_outcome" to (ctrl?.first ?: "unobserved"),
                    "ctrl_ms" to (ctrl?.second ?: -1L),
                    "shadow_percent" to (flag?.percent ?: 100),
                    "rev" to DynamicRulesManager.getActiveRev(),
                    "rules_version" to DynamicRulesManager.version.value,
                    "sample_day" to dayFormat.get().format(Date()),
                    "app_version" to BuildConfig.VERSION_NAME
                )
            )
        } catch (_: Exception) {}
    }
}
