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
        versionCode = 310
        versionName = "3.1.0"

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
            isMinifyEnabled = false
            isShrinkResources = false
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

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Local unit tests (JVM)
    testImplementation("junit:junit:4.13.2")
    // Real org.json for unit tests; the android.jar stub throws "Stub!" on any
    // call, so fixtures that exercise resolvers (JSONObject) need the JVM impl.
    testImplementation("org.json:json:20240303")
    // MockWebServer for engine tests: flaky-connection, retry, and resume scenarios.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

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
