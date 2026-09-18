package com.anonrode.downloader.pipeline

import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.data.rules.DynamicRulesManager
import java.net.URI
import java.net.URLDecoder

/**
 * StrictLinkClassifier — The defensive extraction shield (R1).
 *
 * Categorizes extracted anchor links into:
 *  - [LinkClass.DirectMedia]: genuine media file or HLS/DASH manifest
 *  - [LinkClass.KnownLocker]: recognized file locker needing resolution
 *  - [LinkClass.IntermediateGateway]: redirect wrapper or multi-hop gateway needing unwrapping
 *  - [LinkClass.Unknown]: plausible external media/stream candidate requiring single-probe verification
 *  - [LinkClass.NavigationJunk]: homepage, category, tag, social, comment, or ad links to be dropped
 *
 * Enforces provider-locker boundaries so raw HTML and navigation anchors
 * are rejected before reaching resolvers or the download engine.
 */
object StrictLinkClassifier {

    sealed interface LinkClass {
        data class DirectMedia(val ext: String, val isHls: Boolean) : LinkClass
        data class KnownLocker(val host: String) : LinkClass
        data class IntermediateGateway(val gatewayType: String, val targetUrl: String?) : LinkClass
        data class Unknown(val host: String) : LinkClass
        data class NavigationJunk(val reason: String) : LinkClass
    }

    private val DIRECT_EXTENSIONS = setOf(
        "mp4", "mkv", "webm", "avi", "m3u8", "m4v", "ts", "mov", "flv", "mp3"
    )

    private val DEFAULT_LOCKER_HOSTS = listOf(
        "streamsss.net", "streamwish.com", "streamtape.com", "doodstream.com",
        "dood.", "vidhide.com", "mixdrop.co", "mp4upload.com", "hglink.tv",
        "loadedfiles.net", "loadedfiles.com", "downloadwella.com", "wetafiles.com",
        "vikingfile.com", "lulacloud.com", "waffi", "pixeldrain.com",
        "filevault", "kissorgrab.com", "wildshare", "gtoddl", "wapkizfile",
        "fastupload.io", "gofile.io", "krakenfiles.com", "swish"
    )

    private val NAV_PATH_SEGMENTS = setOf(
        "tag", "category", "categories", "dmca", "menu", "page", "pages",
        "author", "about", "contact", "privacy", "policy", "terms", "sitemap",
        "feed", "login", "register", "signin", "signup", "account", "cart",
        "checkout", "search", "faq", "help", "request", "submit", "advertise",
        "wp-content", "wp-json", "wp-admin", "cdn-cgi", "email-protection",
        "series-download", "movie-download", "download-movies", "download-series",
        "cant-download", "downloader", "how-to-download", "date", "archive",
        "season-list", "series-list"
    )

    private val MEDIA_EXT_REGEX = Regex("""\.(mkv|mp4|webm|avi|m3u8|m4v|ts|zip|rar)(?:[?#]|$)""", RegexOption.IGNORE_CASE)

    /**
     * Classifies a link with full context.
     *
     * @param url The link under inspection.
     * @param siteHost Optional provider host (e.g. "naijavault.com", "thenkiri.com") to evaluate same-site nav.
     * @param currentPath Optional page path for self-reference detection.
     */
    fun classify(
        url: String,
        siteHost: String? = null,
        currentPath: String? = null
    ): LinkClass {
        val trimmed = url.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("javascript:") ||
            trimmed.startsWith("mailto:") || trimmed.startsWith("tel:")
        ) {
            return LinkClass.NavigationJunk("invalid_or_non_http")
        }

        val clean = trimmed.substringBefore('#')
        val uri = try {
            URI(clean)
        } catch (_: Exception) {
            return LinkClass.NavigationJunk("malformed_uri")
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return LinkClass.NavigationJunk("non_http_scheme")
        }

        val host = uri.host?.lowercase() ?: return LinkClass.NavigationJunk("missing_host")
        val cleanHost = host.removePrefix("www.")
        val cleanSiteHost = siteHost?.lowercase()?.removePrefix("www.")
        val path = uri.path?.lowercase().orEmpty()

        // 1. Homepage / root path check (empty or "/" path is always nav junk)
        if (path.isBlank() || path == "/") {
            return LinkClass.NavigationJunk("homepage")
        }

        // Same-site self-reference check
        if (cleanSiteHost != null && (cleanHost == cleanSiteHost || cleanHost.endsWith(".$cleanSiteHost"))) {
            if (currentPath != null && path.trimEnd('/') == currentPath.trimEnd('/')) {
                return LinkClass.NavigationJunk("self_reference")
            }
        }

        // 2. Intermediate gateway unwrap check (e.g. Nkiri ?redirect=, ?url=)
        val unwrapped = unwrapRedirect(clean)
        if (unwrapped != null && unwrapped != clean) {
            return LinkClass.IntermediateGateway("redirect_wrapper", unwrapped)
        }

        // 3. Known multi-hop gateway patterns (e.g. NaijaVault /dl-, /sdm_downloads/)
        if (path.startsWith("/dl-") || path.contains("/sdm_downloads/")) {
            return LinkClass.IntermediateGateway("download_gateway", null)
        }

        // 4. Known locker hosts (dynamic OTA + defaults + HostHealth learned)
        // Checked BEFORE direct media extension: lockers like loadedfiles.net/.../ep.mkv
        // and vikingfile.com/d/.../ep.mkv embed media names in URL paths, but are HTML pages
        // or redirectors requiring cracking, not direct media files.
        val allLockers = (DynamicRulesManager.getLockerHosts() + DEFAULT_LOCKER_HOSTS).distinct()
        for (locker in allLockers) {
            if (cleanHost == locker || cleanHost.endsWith(".$locker")) {
                return LinkClass.KnownLocker(locker)
            }
        }
        if (HostHealth.hasProvenLocker(cleanHost)) {
            return LinkClass.KnownLocker(cleanHost)
        }

        // 5. Direct media extension check
        val ext = clean.substringBefore('?').substringAfterLast('.', "").lowercase()
        if (ext in DIRECT_EXTENSIONS) {
            val isHls = ext == "m3u8"
            return LinkClass.DirectMedia(ext, isHls)
        }

        // 6. Navigation junk filtering
        val segments = path.split('/').filter { it.isNotBlank() }
        val hasNavWord = segments.any { seg ->
            seg in NAV_PATH_SEGMENTS || seg.startsWith("how-to") || seg.endsWith("-menu")
        }
        if (hasNavWord) {
            return LinkClass.NavigationJunk("nav_path_segment")
        }

        // Same-site content page filtering (sibling posts vs genuine download targets)
        if (cleanSiteHost != null && (cleanHost == cleanSiteHost || cleanHost.endsWith(".$cleanSiteHost"))) {
            val isDownloadPath = path.contains("/dl-") || path.contains("/download/") ||
                path.contains("/cdn/") || MEDIA_EXT_REGEX.containsMatchIn(path)
            if (!isDownloadPath) {
                return LinkClass.NavigationJunk("same_site_non_download")
            }
        }

        // 7. Shallow single-segment path heuristic for unknown hosts
        if (segments.size == 1) {
            val seg = segments.first()
            val showLike = seg.contains("-episode-") || seg.contains("season") ||
                seg.contains("-movie-") || (seg.endsWith("-drama") && seg.count { it == '-' } >= 2)
            if (!showLike) {
                return LinkClass.NavigationJunk("shallow_unknown_path")
            }
        }

        return LinkClass.Unknown(cleanHost)
    }

    /**
     * Unwraps download manager redirect wrappers:
     * e.g. `.../dl/download-17827/?redirect=https%3A%2F%2Fdownloadwella.com%2F...`
     * Returns decoded target URL if valid HTTP(S), or null.
     */
    fun unwrapRedirect(url: String): String? {
        return try {
            val wrapper = URI(url.trim())
            val path = wrapper.path?.lowercase().orEmpty()
            val isDownloadWrapper = path.startsWith("/dl/") || path.contains("/download/") ||
                path.contains("download-manager")
            val query = wrapper.rawQuery ?: return null
            val parameter = query.split('&').firstOrNull { param ->
                val key = URLDecoder.decode(param.substringBefore('='), "UTF-8").lowercase()
                key in setOf("redirect", "url", "link", "target", "destination")
            } ?: return null

            val target = URLDecoder.decode(parameter.substringAfter('=', ""), "UTF-8").trim()
            val parsed = URI(target)
            val scheme = parsed.scheme?.lowercase()
            if ((scheme == "http" || scheme == "https") && !parsed.host.isNullOrBlank()) {
                target
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun isDirectMedia(url: String): Boolean = classify(url) is LinkClass.DirectMedia

    fun isKnownLocker(url: String): Boolean = classify(url) is LinkClass.KnownLocker

    fun isIntermediateGateway(url: String): Boolean = classify(url) is LinkClass.IntermediateGateway

    fun isNavigationJunk(url: String, siteHost: String? = null, currentPath: String? = null): Boolean =
        classify(url, siteHost, currentPath) is LinkClass.NavigationJunk

    fun isCandidateForDownload(url: String, siteHost: String? = null): Boolean {
        val c = classify(url, siteHost)
        return c !is LinkClass.NavigationJunk
    }
}
