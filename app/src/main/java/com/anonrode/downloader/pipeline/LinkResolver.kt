package com.anonrode.downloader.pipeline

import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.providers.ProviderRegistry
import com.anonrode.downloader.resolvers.ResolverRegistry

/**
 * The link-cracking ladder — the single chain every caller uses to turn a
 * listing/gateway URL into a direct file URL. Extracted verbatim from
 * DownloadEngine.resolveStreamUrl (2026-09-14) so the search-verify oracle
 * (ResultVerifier) proves results with EXACTLY the machinery downloads use,
 * not a lookalike. The engine's semantics are unchanged: it calls through
 * [resolveChain] with allowCacheHit=false (invalidate-first — see the poison
 * commentary), the oracle calls with allowCacheHit=true (its whole job is
 * consuming fresh cache hits).
 */
object LinkResolver {

    /** Cloudflare/JS-challenge pages answer HTTP 200 with no real content.
     *  Shared by PlutoProvider.loadEpisodes and ResultVerifier: a challenge
     *  page is NEVER proof of absence (doctrine: uncertainty never hides). */
    fun isSecurityChallenge(html: String): Boolean {
        val low = html.lowercase()
        return low.contains("just a moment") || low.contains("cf-challenge") ||
            low.contains("challenge-platform") || (low.contains("cloudflare") && low.contains("verify"))
    }

    // Locker hosts whose URLs are pages to crack, not direct files. Hoisted
    // out of isKnownLockerHost so the list is built once instead of on
    // every call (it runs per resolve attempt).
    private val KNOWN_LOCKER_HOSTS = listOf(
        "downloadwella.com",
        "loadedfiles.",
        "wetafiles.com",
        "vikingfile.com",
        "lulacloud.com",
        "waffi",
        "dood.",
        "streamwish.",
        "strwsh.",
        "stwish.",
        "sfastwish.",
        "vidhide.",
        "kissorgrab.com",
        // nkiserv.com REMOVED: naijavault drawers hand out direct
        // ds2.nkiserv.com/TV/*.mkv files (nkiri's own CDN). Listing it as
        // a locker made the engine try to 'crack' a finished direct file
        // and fail cleanly every time (live log 23:23:03).
        "wildshare.net",
        "vidmoly.",
        "mixdrop.",
        "mixdrp.",
        "streamtape.",
        "pixeldrain.com",
        "vidbasic.",
        "vidb.top",
        "lightdl.cc",
        "5play.cc",
        "megaplay.",
        "blogger.com"
    )

    /**
     * A URL that is provably a direct file rather than a page to crack, even
     * when its host appears in [isKnownLockerHost]'s list: pixeldrain's API
     * endpoint and token-carrying CDN links (?pt= / ?token= / ?download) are
     * resolver *outputs* — the cracking already happened — so exempting them
     * lets genuine direct links through instead of discarding them (the probe
     * in TurboDownloader still rejects any server that lies and serves HTML).
     */
    fun isProvablyDirectFile(url: String): Boolean {
        val lower = url.lowercase()
        val path = lower.substringAfter("://", "").substringBefore('?').substringBefore('#')
        if (path.contains("/api/file/")) return true
        val query = lower.substringAfter('?', "").substringBefore('#')
        if (query.contains("pt=") || query.contains("token=") || query.contains("download")) return true
        // R2 / S3 / Object Storage signed links: the query string contains
        // AWS-style signing parameters and/or response-content-disposition.
        // These are resolver outputs (the direct cracked media stream) serving
        // the raw file object, not web pages.
        if (query.contains("response-content-disposition=") ||
            query.contains("x-amz-signature=") ||
            query.contains("x-amz-credential=")) return true
        if (lower.contains("r2.cloudflarestorage.com") || lower.contains(".r2.dev/")) return true
        return false
    }

    fun isKnownLockerHost(url: String): Boolean {
        if (url.isBlank()) return false
        if (isProvablyDirectFile(url)) return false
        val lower = url.lowercase()
        // Host-based, not extension-based: locker pages carry the media filename
        // in their path (loadedfiles.net/.../Episode.mkv), so a .mkv/.mp4 suffix
        // must NOT exempt them from resolution — the host decides whether a URL
        // is a page to crack or a direct file.
        val host = lower.substringAfter("://", "").substringBefore('/').substringBefore(':')
        return KNOWN_LOCKER_HOSTS.any { host.contains(it) }
    }

    /**
     * The crack ladder, moved verbatim from DownloadEngine.resolveStreamUrl.
     *
     * Resolver output is TRUSTED: a URL that differs from the input page was
     * cracked. Locker CDN subdomains legitimately embed the locker's name
     * (fsmc02.downloadwella.com served nkiri's real .mkv — live-verified), so
     * isKnownLockerHost must not reject them; it only exists to stop an
     * UNRESOLVED locker page from being treated as a direct file.
     *
     * The poison list is INTENTIONALLY NOT consulted here. A URL that
     * returned HTML once may serve the real .mkv a second later — token
     * rotation, edge node assignment, and the server's anti-abuse cooldown
     * all clear in seconds-to-minutes, and a downloader that gives up
     * after one HTML response on a token-bearing URL would lose every
     * locker download the moment a single edge node happens to be rate-
     * limited. The 11-episode wildshare cascade in app-2026-09-01 fired
     * because the engine REJECTED wildshare after one HTML page; the right
     * fix is to retry the source page for a fresh token, not blacklist
     * the host. Poison is therefore consulted only by the download path
     * (to keep a known-bad URL out of the bytes-on-disk fetch), never by
     * the resolver path.
     *
     * [allowCacheHit] false (the download engine): "fetch a FRESH link" is
     * this function's semantic — the ResolveCache entry for the input is
     * invalidated first, because serving a cached URL on the refresh path
     * re-hands a dead token (the mid-download 403 self-heal depends on it).
     * true (the oracle): consume cache — re-cracking pages the app already
     * cracked minutes ago is exactly the mobile data this design must not burn.
     */
    suspend fun resolveChain(
        permUrl: String,
        site: String,
        defaultQual: String,
        bypassHealth: Boolean = false,
        allowCacheHit: Boolean = false
    ): String? {
        fun accept(out: String?): Boolean {
            if (out.isNullOrBlank()) return false
            if (out != permUrl) return true
            return !isKnownLockerHost(out)
        }

        // 1. Try direct resolution via ResolverRegistry.
        if (!allowCacheHit) {
            ResolveCache.invalidate(ResolveCache.keyFor(permUrl, defaultQual))
        }
        var resolved = ResolverRegistry.resolve(permUrl, defaultQual, bypassHealth = bypassHealth)
        if (accept(resolved)) {
            return resolved
        }

        // 2. Try ProviderRegistry
        if (site.isNotBlank()) {
            try {
                val recipe = ProviderRegistry.resolveEpisode(site, permUrl, defaultQual)
                if (recipe.directUrl.isNotBlank() && recipe.directUrl != permUrl) {
                    resolved = recipe.directUrl
                }
            } catch (_: Exception) {}
        }

        if (!accept(resolved)) {
            for (provider in ProviderRegistry.allProviders) {
                if (provider.canHandle(permUrl)) {
                    try {
                        val recipe = provider.resolveEpisode(permUrl, defaultQual)
                        if (recipe.directUrl.isNotBlank() && recipe.directUrl != permUrl) {
                            resolved = recipe.directUrl
                            break
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // 3. Unpack secondary lockers if present. Non-direct URLs that are NOT known
        // lockers are embed/watch pages (e.g. vidsrc.mov): the registry can't crack
        // their token-gated chains, so don't waste fetches — startTask routes them
        // straight to yt-dlp.
        if (!accept(resolved) && !resolved.isNullOrBlank() && isKnownLockerHost(resolved)) {
            try {
                val inner = ResolverRegistry.resolve(resolved, defaultQual)
                if (accept(inner)) {
                    resolved = inner
                }
            } catch (_: Exception) {}
        }

        return if (accept(resolved)) resolved else null
    }

    /** The oracle's terminal check: one no-redirect Range: bytes=0-0 request
     *  (at most 1 body byte — metered-data discipline, see probeTerminal doc).
     *  Non-null totalBytes = the URL really serves a file: LIVE proof. */
    fun probeTerminal(url: String, referer: String? = null, tag: String? = null): Long? =
        HttpClient.probeTerminal(url, referer = referer, tag = tag)?.totalBytes
}
