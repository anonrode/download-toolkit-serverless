package com.anonrode.downloader.pipeline

import com.anonrode.downloader.util.DebugLog
import java.security.MessageDigest

/**
 * Typed resolution-pipeline failures.
 *
 * Root-cause rule from the project's own history: nearly every production bug
 * was a SILENT fallback (null instead of an explanation, "0 links" instead of
 * "regex matched the wrong host", full download instead of "all files
 * blocked"). Every stage therefore fails LOUDLY with one of these, carrying
 * what a shared activity log needs to diagnose it without device access.
 */
sealed class PipelineError(message: String) : Exception(message) {

    /** Site root unreachable after retries (DNS/connection dead). */
    class SiteDown(site: String, detail: String = "") :
        PipelineError("Site down: $site${if (detail.isNotBlank()) " ($detail)" else ""}")

    /** Specific host answered but is unusable (dead locker, empty shell). */
    class HostDead(host: String, detail: String = "") :
        PipelineError("Host dead: $host${if (detail.isNotBlank()) " ($detail)" else ""}")

    /** 429 / explicit rate limiting — back off, do not hammer. */
    class RateLimited(host: String) :
        PipelineError("Rate limited by $host — backing off")

    /** Pattern-match on an IP range block (nkiri/carrier lesson). */
    class BlockedIp(host: String) :
        PipelineError("Access blocked for $host — this network/IP is refused by the site")

    /** Locker token expired before use — JIT re-resolve fixes this. */
    class TokenExpired(url: String) :
        PipelineError("Stream token expired for ${url.take(80)}")

    /** Stage parsed a page but extracted nothing — carries a page fingerprint
     *  so the log shows WHAT was parsed when selectors decay. */
    class ParseEmpty(stage: String, pageHash: String, detail: String = "") :
        PipelineError(
            "Nothing extracted at stage '$stage' (page fingerprint $pageHash)" +
                if (detail.isNotBlank()) ": $detail" else ""
        )

    /** Pre-enqueue validation rejected the URL (HTML decoy, wrong type...). */
    class ValidationFailed(detail: String) : PipelineError("Validation failed: $detail")

    /** Total resolution budget exhausted (chain too deep / too slow). */
    class BudgetExceeded(stage: String, elapsedMs: Long) :
        PipelineError("Resolution budget exceeded at '$stage' after ${elapsedMs}ms")

    companion object {
        /** Best-effort classification of HttpClient.lastFailure strings into
         *  typed errors, so call sites can `throw classify(host, lastFailure)`. */
        fun classify(host: String, lastFailure: String?): PipelineError {
            val f = lastFailure ?: return HostDead(host)
            return when {
                f.contains("429") -> RateLimited(host)
                f.startsWith("HTTP 403") || f.startsWith("HTTP 401") -> BlockedIp(host)
                f.startsWith("HTTP ") -> HostDead(host, f.take(60))
                else -> SiteDown(host, f.take(60))
            }
        }
    }
}

/**
 * Structured per-hop journal. One line per pipeline hop with outcome,
 * duration and an optional page fingerprint — the black box that makes
 * "why did this episode fail" answerable from a user-shared activity log.
 */
object PipelineJournal {

    /** Stable short fingerprint of a fetched body (first 8 hex of MD5). */
    fun pageHash(body: String): String = try {
        val md = MessageDigest.getInstance("MD5").digest(body.toByteArray(Charsets.UTF_8))
        md.joinToString("") { "%02x".format(it) }.take(8)
    } catch (_: Exception) {
        "????????"
    }

    fun hop(site: String, stage: String, url: String, ok: Boolean, ms: Long, detail: String = "", hash: String = "") {
        val line = buildString {
            append("[hop] site=").append(site.ifBlank { "?" })
            append(" stage=").append(stage)
            append(" url=").append(url.take(90))
            append(" result=").append(if (ok) "OK" else "ERR")
            append(" ms=").append(ms)
            if (hash.isNotBlank()) append(" hash=").append(hash)
            if (detail.isNotBlank()) append(" ").append(detail.take(160))
        }
        if (ok) DebugLog.resolve(line) else DebugLog.error(line)
    }

    inline fun <T> timed(site: String, stage: String, url: String, block: () -> T?): T? {
        val start = System.currentTimeMillis()
        val result = try { block() } catch (e: Exception) {
            hop(site, stage, url, ok = false, ms = System.currentTimeMillis() - start, detail = e.message ?: "")
            throw e
        }
        hop(site, stage, url, ok = result != null, ms = System.currentTimeMillis() - start)
        return result
    }
}
