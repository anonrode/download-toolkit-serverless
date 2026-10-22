package com.anonrode.downloader.util

import com.anonrode.downloader.data.models.DownloadTask

/**
 * Shared show-identity and episode-ordering rules.
 *
 * Lives in `util` so pure data layers (watch history) can group episodes
 * without importing the UI package. [com.anonrode.downloader.ui.components.DownloadsSorter]
 * delegates here; the regexes and comparator below are the live contract,
 * pinned by DownloadsSorterTest — extraction must not change behavior.
 */
object ShowIdentity {

    /**
     * Normalizes a show title into the grouping key shared by the Downloads
     * library, the player queue and watch history. Site watermarks, release
     * noise and trailing season markers are stripped so every season of one
     * show lands in a single group.
     */
    fun normalizeShowKey(raw: String): String {
        var s = raw.trim()
        if (s.isBlank()) return "Unknown"
        // Strip site watermarks in brackets or parens: [9jaRocks.Com], [NaijaPrey], (NetNaija), etc.
        s = s.replace(Regex("""\[[^\]]*\]"""), "")
        s = s.replace(Regex("""\([^\)]*(?:rocks|prey|naija|netnaija|nkiri)[^\)]*\)""", RegexOption.IGNORE_CASE), "")
        // Strip release/codec/resolution noise: 540p, 720p, 1080p, x265, x264, WEBRip, etc.
        s = s.replace(Regex("""\b(?:\d{3,4}p|x26[45]|hevc|h26[45]|aac|web-?rip|dvd-?rip|bluray|hdrip)\b""", RegexOption.IGNORE_CASE), "")
        // Strip trailing season markers so all seasons of the same show group into one show card:
        // "The Pitt S02" -> "The Pitt", "President Curtis - S01" -> "President Curtis"
        s = s.replace(Regex("""[-–—._\s]+S\d{1,2}(?:E\d{1,3})?.*""", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("""[-–—._\s]+Season\s*\d{1,2}.*""", RegexOption.IGNORE_CASE), "")
        // Clean trailing separators
        s = s.replace(Regex("""[._\-–—\s]+$"""), "").trim()
        return if (s.isBlank()) raw.trim() else s
    }

    /**
     * Canonical episode order: numbered episodes ascending, un-numbered
     * tasks (movies, direct links, epNum 0) after them; stable by enqueue
     * time for equal keys. Same semantics as the historical
     * DownloadsSorter.EPISODE_ORDER.
     */
    val episodeOrder: Comparator<DownloadTask> =
        compareBy<DownloadTask> {
            if (it.episodeNum > 0 || it.season > 0 || it.episode > 0) {
                SeasonEpisode.sortKey(SeasonEpisode.seasonOf(it), SeasonEpisode.episodeOf(it))
            } else {
                Int.MAX_VALUE
            }
        }.thenBy { it.createdAt }
}
