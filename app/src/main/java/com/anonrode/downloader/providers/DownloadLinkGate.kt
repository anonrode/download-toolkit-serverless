package com.anonrode.downloader.providers

/**
 * Shared "does this post body actually carry a download link" test.
 *
 * Four call sites, all paying ZERO extra network: the markers are looked for
 * in response bodies the caller already received. WP-REST `content.rendered`
 * (TrendingFeed + NaijaVaultProvider.search, which fetch /wp-json with the
 * default content field) and RSS `content:encoded` (TrendingFeed's feed sites
 * + NaijaPreyProvider.search + RocksProvider.search — the `/search/<q>/feed/rss2/`
 * body ships the whole post, same bytes the provider downloads anyway; verified
 * 2026-09-11: 5/5 naijaprey and 22/22 9jarocks items carry a locker link, so
 * this is a pure future-stub guard, it never drops a live result). Results
 * whose body has no download link are either site-side stub posts or
 * YouTube-watch embeds (live-verified 2026-09-11: naijavault "?search=revenge"
 * returns 10 posts; the 4 without markers link only to a yooyotvlive.com.ng
 * YouTube wrapper) — cards the user could tap into an empty drawer.
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
