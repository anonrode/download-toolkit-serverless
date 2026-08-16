package com.anonrode.downloader.providers

import com.anonrode.downloader.data.rules.DynamicRulesManager

import com.anonrode.downloader.data.models.DownloadRecipe
import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.ShowDetails
import com.anonrode.downloader.data.net.HttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder

object TorrentProvider : SiteProvider {
    override val name: String = "torrents"
    override val mainUrl: String get() = DynamicRulesManager.getBaseUrl(name)

    private val TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://tracker.bittor.pw:1337/announce",
        "udp://public.popcorn-tracker.org:6969/announce",
        "udp://tracker.dler.org:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://open.demonii.com:1337/announce"
    )

    override suspend fun search(query: String): List<ShowCard> {
        val results = mutableListOf<ShowCard>()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "$mainUrl/q.php?q=$encoded&cat=0"

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", HttpClient.DEFAULT_UA)
                .header("Referer", "https://thepiratebay.org/")
                .header("Origin", "https://thepiratebay.org")
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .build()

            HttpClient.shared.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return emptyList()
                val body = res.body?.string() ?: return emptyList()
                val array = JSONArray(body)

                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val id = item.optString("id")
                    if (id == "0") continue

                    val title = item.optString("name")
                    val infoHash = item.optString("info_hash")
                    val seeders = item.optInt("seeders", 0)
                    val leechers = item.optInt("leechers", 0)
                    val sizeBytes = item.optLong("size", 0L)
                    val status = item.optString("status") // vip, trusted, member

                    if (infoHash.isNotBlank() && title.isNotBlank()) {
                        val sizeMb = sizeBytes / (1024 * 1024)
                        val sizeStr = if (sizeMb >= 1024) String.format("%.1f GB", sizeMb / 1024.0) else "$sizeMb MB"
                        val trustBadge = when (status.lowercase()) {
                            "vip" -> "⭐ VIP"
                            "trusted" -> "🛡️ Trusted"
                            else -> "Torrents"
                        }

                        val magnet = buildMagnet(infoHash, title)

                        results.add(
                            ShowCard(
                                title = title,
                                url = magnet,
                                posterUrl = "",
                                site = name,
                                category = "$trustBadge • $sizeStr • 🟢 $seeders seeders"
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}
        return results
    }

    override suspend fun loadEpisodes(showUrl: String): ShowDetails {
        val title = extractTitleFromMagnet(showUrl)
        val show = ShowCard(title = title, url = showUrl, site = name, category = "Torrent")
        val episodes = listOf(
            EpisodeItem(
                title = title,
                url = showUrl,
                episodeNum = 1,
                site = name
            )
        )
        return ShowDetails(show = show, episodes = episodes)
    }

    override suspend fun resolveEpisode(episodeUrl: String, quality: String): DownloadRecipe {
        val title = extractTitleFromMagnet(episodeUrl)
        return DownloadRecipe(
            directUrl = episodeUrl,
            filename = "$title.torrent",
            backend = "aria2c",
            parallelSockets = 16
        )
    }

    private fun buildMagnet(infoHash: String, title: String): String {
        val enc = URLEncoder.encode(title, "UTF-8")
        val tr = TRACKERS.joinToString("") { "&tr=" + URLEncoder.encode(it, "UTF-8") }
        return "magnet:?xt=urn:btih:$infoHash&dn=$enc$tr"
    }

    private fun extractTitleFromMagnet(magnetUrl: String): String {
        val match = Regex("""dn=([^&]+)""").find(magnetUrl)
        return match?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: "Torrent Download"
    }
}
