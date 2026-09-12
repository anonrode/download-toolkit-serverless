package com.anonrode.downloader.providers

/**
 * Shared "does this post body actually carry a download link" test.
 *
 * Two call sites, both paying ZERO extra network: the markers are looked for in
 * response bodies the callers already receive — the WP-REST `content.rendered`
 * field (TrendingFeed + NaijaVaultProvider.search, which use /wp-json with the
 * default content field) and RSS `content:encoded` (TrendingFeed's two feed
 * sites). Search/trending results whose body has no download link are either
 * site-side stub posts or YouTube-watch embeds (live-verified 2026-09-11:
 * naijavault "?search=revenge" returns 10 posts; the 4 without markers link
 * only to a yooyotvlive.com.ng YouTube wrapper) — cards the user could tap
 * into an empty drawer.
 *
 * The caller keeps its fallback (if the whole batch misses the markers — a
 * future locker host unknown here — keep the ungated batch): an all-empty row
 * is worse than a few stub cards.
 */
object DownloadLinkGate {

    // Mirrors the providers' own locker criteria (Nkiri/Rocks sweep lists,
    // NaijaVault /dl- gateway pages, NaijaPrey sdm/wildshare chain).
    private val MARKERS = listOf(
        "/dl-", "downloadwella.com", "wetafiles.com", "loadedfiles", "nkiserv.com",
        "vikingfile", "lulacloud", "waffi", "sdm_downloads", "np-downloader", "wildshare"
    )

    fun hasDownloadLink(content: String): Boolean {
        if (content.isBlank()) return false
        val low = content.lowercase()
        return MARKERS.any { low.contains(it) }
    }
}
