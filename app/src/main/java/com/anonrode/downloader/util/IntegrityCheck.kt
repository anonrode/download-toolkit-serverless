package com.anonrode.downloader.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.anonrode.downloader.BuildConfig
import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.data.rules.TamperPolicy
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runtime repackage detector (workstream C, phase 1).
 *
 * Compares THIS installed package's signing-certificate SHA-256 against the
 * optional OTA "tamper" pin ([DynamicRulesManager.getTamperPolicy]). The
 * honest limitation is unchanged from the plan (§5): a patched app can lie —
 * the value of this object is VISIBILITY, not enforcement. It therefore
 * NEVER blocks, gates, or throws: the consumer reads a [Verdict] and, on a
 * genuine [Verdict.MISMATCH], one aggregate `tamper_detected` telemetry
 * record leaves the device per session (inside the existing encrypted
 * envelope; event id-only — no user data, and the cert prefix is public
 * information derivable from any copy of the APK).
 *
 * Verdict semantics:
 *  - [Verdict.NO_PIN]   — no tamper policy in the active payload, or a blank
 *                         pin ("feature off" is the safe state, plan §2).
 *  - [Verdict.OK]       — pin present, installed cert matches it.
 *  - [Verdict.MISMATCH] — pin present, installed cert differs (reported once).
 *  - [Verdict.UNKNOWN]  — pin present but unusable/unreadable (malformed pin
 *                         or PackageManager failure); never reported.
 *
 * Caching: the signing-certificate SHA-256 is computed once per process and
 * held in a @Volatile — it is the expensive, genuinely stable value
 * (signing certs cannot change without a reinstall). The verdict itself is a
 * pure hex comparison recomputed per call against the CURRENT pin, so a
 * payload synced mid-session takes effect immediately; caching the verdict
 * instead would freeze an early NO_PIN forever.
 *
 * minSdk is 26 (app/build.gradle.kts), so the API 28+
 * GET_SIGNING_CERTIFICATES path has a real fallback branch: API 26–27 use
 * the legacy GET_SIGNATURES flag (deprecated on 28+ but the only option
 * below it). All signers are compared, which also covers v3 signing
 * lineages (a rotation overlap matches as long as one presented cert is the
 * pin) and multi-signer APKs.
 */
object IntegrityCheck {

    enum class Verdict { NO_PIN, OK, MISMATCH, UNKNOWN }

    /** SHA-256 hex digests of every cert the package presents. @Volatile:
     *  evaluate() may be called from any thread (sync completion, Activity
     *  resume, Settings diagnostics); concurrent first calls recompute
     *  harmlessly and publish the same value. */
    @Volatile
    private var cachedCertSha256: List<String>? = null

    /** One MISMATCH telemetry record per session, regardless of caller count
     *  (the same discipline as the OTA rev-rejection report). */
    private val mismatchReported = AtomicBoolean(false)

    private val HEX64 = Regex("^[0-9a-f]{64}$")

    /**
     * The one public entry point: current tamper verdict for this install.
     * Pure read + at most one fire-and-forget telemetry call; never throws,
     * never blocks the caller, never influences app behavior.
     */
    fun evaluate(context: Context): Verdict {
        val policy: TamperPolicy? = try {
            DynamicRulesManager.getTamperPolicy()
        } catch (_: Exception) {
            null
        }
        // Absent section or blank pin = feature off (safe state).
        val pin = normalizeHex(policy?.expectedCertSha256) ?: return Verdict.NO_PIN
        // A pin that is not 64 hex chars cannot be compared to anything —
        // policy exists but is unusable. No report: this is a payload
        // authoring problem the publish-side validator already rejects.
        if (!HEX64.matches(pin)) return Verdict.UNKNOWN
        val certs = certSha256s(context) ?: return Verdict.UNKNOWN
        return if (certs.any { it == pin }) {
            Verdict.OK
        } else {
            reportMismatchOnce(context, certs.first(), policy)
            Verdict.MISMATCH
        }
    }

    /** Diagnostics helper (Settings rows / telemetry context): is this build
     *  a debuggable install (FLAG_DEBUGGABLE, i.e. debug variant)? Debug
     *  builds are debug-signed, so a release pin legitimately MISMATCHes
     *  there — consumers tag telemetry with this flag to filter. */
    fun isDebugBuild(context: Context): Boolean = try {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    } catch (_: Exception) {
        false
    }

    /** Aggregate, id-only mismatch record. type + app version + a 12-hex
     *  prefix of the ACTUAL cert (public info; lets the server distinguish a
     *  known repackage cert from a one-off) + the policy's reportOnly mode.
     *  Never includes the pin value or anything user-derived. */
    private fun reportMismatchOnce(context: Context, actual: String, policy: TamperPolicy?) {
        if (!mismatchReported.compareAndSet(false, true)) return
        try {
            RemoteLogger.report(
                type = "tamper_detected",
                message = "APK signing certificate does not match the OTA pinned value",
                context = mapOf(
                    "app_version" to BuildConfig.VERSION_NAME,
                    "actual_cert_prefix" to actual.take(12),
                    "report_only" to (policy?.reportOnly ?: true),
                    "debug_build" to isDebugBuild(context)
                )
            )
        } catch (_: Exception) {
            // Reporting must never affect the app (RemoteLogger is already
            // non-throwing; this is belt-and-braces).
        }
    }

    /** Cached certificate digests; null when the package info cannot be read
     *  (never cached, so a transient failure can recover next call). */
    private fun certSha256s(context: Context): List<String>? {
        cachedCertSha256?.let { return it }
        val certs = readSigningCerts(context) ?: return null
        if (certs.isEmpty()) return null
        val hexes = certs.mapNotNull { cert ->
            try {
                MessageDigest.getInstance("SHA-256")
                    .digest(cert.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            } catch (_: Exception) {
                null
            }
        }
        if (hexes.isEmpty()) return null
        cachedCertSha256 = hexes
        return hexes
    }

    /** Every signing certificate the package presents, or null on failure.
     *  API >= 28: SigningInfo (multi-signer APK contents signers + the
     *  signing lineage history — union, so a rotation overlap still matches
     *  a pin naming either the old or the new cert). API 26–27: the only
     *  available source is the legacy signatures array. */
    @Suppress("DEPRECATION")
    private fun readSigningCerts(context: Context): List<Signature>? = try {
        val pm = context.packageManager
        val pkg = context.packageName
        if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            val si = info.signingInfo
            when {
                si == null -> null
                si.hasMultipleSigners() -> si.apkContentsSigners.toList()
                else -> si.apkContentsSigners.toList() + si.signingCertificateHistory.toList()
            }
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures?.toList()
        }
    } catch (_: Exception) {
        null
    }

    /** "AB:CD:…"/"ABCD…" -> lowercase bare hex; blank -> null (no pin). */
    private fun normalizeHex(raw: String?): String? = raw
        ?.trim()
        ?.lowercase()
        ?.replace(":", "")
        ?.replace(" ", "")
        ?.takeIf { it.isNotEmpty() }
}
