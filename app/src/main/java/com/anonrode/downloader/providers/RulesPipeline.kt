package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.data.rules.Pipeline
import com.anonrode.downloader.data.rules.PipelineSource
import com.anonrode.downloader.data.rules.PipelineStep
import com.anonrode.downloader.data.rules.jsonStringList
import com.anonrode.downloader.util.DebugLog
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Locale

/**
 * Executor for the declarative step-pipeline schema (see PipelineModels.kt for
 * the shape and the closed-vocabulary governance rule).
 *
 * What this is: a fixed set of DATA-driven primitives — GET/POST fetch,
 * failover/merge across endpoints, JSON-path walking, Jsoup extraction, label
 * chains — with hard structural bounds (max 8 steps, max 4 sources per step,
 * no loops, no conditionals, HttpClient byte caps). A rules payload can be
 * wrong; it cannot be unbounded. There is deliberately no code-execution
 * channel: anything that outgrows this vocabulary stays in the compiled
 * provider fallback every migrated site keeps.
 *
 * Contract with providers: [runSearch] returns [] and [runEpisodes] returns
 * null on any failure — the caller then runs its compiled path. A pipeline
 * that produces an EMPTY result also falls through (non-empty wins), so a
 * broken selector degrades to compiled behavior instead of blanking the UI.
 */
object RulesPipeline {

    /** Result of an episodes pipeline: nullable meta fields (absent when the
     *  rules carry no "meta" spec — provider keeps its own) + episode list. */
    data class PipelineEpisodes(
        val metaTitle: String? = null,
        val metaPoster: String? = null,
        val metaSynopsis: String? = null,
        val episodes: List<EpisodeItem> = emptyList()
    )

    // ---------------------------------------------------------------- search

    suspend fun runSearch(site: String, pipeline: Pipeline, query: String): List<ShowCard> {
        return try {
            runSearchInner(site, pipeline, query)
        } catch (e: Exception) {
            DebugLog.error("$site pipeline search: aborted (${e.javaClass.simpleName}: ${e.message})")
            emptyList()
        }
    }

    private suspend fun runSearchInner(site: String, pipeline: Pipeline, query: String): List<ShowCard> {
        val bases = DynamicRulesManager.getBaseUrls(site)
        val base = (bases.firstOrNull { it.isNotBlank() } ?: "").trimEnd('/')

        val effectiveQuery = applyQueryTransform(query, firstItems(pipeline)?.optJSONObject("queryTransform"))
        val vars = mutableMapOf(
            "base" to base,
            "url" to "",
            "query" to URLEncoder.encode(effectiveQuery, "UTF-8")
        )

        var cards = emptyList<ShowCard>()
        for (step in pipeline.steps) {
            val items = runStep(site, "search", step, vars, tag = "search") { outcome ->
                if (step.items != null) extractSearchCards(site, step, outcome, vars) else emptyList()
            }
            if (step.items != null) cards = items
        }
        if (cards.isNotEmpty()) {
            DebugLog.resolve("$site pipeline search: ${cards.size} cards for \"$effectiveQuery\"")
        }
        return cards
    }

    // -------------------------------------------------------------- episodes

    suspend fun runEpisodes(site: String, pipeline: Pipeline, showUrl: String): PipelineEpisodes? {
        return try {
            runEpisodesInner(site, pipeline, showUrl)
        } catch (e: Exception) {
            DebugLog.error("$site pipeline episodes: aborted (${e.javaClass.simpleName}: ${e.message})")
            null
        }
    }

    private suspend fun runEpisodesInner(site: String, pipeline: Pipeline, showUrl: String): PipelineEpisodes? {
        val bases = DynamicRulesManager.getBaseUrls(site)
        val vars = mutableMapOf(
            "base" to (bases.firstOrNull { it.isNotBlank() } ?: "").trimEnd('/'),
            "url" to showUrl,
            "query" to ""
        )

        var result: PipelineEpisodes? = null
        var fetched = false
        for (step in pipeline.steps) {
            val items = runStep(site, "episodes", step, vars, tag = null) { outcome ->
                if (step.items != null) {
                    fetched = true
                    listOf(extractEpisodes(site, step, outcome, vars, showUrl))
                } else emptyList()
            }
            if (step.items != null) result = items.firstOrNull()
        }
        // A pipeline that fetched nothing is indistinguishable from a dead
        // host vs a bad rule — hand back null so the compiled path runs.
        if (!fetched || result == null) return null
        DebugLog.resolve("$site pipeline episodes: ${result.episodes.size} items from $showUrl")
        return result
    }

    // -------------------------------------------------------- step execution

    internal class StepOutcome(val body: String, val doc: Document?, val json: Any?, val baseUrl: String)

    private fun firstItems(pipeline: Pipeline): JSONObject? =
        pipeline.steps.firstNotNullOfOrNull { it.items }

    /** Runs one step's source list according to its mode, returning the
     *  extracted items of the winning source(s). */
    private suspend fun <T> runStep(
        site: String,
        stage: String,
        step: PipelineStep,
        vars: MutableMap<String, String>,
        tag: String?,
        extract: (StepOutcome) -> List<T>
    ): List<T> {
        val expanded = expandSources(site, step, vars)
        val merged = mutableListOf<T>()
        var lastItems = emptyList<T>()

        for ((source, sourceVars) in expanded) {
            val outcome = fetchSource(site, stage, step, source, sourceVars, tag) ?: continue
            applyBinds(step, outcome, vars)
            val items = extract(outcome)
            when (step.mode) {
                "merge" -> merged.addAll(items)
                "failover" -> {
                    lastItems = items
                    if (items.isNotEmpty()) return items
                }
                else -> return items // "single": first successful fetch is authoritative
            }
        }
        // failover: every source empty/failed → return the last attempt's
        // result (usually empty), matching compiled failover-over-bases loops.
        return if (step.mode == "failover") lastItems else merged
    }

    /** Failover steps with a single {base} source expand across the site's
     *  OTA domain + mirrors, so mirror rotation stays a rules edit. */
    private fun expandSources(
        site: String,
        step: PipelineStep,
        vars: Map<String, String>
    ): List<Pair<PipelineSource, Map<String, String>>> {
        if (step.mode == "failover" && step.sources.size == 1 && step.sources[0].url.contains("{base}")) {
            val bases = DynamicRulesManager.getBaseUrls(site).filter { it.isNotBlank() }
            if (bases.isNotEmpty()) {
                return bases.map { b ->
                    step.sources[0] to (vars + ("base" to b.trimEnd('/')))
                }
            }
        }
        return step.sources.map { it to vars }
    }

    private suspend fun fetchSource(
        site: String,
        stage: String,
        step: PipelineStep,
        source: PipelineSource,
        vars: Map<String, String>,
        tag: String?
    ): StepOutcome? {
        val url = renderTemplate(source.url, vars) { name -> vars[name] }
        if (url.isNullOrBlank() || !url.startsWith("http")) {
            DebugLog.error("$site pipeline $stage: unresolvable source url \"${source.url}\"")
            return null
        }
        val referer = source.headers.entries
            .firstOrNull { it.key.equals("Referer", ignoreCase = true) }
            ?.value?.let { renderTemplate(it, vars) { n -> vars[n] } }
        val headers = source.headers
            .filterKeys { !it.equals("Referer", ignoreCase = true) }
            .mapValues { renderTemplate(it.value, vars) { n -> vars[n] } ?: "" }

        val body = if (source.method == "POST") {
            val form = source.form.mapValues { renderTemplate(it.value, vars) { n -> vars[n] } ?: "" }
            HttpClient.postForm(url, form, referer, headers, tag)
        } else {
            HttpClient.getText(url, referer, headers, tag = tag)
        }
        if (body.isNullOrBlank()) {
            DebugLog.error("$site pipeline $stage: no body from ${source.method} $url (${HttpClient.lastFailure ?: "blank"})")
            return null
        }

        return when (step.asFormat) {
            "json" -> {
                val parsed: Any? = try {
                    JSONObject(body)
                } catch (_: Exception) {
                    try { JSONArray(body) } catch (_: Exception) { null }
                }
                if (parsed == null) {
                    DebugLog.error("$site pipeline $stage: invalid JSON from $url")
                    null
                } else StepOutcome(body, null, parsed, url)
            }
            "rss" -> StepOutcome(body, JsoupXml(body), null, url)
            else -> StepOutcome(body, org.jsoup.Jsoup.parse(body, url), null, url)
        }
    }

    private fun JsoupXml(body: String): Document =
        org.jsoup.Jsoup.parse(body, "", org.jsoup.parser.Parser.xmlParser())

    private fun applyBinds(step: PipelineStep, outcome: StepOutcome, vars: MutableMap<String, String>) {
        for (bind in step.bind) {
            val value: String? = when {
                bind.regex.isNotBlank() -> {
                    val m = try { Regex(bind.regex).find(outcome.body) } catch (_: Exception) { null }
                    m?.groupValues?.getOrNull(bind.group)
                }
                bind.jsonPath.isNotBlank() -> walkJson(outcome.json, bind.jsonPath).firstOrNull()?.let { jsonToString(it) }
                bind.selector.isNotBlank() -> outcome.doc?.selectFirst(bind.selector)?.let { el ->
                    if (bind.attr.isNotBlank()) el.attr(bind.attr) else el.text()
                }
                else -> null
            }
            if (!value.isNullOrBlank()) vars[bind.name] = value.trim()
        }
    }

    // -------------------------------------------------------- query transform

    internal fun applyQueryTransform(query: String, spec: JSONObject?): String {
        if (spec == null) return query
        val stripRegex = spec.optString("stripRegex")
        var out = query
        if (stripRegex.isNotBlank()) {
            out = try { query.replace(Regex(stripRegex), "").trim() } catch (_: Exception) { query }
        }
        val minLength = spec.optInt("minLength", 0)
        return if (minLength > 0 && out.length < minLength) query else out
    }

    // ------------------------------------------------------ search extraction

    /** One candidate card: either a Jsoup element (html/rss) or a JSON object. */
    internal class CardCtx(val element: Element?, val json: JSONObject?, val vars: Map<String, String>)

    internal fun extractSearchCards(
        site: String,
        step: PipelineStep,
        outcome: StepOutcome,
        vars: Map<String, String>
    ): List<ShowCard> {
        val items = step.items ?: return emptyList()
        val defaults = stringMapOf(items.optJSONObject("defaults"))

        val contexts: List<CardCtx> = when (step.asFormat) {
            "json" -> walkJson(outcome.json, items.optString("itemPath"))
                .filterIsInstance<JSONObject>()
                .map { obj ->
                    val itemVars = HashMap(vars)
                    defaults.forEach { (k, v) -> if (obj.opt(k) == null || obj.opt(k) == JSONObject.NULL) itemVars.putIfAbsent(k, v) }
                    CardCtx(null, obj, itemVars)
                }
            "rss" -> outcome.doc?.select("item")?.map { CardCtx(it, null, vars) } ?: emptyList()
            else -> {
                val selector = items.optString("cardSelector")
                if (selector.isBlank()) return emptyList()
                outcome.doc?.select(selector)?.map { CardCtx(it, null, vars) } ?: emptyList()
            }
        }

        // Per-item computed vars ("vars": {name: fieldSpec}) available to templates.
        val varSpecs = items.optJSONObject("vars")

        val titleSpec = items.opt("title")
        val urlSpec = items.opt("url")
        val posterSpec = items.opt("poster")
        val yearSpec = items.opt("year")
        val categorySpec = items.opt("category")
        val urlBlacklist = jsonStringList(items.optJSONArray("urlBlacklist"))
        val stripQuery = items.optBoolean("urlStripQuery", false)
        val limit = items.optInt("limit", 0)

        val results = mutableListOf<ShowCard>()
        for (ctx in contexts) {
            val itemVars = if (varSpecs != null && ctx.json != null) {
                val m = HashMap(ctx.vars)
                val keys = varSpecs.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val spec = varSpecs.optString(k)
                    if (spec.isNotBlank()) {
                        resolveField(spec, "var", ctx, m)?.let { m[k] = it }
                    }
                }
                CardCtx(ctx.element, ctx.json, m)
            } else ctx

            val url = resolveFieldOpt(urlSpec, "url", itemVars)?.let { u ->
                if (stripQuery) u.substringBefore("?") else u
            }?.trim()
            if (url.isNullOrBlank()) continue
            if (urlBlacklist.any { url.contains(it, ignoreCase = true) }) continue

            val title = resolveFieldOpt(titleSpec, "title", itemVars)?.trim()
            if (title.isNullOrBlank()) continue

            val poster = resolveFieldOpt(posterSpec, "poster", itemVars)?.trim() ?: ""
            val year = resolveFieldOpt(yearSpec, "year", itemVars)?.trim() ?: ""
            val category = resolveCategory(categorySpec, itemVars, title)

            results.add(ShowCard(title = title, url = url, posterUrl = poster, site = site, category = category, year = year))
        }

        val deduped = results.distinctBy { it.url }
        return if (limit > 0) deduped.take(limit) else deduped
    }

    /** Field specs may be a single string or an array of alternatives tried
     *  in order (first non-blank wins). */
    private fun resolveFieldOpt(spec: Any?, fieldName: String, ctx: CardCtx): String? {
        return when (spec) {
            is JSONArray -> {
                for (i in 0 until spec.length()) {
                    val v = resolveField(spec.optString(i), fieldName, ctx, ctx.vars)
                    if (!v.isNullOrBlank()) return v
                }
                null
            }
            is String -> resolveField(spec, fieldName, ctx, ctx.vars)
            else -> null
        }
    }

    internal fun resolveField(spec: String, fieldName: String, ctx: CardCtx, vars: Map<String, String>): String? {
        if (spec.isBlank()) return null
        return when {
            spec == "self" -> if (fieldName == "url") ctx.href() else ctx.element?.text()

            spec.startsWith("literal:") -> spec.substringAfter(":")

            spec.startsWith("link:") -> {
                val el = ctx.linkElement(spec.substringAfter(":")) ?: return null
                if (fieldName == "url") el.absHrefFallback() else el.text()
            }

            spec.startsWith("selector:") -> ctx.scope()?.selectFirst(spec.substringAfter(":"))?.text()

            spec.startsWith("attr:") -> {
                val rest = spec.substringAfter(":")
                val css = rest.substringBeforeLast(':')
                val attr = rest.substringAfterLast(':')
                val el = ctx.scope()?.selectFirst(css) ?: return null
                el.attrValue(attr)
            }

            spec.startsWith("field:") -> ctx.json?.let { jsonField(it, spec.substringAfter(":")) }

            spec.startsWith("var:") -> vars[spec.substringAfter(":")]

            spec.startsWith("template:") -> renderTemplate(spec.substringAfter(":"), vars) { name ->
                vars[name] ?: ctx.json?.let { jsonField(it, name) }
            }

            else -> null
        }
    }

    private fun CardCtx.scope(): Element? = element
    private fun CardCtx.href(): String? = element?.let { if (it.tagName() == "a") it.absHrefFallback() else null }
    private fun CardCtx.linkElement(css: String): Element? {
        val el = element ?: return null
        return if (el.tagName() == "a") el else el.selectFirst(css)
    }

    private fun Element.absHrefFallback(): String =
        attr("abs:href").ifBlank { attr("href") }

    private fun Element.attrValue(attr: String): String? {
        if (tagName() == "meta" && (attr == "src" || attr == "content")) return attr("content").ifBlank { null }
        return when (attr) {
            "src" -> attr("abs:src").ifBlank { attr("src") }.ifBlank { null }
            "href" -> absHrefFallback().ifBlank { null }
            else -> attr(attr).ifBlank { null }
        }
    }

    // ------------------------------------------------------ category resolution

    private fun resolveCategory(spec: Any?, ctx: CardCtx, title: String): String {
        if (spec == null) return "Drama"
        if (spec is String) return resolveField(spec, "category", ctx, ctx.vars) ?: "Drama"
        if (spec !is JSONObject) return "Drama"

        // {"base": "Anime", "suffixField": "post_sub"} -> "Anime (Dub)"
        val base = spec.optString("base")
        if (base.isNotBlank()) {
            val suffixField = spec.optString("suffixField")
            val suffix = if (suffixField.isNotBlank()) ctx.json?.optString(suffixField) ?: "" else ""
            return if (suffix.isNotBlank()) "$base ($suffix)" else base
        }

        // {"classPrefix": "category-", "default": "Asian Drama"} — first matching
        // class on the card element, stripped and prettified.
        val prefix = spec.optString("classPrefix")
        if (prefix.isNotBlank()) {
            val cls = ctx.element?.classNames()?.firstOrNull { it.startsWith(prefix) }
            return cls?.removePrefix(prefix)?.replace('-', ' ')
                ?.replaceFirstChar { it.uppercase() }
                ?: spec.optString("default").ifBlank { "Drama" }
        }

        // {"field": "media_type", "map": {"tv": "TV Show"}, "default": "Movie"}
        val field = spec.optString("field")
        if (field.isNotBlank() && ctx.json != null) {
            val raw = jsonField(ctx.json, field) ?: ""
            val map = spec.optJSONObject("map")
            val mapped = map?.optString(raw)?.takeIf { it.isNotBlank() }
            return mapped ?: spec.optString("default").ifBlank { "Drama" }
        }

        // {"keywords": [{"contains": ["kdrama","drama"], "value": "Asian Drama"}], "default": "..."}
        val keywords = spec.optJSONArray("keywords")
        if (keywords != null) {
            val lowerTitle = title.lowercase()
            for (i in 0 until keywords.length()) {
                val rule = keywords.optJSONObject(i) ?: continue
                for (needle in jsonStringList(rule.optJSONArray("contains"))) {
                    if (lowerTitle.contains(needle.lowercase())) {
                        val value = rule.optString("value")
                        if (value.isNotBlank()) return value
                    }
                }
            }
            return spec.optString("default").ifBlank { "Drama" }
        }

        return "Drama"
    }

    // ----------------------------------------------------- episodes extraction

    private class AnchorCtx(
        val href: String,
        val text: String,
        val element: Element?,
        val captures: List<String>
    )

    internal fun extractEpisodes(
        site: String,
        step: PipelineStep,
        outcome: StepOutcome,
        vars: Map<String, String>,
        showUrl: String
    ): PipelineEpisodes {
        val items = step.items ?: return PipelineEpisodes()
        val doc = outcome.doc ?: return PipelineEpisodes()

        // ---- collect anchors
        val anchors = mutableListOf<AnchorCtx>()
        val hrefRegexStr = items.optString("hrefRegex")
        if (hrefRegexStr.isNotBlank()) {
            val re = try { Regex(hrefRegexStr) } catch (_: Exception) { null }
            if (re != null) {
                for (a in doc.select("a[href]")) {
                    val raw = a.attr("href")
                    val m = re.find(raw) ?: continue
                    anchors.add(
                        AnchorCtx(
                            href = HttpClient.safeResolveUri(showUrl, raw),
                            text = a.text().trim(),
                            element = a,
                            captures = m.groupValues.drop(1)
                        )
                    )
                }
            }
        } else {
            val selector = items.optString("anchorSelector")
            if (selector.isNotBlank()) {
                for (a in doc.select(selector)) {
                    val raw = a.attr("href").trim()
                    if (raw.isBlank() || raw.startsWith("#") || raw.startsWith("javascript:")) continue
                    val href = a.attr("abs:href").ifBlank { HttpClient.safeResolveUri(showUrl, raw) }
                        .substringBefore('#')
                    anchors.add(AnchorCtx(href, a.text().trim(), a, emptyList()))
                }
            }
        }

        // ---- filters + dedupe
        val allowlist = jsonStringList(items.optJSONArray("urlAllowlist"))
        val blacklist = jsonStringList(items.optJSONArray("urlBlacklist"))
        val dedupeByCaptures = hrefRegexStr.isNotBlank()
        val seenUrls = mutableSetOf<String>()
        val seenCaptures = mutableSetOf<List<String>>()
        val kept = mutableListOf<AnchorCtx>()
        for (ctx in anchors) {
            val href = ctx.href
            if (href.isBlank() || href == showUrl) continue
            // A link to a parent section of the current page (e.g. /anime/ from
            // /anime/<show>/) is navigation, never an episode.
            if (showUrl.startsWith(href)) continue
            if (blacklist.any { href.contains(it, ignoreCase = true) }) continue
            if (allowlist.isNotEmpty() && allowlist.none { href.contains(it, ignoreCase = true) }) continue
            if (dedupeByCaptures) {
                if (!seenCaptures.add(ctx.captures)) continue
            } else {
                if (!seenUrls.add(href)) continue
            }
            kept.add(ctx)
        }

        // ---- numbering
        val numbering = items.optJSONObject("numbering")
        val numChain = numbering?.optJSONArray("chain")
        val sortByNumber = numbering?.optBoolean("sortByNumber", false)

        fun deriveNum(ctx: AnchorCtx, position: Int): Int {
            if (numChain != null) {
                for (i in 0 until numChain.length()) {
                    val entry = numChain.optJSONObject(i) ?: continue
                    val pattern = entry.optString("regexText").ifBlank { entry.optString("regexUrl") }
                    if (pattern.isBlank()) continue
                    val target = if (entry.optString("regexText").isNotBlank()) ctx.text else ctx.href
                    val re = try { Regex(pattern) } catch (_: Exception) { continue }
                    val m = re.find(target) ?: continue
                    val numStr = m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: m.value
                    val num = numStr.toIntOrNull()
                    if (num != null) return num
                }
            }
            return position
        }

        // ---- labels
        val labelChain = items.optJSONArray("labelChain")

        fun labelFor(ctx: AnchorCtx, num: Int): String {
            if (labelChain != null) {
                for (i in 0 until labelChain.length()) {
                    val label = evalLabelEntry(labelChain.opt(i), ctx, num)
                    if (!label.isNullOrBlank()) return label.trim()
                }
            }
            return "Episode $num"
        }

        var built = kept.mapIndexed { idx, ctx ->
            val num = deriveNum(ctx, idx + 1)
            EpisodeItem(title = labelFor(ctx, num), url = ctx.href, episodeNum = num, site = site)
        }

        // ---- ordering
        val sortBy = items.optString("sortBy", "none")
        if (sortBy == "captures") {
            built = kept
                .mapIndexed { idx, ctx -> idx to ctx }
                .sortedWith(
                    compareBy(
                        { numericCapture(it.second.captures, 0) },
                        { numericCapture(it.second.captures, 1) },
                        { it.first }
                    )
                )
                .mapIndexed { newIdx, (_, ctx) ->
                    val num = newIdx + 1
                    EpisodeItem(title = labelFor(ctx, num), url = ctx.href, episodeNum = num, site = site)
                }
        } else if (sortByNumber) {
            built = built.sortedBy { it.episodeNum }
        }

        // ---- single-episode fallback
        var episodes = built
        if (episodes.isEmpty()) {
            val fb = items.optJSONObject("fallbackSingle")
            if (fb != null) {
                val condition = fb.optString("when", "always")
                val hit = condition == "always" ||
                    (condition.startsWith("hasElement:") && doc.selectFirst(condition.substringAfter(":")) != null)
                if (hit) {
                    episodes = listOf(
                        EpisodeItem(
                            title = fb.optString("title").ifBlank { "Episode 1" },
                            url = showUrl,
                            episodeNum = 1,
                            site = site
                        )
                    )
                }
            }
        }

        // ---- optional meta (only when the rules carry a "meta" spec)
        val meta = items.optJSONObject("meta")
        var metaTitle: String? = null
        var metaPoster: String? = null
        var metaSynopsis: String? = null
        if (meta != null) {
            metaTitle = docField(meta.opt("title"), doc, vars)?.trim()?.let { applyTitleCleanup(it, meta.optJSONArray("titleCleanup")) }
            metaPoster = docField(meta.opt("poster"), doc, vars)?.trim()
            metaSynopsis = docField(meta.opt("synopsis"), doc, vars)?.trim()
        }

        return PipelineEpisodes(metaTitle, metaPoster, metaSynopsis, episodes)
    }

    private fun numericCapture(captures: List<String>, idx: Int): Int =
        captures.getOrNull(idx)?.toIntOrNull() ?: Int.MAX_VALUE

    /** Evaluates one labelChain entry; null means "no hit, try next". */
    private fun evalLabelEntry(entry: Any?, ctx: AnchorCtx, num: Int): String? {
        return when {
            entry == null -> null
            entry == "counter" || entry == JSONObject.NULL -> "Episode $num"
            entry == "text" -> validAnchorText(ctx.text, maxLength = 40, skip = GENERIC_LINK_LABELS)
            entry is String && entry.startsWith("sibling:") -> siblingLabel(ctx.element, entry.substringAfter(":"))
            entry is JSONObject -> {
                when {
                    entry.has("text") -> {
                        val cfg = entry.optJSONObject("text")
                        validAnchorText(
                            ctx.text,
                            maxLength = cfg?.optInt("maxLength", 40) ?: 40,
                            skip = jsonStringList(cfg?.optJSONArray("skip")).ifEmpty { GENERIC_LINK_LABELS }
                        )
                    }
                    entry.optString("regexText").isNotBlank() -> {
                        val re = try { Regex(entry.optString("regexText")) } catch (_: Exception) { null }
                        re?.find(ctx.text)?.let { m ->
                            m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: m.value
                        }
                    }
                    entry.optString("regexUrl").isNotBlank() -> {
                        val re = try { Regex(entry.optString("regexUrl")) } catch (_: Exception) { null }
                        val m = re?.find(ctx.href)
                        if (m == null) null
                        else {
                            val tmpl = entry.optString("label")
                            if (tmpl.isNotBlank()) fillCaptureTemplate(tmpl, m.groupValues.drop(1))
                            else m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: m.value
                        }
                    }
                    entry.optString("label").isNotBlank() -> fillCaptureTemplate(entry.optString("label"), ctx.captures)
                    else -> null
                }
            }
            else -> null
        }
    }

    private val GENERIC_LINK_LABELS = listOf("Download", "Download Episode", "Download Movie")

    private fun validAnchorText(text: String, maxLength: Int, skip: List<String>): String? {
        val t = text.trim()
        if (t.isBlank()) return null
        if (maxLength > 0 && t.length > maxLength) return null
        if (skip.any { t.equals(it, ignoreCase = true) }) return null
        return t
    }

    /** Nearest preceding h1-h6/p sibling (of the anchor or its parent) whose
     *  text contains [contains] — the "Episode 3" heading above a locker link. */
    private fun siblingLabel(anchor: Element?, contains: String): String? {
        if (anchor == null) return null
        val candidates = listOfNotNull(anchor.previousElementSibling(), anchor.parent()?.previousElementSibling())
        for (el in candidates) {
            val tag = el.tagName().lowercase()
            val isHeading = tag == "p" || (tag.startsWith("h") && tag.length == 2 && tag[1].isDigit())
            val text = el.text().trim()
            if (isHeading && text.isNotBlank() && text.contains(contains, ignoreCase = true)) return text
        }
        return null
    }

    /** Fills {1}, {2}, ... from capture groups; {1:%02d} zero-pads numerics. */
    internal fun fillCaptureTemplate(template: String, captures: List<String>): String {
        return try {
            Regex("""\{(\d+)(?::(%[^}]+))?}""").replace(template) { m ->
                val idx = (m.groupValues[1].toIntOrNull() ?: 0) - 1
                val value = captures.getOrNull(idx) ?: ""
                val fmt = m.groupValues[2]
                if (fmt.isNotBlank()) {
                    try { String.format(Locale.US, fmt, value.toIntOrNull() ?: 0) } catch (_: Exception) { value }
                } else value
            }
        } catch (_: Exception) {
            template
        }
    }

    private fun applyTitleCleanup(title: String, ops: JSONArray?): String {
        if (ops == null) return title
        var out = title
        for (i in 0 until ops.length()) {
            val op = ops.opt(i)
            out = when {
                op == "trim" -> out.trim()
                op is JSONObject && op.has("removePrefix") -> out.removePrefix(op.optString("removePrefix"))
                op is JSONObject && op.has("substringBefore") -> out.substringBefore(op.optString("substringBefore"))
                else -> out
            }
        }
        return out.trim()
    }

    /** Doc-scoped field specs for episode meta (selector:/attr:/literal:/template:). */
    private fun docField(spec: Any?, doc: Document, vars: Map<String, String>): String? {
        val candidates = when (spec) {
            is JSONArray -> (0 until spec.length()).map { spec.optString(it) }
            is String -> listOf(spec)
            else -> return null
        }
        for (raw in candidates) {
            if (raw.isBlank()) continue
            val v = when {
                raw.startsWith("literal:") -> raw.substringAfter(":")
                raw.startsWith("selector:") -> doc.selectFirst(raw.substringAfter(":"))?.text()
                raw.startsWith("attr:") -> {
                    val rest = raw.substringAfter(":")
                    val css = rest.substringBeforeLast(':')
                    val attr = rest.substringAfterLast(':')
                    doc.selectFirst(css)?.attrValue(attr)
                }
                raw.startsWith("template:") -> renderTemplate(raw.substringAfter(":"), vars) { name -> vars[name] }
                else -> null
            }
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    // ------------------------------------------------------------- primitives

    /**
     * Walks a JSON tree along a dot path. Segment grammar:
     *  - `name`   → object key
     *  - `name[]` → object key whose value is an array; emits each element
     *  - `*`      → every value of an object (dynamic top-level keys)
     * Arrays encountered mid-walk are descended into element-by-element, so
     * `*.all[]` walks {anyKey: [ {all: [items]} ] } — the anitaku shape.
     */
    internal fun walkJson(root: Any?, path: String): List<Any?> {
        if (path.isBlank()) return listOfNotNull(root)
        var current = listOf(root)
        for (segment in path.split('.')) {
            if (segment.isBlank()) continue
            val iterate = segment.endsWith("[]")
            val key = if (iterate) segment.removeSuffix("[]") else segment
            val next = mutableListOf<Any?>()
            for (node in current) {
                val focus: List<Any?> = if (node is JSONArray) {
                    (0 until node.length()).map { node.opt(it) }
                } else listOf(node)
                for (f in focus) {
                    if (f !is JSONObject) continue
                    val targets: List<Any?> = if (key == "*") {
                        val ks = f.keys()
                        val acc = mutableListOf<Any?>()
                        while (ks.hasNext()) acc.add(f.opt(ks.next()))
                        acc
                    } else {
                        listOf(f.opt(key))
                    }
                    for (t in targets) {
                        if (t == null || t == JSONObject.NULL) continue
                        if (iterate && t is JSONArray) {
                            for (i in 0 until t.length()) next.add(t.opt(i))
                        } else {
                            next.add(t)
                        }
                    }
                }
            }
            current = next
        }
        return current
    }

    /** Dot-path lookup inside one JSON item with `|` fallbacks and an optional
     *  `:before(<char>)` cut (e.g. `release_date|first_air_date:before(-)` → year). */
    internal fun jsonField(item: JSONObject, path: String): String? {
        for (alt in path.split('|')) {
            val cutIdx = alt.indexOf(":before(")
            val fieldPath = if (cutIdx >= 0) alt.substring(0, cutIdx) else alt
            val cutChar = if (cutIdx >= 0) alt.substring(cutIdx + 8).removeSuffix(")").takeIf { it.length == 1 } else null
            var node: Any? = item
            for (seg in fieldPath.split('.')) {
                node = (node as? JSONObject)?.opt(seg)
            }
            if (node == null || node == JSONObject.NULL) continue
            var str = node.toString()
            if (cutChar != null) str = if (str.contains(cutChar)) str.substringBefore(cutChar) else ""
            if (str.isNotBlank()) return str
        }
        return null
    }

    private fun jsonToString(v: Any?): String? =
        if (v == null || v == JSONObject.NULL) null else v.toString()

    /** Pure substitution of {name}; a blank/missing required var fails the
     *  whole template (returns null) so spec arrays can fall through. */
    internal fun renderTemplate(template: String, vars: Map<String, String>, resolve: (String) -> String?): String? {
        return try {
            val sb = StringBuilder()
            var cursor = 0
            val re = Regex("""\{([^{}]+)}""")
            var matched = false
            for (m in re.findAll(template)) {
                matched = true
                sb.append(template, cursor, m.range.first)
                val value = resolve(m.groupValues[1])
                if (value.isNullOrBlank() || value == "null") return null
                sb.append(value)
                cursor = m.range.last + 1
            }
            if (!matched) template else sb.append(template, cursor, template.length).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun stringMapOf(obj: JSONObject?): Map<String, String> {
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
}
