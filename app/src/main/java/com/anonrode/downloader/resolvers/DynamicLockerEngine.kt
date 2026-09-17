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
        return DynamicRulesManager.getPipeline(clean) != null ||
            DynamicRulesManager.getPipeline(clean.substringBeforeLast('.').substringAfterLast('.')) != null
    }

    suspend fun resolve(url: String, quality: String = "720p"): String? {
        val host = HttpClient.parsedHost(url) ?: return null
        val clean = host.removePrefix("www.").lowercase()
        val siteKey = if (DynamicRulesManager.getPipeline(clean) != null) clean
            else clean.substringBeforeLast('.').substringAfterLast('.')
        return RulesPipeline.runResolveForSite(siteKey, url, quality)
    }
}
