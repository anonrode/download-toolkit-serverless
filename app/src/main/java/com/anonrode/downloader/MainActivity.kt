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
import com.anonrode.downloader.data.router.ParsedUrl
import com.anonrode.downloader.data.router.UrlRouter
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

                BackHandler(enabled = currentScreen != "home") {
                    currentScreen = "home"
                }

                when (currentScreen) {
                    "home" -> {
                        HomeScreen(
                            viewModel = viewModel,
                            onOpenDownloads = { currentScreen = "downloads" },
                            onOpenSettings = { showSettings = true },
                            onOpenSocialModal = { platform, url ->
                                activeSocialTarget.value = Pair(platform, url)
                            }
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

                        socialTarget?.let { (platform, url) ->
                            SocialModal(
                                platformName = platform,
                                url = url,
                                viewModel = viewModel,
                                onDismiss = { activeSocialTarget.value = null }
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
                when (val parsed = UrlRouter.parse(cleanUrl)) {
                    is ParsedUrl.DramaUrl -> {
                        viewModel.openEpisodeDrawer(parsed.showCard)
                    }
                    is ParsedUrl.SocialUrl -> {
                        if (viewModel.engine.instantSocialDownload) {
                            viewModel.engine.enqueue(
                                showTitle = "Social",
                                episodeNum = 1,
                                episodeTitle = "${parsed.platform} Video",
                                sourceUrl = parsed.cleanUrl,
                                isDirect = false,
                                backend = "yt-dlp",
                                parallelSockets = 1
                            )
                            Toast.makeText(this, "Downloading from ${parsed.platform}...", Toast.LENGTH_SHORT).show()
                        } else {
                            activeSocialTarget.value = Pair(parsed.platform, parsed.cleanUrl)
                        }
                    }
                    is ParsedUrl.DirectMediaUrl -> {
                        viewModel.engine.enqueue(
                            showTitle = "Direct Downloads",
                            episodeNum = 1,
                            episodeTitle = parsed.filename,
                            sourceUrl = parsed.url,
                            isDirect = true,
                            backend = "aria2c",
                            parallelSockets = 16
                        )
                        Toast.makeText(this, "Downloading ${parsed.filename}...", Toast.LENGTH_SHORT).show()
                    }
                    is ParsedUrl.SearchQuery -> {
                        viewModel.onQueryChanged(parsed.query)
                        viewModel.search(parsed.query)
                    }
                }
            }
        }
    }

    private fun extractCleanUrl(text: String): String? {
        val urlRegex = Regex("""https?://[^\s<>"{}|\^`]+""")
        val match = urlRegex.find(text)
        return match?.value?.trim()
    }
}
