package com.anonrode.downloader.providers

import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.pipeline.PipelineJournal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * Executes a site's OTA-declared search strategy chain, in order, until one
 * returns results. Encodes the dramarain lesson as data: when a site's
 * primary search endpoint dies (?s= broke server-side), the playbook grows a
 * slugGuess strategy and every user is fixed on next rules sync — no APK.
 *
 * Strategy shapes:
 *  {"type":"urlTemplate","pattern":"/?s={query}","cardSelector":"article",
 *   "titleSelector":"h2 a","linkSelector":"a[href]"}
 *  {"type":"slugGuess","suffixes":["","-korean-drama"],"-pattern":"-{slug}"}
 *  {"type":"rss","pattern":"/search/{query}/feed/rss2/"}
 */
object SearchStrategyRunner {

    suspend fun run(siteName: String, query: String, mainUrl: String): List<ShowCard>? {
        val strategies = DynamicRulesManager.getSearchStrategies(siteName)
        if (strategies.isEmpty()) return null

        for (st in strategies) {
            if (!com.anonrode.downloader.pipeline.HostHealth.isUsable(mainUrl)) break
            val type = st.optString("type")
            val start = System.currentTimeMillis()
            val results = try {
                when (type) {
                    "urlTemplate" -> runUrlTemplate(st, query, mainUrl, siteName)
                    "rss" -> runRss(st, query, mainUrl, siteName)
                    "slugGuess" -> runSlugGuess(st, query, mainUrl, siteName)
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
            PipelineJournal.hop(
                site = siteName, stage = "search:$type", url = mainUrl + " q=" + query.take(40),
                ok = !results.isNullOrEmpty(), ms = System.currentTimeMillis() - start
            )
            if (!results.isNullOrEmpty()) return results
        }
        return null
    }

    private fun encode(q: String) = URLEncoder.encode(q, "UTF-8")

    private suspend fun runUrlTemplate(
        st: org.json.JSONObject, query: String, mainUrl: String, siteName: String
    ): List<ShowCard> = withContext(Dispatchers.IO) {
        val url = mainUrl.trimEnd('/') + st.optString("pattern", "/?s={query}")
            .replace("{query}", encode(query))
        val html = HttpClient.getText(url, tag = "search-strategy") ?: return@withContext emptyList()
        val doc = Jsoup.parse(html, url)
        val cardSel = st.optString("cardSelector", "article")
        val out = mutableListOf<ShowCard>()
        for (item in doc.select(cardSel)) {
            val a = if (item.tagName() == "a") item
            else item.selectFirst(st.optString("linkSelector", "h2 a, .entry-title a, a[href]")) ?: continue
            val title = a.text().trim()
            val href = a.attr("abs:href").ifBlank {
                HttpClient.safeResolveUri(url, a.attr("href"))
            } ?: ""
            if (href.isNotBlank() && title.isNotBlank() && out.none { it.url == href }) {
                out.add(ShowCard(
                    title = title, url = href,
                    posterUrl = item.selectFirst("img")?.attr("abs:src"),
                    site = siteName, category = ""
                ))
            }
        }
        out
    }

    private suspend fun runRss(
        st: org.json.JSONObject, query: String, mainUrl: String, siteName: String
    ): List<ShowCard> = withContext(Dispatchers.IO) {
        val url = mainUrl.trimEnd('/') + st.optString("pattern", "/search/{query}/feed/rss2/")
            .replace("{query}", encode(query))
        val body = HttpClient.getText(url, tag = "search-strategy") ?: return@withContext emptyList()
        if (!body.contains("<item")) return@withContext emptyList()
        val doc = Jsoup.parse(body, url)
        doc.select("item").mapNotNull { item ->
            val title = item.selectFirst("title")?.text()?.trim() ?: return@mapNotNull null
            val link = item.selectFirst("link")?.text()?.trim()
                ?: item.selectFirst("link")?.attr("href") ?: return@mapNotNull null
            ShowCard(
                title = title, url = link,
                posterUrl = item.selectFirst("thumbnail, enclosure, image")?.attr("url"),
                site = siteName, category = ""
            )
        }
    }

    private suspend fun runSlugGuess(
        st: org.json.JSONObject, query: String, mainUrl: String, siteName: String
    ): List<ShowCard> = withContext(Dispatchers.IO) {
        val slug = query.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val pattern = st.optString("pattern", "/{slug}{suffix}/")
        val suffixes = st.optJSONArray("suffixes")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }
        } ?: listOf("")
        for (suffix in suffixes) {
            val url = mainUrl.trimEnd('/') + pattern
                .replace("{slug}", slug).replace("{suffix}", suffix)
            val code = HttpClient.probe(url, timeoutMs = 8_000L, tag = "search-strategy")
            if (code) {
                // Slug exists: return it as a single strong candidate.
                return@withContext listOf(ShowCard(
                    title = query.trim().replaceFirstChar { it.uppercase() },
                    url = url, posterUrl = "", site = siteName, category = ""
                ))
            }
        }
        emptyList()
    }
}
