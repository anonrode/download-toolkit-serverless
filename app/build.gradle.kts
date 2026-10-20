plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.anonrode.downloader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.anonrode.downloader"
        minSdk = 26
        targetSdk = 34
        // v3.3.0 — platform surfaces (announcements / update flow + gate /
        // engagement / ads readiness) + hardening (R8) + audit-fix batch.
        versionCode = 331
        versionName = "3.3.0"

        // Manual-runtime FCM defaults (2026-10-19 un-park). These are the
        // NON-SECRET Firebase Android client identifiers; there is deliberately
        // no google-services plugin and no google-services.json. The signed
        // OTA `fcm` block remains the override/rotation path (FcmRegistrar
        // re-inits when the OTA appId differs). A service-account key must
        // never appear here or anywhere in the repo.
        buildConfigField("String", "FCM_PROJECT_ID", "\"anonrode-d591f\"")
        buildConfigField("String", "FCM_APP_ID", "\"1:93321768940:android:6ef4186eb2157d72ce5ac9\"")
        buildConfigField("String", "FCM_SENDER_ID", "\"93321768940\"")
        buildConfigField("String", "FCM_API_KEY", "\"AIzaSyBI7T92Ey_WqLKf-JXjEV7utQS94EZRZx8\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        resourceConfigurations += listOf("en")

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        getByName("debug") {
            // Standard debug signing for release artifact distribution
        }
        // Real release key, used only when CI provides it via GitHub
        // secrets. Local/dev builds fall back to debug signing so the
        // project still builds without the secrets set.
        create("release") {
            val ksPath = System.getenv("KEYSTORE_PATH")
            if (!ksPath.isNullOrBlank()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Phase 1 hardening (workstream C): R8 + resource shrinking ON for
            // release only. There is no local compiler in this cycle — the
            // rules in proguard-rules.pro are audited per-dependency and the
            // first CI run is the real check (see scratch/phase1_report_S4.md
            // §Uncertainties).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (System.getenv("KEYSTORE_PATH").isNullOrBlank())
                signingConfigs.getByName("debug")
            else
                signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Robolectric needs the real merged manifest + Android resources
            // (not the android.jar stub) to host Compose test activity launches.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.activity:activity-compose:1.9.2")

    // Jetpack Compose BOM 2024.09.00
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Coroutines & Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")

    // OkHttp 4.12 & Jsoup HTML parser (For on-device scraping & link resolution)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.1")

    // Coil Image Loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // yt-dlp + ffmpeg native engines (aria2c comes from jniLibs, see NOTICE)
    val youtubedlAndroid = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$youtubedlAndroid")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$youtubedlAndroid")

    // Media3 (ExoPlayer) for in-app playback. 1.4.1 is the newest line that
    // still targets compileSdk 34; 1.5+ requires 35. media3-ui ships a
    // PlayerView that owns the render surface + resizeMode, the cleanest
    // stable base for the Compose-hosted modal. HLS covers the playlist
    // sources the engine can produce; DASH is intentionally not included —
    // this app has no DASH sources.
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-datasource:$media3")

    // ── Future-capability pre-wiring (plan §4.6 / Decision 16) ──────────────
    // Every SDK below ships DISABLED: nothing initializes unless the OTA
    // payload's "ads"/"fcm" sections enable it (ads/Ads.kt, update/
    // FcmRegistrar.kt, update/IntegrityAttestor.kt gate at runtime).
    // OTA can ship config/credentials, never code — so the code compiles in
    // now and activation later is a payload edit wherever the vendor accepts
    // runtime credentials. Pins were verified against LIVE registries +
    // artifacts (scratch/qa_report_V1.md): all exist and none blocks
    // compileSdk 34 (QA-V1 byte-checked the AAR metadata; upgrade locks —
    // Kotlin-2.1 floors, removed overloads, media3 1.5+ needing compileSdk
    // 35 — are recorded there for the next bump, not asserted here).
    implementation("com.applovin:applovin-sdk:12.6.1")                 // MAX, sdkKey = payload field
    implementation("com.unity3d.ads:unity-ads:4.12.2")                 // gameId = payload field
    implementation("com.google.android.gms:play-services-ads:23.6.0")  // AdMob, unit ids = payload fields
    implementation("com.google.android.ump:user-messaging-platform:3.1.0") // UMP consent (EEA), inert
    implementation("com.google.firebase:firebase-messaging:24.1.0")   // manual FirebaseOptions — NO google-services.json
    implementation("com.google.android.play:integrity:1.4.0")         // standard integrity tokens, hard-gated off (update/IntegrityAttestor.kt)

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Local unit tests (JVM)
    testImplementation("junit:junit:4.13.2")
    // Real org.json for unit tests; the android.jar stub throws "Stub!" on any
    // call, so fixtures that exercise resolvers (JSONObject) need the JVM impl.
    testImplementation("org.json:json:20240303")
    // MockWebServer for engine tests: flaky-connection, retry, and resume scenarios.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Lets Robolectric tests replace Dispatchers.Main. Coil's execute() always
    // hops to Main; the paused Robolectric looper would otherwise deadlock a
    // runBlocking test that blocks the main thread.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    // Robolectric Compose UI tests (phase 2 test pyramid): shadows the Android
    // framework so DownloadCard renders on the JVM. 4.16.x is the newest stable
    // line covering compileSdk 34; it pins androidx.test:monitor to 1.8.x, so
    // ActivityScenario core is raised from the 1.5.0 ui-test-junit4 drags in.
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.6.1")
    // Same Compose BOM as the app so the JVM test APIs match the UI under test.
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")

    // Instrumented smoke tests (emulator in CI): Compose UI testing
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}

// CI diagnostics: print every test lifecycle event so a hang names the test
// instead of timing out with no evidence in the runner log.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    testLogging {
        events("started", "passed", "failed", "skipped")
        showStandardStreams = true
    }
}
