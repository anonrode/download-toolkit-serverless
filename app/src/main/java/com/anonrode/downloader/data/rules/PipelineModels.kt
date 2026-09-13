package com.anonrode.downloader.data.rules

import org.json.JSONArray
import org.json.JSONObject

/**
 * Declarative per-site scrape pipelines — the OTA schema that lets a site's
 * search, episode navigation AND link-crack be rewritten as DATA instead of
 * Kotlin.
 *
 * Shape (top-level playbook key "pipelines"):
 * ```
 * "pipelines": {
 *   "<site>": {
 *     "schema": 1,
 *     "search":   { "steps": [ <step>, ... ] },
 *     "episodes": { "steps": [ <step>, ... ] },
 *
 *     "resolve":  { "steps": [ <step>, ... ] },     // optional; drives
 *     "terminal": {                                 // resolveEpisode.
 *       "source": "entry" | "final",                //   entry = extract from
 *       "regex": "...", "group": 1, "decode": true, //          the EPISODE URL
 *       "spec": "{var}",                            //   final = template bound
 *       "hosts": ["downloadwella.com", ...],        //          by the last step
 *       "mode": "probe" | "handoff",                //   referer: template, probe only
 *       "referer": "{base}/"
 *     }
 *   }
 * }
 * ```
 *
 * Step:
 * ```
 * {
 *   "sources": [ { "url": tmpl, "method": "GET"|"POST",
 *                  "headers": {name: tmpl}, "form": {field: tmpl} } ],  // 1..4
 *   "mode": "single" | "failover" | "merge",
 *   "as":   "html" | "json" | "rss",
 *   "bind": { "<var>": {"regex": .., "group": n} | {"json": path} | {"selector": css, "attr": a} },
 *   "items": { ... stage-specific extraction spec ... }
 * }
 * ```
 *
 * TERMINAL GOVERNANCE (added with the resolve stage, 2026-09-12): `resolve` is
 * only ever accepted together with a VALID `terminal` — a crack without a
 * trusted endpoint is exactly what the hostile review of the catch-all design
 * rejected. Rules: hosts MUST be a non-empty list of pure domain names (a
 * terminal URL whose host does not suffix-match them is refused, closing the
 * "downloaded an ad MP4" class); mode "probe" additionally requires the URL
 * to pass HttpClient.probeTerminal (no-redirect Range gate) before it is
 * returned; mode "handoff" feeds the (host-gated) candidate back to the
 * compiled ResolverRegistry — recursion is impossible because the registry
 * never calls back into the pipeline. The whole resolve run carries a fetch
 * byte budget (see RulesPipeline).
 *
 * CLOSED VOCABULARY, v1. Deliberately no loops, no conditionals, no
 * expressions: execution is structurally bounded (max steps/sources enforced
 * at parse time, HttpClient caps apply), so a bad payload can be broken but
 * never hang or drain. Templates are pure substitution: {base} {query}
 * (URL-encoded) {queryRaw} (unencoded, for POST form bodies) {url} (episodes
 * stage: the show URL) and any {var} bound by an earlier step. Adding a new
 * primitive requires updating (1) the executor
 * RulesPipeline, (2) scripts/encrypt_rules.py validation, (3) the probe
 * harness mirror — otherwise the weirdness stays in the compiled provider
 * fallback, which every migrated provider keeps.
 *
 * schema != 1 entries are ignored (not an error): future schema versions can
 * ship additively while older apps keep using compiled fallbacks. The resolve
 * stage deliberately rides schema 1 as OPTIONAL fields so old apps (which
 * never read them) keep their search/episodes pipelines untouched.
 */

data class PipelineSource(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val form: Map<String, String> = emptyMap()
)

data class PipelineBind(
    val name: String,
    val regex: String = "",
    val group: Int = 0,
    val jsonPath: String = "",
    val selector: String = "",
    val attr: String = ""
)

data class PipelineStep(
    val sources: List<PipelineSource>,
    val mode: String = "single",
    val asFormat: String = "html",
    val bind: List<PipelineBind> = emptyList(),
    val items: JSONObject? = null
)

data class Pipeline(
    val steps: List<PipelineStep>
)

/**
 * The trusted END of a resolve pipeline. See the file doc for the governance
 * rules; every field here is validated at PARSE time so the executor can
 * trust the shape (only its DATA can still be wrong).
 */
data class PipelineTerminal(
    /** "entry": run [regex] against the raw episode URL (no fetch needed —
     *  the nkiri-wrapper class of fix); "final": render [spec] from bound vars. */
    val source: String,
    val regex: String = "",
    val group: Int = 1,
    /** URL-decode the extracted value once (wrapper params are percent-encoded). */
    val decode: Boolean = false,
    /** "final" extraction template, e.g. "{dlink}". */
    val spec: String = "",
    /** REQUIRED, non-empty: terminal URL host must equal or suffix-match one. */
    val hosts: List<String>,
    /** "probe": validate the terminal with HttpClient.probeTerminal before
     *  returning it; "handoff": feed it back to the compiled ResolverRegistry. */
    val mode: String,
    val referer: String = ""
)

data class SitePipeline(
    val schema: Int = 1,
    val search: Pipeline? = null,
    val episodes: Pipeline? = null,
    val resolve: Pipeline? = null,
    val terminal: PipelineTerminal? = null
)

private const val MAX_STEPS = 8
private const val MAX_SOURCES = 4

/** Lenient per-site parse: malformed input yields null (caller skips the
 *  site and journals it) — never an exception that could fail the whole
 *  playbook parse. A bad resolve/terminal NEVER invalidates the site's
 *  search/episodes pipelines; it only nulls the resolve stage. */
internal fun parseSitePipeline(obj: JSONObject): SitePipeline? {
    return try {
        val schema = obj.optInt("schema", 1)
        if (schema != 1) return null // unknown future schema — ignore, keep fallback
        val resolvePl = obj.optJSONObject("resolve")?.let { parsePipeline(it) }
        val terminal = obj.optJSONObject("terminal")?.let { parseTerminal(it) }
        SitePipeline(
            schema = schema,
            search = obj.optJSONObject("search")?.let { parsePipeline(it) },
            episodes = obj.optJSONObject("episodes")?.let { parsePipeline(it) },
            // resolve without a valid terminal is refused BY DESIGN (the
            // trust lives in the terminal gate, not in the hops).
            resolve = if (resolvePl != null && terminal != null) resolvePl else null,
            terminal = if (resolvePl != null && terminal != null) terminal else null
        ).takeIf { it.search != null || it.episodes != null || it.resolve != null }
    } catch (_: Exception) {
        null
    }
}

private fun parseTerminal(obj: JSONObject): PipelineTerminal? {
    val source = obj.optString("source").lowercase()
    if (source != "entry" && source != "final") return null
    val mode = obj.optString("mode").lowercase()
    if (mode != "probe" && mode != "handoff") return null
    val hosts = jsonStringList(obj.optJSONArray("hosts"))
        .map { it.lowercase().trim().removePrefix("*.") }
        .filter { it.isNotBlank() }
    if (hosts.isEmpty()) return null
    // Pure domain names only: a "host" carrying scheme/path/port would be the
    // old substring-marker style and re-opens the ad-slot matching hole.
    if (hosts.any { it.any { c -> c in "/: " } }) return null
    val group = obj.optInt("group", 1)
    if (group < 0 || group > 32) return null
    val regex = obj.optString("regex")
    if (source == "entry") {
        if (regex.isBlank()) return null
        try { Regex(regex) } catch (_: Exception) { return null } // compile-check now, not on the user's tap
    }
    val spec = obj.optString("spec")
    if (source == "final" && spec.isBlank()) return null
    return PipelineTerminal(
        source = source,
        regex = regex,
        group = group,
        decode = obj.optBoolean("decode", false),
        spec = spec,
        hosts = hosts,
        mode = mode,
        referer = obj.optString("referer")
    )
}

private fun parsePipeline(obj: JSONObject): Pipeline? {
    val stepsArr = obj.optJSONArray("steps") ?: return null
    if (stepsArr.length() == 0 || stepsArr.length() > MAX_STEPS) return null
    val steps = mutableListOf<PipelineStep>()
    for (i in 0 until stepsArr.length()) {
        val s = stepsArr.optJSONObject(i) ?: return null
        steps.add(parseStep(s) ?: return null)
    }
    return Pipeline(steps)
}

private fun parseStep(obj: JSONObject): PipelineStep? {
    val sourcesArr = obj.optJSONArray("sources") ?: return null
    if (sourcesArr.length() == 0 || sourcesArr.length() > MAX_SOURCES) return null
    val sources = mutableListOf<PipelineSource>()
    for (i in 0 until sourcesArr.length()) {
        val s = sourcesArr.optJSONObject(i) ?: return null
        val url = s.optString("url")
        if (url.isBlank()) return null
        val method = s.optString("method", "GET").uppercase()
        if (method != "GET" && method != "POST") return null
        sources.add(
            PipelineSource(
                url = url,
                method = method,
                headers = stringMap(s.optJSONObject("headers")),
                form = stringMap(s.optJSONObject("form"))
            )
        )
    }
    val mode = obj.optString("mode", "single").lowercase()
    if (mode != "single" && mode != "failover" && mode != "merge") return null
    val asFormat = obj.optString("as", "html").lowercase()
    if (asFormat != "html" && asFormat != "json" && asFormat != "rss") return null

    val binds = mutableListOf<PipelineBind>()
    val bindObj = obj.optJSONObject("bind")
    if (bindObj != null) {
        val keys = bindObj.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val spec = bindObj.optJSONObject(name) ?: continue
            binds.add(
                PipelineBind(
                    name = name,
                    regex = spec.optString("regex"),
                    group = spec.optInt("group", 0),
                    jsonPath = spec.optString("json"),
                    selector = spec.optString("selector"),
                    attr = spec.optString("attr")
                )
            )
        }
    }

    return PipelineStep(
        sources = sources,
        mode = mode,
        asFormat = asFormat,
        bind = binds,
        items = obj.optJSONObject("items")
    )
}

private fun stringMap(obj: JSONObject?): Map<String, String> {
    if (obj == null) return emptyMap()
    val out = mutableMapOf<String, String>()
    val keys = obj.keys()
    while (keys.hasNext()) {
        val k = keys.next()
        val v = obj.optString(k)
        if (v.isNotBlank()) out[k] = v
    }
    return out
}

internal fun jsonStringList(arr: JSONArray?): List<String> {
    if (arr == null) return emptyList()
    val out = mutableListOf<String>()
    for (i in 0 until arr.length()) {
        val s = arr.optString(i)
        if (s.isNotBlank()) out.add(s)
    }
    return out
}
