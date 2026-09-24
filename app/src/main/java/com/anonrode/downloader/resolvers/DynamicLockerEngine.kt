package com.anonrode.downloader.resolvers

import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.providers.RulesPipeline

/**
 * DynamicLockerEngine (Layer 3 — Zero-APK OTA Locker Resolver).
 *
 * Executes declarative OTA resolution steps for video lockers.
 * Lookups can be keyed by full locker domain (e.g. "vikingfile.com", "lulacloud.com")
 * or site key. If an OTA rule is published in scraper_rules.json under "pipelines",
 * DynamicLockerEngine resolves the stream URL dynamically without an APK release.
 */
object DynamicLockerEngine {

    fun canResolve(url: String): Boolean {
        val host = HttpClient.parsedHost(url) ?: return false
        val clean = host.removePrefix("www.").lowercase()
        return pipelineKeyForHost(clean)
            ?.let { DynamicRulesManager.getPipeline(it)?.terminal != null }
            ?: false
    }

    suspend fun resolve(url: String, quality: String = "720p"): String? {
        val host = HttpClient.parsedHost(url) ?: return null
        val clean = host.removePrefix("www.").lowercase()
        val siteKey = pipelineKeyForHost(clean) ?: return null
        return RulesPipeline.runResolveForSite(siteKey, url, quality)
    }

    /** A provider/search-only pipeline is not a locker resolver. */
    private fun pipelineKeyForHost(host: String): String? {
        DynamicRulesManager.getPipeline(host)?.let { return host }
        val baseKey = host.substringBeforeLast('.').substringAfterLast('.')
        return baseKey.takeIf { DynamicRulesManager.getPipeline(it) != null }
    }
}
