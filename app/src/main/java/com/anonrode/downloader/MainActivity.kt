package com.anonrode.downloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.anonrode.downloader.ui.components.MainScaffold
import com.anonrode.downloader.ui.components.MainTab
import com.anonrode.downloader.ui.screens.SocialModal
import com.anonrode.downloader.ui.screens.SplashContent
import com.anonrode.downloader.ui.theme.AccentPrimary
import com.anonrode.downloader.ui.theme.AnonDownloaderTheme
import com.anonrode.downloader.ui.theme.DarkAnonColors
import com.anonrode.downloader.ui.theme.LightAnonColors
import com.anonrode.downloader.ui.theme.SurfaceCard
import com.anonrode.downloader.ui.theme.TextMuted
import com.anonrode.downloader.ui.theme.TextPrimary
import com.anonrode.downloader.ui.theme.TextSecondary
import com.anonrode.downloader.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var activeSocialTarget = mutableStateOf<Pair<String, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            installSplashScreen()
        } catch (_: Throwable) {}

        super.onCreate(savedInstanceState)
        // Cold-start: fit the window edge-to-edge with the bar icon tint and
        // window background matched to the persisted theme so the first frame
        // already has the right system-bar contrast. Without this, light mode
        // launches with white-on-near-white status icons (invisible) until the
        // first composition. Theme switches later re-run enableEdgeToEdge
        // through syncSystemBars — that is the only call that repaints the bar
        // FILL (see its doc); here it just seeds the first frame.
        val coldPrefs = getSharedPreferences("downloader_settings", android.content.Context.MODE_PRIVATE)
        val coldTheme = coldPrefs.getString("pref_theme_mode", "dark") ?: "dark"
        applyEdgeToEdge(coldTheme)

        handleShareIntent(intent)

        val prefs = getSharedPreferences("downloader_settings", android.content.Context.MODE_PRIVATE)

        // Read both persisted prefs outside the composable: the theme decides
        // the initial colour scheme, the tab decides which screen renders
        // first. Doing the read here (instead of inside Compose) keeps the
        // restore decision in one place and avoids the "first composition
        // flashes the wrong tab" bug.
        val initialThemeMode = prefs.getString("pref_theme_mode", "dark") ?: "dark"
        val initialTab = prefs.getString("pref_last_tab", MainTab.DEFAULT)?.let { stored ->
            if (stored == MainTab.SEARCH || stored == MainTab.DOWNLOADS || stored == MainTab.SETTINGS) stored
            else MainTab.DEFAULT
        } ?: MainTab.DEFAULT

        setContent {
            var themeMode by remember { mutableStateOf(initialThemeMode) }

            // Resolve the effective theme for the system bars and window
            // background. "system" follows the device night mode, mirroring
            // AnonDownloaderTheme's own resolution below, so Auto gets the
            // right bar contrast and background on light AND dark devices.
            val isDark = when (themeMode.lowercase()) {
                "light" -> false
                "system" -> isSystemInDarkTheme()
                else -> true
            }

            // Theme-flip sync: re-applies enableEdgeToEdge with the newly
            // resolved bar style and swaps the window background. Re-running
            // enableEdgeToEdge is the only way the bar FILL repaints — flipping
            // the icon-appearance flags alone leaves the fill on the leaving
            // theme's contrast-scrim decision (the gray band the previous
            // build showed on dark→light). Its 1-frame window re-fit is
            // invisible because onThemeChanged below already landed the window
            // background on the target color BEFORE the state write.
            // SideEffect (not LaunchedEffect): it runs after this scope
            // recomposes successfully and BEFORE the frame is dispatched, so
            // the bar restyle and the Compose color swap land in the SAME
            // frame. This scope only recomposes when themeMode changes (or the
            // device night mode changes in Auto), so the sync never runs on
            // unrelated recompositions.
            SideEffect { syncSystemBars(isDark) }

            AnonDownloaderTheme(themeMode = themeMode) {
                val socialTarget by activeSocialTarget

                // Request Notification Permission on Android 13+ (API 33+)
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                    onResult = { _ -> }
                )

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val hasPermission = ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED

                        if (!hasPermission) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }

                // ---- Storage access ----
                // Downloads are written straight into Download/Anon, which on
                // Android 11+ (API 30+) requires MANAGE_EXTERNAL_STORAGE —
                // "All files access". That permission has NO runtime dialog:
                // it can only be granted via a Settings redirect. On Android
                // 9-10 (API 26-29) it is a normal WRITE_EXTERNAL_STORAGE prompt.
                // Neither was ever requested, so fresh installs failed with a
                // confusing IO error until the user manually granted access in
                // app info (tester report). Show a rationale dialog at launch
                // and route to the right grant screen.
                val context = LocalContext.current
                var showStorageRationale by remember { mutableStateOf(false) }
                val writeStorageLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { granted ->
                    showStorageRationale = !granted
                }
                val manageStorageLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { _ ->
                    // The result code is meaningless for the All-files-access
                    // toggle — re-check the actual grant state on return.
                    showStorageRationale = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        !Environment.isExternalStorageManager()
                }

                LaunchedEffect(Unit) {
                    showStorageRationale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        !Environment.isExternalStorageManager()
                    } else {
                        ContextCompat.checkSelfPermission(
                            context, Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ) != PackageManager.PERMISSION_GRANTED
                    }
                }

                // Guaranteed-visible splash: the system SplashScreen API dismisses
                // on first frame (never seen on fast devices), so hold a designed
                // Compose splash for a brief beat before revealing the app. Kept
                // short on purpose — a long hold read as a slow response when
                // tapping the app icon. Cold start only: on a config-change
                // recreation (savedInstanceState != null) the splash would
                // flash over the live UI for half a second.
                var showSplash by remember { mutableStateOf(savedInstanceState == null) }
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(500)
                    showSplash = false
                }
                if (showSplash) {
                    SplashContent()
                    return@AnonDownloaderTheme
                }

                MainScaffold(
                    viewModel = viewModel,
                    initialTab = initialTab,
                    themeMode = themeMode,
                    onThemeChanged = { newMode ->
                        // Point the window background at the TARGET theme's
                        // color BEFORE the Compose color swap. The swap is a
                        // full-tree recomposition (the palette is a static
                        // CompositionLocal and all three tabs stay composed),
                        // which can drop frames on slow devices; landing the
                        // background first means any pixel exposed mid-switch
                        // (system-bar regions, a late content frame) is
                        // already the color we are switching TO, so a flash
                        // of the leaving theme is impossible. The content is
                        // opaque and full-bleed, so the early background is
                        // invisible until the content itself flips. Bar ICON
                        // appearance deliberately still flips with the
                        // content in the SideEffect below — early-flipping it
                        // would hide the icons against the old theme for a
                        // frame.
                        setWindowBackground(resolveIsDark(newMode))
                        themeMode = newMode
                        prefs.edit().putString("pref_theme_mode", newMode).apply()
                    },
                    onOpenSocial = { platform, url ->
                        activeSocialTarget.value = Pair(platform, url)
                    },
                    onWriteTabPref = { newTab ->
                        prefs.edit().putString("pref_last_tab", newTab).apply()
                    }
                )

                socialTarget?.let { (platform, url) ->
                    SocialModal(
                        platform = platform,
                        url = url,
                        viewModel = viewModel,
                        onDismiss = { activeSocialTarget.value = null }
                    )
                }

                if (showStorageRationale) {
                    AlertDialog(
                        onDismissRequest = { showStorageRationale = false },
                        containerColor = SurfaceCard,
                        titleContentColor = TextPrimary,
                        textContentColor = TextSecondary,
                        title = {
                            Text("Storage access needed", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        },
                        text = {
                            Text(
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                                    "Anon Downloader saves downloads to your Downloads folder. " +
                                        "On this Android version that needs \"All files access\" — " +
                                        "tap Grant and switch it on. Downloads will fail until then."
                                else
                                    "Anon Downloader saves downloads to your Downloads folder. " +
                                        "Grant storage permission so downloads can be written there.",
                                fontSize = 13.sp
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    try {
                                        manageStorageLauncher.launch(
                                            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                                data = Uri.parse("package:${context.packageName}")
                                            }
                                        )
                                    } catch (_: Exception) {
                                        // Some OEMs drop the All-files-access action —
                                        // fall back to the app's own info page.
                                        context.startActivity(
                                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                data = Uri.parse("package:${context.packageName}")
                                            }
                                        )
                                    }
                                } else {
                                    writeStorageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                }
                            }) {
                                Text("Grant", color = AccentPrimary, fontWeight = FontWeight.SemiBold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showStorageRationale = false }) {
                                Text("Not now", color = TextMuted)
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            // Share payloads arrive in two shapes: a bare URL, or prose that
            // wraps a URL ("Check this out: https://…"). Pull the URL out if
            // one is embedded; fall back to the raw text so the router can
            // classify it (e.g. as a search query).
            val url = com.anonrode.downloader.util.UrlExtractor.firstUrl(sharedText)
                ?: sharedText.trim()
            if (url.isNotBlank()) {
                viewModel.handlePastedInput(url) { platform, u ->
                    activeSocialTarget.value = Pair(platform, u)
                }
            }
        }
    }
}

/**
 * Resolves a theme-mode string to the effective dark state outside of
 * composition (click handlers, cold start). "system" reads the device's
 * current night mode instead of assuming dark, so Auto on a light device
 * gets dark status icons and the light window background. Inside
 * composition the setContent scope uses `isSystemInDarkTheme()` instead so
 * Auto reacts to night-mode changes; the two agree by construction.
 * Dark (or unknown) matches the no-arg `enableEdgeToEdge()` behavior the
 * app always had.
 */
private fun MainActivity.resolveIsDark(themeMode: String): Boolean = when (themeMode.lowercase()) {
    "light" -> false
    "system" -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    else -> true
}

/**
 * Applies the activity's edge-to-edge system-bar styling to match the app's
 * theme, and points the window background at the resolved theme's color.
 * Seeds the cold-start frame from `onCreate`; theme switches after that run
 * the same restyle through [syncSystemBars], which re-applies this call with
 * the newly resolved style (re-running `enableEdgeToEdge` is the only way the
 * bar FILL repaints — see its doc there).
 */
private fun MainActivity.applyEdgeToEdge(themeMode: String) {
    val isDark = resolveIsDark(themeMode)
    val style = if (isDark) {
        // Dark theme: light icons on a transparent bar (the app's black
        // surface bleeds through).
        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
    } else {
        // Light theme: dark icons on a transparent bar (the app's near-white
        // surface bleeds through). `light(scrim, darkScrim)` both transparent
        // because the app surface is always a clean light color and the OS
        // scrim is only needed when the underlying content might be unreadable.
        SystemBarStyle.light(
            android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT
        )
    }
    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    // Match the window background too, so even frames drawn before the first
    // composition (e.g. splash dismissal) show the resolved theme's color
    // instead of the XML default black.
    setWindowBackground(isDark)
}

/**
 * Theme-flip sync: re-applies `enableEdgeToEdge` with the resolved bar style
 * and swaps the window background. Re-running `enableEdgeToEdge` is the only
 * way the system bar FILL repaints — `isAppearanceLight*Bars =` alone only
 * changes the icon color and the bar fill stays on the leaving theme's
 * contrast-scrim decision (the gray band visible in the previous build's
 * dark→light flip). The previous run kept this lightweight to avoid
 * re-fitting the window on every flip, but that left a guaranteed
 * wrong-color band. Land the background on the target color first (see
 * `onThemeChanged` in setContent) so a 1-frame insets re-dispatch is
 * invisible, and re-apply the bar style here. Called from a `SideEffect`,
 * which runs after a successful recomposition and before the frame is
 * dispatched — so the bar appearance and the Compose color swap land in
 * the same frame.
 */
private fun MainActivity.syncSystemBars(isDark: Boolean) {
    val style = if (isDark) {
        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
    } else {
        SystemBarStyle.light(
            android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT
        )
    }
    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    setWindowBackground(isDark)
}

/**
 * Points the window background at the TARGET theme's background color so any
 * region exposed mid-transition (the system-bar band, over-scroll, etc.)
 * shows the theme the app is switching TO, never the one it is leaving.
 */
private fun MainActivity.setWindowBackground(isDark: Boolean) {
    window.setBackgroundDrawable(
        ColorDrawable((if (isDark) DarkAnonColors else LightAnonColors).background.toArgb())
    )
}
