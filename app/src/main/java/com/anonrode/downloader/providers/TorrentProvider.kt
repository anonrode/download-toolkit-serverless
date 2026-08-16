package com.anonrode.downloader.providers

import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.data.models.DownloadRecipe
import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.ShowDetails
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.security.TorrentSecurityShield
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

object TorrentProvider : SiteProvider {
    override val name: String = "torrents"
    override val mainUrl: String get() = DynamicRulesManager.getBaseUrl(name)

    override suspend fun search(query: String): List<ShowCard> = coroutineScope {
        val tpbDeferred = async { searchTpb(query) }
        val ytsDeferred = async { searchYts(query) }

        val tpbResults = tpbDeferred.await()
        val ytsResults = ytsDeferred.await()

        val combined = mutableListOf<ShowCard>()
        val seenHashes = mutableSetOf<String>()

        for (card in ytsResults) {
            val hash = extractHashFromMagnet(card.url)
            if (hash.isNotBlank() && seenHashes.add(hash)) {
                combined.add(card)
            }
        }

        for (card in tpbResults) {
            val hash = extractHashFromMagnet(card.url)
            if (hash.isNotBlank() && seenHashes.add(hash)) {
                combined.add(card)
            }
        }

        combined
    }

    private suspend fun searchTpb(query: String): List<ShowCard> {
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

                    val validation = TorrentSecurityShield.validateTorrent(
                        title = title,
                        infoHash = infoHash,
                        status = status,
                        seeders = seeders,
                        sizeBytes = sizeBytes,
                        userQuery = query
                    )
                    if (!validation.first) continue

                    val magnetResult = TorrentSecurityShield.buildSanitizedMagnet(infoHash, title)
                    val magnet = magnetResult.first ?: continue

                    val sizeMb = sizeBytes / (1024 * 1024)
                    val sizeStr = if (sizeMb >= 1024) String.format("%.1f GB", sizeMb / 1024.0) else "$sizeMb MB"
                    val trustBadge = when (status.lowercase()) {
                        "vip" -> "⭐ VIP"
                        "trusted" -> "🛡️ Trusted"
                        else -> "Torrents"
                    }

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
        } catch (_: Exception) {}
        return results
    }

    private suspend fun searchYts(query: String): List<ShowCard> {
        val results = mutableListOf<ShowCard>()
        val endpoints = listOf(
            "https://yts.mx/api/v2/list_movies.json",
            "https://yts.lt/api/v2/list_movies.json",
            "https://movies-api.accel.li/api/v2/list_movies.json"
        )

        for (baseUrl in endpoints) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = "$baseUrl?query_term=$encoded&limit=15"

                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", HttpClient.DEFAULT_UA)
                    .build()

                HttpClient.shared.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) return@use
                    val body = res.body?.string() ?: return@use
                    val json = JSONObject(body)
                    val movies = json.optJSONObject("data")?.optJSONArray("movies") ?: return@use

                    for (i in 0 until movies.length()) {
                        val m = movies.getJSONObject(i)
                        val title = m.optString("title_long").ifEmpty { m.optString("title", "Movie") }
                        val cover = m.optString("medium_cover_image").ifEmpty { m.optString("large_cover_image") }
                        val torrents = m.optJSONArray("torrents") ?: continue

                        for (j in 0 until torrents.length()) {
                            val t = torrents.getJSONObject(j)
                            val hash = t.optString("hash")
                            if (hash.isBlank()) continue

                            val q = t.optString("quality", "720p")
                            val codec = t.optString("video_codec", "x264")
                            val seeders = t.optInt("seeds", 1)
                            val sizeBytes = t.optLong("size_bytes", 0L)
                            val releaseName = "$title [$q] [$codec] [YTS]"

                            val validation = TorrentSecurityShield.validateTorrent(
                                title = releaseName,
                                infoHash = hash,
                                status = "trusted",
                                seeders = seeders,
                                sizeBytes = sizeBytes,
                                userQuery = query
                            )
                            if (!validation.first) continue

                            val magnetResult = TorrentSecurityShield.buildSanitizedMagnet(hash, releaseName)
                            val magnet = magnetResult.first ?: continue

                            val sizeMb = sizeBytes / (1024 * 1024)
                            val sizeStr = if (sizeMb >= 1024) String.format("%.1f GB", sizeMb / 1024.0) else "$sizeMb MB"

                            results.add(
                                ShowCard(
                                    title = releaseName,
                                    url = magnet,
                                    posterUrl = cover,
                                    site = name,
                                    category = "⭐ YTS • $sizeStr • 🟢 $seeders seeds"
                                )
                            )
                        }
                    }
                }
                if (results.isNotEmpty()) break
            } catch (_: Exception) {}
        }
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

    private fun extractTitleFromMagnet(magnetUrl: String): String {
        val match = Regex("""dn=([^&]+)""").find(magnetUrl)
        return match?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") } ?: "Torrent Download"
    }

    private fun extractHashFromMagnet(magnetUrl: String): String {
        val match = Regex("""xt=urn:btih:([a-zA-Z0-9]+)""").find(magnetUrl)
        return match?.groupValues?.get(1)?.lowercase() ?: ""
    }
}
