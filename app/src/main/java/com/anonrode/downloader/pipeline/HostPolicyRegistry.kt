package com.anonrode.downloader.pipeline

object HostPolicyRegistry {

    val ASIAN_DRAMA_CLUSTER = setOf("asianc", "dramarain", "dramakey", "pluto", "nepu")
    val WESTERN_CLUSTER = setOf("nkiri", "9jarocks", "naijavault", "naijaprey")
    val DEDICATED_ASIAN_SITES = setOf("asianc", "dramarain", "dramakey")
    val SECONDARY_ASIAN_FALLBACK = setOf("nkiri", "9jarocks")

    // Built-in per-host socket caps. Speed-first policy (2026-10, 1DM batch):
    // start wide and let TurboDownloader's 429 ladder + drop-and-replace adapt
    // per connection instead of pre-throttling to 1-4 sockets. OTA-overridable
    // via contentPolicy.socketCaps (see effectiveSocketsForUrl). Clamp + overlay
    // semantics validated in scratch/test_zero_redo.py (section A).
    private val SENSITIVE_SOCKET_CAP_HOSTS = mapOf(
        "kissorgrab.com" to 6,
        "dl.plutomovies.com" to 6,
        "plutomovies.com" to 8,
        "wetafiles.com" to 12,
        "downloadwella.com" to 12,
        "loadedfiles.org" to 12,
        "loadedfiles.net" to 12,
        "loadedfiles.com" to 12,
        "loadedfiles.st" to 12,
        "loadedfiles.to" to 12
    )

    // Explicit allowlist of locker hosts known to omit intermediate CA certificates.
    // Never disable TLS verification globally for untrusted/arbitrary hosts.
    private val BROKEN_TLS_INTERMEDIATE_HOSTS = setOf(
        "wetafiles.com",
        "downloadwella.com",
        "loadedfiles.org",
        "loadedfiles.net",
        "loadedfiles.com",
        "loadedfiles.st",
        "loadedfiles.to",
        "kissorgrab.com",
        "plutomovies.com",
        // Live-verified broken/mismatched CA chains, documented in
        // resolvers/Resolvers.kt (lulacloud.com verify code 20; mixdrop.co).
        "lulacloud.com",
        "mixdrop.co"
    )

    /**
     * HLS-specific per-host lane caps. A master playlist and its segment CDN
     * can be different hosts; the effective cap is the MINIMUM over both, so
     * one hot CDN cannot inherit the other's allowance. OTA socketCaps still
     * win when lower. Global sizing lives in [LaneBudget].
     */
    private val HLS_HOST_CAP_RULES = listOf(
        "nexabloom.top" to 6,
        "vidsrc" to 6,          // vidsrc.* family
        "watching.onl" to 6,    // anitaku CDN family
        "anivideo.sbs" to 6     // anitaku CDN family
    )

    /**
     * Global concurrency controller. Without it, maxConcurrentDownloads(4) ×
     * 16 sockets is 64 flows; three real tasks observed 48 and the CDN
     * answered with per-IP throttling/403s.
     *
     * Contract:
     *  - ONE budget for all network transfers (direct Turbo + HLS).
     *  - water-filling fair share with MIN_LANES_PER_TASK; a task whose cap
     *    is lower (HLS/host cap) returns the surplus to the pool.
     *  - admission needs the minimum grant; callers that get 0 must requeue.
     *  - grants are advisory while a piece/fragment is in flight; Turbo reads
     *    the grant at its next CLAIM, so a shrink is a boundary event.
     *  - AIMD rebalancing every ~5s: grow +1..2 lanes while throughput
     *    improves, surrender a lane on a plateau (>10% test).
     */
    object LaneBudget {
        @Volatile var globalLanes: Int = 24
        const val MIN_LANES_PER_TASK = 4
        const val HLS_MAX_LANES = 8
        const val DIRECT_MAX_LANES = 16

        /** RC-7: no single task on a phone sustains more than this; anything
         *  above is a telemetry spike (resume-priming double-count) and is
         *  clamped before it can steer the controller or a display. */
        const val MAX_PLAUSIBLE_BPS = 500.0 * 1024 * 1024

        private data class Grant(
            var lanes: Int,
            val hls: Boolean,
            val cap: Int,
            var speedBps: Double = 0.0,
            var prevSpeedBps: Double = 0.0,
            /** Consecutive non-improving samples (RC-7 plateau veto). */
            var flatSamples: Int = 0
        )

        private val grants = LinkedHashMap<String, Grant>()
        private var lastAdmissionAt = 0L

        @Synchronized
        fun hasMinimumCapacity(): Boolean =
            globalLanes - grants.values.sumOf { it.lanes } >= MIN_LANES_PER_TASK

        @Synchronized
        fun noteAdmission() {
            lastAdmissionAt = System.currentTimeMillis()
        }

        @Synchronized
        fun msSinceAdmission(): Long =
            if (lastAdmissionAt == 0L) Long.MAX_VALUE
            else System.currentTimeMillis() - lastAdmissionAt

        /**
         * Admit a task with a fair-share grant. Returns 0 when the free pool
         * cannot cover the minimum — the caller must queue, never start.
         */
        @Synchronized
        fun reserve(taskId: String, requested: Int, hls: Boolean, plannedTasks: Int = 1): Int {
            val cap = if (hls) HLS_MAX_LANES else DIRECT_MAX_LANES
            val want = requested.coerceIn(1, cap)
            if (taskId.isBlank()) return want
            grants[taskId]?.let { return it.lanes }
            val used = grants.values.sumOf { it.lanes }
            val free = globalLanes - used
            if (free < MIN_LANES_PER_TASK) {
                com.anonrode.downloader.util.DebugLog.engine(
                    "lane admission deferred: free=$free < min=$MIN_LANES_PER_TASK active=${grants.size} used=$used"
                )
                return 0
            }
            // Water-filling admission: the free pool is divided by the number
            // of transfers still waiting for a share, so the FIRST of three
            // tasks takes ~8 (not all 24) and the rest can still be admitted.
            val divisor = maxOf(1, plannedTasks.coerceAtLeast(1) - grants.size)
            val share = maxOf(MIN_LANES_PER_TASK, free / divisor)
            val floor = minOf(MIN_LANES_PER_TASK, want)
            val grant = minOf(want, share, free).coerceAtLeast(floor)
            grants[taskId] = Grant(lanes = grant, hls = hls, cap = cap)
            com.anonrode.downloader.util.DebugLog.engine(
                "lane admission task=$taskId kind=${if (hls) "hls" else "direct"} grant=$grant " +
                    "active=${grants.size} used=${grants.values.sumOf { it.lanes }} free=${globalLanes - grants.values.sumOf { it.lanes }}"
            )
            return grant
        }

        @Synchronized
        fun release(taskId: String): Int {
            val g = grants.remove(taskId) ?: return 0
            com.anonrode.downloader.util.DebugLog.engine(
                "lane release task=$taskId lanes=${g.lanes} active=${grants.size} " +
                    "used=${grants.values.sumOf { it.lanes }}"
            )
            return g.lanes
        }

        @Synchronized
        fun grantFor(taskId: String): Int = grants[taskId]?.lanes ?: 0

        @Synchronized
        fun reportSpeed(taskId: String, bps: Double) {
            val g = grants[taskId] ?: return
            if (bps <= 0.0) return
            // RC-7 spike clamp (2026-10-21): the released build printed a
            // 2.4 GB/s lane sample (perTask=c6710cfb:6:2485125KB/s) from a
            // resume-priming telemetry tick. No single device pulls half a
            // gigabyte per second; values above the physical ceiling are a
            // telemetry defect and must neither steer the controller nor
            // reach any display surface.
            val clamped = minOf(bps, MAX_PLAUSIBLE_BPS)
            g.speedBps = clamped
        }

        /**
         * AIMD rebalance. HLS grants are fixed for the whole run (yt-dlp
         * cannot resize workers); direct grants move at most 2 lanes per
         * cycle and never below the minimum. Returns decision log lines.
         */
        @Synchronized
        fun rebalance(): List<String> {
            val active = grants.entries.toList()
            if (active.isEmpty()) return emptyList()
            val fair = fairAllocation(active)
            val messages = mutableListOf<String>()
            for ((id, g) in active) {
                if (g.hls) continue
                val target = fair[id] ?: g.lanes
                var next = g.lanes
                if (target > g.lanes) {
                    // AIMD growth only if the last speed sample improved.
                    if (g.prevSpeedBps <= 0.0 || g.speedBps >= g.prevSpeedBps * 1.10) {
                        next = minOf(target, g.lanes + 2)
                    }
                } else if (target < g.lanes) {
                    // Surplus lanes go back to the pool, at most one step and
                    // never below the guaranteed minimum.
                    next = maxOf(MIN_LANES_PER_TASK, g.lanes - 1)
                } else if (g.lanes > MIN_LANES_PER_TASK &&
                    g.prevSpeedBps > 0.0 &&
                    g.speedBps <= g.prevSpeedBps * 1.10
                ) {
                    // RC-7 plateau veto (2026-10-21): the marginal lane must be
                    // idle across TWO consecutive samples before lanes move —
                    // the 5 s samples swing 10-30x on real streams (41→1522→24
                    // KB/s on the Servamp pair, share-43), and a single-sample
                    // veto made the controller act on its own measurement
                    // noise. An improvement resets the counter.
                    g.flatSamples++
                    if (g.flatSamples >= 2) {
                        next = maxOf(MIN_LANES_PER_TASK, g.lanes - 1)
                    }
                }
                if (g.speedBps > g.prevSpeedBps * 1.10) g.flatSamples = 0
                if (next != g.lanes) {
                    messages.add(
                        "lane rebalance task=$id ${g.lanes}->$next " +
                            "speed=${(g.speedBps / 1024).toInt()}KB/s target=$target"
                    )
                    g.lanes = next
                }
                g.prevSpeedBps = g.speedBps
            }
            // Always emit the controller state (evidence for the synthetic
            // matrix): active count, granted/used/free lanes and per-task
            // speed, plus any decision lines above.
            messages.add(
                "lane state active=${grants.size} used=${grants.values.sumOf { it.lanes }} " +
                    "free=${globalLanes - grants.values.sumOf { it.lanes }} " +
                    "perTask=" + grants.entries.joinToString(",") {
                        "${it.key.take(8)}:${it.value.lanes}:${(it.value.speedBps / 1024).toInt()}KB/s"
                    }
            )
            return messages
        }

        private fun fairAllocation(entries: List<Map.Entry<String, Grant>>): Map<String, Int> {
            val alloc = LinkedHashMap<String, Int>()
            var remaining = globalLanes
            // Guarantee the minimum first (HLS grants are fixed for the run).
            for ((id, g) in entries) {
                val cap = if (g.hls) g.lanes else g.cap
                val min = minOf(MIN_LANES_PER_TASK, cap, remaining)
                alloc[id] = min
                remaining -= min
            }
            // Water-fill the surplus in rounds.
            while (remaining > 0) {
                val eligible = entries.filter { (id, g) ->
                    val cap = if (g.hls) g.lanes else g.cap
                    (alloc[id] ?: 0) < cap
                }
                if (eligible.isEmpty()) break
                val share = maxOf(1, remaining / eligible.size)
                var added = false
                for ((id, g) in eligible) {
                    if (remaining <= 0) break
                    val cap = if (g.hls) g.lanes else g.cap
                    val room = cap - (alloc[id] ?: 0)
                    val add = minOf(share, room, remaining)
                    if (add > 0) {
                        alloc[id] = (alloc[id] ?: 0) + add
                        remaining -= add
                        added = true
                    }
                }
                if (!added) break
            }
            return alloc
        }

        @Synchronized
        fun reservedLanes(): Int = grants.values.sumOf { it.lanes }

        @Synchronized
        fun activeTasks(): Int = grants.size

        @Synchronized
        fun snapshot(): String =
            "lane state active=${grants.size} used=${grants.values.sumOf { it.lanes }} " +
                "free=${globalLanes - grants.values.sumOf { it.lanes }} perTask=" +
                grants.entries.joinToString(",") { "${it.key.take(8)}:${it.value.lanes}" }

        @Synchronized
        fun clearForTest() {
            grants.clear()
            lastAdmissionAt = 0L
            globalLanes = 24
        }
    }

    fun effectiveHlsLanesForUrl(masterUrl: String, segmentUrl: String?, requested: Int): Int {
        val want = requested.coerceIn(1, LaneBudget.HLS_MAX_LANES)
        fun capFor(url: String): Int {
            if (url.isBlank()) return LaneBudget.HLS_MAX_LANES
            val host = UrlClassifier.extractHost(url)
            // Reuse the OTA overlay + built-in locker caps, then apply the
            // HLS-specific CDN rules (which may be lower).
            val socketCap = effectiveSocketsForUrl(url, LaneBudget.HLS_MAX_LANES)
            val builtIn = HLS_HOST_CAP_RULES.firstOrNull { (domain, _) ->
                host == domain || host.endsWith(".$domain") || host.contains(domain)
            }?.second ?: LaneBudget.HLS_MAX_LANES
            return minOf(socketCap, builtIn).coerceIn(1, LaneBudget.HLS_MAX_LANES)
        }
        return minOf(want, capFor(masterUrl), capFor(segmentUrl ?: masterUrl))
    }

    fun effectiveSocketsForUrl(url: String, requestedSockets: Int): Int {
        val clampedRequest = requestedSockets.coerceIn(1, 16)
        if (clampedRequest == 1) return 1
        val host = UrlClassifier.extractHost(url)
        // OTA overlay first (contentPolicy.socketCaps), built-ins second — the
        // overlay can retune any host (including the capped ones) without an
        // APK; removing the section restores these compiled caps. Same
        // read-through precedence style as DynamicRulesManager.resolveReferer.
        val otaCap = com.anonrode.downloader.data.rules.DynamicRulesManager
            .getContentPolicy().socketCaps.entries
            .firstOrNull { (domain, _) -> host == domain || host.endsWith(".$domain") }
            ?.value
            ?.coerceIn(1, 16)
        if (otaCap != null) return minOf(otaCap, clampedRequest)
        val matchedCap = SENSITIVE_SOCKET_CAP_HOSTS.entries
            .firstOrNull { (domain, _) -> host == domain || host.endsWith(".$domain") }
            ?.value
        return if (matchedCap != null) {
            minOf(matchedCap, clampedRequest)
        } else {
            clampedRequest
        }
    }

    /**
     * Per-host adaptive socket occupancy (board rows 5/29, 2026-10-21).
     *
     * The static cap answers "how many sockets MAY this host take"; this
     * controller answers "how many is this host ACTUALLY giving us goodput
     * with right now", per process session:
     *  - START at the host cap (the owner's speed-first policy),
     *  - FAST multiplicative backoff on 429/403/503 (halve immediately; a
     *    rejected request pattern must not be repeated at the old width),
     *  - SLOW additive recovery with hysteresis: +1 only after
     *    [RECOVERY_CONFIRMATIONS] consecutive improving goodput samples.
     *    This is the two-sample rule 188aa09 added for lane SHRINK, applied
     *    symmetrically here so one noisy sample can neither grow nor shrink;
     *  - clamp 1..hostCap at every entry point.
     *
     * Session memory is the point: a host that tripped at 16 sockets starts
     * the NEXT task on that host at 8 instead of re-tripping, then climbs back
     * only while goodput keeps improving. Keyed by host, so one hot CDN never
     * inherits another host's penalty.
     */
    object HostSocketOccupancy {
        const val MIN_SOCKETS = 1
        const val RECOVERY_CONFIRMATIONS = 2
        const val RECOVERY_IMPROVE_RATIO = 1.05

        private data class State(
            var sockets: Int,
            var lastBps: Double = 0.0,
            var improvingSamples: Int = 0
        )

        private val hosts = LinkedHashMap<String, State>()

        private fun keyFor(url: String): String {
            val host = UrlClassifier.extractHost(url)
            return host.ifBlank { url.take(120) }
        }

        private fun stateFor(url: String, hostCap: Int): Pair<State, Int> {
            val cap = hostCap.coerceIn(MIN_SOCKETS, 16)
            val key = keyFor(url)
            val state = hosts.getOrPut(key) { State(sockets = cap) }
            if (state.sockets !in MIN_SOCKETS..cap) {
                state.sockets = state.sockets.coerceIn(MIN_SOCKETS, cap)
            }
            return state to cap
        }

        /** Occupancy a new task on this host should start at (session memory;
         *  first use of a host starts at its cap). */
        @Synchronized
        fun currentForUrl(url: String, hostCap: Int): Int = stateFor(url, hostCap).first.sockets

        /** 429/403/503: halve NOW, forget any recovery progress. */
        @Synchronized
        fun recordBackoff(url: String, hostCap: Int): Int {
            val (state, _) = stateFor(url, hostCap)
            state.sockets = maxOf(MIN_SOCKETS, state.sockets / 2)
            state.improvingSamples = 0
            state.lastBps = 0.0
            return state.sockets
        }

        /**
         * Goodput sample (aggregate bytes/s). Sockets grow by ONE only after
         * two consecutive samples improve by [RECOVERY_IMPROVE_RATIO]; a flat
         * or regressing sample resets the run (hysteresis). Returns the
         * occupancy the caller should run at.
         */
        @Synchronized
        fun recordGoodput(url: String, hostCap: Int, bps: Double): Int {
            val (state, cap) = stateFor(url, hostCap)
            if (!bps.isFinite() || bps <= 0.0) {
                state.improvingSamples = 0
                return state.sockets
            }
            val improving = state.lastBps > 0.0 && bps >= state.lastBps * RECOVERY_IMPROVE_RATIO
            state.lastBps = bps
            if (state.sockets >= cap) {
                state.improvingSamples = 0
                return state.sockets
            }
            if (improving) {
                state.improvingSamples++
                if (state.improvingSamples >= RECOVERY_CONFIRMATIONS) {
                    state.sockets = minOf(cap, state.sockets + 1)
                    state.improvingSamples = 0
                }
            } else {
                state.improvingSamples = 0
            }
            return state.sockets
        }

        @Synchronized
        fun clearForTest() {
            hosts.clear()
        }
    }

    fun allowsPermissiveTlsFallback(url: String): Boolean {
        val host = UrlClassifier.extractHost(url)
        return BROKEN_TLS_INTERMEDIATE_HOSTS.any { domain ->
            host == domain || host.endsWith(".$domain")
        }
    }
}
