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
        val key = pipelineKeyForHost(clean) ?: return false
        val pipeline = DynamicRulesManager.getPipeline(key) ?: return false
        // Must have an explicit multi-step resolution pipeline to avoid hijacking
        // entry-only provider gateways into infinite handoff loops.
        return pipeline.resolve != null && pipeline.terminal != null
    }

    suspend fun resolve(url: String, quality: String = "720p", depth: Int = 0): String? {
        val host = HttpClient.parsedHost(url) ?: return null
        val clean = host.removePrefix("www.").lowercase()
        val siteKey = pipelineKeyForHost(clean) ?: return null
        return RulesPipeline.runResolveForSite(siteKey, url, quality, depth)
    }

    /** A provider/search-only pipeline is not a locker resolver. */
    private fun pipelineKeyForHost(host: String): String? {
        if (DynamicRulesManager.getPipeline(host) != null) return host
        val parts = host.split('.').filter { it.isNotBlank() }
        for (start in 0 until parts.size) {
            val suffix = parts.drop(start).joinToString(".")
            if (DynamicRulesManager.getPipeline(suffix) != null) return suffix
        }
        return null
    }
}
