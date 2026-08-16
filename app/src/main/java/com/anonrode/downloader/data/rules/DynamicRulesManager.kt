package com.anonrode.downloader.data.rules

import android.content.Context
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.providers.DynamicSiteConfig
import com.anonrode.downloader.providers.GenericDeclarativeProvider
import com.anonrode.downloader.providers.ProviderRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

object DynamicRulesManager {

    private const val RULES_URL = "https://raw.githubusercontent.com/anonrode/download-toolkit-serverless/master/scraper_rules.json"
    private const val CACHE_FILE = "dynamic_scraper_rules.json"

    private val defaultDomains = mapOf(
        "nkiri" to "https://thenkiri.com",
        "dramakey" to "https://dramakey.com",
        "asianc" to "https://asianc.id",
        "anitaku" to "https://gogoanime.or.at",
        "pluto" to "https://plutomovies.com",
        "dramarain" to "https://dramarain.com",
        "9jarocks" to "https://9jarocks.com",
        "naijavault" to "https://naijavault.com",
        "naijaprey" to "https://www.naijaprey.tv",
        "nepu" to "https://nepu.gd",
        "torrents" to "https://apibay.org"
    )

    private val activeDomains = mutableMapOf<String, String>().apply { putAll(defaultDomains) }
    private val dynamicProviders = mutableListOf<GenericDeclarativeProvider>()

    private val _version = MutableStateFlow("2026.08.16.1 (Bundled)")
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

    fun getDynamicProviders(): List<GenericDeclarativeProvider> = dynamicProviders

    suspend fun syncFromGitHub(context: Context): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val jsonStr = HttpClient.getText(RULES_URL)
            if (jsonStr.isNullOrBlank()) {
                return@withContext Pair(false, "Could not reach GitHub rules repository")
            }

            parseRulesJson(jsonStr)

            val file = File(context.filesDir, CACHE_FILE)
            file.writeText(jsonStr)

            Pair(true, _version.value)
        } catch (t: Throwable) {
            Pair(false, t.message ?: "Failed to sync rules")
        }
    }

    private fun parseRulesJson(jsonStr: String) {
        try {
            val obj = JSONObject(jsonStr)
            val ver = obj.optString("version", "2026.08.16.1")
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
        } catch (_: Exception) {}
    }
}
