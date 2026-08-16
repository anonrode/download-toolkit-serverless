package com.anonrode.downloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.anonrode.downloader.ui.screens.*
import com.anonrode.downloader.ui.theme.AnonDownloaderTheme
import com.anonrode.downloader.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var activeSocialTarget = mutableStateOf<Pair<String, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            installSplashScreen()
        } catch (_: Throwable) {}

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleShareIntent(intent)

        setContent {
            AnonDownloaderTheme {
                val uiState by viewModel.uiState.collectAsState()
                var currentScreen by remember { mutableStateOf("home") }
                var showSettings by remember { mutableStateOf(false) }
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

                BackHandler(enabled = currentScreen != "home") {
                    currentScreen = "home"
                }

                // Guaranteed-visible splash: the system SplashScreen API dismisses
                // on first frame (never seen on fast devices), so hold a designed
                // Compose splash for a short beat before revealing the app.
                var showSplash by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(1100)
                    showSplash = false
                }
                if (showSplash) {
                    SplashContent()
                    return@AnonDownloaderTheme
                }

                when (currentScreen) {
                    "home" -> {
                        HomeScreen(
                            viewModel = viewModel,
                            onOpenDownloads = { currentScreen = "downloads" },
                            onOpenSettings = { showSettings = true },
                            onOpenSocial = { platform, url ->
                                activeSocialTarget.value = Pair(platform, url)
                            }
                        )
                    }
                    "downloads" -> {
                        DownloadsScreen(
                            viewModel = viewModel,
                            onBack = { currentScreen = "home" }
                        )
                    }
                }

                if (showSettings) {
                    SettingsSheet(
                        viewModel = viewModel,
                        onDismiss = { showSettings = false }
                    )
                }

                if (socialTarget != null) {
                    val (platform, url) = socialTarget!!
                    SocialModal(
                        platform = platform,
                        url = url,
                        viewModel = viewModel,
                        onDismiss = { activeSocialTarget.value = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                viewModel.handlePastedInput(sharedText) { platform, url ->
                    activeSocialTarget.value = Pair(platform, url)
                }
            }
        }
    }
}
