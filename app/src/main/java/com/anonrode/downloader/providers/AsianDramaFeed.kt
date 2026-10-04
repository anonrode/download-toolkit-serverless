package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.util.ExplicitContentFilter
import com.anonrode.downloader.util.NameSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup

object AsianDramaFeed {

    private const val TIMEOUT_MS = 8000L
    private const val PER_SITE_LIMIT = 24
    private const val MAX_FEED_CARDS = 60

    fun cacheKeyFor(region: DramaRegion, era: DramaEra): String =
        "drama_${region.tag}_${era.tag}"

    suspend fun fetch(
        region: DramaRegion,
        era: DramaEra,
        page: Int = 1,
        forceRefresh: Boolean = false,
        filterExplicit: Boolean = true,
        onPartial: (List<ShowCard>) -> Unit = {}
    ): List<ShowCard> {
        val key = cacheKeyFor(region, era)

        // 1. Return fresh cached cards immediately if available on page 1
        if (page == 1 && !forceRefresh && FeedCache.isCategoryFresh(key)) {
            val cached = FeedCache.categoryCards(key)
            if (cached.isNotEmpty()) {
                val filtered = if (filterExplicit) ExplicitContentFilter.filterSafe(cached) else cached
                onPartial(filtered)
                return filtered
            }
        }

        // 2. Fetch live across candidate providers concurrently
        return coroutineScope {
            val candidateSites = when (region) {
                DramaRegion.KDRAMA -> listOf("dramakey", "asianc", "dramarain", "nepu", "nkiri", "9jarocks")
                DramaRegion.CDRAMA -> listOf("dramarain", "dramakey", "asianc", "nepu", "nkiri", "9jarocks")
            }

            val slots = arrayOfNulls<List<ShowCard>>(candidateSites.size)
            val lock = Any()

            candidateSites.mapIndexed { i, site ->
                async(Dispatchers.IO) {
                    val cards = withTimeoutOrNull(TIMEOUT_MS) {
                        fetchSiteFor(site, region, era, page, filterExplicit)
                    } ?: emptyList()

                    synchronized(lock) {
                        slots[i] = cards
                        val availablePairs = candidateSites.mapIndexed { j, s -> s to (slots[j] ?: emptyList()) }
                        val currentMix = CategoryFeed.mixCards(availablePairs, filterExplicit)
                        if (currentMix.isNotEmpty()) {
                            onPartial(currentMix)
                        }
                    }
                }
            }.awaitAll()

            val finalPairs = candidateSites.mapIndexed { j, s -> s to (slots[j] ?: emptyList()) }
            val finalMixed = CategoryFeed.mixCards(finalPairs, filterExplicit)
            val result = finalMixed

            if (page == 1 && result.isNotEmpty()) {
                FeedCache.saveCategory(key, result)
            }
            result
        }
    }

    private suspend fun fetchSiteFor(
        site: String,
        region: DramaRegion,
        era: DramaEra,
        page: Int,
        filterExplicit: Boolean
    ): List<ShowCard> {
        return try {
            when (site) {
                "asianc" -> fetchAsianC(region, era, page, filterExplicit)
                "dramakey" -> fetchDramaKey(region, era, page, filterExplicit)
                "dramarain" -> fetchDramaRain(region, era, page, filterExplicit)
                "nepu" -> if (page > 1) emptyList() else fetchNepu(region, era, filterExplicit)
                "nkiri" -> fetchNkiri(region, era, page, filterExplicit)
                "9jarocks" -> fetch9jaRocks(region, era, page, filterExplicit)
                else -> emptyList()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private suspend fun fetchAsianC(
        region: DramaRegion,
        era: DramaEra,
        page: Int,
        filterExplicit: Boolean
    ): List<ShowCard> {
        val base = DynamicRulesManager.getBaseUrl("asianc").ifBlank { "https://asianc.id" }.trimEnd('/')
        val pageSuffix = if (page > 1) "&page=$page" else ""
        val url = when {
            region == DramaRegion.KDRAMA && era == DramaEra.HISTORICAL -> "$base/search?type=drama&keyword=historical$pageSuffix"
            region == DramaRegion.CDRAMA && era == DramaEra.HISTORICAL -> "$base/search?type=drama&keyword=wuxia$pageSuffix"
            region == DramaRegion.KDRAMA -> "$base/search?type=drama&keyword=korean$pageSuffix"
            else -> "$base/search?type=drama&keyword=chinese$pageSuffix"
        }

        val html = HttpClient.getText(url, referer = "$base/", tag = "browse") ?: return emptyList()
        val doc = Jsoup.parse(html, url)
        val cards = mutableListOf<ShowCard>()

        for (item in doc.select("ul.listing.items li, .list-episode-item li, .video-block, li.filter-item")) {
            val a = item.selectFirst("a[href*='/drama-detail/']") ?: item.selectFirst("a[href]") ?: continue
            val rawHref = a.attr("href")
            if (rawHref.isBlank()) continue
            val fullUrl = if (rawHref.startsWith("/")) "$base$rawHref" else rawHref

            val titleEl = item.selectFirst(".title, h3, .name") ?: a
            val rawTitle = titleEl.text().trim()
            if (rawTitle.isBlank()) continue

            val img = item.selectFirst("img")
            val poster = img?.attr("abs:data-original")?.ifBlank {
                img.attr("data-original").ifBlank {
                    img.attr("abs:src").ifBlank { img.attr("src") }
                }
            }.orEmpty()

            val clean = NameSanitizer.cleanTitle(rawTitle)
            val card = ShowCard(
                title = clean.ifBlank { rawTitle },
                url = fullUrl,
                posterUrl = poster,
                site = "asianc",
                category = if (region == DramaRegion.KDRAMA) "K-Drama" else "C-Drama",
                tags = listOf(region.label, era.label),
                genres = listOf(region.label, era.label)
            )

            if (!filterExplicit || !ExplicitContentFilter.isExplicit(card)) {
                cards.add(card)
            }
            if (cards.size >= PER_SITE_LIMIT) break
        }
        return cards
    }

    private suspend fun fetchDramaKey(
        region: DramaRegion,
        era: DramaEra,
        page: Int,
        filterExplicit: Boolean
    ): List<ShowCard> {
        val base = "https://dramakey.cc"
        val pagePrefix = if (page > 1) "page/$page/" else ""
        val url = when {
            era == DramaEra.HISTORICAL -> "$base/genre/historical/$pagePrefix"
            region == DramaRegion.KDRAMA -> "$base/korean/$pagePrefix"
            else -> "$base/chinese/$pagePrefix"
        }

        val html = HttpClient.getText(url, referer = "$base/", tag = "browse") ?: return emptyList()
        val doc = Jsoup.parse(html, url)
        val cards = mutableListOf<ShowCard>()

        for (item in doc.select("a.series-card-link, .series-item, .drama-card, article.series-card, article, .item")) {
            val a = (if (item.tagName() == "a") item else item.selectFirst("a.series-card-link") ?: item.selectFirst("a"))
                ?: item.parent()?.takeIf { it.tagName() == "a" } ?: continue
            val href = a.attr("abs:href").ifBlank {
                val raw = a.attr("href")
                if (raw.startsWith("/")) "$base$raw" else raw
            }
            if (href.isBlank()) continue

            val titleEl = item.selectFirst(".series-title, .title, h2, h3, .entry-title") ?: a
            val rawTitle = titleEl.text().trim().ifBlank { a.attr("aria-label").removePrefix("View ").trim() }
            if (rawTitle.isBlank() || rawTitle.length < 2) continue

            val img = item.selectFirst("img") ?: a.selectFirst("img")
            val poster = img?.attr("abs:src")?.ifBlank { img.attr("src") }.orEmpty()

            val clean = NameSanitizer.cleanTitle(rawTitle)
            val card = ShowCard(
                title = clean.ifBlank { rawTitle },
                url = href,
                posterUrl = poster,
                site = "dramakey",
                category = if (region == DramaRegion.KDRAMA) "K-Drama" else "C-Drama",
                tags = listOf(region.label, era.label),
                genres = listOf(region.label, era.label)
            )

            if (!filterExplicit || !ExplicitContentFilter.isExplicit(card)) {
                cards.add(card)
            }
            if (cards.size >= PER_SITE_LIMIT) break
        }
        return cards
    }

    private suspend fun fetchDramaRain(
        region: DramaRegion,
        era: DramaEra,
        page: Int,
        filterExplicit: Boolean
    ): List<ShowCard> {
        val base = DynamicRulesManager.getBaseUrl("dramarain").ifBlank { "https://dramarain.com" }.trimEnd('/')
        val pagePrefix = if (page > 1) "page/$page/" else ""
        val url = when {
            era == DramaEra.HISTORICAL -> "$base/tag/historical/$pagePrefix"
            else -> "$base/chinese-drama/$pagePrefix"
        }

        val html = HttpClient.getText(url, referer = "$base/", tag = "browse") ?: return emptyList()
        val doc = Jsoup.parse(html, url)
        val cards = mutableListOf<ShowCard>()

        for (item in doc.select("article, .post-item, .post")) {
            val a = item.selectFirst("h2 a, .entry-title a, .post-title a, a[rel='bookmark']") ?: continue
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            if (href.isBlank()) continue

            val rawTitle = a.text().trim()
            if (rawTitle.isBlank()) continue

            val img = item.selectFirst("img")
            val poster = img?.attr("abs:src")?.ifBlank { img.attr("src") }.orEmpty()

            val clean = NameSanitizer.cleanTitle(rawTitle)
            val dramaTags = listOf(region.label, era.label, if (rawTitle.contains("(Complete)")) "Completed" else "Ongoing")
            val card = ShowCard(
                title = clean.ifBlank { rawTitle },
                url = href,
                posterUrl = poster,
                site = "dramarain",
                category = if (region == DramaRegion.KDRAMA) "K-Drama" else "C-Drama",
                tags = dramaTags,
                genres = dramaTags
            )

            if (!filterExplicit || !ExplicitContentFilter.isExplicit(card)) {
                cards.add(card)
            }
            if (cards.size >= PER_SITE_LIMIT) break
        }
        return cards
    }

    private suspend fun fetchNepu(
        region: DramaRegion,
        era: DramaEra,
        filterExplicit: Boolean
    ): List<ShowCard> {
        val query = when {
            era == DramaEra.HISTORICAL && region == DramaRegion.KDRAMA -> "sageuk"
            era == DramaEra.HISTORICAL -> "wuxia"
            region == DramaRegion.KDRAMA -> "korean drama"
            else -> "chinese drama"
        }
        val raw = TrendingFeed.fetchApiSearch("nepu", query, PER_SITE_LIMIT)
        return if (filterExplicit) ExplicitContentFilter.filterSafe(raw) else raw
    }

    private suspend fun fetchNkiri(
        region: DramaRegion,
        era: DramaEra,
        page: Int,
        filterExplicit: Boolean
    ): List<ShowCard> {
        val query = when {
            era == DramaEra.HISTORICAL && region == DramaRegion.KDRAMA -> "sageuk"
            era == DramaEra.HISTORICAL -> "wuxia"
            region == DramaRegion.KDRAMA -> "korean drama"
            else -> "chinese drama"
        }
        return TrendingFeed.fetchWpRest("nkiri", query = query, page = page, limit = PER_SITE_LIMIT, filterExplicit = filterExplicit)
    }

    private suspend fun fetch9jaRocks(
        region: DramaRegion,
        era: DramaEra,
        page: Int,
        filterExplicit: Boolean
    ): List<ShowCard> {
        val term = if (region == DramaRegion.KDRAMA) "korean drama" else "chinese drama"
        val enc = java.net.URLEncoder.encode(term, "UTF-8")
        val path = if (page > 1) "/search/$enc/feed/rss2/?paged=$page" else "/search/$enc/feed/rss2/"
        return TrendingFeed.fetchRss("9jarocks", path, filterExplicit = filterExplicit)
    }
}
