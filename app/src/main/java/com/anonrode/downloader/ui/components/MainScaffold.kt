package com.anonrode.downloader.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.anonrode.downloader.ui.screens.DownloadsScreen
import com.anonrode.downloader.ui.screens.HomeScreen
import com.anonrode.downloader.ui.screens.SettingsScreen
import com.anonrode.downloader.ui.theme.AccentPrimary
import com.anonrode.downloader.ui.theme.BackgroundDark
import com.anonrode.downloader.ui.theme.SurfaceElevated
import com.anonrode.downloader.ui.theme.TextMuted
import com.anonrode.downloader.viewmodel.MainViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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
 *  - No nav-graph library: a `rememberSaveable` String picks the active tab
 *    and all three screens stay composed (see TabPage), so switching is a
 *    z-order flip — instant, with scroll state preserved.
 *  - Per-tab [BackHandler]s route the system back gesture to the right place
 *    without bouncing through the default exit-app behaviour.  Search's
 *    handler is disabled so a back press there exits the app as before.
 *  - The Downloads badge is observed in a leaf composable ([DownloadsBadge])
 *    with a distinctUntilChanged count flow, so progress ticks never
 *    recompose the scaffold or the active screen (paused tasks are parked,
 *    not active, and stay off the badge).
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

    // Stable callbacks: recreating them on every recomposition would defeat
    // skipping in the tab screens below.
    val openSearch = remember { { currentTab = MainTab.SEARCH } }
    val openDownloads = remember { { currentTab = MainTab.DOWNLOADS } }
    val openSettings = remember { { currentTab = MainTab.SETTINGS } }

    // NOTE: the scaffold deliberately does NOT collect engine.tasks here.
    // Progress ticks emit several times per second while downloading; an
    // observation at this level recomposed the whole scaffold — bottom bar
    // AND the active screen — on every tick, which is what made taps feel
    // laggy app-wide. Only the badge leaf below observes the task list.
    Scaffold(
        containerColor = BackgroundDark,
        bottomBar = {
            BottomNavBar(
                currentTab = currentTab,
                viewModel = viewModel,
                onTabSelected = { currentTab = it }
            )
        }
    ) { innerPadding ->
        // The Scaffold padding already accounts for the bottom bar; the
        // individual screens own their own status-bar padding because their
        // own background surface extends behind it.
        //
        // All three tabs STAY COMPOSED and only the active one is shown
        // (z-order + alpha). Switching tabs is therefore a draw-layer flip,
        // not a teardown + rebuild — instant, and scroll positions survive.
        // The old `when(currentTab)` rebuilt the destination screen from
        // scratch on every tap, which read as a slow tab switch.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TabPage(active = currentTab == MainTab.SEARCH) {
                HomeScreen(
                    viewModel = viewModel,
                    onOpenDownloads = openDownloads,
                    onOpenSettings = openSettings,
                    onOpenSocial = onOpenSocial
                )
            }
            TabPage(active = currentTab == MainTab.DOWNLOADS) {
                DownloadsScreen(
                    viewModel = viewModel,
                    onBack = openSearch
                )
            }
            TabPage(active = currentTab == MainTab.SETTINGS) {
                SettingsScreen(
                    viewModel = viewModel,
                    themeMode = themeMode,
                    onThemeChanged = onThemeChanged,
                    onBack = openSearch
                )
            }
        }
    }
}

/** One always-composed tab layer. The active layer sits on top and swallows
 *  stray taps (a plain Box would let them fall through to the hidden page). */
@Composable
private fun TabPage(active: Boolean, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(if (active) 1f else 0f)
            .graphicsLayer { alpha = if (active) 1f else 0f }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = active,
                onClick = {}
            )
    ) {
        content()
    }
}

@Composable
private fun BottomNavBar(
    currentTab: String,
    viewModel: MainViewModel,
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
                icon = { DownloadsBadge(viewModel) },
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

/** Downloads-tab badge, isolated so the task-list observation recomposes
 *  only this icon. The derived flow emits only when the COUNT changes
 *  (distinctUntilChanged), so progress ticks that don't change the number
 *  of active tasks never touch composition at all. */
@Composable
private fun DownloadsBadge(viewModel: MainViewModel) {
    val badgeCount by remember {
        viewModel.engine.tasks
            .map { downloadsStats(it).badgeCount }
            .distinctUntilChanged()
    }.collectAsState(initial = 0)

    if (badgeCount > 0) {
        BadgedBox(badge = {
            Badge(
                containerColor = AccentPrimary,
                contentColor = BackgroundDark
            ) {
                Text(
                    text = if (badgeCount > 99) "99+" else badgeCount.toString(),
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
}
