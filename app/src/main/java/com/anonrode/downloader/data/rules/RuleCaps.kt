package com.anonrode.downloader.data.rules

import org.json.JSONArray
import org.json.JSONObject

/**
 * Load-time validation caps for the OTA payload (Phase 2, plan slice P2-3;
 * prior art: Safe Browsing client-requested caps §1.2, AdGuard in-registry
 * validation §1.5, design doc §3.6).
 *
 * The publish-side validator (scripts/encrypt_rules.py — already updated to
 * validate rev + flags and the shared caps) is the HARD gate; this client-side
 * scan runs the SAME caps at load time. This rev cycle the client phase is
 * WARN ONLY (the plan's canary rule): every violation is journaled and counted
 * to the telemetry sink, but a violating payload still activates. Enforcement
 * flips only after field data shows the caps clean for a full payload cycle.
 *
 * Caps, all mirroring existing engine bounds:
 *  - whole decrypted payload <= [MAX_DECRYPTED_BYTES] (2 MB);
 *  - regex PATTERN strings <= [MAX_REGEX_CHARS] — 300, matching the
 *    publish-side MAX (coordinator correction 2026-10-15; Java's Pattern is a
 *    backtracking engine with no complexity guarantee — Rust-regex §1.11 is
 *    the direction, so cap what we cannot prove);
 *  - pipeline steps / per-step sources against PipelineModels' MAX_STEPS (8)
 *    and MAX_SOURCES (4) — the same numbers the executor refuses at parse;
 *  - pipelines family entry count <= [MAX_PIPELINES] (§3.6 example);
 *  - every URL-bearing string field must be a plausible http(s) endpoint —
 *    the remote_logging_url guard generalized to the whole payload (deny
 *    file://, ws://, javascript:, deny junk schemes).
 *
 * WARN-only is itself a safety property: this scan runs inside the activation
 * parse path, so it is bounded work and can NEVER influence the payload's own
 * fate this cycle.
 */
object RuleCaps {

    /** Whole decrypted payload ceiling (UTF-8 bytes) — the Safe Browsing
     *  shape: "caps requested by the client ... protecting memory/bandwidth"
     *  (§1.2). */
    const val MAX_DECRYPTED_BYTES = 2_000_000

    /** Regex PATTERN length cap. 300 = publish-side MAX (coordinator 2026-10-15). */
    const val MAX_REGEX_CHARS = 300

    /** Mirrors PipelineModels' private MAX_STEPS (deliberate restatement: this
     *  file does not edit that one; if the executor ever raises its bound this
     *  scan over-warns, which fails safe). */
    const val MAX_STEPS = 8

    /** Mirrors PipelineModels' private MAX_SOURCES (see the MAX_STEPS note). */
    const val MAX_SOURCES = 4

    /** Pipelines family entry count (design §3.6: "pipelines <= 64 entries"). */
    const val MAX_PIPELINES = 64

    /** Keys whose string value is a regex PATTERN: the bare "regex" of
     *  urlBinds/terminal/seasonFromUrl plus anything ending "Regex"
     *  (stripRegex/hrefRegex/regexText/regexUrl). */
    private const val REGEX_KEY = "regex"
    private const val REGEX_SUFFIX = "Regex"

    /** Absolute-URL probe: any value carrying an explicit scheme must then be
     *  http(s) — the existing remote_logging_url contract generalized to
     *  every URL-bearing field in the payload. */
    private val SCHEMED_URL = Regex("""^[A-Za-z][A-Za-z0-9+.\-]*://""")

    /** Walk budgets for one scan: a hostile/buggy payload must not make the
     *  WARN scan itself expensive (it runs on the activation path). */
    private const val MAX_NODES = 20_000
    private const val MAX_DEPTH = 24
    private const val MAX_VIOLATIONS = 32

    /** Per-scan mutable budget (instance-local to the scan call — no shared
     *  state, so concurrent parses can never corrupt each other's accounting). */
    private class Budget {
        var nodes = 0
        var depth = 0
    }

    /**
     * WARN-phase cap scan. Returns human-readable violation descriptions
     * (empty = clean); never throws; bounded work. Envelope size is checked
     * from [jsonStr]; structural caps from the parsed [obj].
     */
    fun scan(jsonStr: String, obj: JSONObject): List<String> {
        val violations = mutableListOf<String>()
        val budget = Budget()
        try {
            val bytes = jsonStr.toByteArray(Charsets.UTF_8).size
            if (bytes > MAX_DECRYPTED_BYTES) {
                violations.add("payload size $bytes > $MAX_DECRYPTED_BYTES bytes")
            }
            walk(obj, "", violations, budget)
            checkPipelines(obj, violations)
        } catch (_: Exception) {
            // The WARN scan itself must never fail an activation.
        }
        return violations.take(MAX_VIOLATIONS)
    }

    /** Payload-family structural caps: pipelines count, steps-per-stage,
     *  sources-per-step — the load-bearing numbers the executor enforces. */
    private fun checkPipelines(obj: JSONObject, out: MutableList<String>) {
        if (out.size >= MAX_VIOLATIONS) return
        val pipelines = obj.optJSONObject("pipelines") ?: return
        val keys = pipelines.keys()
        var count = 0
        while (keys.hasNext()) {
            if (out.size >= MAX_VIOLATIONS) return
            count++
            val site = keys.next()
            val siteObj = pipelines.optJSONObject(site) ?: continue
            for (stage in listOf("search", "episodes", "resolve")) {
                val stageObj = siteObj.optJSONObject(stage) ?: continue
                val steps = stageObj.optJSONArray("steps") ?: continue
                if (steps.length() > MAX_STEPS) {
                    out.add("pipelines/$site/$stage: ${steps.length()} steps > MAX_STEPS($MAX_STEPS)")
                }
                for (i in 0 until steps.length()) {
                    if (out.size >= MAX_VIOLATIONS) return
                    val step = steps.optJSONObject(i) ?: continue
                    val sources = step.optJSONArray("sources") ?: continue
                    if (sources.length() > MAX_SOURCES) {
                        out.add("pipelines/$site/$stage step$i: ${sources.length()} sources > MAX_SOURCES($MAX_SOURCES)")
                    }
                }
            }
        }
        if (count > MAX_PIPELINES) {
            out.add("pipelines: $count entries > expected max $MAX_PIPELINES")
        }
    }

    /** Recursive value walk: regex-pattern length + URL-scheme shape over
     *  every string field. Bounded by [MAX_NODES]/[MAX_DEPTH]. */
    private fun walk(node: Any?, path: String, out: MutableList<String>, b: Budget) {
        if (out.size >= MAX_VIOLATIONS || b.nodes >= MAX_NODES) return
        b.nodes++
        when (node) {
            is JSONObject -> {
                if (b.depth >= MAX_DEPTH) return
                val keys = node.keys()
                while (keys.hasNext()) {
                    if (out.size >= MAX_VIOLATIONS || b.nodes >= MAX_NODES) return
                    val k = keys.next()
                    val child = node.opt(k)
                    if (child is String) {
                        checkString(k, child, path, out)
                    } else {
                        b.depth++
                        walk(child, if (path.isEmpty()) k else "$path.$k", out, b)
                        b.depth--
                    }
                }
            }
            is JSONArray -> {
                if (b.depth >= MAX_DEPTH) return
                for (i in 0 until node.length()) {
                    if (out.size >= MAX_VIOLATIONS || b.nodes >= MAX_NODES) return
                    val child = node.opt(i)
                    if (child is String) {
                        checkString("", child, "$path[$i]", out)
                    } else {
                        b.depth++
                        walk(child, "$path[$i]", out, b)
                        b.depth--
                    }
                }
            }
        }
    }

    /** One field check: regex-pattern length, then URL-scheme shape. */
    private fun checkString(key: String, value: String, path: String, out: MutableList<String>) {
        if (out.size >= MAX_VIOLATIONS) return
        val isRegex = key == REGEX_KEY || key.endsWith(REGEX_SUFFIX) ||
            (key == "pattern" && (path.startsWith("downloader_routing") || path.startsWith("proxy_unwrappers")))
        if (isRegex && value.length > MAX_REGEX_CHARS) {
            out.add("$path/$key: regex pattern ${value.length} chars > $MAX_REGEX_CHARS")
            return
        }
        if (SCHEMED_URL.containsMatchIn(value) &&
            !value.startsWith("http://") &&
            !value.startsWith("https://")
        ) {
            // Endpoint-shape guard (the remote_logging_url contract applied to
            // every URL-bearing field): non-http(s) schemes are unreviewed
            // attack surface (file://, ws://, javascript:) — this cycle WARN.
            out.add("$path/$key: absolute URL not http(s) ('${value.take(24)}…')")
        }
    }
}
