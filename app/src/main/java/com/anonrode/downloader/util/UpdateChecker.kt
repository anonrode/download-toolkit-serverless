package com.anonrode.downloader.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.anonrode.downloader.BuildConfig
import com.anonrode.downloader.data.net.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed class UpdateCheckResult {
    data class Available(val latestTag: String, val releaseUrl: String) : UpdateCheckResult()
    data class UpToDate(val latestTag: String) : UpdateCheckResult()
    // NOTE: must stay an `object`. SettingsSheet matches `UpdateCheckResult.Error`
    // as an equality branch — a data class here would not compile there.
    object Error : UpdateCheckResult()
}

/**
 * Manual "Check for updates" against GitHub Releases — with an OTA mirror
 * fallback (Phase 0 delivery chain). Order:
 *
 *  1. https://api.github.com/repos/anonrode/download-toolkit-serverless/releases/latest
 *     (canonical; public repo, no token needed)
 *  2. http://68.155.146.145:8000/ota/latest.json — the same box that mirrors
 *     the OTA rules payload (scripts/ota_mirror), serving
 *     {"version": "...", "assets": [...], "html_url": "..."} refreshed from
 *     the GitHub API. Raw-IP answer: needs no DNS, so it still works when
 *     carrier DNS poisons or blocks every GitHub host (the observed v3.2.9
 *     failure shape, log share-31).
 *  3. Cached in-memory answer from a previous successful check in this
 *     process (better than nothing; the only honest third step without a
 *     Context at this layer).
 *
 * The repo is public and every v* tag ships the APKs as release assets, so no
 * token is needed anywhere in the chain. Only runs when the user taps the
 * check — never at startup (no surprise data use, no rate-limit pressure).
 *
 * Diagnostics: every attempt is journaled with its failing STAGE via
 * OtaFetchDiagnostics; a total failure posts one encrypted aggregate event to
 * the client-error telemetry sink. [lastCheckDetail] carries the human-readable
 * stage summary for anything that wants it (journal/debug builds).
 */
object UpdateChecker {

    private const val REPO = "anonrode/download-toolkit-serverless"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val MIRROR_LATEST_URL = "http://68.155.146.145:8000/ota/latest.json"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases"

    /** Stage summary of the last check ("" when the last check succeeded). */
    @Volatile
    var lastCheckDetail: String = ""
        private set

    /** Last known-good (tag, page URL) from any successful source, for the
     *  cached fallback. Per-process only — never worth the storage surface. */
    @Volatile private var cachedTag: String? = null
    @Volatile private var cachedUrl: String? = null

    suspend fun check(): UpdateCheckResult = withContext(Dispatchers.IO) {
        val totalStarted = System.currentTimeMillis()
        val attempts = mutableListOf<OtaFetchDiagnostics.Attempt>()

        fun tryFetchJson(label: String, host: String, rawUrl: String): JSONObject? {
            val url = "$rawUrl?t=${System.currentTimeMillis()}"
            val startedAt = System.currentTimeMillis()
            var failure: Throwable? = null
            val body = try {
                HttpClient.getText(
                    url,
                    headers = mapOf("Accept" to "application/vnd.github+json"),
                    tag = "update_check",
                    onFailure = { _, e -> failure = e }
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                failure = t
                null
            }
            val elapsed = System.currentTimeMillis() - startedAt
            if (body.isNullOrBlank()) {
                val e = failure
                val stage = if (e != null) OtaFetchDiagnostics.classify(e) else OtaFetchDiagnostics.STAGE_UNKNOWN
                val code = e?.let { OtaFetchDiagnostics.httpCodeOf(it) }
                attempts.add(
                    OtaFetchDiagnostics.Attempt(
                        source = label, url = url, stage = stage,
                        errorClass = e?.javaClass?.simpleName ?: "null-body",
                        detail = e?.message ?: "empty response body",
                        elapsedMs = elapsed, httpCode = code
                    )
                )
                OtaFetchDiagnostics.noteAttempt(
                    label, url, stage,
                    e?.javaClass?.simpleName ?: "null-body",
                    e?.message ?: "empty response body", elapsed, code
                )
                return null
            }
            return try {
                JSONObject(body)
            } catch (e: Exception) {
                attempts.add(
                    OtaFetchDiagnostics.Attempt(
                        source = label, url = url, stage = OtaFetchDiagnostics.STAGE_PARSE,
                        errorClass = e.javaClass.simpleName,
                        detail = "response was not JSON", elapsedMs = elapsed
                    )
                )
                OtaFetchDiagnostics.noteAttempt(label, url, OtaFetchDiagnostics.STAGE_PARSE, e.javaClass.simpleName, "response was not JSON", elapsed)
                null
            }
        }

        // ---- source 1: canonical GitHub Releases API ------------------------
        run {
            val obj = tryFetchJson("api.github.com", "api.github.com", API_URL)
            if (obj != null) {
                val tag = obj.optString("tag_name")
                val url = obj.optString("html_url")
                if (tag.isNotBlank() && url.isNotBlank()) {
                    cachedTag = tag
                    cachedUrl = url
                    lastCheckDetail = ""
                    OtaFetchDiagnostics.reportSuccess(
                        "update-check", "api.github.com", tag,
                        System.currentTimeMillis() - totalStarted, 1
                    )
                    return@withContext decide(BuildConfig.VERSION_NAME, tag, url)
                }
                attempts.add(
                    OtaFetchDiagnostics.Attempt(
                        source = "api.github.com", url = API_URL,
                        stage = OtaFetchDiagnostics.STAGE_PARSE,
                        errorClass = "ShapeMismatch", detail = "tag_name/html_url missing",
                        elapsedMs = -1L
                    )
                )
            }
        }

        // ---- source 2: our OTA mirror (raw IP, no DNS needed) ---------------
        run {
            val obj = tryFetchJson("self-box", "68.155.146.145", MIRROR_LATEST_URL)
            if (obj != null) {
                val tag = obj.optString("version")
                if (tag.isNotBlank()) {
                    val url = obj.optString("html_url").ifBlank {
                        "https://github.com/$REPO/releases/tag/$tag"
                    }
                    cachedTag = tag
                    cachedUrl = url
                    lastCheckDetail = ""
                    OtaFetchDiagnostics.reportSuccess(
                        "update-check", "self-box", tag,
                        System.currentTimeMillis() - totalStarted,
                        attempts.size + 1
                    )
                    return@withContext decide(BuildConfig.VERSION_NAME, tag, url)
                }
                attempts.add(
                    OtaFetchDiagnostics.Attempt(
                        source = "self-box", url = MIRROR_LATEST_URL,
                        stage = OtaFetchDiagnostics.STAGE_PARSE,
                        errorClass = "ShapeMismatch", detail = "version missing in latest.json",
                        elapsedMs = -1L
                    )
                )
            }
        }

        // ---- source 3: cached in-memory answer ------------------------------
        val cTag = cachedTag
        val cUrl = cachedUrl
        if (cTag != null && cUrl != null) {
            lastCheckDetail = "Could not reach GitHub — showing cached answer (v$cTag)"
            OtaFetchDiagnostics.noteAttempt(
                "cached", "-", OtaFetchDiagnostics.STAGE_UNKNOWN,
                "CacheFallback", "using cached tag v$cTag after ${attempts.size} failed source(s)", -1L
            )
            return@withContext decide(BuildConfig.VERSION_NAME, cTag, cUrl)
        }

        // ---- total failure --------------------------------------------------
        lastCheckDetail = "Could not reach GitHub (${OtaFetchDiagnostics.friendlyStage(
            attempts.lastOrNull()?.stage ?: OtaFetchDiagnostics.STAGE_UNKNOWN,
            attempts.lastOrNull()?.httpCode
        )}) after ${attempts.size} source(s)"
        OtaFetchDiagnostics.reportTotalFailure(
            kind = "GitHub releases API",
            attempts = attempts,
            totalMs = System.currentTimeMillis() - totalStarted,
            dnsEvidenceHost = "api.github.com"
        )
        UpdateCheckResult.Error
    }

    /** Version comparison + verdict, shared by every source's answer. */
    private fun decide(current: String, latestTag: String, releaseUrl: String): UpdateCheckResult {
        return if (versionCompare(current, latestTag) < 0) {
            UpdateCheckResult.Available(latestTag, releaseUrl)
        } else {
            UpdateCheckResult.UpToDate(latestTag)
        }
    }

    fun openInBrowser(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (t: Throwable) {
            DebugLog.write("open browser failed: $url (${t.message})")
        }
    }

    /** Compares dotted version strings ("3.0.4" vs "v3.0.5"): negative when
     *  current is older, 0 when equal, positive when newer. Non-numeric
     *  segments (e.g. "-beta" suffixes) are ignored. */
    private fun versionCompare(current: String, latest: String): Int {
        val a = current.trimStart('v').split('.').mapNotNull { it.toIntOrNull() }
        val b = latest.trimStart('v').split('.').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }
}
