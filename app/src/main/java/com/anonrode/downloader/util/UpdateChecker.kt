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
    object Error : UpdateCheckResult()
}

/**
 * Manual "Check for updates" against the GitHub Releases API. The repo is
 * public and every v* tag ships the APKs as release assets, so no token or
 * server is needed: GET releases/latest, compare its tag to
 * BuildConfig.VERSION_NAME, and hand back the release page URL when a newer
 * build exists. Only runs when the user taps the check — never at startup
 * (no surprise data use, no rate-limit pressure).
 */
object UpdateChecker {

    private const val REPO = "anonrode/download-toolkit-serverless"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases"

    suspend fun check(): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val body = HttpClient.getText(API_URL, headers = mapOf("Accept" to "application/vnd.github+json"))
            if (body.isNullOrBlank()) return@withContext UpdateCheckResult.Error
            val obj = JSONObject(body)
            val tag = obj.optString("tag_name")
            val url = obj.optString("html_url")
            if (tag.isBlank() || url.isBlank()) return@withContext UpdateCheckResult.Error
            if (versionCompare(BuildConfig.VERSION_NAME, tag) < 0) {
                UpdateCheckResult.Available(tag, url)
            } else {
                UpdateCheckResult.UpToDate(tag)
            }
        } catch (t: Throwable) {
            UpdateCheckResult.Error
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
