# ═══════════════════════════════════════════════════════════════════════════
# Anon Downloader — release R8 rules (phase 1 hardening, workstream C).
#
# Rewritten against the ACTUAL dependency set in app/build.gradle.kts.
# Discipline (plan §5): no blanket -dontobfuscate / -dontshrink. Every rule
# below carries an inline justification; libraries whose consumer rules
# ship inside their artifacts are NOT duplicated here.
#
# Verified 2026-10-18 by source/artifact inspection (no local compiler):
#  - OkHttp 4.12.0 ships META-INF/proguard/okhttp3.pro inside the jar.
#  - kotlinx-coroutines 1.8.1 ships META-INF/proguard/coroutines.pro.
#  - kotlinx-serialization 1.7.2: rules are supplied with the library
#    ("keep serializers for all serializable classes retained after
#    shrinking"); the one gap is NAMED companion objects — audited
#    Models.kt + FeedCache.kt: none exist in this app.
#  - media3 1.4.1 ships consumer rules as proguard.txt (media3-common,
#    media3-exoplayer, media3-ui, media3-datasource; media3-session and
#    media3-exoplayer-hls ship none). media3 1.5+ raises minCompileSdk to
#    35 (byte-verified) — part of why this line stays on 1.4.1.
#  - Coil 2.7.0 ships NO consumer rules (coil-base build script declares no
#    consumerProguardFiles; no META-INF/proguard in the module) — see below.
#  - No app code loads classes by name: zero Class.forName / ClassLoader /
#    declaredFields / ServiceLoader / WebView JS-bridge / resource-name
#    lookups over app/src/main (the only ::class.java.name uses are
#    diagnostic strings in CrashHandler and are cosmetic under obfuscation).
#  - Phase 2 adds the pre-wired future SDKs (AppLovin, Unity Ads, AdMob/UMP,
#    firebase-messaging, play-integrity) — all inert unless an OTA payload
#    enables them; their R8 treatment is documented in the phase-2 block at
#    the bottom of this file.
# ═══════════════════════════════════════════════════════════════════════════

# ── Attribute retention ────────────────────────────────────────────────────
# Annotations (RuntimeVisibleAnnotations/AnnotationDefault) are what the
# kotlinx-serialization generated descriptors and the crash/telemetry flows
# rely on; InnerClasses/Signature/EnclosingMethod keep generic signatures and
# anonymous-class metadata intact for stack traces and debug tooling.
# (*Annotation* already matches the two annotation entries — both spellings
# are kept so the intent stays explicit.)
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, AnnotationDefault, EnclosingMethod

# ── Obfuscation strength ───────────────────────────────────────────────────
# Single-package output + access widening (plan §5). Judgment, stated in the
# phase-1 report: safe for THIS app because nothing is ever loaded by name
# (see audit above); manifest-declared components (AnonApp, MainActivity,
# QuickShareActivity, DownloadService, RetryReceiver, androidx FileProvider)
# are pinned by AGP's generated manifest rules and are never repackaged;
# kotlinx.serialization resolves through generated companions / compiled
# direct call sites, not through class-name lookups.
-repackageclasses 'anon'
-allowaccessmodification

# ── youtubedl-android 0.18.1 (library + ffmpeg) ────────────────────────────
# The engine is a native/python subprocess launcher whose Kotlin half feeds
# extracted executables and per-channel update objects; the separate aria2c
# artifact is intentionally NOT on the classpath (aria2c ships as a jniLib,
# see NOTICE) while the library can reference com.yausername.aria2c.* —
# genuinely optional at runtime, hence the dontwarn. Keeping the whole tree
# is cheap (two small AARs) and removes all reflection risk from the download
# stack; nothing in it is worth obfuscating.
-keep class com.yausername.** { *; }
-dontwarn com.yausername.**

# ── kotlinx.serialization 1.7.2 (+ Kotlin 2.0.20 serialization plugin) ────
# The plugin applies in this module and generates $serializer classes;
# 1.7.2 ships its own R8 rules keeping serializers of every retained
# @Serializable class — no generic keep block is required (the pre-existing
# broad model keep is deliberately removed). Two narrow keep lines for this
# app's own serialization surface:
#  - TaskStatus is the only @Serializable enum in JSON payloads; kotlinx
#    serializes enums by Enum.name, so pin its constants against any R8
#    enum-rewrite edge case (zero observable cost: the names appear in JSON
#    either way).
#  - dontwarn for the library's optional kotlin-reflect-backed fallback
#    paths: kotlin-reflect is deliberately NOT a dependency, and this app
#    never performs runtime-reflective serializer lookup (all call sites are
#    reified/generated), so a missing-class warning from those guarded paths
#    would be benign noise.
-keepclassmembers enum com.anonrode.downloader.data.models.TaskStatus { *; }
-dontwarn kotlinx.serialization.**
-dontwarn kotlin.reflect.**

# ── OkHttp 4.12.0 / Okio ───────────────────────────────────────────────────
# Verified: the 4.12.0 jar ships META-INF/proguard/okhttp3.pro which keeps
# the internal platform classes OkHttp reaches via string lookups — no keep
# rules needed here (the pre-existing broad okhttp keep is deliberately
# removed). The only missing-class warnings OkHttp's platform probing can
# produce concern TLS providers that are genuinely optional and absent on
# Android; the shipped rules already name these four — duplicated so they
# stand even if embedded-rule pickup ever changes.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.graalvm.nativeimage.**

# ── Coil 2.7.0 (coil-compose) ──────────────────────────────────────────────
# Verified: coil 2.7.0 ships NO consumer rules. Coil discovers pluggable
# decoders/fetchers/interceptors through ServiceLoader-style registries and
# is the poster pipeline for every screen — a release-only discovery
# regression would be invisible to the debug-flavored CI tests. Keeping the
# library wholesale costs little (third-party UI plumbing, nothing worth
# obfuscating) and removes that risk entirely; narrow this only after a
# green CI run plus a device soak. Re-check for consumer rules when Coil is
# next upgraded.
-keep class coil.** { *; }

# ── Jsoup / org.json ───────────────────────────────────────────────────────
# Intentionally absent: Jsoup is reflection-free (direct references only) and
# org.json is the platform implementation shipped in android.jar (never
# shrunk/renamed). No rules required.

# ── R8 full-mode missing-class relaxations (CI run 39243465048) ────────────
# R8 fails the release build outright when these referenced-but-absent
# classes are seen. Every one is a guarded/optional reference inside an SDK
# we ship but never trigger on Android:
#  - LoudnessCodecController(±listener): API 35 classes referenced by
#    play-services-ads internals; compileSdk is 34 and the SDK guards the
#    call by SDK_INT at runtime.
#  - java.beans.*: Jackson's Java7SupportImpl (only instantiated when the
#    java.beans package exists — it never does on Android).
#  - org.tukaani.xz.*: commons-compress sevenz/xz coders for archive formats
#    this app never opens (xz is not a shipped dependency).
#  - org.w3c.dom.bootstrap.DOMImplementationRegistry: Jackson's
#    DOMSerializer (the DOM bootstrap is absent on Android).
-dontwarn android.media.LoudnessCodecController
-dontwarn android.media.LoudnessCodecController$OnLoudnessCodecUpdateListener
-dontwarn java.beans.**
-dontwarn org.tukaani.xz.**
-dontwarn org.w3c.dom.bootstrap.DOMImplementationRegistry

# ── Phase-2 pre-wired future SDKs (all inert at runtime) ───────────────────
# Per-SDK R8 treatment, audited against vendor documentation; every one of
# these artifacts declares consumer rules or a documented no-configuration
# requirement, so no blanket keeps are added for them:
#  - play-services-ads 23.x (Google): the Play services AARs ship consumer
#    ProGuard rules (mediation-adapter keeps; byte-verified in
#    play-services-ads-lite 23.6.0). No app-side rules needed.
#  - user-messaging-platform 3.1.0: ships NO consumer rules (byte-verified;
#    the inert path calls the SDK directly, nothing reflective). UMP 4.x
#    adds a proguard.txt — re-check if ever upgraded.
#  - firebase-messaging 24.1.0: the messaging AAR itself ships NO consumer
#    rules (byte-verified); the consumer rules on this classpath come from
#    its transitive artifacts firebase-common / firebase-components /
#    firebase-encoders-json (each ships proguard.txt). The manual-
#    FirebaseOptions path adds no reflection of app classes.
#  - play-integrity 1.4.0: ships NO consumer rules (byte-verified; its
#    core-common 2.0.4 dependency ships none either) — the task API is
#    called directly, nothing reflective on the inert path.
#  - applovin-sdk 12.x: the SDK bundles its own ProGuard configuration
#    (AppLovin docs: no additional rules required).
#  - unity-ads 4.12.2: byte-verified — the AAR ships proguard.txt consumer
#    rules that already keep com.unity3d.ads.** and com.unity3d.services.**;
#    the one conservative keep below is retained as belt-and-braces (cost is
#    small, one small AAR) and is REMOVABLE after the first green CI run.
#    Unity docs caveat: SDK 4.20.0+ ships its full R8 rule set; for < 4.20.0
#    with AGP 9 + minify they require app-side WorkManager keep rules —
#    NOT applicable to this build (AGP 8.5.2); re-check on any AGP bump.
# CI-watch: if R8 reports missing classes from any vendor package, add the
# narrowest possible dontwarn/keep for exactly that class (never `**`).
-keep class com.unity3d.ads.** { *; }

# FCM service (2026-10-19 un-park): manifest-referenced component; keep the
# class + its overridden callbacks even under full-mode R8 so token refresh
# and MESSAGING_EVENT delivery cannot be renamed out of the manifest contract.
-keep class com.anonrode.downloader.update.AnonFirebaseMessagingService { *; }

# The ads/integrity SDKs are INERT unless the OTA payload enables them. FCM
# now has BuildConfig client defaults (manual runtime FirebaseOptions), so its
# service keep above is a real shipping path; ads/Integrity keep rules only
# need to satisfy R8's static analysis.
