package com.anonrode.downloader.data.rules

import android.content.Context
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.util.OtaFetchDiagnostics
import com.anonrode.downloader.providers.DynamicSiteConfig
import com.anonrode.downloader.providers.GenericDeclarativeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class SiteRuleConfig(
    val searchPattern: String = "",
    val searchType: String = "html",
    val cardSelector: String = "",
    val titleSelector: String = "",
    val linkSelector: String = "",
    val posterSelector: String = "",
    val episodeSelector: String = "",
    val seriesDescentSelectors: List<String> = emptyList(),
    val downloadAnchorSelector: String = "",
    val slugSuffixes: List<String> = emptyList()
)

/** One ordered host policy from the OTA playbook. First matching rule wins. */
data class HostPolicyRule(
    val match: String,
    val referer: String,   // "" | "exact:<url>" resolved value | "none"
    val ua: String = ""
)

/**
 * OTA-tunable content/download policy (optional top-level "contentPolicy").
 * Absent section => compiled defaults: no socket-cap overlay, no content
 * suppression — the Firebase in-app-defaults floor every section follows.
 * Clamp + overlay semantics validated in scratch/test_zero_redo.py (A) before
 * this port; the content-policy fields are schema-ready (filter feature ships
 * separately).
 */
data class ContentPolicy(
    val socketCaps: Map<String, Int> = emptyMap(),
    val suppressNollywood: Boolean = false,
    val nollywoodOnlySites: List<String> = emptyList(),
    val nollywoodSites: List<String> = emptyList(),
    val nollywoodTerms: List<String> = emptyList()
)

/**
 * One OTA announcement (optional top-level "announcements" array): remote
 * messaging rendered by the announcement host (dialog/sheet/banner).
 * Absent section => no announcements (feature OFF — the in-app-defaults
 * floor every section follows). Consumer: the announcement picker reads
 * oncePer/minApp/expiry to decide what this install may show.
 */
data class Announcement(
    val id: String,          // oncePer="id" store key; validator caps 64 chars
    val title: String,       // <= 80 chars (validator cap)
    val body: String,        // <= 300 chars (validator cap)
    val mode: String,        // "dialog" | "sheet" | "banner"
    val action: String,      // "none" | "url" | "update"
    val actionLabel: String,
    val actionUrl: String,   // http(s) when action="url"
    val oncePer: String,     // "id" (default) | "rev"
    val minApp: Int,         // VERSION_CODE floor; 0 = every build
    val expiresAtMs: Long    // absolute epoch-ms hint; 0 = never expires
)

/** One APK hosting source from the "update" policy, walked in order (VPS
 *  first — the fast unmetered pipe — GitHub fallback). Every attempt must
 *  verify the SAME SHA-256 pin from the signed payload (Decision 4). */
data class UpdateSource(
    val kind: String,        // "vps" | "github" | "gitea"
    val base: String         // http(s) directory/base URL
)

/**
 * Force-obsolete gate block inside "update". House default is a soft,
 * dismissible notice; "hard" is an explicit remote policy only. escapeHatch
 * defaults TRUE (brick-safety rail, plan §4.5) — a hard gate must always be
 * escapable by construction.
 */
data class UpdateForce(
    val enabled: Boolean = false,
    val minSupportedVersionCode: Int = 0,   // 0 = no floor (never gate on it)
    val mode: String = "soft",              // "soft" | "hard"
    val snoozeHours: Int = 24,              // 0..168 (validator cap)
    val escapeHatch: Boolean = true
)

/**
 * OTA update policy (optional top-level "update"): latest version identity,
 * dual hosting sources + SHA-256 pins, message, optional force gate.
 * Absent section => null (no update flow; pure pass-through).
 */
data class UpdatePolicy(
    val latestVersionName: String,
    val latestVersionCode: Int,
    val sources: List<UpdateSource>,
    val apkArm64: String,        // file name under each source base
    val apkUniversal: String,
    val sha256Arm64: String,     // 64 lowercase hex; "" until release-day pins
    val sha256Universal: String,
    val message: String,         // <= 300 chars (validator cap)
    val force: UpdateForce?      // null = no force gate authored
)

/**
 * OTA ads readiness (optional top-level "ads"). Nothing renders while
 * [enabled] is false; [provider] selects the slot implementation ("house" =
 * zero-SDK house texts; vendor adapters pre-wired but inert — plan §4.6).
 * Credentials are runtime strings, so registration later is an OTA flip.
 * Absent section => null (feature OFF).
 */
data class AdsConfig(
    val enabled: Boolean,
    val provider: String,          // "house"|"none"|"applovin"|"unity"|"admob"|"auto"
    val placements: List<String>,  // known slot ids, e.g. "downloads_shelf"
    val frequencyPerDay: Int,      // 0..20 (validator cap)
    val houseTitle: String,
    val houseBody: String,
    val houseActionLabel: String,
    val houseActionUrl: String,
    val houseId: String,
    val applovinSdkKey: String,    // vendor credential slots ("" = unset)
    val unityGameId: String,
    val admobAppId: String,
    val admobBannerUnitId: String,
    val admobNativeUnitId: String
)

/**
 * OTA re-engagement config (optional top-level "engagement"): local
 * notification after [inactiveDays] without an app open, deduped by
 * [cooldownDays]; [welcomeBack] arms the in-app return card. Absent
 * section => null (feature OFF).
 */
data class EngagementConfig(
    val enabled: Boolean,
    val inactiveDays: Int,     // 1..365 (validator cap)
    val cooldownDays: Int,     // 1..365 (validator cap)
    val title: String,
    val body: String,
    val welcomeBack: Boolean
)

/**
 * Runtime signing-cert pin (optional top-level "tamper"): expected APK
 * signing-cert SHA-256 (64 hex, case-insensitive). [reportOnly] is the
 * default posture — a patched client can lie, so the value is VISIBILITY
 * (tamper_detected telemetry), never a lock (plan §5). Absent section =>
 * null (check disabled).
 */
data class TamperPolicy(
    val expectedCertSha256: String,
    val reportOnly: Boolean = true
)

/**
 * FCM runtime config (optional top-level "fcm"): manual FirebaseOptions —
 * deliberately NO google-services.json; all four strings ship OTA and any
 * registrar built on this stays inert while [enabled] is false. Absent
 * section => null.
 */
data class FcmConfig(
    val enabled: Boolean,
    val apiKey: String,
    val appId: String,
    val senderId: String,
    val projectId: String
)

object DynamicRulesManager {

    // OTA rules are AES-128-CBC encrypted so the scraping logic is not
    // readable from the GitHub repo (site owners watching the repo see
    // ciphertext). Key/IV ship in the APK — this is obfuscation-grade
    // protection, NOT strong security: a determined reverse engineer can
    // extract them. Keep the key/IV in sync with probe/encrypt_rules.py.
    private const val RULES_KEY_HEX = "8f3a9c21d4e65b0789a2c4f6d1e3b5a7"
    private const val RULES_IV_HEX = "5b7e9d2f4a6c8e10f3a5c7d9b1e2f4a6"
    private const val RULES_URL = "https://raw.githubusercontent.com/anonrode/download-toolkit-serverless/master/scraper_rules.json.enc"

    // Ordered delivery chain (Phase 0). every source serves the SAME signed
    // payload byte-for-byte; the ECDSA/AES verification happens AFTER download
    // exactly as before (transport never trusted — a CDN or the mirror box
    // cannot feed the app fake logic, a bad payload is refused and the NEXT
    // source is tried). Rationale: app v3.2.9 on carrier networks could not
    // reach raw.githubusercontent.com at all ("could not reach GitHub" toast,
    // log share-31) while desktop was fine — carrier DNS poisoning/rate-
    // limiting is invisible to our DoH fallback (it only engages on lookup
    // failure, never on poisoned-but-resolvable answers), so delivery needs
    // independent hosts, not just one GitHub URL.
    //   1. raw.githubusercontent.com  — canonical
    //   2. cdn.jsdelivr.net/gh        — global CDN, different network path
    //   3. cdn.statically.io          — second independent CDN
    //   4. our own box (raw IP, cleartext exception in
    //      network_security_config)  — needs no DNS at all; last resort that
    //      answers even when every DNS path is broken or GitHub is blocked.
    private const val MIRROR_JSDELIVR = "https://cdn.jsdelivr.net/gh/anonrode/download-toolkit-serverless@master/scraper_rules.json.enc"
    private const val MIRROR_STATICALLY = "https://cdn.statically.io/gh/anonrode/download-toolkit-serverless@master/scraper_rules.json.enc"
    private const val MIRROR_SELF = "http://68.155.146.145/ota/scraper_rules.json.enc"

    private data class OtaSource(val label: String, val host: String, val url: String)

    private val RULES_SOURCES = listOf(
        OtaSource("raw.githubusercontent", "raw.githubusercontent.com", RULES_URL),
        OtaSource("jsdelivr", "cdn.jsdelivr.net", MIRROR_JSDELIVR),
        OtaSource("statically", "cdn.statically.io", MIRROR_STATICALLY),
        OtaSource("self-box", "68.155.146.145", MIRROR_SELF)
    )

    /** Label of the delivery-chain source that served the currently active
     *  rules payload ("" = bundled/cached rules never OTA-refreshed). */
    @Volatile
    var lastSyncSource: String = ""
        private set
    private const val CACHE_FILE = "dynamic_scraper_rules.enc"

    // ECDSA P-256 public key (X509/SPKI DER, base64). Private half lives ONLY
    // in the GitHub Actions secret OTA_SIGNING_PRIVATE_KEY; ota-rules.yml signs
    // every payload. Any payload whose signature doesn't verify is refused —
    // a repo hijack or CDN MITM cannot feed this app fake scraping logic.
    // Since step 7 (signing rotation, docs/SIGNING_ROTATION.md) this is
    // ROOT-1 = pin slot 1 of a dual-pin set: retirement/rotation no longer
    // requires an app release (leaf keys rotate via the envelope "chain"
    // object under any pinned root).
    // internal var (not const) solely so JVM tests can inject an ephemeral
    // keypair and exercise sign -> verify end-to-end.
    internal var rulesSigningPubB64: String =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAELW5uNxiti768q9f1YPvjaMyd0b60W7tEn6hCCQBtu6YyguDIMtKvefov9uwD" +
        "0uN9JP0HKkYUJB1wSL3Q928+lQ=="

    // ---- Step 7: pinned ROOT trust set (dual-pin overlap) ------------------
    // ROOT-2 of the signing hierarchy (design doc §3.4/§7; Firefox Remote
    // Settings §1.4 model — pinned ROOT in the app, delivered LEAF inside
    // the envelope). Generated 2026-10-15; public half only — the private
    // half NEVER ships and lives ONLY in this PC's keys/ cold custody
    // (docs/SIGNING_ROTATION.md carries the fingerprints + drill). Leaves
    // rotate under this pin with ZERO app releases; retiring root-1 is a
    // deliberate future-release edit (same block, one line removed).
    internal const val ROTATION_ROOT2_PUB_B64: String =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEr6faxWOcnYb9PN7Qtb1kZPm55ZzZ0tlUTpacE7BHBwowx+dxVpqHVLHAE6Ji" +
        "U6pDyGEvpta/+vSMxjjNVM5Rag=="

    /**
     * Effective pinned ROOT set used by EVERY signature verification here:
     * [rulesSigningPubB64 (root-1 slot; kept byte-identical and still the
     * test-injection surface — existing JVM tests assign an ephemeral key
     * here)] + [ROTATION_ROOT2_PUB_B64 (root-2)]. Rotation overlap semantics
     * ("verify first that succeeds"): a payload signed by EITHER root is
     * accepted, so nothing in the fleet is stranded during the window; a
     * chain envelope under root-2 still verifies even while slot 1 is
     * overridden by tests. Mirror pin set in scripts/encrypt_rules.py
     * (ROOT_PINS_B64) — keep both lists in lockstep.
     */
    internal fun signingRootPins(): List<String> =
        listOf(rulesSigningPubB64, ROTATION_ROOT2_PUB_B64)

    // Built-in fallback policies — mirrors the historical hardcoded referer
    // map in DownloadEngine (behavior-identical when no OTA rule matches).
    // OTA hostPolicies always take precedence over these.
    private val DEFAULT_HOST_POLICIES = listOf(
        HostPolicyRule("cdn.watching.onl", "exact:https://megaplay.buzz/"),
        HostPolicyRule("anivideo.sbs", "exact:https://megaplay.buzz/"),
        HostPolicyRule("nexabloom", "exact:https://megaplay.buzz/"),
        HostPolicyRule("vyrnex", "exact:https://megaplays.se/"),
        HostPolicyRule("megaplays.se", "exact:https://megaplays.se/"),
        HostPolicyRule("megap.", "exact:https://megaplay.buzz/"),
        HostPolicyRule("watching.onl", "exact:https://megaplay.buzz/"),
        HostPolicyRule("lisaido", "none"),
        HostPolicyRule("vidsrc", "none"),
        HostPolicyRule("gogoanime", "exact:https://gogoanime.or.at/"),
        HostPolicyRule("anitaku", "exact:https://gogoanime.or.at/"),
        HostPolicyRule("workers.dev", "exact:https://gogoanime.or.at/"),
        HostPolicyRule("workerforcloud", "exact:https://gogoanime.or.at/"),
        HostPolicyRule("asianc", "exact:https://asianc.id/"),
        HostPolicyRule("vidbasic", "exact:https://vidb.top/"),
        HostPolicyRule("vidb.", "exact:https://vidb.top/"),
        HostPolicyRule("jisooido", "exact:https://vidb.top/"),
        HostPolicyRule("exoidol", "exact:https://vidb.top/"),
        HostPolicyRule("premilkyway", "exact:https://vidb.top/"),
        HostPolicyRule("waffi.cloud", "none"),
        HostPolicyRule("tamilembed", "exact:https://anitaku.com.ro/"),
        HostPolicyRule("animesama", "exact:https://anitaku.com.ro/"),
        HostPolicyRule("kickassanime", "exact:https://anitaku.com.ro/"),
        HostPolicyRule("blogger.com", "exact:https://anitaku.com.ro/"),
        HostPolicyRule("googlevideo", "exact:https://www.blogger.com/"),
        HostPolicyRule("pluto", "exact:https://plutomovies.com/"),
        HostPolicyRule("kissorgrab", "exact:https://plutomovies.com/"),
        HostPolicyRule("thenkiri", "exact:https://nkiri.top/"),
        HostPolicyRule("nkiri", "exact:https://nkiri.top/"),
        HostPolicyRule("9jarocks", "exact:https://my9jarocks.bz/"),
        HostPolicyRule("loadedfiles", "exact:https://my9jarocks.bz/"),
        HostPolicyRule("naijavault", "exact:https://www.naijavault.com/"),
        HostPolicyRule("vikingfile", "exact:https://www.naijavault.com/"),
        HostPolicyRule("lulacloud", "exact:https://www.naijavault.com/"),
        HostPolicyRule("naijaprey", "exact:https://www.naijaprey.tv/"),
        HostPolicyRule("dramakey", "exact:https://dramakey.com/"),
        HostPolicyRule("dramarain", "exact:https://dramarain.com/")
    )

    private val defaultDomains = mapOf(
        "nkiri" to "https://nkiri.top",
        "dramakey" to "https://dramakey.com",
        "asianc" to "https://asianc.id",
        "anitaku" to "https://anitaku.com.ro",
        "pluto" to "https://plutomovies.com",
        "dramarain" to "https://dramarain.com",
        "9jarocks" to "https://9jarocks.net",
        "naijavault" to "https://www.naijavault.com",
        "naijaprey" to "https://www.naijaprey.tv",
        "nepu" to "https://nepu.gd",
        "torrents" to "https://apibay.org"
    )

    private val defaultMediaExtensions = listOf(
        ".mp4", ".mkv", ".webm", ".avi", ".ts", ".m3u8", ".m4v", ".mp3", ".zip", ".rar", "-mp4", "-mkv",
        ".jpg", ".jpeg", ".png", ".webp"
    )

    // Rule state is published as immutable snapshots behind @Volatile refs.
    // parseRulesJson builds fresh collections and swaps them in one go, so a
    // reader (resolveReferer on an active download thread, getBaseUrl in a
    // provider search) can never observe a half-applied playbook or hit a
    // ConcurrentModificationException while an OTA sync is parsing.
    @Volatile private var activeDomains: Map<String, String> = defaultDomains
    @Volatile private var activeMirrors: Map<String, List<String>> = emptyMap()
    @Volatile private var activeSiteConfigs: Map<String, SiteRuleConfig> = emptyMap()
    @Volatile private var activeResolverConfigs: Map<String, JSONObject> = emptyMap()
    @Volatile private var activeMediaExtensions: List<String> = defaultMediaExtensions
    @Volatile private var dynamicProviders: List<GenericDeclarativeProvider> = emptyList()

    // Playbook v2 state
    @Volatile private var activeHostPolicies: List<HostPolicyRule> = emptyList()
    @Volatile private var activeUrlTemplates: Map<String, String> = emptyMap()
    @Volatile private var activeKnownDead: List<String> = emptyList()
    @Volatile private var activeLockerHosts: List<String> = emptyList()
    @Volatile private var activeSearchStrategies: Map<String, List<JSONObject>> = emptyMap()

    // Track 6 (OTA-configured remote error logging): the /api/logs/client-error
    // sink. Empty string = reporting disabled. Read fresh on every report, so
    // relocating the server redirects every client on the next OTA sync —
    // the exact reason this lives in rules and not BuildConfig.
    @Volatile private var remoteLoggingUrl: String = ""

    // Declarative step pipelines: search/episode navigation as OTA data
    // (see PipelineModels.kt). Sites whose provider reads a pipeline here
    // keep their compiled code as per-call fallback.
    @Volatile private var activePipelines: Map<String, SitePipeline> = emptyMap()

    // Per-family flags + kill switches (P2-1). Published with the atomic swap
    // like every other collection; an payload without "flags" publishes an
    // empty map = every family on its safe default (Firebase in-app-defaults
    // floor — flags are durable only inside the payload that carried them).
    @Volatile private var activeFlags: Map<String, FamilyFlag> = emptyMap()

    // OTA content/download policy overlay (per-host socket caps live; content-
    // policy fields parsed for schema readiness). Published with the atomic
    // swap like every other snapshot; absent section = compiled defaults.
    @Volatile private var activeContentPolicy: ContentPolicy = ContentPolicy()

    // v3.3.0 platform-surface sections (plan §4.1): announcements, update
    // policy, ads readiness, engagement, tamper pin, FCM runtime config.
    // Published with the atomic swap like every other snapshot; an absent
    // section publishes OFF (null / empty) — no stale state can survive the
    // payload that carried it (same in-app-defaults floor as flags).
    @Volatile private var activeAnnouncements: List<Announcement> = emptyList()
    @Volatile private var activeUpdatePolicy: UpdatePolicy? = null
    @Volatile private var activeAdsConfig: AdsConfig? = null
    @Volatile private var activeEngagementConfig: EngagementConfig? = null
    @Volatile private var activeTamperPolicy: TamperPolicy? = null
    @Volatile private var activeFcmConfig: FcmConfig? = null

    /** Resolved flag for one family id (see RulesFlags.FAMILY_IDS), or the
     *  safe default when the active payload carried no flag for it. */
    fun getFamilyFlag(family: String): FamilyFlag =
        try { activeFlags[family] ?: RulesFlags.DEFAULT } catch (_: Exception) { RulesFlags.DEFAULT }

    /** Lenient parse of the optional "contentPolicy" section; never throws.
     *  Host keys are normalized (lowercase, www. stripped, trailing dot) and
     *  cap values clamped 1..16; arrays bounded 64/128 like every section. */
    private fun buildContentPolicy(obj: JSONObject): ContentPolicy {
        val cp = obj.optJSONObject("contentPolicy") ?: return ContentPolicy()
        val caps = mutableMapOf<String, Int>()
        cp.optJSONObject("socketCaps")?.let { capsObj ->
            if (capsObj.length() <= 64) {
                val ks = capsObj.keys()
                while (ks.hasNext()) {
                    val k = ks.next()
                    val v = (capsObj.opt(k) as? Number)?.toInt() ?: continue
                    val host = k.trim().lowercase().removePrefix("www.").trimEnd('.')
                    if (host.isNotBlank()) caps[host] = v.coerceIn(1, 16)
                }
            }
        }
        return ContentPolicy(
            socketCaps = caps,
            suppressNollywood = cp.optBoolean("suppressNollywood", false),
            nollywoodOnlySites = lowerTrimmedList(cp.optJSONArray("nollywoodOnlySites"), 64),
            nollywoodSites = lowerTrimmedList(cp.optJSONArray("nollywoodSites"), 64),
            nollywoodTerms = lowerTrimmedList(cp.optJSONArray("nollywoodTerms"), 128)
        )
    }

    private fun lowerTrimmedList(arr: org.json.JSONArray?, max: Int): List<String> =
        if (arr == null) emptyList()
        else (0 until minOf(arr.length(), max)).mapNotNull {
            arr.optString(it).trim().lowercase().ifBlank { null }
        }

    /** Lenient int read (authoring comfort like RulesFlags.parseMinApp): a
     *  JSON number or a numeric string, anything else yields [default]. The
     *  callers coerce the result into the section's validator range. */
    private fun optIntLenient(obj: JSONObject, key: String, default: Int): Int {
        if (!obj.has(key)) return default
        return when (val v = obj.opt(key)) {
            is Number -> v.toInt()
            is String -> v.trim().toIntOrNull() ?: default
            else -> default
        }
    }

    /** Closed vocabulary for announcement fields — the publish-side validator
     *  enforces the same sets; an unknown value here falls back to the safe
     *  default instead of shipping an unrenderable message. */
    private val ANNOUNCEMENT_MODES = setOf("dialog", "sheet", "banner")
    private val ANNOUNCEMENT_ACTIONS = setOf("none", "url", "update")

    /** Lenient parse of the optional top-level "announcements" array (bounded
     *  16 like the validator). Blank/absent => emptyList (feature OFF).
     *  Entries that are not objects, or carry no usable id (the oncePer
     *  store key), are skipped — never fatal, matching every other section.
     *  Unknown mode/action fall back to dialog/none; oncePer defaults to
     *  "id" (dismiss once, never re-shown unless the id changes). */
    private fun buildAnnouncements(obj: JSONObject): List<Announcement> {
        val arr = obj.optJSONArray("announcements") ?: return emptyList()
        val out = mutableListOf<Announcement>()
        for (i in 0 until arr.length()) {
            if (out.size >= 16) break
            val a = arr.optJSONObject(i) ?: continue
            val id = a.optString("id").trim()
            if (id.isBlank()) continue
            val mode = a.optString("mode").trim().lowercase()
                .let { if (it in ANNOUNCEMENT_MODES) it else "dialog" }
            val action = a.optString("action").trim().lowercase()
                .let { if (it in ANNOUNCEMENT_ACTIONS) it else "none" }
            val oncePer = if (a.optString("oncePer").trim().equals("rev", ignoreCase = true)) "rev" else "id"
            out.add(
                Announcement(
                    id = id,
                    title = a.optString("title").trim(),
                    body = a.optString("body").trim(),
                    mode = mode,
                    action = action,
                    actionLabel = a.optString("actionLabel").trim(),
                    actionUrl = a.optString("actionUrl").trim(),
                    oncePer = oncePer,
                    minApp = optIntLenient(a, "minApp", 0).coerceAtLeast(0),
                    expiresAtMs = a.optLong("expiresAtMs", 0L).coerceAtLeast(0L)
                )
            )
        }
        return out
    }

    /** Lenient parse of the optional top-level "update" object; null when
     *  absent (feature OFF). Sources keep payload order (the consumer walks
     *  it); SHA-256 pins are normalized to lowercase for the digest compare. */
    private fun buildUpdatePolicy(obj: JSONObject): UpdatePolicy? {
        val u = obj.optJSONObject("update") ?: return null
        val sources = mutableListOf<UpdateSource>()
        u.optJSONArray("sources")?.let { arr ->
            for (i in 0 until minOf(arr.length(), 4)) {
                val s = arr.optJSONObject(i) ?: continue
                val kind = s.optString("kind").trim().lowercase()
                val base = s.optString("base").trim()
                if (kind.isBlank() || base.isBlank()) continue
                sources.add(UpdateSource(kind, base))
            }
        }
        val force = u.optJSONObject("force")?.let { f ->
            UpdateForce(
                enabled = f.optBoolean("enabled", false),
                minSupportedVersionCode = optIntLenient(f, "minSupportedVersionCode", 0).coerceIn(0, 1_000_000),
                mode = f.optString("mode").trim().lowercase().let { if (it == "hard") "hard" else "soft" },
                snoozeHours = optIntLenient(f, "snoozeHours", 24).coerceIn(0, 168),
                escapeHatch = f.optBoolean("escapeHatch", true)
            )
        }
        return UpdatePolicy(
            latestVersionName = u.optString("latestVersionName").trim(),
            latestVersionCode = optIntLenient(u, "latestVersionCode", 0).coerceIn(0, 1_000_000),
            sources = sources,
            apkArm64 = u.optString("apkArm64").trim(),
            apkUniversal = u.optString("apkUniversal").trim(),
            sha256Arm64 = u.optString("sha256Arm64").trim().lowercase(),
            sha256Universal = u.optString("sha256Universal").trim().lowercase(),
            message = u.optString("message").trim(),
            force = force
        )
    }

    /** Lenient parse of the optional top-level "ads" object; null when absent
     *  (feature OFF). House texts are nested under "house" (§4.1); vendor
     *  credential fields sit flat and may be empty until registration day. */
    private fun buildAdsConfig(obj: JSONObject): AdsConfig? {
        val ads = obj.optJSONObject("ads") ?: return null
        val placements = ads.optJSONArray("placements")?.let { arr ->
            (0 until minOf(arr.length(), 8)).mapNotNull { i ->
                arr.optString(i).trim().ifBlank { null }
            }
        } ?: emptyList()
        val house = ads.optJSONObject("house")
        return AdsConfig(
            enabled = ads.optBoolean("enabled", false),
            provider = ads.optString("provider", "none").trim().lowercase().ifBlank { "none" },
            placements = placements,
            frequencyPerDay = optIntLenient(ads, "frequencyPerDay", 0).coerceIn(0, 20),
            houseTitle = house?.optString("title")?.trim() ?: "",
            houseBody = house?.optString("body")?.trim() ?: "",
            houseActionLabel = house?.optString("actionLabel")?.trim() ?: "",
            houseActionUrl = house?.optString("actionUrl")?.trim() ?: "",
            houseId = house?.optString("id")?.trim() ?: "",
            applovinSdkKey = ads.optString("applovinSdkKey").trim(),
            unityGameId = ads.optString("unityGameId").trim(),
            admobAppId = ads.optString("admobAppId").trim(),
            admobBannerUnitId = ads.optString("admobBannerUnitId").trim(),
            admobNativeUnitId = ads.optString("admobNativeUnitId").trim()
        )
    }

    /** Lenient parse of the optional top-level "engagement" object; null when
     *  absent (feature OFF). Windows default to the §4.1 example (7/14) and
     *  clamp into the validator range 1..365. */
    private fun buildEngagementConfig(obj: JSONObject): EngagementConfig? {
        val e = obj.optJSONObject("engagement") ?: return null
        return EngagementConfig(
            enabled = e.optBoolean("enabled", false),
            inactiveDays = optIntLenient(e, "inactiveDays", 7).coerceIn(1, 365),
            cooldownDays = optIntLenient(e, "cooldownDays", 14).coerceIn(1, 365),
            title = e.optString("title").trim(),
            body = e.optString("body").trim(),
            welcomeBack = e.optBoolean("welcomeBack", true)
        )
    }

    /** Lenient parse of the optional top-level "tamper" object; null when
     *  absent (integrity check disabled). The pin is normalized to lowercase
     *  hex — consumers compare against lowercase certificate digests. */
    private fun buildTamperPolicy(obj: JSONObject): TamperPolicy? {
        val t = obj.optJSONObject("tamper") ?: return null
        return TamperPolicy(
            expectedCertSha256 = t.optString("expectedCertSha256").trim().lowercase(),
            reportOnly = t.optBoolean("reportOnly", true)
        )
    }

    /** Lenient parse of the optional top-level "fcm" object; null when absent.
     *  Manual FirebaseOptions only — no google-services.json anywhere. */
    private fun buildFcmConfig(obj: JSONObject): FcmConfig? {
        val f = obj.optJSONObject("fcm") ?: return null
        return FcmConfig(
            // A complete OTA block without an explicit switch is ENABLED;
            // explicit enabled=false is the kill switch. Omission used to
            // silently disable FCM, which made a typo look like an outage.
            enabled = f.optBoolean("enabled", true),
            apiKey = f.optString("apiKey").trim(),
            appId = f.optString("appId").trim(),
            senderId = f.optString("senderId").trim(),
            projectId = f.optString("projectId").trim()
        )
    }

    // ---- P2-2: rev monotonicity + last-known-good ladder + sticky source ---
    // Application context for prefs persistence; captured in init() and
    // null-guarded everywhere so the JVM-test path (parseRulesJson called
    // without init) behaves exactly as before.
    @Volatile private var appContext: android.content.Context? = null

    private const val PREFS_NAME = "downloader_settings"
    private const val PREF_ACTIVE_REV = "ota_rules_active_rev"
    private const val PREF_LAST_SOURCE = "ota_rules_last_source"
    private const val PREV_CACHE_1 = "rules_prev_1.enc"
    private const val PREV_CACHE_2 = "rules_prev_2.enc"

    /** Highest rev ever ACTIVATED (never set by payloads without "rev").
     *  Prefs-persisted; 0 = none. CRLSet semantics: only a strictly newer rev
     *  may win — a stale CDN serving an old-but-signed payload is REJECTED. */
    @Volatile private var activeRev: Long = 0L

    /** Currently active payload rev, 0 when none (bundled rules / legacy
     *  payloads never set it). Surfaced for telemetry and diagnostics. */
    fun getActiveRev(): Long = activeRev

    private fun prefs(): android.content.SharedPreferences? = try {
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    } catch (_: Exception) { null }

    /** One rev-rejection telemetry event per session, max (stale CDNs retry
     *  the same stale body across all 4 sources — one record says enough). */
    private val revRejectReported = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Caps-WARN telemetry budget (P2-3): max 2 aggregate records per session. */
    private val capsWarnReported = java.util.concurrent.atomic.AtomicLong(0)

    // ---- P2-6: activation + delivery-chain telemetry -----------------------
    private const val PREF_ACTIVATION_REPORT_AT = "ota_activation_report_at"
    private const val PREF_ACTIVATION_REPORT_KEY = "ota_activation_report_key"
    private const val ACTIVATION_AGGREGATE_WINDOW_MS = 24 * 60 * 60_000L

    /** Per-source delivery counters (the plan's Attempt-record roll-up — the
     *  Attempt list itself has no read-back API, so the loop tallies at the
     *  same decision points; process-lifetime, in-memory). */
    private val sourceFailCount = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private val sourceSuccessCount = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    /** Exact-payload dedup so a restored cache (same version+rev) on every
     *  app start cannot re-report within the 24h window. */
    @Volatile private var lastActivationReportKey: String = ""

    /** P2-6 deferred activation report. Built by the atomic publish block
     *  (which doesn't yet know the winning chain source — lastSyncSource is
     *  set by the sync loop AFTER parse returns), flushed by the sync success
     *  path (or the bundled/cached restore path in init with a blank source).
     *  One-payload-lifetime: each publish overwrites it. */
    private class PendingActivation(
        val ver: String,
        val rev: Long?,
        val durationMs: Long,
        val applied: Map<String, Int>,
        val skippedEntries: Int
    )

    private val rulesLock = Any()
    @Volatile private var pendingActivationReport: PendingActivation? = null

    private fun flushActivationReport() = synchronized(rulesLock) {
        val pending = pendingActivationReport ?: return@synchronized
        pendingActivationReport = null
        reportActivation(pending.ver, pending.rev, pending.durationMs, pending.applied, pending.skippedEntries, lastSyncSource)
    }

    private fun countSourceAttempt(label: String, success: Boolean) {
        try {
            val map = if (success) sourceSuccessCount else sourceFailCount
            map.getOrPut(label) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
        } catch (_: Exception) {}
    }

    /** "label=N | ..." roll-up over one counter map (sorted for stable diffs). */
    private fun tallyCounts(map: java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>): String {
        return try {
            map.entries.sortedBy { it.key }
                .joinToString("|") { "${it.key}=${it.value.get()}" }
                .ifBlank { "none" }
        } catch (_: Exception) { "none" }
    }

    /** The single activation record (plan P2-6): rev, version, winning
     *  source, duration, applied/skipped family counts, chain roll-up.
     *  [source] is passed IN (flushActivationReport) because the winning
     *  chain source is only known after parse returns. Dedup: exact payload
     *  within a session; 1/24h aggregate per payload across sessions (prefs).
     *  Sink-gated; never throws. */
    private fun reportActivation(ver: String, rev: Long?, durationMs: Long, applied: Map<String, Int>, skippedEntries: Int, source: String) {
        try {
            val identity = "$ver/${rev ?: 0L}"
            if (lastActivationReportKey == identity) return // same payload, this session
            lastActivationReportKey = identity

            val store = prefs()
            val now = System.currentTimeMillis()
            if (store != null) {
                if (store.getString(PREF_ACTIVATION_REPORT_KEY, "") == identity &&
                    now - store.getLong(PREF_ACTIVATION_REPORT_AT, 0L) < ACTIVATION_AGGREGATE_WINDOW_MS
                ) return // 1/24h aggregate cap for the same payload
            }
            if (getRemoteLoggingUrl().isBlank()) return

            com.anonrode.downloader.util.RemoteLogger.report(
                type = "ota_activation",
                message = "OTA rules activated: $ver (rev ${rev ?: "none"}) via ${source.ifBlank { "bundled-or-cached" }}",
                context = mapOf(
                    "version" to ver,
                    "rev" to (rev ?: 0L),
                    "source" to source.ifBlank { "bundled-or-cached" },
                    "duration_ms" to durationMs,
                    "applied_families" to applied.entries.joinToString("|") { "${it.key}=${it.value}" },
                    "skipped_entries" to skippedEntries,
                    "chain_success" to tallyCounts(sourceSuccessCount),
                    "chain_fail" to tallyCounts(sourceFailCount),
                    "app_version" to com.anonrode.downloader.BuildConfig.VERSION_NAME
                )
            )
            try {
                store?.edit()
                    ?.putLong(PREF_ACTIVATION_REPORT_AT, now)
                    ?.putString(PREF_ACTIVATION_REPORT_KEY, identity)
                    ?.apply()
            } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    /** Aggregate telemetry for load-time caps violations (WARN phase). One
     *  record carries the count + first samples; billed against the session
     *  cap above so a poisoned payload can't stream reports. Sink-gated. */
    private fun reportCapsWarn(violations: List<String>) {
        try {
            val n = capsWarnReported.incrementAndGet()
            if (n > 2) return
            if (getRemoteLoggingUrl().isBlank()) return
            com.anonrode.downloader.util.RemoteLogger.report(
                type = "ota_caps_warn",
                message = "load-time caps violations: ${violations.size} (WARN phase — payload still applied)",
                context = mapOf(
                    "count" to violations.size,
                    "samples" to violations.take(8).joinToString(" | "),
                    "active_version" to _version.value,
                    "active_rev" to activeRev,
                    "app_version" to com.anonrode.downloader.BuildConfig.VERSION_NAME
                )
            )
        } catch (_: Exception) {}
    }

    @Volatile
    var tokenTtlMinutes: Long = 10
        private set

    private val _version = MutableStateFlow("2026.08.22.1 (Bundled)")
    val version: StateFlow<String> = _version.asStateFlow()

    fun init(context: Context) {
        try {
            appContext = context.applicationContext
            // ShadowRunner sampler prefs home (P2-4).
            com.anonrode.downloader.pipeline.ShadowRunner.attach(context)
            // NoSourceLedger prefs home (search-hiding ledger; cleared per
            // rules activation like every other derived state).
            com.anonrode.downloader.pipeline.NoSourceLedger.attach(context)
            // Persisted rev survives process death: a stale-CDN payload must
            // still lose against the rev this install is already past.
            activeRev = prefs()?.getLong(PREF_ACTIVE_REV, 0L) ?: 0L
        } catch (_: Exception) {}
        try {
            val dir = context.filesDir
            // F3 (OTA red-team): a crash-truncated primary cache must not drop
            // the device to the bundled floor when a VERIFIED prev payload
            // survives — walk the restore ladder (primary → prev1 → prev2),
            // first parse wins. Restore is never rev-gated (bedrock), and a
            // ladder entry that fails to decrypt is skipped, not trusted.
            for (name in listOf(CACHE_FILE, PREV_CACHE_1, PREV_CACHE_2)) {
                val f = File(dir, name)
                if (!f.exists()) continue
                val raw = try { f.readText() } catch (_: Exception) { null } ?: continue
                if (raw.isBlank()) continue
                // Primary cache may predate encryption (legacy plaintext);
                // prev-ladder entries are always verified envelopes.
                val decrypted = try { decryptRules(raw) } catch (_: Exception) { null }
                val isEnvelope = raw.trim().let { it.startsWith("{") && it.contains("\"payload\"") }
                val plain = if (name == CACHE_FILE) {
                    decrypted ?: if (!isEnvelope && raw.trim().startsWith("{")) raw else null
                } else {
                    decrypted
                } ?: continue
                if (parseRulesJson(plain)) {
                    // P2-6: cache-restore activation flushes with its blank
                    // source ("bundled-or-cached"); fresh installs with no
                    // cache simply arm nothing (the bundled floor is not an
                    // activation).
                    flushActivationReport()
                    break
                }
            }
        } catch (_: Exception) {}
    }

    fun getBaseUrl(site: String): String {
        return activeDomains[site.lowercase()] ?: defaultDomains[site.lowercase()] ?: ""
    }

    /** Primary + mirror hosts for a site, in failover order. Providers that
     *  can retry (e.g. nkiri's original IP is ISP-blocked on some networks
     *  while its Cloudflare mirror works) iterate this list. */
    fun getBaseUrls(site: String): List<String> {
        val key = site.lowercase()
        val primary = getBaseUrl(key)
        val mirrors = activeMirrors[key] ?: emptyList()
        return listOf(primary) + mirrors.filter { it.isNotBlank() && it != primary }
    }

    fun getSiteConfig(site: String): SiteRuleConfig? {
        return activeSiteConfigs[site.lowercase()]
    }

    fun getResolverConfig(resolverName: String): JSONObject? {
        return activeResolverConfigs[resolverName.lowercase()]
    }

    fun getDirectMediaExtensions(): List<String> {
        return if (activeMediaExtensions.isNotEmpty()) activeMediaExtensions else defaultMediaExtensions
    }

    fun getDynamicProviders(): List<GenericDeclarativeProvider> = dynamicProviders

    /** Resolve the referer for a CDN/media URL: OTA hostPolicies first
     *  (ordered, first match wins), then the built-in defaults. "none" and
     *  unmatched hosts yield "" (no referer header). This is now the SINGLE
     *  source of referer truth — the old per-file hardcoded maps retire. */
    fun resolveReferer(url: String): String {
        val low = url.lowercase()
        val rule = (activeHostPolicies.asSequence() + DEFAULT_HOST_POLICIES.asSequence())
            .firstOrNull { low.contains(it.match) }
            ?: return ""
        return when {
            rule.referer.equals("none", ignoreCase = true) -> ""
            rule.referer.startsWith("exact:", ignoreCase = true) -> rule.referer.removePrefix("exact:")
            else -> "" // "site" or unknown directive — no context here, send nothing
        }
    }

    /** Resolve the user-agent for a CDN/media URL: OTA hostPolicies first,
     *  then built-in defaults. Blank string if unspecified. */
    fun resolveUserAgent(url: String): String {
        val low = url.lowercase()
        val rule = (activeHostPolicies.asSequence() + DEFAULT_HOST_POLICIES.asSequence())
            .firstOrNull { low.contains(it.match) }
            ?: return ""
        return rule.ua.trim()
    }

    /** True when the playbook flags this host fragment as known-dead, so the
     *  app can skip it without burning a request. */
    fun isKnownDead(hostOrUrl: String): Boolean {
        val low = hostOrUrl.lowercase()
        return activeKnownDead.any { low.contains(it) }
    }

    fun getUrlTemplate(site: String): String =
        activeUrlTemplates[site.lowercase()] ?: ""

    fun getLockerHosts(): List<String> =
        activeLockerHosts.ifEmpty { emptyList() }

    /** OTA-configured client-error sink for RemoteLogger; "" = disabled. */
    fun getRemoteLoggingUrl(): String = remoteLoggingUrl

    /** Active OTA content policy: per-host socket-cap overlay + content-policy
     *  fields. Never throws; an absent section returns compiled defaults. */
    fun getContentPolicy(): ContentPolicy =
        try { activeContentPolicy } catch (_: Exception) { ContentPolicy() }

    /** Active OTA announcements in payload order. Never throws; an absent
     *  section (or a family held back by its flag) yields an empty list. */
    fun getAnnouncements(): List<Announcement> =
        try { activeAnnouncements } catch (_: Exception) { emptyList() }

    /** Active OTA update policy, or null when the payload carried none
     *  (feature OFF — the update flow is inert). Never throws. */
    fun getUpdatePolicy(): UpdatePolicy? =
        try { activeUpdatePolicy } catch (_: Exception) { null }

    /** Active OTA ads readiness config, or null when absent. Never throws. */
    fun getAdsConfig(): AdsConfig? =
        try { activeAdsConfig } catch (_: Exception) { null }

    /** Active OTA re-engagement config, or null when absent. Never throws. */
    fun getEngagementConfig(): EngagementConfig? =
        try { activeEngagementConfig } catch (_: Exception) { null }

    /** Active runtime-integrity cert pin, or null when absent (check
     *  disabled). Never throws. */
    fun getTamperPolicy(): TamperPolicy? =
        try { activeTamperPolicy } catch (_: Exception) { null }

    /** Active FCM runtime config, or null when absent. Never throws. */
    fun getFcmConfig(): FcmConfig? =
        try { activeFcmConfig } catch (_: Exception) { null }

    /** AES-128 key for RemoteLogger's encrypted report envelopes: the SAME
     *  hex key that obfuscates the OTA rules payload (RULES_KEY_HEX, mirror
     *  of scripts/encrypt_rules.py). One secret to hold: whoever can read
     *  scraper_rules payloads can read telemetry reports, and nobody else —
     *  the server sink, by design, cannot. */
    internal fun telemetryCipherKey(): ByteArray = hexToBytes(RULES_KEY_HEX)

    fun getTokenTtlMs(): Long = tokenTtlMinutes * 60_000L

    /** Ordered OTA search strategies for a site (urlTemplate / rss / json /
     *  slugGuess), empty when the playbook declares none. */
    fun getSearchStrategies(site: String): List<JSONObject> =
        activeSearchStrategies[site.lowercase()] ?: emptyList()

    /** Declarative step pipeline for a site's search/episodes, or null when
     *  the playbook declares none (or the entry used an unknown schema
     *  version and was ignored). Callers fall back to compiled logic. */
    fun getPipeline(site: String): SitePipeline? =
        activePipelines[site.lowercase()]

    /** Look up a pipeline key matching the host directly. */
    fun findPipelineKey(host: String): String? {
        val cleanHost = host.lowercase().removePrefix("www.")
        val direct = activePipelines[cleanHost]
        if (direct != null) return cleanHost
        return null
    }

    /**
     * Ordered multi-source rules sync (Phase 0 delivery chain). Fetches the
     * signed payload from [RULES_SOURCES] in order; the first source whose
     * payload decrypts, verifies, and parses wins. The cached last-known-good
     * .enc is written ONLY after full verification of a fresh payload — a
     * total delivery failure can NEVER destroy it (same guarantee as before
     * the chain: the file write was already gated on decrypt+parse).
     *
     * The returned message names the failed STAGE of the last attempt so the
     * Settings toast reads e.g. "Sync error: Could not reach GitHub (DNS) —
     * 4 sources tried, cached logic kept" instead of an opaque generic line.
     * Every attempt is journaled via OtaFetchDiagnostics; a total failure
     * additionally reports one encrypted aggregate event to the client-error
     * telemetry sink.
     */
    private val syncMutex = Mutex()

    suspend fun syncFromGitHub(context: Context): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        // F1 (OTA red-team): startup auto-sync and a manual Settings sync can
        // race — serialize whole sync attempts so two chains can never
        // interleave a parse/publish. Only syncs serialize; callers unaffected.
        syncMutex.withLock { syncFromGitHubLocked(context) }
    }

    private suspend fun syncFromGitHubLocked(context: Context): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
        val totalStarted = System.currentTimeMillis()
        val attempts = mutableListOf<OtaFetchDiagnostics.Attempt>()
        var lastStage = OtaFetchDiagnostics.STAGE_UNKNOWN
        var lastHttpCode: Int? = null
        var lastErrorClass = ""
        var lastDetail = ""

        // Sticky source ordering (P2-2, uBO origin+CDN model §1.1): the source
        // that served the LAST successful sync is tried first this time, then
        // the canonical order. Receipts (share-31, carrier DNS poisoning) show
        // hosts fail independently and stay reachable once known-good — never
        // re-punish ourselves by starting from raw.githubusercontent every
        // sync. A dead sticky costs exactly one failed attempt before the
        // canonical chain resumes (its failure is journaled like any other).
        val stickyLabel = try {
            prefs()?.getString(PREF_LAST_SOURCE, null)
        } catch (_: Exception) { null }
        val stickySource = stickyLabel?.let { label -> RULES_SOURCES.firstOrNull { it.label == label } }
        val orderedSources = if (stickySource != null) {
            listOf(stickySource) + (RULES_SOURCES - stickySource)
        } else RULES_SOURCES

        for (source in orderedSources) {
            // Append cache-buster timestamp to bypass the GitHub CDN's 5-minute
            // cache (and any future CDN edge cache on the mirrors).
            val cacheBustedUrl = "${source.url}?t=${System.currentTimeMillis()}"
            val startedAt = System.currentTimeMillis()
            var failure: Throwable? = null
            val body = try {
                HttpClient.getText(
                    cacheBustedUrl,
                    tag = "ota_rules",
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
                lastStage = stage
                lastHttpCode = e?.let { OtaFetchDiagnostics.httpCodeOf(it) }
                lastErrorClass = e?.javaClass?.simpleName ?: "null-body"
                lastDetail = e?.message ?: "empty response body"
                countSourceAttempt(source.label, false)
                attempts.add(
                    OtaFetchDiagnostics.Attempt(
                        source = source.label, url = cacheBustedUrl, stage = stage,
                        errorClass = lastErrorClass, detail = lastDetail, elapsedMs = elapsed,
                        httpCode = lastHttpCode
                    )
                )
                // DNS-stage failures get IP evidence: which addresses the
                // phone's pipeline actually produced for this host.
                if (stage == OtaFetchDiagnostics.STAGE_DNS) {
                    val (ips, doh) = OtaFetchDiagnostics.snapshotDns(source.host)
                    OtaFetchDiagnostics.noteAttempt(
                        source.label, cacheBustedUrl, stage, lastErrorClass,
                        "dns=[$ips] doh=$doh ${lastDetail}", elapsed, lastHttpCode
                    )
                } else {
                    OtaFetchDiagnostics.noteAttempt(
                        source.label, cacheBustedUrl, stage, lastErrorClass, lastDetail, elapsed, lastHttpCode
                    )
                }
                continue // next source in the chain — never end the sync early
            }

            // P2-2, uBO hardening ("consider an empty result to be an error",
            // "Not a text file", §1.1): CDNs answer soft failures with empty
            // bodies or HTML error pages. Sniffed BEFORE decrypt so a mirror's
            // interstitial burns a cheap content check instead of a
            // signature/decrypt failure that would read as tampering.
            val sniffProblem = payloadBodyProblem(body)
            if (sniffProblem != null) {
                countSourceAttempt(source.label, false)
                lastStage = OtaFetchDiagnostics.STAGE_PARSE
                lastHttpCode = null
                lastErrorClass = sniffProblem
                lastDetail = "non-payload body from source (first-byte sniff)"
                attempts.add(
                    OtaFetchDiagnostics.Attempt(
                        source = source.label, url = cacheBustedUrl, stage = lastStage,
                        errorClass = lastErrorClass, detail = lastDetail, elapsedMs = elapsed
                    )
                )
                OtaFetchDiagnostics.noteAttempt(source.label, cacheBustedUrl, lastStage, lastErrorClass, lastDetail, elapsed)
                continue
            }

            // Payload is base64(AES-CBC) / signed envelope — verify + decrypt
            // AFTER download, exactly as today (transport never trusted).
            val plain = decryptRules(body)
            if (plain == null) {
                countSourceAttempt(source.label, false)
                lastStage = OtaFetchDiagnostics.STAGE_VERIFY
                lastHttpCode = null
                lastErrorClass = "SignatureOrDecryptFailure"
                lastDetail = "payload failed signature/decrypt"
                attempts.add(
                    OtaFetchDiagnostics.Attempt(
                        source = source.label, url = cacheBustedUrl, stage = lastStage,
                        errorClass = lastErrorClass, detail = lastDetail, elapsedMs = elapsed
                    )
                )
                OtaFetchDiagnostics.noteAttempt(source.label, cacheBustedUrl, lastStage, lastErrorClass, lastDetail, elapsed)
                continue
            }
            // otaFetch = true: an OTA-fetched payload passes the CRLSet-style
            // monotonic rev gate (P2-2). A stale rev rejects THIS source but
            // the chain keeps trying the others — a slow CDN is skipped, a
            // fresh mirror still delivers.
            if (!parseRulesJson(plain, otaFetch = true, syncElapsedMs = System.currentTimeMillis() - totalStarted)) {
                countSourceAttempt(source.label, false)
                lastStage = OtaFetchDiagnostics.STAGE_PARSE
                lastHttpCode = null
                lastErrorClass = "ParseFailure"
                lastDetail = "payload failed to parse"
                attempts.add(
                    OtaFetchDiagnostics.Attempt(
                        source = source.label, url = cacheBustedUrl, stage = lastStage,
                        errorClass = lastErrorClass, detail = lastDetail, elapsedMs = elapsed
                    )
                )
                OtaFetchDiagnostics.noteAttempt(source.label, cacheBustedUrl, lastStage, lastErrorClass, lastDetail, elapsed)
                continue
            }

            // Verified: cache stays encrypted, byte-identical to what was
            // served. P2-2: the previous cache (also raw verified bytes) is
            // kept TWO deep on disk (rules_prev_1/2.enc, the restore ladder —
            // CRLSet/Remote-Settings "prefer verified-old" semantics §1.3/§1.4)
            // and rotated only when a genuinely NEW body arrives, so re-syncs
            // of the same rev never churn the ladder.
            try {
                val file = File(context.filesDir, CACHE_FILE)
                val prev1 = File(context.filesDir, PREV_CACHE_1)
                val prev2 = File(context.filesDir, PREV_CACHE_2)
                val existing = if (file.exists()) try { file.readText() } catch (_: Exception) { null } else null
                if (existing == null || existing != body) {
                    if (prev1.exists()) prev1.copyTo(prev2, overwrite = true)
                    if (file.exists()) file.copyTo(prev1, overwrite = true)
                    atomicWrite(file, body)
                }
                try {
                    prefs()?.edit()?.putString(PREF_LAST_SOURCE, source.label)?.apply()
                } catch (_: Exception) {}
            } catch (_: Exception) {
                // Cache bookkeeping must never fail an otherwise-verified sync;
                // the pre-P2 chain threw here only on disk errors, preserved.
            }
            lastSyncSource = source.label
            countSourceAttempt(source.label, true)
            // P2-6: winning source is now known — emit the pending activation
            // record (dedup/caps apply inside).
            flushActivationReport()
            OtaFetchDiagnostics.reportSuccess(
                "rules", source.label, _version.value,
                System.currentTimeMillis() - totalStarted, attempts.size + 1
            )
            return@withContext Pair(true, "${_version.value} (via ${source.label})")
        }

        // Total failure: record + telemetry, keep last-known-good state.
        val totalMs = System.currentTimeMillis() - totalStarted
        OtaFetchDiagnostics.reportTotalFailure(
            kind = "GitHub rules repo",
            attempts = attempts,
            totalMs = totalMs,
            dnsEvidenceHost = RULES_SOURCES.first().host
        )
        val stageDesc = OtaFetchDiagnostics.friendlyStage(lastStage, lastHttpCode)
        Pair(
            false,
            "Could not reach GitHub ($stageDesc) — ${RULES_SOURCES.size} sources tried, cached logic kept"
        )
        } catch (t: Throwable) {
            // Belt-and-braces: an unexpected local error (e.g. a cache-file IO
            // failure) must surface as a failure Pair exactly like the
            // pre-chain version, never as a throw into the Settings coroutine.
            Pair(false, "Failed to sync rules: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** F3 (OTA red-team): write-then-rename so a crash mid-write can never
     *  leave a truncated last-known-good cache behind (rename-on-same-volume
     *  is atomic; the tmp file is cleaned up on failure). */
    private fun atomicWrite(file: File, body: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.writeText(body)
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) tmp.delete()
            }
        } catch (_: Exception) {
            try { tmp.delete() } catch (_: Exception) {}
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /** Optional payload "rev": JSON integer (or integer string — authoring
     *  comfort, publish-side uses ints). null when absent or not a number; a
     *  non-positive rev is treated as absent by the caller (publish-side
     *  validator only ever ships positive ints — additive-only tolerance). */
    private fun parseRev(obj: JSONObject): Long? {
        if (!obj.has("rev")) return null
        return when (val v = obj.opt("rev")) {
            is Number -> v.toLong()
            is String -> v.trim().toLongOrNull()
            else -> null
        }
    }

    /** P2-2 first-byte sniff (uBO "consider an empty result to be an error" /
     *  "Not a text file", §1.1): a mirror that answers with an empty body, an
     *  HTML error/interstitial page, or a truncated fragment is a DELIVERY
     *  failure, not a signature failure — classed before decrypt. Pure,
     *  lenient: the v2 JSON envelope starts with '{' and base64 bodies start
     *  with [A-Za-z0-9], so anything beginning with '<' (or a body shorter
     *  than any real envelope) is shown the door. Returns an error-class
     *  string, or null when plausible. */
    private fun payloadBodyProblem(body: String): String? {
        val t = body.trim()
        if (t.isEmpty()) return "empty-body"
        if (t.length < 64) return "body-too-short"
        val head = t.take(256).lowercase()
        if (head.startsWith("<") || head.contains("<!doctype") || head.contains("<html")) return "html-body"
        return null
    }

    /** Telemetry for a rev rejection (plan P2-2: "DebugLog.engine + telemetry").
     *  Once per session — the same stale CDN body typically fails all 4 chain
     *  sources in one sync and one record is enough. Sink-gated like every
     *  RemoteLogger call; never throws. */
    private fun reportRevRejection(staleRev: Long) {
        try {
            if (!revRejectReported.compareAndSet(false, true)) return
            if (getRemoteLoggingUrl().isBlank()) return
            com.anonrode.downloader.util.RemoteLogger.report(
                type = "ota_rev_rejected",
                message = "stale OTA payload rejected: rev=$staleRev <= active=$activeRev",
                context = mapOf(
                    "stale_rev" to staleRev,
                    "active_rev" to activeRev,
                    "active_version" to _version.value,
                    "last_source" to lastSyncSource,
                    "app_version" to com.anonrode.downloader.BuildConfig.VERSION_NAME
                )
            )
        } catch (_: Exception) {}
    }

    // ---- P2-7: activation diff ---------------------------------------------

    /**
     * The set of host targets whose policy changed between the previously
     * active collections and the new ones [HostHealth.clearBackoffsFor] should
     * release. Families compared: knownDead, lockerHosts, hostPolicies
     * (per-match fragment), pipelines (per-site structural signature).
     * A member of the old set MISSING from the new one is changed by
     * definition — removal (confirmed fix) clears immediately; a purely
     * re-ordered but content-identical list is NOT changed (compare keyed
     * signatures, not positions). Pure.
     */
    private fun computeChangedHosts(
        oldKnownDead: List<String>,
        oldLockerHosts: List<String>,
        oldHostPolicies: List<HostPolicyRule>,
        oldPipelines: Map<String, SitePipeline>,
        newKnownDead: List<String>,
        newLockerHosts: List<String>,
        newHostPolicies: List<HostPolicyRule>,
        newPipelines: Map<String, SitePipeline>
    ): Set<String> {
        val changed = HashSet<String>()
        try {
            // Member-set families: any leave or arrival is a change.
            diffSets(oldKnownDead.toSet(), newKnownDead.toSet(), changed)
            diffSets(oldLockerHosts.toSet(), newLockerHosts.toSet(), changed)

            // hostPolicies: first-match-wins per `match` fragment — a changed
            // referer/ua (or a removed/added rule) invalidates that fragment's
            // targets; equality uses the data class (agents are value fields).
            val oldPol = oldHostPolicies.associateBy { it.match }
            val newPol = newHostPolicies.associateBy { it.match }
            for (m in oldPol.keys + newPol.keys) {
                if (oldPol[m] != newPol[m]) changed.add(m)
            }

            // Pipelines: per-site structural signature (the executed fetches +
            // bindings + terminal governance), NOT object identity — every
            // parse builds fresh JSONObject items, so identity would flag the
            // whole family changed on every activation of identical content.
            val keys = oldPipelines.keys + newPipelines.keys
            for (k in keys) {
                val oldSig = oldPipelines[k]?.let { pipelineSignature(it) } ?: ""
                val newSig = newPipelines[k]?.let { pipelineSignature(it) } ?: ""
                if (oldSig != newSig) {
                    changed.add(k)
                    oldPipelines[k]?.terminal?.hosts?.let { changed.addAll(it) }
                    newPipelines[k]?.terminal?.hosts?.let { changed.addAll(it) }
                }
            }
        } catch (_: Exception) {
            // Diff computed by hand: on any surprise return everything, so
            // the invalidation degrades to the coarse-but-correct behavior.
            (oldKnownDead + oldLockerHosts + newKnownDead + newLockerHosts).forEach { changed.add(it) }
        }
        changed.removeAll { it.isBlank() }
        return changed
    }

    private fun diffSets(old: Set<String>, new: Set<String>, out: MutableSet<String>) {
        for (x in old) if (!new.contains(x)) out.add(x)
        for (x in new) if (!old.contains(x)) out.add(x)
    }

    /** Comparable text signature of a compiled pipeline: what it fetches
     *  (sources/urls/modes/delays/dead-patterns), how it binds (names/specs),
     *  what it extracts (items spec text), and its terminal governance.
     *  Deliberately NOT the JSONObject's runtime identity. */
    private fun pipelineSignature(sp: SitePipeline): String {
        val sb = StringBuilder()
        for (p in listOf(sp.search, sp.episodes, sp.resolve)) {
            if (p == null) {
                sb.append("∅")
            } else {
                for (st in p.steps) {
                    sb.append('[').append(st.mode).append('/').append(st.asFormat)
                        .append('/').append(st.delayMs).append('/').append(st.deadPatterns).append(']')
                    for (s in st.sources) {
                        sb.append('(').append(s.method).append(' ').append(s.url)
                            .append(" h").append(s.headers.size).append(" f").append(s.form.size).append(')')
                    }
                    for (b in st.bind) {
                        sb.append('<').append(b.name).append('=').append(b.sourceVar)
                            .append(b.regex).append(b.jsonPath).append(b.selector).append(b.attr)
                            .append(b.header).append(b.cookie).append(b.unpackJs)
                            .append(b.powSha256 != null).append(b.transforms).append('>')
                    }
                    st.items?.let { sb.append('{').append(it.toString()).append('}') }
                }
                for (b in p.urlBinds) {
                    sb.append('u').append(b.name).append('/').append(b.regex)
                        .append('/').append(b.group).append('/').append(b.decode)
                }
            }
            sb.append('#')
        }
        val t = sp.terminal
        if (t != null) {
            sb.append("T").append(t.source).append(t.regex).append(t.group).append(t.spec)
                .append(t.hosts).append(t.mode).append(t.referer).append(t.decode)
                .append(t.stripQueryParams)
        }
        return sb.toString()
    }

    /** Decrypts the OTA payload -> JSON text; null on any failure.
     *
     *  Accepts only SIGNED envelopes:
     *   - v2 envelope JSON {"v":2,"iv":..,"payload":<b64>,"sig":<b64>} —
     *     signature verified BEFORE decryption; v2 without a signature or
     *     with a bad one is refused outright.
     *   - v2 envelope + optional "chain" object (step 7 signing rotation,
     *     docs/SIGNING_ROTATION.md): "sig" is then by a LEAF key delivered in
     *     chain.leafPubB64, which must first be vouched for by a PINNED ROOT
     *     (dual-pin set root-1/root-2, "verify first that succeeds") over the
     *     canonical leaf bytes — see SignatureChain.verifyChainMode. A
     *     malformed or failing chain REJECTS outright: it never falls back to
     *     legacy verification and never accepts an unsigned payload.
     *  Legacy formats are refused — both are indistinguishable from a
     *  hijacked unsigned payload:
     *   - v1/versionless envelope {"v":1,"payload":<b64>} with no "sig": a
     *     repo hijack could push one and bypass signature verification.
     *   - bare base64(AES-CBC fixed-IV) from older payloads/caches: no
     *     version, no signature; callers must use the signed v2 envelope.
     *  java.util.Base64 keeps this JVM-unit-testable. */
    internal fun decryptRules(text: String): String? {
        return try {
            val t = text.trim()
            if (t.startsWith("{")) {
                val env = JSONObject(t)
                val payloadB64 = env.optString("payload")
                if (payloadB64.isBlank()) return null
                val sigB64 = env.optString("sig")
                val isV2 = env.optInt("v", 1) >= 2
                if (!isV2 && sigB64.isBlank()) {
                    // Legacy v1 envelope (explicit "v":"1" or versionless)
                    // with no signature: refused. Accepting it would let a
                    // repo hijack push {"v":1,"payload":...} and bypass
                    // signature verification entirely.
                    com.anonrode.downloader.util.DebugLog.error(
                        "OTA rules REJECTED: unsigned legacy envelope — callers must use v2 signed"
                    )
                    return null
                }
                if (isV2 || sigB64.isNotBlank()) {
                    // Envelope claims authenticity: verify BEFORE decrypting.
                    // Chain dispatch (step 7): the mere PRESENCE of "chain"
                    // selects chain mode EXCLUSIVELY — a vouched-leaf failure
                    // or a malformed chain is a hard reject with the same
                    // semantics as any other signature failure (REJECT the
                    // payload, keep current state; the sync ladder continues
                    // with the next source / last-known-good).
                    if (sigB64.isBlank() || !verifyEnvelopeSignature(env, payloadB64, sigB64)) {
                        com.anonrode.downloader.util.DebugLog.error(
                            "OTA rules REJECTED: signature missing/invalid"
                        )
                        return null
                    }
                }
                val ivHex = env.optString("iv").ifBlank { RULES_IV_HEX }
                aesDecrypt(java.util.Base64.getDecoder().decode(payloadB64), IvParameterSpec(hexToBytes(ivHex)))
            } else {
                // Bare base64 (legacy pre-envelope format): no version, no
                // signature — a hijacked payload could arrive in exactly this
                // shape, so it is refused outright.
                com.anonrode.downloader.util.DebugLog.error(
                    "OTA rules REJECTED: legacy unsigned payload — callers must use v2 signed"
                )
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun aesDecrypt(data: ByteArray, iv: IvParameterSpec): String {
        val key = SecretKeySpec(hexToBytes(RULES_KEY_HEX), "AES")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key, iv)
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }

    /**
     * Signature dispatch for one v2 envelope (step 7 signing rotation). The
     * PRESENCE of the "chain" key selects chain mode EXCLUSIVELY (pinned-root
     * vouch for the delivered leaf, then the leaf's payload signature); every
     * other envelope verifies in legacy mode against the dual-pin set. The
     * pure crypto policy lives in [SignatureChain] (pure file, JVM-testable);
     * this function only reads the envelope field and supplies the pins.
     *
     * Fail-closed rules (a chain envelope is a chain or it is REJECTED —
     * never a silent fallback, never an unsigned acceptance):
     *  - "chain" present but not an object -> false.
     *  - chain missing leafPubB64/rootSig/alg, or alg != "P256-SHA256" -> false.
     *  - legacy mode: first pinned root that verifies wins; none -> false.
     * Keeps the envelope-verification failure semantics of every caller:
     * reject THIS payload, keep the current rules state, climb the ladder.
     */
    private fun verifyEnvelopeSignature(env: JSONObject, payloadB64: String, sigB64: String): Boolean {
        val pins = signingRootPins()
        if (env.has("chain")) {
            val chain = env.optJSONObject("chain") ?: return false
            return SignatureChain.verifyChainMode(
                payloadB64 = payloadB64,
                sigB64 = sigB64,
                leafPubB64 = chain.optString("leafPubB64"),
                rootSigB64 = chain.optString("rootSig"),
                chainAlg = chain.optString("alg"),
                rootPinsB64 = pins
            )
        }
        return SignatureChain.verifyLegacyMode(payloadB64, sigB64, pins)
    }

    /** ECDSA-P256-SHA256 over the ASCII payload string against the pinned
     *  root set ("verify first that succeeds" — legacy envelope shape).
     *  Delegates to [SignatureChain.verifyLegacyMode]; testable directly. */
    internal fun verifySignature(payloadB64: String, sigB64: String): Boolean =
        SignatureChain.verifyLegacyMode(payloadB64, sigB64, signingRootPins())

    /** TEST-ONLY payload injection for the instrumented UI screenshot run
     *  (androidTest cannot reach the internal [parseRulesJson]). Hard-gated
     *  to debug builds: a release build returns false and changes nothing. */
    fun debugApplyPayloadForTests(jsonStr: String): Boolean {
        if (!com.anonrode.downloader.BuildConfig.DEBUG) return false
        return parseRulesJson(jsonStr)
    }

    /** Returns false when the payload is malformed (or, for OTA-fetched
     *  payloads only, when its rev is not strictly newer than the active one);
     *  the previously active rules (bundled defaults on first run) stay in
     *  place untouched. internal so JVM unit tests can exercise the full parse
     *  path (default otaFetch=false keeps every existing caller behaving
     *  exactly as before — bundled/cached activation is never rev-gated). */
    internal fun parseRulesJson(jsonStr: String, otaFetch: Boolean = false, syncElapsedMs: Long = 0L): Boolean {
        return synchronized(rulesLock) {
            // Build-then-swap: every collection is populated locally and the
            // whole state is published at the end. Readers never observe a
            // half-applied playbook, a parse failure leaves the previous state
            // intact (the old clear-then-fill left an EMPTY state on failure),
            // and no live collection is mutated while download threads iterate
            // it mid-sync.
            val domains = mutableMapOf<String, String>()
            val mirrors = mutableMapOf<String, List<String>>()
            val siteConfigs = mutableMapOf<String, SiteRuleConfig>()
            val resolverConfigs = mutableMapOf<String, JSONObject>()
            val mediaExts = mutableListOf<String>()
            val hostPolicies = mutableListOf<HostPolicyRule>()
            val urlTemplates = mutableMapOf<String, String>()
            val knownDead = mutableListOf<String>()
            val lockerHosts = mutableListOf<String>()
            val searchStrategies = mutableMapOf<String, List<JSONObject>>()
            val dynProviders = mutableListOf<GenericDeclarativeProvider>()
            // P2-6 activation telemetry: applied/skipped entry counts (the
            // lenient parser's journals, counted).
            var skippedPipelines = 0
            var skippedDynProviders = 0
            try {
                val obj = JSONObject(jsonStr)
                if (obj.has("payload") && !obj.has("domains") && !obj.has("sites") && !obj.has("pipelines")) {
                    com.anonrode.downloader.util.DebugLog.error(
                        "OTA rules REJECTED: raw encrypted envelope passed to parseRulesJson without decryption"
                    )
                    return false
                }
                val ver = obj.optString("version", "2026.08.22.1")

            // P2-3: load-time validation caps — WARN phase this rev cycle
            // (plan canary rule): journal + count every violation, never reject.
            // The publish-side validator already enforces the same caps hard;
            // the client flips to enforce after a clean payload cycle.
            val capsViolations = RuleCaps.scan(jsonStr, obj)
            if (capsViolations.isNotEmpty()) {
                com.anonrode.downloader.util.DebugLog.engine(
                    "OTA rules[$ver] caps WARN: ${capsViolations.size} violation(s) — payload still applied (WARN phase)"
                )
                capsViolations.take(8).forEach { v ->
                    com.anonrode.downloader.util.DebugLog.engine("OTA rules[$ver] cap WARN: $v")
                }
                reportCapsWarn(capsViolations)
            }

            // Per-family flags (P2-1): lenient parse, journal every decision —
            // unknown family keys and sub-keys are skipped, never fatal.
            val familyFlags = RulesFlags.parse(obj.optJSONObject("flags")) { msg ->
                com.anonrode.downloader.util.DebugLog.engine("OTA rules[$ver] $msg")
            }

            // Fleet guard: a payload that declares it needs a newer app is
            // rejected wholesale and the previous state stays — an older
            // client can't run whatever the payload assumes. Purely additive
            // payloads leave minAppVersion unset so the installed fleet keeps
            // receiving domain/selector updates.
            val minApp = obj.optInt("minAppVersion", 0)
            if (minApp > 0 && minApp > com.anonrode.downloader.BuildConfig.VERSION_CODE) {
                com.anonrode.downloader.util.DebugLog.error(
                    "OTA rules REJECTED: payload requires app v$minApp, this is ${com.anonrode.downloader.BuildConfig.VERSION_CODE} — keeping previous rules"
                )
                return false
            }

            // P2-2 — CRLSet-style monotonic rev (prior art §1.3: "strictly
            // monotonically increasing sequence numbers"; Remote Settings CDN-
            // staleness guard "CDN served staled data, ignore", §1.4). Applies
            // ONLY to OTA-fetched payloads: the cached/bundled activation path
            // is the restore ladder and must never be bricked or discarded
            // because of a rev comparison (bedrock constraint 5). A rev-less
            // payload is legacy-tolerant — it passes and never moves the
            // activeRev pointer.
            val rev = parseRev(obj)
            if (otaFetch) {
                val r = rev
                if (r != null && activeRev > 0L && r in 1 until activeRev) {
                    com.anonrode.downloader.util.DebugLog.engine(
                        "OTA rules REJECTED: stale rev $r < active rev $activeRev (last source=${lastSyncSource.ifBlank { "none" }}) — keeping current rules"
                    )
                    reportRevRejection(r)
                    return false
                }
            }

            val domainsObj = obj.optJSONObject("domains")
            if (domainsObj != null) {
                val keys = domainsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val url = domainsObj.optString(k)
                    if (url.isNotBlank()) {
                        domains[k.lowercase()] = url
                    }
                }
            }

            val mirrorsObj = obj.optJSONObject("mirrors")
            if (mirrorsObj != null) {
                val keys = mirrorsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val arr = mirrorsObj.optJSONArray(k)
                    if (arr != null) {
                        val list = mutableListOf<String>()
                        for (i in 0 until arr.length()) {
                            val m = arr.optString(i)
                            if (m.isNotBlank()) list.add(m)
                        }
                        mirrors[k.lowercase()] = list
                    }
                }
            }

            val sitesObj = obj.optJSONObject("sites")
            if (sitesObj != null) {
                val keys = sitesObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val sObj = sitesObj.optJSONObject(k)
                    if (sObj != null) {
                        val descentList = mutableListOf<String>()
                        val descentArr = sObj.optJSONArray("seriesDescentSelectors")
                        if (descentArr != null) {
                            for (idx in 0 until descentArr.length()) {
                                val item = descentArr.optString(idx)
                                if (item.isNotBlank()) descentList.add(item)
                            }
                        }
                        val slugList = mutableListOf<String>()
                        val slugArr = sObj.optJSONArray("slugSuffixes")
                        if (slugArr != null) {
                            for (idx in 0 until slugArr.length()) {
                                slugList.add(slugArr.optString(idx))
                            }
                        }

                        siteConfigs[k.lowercase()] = SiteRuleConfig(
                            searchPattern = sObj.optString("searchPattern"),
                            searchType = sObj.optString("searchType", "html"),
                            cardSelector = sObj.optString("cardSelector"),
                            titleSelector = sObj.optString("titleSelector"),
                            linkSelector = sObj.optString("linkSelector"),
                            posterSelector = sObj.optString("posterSelector"),
                            episodeSelector = sObj.optString("episodeSelector"),
                            seriesDescentSelectors = descentList,
                            downloadAnchorSelector = sObj.optString("downloadAnchorSelector"),
                            slugSuffixes = slugList
                        )
                    }
                }
            }

            val resolversObj = obj.optJSONObject("resolvers")
            if (resolversObj != null) {
                val keys = resolversObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val rObj = resolversObj.optJSONObject(k)
                    if (rObj != null) {
                        resolverConfigs[k.lowercase()] = rObj
                    }
                }
            }

            val mediaExtArr = obj.optJSONArray("directMediaExtensions")
            if (mediaExtArr != null && mediaExtArr.length() > 0) {
                for (idx in 0 until mediaExtArr.length()) {
                    val ext = mediaExtArr.optString(idx)
                    if (ext.isNotBlank()) mediaExts.add(ext)
                }
            }

            // Playbook v2: ordered host policies (first match wins), url
            // templates, known-dead hosts, token TTL.
            val hpArr = obj.optJSONArray("hostPolicies")
            if (hpArr != null) {
                for (i in 0 until hpArr.length()) {
                    val p = hpArr.optJSONObject(i) ?: continue
                    val m = p.optString("match")
                    if (m.isBlank()) continue
                    hostPolicies.add(
                        HostPolicyRule(m.lowercase(), p.optString("referer"), p.optString("ua"))
                    )
                }
            }

            val utObj = obj.optJSONObject("urlTemplates")
            if (utObj != null) {
                val keys = utObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val tpl = utObj.optString(k)
                    if (tpl.isNotBlank()) urlTemplates[k.lowercase()] = tpl
                }
            }

            val kdArr = obj.optJSONArray("knownDead")
            if (kdArr != null) {
                for (i in 0 until kdArr.length()) {
                    val h = kdArr.optString(i)
                    if (h.isNotBlank()) knownDead.add(h.lowercase())
                }
            }

            // OTA locker host seeds: LockerRegistry.classify() consults these
            // first (prioritization), but never gates — unknown hosts still
            // get probed and can work on first contact.
            val lhArr = obj.optJSONArray("lockerHosts")
            if (lhArr != null) {
                for (i in 0 until lhArr.length()) {
                    val h = lhArr.optString(i)
                    if (h.isNotBlank()) lockerHosts.add(h.lowercase())
                }
            }

            // Remote error-log sink (Track 6): accepted only as a plausible
            // http(s) endpoint — anything else is treated as absent rather
            // than handed to URL.openConnection. Published with the rest of
            // the state in the atomic-swap block below.
            val remoteLogCandidate = obj.optString("remote_logging_url", "").trim()
            val remoteLogSink =
                if (remoteLogCandidate.startsWith("https://") || remoteLogCandidate.startsWith("http://")) {
                    remoteLogCandidate
                } else ""

            // OTA content/download policy (2026-10): socket-cap overlay (live
            // consumer: HostPolicyRegistry) + content-policy fields (schema-
            // ready). Lenient like every section; absent => compiled defaults.
            var contentPolicy = buildContentPolicy(obj)

            // v3.3.0 platform-surface sections (plan §4.1): announcements,
            // update, ads, engagement (flag-backed families, blanked below
            // when their flag holds them back) + tamper and fcm (self-gating
            // via their own enabled / presence flags). Lenient like every
            // section; absent => OFF.
            var announcements = buildAnnouncements(obj)
            var updatePolicy = buildUpdatePolicy(obj)
            var adsConfig = buildAdsConfig(obj)
            var engagementConfig = buildEngagementConfig(obj)
            val tamperPolicy = buildTamperPolicy(obj)
            val fcmConfig = buildFcmConfig(obj)

            val ttl = obj.optLong("tokenTtlMinutes", 10L).coerceIn(1L, 240L)

            // Ordered search strategy chains: when a site's primary search
            // breaks (dramarain ?s= lesson), OTA adds fallback strategies as
            // data — no APK rebuild.
            val ssObj = obj.optJSONObject("searchStrategies")
            if (ssObj != null) {
                val keys = ssObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val arr = ssObj.optJSONArray(k) ?: continue
                    val list = mutableListOf<JSONObject>()
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { list.add(it) }
                    }
                    if (list.isNotEmpty()) searchStrategies[k.lowercase()] = list
                }
            }

            // Declarative step pipelines (search/episode navigation as data).
            // Lenient per site: a malformed entry or an unknown schema version
            // is skipped and journaled, never fatal — that site's compiled
            // provider fallback covers it, and the rest of the payload still
            // applies. (dynamic_providers below follows the same lenient
            // contract after the 2026-09-13 fix; it used to throw.)
            val pipelines = mutableMapOf<String, SitePipeline>()
            val pipelinesObj = obj.optJSONObject("pipelines")
            if (pipelinesObj != null) {
                val keys = pipelinesObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val pObj = pipelinesObj.optJSONObject(k) ?: continue
                    val parsed = parseSitePipeline(pObj)
                    if (parsed != null) {
                        pipelines[k.lowercase()] = parsed
                    } else {
                        skippedPipelines++
                        com.anonrode.downloader.util.DebugLog.error(
                            "OTA rules: skipping pipeline for \"$k\" (malformed or unknown schema)"
                        )
                    }
                }
            }

            // Parse any dynamic new providers added remotely.
            // optJSONObject, not getJSONObject: a non-object entry here used
            // to throw JSONException and fail the WHOLE playbook parse — one
            // stray string in dynamic_providers poisoned every OTA update for
            // every site. Malformed entries are skipped, like everywhere else
            // in this parser; id/base_url are load-bearing (a provider
            // without them can never match a URL or build a search), so an
            // entry missing either is skipped too.
            val dynamicList = obj.optJSONArray("dynamic_providers")
            if (dynamicList != null) {
                for (i in 0 until dynamicList.length()) {
                    val item = dynamicList.optJSONObject(i) ?: continue
                    val cfg = DynamicSiteConfig(
                        id = item.optString("id"),
                        displayName = item.optString("display_name"),
                        category = item.optString("category", "Media"),
                        baseUrl = item.optString("base_url"),
                        searchUrlPattern = item.optString("search_url_pattern", "{base}/?s={query}"),
                        searchType = item.optString("search_type", "html"),
                        cardSelector = item.optString("card_selector", "article"),
                        titleSelector = item.optString("title_selector", "h2, .entry-title"),
                        linkSelector = item.optString("link_selector", "a"),
                        posterSelector = item.optString("poster_selector", "img"),
                        episodeLinkSelector = item.optString("episode_link_selector", "a[href*='download']")
                    )
                    if (cfg.id.isBlank() || cfg.baseUrl.isBlank()) {
                        skippedDynProviders++
                        continue
                    }
                    dynProviders.add(GenericDeclarativeProvider(cfg))
                }
            }

            // P2-1/P2-5 enforcement — no provider edits: a family the flags
            // hold back (kill switch off, build below the family's minApp
            // floor, shadow measurement mode, or this device's stable bucket
            // outside a partial rollout) publishes its section EMPTY,
            // byte-for-byte the "missing/malformed section" state every
            // compiled fallback already engages on. The knownDead/lockerHosts
            // note from the plan holds: emptying those only loosens
            // pre-filtering, it can never break a resolution. A payload with
            // no "flags" object leaves every family at DEFAULT (enabled,
            // percent 100) — flags expire with the payload that carried them.
            val deviceUuid = RulesFlags.installUuid(appContext)
            fun familyGateBlank(family: String, blank: () -> Unit) {
                val flag = familyFlags[family] ?: return
                if (RulesFlags.isFamilyActiveForDevice(flag, family, deviceUuid)) return
                val bucket = RulesFlags.stableBucket(deviceUuid, family)
                when {
                    !flag.enabled ->
                        com.anonrode.downloader.util.DebugLog.engine(
                            "OTA rules[$ver] family \"$family\" disabled by kill flag — publishing empty (compiled fallback engages)"
                        )
                    !flag.minAppSatisfied ->
                        com.anonrode.downloader.util.DebugLog.engine(
                            "OTA rules[$ver] family \"$family\" needs app ${flag.minAppVersion}; this is " +
                                com.anonrode.downloader.BuildConfig.VERSION_CODE + " — publishing empty (minApp guard)"
                        )
                    flag.shadow ->
                        com.anonrode.downloader.util.DebugLog.engine(
                            "OTA rules[$ver] family \"$family\" in shadow mode — publishing empty (compiled control serves users)"
                        )
                    else ->
                        com.anonrode.downloader.util.DebugLog.engine(
                            "OTA rules[$ver] family \"$family\" percent rollup ${flag.percent}: bucket $bucket not selected — publishing empty for this device"
                        )
                }
                blank()
            }
            familyGateBlank("domains") { domains.clear() }
            familyGateBlank("mirrors") { mirrors.clear() }
            familyGateBlank("sites") { siteConfigs.clear() }
            familyGateBlank("resolvers") { resolverConfigs.clear() }
            familyGateBlank("directMediaExtensions") { mediaExts.clear() }
            familyGateBlank("hostPolicies") { hostPolicies.clear() }
            familyGateBlank("urlTemplates") { urlTemplates.clear() }
            familyGateBlank("knownDead") { knownDead.clear() }
            familyGateBlank("lockerHosts") { lockerHosts.clear() }
            familyGateBlank("searchStrategies") { searchStrategies.clear() }
            familyGateBlank("contentPolicy") { contentPolicy = ContentPolicy() }
            // v3.3.0 platform surfaces: a held-back family publishes OFF —
            // exact same shape as an absent section, so every consumer's
            // null/empty check doubles as the kill-switch path.
            familyGateBlank("announcements") { announcements = emptyList() }
            familyGateBlank("update") { updatePolicy = null }
            familyGateBlank("ads") { adsConfig = null }
            familyGateBlank("engagement") { engagementConfig = null }

            // P2-4 SHADOW MODE for the behavior family (Scientist rule, plan
            // slice 4): when the payload flags `pipelines` shadow, the section
            // publishes EMPTY — every provider runs its compiled control and
            // the USER-FACING RESULT IS ALWAYS CONTROL — while the parsed
            // candidate set is handed to ShadowRunner for sampled, deferred,
            // never-user-facing measurement. Promotion/kill is all payload
            // data: shadow=false + percent buckets promotes; enabled=false
            // (or no flag at all) disarms via the updateCandidates(emptyMap).
            var shadowArmed = false
            var pipelinesShadowArmed = 0
            val pipelinesFlag = familyFlags["pipelines"]
            if (pipelinesFlag != null && pipelinesFlag.enabled && pipelinesFlag.minAppSatisfied &&
                pipelinesFlag.shadow && pipelines.isNotEmpty()
            ) {
                pipelinesShadowArmed = pipelines.size
                com.anonrode.downloader.pipeline.ShadowRunner.updateCandidates(pipelines.toMap(), pipelinesFlag)
                com.anonrode.downloader.util.DebugLog.engine(
                    "OTA rules[$ver] pipelines SHADOW mode: ${pipelines.size} candidate(s) armed; published section EMPTY (compiled control serves users)"
                )
                pipelines.clear()
                shadowArmed = true
            } else {
                familyGateBlank("pipelines") { pipelines.clear() }
            }
            if (!shadowArmed) {
                com.anonrode.downloader.pipeline.ShadowRunner.updateCandidates(emptyMap(), null)
            }

            familyGateBlank("dynamic_providers") { dynProviders.clear() }

            // P2-7 set-up: previous family target-hosts for the diff —
            // captured BEFORE the atomic swap so the old/new comparison is
            // exact. Empty collections = no rules were active yet (defaults).
            val prevKnownDead = activeKnownDead
            val prevLockerHosts = activeLockerHosts
            val prevHostPolicies = activeHostPolicies
            val prevPipelines = activePipelines

            // Atomic publish — readers see either the old playbook or the new
            // one, never a mix.
            activeDomains = domains
            activeMirrors = mirrors
            activeSiteConfigs = siteConfigs
            activeResolverConfigs = resolverConfigs
            activeMediaExtensions = mediaExts.ifEmpty { defaultMediaExtensions }
            activeHostPolicies = hostPolicies
            activeUrlTemplates = urlTemplates
            activeKnownDead = knownDead
            activeLockerHosts = lockerHosts
            activeSearchStrategies = searchStrategies
            activePipelines = pipelines
            dynamicProviders = dynProviders
            activeFlags = familyFlags
            activeContentPolicy = contentPolicy
            activeAnnouncements = announcements
            activeUpdatePolicy = updatePolicy
            activeAdsConfig = adsConfig
            activeEngagementConfig = engagementConfig
            activeTamperPolicy = tamperPolicy
            activeFcmConfig = fcmConfig
            remoteLoggingUrl = remoteLogSink
            tokenTtlMinutes = ttl
            // P2-2: a payload that carries a rev and survived the gate becomes
            // the new activation floor. Rev-less (legacy) payloads never move
            // the pointer — they must stay able to run on any install.
            val revNow = rev
            if (revNow != null && revNow > 0L) {
                // F11 (OTA red-team): the pointer is monotonic — a cache-
                // restore (otaFetch=false) must never REGRESS the persisted
                // floor to an older cached rev.
                val newFloor = maxOf(activeRev, revNow)
                if (newFloor != activeRev) {
                    activeRev = newFloor
                    try {
                        prefs()?.edit()?.putLong(PREF_ACTIVE_REV, newFloor)?.apply()
                    } catch (_: Exception) {}
                }
            }
            // P2-6: ONE activation telemetry record per activation — version,
            // rev, winning source, duration, applied/skipped family entry
            // counts and the delivery-chain per-source roll-up. Content-free
            // (no URLs beyond the well-known chain source labels, no payload
            // fragments). Built here, FLUSHED by the sync path once it knows
            // the winning source (or by init's bundled/cached restore with a
            // blank source). Deduped per version+rev AND capped 1/24h aggregate.
            pendingActivationReport = PendingActivation(
                ver = ver,
                rev = rev,
                durationMs = syncElapsedMs,
                applied = mapOf(
                    "domains" to domains.size,
                    "mirrors" to mirrors.size,
                    "sites" to siteConfigs.size,
                    "resolvers" to resolverConfigs.size,
                    "directMediaExtensions" to mediaExts.size,
                    "hostPolicies" to hostPolicies.size,
                    "urlTemplates" to urlTemplates.size,
                    "knownDead" to knownDead.size,
                    "lockerHosts" to lockerHosts.size,
                    "searchStrategies" to searchStrategies.size,
                    "pipelines" to pipelines.size,
                    "pipelines_shadow_armed" to pipelinesShadowArmed,
                    "dynamic_providers" to dynProviders.size
                ),
                skippedEntries = skippedPipelines + skippedDynProviders
            )
            // Rules-rev state invalidation (Remote Settings sync-event
            // semantics, §3.7). P2-7 upgrade: with a rev present the drop is
            // DIFF-SCOPED — only hosts whose knownDead/lockerHosts/hostPolicy/
            // pipeline entry changed or was removed get their stale backoff
            // verdict cleared, so a truly-dead host's live backoff survives
            // unrelated updates. Hosts REMOVED from knownDead/lockerHosts are
            // in the changed set by construction (the confirmed
            // "remembers-dead-after-fix" bug). Legacy payloads without a rev
            // cannot be diffed reliably, so they fall back to the existing
            // coarse whole-ledger clearing. Both paths drop only the judgment;
            // ok/fail history and 429 cooldowns stay.
            val prevVer = _version.value
            // F7 (OTA red-team): capture the pre-publish rev — a rev-ONLY bump
            // must invalidate stale verdicts too, not just version-string bumps.
            val prevActiveRev = activeRev
            // NoSourceLedger: new payload may add platforms — hidden titles
            // get re-judged against the new playbook.
            com.anonrode.downloader.pipeline.NoSourceLedger.clear()
            _version.value = ver
            if (ver != prevVer || activeRev != prevActiveRev) {
                val revForDiff = rev
                if (revForDiff != null && revForDiff > 0L) {
                    val changedHosts = computeChangedHosts(
                        prevKnownDead, prevLockerHosts, prevHostPolicies, prevPipelines,
                        knownDead, lockerHosts, hostPolicies, pipelines
                    )
                    if (changedHosts.isEmpty()) {
                        com.anonrode.downloader.util.DebugLog.engine(
                            "OTA rules $prevVer -> $ver (rev $revForDiff): no host-policy changes detected — backoff verdicts kept"
                        )
                    } else {
                        val cleared = com.anonrode.downloader.pipeline.HostHealth.clearBackoffsFor(changedHosts)
                        com.anonrode.downloader.util.DebugLog.engine(
                            "OTA rules $prevVer -> $ver (rev $revForDiff): diff cleared $cleared backoff verdict(s) across ${changedHosts.size} changed host target(s)"
                        )
                        if (cleared == 0) {
                            com.anonrode.downloader.util.DebugLog.trace(
                                "OTA rules diff: changed targets [${changedHosts.take(8).joinToString(", ")}]"
                            )
                        }
                    }
                } else {
                    val cleared = com.anonrode.downloader.pipeline.HostHealth.clearBackoffsOnNewRules()
                    if (cleared > 0) {
                        com.anonrode.downloader.util.DebugLog.engine(
                            "OTA rules $prevVer -> $ver (no rev on payload): cleared $cleared stale host backoff verdicts (coarse fallback)"
                        )
                    }
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
}
