package com.anonrode.downloader.pipeline

import com.anonrode.downloader.data.models.SubtitleTrack
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.providers.ProviderRegistry
import com.anonrode.downloader.resolvers.ResolverRegistry
import com.anonrode.downloader.resolvers.isDirectMediaUrl

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
        "blogger.com",
        "np-downloader.com",
        "vdl.np-downloader.com"
    )

    /**
     * A URL that is provably a direct file rather than a page to crack, even
     * when its host appears in [isKnownLockerHost]'s list: pixeldrain's API
     * endpoint and dedicated resolver-output token params
     * (?download_token= / ?media_token= / ?direct_download=1) are resolver
     * *outputs* — the cracking already happened — so exempting them lets
     * genuine direct links through instead of discarding them (the probe
     * in TurboDownloader still rejects any server that lies and serves HTML).
     * NOTE: ?pt= is Loadedfiles' INTERNAL hop marker — mid-chain pages carry
     * it, so it must never exempt (5b0a903 hardening).
     */
    fun isProvablyDirectFile(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()
        val query = lower.substringAfter('?', "").substringBefore('#')
        // Universal guard: URLs carrying ?preview / &preview serve an in-browser
        // HTML landing page / player, NEVER raw video bytes.
        if (query.contains("preview")) return false
        val path = lower.substringAfter("://", "").substringBefore('?').substringBefore('#')
        val host = path.substringBefore('/')
        if (path.contains("/api/file/") || path.contains("/api/download/") || path.contains("/token/download/")) return true
        if (host.contains("lightdl.cc") && (path.contains("/api/download/") || path.contains("/api/files/"))) return true
        if (path.endsWith(".m3u8") || path.endsWith(".mpd") || lower.contains(".m3u8?") || lower.contains(".mpd?")) return true
        if (lower.contains("playlist.php") || lower.contains("/api/orion/")) return true
        if ((host.startsWith("hls.") || host.startsWith("stream.")) && (host.contains("vidbasic.") || host.contains("vidb.top"))) return true
        if (host.contains("lisaido.top") || host.contains("premilkyway.com")) return true

        // Direct CDN locker endpoints
        if ((host.contains("downloadwella.com") || host.contains("wetafiles.com")) && path.contains("/d/")) return true
        // vikingfile.com /d/ is NOT here on purpose: it 302-hops to the R2
        // storage URL and VikingFileResolver must crack it — treating /d/ as
        // already-direct sent parallel sockets at the Nginx front (429s,
        // commit 0a5b1df).
        if (host.contains("wildshare.net") && (lower.contains("download_token=") || path.contains("/d/"))) return true
        if (host.contains("gfrdaseazzs.com")) return true
        if (host.contains("loadedfiles") && (path.contains("/token/download/") || path.contains("/d/"))) return true
        // kissorgrab.com/dl/ is the cracked direct media endpoint — NOT a locker
        // page. The dl.plutomovies.com redirect chain lands here and the bytes
        // are a real MKV file (live-verified 2026-09-19: HTTP 206,
        // Content-Disposition: attachment; filename=".mkv", MKV magic header).
        if (host.contains("kissorgrab.com") && path.contains("/dl/")) return true
        // nkiserv.com is nkiri's own CDN (the host was de-listed from the
        // locker list for exactly this — see KNOWN_LOCKER_HOSTS): naijavault
        // drawers hand out direct ds2.nkiserv.com/TV/*.mkv files. Its media
        // paths are finished direct files, never pages to crack
        // (live log 23:23:03).
        if (host.contains("nkiserv.com") &&
            (path.endsWith(".mkv") || path.endsWith(".mp4") || path.endsWith(".avi") || path.endsWith(".webm") || path.contains("/tv/"))
        ) return true
        // waffi.cloud direct CDN stream endpoints (japa.waffi.cloud, drip.waffi.cloud)
        // Never treat ?preview as direct file: ?preview serves an HTML landing page!
        if (host.contains("waffi.cloud")) {
            if (lower.contains("preview")) return false
            if (path.contains("/c/") || path.endsWith(".mkv") || path.endsWith(".mp4")) return true
        }

        val otaLockers = com.anonrode.downloader.data.rules.DynamicRulesManager.getLockerHosts()
        val isLockerHost = (KNOWN_LOCKER_HOSTS + otaLockers).any { host.contains(it) }
        if (!isLockerHost && (query.contains("download_token=") || query.contains("media_token=") || query.contains("direct_download=1"))) return true
        // R2 / S3 / Object Storage signed links: the query string contains
        // AWS-style signing parameters and/or response-content-disposition.
        // These are resolver outputs (the direct cracked media stream) serving
        // the raw file object, not web pages.
        if (query.contains("response-content-disposition=") ||
            query.contains("x-amz-signature=") ||
            query.contains("x-amz-credential=") ||
            query.contains("x-amz-algorithm=") ||
            query.contains("filename=") ||
            query.contains("filename*=")) return true

        // Cloud storage object endpoints
        if (lower.contains("r2.cloudflarestorage.com") || lower.contains(".r2.dev/")) return true
        if (host.contains("b-cdn.net") || host.contains("backblazeb2.com") ||
            host.contains("digitaloceanspaces.com") || host.contains("wasabisys.com") ||
            host.contains("storage.googleapis.com") || host.contains("blob.core.windows.net") ||
            host.contains(".amazonaws.com")) return true

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
        if (KNOWN_LOCKER_HOSTS.any { host.contains(it) }) return true
        val otaLockers = com.anonrode.downloader.data.rules.DynamicRulesManager.getLockerHosts()
        return otaLockers.any { host.contains(it) }
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
     *
     * [memoTaskId] threads the download engine's per-task dead-URL memory
     * (engine.ResolveBudget) down to the registry. Null (the oracle and every
     * other non-engine caller) leaves this chain's behavior byte-identical
     * to pre-memo semantics; with a task id, a url that already failed one
     * FULL crack pass in that task is refused without any request, and every
     * nested registry re-entry inherits the memo via its coroutine context.
     */
    suspend fun resolveChain(
        permUrl: String,
        site: String,
        defaultQual: String,
        bypassHealth: Boolean = false,
        allowCacheHit: Boolean = false,
        recordHealth: Boolean = true,
        memoTaskId: String? = null
    ): String? {
        fun acceptShape(out: String?): Boolean {
            if (out.isNullOrBlank()) return false
            if (out == permUrl) return false
            if (isProvablyDirectFile(out) || isDirectMediaUrl(out) || out.contains(".m3u8", ignoreCase = true) || out.contains(".mpd", ignoreCase = true)) {
                return true
            }
            return !isKnownLockerHost(out)
        }

        /** Shape + EVIDENCE acceptance (2026-10-20). A URL that survives the
         *  shape rules but is a manifest-shaped endpoint is inspected once:
         *  a self-referential master or a sub-1KiB mpegurl decoy is rejected
         *  and poisoned so no cache can replay it. Direct media paths skip
         *  the network check entirely (zero extra cost on the common path). */
        suspend fun acceptEvidence(out: String?, entryUrl: String): String? {
            if (!acceptShape(out)) return null
            val candidate = UrlClassifier.repairConcatenatedUrl(out!!.trim())
            if (candidate == entryUrl) return null
            if (UrlClassifier.isDirectMediaPath(candidate) && !UrlClassifier.isStreamingLink(candidate)) {
                return candidate
            }
            if (!UrlClassifier.isManifestShaped(candidate) && !candidate.contains("proxy", ignoreCase = true)) {
                return candidate
            }
            val evidence = UrlClassifier.inspectTerminal(
                candidate,
                referer = com.anonrode.downloader.data.rules.DynamicRulesManager.resolveReferer(candidate),
                tag = "resolve-evidence"
            )
            return when (evidence.kind) {
                UrlClassifier.TerminalKind.SELF_REFERENTIAL,
                UrlClassifier.TerminalKind.STUB -> {
                    ResolveCache.poison(candidate)
                    com.anonrode.downloader.util.DebugLog.resolve(
                        "resolve evidence: rejected ${evidence.kind} terminal (${evidence.reason}) ${candidate.take(140)}"
                    )
                    null
                }
                else -> candidate
            }
        }

        // 1. Try direct resolution via ResolverRegistry.
        if (!allowCacheHit) {
            ResolveCache.invalidate(ResolveCache.keyFor(permUrl, defaultQual))
        }
        var resolved = ResolverRegistry.resolve(
            permUrl, defaultQual, bypassHealth = bypassHealth, recordHealth = recordHealth, memoTaskId = memoTaskId
        )
        acceptEvidence(resolved, permUrl)?.let { return it }

        // 2. Try ProviderRegistry
        if (site.isNotBlank()) {
            try {
                val recipe = ProviderRegistry.resolveEpisode(site, permUrl, defaultQual)
                if (recipe.directUrl.isNotBlank() && recipe.directUrl != permUrl) {
                    ResolvedHints.remember(permUrl, recipe)
                    resolved = recipe.directUrl
                }
            } catch (_: Exception) {}
        }

        if (!acceptShape(resolved)) {
            for (provider in ProviderRegistry.enabledProviders) {
                if (provider.canHandle(permUrl)) {
                    try {
                        val recipe = provider.resolveEpisode(permUrl, defaultQual)
                        if (recipe.directUrl.isNotBlank() && recipe.directUrl != permUrl) {
                            ResolvedHints.remember(permUrl, recipe)
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
        if (!acceptShape(resolved) && !resolved.isNullOrBlank() && isKnownLockerHost(resolved)) {
            try {
                val inner = ResolverRegistry.resolve(resolved, defaultQual, memoTaskId = memoTaskId)
                if (acceptShape(inner)) {
                    resolved = inner
                }
            } catch (_: Exception) {}
        }

        return acceptEvidence(resolved, permUrl)
    }

    /** The oracle's terminal check: one no-redirect Range: bytes=0-1024 request
     *  (at most 1 KiB — metered-data discipline, see probeTerminal doc).
     *  Non-null totalBytes = the URL really serves a file: LIVE proof. */
    fun probeTerminal(url: String, referer: String? = null, tag: String? = null): Long? =
        HttpClient.probeTerminal(url, referer = referer, tag = tag)?.totalBytes

    /** Stub floor for HLS playlist probes (activity-log audit §3.4): the probe
     *  accepts ANY 200 for an m3u8/mpegurl target, so a decoy
     *  `master.m3u8 -> 200 Content-Length: 1` still classifies as a validated
     *  terminal (HttpClient.kt:1303-1308 — the non-HLS branch carries the
     *  1024-byte class gate, the HLS branch deliberately does not). Callers
     *  here only see the probe's totalBytes, so they re-apply the same
     *  1024-byte class before treating a sub-floor size on an HLS-shaped URL
     *  as a playlist proof. */
    const val HLS_STUB_MIN_BYTES = 1024L

    fun isHlsStubTerminal(url: String, totalBytes: Long): Boolean {
        if (totalBytes <= 0L || totalBytes >= HLS_STUB_MIN_BYTES) return false
        val low = url.substringBefore('?').lowercase()
        return low.contains("m3u8") || low.contains("mpegurl")
    }
}

/**
 * Bounded in-memory memory of the non-URL payload a provider's recipe
 * returned for an episode: the CDN Referer it requires (e.g. nsrplay's
 * `data.referer` -> vimeos) and its subtitle tracks.
 *
 * The download path re-resolves from the episode page inside
 * DownloadEngine.startTask, but LinkResolver's contract only returns a
 * String. This cache carries the recipe's headers/subtitles across that
 * boundary without changing every caller's signature. Entries are keyed by
 * BOTH the episode URL and the resolved direct URL, expire with the token TTL
 * window, and are bounded so a long browsing session cannot grow them.
 *
 * Memory-only by design, like ResolveCache: nothing is persisted.
 */
object ResolvedHints {

    data class Hints(
        val referer: String = "",
        val subtitles: List<SubtitleTrack> = emptyList(),
        val expiresAtMs: Long
    )

    private const val TTL_MS = 30 * 60_000L
    private const val MAX_ENTRIES = 128

    private val map = java.util.concurrent.ConcurrentHashMap<String, Hints>()

    private fun key(url: String): String = url.trim()

    fun remember(episodeUrl: String, recipe: com.anonrode.downloader.data.models.DownloadRecipe) {
        if (episodeUrl.isBlank()) return
        val referer = recipe.headers.entries
            .firstOrNull { it.key.equals("referer", ignoreCase = true) }
            ?.value?.takeIf { it.isNotBlank() }
            .orEmpty()
        val sub = recipe.subtitles
        if (referer.isBlank() && sub.isEmpty()) return
        val hints = Hints(referer, sub, System.currentTimeMillis() + TTL_MS)
        put(episodeUrl, hints)
        if (recipe.directUrl.isNotBlank()) put(recipe.directUrl, hints)
    }

    fun remember(episodeUrl: String, directUrl: String, referer: String?, subtitles: List<SubtitleTrack>) {
        if (episodeUrl.isBlank() && directUrl.isBlank()) return
        if (referer.isNullOrBlank() && subtitles.isEmpty()) return
        val hints = Hints(referer.orEmpty(), subtitles, System.currentTimeMillis() + TTL_MS)
        if (episodeUrl.isNotBlank()) put(episodeUrl, hints)
        if (directUrl.isNotBlank()) put(directUrl, hints)
    }

    private fun put(url: String, hints: Hints) {
        if (map.size >= MAX_ENTRIES) {
            val now = System.currentTimeMillis()
            map.entries.removeIf { now >= it.value.expiresAtMs }
            if (map.size >= MAX_ENTRIES) {
                map.keys.take(map.size / 2).forEach { map.remove(it) }
            }
        }
        map[key(url)] = hints
    }

    private fun live(url: String): Hints? {
        if (url.isBlank()) return null
        val h = map[key(url)] ?: return null
        if (System.currentTimeMillis() >= h.expiresAtMs) {
            map.remove(key(url), h)
            return null
        }
        return h
    }

    /** CDN referer the provider recipe declared for this episode/stream. */
    fun refererFor(url: String): String? = live(url)?.referer?.takeIf { it.isNotBlank() }

    /** Subtitle tracks proved for this episode (empty = none known). */
    fun subtitlesFor(url: String): List<SubtitleTrack> = live(url)?.subtitles.orEmpty()

    /** Tests only. */
    internal fun clearForTests() = map.clear()
}
