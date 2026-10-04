package com.anonrode.downloader.util

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/**
 * PostContentSanitizer — Defensive DOM and Link Isolation Shield.
 *
 * Prevents CMS/WordPress related-posts widgets, sidebars, recommendation carousels,
 * and sibling post permalinks from leaking into episode download drawers.
 */
object PostContentSanitizer {

    /**
     * Known related-post, recommendation, sidebar, comment, and navigational
     * container selectors across WordPress and CMS streaming templates.
     */
    val JUNK_CONTAINER_SELECTORS = listOf(
        // Related post plugins & widgets
        ".crp_related", ".crp-related", "[class*='crp_related']",
        ".yarpp-related", ".yarpp_related", ".yarpp-related-none",
        ".jp-relatedposts", "#jp-relatedposts",
        ".related-posts", ".relatedposts", ".related-post", ".related_posts",
        ".related-articles", ".related-entries", ".related-content", ".related-items",
        ".related-meta", ".related-wrap", ".entry-related",
        ".mh-related", ".mh-related-bottom",
        ".single-related", ".post-related", ".similar-posts",
        "#episode_related", ".episodes-related", ".series-related", ".drama-related",
        ".relat", ".relate", ".relatedlist",
        // Sidebars, widgets, navigation, footers, headers
        "aside", ".sidebar", "#sidebar", ".widget", ".widget-area",
        ".site-sidebar", ".main-sidebar", ".secondary-sidebar", ".mh-sidebar",
        ".mh-widget-col-1", ".mh-widget-col-2",
        "footer", "#footer", ".site-footer",
        "nav", ".site-navigation", ".navigation", ".post-navigation", ".nav-links",
        // Social, comments, ads, tags
        ".comments", "#comments", ".comment-respond", ".mh-comments", "#mh-comments",
        ".sharedaddy", ".sd-sharing", ".share-buttons", ".social-share",
        ".tagsul", ".entry-tags", ".post-tags", ".tags-links",
        ".author-box", ".author-bio",
        ".adsbygoogle", ".code-block", ".ad-container", ".advertisement"
    ).joinToString(", ")

    private val SIBLING_POST_TEXT_PATTERN = Regex(
        """(?i)\b(?:chinese|nollywood|hollywood|bollywood|korean|japanese)\s+movies?\b|""" +
        """\((?:action|adventure|drama|thriller|comedy|romance|fantasy|horror|sci-fi|animation)\)|""" +
        """\b(?:season\s+\d+\s+episode\s+\d+.*(?:added|update))\b""",
        RegexOption.IGNORE_CASE
    )

    private val KNOWN_DOWNLOAD_GATEWAY_PATHS = listOf(
        "/sdm_downloads/",
        "/dl-",
        "/download.php",
        "?download",
        "?pt="
    )

    private val DIRECT_MEDIA_EXTENSIONS = setOf(
        "mp4", "mkv", "webm", "avi", "m3u8", "m4v", "ts", "mov", "flv", "zip", "rar", "7z"
    )

    /**
     * Purges junk and recommendation elements from the given DOM node in place.
     *
     * @param element The container element (or Document) to sanitize.
     * @return The same element for fluent chaining.
     */
    fun clean(element: Element): Element {
        try {
            element.select(JUNK_CONTAINER_SELECTORS).remove()
        } catch (_: Exception) {}
        return element
    }

    /**
     * Determines whether anchor text strongly exhibits sibling-post title patterns
     * rather than legitimate download or episode labels.
     *
     * Never flags legitimate download buttons such as "Server 1", "Server 2",
     * "Download 720p", "Download 1080p", "Part 1", "Complete Season ZIP", etc.
     */
    fun isSiblingPostAnchorText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return false

        // Never drop standard download/server/part/quality/episode button text
        val isLegitDownloadButton = Regex(
            """(?i)^\s*(?:download(?:\s+(?:\d{1,4}|movie|now|full|all))?|""" +
            """(?:episode|ep|e)\s*\d{1,4}.*|""" +
            """server\s*\d{1,2}.*|part\s*\d{1,2}.*|""" +
            """(?:s\d{1,2}\s*)?complete\s+season.*|""" +
            """\d{3,4}p.*|hd.*|fast\s+download.*)\s*$"""
        ).matches(trimmed)

        if (isLegitDownloadButton) return false

        // Check if anchor text contains category/genre descriptors typical of related posts
        if (SIBLING_POST_TEXT_PATTERN.containsMatchIn(trimmed)) {
            return true
        }

        return false
    }

    /**
     * Checks if a URL pointing to the site's own domain is a sibling post article
     * rather than a valid file/gateway download link.
     */
    fun isSameSitePostPermalink(href: String, siteHost: String): Boolean {
        if (href.isBlank()) return false
        val uri = try { URI(href) } catch (_: Exception) { return false }
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return false
        val cleanSiteHost = siteHost.lowercase().removePrefix("www.")

        if (host != cleanSiteHost && !host.endsWith(".$cleanSiteHost")) {
            // External domain (e.g. locker, CDN, third-party download monitor) -> not a same-site post
            return false
        }

        val rawPath = uri.rawPath ?: ""
        val lowerPath = rawPath.lowercase()
        val query = uri.rawQuery?.lowercase().orEmpty()

        // 1. Direct media files on same host are always legitimate
        val ext = lowerPath.substringAfterLast('.', "")
        if (ext in DIRECT_MEDIA_EXTENSIONS) return false

        // 2. Recognized download gateways on same host are legitimate
        for (gw in KNOWN_DOWNLOAD_GATEWAY_PATHS) {
            if (gw.startsWith("?") && query.contains(gw.removePrefix("?"))) return false
            if (lowerPath.contains(gw)) return false
        }

        // 3. Blog post / article permalink checks
        // On NaijaPrey, movie permalinks match /download-<movie-title>/
        // Generic WP movie permalinks match /<movie-title>/ or /archives/...
        if (lowerPath.startsWith("/download-") || lowerPath.startsWith("/drama/") ||
            lowerPath.startsWith("/anime/") || lowerPath.startsWith("/movies/") ||
            lowerPath.startsWith("/film/") || lowerPath.count { it == '/' } in 1..2
        ) {
            return true
        }

        return false
    }
}
