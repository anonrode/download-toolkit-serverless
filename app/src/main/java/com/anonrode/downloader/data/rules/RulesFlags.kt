package com.anonrode.downloader.data.rules

import android.content.Context
import com.anonrode.downloader.BuildConfig
import org.json.JSONObject
import java.util.UUID

/**
 * Per-family OTA flags + kill switches (Phase 2, plan slice P2-1; prior art:
 * Mozilla Remote Settings bucket/collection split §1.4, AdGuard trust-level
 * gating §1.5, Firebase in-app-defaults floor §1.6).
 *
 * Payload shape (top-level "flags" object; every field optional):
 * ```
 * "flags": {
 *   "<family>": { "enabled": true|false, "percent": 0..100,
 *                 "minApp": 42, "expiresMs": 1770000000000, "shadow": true|false }
 * }
 * ```
 * `<family>` MUST be one of the literal top-level payload keys in
 * [FAMILY_IDS] (the client's parseRulesJson section names). The publish-side
 * validator (encrypt_rules.py) hard-fails unknown families/sub-keys; this
 * client parser enforces the SAME vocabulary LENIENTLY — unknown family key
 * => journal + skip the entry, unknown SUB-key => skip that family's whole
 * flag object (a typo'd flag must never half-apply; AdGuard trust-level
 * lesson). Leniency is the additive-only rule in action: an old client that
 * has never heard of "flags" ignores the object wholesale.
 *
 * Durability follows Firebase's in-app-defaults floor: flags live ONLY
 * inside the payload that carried them. A payload that omits "flags" (or a
 * family) resets that family to [DEFAULT] — no stale-flag limbo, and the
 * compiled fallback is the instant kill switch. Precedence (plan §3.5 /
 * Chromium variations): bundled default < payload flag entry; a future
 * per-device debug override would slot above (none exists tonight).
 *
 * DEFAULT state per family: enabled, percent 100, no minApp, no expiry,
 * shadow off — i.e. zero behavior change for every payload shipped before
 * this schema existed.
 */

/** One family's flag record. Defaults are the safe state (see class doc). */
data class FamilyFlag(
    /** Kill switch: false => the family publishes EMPTY (compiled fallback). */
    val enabled: Boolean = true,
    /** Stable-bucket rollout percent (0..100). percent >= 100 => every device. */
    val percent: Int = 100,
    /** Minimum app VERSION_CODE for this family; 0 = no floor. Below it the
     *  family publishes empty + journal skip (old-browser-on-new-pack). */
    val minAppVersion: Int = 0,
    /** Absolute epoch-ms expiry hint for the family (uBO/Remote-Settings style
     *  staleness metadata). 0 = no expiry. NOTE: advisory-only this rev cycle —
     *  stale-but-functional is the posture (uBO: "functional even when
     *  'expired'"); expired flags journal, they never disable. */
    val expiresMs: Long = 0L,
    /** Shadow-run marker (GitHub Scientist pattern, plan P2-4): the family's
     *  parsed section is held back from the user path (compiled control runs)
     *  while the candidate engine measures it sample-by-sample. */
    val shadow: Boolean = false
) {
    /** True when this device's app build satisfies the family's minApp floor. */
    val minAppSatisfied: Boolean
        get() = minAppVersion <= 0 || minAppVersion <= BuildConfig.VERSION_CODE

    /** True when the flag carries no signals beyond the defaults — kept so a
     *  no-op flag entry can be distinguished from a real rollout decision. */
    val isDefault: Boolean
        get() = this == RulesFlags.DEFAULT
}

object RulesFlags {

    /**
     * The family-id whitelist — the LITERAL top-level payload keys (coordinator
     * correction 2026-10-15; the client variable names in older notes were NOT
     * ids). Publish-side validator accepts exactly this set; unknown ids here
     * are journaled and skipped.
     */
    val FAMILY_IDS: List<String> = listOf(
        "domains",
        "mirrors",
        "sites",
        "resolvers",
        "directMediaExtensions",
        "hostPolicies",
        "urlTemplates",
        "knownDead",
        "lockerHosts",
        "searchStrategies",
        "pipelines",
        "dynamic_providers",
        "contentPolicy"
    )

    /** Allowed per-family flag sub-keys; anything else voids that family's
     *  flag object (typo hazard — half-applied flags are worse than none). */
    private val ALLOWED_SUB_KEYS = setOf("enabled", "percent", "minApp", "expiresMs", "shadow")

    /** Safe default: family fully on, no gating, no experiment. */
    val DEFAULT = FamilyFlag()

    /** Flags for [family], with [DEFAULT] when the payload carried none — the
     *  Firebase in-app-defaults floor. */
    fun flagFor(family: String, flags: Map<String, FamilyFlag>): FamilyFlag =
        flags[family] ?: DEFAULT

    /**
     * Lenient flags-object parse. Never throws, never fails the payload —
     * problems are reported through [journal] and the offending entry/family
     * is skipped. An absent "flags" object yields an empty map = all defaults.
     *
     * @param flagsObj the payload's optional "flags" object.
     * @param journal receives one human line per noteworthy decision (wired
     *        to DebugLog.engine with the payload version by the caller).
     */
    fun parse(flagsObj: JSONObject?, journal: (String) -> Unit): Map<String, FamilyFlag> {
        if (flagsObj == null) return emptyMap()
        val out = mutableMapOf<String, FamilyFlag>()
        try {
            val keys = flagsObj.keys()
            while (keys.hasNext()) {
                val family = keys.next()
                if (!FAMILY_IDS.contains(family)) {
                    // Unknown family: not a security risk (it can only affect a
                    // table nobody reads) but a fleet-wide-typo indicator —
                    // journal it and move on. Never fatal.
                    journal("flags: unknown family \"$family\" ignored")
                    continue
                }
                val fo = flagsObj.optJSONObject(family)
                if (fo == null) {
                    journal("flags: family \"$family\" ignored (entry is not an object)")
                    continue
                }
                // Unknown SUB-key => the author diverged from the contract this
                // client speaks; skip the WHOLE family's flags so no half-applied
                // decision can hide behind a mistyped partner field.
                var foreignKey: String? = null
                val subKeys = fo.keys()
                while (subKeys.hasNext()) {
                    val k = subKeys.next()
                    if (!ALLOWED_SUB_KEYS.contains(k)) foreignKey = k
                }
                if (foreignKey != null) {
                    journal("flags: family \"$family\" skipped (unknown key \"$foreignKey\")")
                    continue
                }

                val percent = if (fo.has("percent")) fo.optInt("percent", DEFAULT.percent) else DEFAULT.percent
                if (percent !in 0..100) {
                    journal("flags: family \"$family\" skipped (percent $percent outside 0..100)")
                    continue
                }

                val minApp = parseMinApp(fo)
                if (minApp == MIN_APP_INVALID) {
                    journal("flags: family \"$family\" skipped (minApp not a positive integer)")
                    continue
                }

                val expires = if (fo.has("expiresMs")) fo.optLong("expiresMs", 0L) else 0L
                if (expires < 0L) {
                    journal("flags: family \"$family\" skipped (negative expiresMs)")
                    continue
                }

                out[family] = FamilyFlag(
                    enabled = fo.optBoolean("enabled", DEFAULT.enabled),
                    percent = percent,
                    minAppVersion = minApp,
                    expiresMs = expires,
                    shadow = fo.optBoolean("shadow", DEFAULT.shadow)
                )

                // Expiry is advisory-only (stale-but-functional posture, see
                // FamilyFlag.expiresMs): journal loudly, never disable.
                val flag = out.getValue(family)
                if (flag.expiresMs in 1..System.currentTimeMillis()) {
                    journal("flags: family \"$family\" PAST its expiresMs hint (advisory only — family stays active)")
                }
            }
        } catch (t: Exception) {
            // A syntactically hostile flags object must never take the payload
            // down: report what we have (families parsed so far stay valid).
            journal("flags: parse aborted (${t.javaClass.simpleName}) — ${out.size} family flag(s) kept")
        }
        return out
    }

    private const val MIN_APP_INVALID = -1

    /** minApp accepts a JSON number or a numeric string (authoring comfort);
     *  anything else is invalid => whole family flag skipped. 0 = no floor. */
    private fun parseMinApp(fo: JSONObject): Int {
        if (!fo.has("minApp")) return 0
        return when (val v = fo.opt("minApp")) {
            is Number -> {
                val i = v.toInt()
                if (i >= 0 && i.toDouble() == v.toDouble()) i else MIN_APP_INVALID
            }
            is String -> v.trim().toIntOrNull() ?: MIN_APP_INVALID
            else -> MIN_APP_INVALID
        }
    }

    // ------------------------------------------------------------------
    // P2-5 — stable-bucket percent assignment (client half of the ladder)
    // ------------------------------------------------------------------

    private const val PREF_DEVICE_UUID = "pref_ota_device_uuid"

    /**
     * Install-scoped identity for bucket assignment (plan open-question Q2:
     * app-generated; deliberately NOT the Google advertising id — that API
     * does not exist on this device class and is policy-forbidden for this
     * use anyway). Generated once on first use into app-private prefs, so a
     * bucket assignment is sticky across syncs and restarts; it churns only
     * on app-data wipe/reinstall, which the plan accepts as ladder noise.
     * Returns "" when no Context is available (JVM test path) — callers treat
     * that bucket-free case as OUTSIDE every partial rollout (conservative).
     */
    fun installUuid(context: Context?): String {
        val ctx = context ?: return ""
        return try {
            val prefs = ctx.getSharedPreferences("downloader_settings", Context.MODE_PRIVATE)
            prefs.getString(PREF_DEVICE_UUID, null) ?: run {
                val fresh = UUID.randomUUID().toString()
                prefs.edit().putString(PREF_DEVICE_UUID, fresh).apply()
                fresh
            }
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * stableBucket(uuid, family) = SHA-256("uuid/family") mod 100 (first 8
     * digest bytes, big-endian). Pure and deterministic: the SAME device lands
     * in the SAME bucket for a family on every sync/restart — no Scientist
     * per-run rand() flapping between engines mid-task (§3.5's stickiness
     * note). Different families draw independent buckets, so the promotion
     * of one family does not bias another's cohort. Blank uuid (no Context)
     * returns 100 = never inside a partial rollout.
     */
    fun stableBucket(uuid: String, family: String): Int {
        if (uuid.isBlank()) return 100
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val digest = md.digest("$uuid/$family".toByteArray(Charsets.UTF_8))
            var acc = 0L
            for (i in 0 until 8) {
                acc = (acc shl 8) or (digest[i].toLong() and 0xFFL)
            }
            // Long math mod 100 (sha256 mod 100 is uniform; guard the sign for
            // the negative half of the unsigned-64 range).
            val mod = (acc % 100L).toInt()
            if (mod < 0) mod + 100 else mod
        } catch (_: Exception) {
            100
        }
    }

    /**
     * The whole per-device activation decision for one family, in precedence
     * order (plan §3.5: bundled default < payload flag; P2-5 ladder):
     *  1. enabled=false  -> false (kill switch, all devices);
     *  2. minApp floor unsatisfied -> false on this build (P2-1);
     *  3. shadow=true    -> false for the USER path (shadow measurement mode,
     *     plan P2-4 — DynamicRulesManager separately arms ShadowRunner);
     *  4. percent>=100   -> true (fully promoted);
     *  5. otherwise      -> true only when stableBucket(uuid, family) < percent
     *     (the sticky 5 -> 25 -> 100 ladder cohort).
     */
    fun isFamilyActiveForDevice(flag: FamilyFlag, family: String, uuid: String): Boolean {
        if (!flag.enabled) return false
        if (!flag.minAppSatisfied) return false
        if (flag.shadow) return false
        if (flag.percent >= 100) return true
        return stableBucket(uuid, family) < flag.percent
    }
}
