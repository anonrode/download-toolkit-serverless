package com.anonrode.downloader.resolvers

import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.pipeline.HostHealth
import com.anonrode.downloader.pipeline.PipelineJournal
import com.anonrode.downloader.pipeline.StreamValidator
import org.jsoup.Jsoup

/**
 * Centralized locker/media discovery and resolution.
 *
 * The old design: each provider maintained its own host list, and any host
 * it didn't know was silently dropped → "0 episodes" on shows using new
 * lockers (The Blood of Youth's 40 streamsss episodes, live-verified).
 *
 * This design: EXTRACT EVERYTHING, ARBITRATE BY EVIDENCE.
 * - Every plausible anchor from the episode page is collected (no host filter).
 * - classify() sorts by evidence: Direct (known extension), Locker (known
 *   host), Unknown (seen for the first time), None (nav junk).
 * - resolveCandidates() races known lockers via resolveAny, probes unknown
 *   hosts once via StreamValidator — if valid, it works on first contact.
 *   No code path can refuse an unfamiliar link.
 * - HostHealth records successes so unknown hosts derive their own reputation.
 * - The playbook seeds the initial host list (OTA, signed); the app learns
 *   beyond it.
 */
object LockerRegistry {

    private val DEFAULT_LOCKER_HOSTS = listOf(
        "streamsss.net", "streamwish.com", "streamtape.com", "doodstream.com",
        "dood.", "vidhide.com", "mixdrop.co", "mp4upload.com", "hglink.tv",
        "loadedfiles.net", "downloadwella.com", "wetafiles.com",
        "vikingfile.com", "lulacloud.com", "waffi", "pixeldrain.com",
        "filevault", "kissorgrab.com", "wildshare", "gtoddl", "wapkizfile",
        "fastupload.io", "gofile.io", "krakenfiles.com", "swish"
    )

    /** Path segments that mark a page as navigation, never media. Applied to
     *  UNKNOWN hosts only — known lockers and proven hosts already returned. */
    private val NAV_SEGMENTS = setOf(
        "tag", "category", "categories", "dmca", "menu", "page", "pages",
        "author", "about", "contact", "privacy", "policy", "terms", "sitemap",
        "feed", "login", "register", "signin", "signup", "account", "cart",
        "checkout", "search", "faq", "help", "request", "submit", "advertise",
        "wp-content", "wp-json", "wp-admin", "cdn-cgi", "email-protection",
        "series-download", "movie-download", "download-movies", "download-series",
        "cant-download", "downloader", "date", "archive"
    )

    sealed class MediaKind {
        /** Direct media file or known stream URL (unchanged since extraction). */
        object Direct : MediaKind()
        /** Known locker host that needs resolution (via ResolverRegistry). */
        data class Locker(val host: String) : MediaKind()
        /** Not a known locker but not nav junk — probe once to decide. */
        data class Unknown(val host: String) : MediaKind()
        /** Nav link, category page, or other junk — skip. */
        object None : MediaKind()
    }

    /** Single-pass classification: hostname-boundary match, no substring false positives. */
    fun classify(url: String): MediaKind {
        if (url.isBlank()) return None
        val clean = url.trim().substringBefore('?').substringBefore('#')
        val host = try { java.net.URI(clean).host?.lowercase() ?: "" } catch (_: Exception) { "" }
        if (host.isBlank()) return None

        // Direct media extensions
        val ext = clean.substringAfterLast('.').lowercase()
        if (ext in setOf("mp4", "mkv", "webm", "avi", "m3u8", "m4v", "ts", "mp3")) return Direct

        // Known locker hosts (OTA data + built-in defaults + learned from HostHealth)
        val knownHosts = DynamicRulesManager.getLockerHosts().ifEmpty { DEFAULT_LOCKER_HOSTS }
        for (kh in knownHosts) {
            if (host == kh || host.endsWith(".$kh")) return Locker(kh)
        }
        // Move 2 (learning): a host that has PROVEN itself (>=1 successful
        // crack recorded in HostHealth) is treated as known even if the
        // playbook never listed it — streamsss.net works once, and every
        // episode after that is a known locker, no OTA needed.
        if (HostHealth.hasProvenLocker(host)) {
            return Locker(host)
        }

        // Path-based heuristics for unknown hosts (known lockers and proven
        // hosts already returned above).
        val path = try { java.net.URI(clean).path ?: "" } catch (_: Exception) { "" }
        val segments = path.split('/').filter { it.isNotBlank() }

        // Strong media signals: /dl-xxx or deep /download/ paths.
        if (path.contains("/dl-") || (path.contains("/download/") && segments.size >= 3)) return Unknown(host)

        // Nav-junk: known nav words anywhere in the path (tag/category/dmca/
        // menus/policy pages), or shallow generic paths — the dramarain ?s=
        // lesson: a dead search endpoint returns single-segment category
        // cards. Media markers (-episode-, season, -drama, -movie-) keep
        // shallow paths alive (nkiri same-site episode links).
        val navWord = segments.any { s ->
            s in NAV_SEGMENTS || s.startsWith("how-to") || s.endsWith("-menu") || s.contains("movies")
        }
        if (navWord) return None
        if (segments.isEmpty()) return None
        // Shallow single-segment paths are nav unless they carry media
        // markers: -episode-, season, -movie-, or a show-style slug ending
        // in -drama with >= 2 dashes ("vincenzo-korean-drama" is a show;
        // "chinese-drama" is a category page).
        if (segments.size == 1) {
            val seg = segments.first()
            val showLike = seg.contains("-episode-") || seg.contains("season") ||
                seg.contains("-movie-") || (seg.endsWith("-drama") && seg.count { it == '-' } >= 2)
            if (!showLike) return None
        }

        return Unknown(host)
    }

    /**
     * Extract all plausible locker/media URLs from raw HTML.
     * Jsoup-first (href, data-video, data-src), regex fallback for obfuscated pages.
     * Nav-junk filtered: requires path depth >= 3 OR host is a known locker.
     */
    fun findLockerLinksInHtml(html: String): List<String> {
        val found = mutableSetOf<String>()
        try {
            val doc = Jsoup.parse(html)
            for (a in doc.select("a[href], a[data-video], a[data-src], iframe[src], video[src], source[src]")) {
                val u = a.attr("abs:href").ifBlank { a.attr("href").ifBlank { a.attr("data-video").ifBlank { a.attr("data-src") } } }
                if (u.isNotBlank() && classify(u) != None) found.add(u)
            }
        } catch (_: Exception) {}
        // Regex fallback for obfuscated/scripted DOMs
        if (found.isEmpty()) {
            val re = Regex("""https?://[^\s"'<>]+(?:\.(?:mp4|mkv|webm|avi|m3u8)|/(?:dl-|download/|embed/|e/|d/))[^\s"'<>]*""", RegexOption.IGNORE_CASE)
            for (m in re.findAll(html)) {
                val u = m.value.trimEnd('.', ',', ')', ']')
                if (classify(u) != None) found.add(u)
            }
        }
        return found.toList()
    }

    /**
     * Resolve a set of candidates to a single stream URL.
     * - Direct media → pass through.
     * - Known lockers → race via ResolverRegistry.resolveAny.
     * - Unknown hosts → probe once via StreamValidator; if valid, it works
     *   on first contact. HostHealth records the outcome.
     */
    suspend fun resolveCandidates(urls: List<String>, quality: String = "720p"): String? {
        val direct = urls.firstOrNull { classify(it) is MediaKind.Direct }
        if (direct != null) return direct

        val lockers = urls.filter { classify(it) is MediaKind.Locker }
        if (lockers.isNotEmpty()) {
            val raced = ResolverRegistry.resolveAny(lockers, quality, maxConcurrency = 3)
            if (raced != null) return raced
        }

        val unknowns = urls.filter { classify(it) is MediaKind.Unknown }
        if (unknowns.isEmpty()) return null

        // Probe each unknown host once (paced via HostHealth, validated via
        // StreamValidator). First success wins.
        for (u in unknowns) {
            val host = try { java.net.URI(u).host ?: "" } catch (_: Exception) { "" }
            if (host.isBlank()) continue
            if (!HostHealth.isUsable(u)) {
                PipelineJournal.hop("", "locker-unknown", u, ok = false, ms = 0,
                    detail = "host backoff — skipping")
                continue
            }
            val start = System.currentTimeMillis()
            val reason = StreamValidator.validate(u, emptyMap())
            if (reason == null) {
                // Valid stream found on first contact — record success.
                HostHealth.recordOk(host)
                PipelineJournal.hop("", "locker-unknown", u, ok = true,
                    ms = System.currentTimeMillis() - start,
                    detail = "unknown host validated on first contact")
                return u
            }
            HostHealth.recordFail(host)
            PipelineJournal.hop("", "locker-unknown", u, ok = false,
                ms = System.currentTimeMillis() - start,
                detail = "probe rejected: $reason")
        }
        return null
    }
}