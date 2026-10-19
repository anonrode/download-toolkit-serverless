package com.anonrode.downloader.update

import android.content.Context
import com.anonrode.downloader.BuildConfig
import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.util.DebugLog
import com.anonrode.downloader.util.RemoteLogger
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Active-announcement selection (workstream B, phase 2; plan §4.2/§4.4).
 *
 * Picks the first eligible entry from the signed payload's `announcements`
 * array (payload order = priority) and publishes it for S3's host to render.
 * Eligibility: minApp ≤ this build, not expired, and not already shown per
 * its oncePer scope — "id" → `announcement_shown_<id>` (forever), "rev" →
 * `announcement_shown_rev_<activeRev>` (re-shows when a new payload rev
 * carries it). The shown marker is persisted at PICK time, not at dismiss:
 * a message the app decided to show has been seen, so a crash between show
 * and dismiss must not replay it.
 *
 * Everything is inert while the payload omits the section or its family flag
 * is off — [DynamicRulesManager.getAnnouncements] already yields empty then
 * (flag gating lives in the rules manager). Telemetry is id-only, in the
 * existing encrypted sink. Never throws.
 */
object AnnouncementManager {

    /** Render model frozen for the phase-2 UI host (S3). */
    data class Active(
        val id: String,
        val title: String,
        val body: String,
        val mode: String,        // "dialog" | "sheet" | "banner"
        val action: String,      // "none" | "url" | "update"
        val actionLabel: String,
        val actionUrl: String
    )

    /** The announcement S3 should currently render (null = nothing). */
    val current: MutableStateFlow<Active?> = MutableStateFlow(null)

    private const val PREFS = "downloader_settings"

    /** Shown-telemetry dedupe per session (one id@rev record each). */
    private val shownReported = ConcurrentHashMap.newKeySet<String>()

    /**
     * Recompute the active announcement for the CURRENT payload. Called by
     * AnonApp after each successful rules activation (and safe to call
     * whenever). Never throws.
     */
    fun refresh(context: Context) {
        try {
            val list = DynamicRulesManager.getAnnouncements()
            if (list.isEmpty()) {
                current.value = null
                return
            }
            if (current.value != null) {
                // QA-V3 F1: a LIVE announcement owns the slot. The cold start
                // runs afterRulesActivated twice (cache-restore + fresh sync);
                // because the shown-marker persists at PICK time, the second
                // call used to see the marker, advance to the next entry (or
                // clear the flow) and the picked message never rendered.
                // Re-evaluation waits until the live one is dismissed.
                return
            }
            val rev = DynamicRulesManager.getActiveRev()
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val picked = list.firstOrNull { a ->
                a.title.isNotBlank() &&
                    a.minApp <= BuildConfig.VERSION_CODE &&
                    (a.expiresAtMs == 0L || now < a.expiresAtMs) &&
                    !prefs.getBoolean(shownKey(a.oncePer, a.id, rev), false)
            }
            if (picked == null) {
                current.value = null
                return
            }
            // Persist BEFORE publishing: see class KDoc (no replay on crash).
            try {
                prefs.edit().putBoolean(shownKey(picked.oncePer, picked.id, rev), true).apply()
            } catch (_: Exception) {
            }
            current.value = Active(
                id = picked.id,
                title = picked.title,
                body = picked.body,
                mode = picked.mode,
                action = picked.action,
                actionLabel = picked.actionLabel,
                actionUrl = picked.actionUrl
            )
            val dedupe = "${picked.id}@$rev"
            if (shownReported.add(dedupe)) {
                report("announcement_shown", picked.id)
            }
        } catch (t: Throwable) {
            current.value = null
            DebugLog.engine("AnnouncementManager.refresh failed: ${t.javaClass.simpleName}")
        }
    }

    /**
     * User dismissed (or acted on) the active announcement. The oncePer
     * marker was already persisted at pick time; this only clears the flow
     * and records the dismissed event (id-only). Never throws.
     */
    fun dismiss(context: Context, id: String) {
        try {
            val cur = current.value
            if (cur != null && cur.id == id) current.value = null
            else if (cur == null) return
            report("announcement_dismissed", id)
        } catch (t: Throwable) {
            DebugLog.engine("AnnouncementManager.dismiss failed: ${t.javaClass.simpleName}")
        }
    }

    /** oncePer scope → persistent shown-marker key (exact names frozen by
     *  the phase-2 contract; "rev" keys carry the active payload rev — AND
     *  the id (QA-V3 F9): without the id, one rev-scoped announcement shown
     *  suppressed every other rev-scoped entry for the whole rev). */
    private fun shownKey(oncePer: String, id: String, rev: Long): String =
        if (oncePer.equals("rev", ignoreCase = true)) "announcement_shown_rev_${rev}_$id"
        else "announcement_shown_$id"

    private fun report(type: String, id: String) {
        try {
            RemoteLogger.report(
                type = type,
                message = "$type: $id",
                context = mapOf("id" to id)
            )
        } catch (_: Exception) {
        }
    }
}
