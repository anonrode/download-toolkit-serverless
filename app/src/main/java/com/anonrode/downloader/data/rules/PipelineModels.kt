package com.anonrode.downloader.data.rules

import org.json.JSONArray
import org.json.JSONObject

/**
 * Declarative per-site scrape pipelines — the OTA schema that lets a site's
 * search and episode navigation be rewritten as DATA instead of Kotlin.
 *
 * Shape (top-level playbook key "pipelines"):
 * ```
 * "pipelines": {
 *   "<site>": {
 *     "schema": 1,
 *     "search":   { "steps": [ <step>, ... ] },
 *     "episodes": { "steps": [ <step>, ... ] }
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
 * CLOSED VOCABULARY, v1. Deliberately no loops, no conditionals, no
 * expressions: execution is structurally bounded (max steps/sources enforced
 * at parse time, HttpClient caps apply), so a bad payload can be broken but
 * never hang or drain. Templates are pure substitution: {base} {query}
 * (auto-encoded) {url} (episodes stage: the show URL) and any {var} bound by
 * an earlier step. Adding a new primitive requires updating (1) the executor
 * RulesPipeline, (2) scripts/encrypt_rules.py validation, (3) the probe
 * harness mirror — otherwise the weirdness stays in the compiled provider
 * fallback, which every migrated provider keeps.
 *
 * schema != 1 entries are ignored (not an error): future schema versions can
 * ship additively while older apps keep using compiled fallbacks.
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

data class SitePipeline(
    val schema: Int = 1,
    val search: Pipeline? = null,
    val episodes: Pipeline? = null
)

private const val MAX_STEPS = 8
private const val MAX_SOURCES = 4

/** Lenient per-site parse: malformed input yields null (caller skips the
 *  site and journals it) — never an exception that could fail the whole
 *  playbook parse. */
internal fun parseSitePipeline(obj: JSONObject): SitePipeline? {
    return try {
        val schema = obj.optInt("schema", 1)
        if (schema != 1) return null // unknown future schema — ignore, keep fallback
        SitePipeline(
            schema = schema,
            search = obj.optJSONObject("search")?.let { parsePipeline(it) },
            episodes = obj.optJSONObject("episodes")?.let { parsePipeline(it) }
        ).takeIf { it.search != null || it.episodes != null }
    } catch (_: Exception) {
        null
    }
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
