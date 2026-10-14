package com.anonrode.downloader.util

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL

/**
 * Single home for yt-dlp core-engine update policy (user directive, 2026-10:
 * "the sync button must work, and the nightly channel where YouTube fixes
 * actually land first must be available").
 *
 * Channel reality (checked against upstream on 2026-10-14): the latest
 * yt-dlp *release* was 2026.08.19 — two months old — while the bot-check
 * work ("Player client maintenance", visionos/web_embedded client fallbacks)
 * lands continuously on the master channel and only ships to a release
 * roughly every couple of months. A STABLE-only install therefore runs
 * months behind YouTube's rotating bot checks. Default here is MASTER
 * (nightly); STABLE remains one tap away and is the automatic fallback
 * whenever the chosen channel's payload errors out.
 *
 * The channel enum constant is resolved by NAME at runtime (valueOf wrapped
 * in runCatching): this compiles and degrades to STABLE against any library
 * version whose enum omits MASTER, instead of failing the build.
 */
object YtDlpCore {

    const val PREF_CHANNEL = "pref_ytdlp_channel"
    private const val PREFS_NAME = "downloader_settings"
    private const val MASTER = "master"
    private const val STABLE = "stable"

    fun savedChannelName(context: Context): String {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(PREF_CHANNEL, MASTER)?.lowercase()?.takeIf { it == STABLE || it == MASTER } ?: MASTER
        } catch (_: Exception) {
            MASTER
        }
    }

    fun setChannel(context: Context, name: String) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(PREF_CHANNEL, name.lowercase()).apply()
        } catch (_: Exception) {}
    }

    /** Parses "master"/"stable" into the library enum WITHOUT naming the
     *  constants — a library whose enum lacks MASTER throws here and the
     *  caller silently gets STABLE instead of a compile error. */
    private fun channel(name: String): YoutubeDL.UpdateChannel {
        return try {
            YoutubeDL.UpdateChannel.valueOf(name.uppercase())
        } catch (_: Exception) {
            YoutubeDL.UpdateChannel.STABLE
        }
    }

    private fun channelLabel(name: String): String = when (name) {
        MASTER -> "Master (nightly)"
        else -> "Stable (release)"
    }

    data class Outcome(
        val ok: Boolean,
        val message: String,
        val version: String?,
        val channelUsed: String
    )

    private suspend fun attempt(context: Context, name: String): Triple<Boolean, String> {
        val status = YoutubeDL.getInstance().updateYoutubeDL(context, channel(name))
        val statusName = status?.name ?: "UNDEFINED"
        return when (statusName) {
            "ERROR" -> Triple(false, "yt-dlp reported an error on the $name channel")
            else -> Triple(true, "success ($statusName)")
        }
    }

    /** Installed extractor version (null when the core has not initialized yet). */
    suspend fun currentVersion(context: Context): String? {
        return try { YoutubeDL.getInstance().version(context) } catch (_: Exception) { null }
    }

    /**
     * Forces a core update on the saved channel; on an ERROR result or a
     * thrown update, retries once on the other channel so the button can
     * never end with "nothing happened". Returns the post-update version.
     */
    suspend fun updateNow(context: Context): Outcome {
        val saved = savedChannelName(context)
        val other = if (saved == MASTER) STABLE else MASTER

        var used = saved
        var ok = false
        var detail = ""
        try {
            val (good, why) = attempt(context, saved)
            ok = good
            detail = why
            if (!good) {
                used = other
                val (good2, why2) = attempt(context, other)
                ok = good2
                detail = "$why; fallback $why2"
            }
        } catch (t: Throwable) {
            DebugLog.error("yt-dlp core update on $saved failed: ${t.message ?: t.javaClass.simpleName}")
            try {
                used = other
                val (good2, why2) = attempt(context, other)
                ok = good2
                detail = "thrown: ${t.message ?: t.javaClass.simpleName}; fallback $why2"
            } catch (t2: Throwable) {
                DebugLog.error("yt-dlp core update fallback also failed: ${t2.message ?: t2.javaClass.simpleName}")
                return Outcome(
                    ok = false,
                    message = "Core update failed (${channelLabel(saved)} and fallback): ${t2.message ?: t2.javaClass.simpleName}",
                    version = null,
                    channelUsed = other
                )
            }
        }

        val version = currentVersion(context)
        val vTxt = version?.takeIf { it.isNotBlank() } ?: "unknown"
        if (ok) {
            DebugLog.user("yt-dlp core updated on $used channel -> $vTxt ($detail)")
        }
        return Outcome(
            ok = ok,
            message = if (ok) "Core on ${channelLabel(used)} — version $vTxt"
                      else "Core update failed: $detail",
            version = version,
            channelUsed = used
        )
    }
}
