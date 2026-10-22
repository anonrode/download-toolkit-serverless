package com.anonrode.downloader.util

import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.EpisodeItem

/**
 * Season/episode identity is explicit.
 *
 * [EpisodeItem.season] / [EpisodeItem.episode] (and the matching DownloadTask
 * fields) are authoritative. [episodeNum] is kept only as a legacy ordering /
 * identity key and is NEVER interpreted as `season * 100 + episode` for
 * flat-numbered providers.
 *
 * Regression this guards (2026-10-20): 9jaRocks publishes 150-episode seasons
 * ("The General's Daughter"). Under the old 100-based convention S01E101
 * became 201, which every decoder read as S02E01 — moving half a season into
 * a fictional season 2. Flat numbering must therefore stay flat.
 */
object SeasonEpisode {

    /**
     * Providers whose historical [EpisodeItem.episodeNum] really was a packed
     * `season * 100 + episode` value. 9jaRocks is deliberately absent: its
     * numbering is flat (1..N) even when N exceeds 99.
     */
    private val LEGACY_PACKED_SITES = setOf(
        "dramakey",
        "dramarain",
        "nepu",
        "nkiri",
        "pluto",
        "plutomovies",
        "naijaprey",
        "naijavault"
    )

    /** Resolve the authoritative (season, episode) pair for an item/task. */
    fun of(site: String, season: Int, episode: Int, episodeNum: Int): Pair<Int, Int> {
        if (season > 0 && episode > 0) return season to episode
        if (season > 0) return season to legacy(site, episodeNum).second.coerceAtLeast(1)
        if (episode > 0) return 1 to episode
        return legacy(site, episodeNum)
    }

    fun of(ep: EpisodeItem): Pair<Int, Int> =
        of(ep.site, ep.season, ep.episode, ep.episodeNum)

    fun of(task: DownloadTask): Pair<Int, Int> =
        of(task.site, task.season, task.episode, task.episodeNum)

    fun seasonOf(ep: EpisodeItem): Int = of(ep).first
    fun episodeOf(ep: EpisodeItem): Int = of(ep).second
    fun seasonOf(task: DownloadTask): Int = of(task).first
    fun episodeOf(task: DownloadTask): Int = of(task).second

    /**
     * Monotonic ordering key for explicit fields. Never persisted and never
     * decoded back into a season/episode pair; it only sorts 999 episodes per
     * season correctly without colliding with another season.
     */
    fun sortKey(season: Int, episode: Int): Int =
        season.coerceAtLeast(1) * 1000 + episode.coerceAtLeast(0)

    private fun legacy(site: String, episodeNum: Int): Pair<Int, Int> {
        if (episodeNum <= 0) return 1 to 1
        // New parses encode uniquely as season*1000 + episode (supports up to
        // 999 episodes per season). Values >= 1000 are unambiguous.
        if (episodeNum >= 1000) {
            val s = episodeNum / 1000
            val e = episodeNum % 1000
            if (s in 1..200 && e >= 1) return s to e
        }
        // Blank/unknown site = a task persisted before provider ids were
        // stored: keep the historical 100-base decode (810 -> S08E10).
        // Explicit providers below are the only ones allowed to unpack.
        if (episodeNum >= 100 && (isUnscopedSite(site) || site.lowercase() in LEGACY_PACKED_SITES)) {
            val s = episodeNum / 100
            val e = episodeNum % 100
            if (s in 1..99 && e in 1..99) return s to e
        }
        return 1 to episodeNum
    }

    /** No provider id means a legacy/persisted task (site was added later). */
    private fun isUnscopedSite(site: String): Boolean =
        site.isBlank() || site.trim().equals("unknown", ignoreCase = true)

    /**
     * Collision-free episodeNum for NEW parses. Explicit [season]/[episode]
     * remain authoritative; this value exists so lookups (`firstOrNull {
     * episodeNum == … }`), ranges and legacy consumers cannot confuse S1E101
     * with S2E1.
     */
    fun encode(season: Int, episode: Int): Int =
        season.coerceAtLeast(1) * 1000 + episode.coerceAtLeast(1)
}
