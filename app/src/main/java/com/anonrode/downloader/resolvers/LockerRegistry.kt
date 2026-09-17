package com.anonrode.downloader.resolvers

import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.pipeline.HostHealth
import com.anonrode.downloader.pipeline.PipelineJournal
import com.anonrode.downloader.pipeline.StreamValidator
import org.jsoup.Jsoup

/**
 * Centralized locker/media discovery and resolution.
 *
 * The old design: each provider maintained its own host list, and any host
 * it didn't know was silently dropped → "0 episodes" on shows using new
 * lockers (The Blood of Youth's 40 streamsss episodes, live-verified).
 *
 * This design: EXTRACT EVERYTHING, ARBITRATE BY EVIDENCE.
 * - Every plausible anchor from the episode page is collected (no host filter).
 * - classify() sorts by evidence: Direct (known extension), Locker (known
 *   host), Unknown (seen for the first time), None (nav junk).
 * - resolveCandidates() races known lockers via resolveAny, probes unknown
 *   hosts once via StreamValidator — if valid, it works on first contact.
 *   No code path can refuse an unfamiliar link.
 * - HostHealth records successes so unknown hosts derive their own reputation.
 * - The playbook seeds the initial host list (OTA, signed); the app learns
 *   beyond it.
 */
object LockerRegistry {


    sealed class MediaKind {
        /** Direct media file or known stream URL (unchanged since extraction). */
        object Direct : MediaKind()
        /** Known locker host that needs resolution (via ResolverRegistry). */
        data class Locker(val host: String) : MediaKind()
        /** Not a known locker but not nav junk — probe once to decide. */
        data class Unknown(val host: String) : MediaKind()
        /** Nav link, category page, or other junk — skip. */
        object None : MediaKind()
    }

    /** Single-pass classification: delegates to centralized StrictLinkClassifier shield. */
    fun classify(url: String): MediaKind {
        return when (val sc = com.anonrode.downloader.pipeline.StrictLinkClassifier.classify(url)) {
            is com.anonrode.downloader.pipeline.StrictLinkClassifier.LinkClass.DirectMedia -> MediaKind.Direct
            is com.anonrode.downloader.pipeline.StrictLinkClassifier.LinkClass.KnownLocker -> MediaKind.Locker(sc.host)
            is com.anonrode.downloader.pipeline.StrictLinkClassifier.LinkClass.IntermediateGateway -> {
                val h = try { java.net.URI(url).host?.lowercase() ?: "" } catch (_: Exception) { "" }
                MediaKind.Unknown(h)
            }
            is com.anonrode.downloader.pipeline.StrictLinkClassifier.LinkClass.Unknown -> MediaKind.Unknown(sc.host)
            is com.anonrode.downloader.pipeline.StrictLinkClassifier.LinkClass.NavigationJunk -> MediaKind.None
        }
    }

    /** True when [url] is a direct media file or a KNOWN locker (playbook-
     *  seeded or learned via HostHealth). Unknown hosts are excluded — they
     *  belong in findLockerLinksInHtml/resolveCandidates, not in curated
     *  episode lists. */
    fun isKnownMedia(url: String): Boolean = when (classify(url)) {
        is MediaKind.Direct, is MediaKind.Locker -> true
        else -> false
    }

    /**
     * Extract all plausible locker/media URLs from raw HTML.
     * Jsoup-first (href, data-video, data-src), regex fallback for obfuscated pages.
     * Nav-junk filtered: requires path depth >= 3 OR host is a known locker.
     */
    fun findLockerLinksInHtml(html: String): List<String> {
        val found = mutableSetOf<String>()
        try {
            val doc = Jsoup.parse(html)
            for (a in doc.select("a[href], a[data-video], a[data-src], iframe[src], video[src], source[src]")) {
                val u = a.attr("abs:href").ifBlank { a.attr("href").ifBlank { a.attr("data-video").ifBlank { a.attr("data-src") } } }
                if (u.isNotBlank() && classify(u) != MediaKind.None) found.add(u)
            }
        } catch (_: Exception) {}
        // Regex fallback for obfuscated/scripted DOMs
        if (found.isEmpty()) {
            val re = Regex("""https?://[^\s"'<>]+(?:\.(?:mp4|mkv|webm|avi|m3u8)|/(?:dl-|download/|embed/|e/|d/))[^\s"'<>]*""", RegexOption.IGNORE_CASE)
            for (m in re.findAll(html)) {
                val u = m.value.trimEnd('.', ',', ')', ']')
                if (classify(u) != MediaKind.None) found.add(u)
            }
        }
        return found.toList()
    }

    /**
     * Resolve a set of candidates to a single stream URL.
     * - Direct media → pass through.
     * - Known lockers → race via ResolverRegistry.resolveAny.
     * - Unknown hosts → probe once via StreamValidator; if valid, it works
     *   on first contact. HostHealth records the outcome.
     */
    suspend fun resolveCandidates(urls: List<String>, quality: String = "720p"): String? {
        val direct = urls.firstOrNull { classify(it) is MediaKind.Direct }
        if (direct != null) return direct

        val lockers = urls.filter { classify(it) is MediaKind.Locker }
        if (lockers.isNotEmpty()) {
            val raced = ResolverRegistry.resolveAny(lockers, quality, maxConcurrency = 3)
            if (raced != null) return raced
        }

        val unknowns = urls.filter { classify(it) is MediaKind.Unknown }
        if (unknowns.isEmpty()) return null

        // Probe each unknown host once (paced via HostHealth, validated via
        // StreamValidator). First success wins.
        for (u in unknowns) {
            val host = try { java.net.URI(u).host ?: "" } catch (_: Exception) { "" }
            if (host.isBlank()) continue
            if (!HostHealth.isUsable(u)) {
                PipelineJournal.hop("", "locker-unknown", u, ok = false, ms = 0,
                    detail = "host backoff — skipping")
                continue
            }
            val start = System.currentTimeMillis()
            val reason = StreamValidator.validate(u, emptyMap())
            if (reason == null) {
                // Valid stream found on first contact — record success.
                HostHealth.recordOk(host)
                PipelineJournal.hop("", "locker-unknown", u, ok = true,
                    ms = System.currentTimeMillis() - start,
                    detail = "unknown host validated on first contact")
                return u
            }
            HostHealth.recordFail(host)
            PipelineJournal.hop("", "locker-unknown", u, ok = false,
                ms = System.currentTimeMillis() - start,
                detail = "probe rejected: $reason")
        }
        return null
    }
}