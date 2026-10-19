package com.anonrode.downloader.ads

import android.content.Context
import com.anonrode.downloader.data.rules.AdsConfig
import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.util.DebugLog
import com.applovin.sdk.AppLovinSdk
import com.applovin.sdk.AppLovinSdkSettings
import com.google.android.gms.ads.MobileAds
import com.unity3d.ads.IUnityAdsInitializationListener
import com.unity3d.ads.UnityAds

/**
 * Ads readiness (workstream C, phase 2; plan §4.6 "register later, activate
 * by OTA").
 *
 * One slot interface, four implementations: HouseAds (zero SDK — S3 renders
 * straight from the payload's house* fields), AppLovin, Unity Ads, AdMob.
 * The vendor SDKs are compiled in but INERT: [activeBridge] returns null
 * unless the payload's `ads` section is enabled AND names a provider, and
 * each bridge additionally refuses to initialize without its credential
 * (sdkKey / gameId / unit ids) — so no ad SDK ever initializes while ads
 * are off, and activation later is a payload edit (the honest §4.6 matrix).
 *
 * AdMob's APPLICATION_ID manifest meta-data currently carries Google's
 * official TEST app id (see AndroidManifest.xml) — the one field AdMob reads
 * from the manifest instead of runtime APIs; replacing it at registration is
 * a documented one-line change.
 *
 * Telemetry: impressions are S3's call (ad_impression, id-only); this file
 * only journals init decisions. Never throws.
 */
interface AdBridge {
    fun init(context: Context, cfg: AdsConfig)
    fun isReady(): Boolean
}

/** No SDK. The payload's house copy IS the ad; S3 renders it via the
 *  AdsConfig getters. Ready whenever any copy exists. */
object HouseAds : AdBridge {
    @Volatile private var ready = false

    override fun init(context: Context, cfg: AdsConfig) {
        ready = cfg.houseTitle.isNotBlank() || cfg.houseBody.isNotBlank()
    }

    override fun isReady(): Boolean = ready
}

/** AppLovin MAX. sdkKey is a runtime credential (payload field) — the
 *  reason AppLovin is the fully-OTA activatable vendor in the §4.6 matrix. */
object AppLovinBridge : AdBridge {
    @Volatile private var ready = false

    override fun init(context: Context, cfg: AdsConfig) {
        if (cfg.applovinSdkKey.isBlank()) return // no credential → stay inert
        try {
            val settings = AppLovinSdkSettings(context)
            AppLovinSdk.getInstance(cfg.applovinSdkKey, settings, context).initializeSdk {
                ready = true
            }
        } catch (t: Throwable) {
            DebugLog.engine("AppLovinBridge init failed: ${t.javaClass.simpleName}")
        }
    }

    override fun isReady(): Boolean = ready
}

/** Unity Ads. gameId is a runtime credential (payload field). */
object UnityBridge : AdBridge {
    @Volatile private var ready = false

    override fun init(context: Context, cfg: AdsConfig) {
        if (cfg.unityGameId.isBlank()) return
        try {
            UnityAds.initialize(context, cfg.unityGameId, false, object : IUnityAdsInitializationListener {
                override fun onInitializationComplete() {
                    ready = true
                }

                override fun onInitializationFailed(error: UnityAds.UnityAdsInitializationError?, message: String?) {
                    ready = false
                    DebugLog.engine("UnityBridge init failed: ${error?.name ?: "unknown"}")
                }
            })
        } catch (t: Throwable) {
            DebugLog.engine("UnityBridge init threw: ${t.javaClass.simpleName}")
        }
    }

    override fun isReady(): Boolean = ready
}

/** AdMob. App id = manifest meta-data (test id until registration — see
 *  AndroidManifest.xml); unit ids arrive as payload fields. */
object AdMobBridge : AdBridge {
    @Volatile private var ready = false

    override fun init(context: Context, cfg: AdsConfig) {
        try {
            MobileAds.initialize(context) {
                ready = true
            }
        } catch (t: Throwable) {
            DebugLog.engine("AdMobBridge init failed: ${t.javaClass.simpleName}")
        }
    }

    override fun isReady(): Boolean = ready
}

object Ads {

    /** Which bridge was initialized and with which credential fingerprint —
     *  a payload credential rotation re-initializes on the next call. */
    @Volatile private var initializedKey: String = ""
    @Volatile private var initializedBridge: AdBridge? = null

    /**
     * The slot's bridge for the CURRENT payload: null (render nothing)
     * while ads are disabled, when the provider is "none"/unknown, or when
     * the chosen vendor has no credential yet. Initializes lazily, once per
     * credential set. Safe to call from the UI thread (vendor init is async
     * by design); never throws.
     */
    @Synchronized
    fun activeBridge(context: Context): AdBridge? {
        return try {
            val cfg = DynamicRulesManager.getAdsConfig() ?: return null
            if (!cfg.enabled) return null
            val provider = cfg.provider.trim().lowercase()
            val bridge = when (provider) {
                "house" -> HouseAds
                "applovin" -> if (cfg.applovinSdkKey.isBlank()) null else AppLovinBridge
                "unity" -> if (cfg.unityGameId.isBlank()) null else UnityBridge
                "admob" -> if (cfg.admobBannerUnitId.isBlank() && cfg.admobNativeUnitId.isBlank()) null else AdMobBridge
                "auto" -> when {
                    cfg.applovinSdkKey.isNotBlank() -> AppLovinBridge
                    cfg.unityGameId.isNotBlank() -> UnityBridge
                    cfg.admobBannerUnitId.isNotBlank() || cfg.admobNativeUnitId.isNotBlank() -> AdMobBridge
                    else -> HouseAds
                }
                else -> null // "none" / unknown / vendor not compiled → nothing renders
            } ?: return null
            val key = provider + "|" + when (bridge) {
                AppLovinBridge -> cfg.applovinSdkKey
                UnityBridge -> cfg.unityGameId
                AdMobBridge -> cfg.admobBannerUnitId + "/" + cfg.admobNativeUnitId
                else -> ""
            }
            if (initializedKey != key || initializedBridge !== bridge) {
                try {
                    bridge.init(context.applicationContext, cfg)
                } catch (t: Throwable) {
                    DebugLog.engine("Ads: bridge init failed (${t.javaClass.simpleName})")
                }
                initializedKey = key
                initializedBridge = bridge
            }
            bridge
        } catch (t: Throwable) {
            DebugLog.engine("Ads.activeBridge failed: ${t.javaClass.simpleName}")
            null
        }
    }
}

/** File-level alias of [Ads.activeBridge] for call sites that expect the
 *  bare contract name (`activeBridge(context)`) — same function, one
 *  implementation, both shapes compile. */
fun activeBridge(context: Context): AdBridge? = Ads.activeBridge(context)
