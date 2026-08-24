package com.anonrode.downloader.data.rules

import android.content.Context
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.providers.DynamicSiteConfig
import com.anonrode.downloader.providers.GenericDeclarativeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
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

object DynamicRulesManager {

    // OTA rules are AES-128-CBC encrypted so the scraping logic is not
    // readable from the GitHub repo (site owners watching the repo see
    // ciphertext). Key/IV ship in the APK — this is obfuscation-grade
    // protection, NOT strong security: a determined reverse engineer can
    // extract them. Keep the key/IV in sync with probe/encrypt_rules.py.
    private const val RULES_KEY_HEX = "8f3a9c21d4e65b0789a2c4f6d1e3b5a7"
    private const val RULES_IV_HEX = "5b7e9d2f4a6c8e10f3a5c7d9b1e2f4a6"
    private const val RULES_URL = "https://raw.githubusercontent.com/anonrode/download-toolkit-serverless/master/scraper_rules.json.enc"
    private const val CACHE_FILE = "dynamic_scraper_rules.enc"

    // ECDSA P-256 public key (X509/SPKI DER, base64). Private half lives ONLY
    // in the GitHub Actions secret OTA_SIGNING_PRIVATE_KEY; ota-rules.yml signs
    // every payload. Any payload whose signature doesn't verify is refused —
    // a repo hijack or CDN MITM cannot feed this app fake scraping logic.
    // Rotate by shipping a new app release with a new public key.
    // internal var (not const) solely so JVM tests can inject an ephemeral
    // keypair and exercise sign -> verify end-to-end.
    internal var rulesSigningPubB64: String =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAELW5uNxiti768q9f1YPvjaMyd0b60W7tEn6hCCQBtu6YyguDIMtKvefov9uwD" +
        "0uN9JP0HKkYUJB1wSL3Q928+lQ=="

    // Built-in fallback policies — mirrors the historical hardcoded referer
    // map in DownloadEngine (behavior-identical when no OTA rule matches).
    // OTA hostPolicies always take precedence over these.
    private val DEFAULT_HOST_POLICIES = listOf(
        HostPolicyRule("cdn.watching.onl", "exact:https://megaplay.buzz/"),
        HostPolicyRule("anivideo.sbs", "exact:https://megaplay.buzz/"),
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
        ".mp4", ".mkv", ".webm", ".avi", ".ts", ".m3u8", ".m4v", ".mp3", ".zip", ".rar", "-mp4", "-mkv"
    )

    private val activeDomains = mutableMapOf<String, String>().apply { putAll(defaultDomains) }
    private val activeMirrors = mutableMapOf<String, List<String>>()
    private val activeSiteConfigs = mutableMapOf<String, SiteRuleConfig>()
    private val activeResolverConfigs = mutableMapOf<String, JSONObject>()
    private val activeMediaExtensions = mutableListOf<String>().apply { addAll(defaultMediaExtensions) }
    private val dynamicProviders = mutableListOf<GenericDeclarativeProvider>()

    // Playbook v2 state
    private val activeHostPolicies = mutableListOf<HostPolicyRule>()
    private val activeUrlTemplates = mutableMapOf<String, String>()
    private val activeKnownDead = mutableListOf<String>()
    private val activeLockerHosts = mutableListOf<String>()
    private val activeSearchStrategies = mutableMapOf<String, List<JSONObject>>()
    var tokenTtlMinutes: Long = 10
        private set

    private val _version = MutableStateFlow("2026.08.22.1 (Bundled)")
    val version: StateFlow<String> = _version.asStateFlow()

    fun init(context: Context) {
        try {
            val file = File(context.filesDir, CACHE_FILE)
            if (file.exists()) {
                val raw = file.readText()
                val plain = decryptRules(raw) ?: raw  // old plaintext cache still parses
                parseRulesJson(plain)
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

    fun getTokenTtlMs(): Long = tokenTtlMinutes * 60_000L

    /** Ordered OTA search strategies for a site (urlTemplate / rss / json /
     *  slugGuess), empty when the playbook declares none. */
    fun getSearchStrategies(site: String): List<JSONObject> =
        activeSearchStrategies[site.lowercase()] ?: emptyList()

    suspend fun syncFromGitHub(context: Context): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            // Append cache-buster timestamp to bypass GitHub CDN's 5-minute cache
            val cacheBustedUrl = "$RULES_URL?t=${System.currentTimeMillis()}"
            val jsonStr = HttpClient.getText(cacheBustedUrl, tag = "ota_rules")
            if (jsonStr.isNullOrBlank()) {
                return@withContext Pair(false, "Could not reach GitHub rules repository")
            }

            // Payload is base64(AES-CBC) — decrypt before parsing.
            val plain = decryptRules(jsonStr)
            if (plain == null) {
                return@withContext Pair(false, "Rules payload failed to decrypt — keeping bundled defaults")
            }
            if (!parseRulesJson(plain)) {
                return@withContext Pair(false, "Rules payload failed to parse — keeping bundled defaults")
            }

            val file = File(context.filesDir, CACHE_FILE)
            file.writeText(jsonStr)  // cache stays encrypted

            Pair(true, _version.value)
        } catch (t: Throwable) {
            Pair(false, t.message ?: "Failed to sync rules")
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /** Decrypts the OTA payload -> JSON text; null on any failure.
     *
     *  Accepts only SIGNED envelopes:
     *   - v2 envelope JSON {"v":2,"iv":..,"payload":<b64>,"sig":<b64>} —
     *     signature verified BEFORE decryption; v2 without a signature or
     *     with a bad one is refused outright.
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
                    // Envelope claims authenticity: verify before decrypting.
                    if (sigB64.isBlank() || !verifySignature(payloadB64, sigB64)) {
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

    /** ECDSA-P256-SHA256 over the ASCII payload string; testable directly. */
    internal fun verifySignature(payloadB64: String, sigB64: String): Boolean {
        return try {
            val pub = KeyFactory.getInstance("EC").generatePublic(
                X509EncodedKeySpec(java.util.Base64.getDecoder().decode(rulesSigningPubB64))
            )
            val s = Signature.getInstance("SHA256withECDSA")
            s.initVerify(pub)
            s.update(payloadB64.toByteArray(Charsets.US_ASCII))
            s.verify(java.util.Base64.getDecoder().decode(sigB64))
        } catch (_: Exception) {
            false
        }
    }

    /** Returns false when the payload is malformed; bundled defaults stay active.
     *  internal so JVM unit tests can exercise the full parse path. */
    internal fun parseRulesJson(jsonStr: String): Boolean {
        // Clear ALL playbook-driven state BEFORE parsing, so a failure
        // anywhere below leaves a fully-default state instead of a
        // half-applied playbook. Every getter already falls back to its
        // bundled default when its collection is empty.
        activeDomains.clear()
        activeMirrors.clear()
        activeSiteConfigs.clear()
        activeResolverConfigs.clear()
        activeMediaExtensions.clear()
        activeHostPolicies.clear()
        activeUrlTemplates.clear()
        activeKnownDead.clear()
        activeLockerHosts.clear()
        activeSearchStrategies.clear()
        dynamicProviders.clear()
        tokenTtlMinutes = 10
        return try {
            val obj = JSONObject(jsonStr)
            val ver = obj.optString("version", "2026.08.22.1")
            _version.value = ver

            val domainsObj = obj.optJSONObject("domains")
            if (domainsObj != null) {
                val keys = domainsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val url = domainsObj.optString(k)
                    if (url.isNotBlank()) {
                        activeDomains[k.lowercase()] = url
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
                        activeMirrors[k.lowercase()] = list
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

                        activeSiteConfigs[k.lowercase()] = SiteRuleConfig(
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
                        activeResolverConfigs[k.lowercase()] = rObj
                    }
                }
            }

            val mediaExtArr = obj.optJSONArray("directMediaExtensions")
            if (mediaExtArr != null && mediaExtArr.length() > 0) {
                for (idx in 0 until mediaExtArr.length()) {
                    val ext = mediaExtArr.optString(idx)
                    if (ext.isNotBlank()) activeMediaExtensions.add(ext)
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
                    activeHostPolicies.add(
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
                    if (tpl.isNotBlank()) activeUrlTemplates[k.lowercase()] = tpl
                }
            }

            val kdArr = obj.optJSONArray("knownDead")
            if (kdArr != null) {
                for (i in 0 until kdArr.length()) {
                    val h = kdArr.optString(i)
                    if (h.isNotBlank()) activeKnownDead.add(h.lowercase())
                }
            }

            // OTA locker host seeds: LockerRegistry.classify() consults these
            // first (prioritization), but never gates — unknown hosts still
            // get probed and can work on first contact.
            val lhArr = obj.optJSONArray("lockerHosts")
            if (lhArr != null) {
                for (i in 0 until lhArr.length()) {
                    val h = lhArr.optString(i)
                    if (h.isNotBlank()) activeLockerHosts.add(h.lowercase())
                }
            }

            tokenTtlMinutes = obj.optLong("tokenTtlMinutes", 10L).coerceIn(1L, 240L)

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
                    if (list.isNotEmpty()) activeSearchStrategies[k.lowercase()] = list
                }
            }

            // Parse any dynamic new providers added remotely
            val dynamicList = obj.optJSONArray("dynamic_providers")
            if (dynamicList != null) {
                for (i in 0 until dynamicList.length()) {
                    val item = dynamicList.getJSONObject(i)
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
                    dynamicProviders.add(GenericDeclarativeProvider(cfg))
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
