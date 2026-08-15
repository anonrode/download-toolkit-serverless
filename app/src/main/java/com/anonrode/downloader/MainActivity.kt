package com.anonrode.downloader

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.anonrode.downloader.service.DownloadService
import com.anonrode.downloader.ui.screens.*
import com.anonrode.downloader.ui.theme.AnonDownloaderTheme
import com.anonrode.downloader.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var activeSharedUrl = mutableStateOf<String?>(null)

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
                val socialUrl by activeSharedUrl

                BackHandler(enabled = currentScreen != "home") {
                    currentScreen = "home"
                }

                when (currentScreen) {
                    "home" -> {
                        HomeScreen(
                            viewModel = viewModel,
                            onOpenDownloads = { currentScreen = "downloads" },
                            onOpenSettings = { showSettings = true },
                            onOpenSocialModal = { url -> activeSharedUrl.value = url }
                        )

                        uiState.activeShowForDrawer?.let { show ->
                            EpisodeDrawer(
                                show = show,
                                viewModel = viewModel,
                                onDismiss = { viewModel.closeEpisodeDrawer() }
                            )
                        }

                        if (showSettings) {
                            SettingsSheet(
                                viewModel = viewModel,
                                onDismiss = { showSettings = false }
                            )
                        }

                        socialUrl?.let { url ->
                            SocialModal(
                                url = url,
                                viewModel = viewModel,
                                onDismiss = { activeSharedUrl.value = null }
                            )
                        }
                    }
                    "downloads" -> {
                        DownloadsScreen(
                            viewModel = viewModel,
                            onBack = { currentScreen = "home" }
                        )
                    }
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
            val rawText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            val cleanUrl = extractCleanUrl(rawText)

            if (!cleanUrl.isNullOrBlank()) {
                if (viewModel.engine.instantSocialDownload) {
                    val platform = getPlatformLabel(cleanUrl)
                    viewModel.engine.enqueue(
                        showTitle = "Social",
                        episodeNum = 1,
                        episodeTitle = "$platform Video",
                        sourceUrl = cleanUrl,
                        isDirect = false,
                        backend = "yt-dlp",
                        parallelSockets = 1
                    )
                    Toast.makeText(this, "Downloading from $platform...", Toast.LENGTH_SHORT).show()
                } else {
                    activeSharedUrl.value = cleanUrl
                }
            }
        }
    }

    private fun extractCleanUrl(text: String): String? {
        val urlRegex = Regex("https?://[^\\s<>\"{}|\\^`]+")
        val match = urlRegex.find(text)
        return match?.value?.trim()
    }

    private fun getPlatformLabel(url: String): String {
        return when {
            url.contains("instagram.com") -> "Instagram"
            url.contains("youtube.com") || url.contains("youtu.be") -> "YouTube"
            url.contains("tiktok.com") -> "TikTok"
            url.contains("twitter.com") || url.contains("x.com") -> "Twitter"
            else -> "Media"
        }
    }
}
