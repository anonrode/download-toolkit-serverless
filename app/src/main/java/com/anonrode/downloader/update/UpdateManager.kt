package com.anonrode.downloader.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.FileProvider
import com.anonrode.downloader.AnonApp
import com.anonrode.downloader.BuildConfig
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.data.rules.UpdatePolicy
import com.anonrode.downloader.util.DebugLog
import com.anonrode.downloader.util.RemoteLogger
import com.anonrode.downloader.util.UpdateCheckResult
import com.anonrode.downloader.util.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * True in-app update flow (workstream B, phase 2; plan §3.3/§3.4 + §4.5
 * brick-safety rails).
 *
 * State comes from the signed OTA payload (`update` section): version code,
 * dual hosting sources (VPS mirror first, GitHub fallback), per-ABI SHA-256
 * pins, message, and the optional force-obsolete gate. Everything here is a
 * no-op while the section is absent — the safe state (plan §2).
 *
 * Honesty rules baked in:
 *  - Downloads verify the SHA-256 pin from the signed payload on EVERY
 *    source attempt; a source serving the wrong bytes is skipped, and an
 *    empty/blank pin fails CLOSED (never installs unverified bytes).
 *  - [State.FORCED] is computed only when the force block is enabled with a
 *    non-zero floor AND the active payload is provably fresh (plan §4.5:
 *    a hard gate must never latch onto stale or unknowable policy). This
 *    manager renders nothing itself — S3's gate consumes [state]/[message]
 *    and owns the escape hatch.
 *  - Telemetry (`update_flow`) carries stage + version codes + source KIND
 *    + failure class ONLY — never URLs, file paths, or payload contents.
 *
 * Staleness source (documented decision, no timestamp getter exists on
 * DynamicRulesManager): the newest of (a) `last_rules_sync` written by
 * AnonApp after each successful sync (consts exposed by AnonApp for this),
 * and (b) the verified-payload cache file mtime (`dynamic_scraper_rules.enc`,
 * written by DynamicRulesManager on every accepted payload — same string as
 * its private CACHE_FILE). Both are local facts; when neither exists the age
 * is unknowable and the hard rail fails safe (no FORCED state).
 */
object UpdateManager {

    enum class State { UNKNOWN, UP_TO_DATE, AVAILABLE, FORCED }

    /** Compose-consumable state (contract: S3 renders; only this object
     *  writes). MutableStateFlow is thread-safe for cross-thread updates. */
    val state: MutableStateFlow<State> = MutableStateFlow(State.UNKNOWN)
    val message: MutableStateFlow<String> = MutableStateFlow("")

    private const val STALE_RAIL_MS = 14L * 24 * 60 * 60 * 1000  // plan §4.5 rail 1
    private const val ARTIFACT_DIR = "apk"
    private const val MAX_APK_BYTES = 300L * 1024 * 1024           // sanity cap
    private const val CACHE_FILE_NAME = "dynamic_scraper_rules.enc" // mirror of DynamicRulesManager CACHE_FILE (private there)

    /** "shown" telemetry is once per (state, versionCode) per session — a
     *  recompute storm (every activity resume) must not stream records. */
    private val shownReported = ConcurrentHashMap.newKeySet<String>()

    // ---- evaluation ---------------------------------------------------------

    /**
     * Sync, cheap (volatile rules + prefs only). Computes [state]/[message]
     * from the active update policy; safe to call on any thread, never
     * throws. FORCED requires the force block enabled, a non-zero
     * minSupportedVersionCode, this build below the floor, a payload whose
     * age is known and < 14 days (§4.5 rail 1 + rail "minSupported 0"), AND a
     * strictly newer build actually published (QA-V2 F1: a gate must always
     * be satisfiable — never demand an update that does not exist).
     */
    fun evaluate(context: Context) {
        try {
            val policy = DynamicRulesManager.getUpdatePolicy()
            if (policy == null) {
                state.value = State.UNKNOWN
                message.value = ""
                return
            }
            val vc = BuildConfig.VERSION_CODE
            val force = policy.force
            val forceFloor = force?.minSupportedVersionCode ?: 0
            val available = policy.latestVersionCode > vc
            val forced = force?.enabled == true &&
                forceFloor > 0 &&
                vc < forceFloor &&
                available &&
                payloadAgeMs(context) < STALE_RAIL_MS
            val newState = when {
                forced -> State.FORCED
                available -> State.AVAILABLE
                else -> State.UP_TO_DATE
            }
            state.value = newState
            message.value = if (newState == State.AVAILABLE || newState == State.FORCED) {
                policy.message.ifBlank { "A new version (${policy.latestVersionName}) is available." }
            } else {
                ""
            }
            if (newState == State.AVAILABLE || newState == State.FORCED) {
                val key = "$newState:${policy.latestVersionCode}"
                if (shownReported.add(key)) {
                    reportFlow(
                        "shown", mapOf(
                            "state" to newState.name,
                            "latest_version" to policy.latestVersionName,
                            "latest_vc" to policy.latestVersionCode,
                            "app_vc" to vc
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            // Evaluation failure = never a gate (rail 1); stay honest.
            state.value = State.UNKNOWN
            message.value = ""
            DebugLog.engine("UpdateManager.evaluate failed: ${t.javaClass.simpleName}")
        }
    }

    /** Newest provable "payload is current" timestamp (see class KDoc). */
    private fun payloadAgeMs(context: Context): Long {
        val lastSync = try {
            context.getSharedPreferences(AnonApp.PREFS_SERVERLESS, Context.MODE_PRIVATE)
                .getLong(AnonApp.KEY_LAST_RULES_SYNC, 0L)
        } catch (_: Exception) {
            0L
        }
        val cacheMtime = try {
            File(context.filesDir, CACHE_FILE_NAME).lastModified()
        } catch (_: Exception) {
            0L
        }
        val newest = maxOf(lastSync, cacheMtime)
        // 0 = no evidence of any verified payload → unknowable → "infinitely
        // old" so the hard rail fails closed.
        if (newest <= 0L) return Long.MAX_VALUE
        return (System.currentTimeMillis() - newest).coerceAtLeast(0L)
    }

    // ---- download -----------------------------------------------------------

    /**
     * Walks `policy.sources` IN PAYLOAD ORDER; URL = source.base +
     * per-ABI filename. Each attempt streams to cacheDir/apk/ and must
     * match the payload's SHA-256 pin — a mismatch (or any error) deletes
     * the partial file and moves to the next source. Returns the verified
     * file or null. Never throws.
     */
    suspend fun downloadApk(
        context: Context,
        onProgress: (downloaded: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): File? = withContext(Dispatchers.IO) {
        try {
            val policy = DynamicRulesManager.getUpdatePolicy() ?: return@withContext null
            val artifact = pickArtifact(policy) ?: run {
                reportFlow("failed", mapOf("failure" to "no_artifact_for_abi"))
                return@withContext null
            }
            val expected = normalizeHex(artifact.sha256)
            if (expected == null) {
                // Fail closed: refusing to download bytes we cannot verify.
                reportFlow(
                    "failed",
                    mapOf("failure" to "no_pin", "app_vc" to BuildConfig.VERSION_CODE)
                )
                return@withContext null
            }
            val dir = File(context.cacheDir, ARTIFACT_DIR)
            if (!dir.exists() && !dir.mkdirs()) {
                reportFlow("failed", mapOf("failure" to "cache_dir"))
                return@withContext null
            }
            val out = File(dir, artifact.file)

            reportFlow(
                "downloading",
                mapOf(
                    "latest_version" to policy.latestVersionName,
                    "latest_vc" to policy.latestVersionCode
                )
            )
            var lastFailure = "no_sources"
            for (source in policy.sources) {
                val base = source.base.trim()
                if (!base.startsWith("http://") && !base.startsWith("https://")) continue
                val url = base.trimEnd('/') + "/" + artifact.file
                val failure = tryDownloadTo(url, out, onProgress)
                if (failure != null) {
                    lastFailure = "${failure}:${source.kind}"
                    DebugLog.engine("UpdateManager: source ${source.kind} failed ($failure)")
                    continue
                }
                val actual = sha256File(out)
                if (actual == null || actual != expected) {
                    out.delete()
                    lastFailure = "mismatch:${source.kind}"
                    DebugLog.error("UpdateManager: SHA-256 mismatch from ${source.kind} — refusing")
                    continue
                }
                reportFlow(
                    "verified",
                    mapOf(
                        "latest_vc" to policy.latestVersionCode,
                        "source" to source.kind.ifBlank { "unknown" }
                    )
                )
                return@withContext out
            }
            out.delete()
            reportFlow("failed", mapOf("failure" to lastFailure))
            null
        } catch (t: Throwable) {
            reportFlow("failed", mapOf("failure" to (t.javaClass.simpleName)))
            null
        }
    }

    private data class Artifact(val file: String, val sha256: String)

    /** arm64 device → per-ABI file when named; anything else (32-bit ARM,
     *  x86 emulators) → universal. The payload only ships these two. */
    private fun pickArtifact(policy: UpdatePolicy): Artifact? {
        val arm64 = android.os.Build.SUPPORTED_ABIS.firstOrNull()?.contains("arm64") == true
        val file = if (arm64) policy.apkArm64 else policy.apkUniversal
        val pin = if (arm64) policy.sha256Arm64 else policy.sha256Universal
        if (file.isBlank()) return null
        return Artifact(file = file, sha256 = pin)
    }

    /** Streams [url] into [out]; returns null on success or a short failure
     *  class on any error (never a URL — telemetry-safe by construction). */
    private fun tryDownloadTo(
        url: String,
        out: File,
        onProgress: (Long, Long) -> Unit
    ): String? {
        val tmp = File(out.parentFile, out.name + ".part")
        var response: okhttp3.Response? = null
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", HttpClient.DEFAULT_UA)
                .build()
            response = HttpClient.shared.newCall(request).execute()
            if (!response.isSuccessful) return "http_${response.code}"
            val body = response.body ?: return "empty_body"
            val total = body.contentLength().let { if (it > 0) it else 0L }
            if (total > MAX_APK_BYTES) return "too_large"
            var downloaded = 0L
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        downloaded += n
                        if (downloaded > MAX_APK_BYTES) return "too_large"
                        output.write(buf, 0, n)
                        onProgress(downloaded, total)
                    }
                }
            }
            if (!tmp.renameTo(out)) {
                tmp.delete()
                return "rename_failed"
            }
            null
        } catch (t: Throwable) {
            tmp.delete()
            t.javaClass.simpleName
        } finally {
            try { response?.close() } catch (_: Exception) {}
        }
    }

    private fun sha256File(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        null
    }

    private fun normalizeHex(raw: String?): String? {
        val hex = raw?.trim()?.lowercase()?.replace(":", "")?.replace(" ", "") ?: return null
        if (hex.length != 64 || hex.any { it !in "0123456789abcdef" }) return null
        return hex
    }

    // ---- install ------------------------------------------------------------

    /**
     * Hands a VERIFIED apk to the system. Primary path: PackageInstaller
     * session (framework API, zero deps; REQUEST_INSTALL_PACKAGES is in the
     * manifest since phase 1); fallback: ACTION_VIEW with a FileProvider URI
     * (the existing `${applicationId}.fileprovider` + file_paths.xml already
     * cover all of cacheDir). Returns true when the system took over the
     * flow — the user still confirms in the installer UI either way.
     */
    fun installApk(context: Context, apk: File): Boolean {
        reportFlow("install_started", mapOf("app_vc" to BuildConfig.VERSION_CODE))
        if (!apk.exists() || apk.length() <= 0L) return false
        val sessionOk = try {
            installViaSession(context, apk)
        } catch (t: Throwable) {
            DebugLog.error("UpdateManager: session install failed (${t.javaClass.simpleName}), falling back to ACTION_VIEW")
            false
        }
        if (sessionOk) return true
        return viewFallback(context, apk)
    }

    private fun installViaSession(context: Context, apk: File): Boolean {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { output ->
                apk.inputStream().use { input -> input.copyTo(output) }
            }
            val resultIntent = Intent(context, PackageInstallerResultReceiver::class.java)
            val pending = android.app.PendingIntent.getBroadcast(
                context,
                sessionId,
                resultIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            )
            session.commit(pending.intentSender)
        }
        return true
    }

    private fun viewFallback(context: Context, apk: File): Boolean = try {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (t: Throwable) {
        DebugLog.error("UpdateManager: install intent failed (${t.javaClass.simpleName})")
        false
    }

    // NOTE (QA-V2 F5): the earlier string-returning `checkForUpdate` wrapper
    // was removed as dead code — Settings runs the structured
    // util/UpdateChecker.check() chain itself and then calls [evaluate] so a
    // manual check also refreshes the prompt/gate state. This object stays
    // the single source for [state]/[message].

    // ---- telemetry ----------------------------------------------------------

    /** One `update_flow` record per stage; aggregate, id-only (plan §10). */
    private fun reportFlow(stage: String, extra: Map<String, Any?> = emptyMap()) {
        try {
            RemoteLogger.report(
                type = "update_flow",
                message = "update flow: $stage",
                context = mapOf("stage" to stage) + extra
            )
        } catch (_: Exception) {
        }
    }
}

/**
 * Result hand-off for the PackageInstaller session (manifest-declared,
 * exported="false" — the system delivers the result explicitly). On
 * STATUS_PENDING_USER_ACTION the system wants the confirmation launched;
 * failures are logged as class-only telemetry. Never touches user data.
 */
class PackageInstallerResultReceiver : BroadcastReceiver() {

    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent ?: return
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    DebugLog.user("UpdateManager: package installer reported success")
                }
                else -> {
                    val cls = when (status) {
                        PackageInstaller.STATUS_FAILURE -> "failure"
                        PackageInstaller.STATUS_FAILURE_ABORTED -> "aborted"
                        PackageInstaller.STATUS_FAILURE_BLOCKED -> "blocked"
                        PackageInstaller.STATUS_FAILURE_CONFLICT -> "conflict"
                        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "incompatible"
                        PackageInstaller.STATUS_FAILURE_INVALID -> "invalid"
                        PackageInstaller.STATUS_FAILURE_STORAGE -> "storage"
                        else -> "unknown_$status"
                    }
                    try {
                        RemoteLogger.report(
                            type = "update_flow",
                            message = "update flow: failed",
                            context = mapOf("stage" to "failed", "failure" to "installer_$cls")
                        )
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
    }
}
