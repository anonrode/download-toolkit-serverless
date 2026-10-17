package com.anonrode.downloader.resolvers

import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.providers.RulesPipeline
import com.anonrode.downloader.util.DebugLog

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
        val hasMultiStep = pipeline.resolve != null && pipeline.terminal != null
        val hasProbedEntry = pipeline.resolve == null && pipeline.terminal?.source == "entry" && pipeline.terminal?.mode == "probe"
        return hasMultiStep || hasProbedEntry
    }

    suspend fun resolve(
        url: String,
        quality: String = "720p",
        depth: Int = 0,
        visitedSites: Set<String> = emptySet()
    ): String? {
        val host = HttpClient.parsedHost(url) ?: return null
        val clean = host.removePrefix("www.").lowercase()
        val siteKey = pipelineKeyForHost(clean) ?: return null
        val nextVisited = nextLockerHop(siteKey, clean, depth, visitedSites)
        if (nextVisited == null) {
            // Depth limit OR cycle on the chain (see nextLockerHop): refusing
            // here is what turns an A -> B -> A rule cycle into a terminated
            // chain (null) instead of the 2026-10-15 recursive-crash stack.
            DebugLog.resolve(
                "DynamicLockerEngine: depth/cycle guard refused $url (depth=$depth, siteKey=$siteKey, visited=$visitedSites)"
            )
            return null
        }
        return RulesPipeline.runResolveForSite(siteKey, url, quality, depth + 1, nextVisited)
    }

    /**
     * Pure re-entry guard for one locker hop — JVM-testable core of
     * [resolve]'s recursion control:
     *  - `depth >= RESOLVE_DEPTH_LIMIT` → refuse (null);
     *  - the candidate site key already on the chain's visited set
     *    → refuse (null) — this is the A -> B -> A cycle stop;
     *  - otherwise → the chain's NEXT visited set (previous sites + this
     *    hop's siteKey), to be threaded into the `runResolveForSite`
     *    call. Depth is carried as +1 by the caller — matching the
     *    `runResolveForSite(siteKey, url, quality, depth + 1, ...)` contract
     *    (pinned by ResolverDefectRegressionTest.otaHandoffCarriesResolverDepth).
     *
     * A legitimate chain of distinct, not-yet-visited sites under the depth
     * limit always proceeds, so valid resolves are unaffected; only depth
     * exhaustion and revisits stop.
     */
    internal fun nextLockerHop(
        siteKey: String,
        host: String,
        depth: Int,
        visitedSites: Set<String>
    ): Set<String>? {
        if (depth >= ResolverRegistry.RESOLVE_DEPTH_LIMIT) return null
        val cleanKey = siteKey.trim().lowercase()
        if (cleanKey.isNotEmpty() && cleanKey in visitedSites) return null
        return if (cleanKey.isNotEmpty()) visitedSites + cleanKey else visitedSites
    }

    /** A provider/search-only pipeline is not a locker resolver. */
    private fun pipelineKeyForHost(host: String): String? {
        if (DynamicRulesManager.getPipeline(host) != null) return host
        val parts = host.split('.').filter { it.isNotBlank() }
        for (start in 0 until parts.size) {
            val suffix = parts.drop(start).joinToString(".")
            if (DynamicRulesManager.getPipeline(suffix) != null) return suffix
        }
        val found = DynamicRulesManager.findPipelineKey(host)
        if (found != null) return found
        if (parts.isNotEmpty()) {
            val sld = parts[0]
            if (DynamicRulesManager.getPipeline(sld) != null) return sld
            for (key in DynamicRulesManager.getLockerHosts()) {
                if (key.startsWith("$sld.") || host.startsWith("$key.") || host.contains(".$key.")) {
                    val pKey = DynamicRulesManager.findPipelineKey(key) ?: key
                    if (DynamicRulesManager.getPipeline(pKey) != null) return pKey
                }
            }
        }
        return null
    }
}
