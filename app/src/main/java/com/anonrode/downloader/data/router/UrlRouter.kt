package com.anonrode.downloader.data.router

import com.anonrode.downloader.data.models.ShowCard
import java.net.URLDecoder

sealed class ParsedUrl {
    data class DramaUrl(val site: String, val showCard: ShowCard) : ParsedUrl()
    data class SocialUrl(val platform: String, val cleanUrl: String) : ParsedUrl()
    data class DirectMediaUrl(val url: String, val filename: String) : ParsedUrl()
    data class MagnetUrl(val magnet: String, val title: String) : ParsedUrl()
    data class SearchQuery(val query: String) : ParsedUrl()
}

object UrlRouter {

    fun parse(input: String): ParsedUrl {
        val trimmed = input.trim()
        val lower = trimmed.lowercase()

        // 0. Magnet links
        if (lower.startsWith("magnet:")) {
            val titleMatch = Regex("""dn=([^&]+)""").find(trimmed)
            val title = titleMatch?.groupValues?.get(1)?.let {
                try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { "Torrent Download" }
            } ?: "Torrent Download"
            return ParsedUrl.MagnetUrl(trimmed, title)
        }

        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            return ParsedUrl.SearchQuery(trimmed)
        }

        // 1. Direct Media files
        val cleanUrl = trimmed.substringBefore('?')
        val ext = cleanUrl.substringAfterLast('.', "")
        if (ext in listOf("mp4", "mkv", "avi", "mov", "webm", "m3u8", "ts", "torrent")) {
            val filename = cleanUrl.substringAfterLast('/').ifEmpty { "download.$ext" }
            return ParsedUrl.DirectMediaUrl(trimmed, filename)
        }

        // 2. Providers
        if (lower.contains("thenkiri.com") || lower.contains("nkiri")) {
            return ParsedUrl.DramaUrl(
                site = "nkiri",
                showCard = ShowCard(title = slugToTitle(extractSlug(trimmed)), url = trimmed, site = "nkiri", category = "Asian Drama")
            )
        }

        if (lower.contains("myasiantv") || lower.contains("asianc.") || lower.contains("dramacool")) {
            return ParsedUrl.DramaUrl(
                site = "asianc",
                showCard = ShowCard(title = slugToTitle(extractSlug(trimmed)), url = trimmed, site = "asianc", category = "Asian Drama")
            )
        }

        if (lower.contains("dramakey")) {
            return ParsedUrl.DramaUrl(
                site = "dramakey",
                showCard = ShowCard(title = slugToTitle(extractSlug(trimmed)), url = trimmed, site = "dramakey", category = "Asian Drama")
            )
        }

        if (lower.contains("dramarain.com")) {
            return ParsedUrl.DramaUrl(
                site = "dramarain",
                showCard = ShowCard(title = slugToTitle(extractSlug(trimmed)), url = trimmed, site = "dramarain", category = "Asian Drama")
            )
        }

        if (lower.contains("plutomovies.com")) {
            return ParsedUrl.DramaUrl(
                site = "pluto",
                showCard = ShowCard(title = slugToTitle(extractSlug(trimmed)), url = trimmed, site = "pluto", category = "Movies & Series")
            )
        }

        if (lower.contains("anitaku.") || lower.contains("gogoanime.")) {
            return ParsedUrl.DramaUrl(
                site = "anitaku",
                showCard = ShowCard(title = slugToTitle(extractSlug(trimmed)), url = trimmed, site = "anitaku", category = "Anime")
            )
        }

        if (lower.contains("9jarocks.") || lower.contains("my9jarocks.")) {
            return ParsedUrl.DramaUrl(
                site = "9jarocks",
                showCard = ShowCard(title = slugToTitle(extractSlug(trimmed)), url = trimmed, site = "9jarocks", category = "Nollywood & Movies")
            )
        }

        if (lower.contains("naijavault.com")) {
            return ParsedUrl.DramaUrl(
                site = "naijavault",
                showCard = ShowCard(title = slugToTitle(extractSlug(trimmed)), url = trimmed, site = "naijavault", category = "Nollywood & Movies")
            )
        }

        if (lower.contains("naijaprey.")) {
            return ParsedUrl.DramaUrl(
                site = "naijaprey",
                showCard = ShowCard(title = slugToTitle(extractSlug(trimmed)), url = trimmed, site = "naijaprey", category = "Nollywood & Series")
            )
        }

        if (lower.contains("nepu.gd") || lower.contains("nepu.to")) {
            return ParsedUrl.DramaUrl(
                site = "nepu",
                showCard = ShowCard(title = slugToTitle(extractSlug(trimmed)), url = trimmed, site = "nepu", category = "Movies")
            )
        }

        // 3. Social Media platforms
        val platform = when {
            lower.contains("instagram.com") -> "Instagram"
            lower.contains("tiktok.com") -> "TikTok"
            lower.contains("twitter.com") || lower.contains("x.com") -> "Twitter"
            lower.contains("facebook.com") || lower.contains("fb.watch") -> "Facebook"
            lower.contains("youtube.com") || lower.contains("youtu.be") -> "YouTube"
            lower.contains("pinterest.com") || lower.contains("pin.it") -> "Pinterest"
            lower.contains("reddit.com") || lower.contains("redd.it") -> "Reddit"
            else -> "Web"
        }

        return ParsedUrl.SocialUrl(platform, trimmed)
    }

    private fun extractSlug(url: String): String {
        return url.trimEnd('/').substringAfterLast('/')
    }

    private fun slugToTitle(slug: String): String {
        val clean = slug.replace(Regex("""[-_]+"""), " ").trim()
        return clean.split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
}
