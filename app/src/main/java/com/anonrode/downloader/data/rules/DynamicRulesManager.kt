package com.anonrode.downloader.data.rules

import android.content.Context
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.providers.DynamicSiteConfig
import com.anonrode.downloader.providers.GenericDeclarativeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class SiteRuleConfig(
    val searchPattern: String = "",
    val searchType: String = "html",
    val cardSelector: String = "",
    val titleSelector: String = "",
    val linkSelector: String = "",
    val posterSelector: String = "",
    val episodeSelector: String = "",
    val seriesDescentSelectors: List<String> = emptyList(),
    val downloadAnchorSelector: String = "",
    val slugSuffixes: List<String> = emptyList()
)

object DynamicRulesManager {

    private const val RULES_URL = "https://raw.githubusercontent.com/anonrode/download-toolkit-serverless/master/scraper_rules.json"
    private const val CACHE_FILE = "dynamic_scraper_rules.json"

    private val defaultDomains = mapOf(
        "nkiri" to "https://nkiri.top",
        "dramakey" to "https://dramakey.com",
        "asianc" to "https://asianc.id",
        "anitaku" to "https://anitaku.com.ro",
        "pluto" to "https://plutomovies.com",
        "dramarain" to "https://dramarain.com",
        "9jarocks" to "https://9jarocks.net",
        "naijavault" to "https://www.naijavault.com",
        "naijaprey" to "https://www.naijaprey.tv",
        "nepu" to "https://nepu.gd",
        "torrents" to "https://apibay.org"
    )

    private val defaultMediaExtensions = listOf(
        ".mp4", ".mkv", ".webm", ".avi", ".ts", ".m3u8", ".m4v", ".mp3", ".zip", ".rar", "-mp4", "-mkv"
    )

    private val activeDomains = mutableMapOf<String, String>().apply { putAll(defaultDomains) }
    private val activeMirrors = mutableMapOf<String, List<String>>()
    private val activeSiteConfigs = mutableMapOf<String, SiteRuleConfig>()
    private val activeResolverConfigs = mutableMapOf<String, JSONObject>()
    private val activeMediaExtensions = mutableListOf<String>().apply { addAll(defaultMediaExtensions) }
    private val dynamicProviders = mutableListOf<GenericDeclarativeProvider>()

    private val _version = MutableStateFlow("2026.08.22.1 (Bundled)")
    val version: StateFlow<String> = _version.asStateFlow()

    fun init(context: Context) {
        try {
            val file = File(context.filesDir, CACHE_FILE)
            if (file.exists()) {
                val raw = file.readText()
                parseRulesJson(raw)
            }
        } catch (_: Exception) {}
    }

    fun getBaseUrl(site: String): String {
        return activeDomains[site.lowercase()] ?: defaultDomains[site.lowercase()] ?: ""
    }

    /** Primary + mirror hosts for a site, in failover order. Providers that
     *  can retry (e.g. nkiri's original IP is ISP-blocked on some networks
     *  while its Cloudflare mirror works) iterate this list. */
    fun getBaseUrls(site: String): List<String> {
        val key = site.lowercase()
        val primary = getBaseUrl(key)
        val mirrors = activeMirrors[key] ?: emptyList()
        return listOf(primary) + mirrors.filter { it.isNotBlank() && it != primary }
    }

    fun getSiteConfig(site: String): SiteRuleConfig? {
        return activeSiteConfigs[site.lowercase()]
    }

    fun getResolverConfig(resolverName: String): JSONObject? {
        return activeResolverConfigs[resolverName.lowercase()]
    }

    fun getDirectMediaExtensions(): List<String> {
        return if (activeMediaExtensions.isNotEmpty()) activeMediaExtensions else defaultMediaExtensions
    }

    fun getDynamicProviders(): List<GenericDeclarativeProvider> = dynamicProviders

    suspend fun syncFromGitHub(context: Context): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            // Append cache-buster timestamp to bypass GitHub CDN's 5-minute cache
            val cacheBustedUrl = "$RULES_URL?t=${System.currentTimeMillis()}"
            val jsonStr = HttpClient.getText(cacheBustedUrl, tag = "ota_rules")
            if (jsonStr.isNullOrBlank()) {
                return@withContext Pair(false, "Could not reach GitHub rules repository")
            }

            if (!parseRulesJson(jsonStr)) {
                return@withContext Pair(false, "Rules payload failed to parse — keeping bundled defaults")
            }

            val file = File(context.filesDir, CACHE_FILE)
            file.writeText(jsonStr)

            Pair(true, _version.value)
        } catch (t: Throwable) {
            Pair(false, t.message ?: "Failed to sync rules")
        }
    }

    /** Returns false when the payload is malformed; bundled defaults stay active. */
    private fun parseRulesJson(jsonStr: String): Boolean {
        return try {
            val obj = JSONObject(jsonStr)
            val ver = obj.optString("version", "2026.08.22.1")
            _version.value = ver

            val domainsObj = obj.optJSONObject("domains")
            if (domainsObj != null) {
                val keys = domainsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val url = domainsObj.optString(k)
                    if (url.isNotBlank()) {
                        activeDomains[k.lowercase()] = url
                    }
                }
            }

            val mirrorsObj = obj.optJSONObject("mirrors")
            if (mirrorsObj != null) {
                val keys = mirrorsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val arr = mirrorsObj.optJSONArray(k)
                    if (arr != null) {
                        val list = mutableListOf<String>()
                        for (i in 0 until arr.length()) {
                            val m = arr.optString(i)
                            if (m.isNotBlank()) list.add(m)
                        }
                        activeMirrors[k.lowercase()] = list
                    }
                }
            }

            val sitesObj = obj.optJSONObject("sites")
            if (sitesObj != null) {
                val keys = sitesObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val sObj = sitesObj.optJSONObject(k)
                    if (sObj != null) {
                        val descentList = mutableListOf<String>()
                        val descentArr = sObj.optJSONArray("seriesDescentSelectors")
                        if (descentArr != null) {
                            for (idx in 0 until descentArr.length()) {
                                val item = descentArr.optString(idx)
                                if (item.isNotBlank()) descentList.add(item)
                            }
                        }
                        val slugList = mutableListOf<String>()
                        val slugArr = sObj.optJSONArray("slugSuffixes")
                        if (slugArr != null) {
                            for (idx in 0 until slugArr.length()) {
                                slugList.add(slugArr.optString(idx))
                            }
                        }

                        activeSiteConfigs[k.lowercase()] = SiteRuleConfig(
                            searchPattern = sObj.optString("searchPattern"),
                            searchType = sObj.optString("searchType", "html"),
                            cardSelector = sObj.optString("cardSelector"),
                            titleSelector = sObj.optString("titleSelector"),
                            linkSelector = sObj.optString("linkSelector"),
                            posterSelector = sObj.optString("posterSelector"),
                            episodeSelector = sObj.optString("episodeSelector"),
                            seriesDescentSelectors = descentList,
                            downloadAnchorSelector = sObj.optString("downloadAnchorSelector"),
                            slugSuffixes = slugList
                        )
                    }
                }
            }

            val resolversObj = obj.optJSONObject("resolvers")
            if (resolversObj != null) {
                val keys = resolversObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val rObj = resolversObj.optJSONObject(k)
                    if (rObj != null) {
                        activeResolverConfigs[k.lowercase()] = rObj
                    }
                }
            }

            val mediaExtArr = obj.optJSONArray("directMediaExtensions")
            if (mediaExtArr != null && mediaExtArr.length() > 0) {
                activeMediaExtensions.clear()
                for (idx in 0 until mediaExtArr.length()) {
                    val ext = mediaExtArr.optString(idx)
                    if (ext.isNotBlank()) activeMediaExtensions.add(ext)
                }
            }

            // Parse any dynamic new providers added remotely
            val dynamicList = obj.optJSONArray("dynamic_providers")
            if (dynamicList != null) {
                dynamicProviders.clear()
                for (i in 0 until dynamicList.length()) {
                    val item = dynamicList.getJSONObject(i)
                    val cfg = DynamicSiteConfig(
                        id = item.optString("id"),
                        displayName = item.optString("display_name"),
                        category = item.optString("category", "Media"),
                        baseUrl = item.optString("base_url"),
                        searchUrlPattern = item.optString("search_url_pattern", "{base}/?s={query}"),
                        searchType = item.optString("search_type", "html"),
                        cardSelector = item.optString("card_selector", "article"),
                        titleSelector = item.optString("title_selector", "h2, .entry-title"),
                        linkSelector = item.optString("link_selector", "a"),
                        posterSelector = item.optString("poster_selector", "img"),
                        episodeLinkSelector = item.optString("episode_link_selector", "a[href*='download']")
                    )
                    dynamicProviders.add(GenericDeclarativeProvider(cfg))
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
