package com.anonrode.downloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
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
        // Cold-start: pick the icon tint that matches the persisted theme so
        // the first frame already has the right system-bar contrast. Without
        // this, light mode launches with white-on-near-white status icons
        // (invisible) until the SideEffect below re-runs on the first
        // composition. The `dark()` overload is the no-arg fallback
        // `enableEdgeToEdge()` already uses.
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

            // Re-apply edge-to-edge whenever the user toggles the theme. The
            // no-arg overload of enableEdgeToEdge() inspects the SYSTEM theme
            // (not ours), so light mode would keep dark icons on a near-white
            // background. `SystemBarStyle.light` flips the icons dark for
            // light theme; `dark` keeps them light. Both styles use a fully
            // transparent scrim so the app's surface bleeds through.
            // Keyed LaunchedEffect (not SideEffect): this now runs on first
            // composition and on theme flips only, instead of re-invoking
            // enableEdgeToEdge after EVERY recomposition of this scope —
            // each call re-dispatches window insets and added a subtle hitch
            // whenever top-level state changed.
            LaunchedEffect(themeMode) { applyEdgeToEdge(themeMode) }

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
                // tapping the app icon.
                var showSplash by remember { mutableStateOf(true) }
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
 * Re-applies the activity's edge-to-edge system-bar styling to match the
 * app's current theme. The no-arg `enableEdgeToEdge()` is theme-agnostic
 * (it inspects the system theme, not ours) and produces invisible status
 * icons when light mode is active on a near-white app background. This
 * helper is called from `onCreate` (cold start) and from a Compose
 * `SideEffect` keyed on `themeMode` (every toggle), so the bars always
 * match the surface underneath.
 */
private fun MainActivity.applyEdgeToEdge(themeMode: String) {
    val style = if (themeMode.equals("light", ignoreCase = true)) {
        // Light theme: dark icons on a transparent bar (the app's near-white
        // surface bleeds through). `light(scrim, darkScrim)` both transparent
        // because the app surface is always a clean light color and the OS
        // scrim is only needed when the underlying content might be unreadable.
        SystemBarStyle.light(
            android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT
        )
    } else {
        // Dark theme (or unknown): light icons on a transparent bar. Matches
        // the no-arg `enableEdgeToEdge()` behavior the app had before this
        // change, so dark mode is unchanged for existing users.
        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
    }
    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
}
