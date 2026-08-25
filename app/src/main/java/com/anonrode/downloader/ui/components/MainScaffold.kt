package com.anonrode.downloader.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anonrode.downloader.ui.screens.DownloadsScreen
import com.anonrode.downloader.ui.screens.HomeScreen
import com.anonrode.downloader.ui.screens.SettingsScreen
import com.anonrode.downloader.ui.theme.AccentPrimary
import com.anonrode.downloader.ui.theme.BackgroundDark
import com.anonrode.downloader.ui.theme.SurfaceElevated
import com.anonrode.downloader.ui.theme.TextMuted
import com.anonrode.downloader.viewmodel.MainViewModel

/** Stable identifiers for the three top-level tabs.  Persisted as
 *  `pref_last_tab` strings so a deep link / external launch that lands on
 *  Settings still restores correctly after a process kill. */
object MainTab {
    const val SEARCH = "search"
    const val DOWNLOADS = "downloads"
    const val SETTINGS = "settings"
    const val DEFAULT = SEARCH
}

/**
 * The new three-tab bottom-nav scaffold (Search / Downloads / Settings).
 *
 *  - No nav-graph library: a `when` over a `rememberSaveable` String is enough
 *    for three screens and avoids the state-loss foot-guns of `Crossfade`.
 *  - Per-tab [BackHandler]s route the system back gesture to the right place
 *    without bouncing through the default exit-app behaviour.  Search's
 *    handler is disabled so a back press there exits the app as before.
 *  - The active Downloads-badge count comes straight from
 *    [MainViewModel.engine] via [downloadsStats], recomputed on every
 *    recomposition so it tracks in-flight + paused tasks live.
 *  - Theme is unchanged: the bottom bar uses `MaterialTheme.colorScheme.surface`
 *    for its background, which the existing `AnonDownloaderTheme` already
 *    maps cleanly in both light and dark.
 *
 *  `initialTab` is read by the caller from SharedPreferences in `onCreate`
 *  so the restore decision lives outside the composable (no read of Android
 *  Context from inside Compose).  Subsequent tab switches are written back
 *  via a [LaunchedEffect] keyed on the tab id so the pref is always current.
 */
@Composable
fun MainScaffold(
    viewModel: MainViewModel,
    initialTab: String,
    themeMode: String,
    onThemeChanged: (String) -> Unit,
    onOpenSocial: (String, String) -> Unit,
    onWriteTabPref: (String) -> Unit
) {
    var currentTab by rememberSaveable { mutableStateOf(initialTab) }

    // -- System back routing --
    // One handler per branch keeps each rule on one line and avoids the
    // "is the handler even installed" gotcha of a single conditional enabled
    // flag.  Search's handler is intentionally absent so back exits the app.
    BackHandler(enabled = currentTab == MainTab.DOWNLOADS) {
        currentTab = MainTab.SEARCH
    }
    BackHandler(enabled = currentTab == MainTab.SETTINGS) {
        currentTab = MainTab.SEARCH
    }

    // Persist every tab change.  `DisposableEffect(Unit)` with a
    // `currentTab` snapshot at disposal would lag the latest switch; use a
    // LaunchedEffect keyed on currentTab instead so the write fires on every
    // transition and never on first composition.
    LaunchedEffect(currentTab) {
        if (currentTab != initialTab) onWriteTabPref(currentTab)
    }

    val tasks by viewModel.engine.tasks.collectAsState()
    val stats = remember(tasks) { downloadsStats(tasks) }

    Scaffold(
        containerColor = BackgroundDark,
        bottomBar = {
            BottomNavBar(
                currentTab = currentTab,
                downloadsBadgeCount = stats.badgeCount,
                onTabSelected = { currentTab = it }
            )
        }
    ) { innerPadding ->
        // The Scaffold padding already accounts for the bottom bar; the
        // individual screens own their own status-bar padding because their
        // own background surface extends behind it.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                MainTab.SEARCH -> {
                    HomeScreen(
                        viewModel = viewModel,
                        onOpenDownloads = { currentTab = MainTab.DOWNLOADS },
                        onOpenSettings = { currentTab = MainTab.SETTINGS },
                        onOpenSocial = onOpenSocial
                    )
                }
                MainTab.DOWNLOADS -> {
                    DownloadsScreen(
                        viewModel = viewModel,
                        onBack = { currentTab = MainTab.SEARCH }
                    )
                }
                MainTab.SETTINGS -> {
                    SettingsScreen(
                        viewModel = viewModel,
                        themeMode = themeMode,
                        onThemeChanged = onThemeChanged,
                        onBack = { currentTab = MainTab.SEARCH }
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomNavBar(
    currentTab: String,
    downloadsBadgeCount: Int,
    onTabSelected: (String) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        ) {
            NavigationBarItem(
                selected = currentTab == MainTab.SEARCH,
                onClick = { onTabSelected(MainTab.SEARCH) },
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = "Search"
                    )
                },
                label = {
                    Text(
                        text = "Search",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                colors = navBarItemColors()
            )
            NavigationBarItem(
                selected = currentTab == MainTab.DOWNLOADS,
                onClick = { onTabSelected(MainTab.DOWNLOADS) },
                icon = {
                    if (downloadsBadgeCount > 0) {
                        BadgedBox(badge = {
                            Badge(
                                containerColor = AccentPrimary,
                                contentColor = BackgroundDark
                            ) {
                                Text(
                                    text = if (downloadsBadgeCount > 99) "99+" else downloadsBadgeCount.toString(),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Rounded.Download,
                                contentDescription = "Downloads"
                            )
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = "Downloads"
                        )
                    }
                },
                label = {
                    Text(
                        text = "Downloads",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                colors = navBarItemColors()
            )
            NavigationBarItem(
                selected = currentTab == MainTab.SETTINGS,
                onClick = { onTabSelected(MainTab.SETTINGS) },
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = "Settings"
                    )
                },
                label = {
                    Text(
                        text = "Settings",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                colors = navBarItemColors()
            )
        }
    }
}

@Composable
private fun navBarItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = AccentPrimary,
    selectedTextColor = AccentPrimary,
    unselectedIconColor = TextMuted,
    unselectedTextColor = TextMuted,
    indicatorColor = SurfaceElevated
)
