package com.anonrode.downloader.pipeline

object HostPolicyRegistry {

    val ASIAN_DRAMA_CLUSTER = setOf("asianc", "dramarain", "dramakey", "pluto", "nepu")
    val WESTERN_CLUSTER = setOf("nkiri", "9jarocks", "naijavault", "naijaprey")
    val DEDICATED_ASIAN_SITES = setOf("asianc", "dramarain", "dramakey")
    val SECONDARY_ASIAN_FALLBACK = setOf("nkiri", "9jarocks")

    // Built-in per-host socket caps. Speed-first policy (2026-10, 1DM batch):
    // start wide and let TurboDownloader's 429 ladder + drop-and-replace adapt
    // per connection instead of pre-throttling to 1-4 sockets. OTA-overridable
    // via contentPolicy.socketCaps (see effectiveSocketsForUrl). Clamp + overlay
    // semantics validated in scratch/test_zero_redo.py (section A).
    private val SENSITIVE_SOCKET_CAP_HOSTS = mapOf(
        "kissorgrab.com" to 6,
        "dl.plutomovies.com" to 6,
        "plutomovies.com" to 8,
        "wetafiles.com" to 12,
        "downloadwella.com" to 12,
        "loadedfiles.org" to 12,
        "loadedfiles.net" to 12,
        "loadedfiles.com" to 12,
        "loadedfiles.st" to 12,
        "loadedfiles.to" to 12
    )

    // Explicit allowlist of locker hosts known to omit intermediate CA certificates.
    // Never disable TLS verification globally for untrusted/arbitrary hosts.
    private val BROKEN_TLS_INTERMEDIATE_HOSTS = setOf(
        "wetafiles.com",
        "downloadwella.com",
        "loadedfiles.org",
        "loadedfiles.net",
        "loadedfiles.com",
        "loadedfiles.st",
        "loadedfiles.to",
        "kissorgrab.com",
        "plutomovies.com",
        // Live-verified broken/mismatched CA chains, documented in
        // resolvers/Resolvers.kt (lulacloud.com verify code 20; mixdrop.co).
        "lulacloud.com",
        "mixdrop.co"
    )

    fun effectiveSocketsForUrl(url: String, requestedSockets: Int): Int {
        val clampedRequest = requestedSockets.coerceIn(1, 16)
        if (clampedRequest == 1) return 1
        val host = UrlClassifier.extractHost(url)
        // OTA overlay first (contentPolicy.socketCaps), built-ins second — the
        // overlay can retune any host (including the capped ones) without an
        // APK; removing the section restores these compiled caps. Same
        // read-through precedence style as DynamicRulesManager.resolveReferer.
        val otaCap = com.anonrode.downloader.data.rules.DynamicRulesManager
            .getContentPolicy().socketCaps.entries
            .firstOrNull { (domain, _) -> host == domain || host.endsWith(".$domain") }
            ?.value
            ?.coerceIn(1, 16)
        if (otaCap != null) return minOf(otaCap, clampedRequest)
        val matchedCap = SENSITIVE_SOCKET_CAP_HOSTS.entries
            .firstOrNull { (domain, _) -> host == domain || host.endsWith(".$domain") }
            ?.value
        return if (matchedCap != null) {
            minOf(matchedCap, clampedRequest)
        } else {
            clampedRequest
        }
    }

    fun allowsPermissiveTlsFallback(url: String): Boolean {
        val host = UrlClassifier.extractHost(url)
        return BROKEN_TLS_INTERMEDIATE_HOSTS.any { domain ->
            host == domain || host.endsWith(".$domain")
        }
    }
}
