package com.anonrode.downloader.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.SubcomposeAsyncImage
import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus
import com.anonrode.downloader.ui.components.DownloadsSorter
import com.anonrode.downloader.ui.components.SegmentBar
import com.anonrode.downloader.ui.components.SegmentCell
import com.anonrode.downloader.ui.components.downloadsStats
import com.anonrode.downloader.ui.theme.*
import com.anonrode.downloader.ui.util.rejectHaptic
import com.anonrode.downloader.viewmodel.MainViewModel
import java.io.File

private const val PREF_SORT = "downloader_settings"
private const val PREF_SORT_KEY = "pref_downloads_sort"

// ─── Downloads v6 status chroma (mock: ui-proposal-downloads-v6-drawer.html) ─────
// The light paper theme stays; state colour is defined HERE (Theme.kt colors
// untouched). State is ALWAYS colour + WORD + GLYPH — never colour alone
// (see downloadStatusStyle below). Mock :root tokens:
//   --live:#0E7490 --live-tint:#E0F2F7 --live-fill:#1591B3
//   --done:#1F7A4D --done-tint:#E6F4EC
//   --paused:#92610F --paused-tint:#FBF1DE
//   --failed:#A84A42 --failed-tint:#F9E9E7 --failed-acc:#C25B52
private val LiveWord = Color(0xFF0E7490)
private val LiveTint = Color(0xFFE0F2F7)
private val LiveFill = Color(0xFF1591B3)
private val DoneWord = Color(0xFF1F7A4D)
private val DoneTint = Color(0xFFE6F4EC)
private val PausedWord = Color(0xFF92610F)
private val PausedTint = Color(0xFFFBF1DE)
private val FailedWord = Color(0xFFA84A42)
private val FailedTint = Color(0xFFF9E9E7)
// Mock --failed-acc: the filled RETRY circle, the rust stall meter and the
// dashed rule above the failure reason (the one non-chrome hue on a card).
private val FailRust = Color(0xFFC25B52)
private val MeterTrack = Color(0xFFE2E8EF)        // live meter track (mock .meter)
private val ChipNeutralBg = Color(0xFFEEF2F6)     // neutral site tag (mock --chipbg)
private val SiteTagInk = Color(0xFF475569)        // neutral site tag text (mock --sub)
private val ThumbTile = Color(0xFFE9EFF5)         // initials tile well (mock .thumb .init)
private val DashRust = Color(0xFFEFD5D2)          // dashed rule (mock .errline border-top)
private val ProgressFillGray = Color(0xFF9AA0A8)  // whole-screen storage meter pin (unchanged)
private val ShelfTileDeep = Color(0xFF0C0E11)     // offline-shelf tile well (mock --deep)
// Glass scrims & hairlines, cited from the mock CSS (.back / .pl / .thumb):
private val GlassBackScrim = Color(0xB8000000)    // header back circle: black at 72% (0xB8)
private val GlassBackOutline = Color(0x17FFFFFF)  // header back outline: white at 9% (0x17 = .09·255)
private val GlassPlayScrim = Color(0x80000000)    // shelf play circle: black at 50% (0x80)
private val GlassPlayOutline = Color(0x24FFFFFF)  // shelf play outline: white at 14% (0x24 = .14·255)

@Composable
fun DownloadsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onPlayTask: (DownloadTask) -> Unit
) {
    val tasks by viewModel.engine.tasks.collectAsState()
    // Storage figures for the header meta line + meter come from the SAME
    // source the Settings hub footer uses: HomeUiState free/totalStorageGb,
    // refreshed by MainViewModel.refreshStorageInfo() (StatFs on IO). No new
    // data source is added — the observer below re-reads on every resume
    // exactly like the Settings sheet does, so the numbers never go stale
    // after a download or deletion while the tabs stay composed.
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    // Destructive confirms get a weighty reject haptic (UI research round):
    // it is the one place the UI already pauses to ask, so the answer should
    // land physically. Default flags — a user with haptics off hears nothing.
    val hapticView = LocalView.current

    // Same lifecycle wiring the Settings sheet uses (ON_RESUME →
    // refreshStorageInfo): MainScaffold keeps all three tabs composed, so a
    // one-shot LaunchedEffect would let the GB numbers go stale.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshStorageInfo()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Read the persisted sort mode at composition. Default is "date" so
    // a fresh install matches the prototype's default tab.  A missing key
    // is treated the same as "date" so an upgrade from the old build lands
    // in the familiar date-grouped view.
    val initialSort = remember {
        val raw = context.getSharedPreferences(PREF_SORT, Context.MODE_PRIVATE)
            .getString(PREF_SORT_KEY, DownloadsSorter.SORT_DATE)
        if (raw != null && raw in DownloadsSorter.ALL_MODES) raw else DownloadsSorter.SORT_DATE
    }
    var sortMode by remember { mutableStateOf(initialSort) }
    // A completed row's actions dialog ("More actions") is the kebab /
    // tile long-press surface: share AND delete, both reusing the existing
    // flows (shareMedia chooser + the gated pendingDeleteTask confirm).
    var pendingActionsTask by remember { mutableStateOf<DownloadTask?>(null) }
    // A completed card's "Delete" removes the FINISHED FILE (purgeTaskArtifacts
    // + remove is irreversible) — like "Cancel all" used to be, it is the only
    // delete path WITHOUT an extra confirm gate.
    var pendingDeleteTask by remember { mutableStateOf<DownloadTask?>(null) }

    // The player overlay is hosted by MainActivity at the ROOT of the window
    // tree (see MediaPlayerModal's comment): rendered inside this screen it
    // would sit within the Scaffold's content area and leave the bottom nav
    // strip visible — one of the reasons the old in-Scaffold layout read as
    // "not fullscreen". Tapping play just hands the task up.

    // Build age-override map so "Date added" groups use the task's real
    // enqueue timestamp (task.createdAt, set by the engine at enqueue).
    // Tasks persisted by older builds carry createdAt == 0; for those the
    // position in engine.tasks stands in — the repository seeds via
    // addFirst, so index 0 is the newest entry.
    //
    // Keyed on a structural signature (id:status per row) rather than the
    // task list itself: progress ticks mutate downloadedBytes constantly but
    // never the structure, so the sort + grouping no longer re-runs several
    // times per second during downloads.
    val structureSig = remember(tasks) {
        buildString { tasks.forEach { append(it.id).append(':').append(it.status.name).append('|') } }
    }

    // v5 zone split (DownloadsSorter.splitShelf): finished direct-media
    // downloads (social/one-off files, showTitle "Direct Downloads") leave
    // the row groups and tile onto the Social & Media shelf; every other
    // task (including in-flight direct files) stays an activity row.
    // Live objects are re-mapped below, the same way the grouped rows are.
    val shelfItems = remember(tasks) { tasks.filter { DownloadsSorter.isShelfItem(it) } }

    val groups = remember(structureSig, sortMode) {
        val snapshot = viewModel.engine.tasks.value.filterNot { DownloadsSorter.isShelfItem(it) }
        if (snapshot.isEmpty()) emptyList()
        else {
            // Re-seed age overrides whenever the structure actually changes;
            // ids no longer present are dropped by the next clear.
            DownloadsSorter.clearAgeOverrides()
            val nowMs = System.currentTimeMillis()
            snapshot.forEachIndexed { index, task ->
                // Real clock age in whole days since enqueue; the positional
                // fallback keeps ordering sane for pre-upgrade tasks. The
                // TODAY/YESTERDAY/THIS WEEK buckets fall out of those values.
                val daysAgo = if (task.createdAt > 0L) {
                    ((nowMs - task.createdAt) / 86_400_000L).coerceAtLeast(0L)
                } else {
                    index.toLong()
                }
                DownloadsSorter.setAgeOverride(task.id, daysAgo)
            }
            val sorted = DownloadsSorter.sortDownloads(snapshot, sortMode)
            if (sortMode != DownloadsSorter.SORT_DATE) sorted else {
                // v6 mock frame 1: the transferring set leads the date view in
                // its own "ACTIVE" section (mock .sect ACTIVE), above the date
                // buckets — the same active set the summary line counts
                // (DOWNLOADING / RESOLVING / VALIDATING; QUEUED stays parked in
                // its date bucket). Pure screen-level regrouping: the sorter
                // and its tests are untouched.
                val activeIds = snapshot.asSequence()
                    .filter {
                        it.status == TaskStatus.DOWNLOADING ||
                            it.status == TaskStatus.RESOLVING ||
                            it.status == TaskStatus.VALIDATING
                    }
                    .mapTo(mutableSetOf()) { it.id }
                if (activeIds.isEmpty()) sorted
                else buildList {
                    add("ACTIVE" to sorted.flatMap { it.second }.filter { it.id in activeIds })
                    sorted.forEach { (header, items) ->
                        val rest = items.filterNot { it.id in activeIds }
                        if (rest.isNotEmpty()) add(header to rest)
                    }
                }
            }
        }
    }

    // The sort above is keyed on structure (ids + statuses) so it doesn't
    // re-run on every progress tick — but that also freezes the task OBJECTS
    // captured in the snapshot, so cards would render stale byte counts
    // between structural changes (the "notification moves, app doesn't"
    // bug). Remap the grouped ids onto the freshest objects each
    // composition: O(n) map, no re-sort, no age-override churn.
    val tasksById = remember(tasks) { tasks.associateBy { it.id } }
    val liveGroups = remember(groups, tasks) {
        groups.map { (header, items) -> header to items.mapNotNull { tasksById[it.id] } }
    }

    // Header meta counts (mock: "26.4 GB of 112 GB · 45 done · 0 active · 1
    // failed"). Counts are structural → remembered on the signature.
    val stats = remember(structureSig) { downloadsStats(viewModel.engine.tasks.value) }

    // Track 4 ("Download Remaining"): per-series count of episodes still
    // missing from the library. seriesUrl/seriesTotal are stamped on tasks by
    // the episode drawer at enqueue time (older builds and direct downloads
    // leave them blank/0 → no entry → no chip). The drawer recomputes the
    // exact live set from the provider when the chip opens it; this count is
    // the card-level hint, keyed on the same clean show title the drawer uses
    // for its folder name.
    val seriesRemaining = remember(structureSig) {
        val byShow = viewModel.engine.tasks.value
            .filter { it.seriesUrl.isNotBlank() }
            .groupBy { it.showTitle }
        byShow.mapValues { (_, items) ->
            (items.maxOf { it.seriesTotal } - items.size).coerceAtLeast(0)
        }.filterValues { it > 0 }
    }
    val seriesInfo = remember(structureSig) {
        val byShow = viewModel.engine.tasks.value
            .filter { it.seriesUrl.isNotBlank() }
            .groupBy { it.showTitle }
        byShow.mapValues { (_, items) ->
            val url = items.firstOrNull { it.seriesUrl.isNotBlank() }?.seriesUrl.orEmpty()
            val site = items.firstOrNull { it.site.isNotBlank() }?.site.orEmpty()
            url to site
        }
    }

    // Header summary line (v6 mock .dl-sub): "X GB of Y GB · N active · N done
    // · N failed" — each count (number AND word) in that state's colour; the
    // words keep the state readable, so colour is never the only signal.
    // Storage part is whole-GB (the Settings footer exposes whole-GB Longs —
    // mock shows a decimal, we degrade to the spec's fallback format "X GB of
    // Y GB"); counts-only when StatFs has not reported yet (total == 0).
    val storagePart = if (uiState.totalStorageGb > 0L) {
        val usedGb = (uiState.totalStorageGb - uiState.freeStorageGb).coerceAtLeast(0L)
        "$usedGb GB of ${uiState.totalStorageGb} GB"
    } else null
    val summaryLine = buildAnnotatedString {
        if (storagePart != null) {
            append(storagePart)
            append(" · ")
        }
        withStyle(SpanStyle(color = LiveWord, fontWeight = FontWeight.Bold)) {
            append("${stats.active} active")
        }
        append(" · ")
        withStyle(SpanStyle(color = DoneWord, fontWeight = FontWeight.Bold)) {
            append("${stats.done} done")
        }
        append(" · ")
        withStyle(SpanStyle(color = FailedWord, fontWeight = FontWeight.Bold)) {
            append("${stats.failed} failed")
        }
    }

    // Actions rail (v6): "Resume all" keeps its existing behaviour (shows only
    // when something is paused); the v5 "Retry failed (N)" toolbar pill is
    // superseded by the mock's per-card RETRY circle — every failed row now
    // carries its own filled retry affordance.
    val hasPaused = tasks.any { it.status == TaskStatus.PAUSED }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            // statusBarsPadding only: the outer Scaffold already clears the
            // bottom bar — navigationBarsPadding() on top of it added a
            // second nav-bar-height dead band above the bottom bar.
            .statusBarsPadding()
            .padding(horizontal = Spacing.lg)
    ) {
        // ── HEADER (mock: .back 38dp glass circle left 16 top 52; title x=66) ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(GlassBackScrim)
                    .border(1.dp, GlassBackOutline, CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "DOWNLOADS & MEDIA",
                    style = Type.screenTitle,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = summaryLine,
                    fontSize = 11.sp,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // ── STORAGE METER (mock .meter: 2dp, radius 1, track elev, fill gray) ──
        if (uiState.totalStorageGb > 0L) {
            val usedGb = (uiState.totalStorageGb - uiState.freeStorageGb).coerceAtLeast(0L)
            val fillFraction = (usedGb.toFloat() / uiState.totalStorageGb.toFloat()).coerceIn(0f, 1f)
            val animatedFill by animateFloatAsState(
                targetValue = fillFraction,
                animationSpec = tween(durationMillis = 350),
                label = "storageMeter"
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(SurfaceElevated)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedFill)
                        .fillMaxHeight()
                        .background(ProgressFillGray)
                )
            }
        }

        // ── SORT RAIL: canonical 32dp segmented bar (notebook §3 / mock .seg) ──
        DownloadsSortRail(
            selectedMode = sortMode,
            onSelectMode = { mode ->
                sortMode = mode
                context.getSharedPreferences(PREF_SORT, Context.MODE_PRIVATE)
                    .edit().putString(PREF_SORT_KEY, mode).apply()
            },
            modifier = Modifier.padding(top = 16.dp)
        )

        // ── ACTIONS RAIL (mock .actions): "Resume all" ghost on the left, the
        // right-aligned status legend riding the same single-line row —
        // everything one line, nothing wraps. ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (hasPaused) {
                ToolbarPill(
                    label = "Resume all",
                    icon = Icons.Rounded.PlayArrow,
                    iconTint = TextPrimary,
                    labelColor = TextPrimary,
                    onClick = { viewModel.engine.resumeAll() }
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            DownloadsStatusLegend()
        }

        if (tasks.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.DownloadDone,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Text("No active or completed downloads", color = TextSecondary, fontSize = Type.rowTitle.fontSize)
                    // The empty state should say what to do NEXT, not just
                    // name the void — one tap back to the search tab.
                    TextButton(onClick = onBack) {
                        Text("Find something to download", color = AccentPrimary, fontSize = Type.body.fontSize, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            val expandedShows = remember { mutableStateMapOf<String, Boolean>() }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                modifier = Modifier.padding(top = 20.dp).fillMaxSize()
            ) {
                if (sortMode == DownloadsSorter.SORT_LIBRARY) {
                    items(liveGroups, key = { "show-${it.first}" }) { (showTitle, showItems) ->
                        val isExpanded = expandedShows[showTitle] ?: (showItems.size == 1 || showItems.any {
                            it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.RESOLVING
                        })
                        val info = seriesInfo[showTitle]
                        ShowFolderCard(
                            showTitle = showTitle,
                            items = showItems,
                            isExpanded = isExpanded,
                            onToggleExpand = {
                                expandedShows[showTitle] = !isExpanded
                            },
                            remainingCount = seriesRemaining[showTitle] ?: 0,
                            onDownloadRemaining = if (info != null) {
                                {
                                    viewModel.openRemainingEpisodes(showTitle, info.first, info.second)
                                }
                            } else null,
                            onPlayTask = onPlayTask,
                            onPauseTask = { viewModel.engine.pause(it) },
                            onRetryTask = { viewModel.engine.retry(it) },
                            onCancelTask = { viewModel.engine.cancel(it) },
                            onRequestActions = { pendingActionsTask = it }
                        )
                    }
                } else {
                    liveGroups.forEach { (header, groupItems) ->
                        item(key = "h-$header") {
                            // Track-4 inline action at group level: rendered only
                            // when the group's remaining pickups belong to ONE
                            // show — the chip opens a per-show drawer, so a
                            // multi-show group has no single unambiguous target.
                            // (Library mode keeps the per-show action on the
                            // folder, so the shortcut never depends on the sort.)
                            val remainingShow = groupItems.map { it.showTitle }
                                .distinct()
                                .filter { (seriesRemaining[it] ?: 0) > 0 }
                                .singleOrNull()
                            val info = remainingShow?.let { seriesInfo[it] }
                            DownloadsGroupHeader(
                                title = header,
                                count = groupItems.size,
                                remainingCount = remainingShow?.let { seriesRemaining[it] } ?: 0,
                                onDownloadRemaining = if (remainingShow != null && info != null) {
                                    { viewModel.openRemainingEpisodes(remainingShow, info.first, info.second) }
                                } else null
                            )
                        }
                        items(groupItems, key = { it.id }) { task ->
                            ActivityRow(
                                task = task,
                                onPlay = { onPlayTask(task) },
                                onPause = { viewModel.engine.pause(task.id) },
                                onRetry = { viewModel.engine.retry(task.id) },
                                onCancel = { viewModel.engine.cancel(task.id) },
                                onOpenActions = { pendingActionsTask = task }
                            )
                        }
                    }
                }

                // ── OFFLINE SHELF (mock zone "Social & Media"; ONE LazyRow max) ──
                if (shelfItems.isNotEmpty()) {
                    item(key = "shelf-zone") {
                        DownloadsGroupHeader(title = "Social & Media", count = shelfItems.size)
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(shelfItems, key = { "shelf-${it.id}" }) { item ->
                                SocialShelfTile(
                                    task = item,
                                    onPlay = { onPlayTask(item) },
                                    onOpenActions = { pendingActionsTask = item }
                                )
                            }
                        }
                    }
                    // Bottom line (mock): mono caption under the shelf zone.
                    item(key = "shelf-footnote") {
                        Text(
                            text = "Hold a tile to share or delete · ${shelfItems.size} items on this device",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp,
                            color = TextMuted,
                            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                        )
                    }
                }
            }
        }
    }

    // ⋮ / kebab actions dialog. Finished items get share-or-delete — both
    // reuse the screen's existing flows (shareMedia's ACTION_SEND chooser and
    // the gated pendingDeleteTask confirm below). FAILED rows keep Retry /
    // Remove via the same engine callbacks the row itself uses. v6: the mock's
    // cards carry no visible cancel circle, so in-flight/paused/queued rows
    // reach cancel HERE ("Cancel this download?") — same engine cancel, one
    // confirm gate, no dead ends.
    pendingActionsTask?.let { target ->
        val failedTarget = target.status == TaskStatus.FAILED
        val finishedTarget = target.status == TaskStatus.COMPLETED
        AlertDialog(
            onDismissRequest = { pendingActionsTask = null },
            shape = RoundedCornerShape(Radius.lg),
            containerColor = SurfaceCard,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text(if (failedTarget) "Retry or remove?" else if (finishedTarget) "Share or delete?" else "Cancel this download?") },
            text = {
                Text(
                    when {
                        failedTarget -> "\"${target.episodeTitle}\" did not finish. Retry now, or remove the dead task?"
                        finishedTarget -> "\"${target.episodeTitle}\" is finished and on this device."
                        else -> "\"${target.episodeTitle}\" has not finished yet. Cancel it and remove the task?"
                    },
                    color = TextSecondary,
                    fontSize = Type.body.fontSize,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    when {
                        failedTarget -> {
                            TextButton(onClick = {
                                pendingActionsTask = null
                                viewModel.engine.retry(target.id)
                            }) { Text("Retry", color = TextPrimary) }
                            TextButton(onClick = {
                                pendingActionsTask = null
                                viewModel.engine.cancel(target.id)
                            }) { Text("Remove", color = TextPrimary) }
                        }
                        finishedTarget -> {
                            TextButton(onClick = {
                                pendingActionsTask = null
                                shareMedia(context, target.filePath)
                            }) { Text("Share", color = TextPrimary) }
                            TextButton(onClick = {
                                pendingActionsTask = null
                                pendingDeleteTask = target
                            }) { Text("Delete", color = TextPrimary) }
                        }
                        else -> {
                            // The lost row-level Cancel affordance (v6 cards
                            // carry Pause/Resume + ⋮ only) — same engine call.
                            TextButton(onClick = {
                                pendingActionsTask = null
                                viewModel.engine.cancel(target.id)
                            }) { Text("Remove", color = TextPrimary) }
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingActionsTask = null }) {
                    Text("Keep it", color = TextSecondary)
                }
            }
        )
    }

    // Single-file delete confirm — mirrors the old "Cancel all" gate for the
    // other irreversible path.
    pendingDeleteTask?.let { task ->
        AlertDialog(
            onDismissRequest = { pendingDeleteTask = null },
            shape = RoundedCornerShape(Radius.lg),
            containerColor = SurfaceCard,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("Delete downloaded file?") },
            text = {
                Text(
                    "\"${task.episodeTitle}\" will be removed from storage. " +
                        "This cannot be undone.",
                    color = TextSecondary,
                    fontSize = Type.body.fontSize,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteTask = null
                        hapticView.rejectHaptic()
                        viewModel.engine.cancel(task.id)
                    }
                ) { Text("Delete", color = TextPrimary) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteTask = null }) {
                    Text("Keep file", color = TextSecondary)
                }
            }
        )
    }
}

/** Canonical 32dp segmented sort rail (notebook §3 "Sort & Segmented Bars",
 *  mock .seg): SurfaceCard container, 3dp inner padding, 32dp cells,
 *  selected pill = AccentPrimary fill + background ink, text-only labels. */
@Composable
private fun DownloadsSortRail(
    selectedMode: String,
    onSelectMode: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val modes = listOf(
        DownloadsSorter.SORT_DATE to "Date",
        DownloadsSorter.SORT_LIBRARY to "By Show",
        DownloadsSorter.SORT_STATUS to "Status",
        DownloadsSorter.SORT_SIZE to "Size"
    )

    SegmentBar(modifier = modifier, innerPadding = 3.dp) {
        modes.forEach { (mode, label) ->
            SegmentCell(
                selected = mode == selectedMode,
                onClick = { onSelectMode(mode) },
                label = label,
                modifier = Modifier.weight(1f),
                cellHeight = 32.dp
            )
        }
    }
}

/** Control pill (mock .pill): SurfaceElevated, hairline, 30dp tall, full
 *  radius, 11sp/600 label, 13dp grayscale icon via tint only. */
@Composable
private fun ToolbarPill(
    label: String,
    icon: ImageVector,
    iconTint: Color = TextPrimary,
    labelColor: Color = TextPrimary,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(Radius.full))
            .background(SurfaceElevated)
            .border(1.dp, BorderHairline, RoundedCornerShape(Radius.full))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(13.dp)
        )
        Text(
            text = label,
            color = labelColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** Group-header count colour (mock .sect .cnt): the mock's CSS paints every
 *  count live-cyan by default, and status-sorted headers override to their
 *  own state. Date/size/"Social & Media" headers therefore carry the live
 *  hue too — cited straight from the approved frame. */
internal fun groupCountColor(header: String): Color = when (header) {
    "PAUSED" -> PausedWord
    "FAILED" -> FailedWord
    "DONE", "COMPLETED" -> DoneWord
    else -> LiveWord
}

/** Group header (mock .sect): 11sp/800 caps, count in the matching state
 *  colour, then a trailing 1dp hairline rule flexing the remaining width.
 *  A custom measure keeps the mock's flex: a plain weight() pair would cap
 *  the header text at half the row and truncate long show titles far too
 *  early. Optional "Download remaining N" inline action sits before the rule. */
@Composable
private fun DownloadsGroupHeader(
    title: String,
    count: Int,
    remainingCount: Int = 0,
    onDownloadRemaining: (() -> Unit)? = null
) {
    val countColor = groupCountColor(title)
    val action: (() -> Unit)? = onDownloadRemaining?.takeIf { remainingCount > 0 }
    Layout(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm),
        content = {
            Text(
                text = title.uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                color = TextMuted,
                letterSpacing = 1.2.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.layoutId("title")
            )
            Text(
                text = " · $count",
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                color = countColor,
                maxLines = 1,
                modifier = Modifier.layoutId("count")
            )
            if (action != null) {
                Box(modifier = Modifier.layoutId("action")) {
                    InlineRemainingAction(count = remainingCount, onClick = action)
                }
            }
            Box(
                modifier = Modifier
                    .layoutId("rule")
                    .height(1.dp)
                    .background(BorderHairline)
            )
        }
    ) { measurables, constraints ->
        val titleM = measurables.first { it.layoutId == "title" }
        val countM = measurables.first { it.layoutId == "count" }
        val ruleM = measurables.first { it.layoutId == "rule" }
        val actionM = measurables.firstOrNull { it.layoutId == "action" }

        val gap = 6.dp.roundToPx()
        val loose = Constraints(minWidth = 0, minHeight = 0, maxWidth = constraints.maxWidth)
        val countP = countM.measure(loose)
        val actionP = actionM?.measure(loose)
        // Measure the title against everything except a 16dp rule sliver, so a
        // pathologically long show title still ellipsizes instead of pushing
        // the rule out; the rule then flexes whatever the text didn't use.
        val titleMax = (
            constraints.maxWidth - countP.width - gap - gap -
                (actionP?.width?.plus(gap) ?: 0) - 16.dp.roundToPx()
            ).coerceAtLeast(0)
        val titleP = titleM.measure(
            Constraints(minWidth = 0, minHeight = 0, maxWidth = titleMax)
        )
        val ruleWidth = (
            constraints.maxWidth - titleP.width - gap - countP.width - gap -
                (actionP?.width?.plus(gap) ?: 0)
            ).coerceAtLeast(0)
        val ruleP = ruleM.measure(
            Constraints(minWidth = ruleWidth, maxWidth = ruleWidth, minHeight = 0)
        )
        val height = maxOf(titleP.height, countP.height, ruleP.height, actionP?.height ?: 0)
        layout(constraints.maxWidth, height) {
            var x = 0
            titleP.placeRelative(0, (height - titleP.height) / 2)
            x += titleP.width + gap
            countP.placeRelative(x, (height - countP.height) / 2)
            x += countP.width + gap
            if (actionP != null) {
                actionP.placeRelative(x, (height - actionP.height) / 2)
                x += actionP.width + gap
            }
            ruleP.placeRelative(x, (height - ruleP.height) / 2)
        }
    }
}

/** Track-4 "Download remaining" as inline text (11sp/600 textSecondary,
 *  count in white/700 per the v5 group-header spec). */
@Composable
private fun InlineRemainingAction(count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.full))
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.Download,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = "Download remaining ",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextSecondary
        )
        Text(
            text = count.toString(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
    }
}

/** v6 activity card (mock .card): white card on the paper background, hairline
 *  border, radius 18, padding 12/14; 46×58 poster/initials thumb, 13sp/700
 *  single-line title, 11sp/600 status line (glyph dot + coloured word + grey
 *  detail — never colour alone), 10sp source chip, 5dp meter (cyan live fill,
 *  rust stall on failed), failed rows add a dashed rule + the humanized reason
 *  line. Trailing: 38dp ink circle action + ⋮ (mock .iconbtn/.kebab); failed
 *  rows carry the filled RETRY circle (mock .iconbtn.fretry). */
@Composable
private fun ActivityRow(
    task: DownloadTask,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onRetry: () -> Unit,
    // Kept for call-site parity: row-level cancel moved into the ⋮ actions
    // dialog ("Cancel this download?"), matching the mock's two-control cards.
    onCancel: () -> Unit,
    onOpenActions: () -> Unit
) {
    val isPaused = task.status == TaskStatus.PAUSED
    // "Active" = the transferring set (matches downloadsStats' bucketing).
    val isActive = task.status == TaskStatus.DOWNLOADING || task.status == TaskStatus.RESOLVING
    val isFailed = task.status == TaskStatus.FAILED
    val isDone = task.status == TaskStatus.COMPLETED

    // Performance guard: the monogram is remembered per item (title pair), so
    // progress ticks re-render text but never re-derive the initials.
    val monogram = remember(task.showTitle, task.episodeTitle) { monogramFor(task) }
    val style = downloadStatusStyle(task.status, neutralInk = TextMuted)

    val totalKnown = task.totalBytes > 0
    // Failed rows freeze at the last known percentage (rust stall meter); live
    // rows animate toward the current byte ratio (350ms, as before).
    val meterTarget = if (totalKnown) {
        (task.downloadedBytes.toFloat() / task.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val meterFraction by animateFloatAsState(
        targetValue = if (isDone) 1f else meterTarget,
        animationSpec = tween(durationMillis = 350),
        label = "rowProgress"
    )
    // Unknown total + bytes in flight: indeterminate sweep (same honest signal
    // as the v5 pin — a static 0% meter would read as a hang).
    val indeterminate = isActive && !totalKnown && task.downloadedBytes > 0
    val detail = statusDetailText(task)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceCard)
            .border(1.dp, BorderHairline, RoundedCornerShape(18.dp))
            // DONE rows open in the player; FAILED rows retry ("tap to retry"
            // behavior kept from v5) on top of the primary RETRY circle.
            .clickable(
                enabled = isDone || isFailed,
                onClickLabel = if (isFailed) "Retry" else "Play"
            ) {
                when {
                    isFailed -> onRetry()
                    isDone -> onPlay()
                }
            }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // POSTER GAP (frame 1 renders real art where the source gave it):
            // DownloadTask (data/models/Models.kt) carries NO artwork field,
            // and stamping one at enqueue must cross engine.enqueue
            // (DownloadEngine.kt:428) plus the journal-persisted model —
            // outside this change's authorized scope. Every card therefore
            // falls back to the initials tile today; DownloadThumb renders
            // Coil art the moment a poster URL rides along (single switching
            // point, right here).
            DownloadThumb(posterUrl = "", initials = monogram)

            Spacer(modifier = Modifier.width(11.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.episodeTitle,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                // Status line (mock .stat): glyph dot 6dp + coloured word +
                // grey detail — always colour + WORD + GLYPH, single line.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (style.kind == DownloadStateKind.LIVE) {
                        StatusDot(color = style.accent, pulse = true)
                    } else {
                        Text(
                            text = style.glyph,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = style.accent
                        )
                    }
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = style.word,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = style.accent,
                        maxLines = 1
                    )
                    if (detail.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = detail,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
                // Site/source tag (mock .sitetag): only when the record knows
                // its source — tinted for live/failed, neutral grey otherwise.
                if (task.site.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row {
                        SiteTagChip(site = task.site.uppercase(), kind = style.kind)
                    }
                }
                // Honest verification note, if any (rare): plain grey line,
                // completed cards only (kept from the v5 contract).
                if (isDone && task.validationNote != null) {
                    Text(
                        text = task.validationNote,
                        fontSize = 10.sp,
                        color = TextMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Trailing controls (mock .iconbtn / .fretry / .kebab), 10dp apart.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when {
                    isActive -> {
                        // Behavior guard: pause/resume must survive — the active
                        // card's solid circle is Pause (same geometry the mock
                        // draws for the paused card's Resume).
                        SolidActionButton(icon = Icons.Rounded.Pause, description = "Pause", onClick = onPause)
                        KebabButton(onClick = onOpenActions)
                    }
                    isPaused -> {
                        SolidActionButton(icon = Icons.Rounded.PlayArrow, description = "Resume", onClick = onRetry)
                        KebabButton(onClick = onOpenActions)
                    }
                    isDone -> {
                        SolidActionButton(icon = Icons.Rounded.PlayArrow, description = "Play", onClick = onPlay)
                        KebabButton(onClick = onOpenActions)
                    }
                    isFailed -> {
                        // Primary failed action (mock .iconbtn.fretry): filled
                        // rust circle, white RETRY label.
                        RetryCircleButton(onClick = onRetry)
                        KebabButton(onClick = onOpenActions)
                    }
                    // QUEUED / VALIDATING: no transfer affordance is real yet —
                    // the ⋮ dialog ("Cancel this download?") stays reachable.
                    else -> KebabButton(onClick = onOpenActions)
                }
            }
        }

        // 5dp meter (mock .meter): cyan live fill on the #E2E8EF track; the
        // failed card keeps a rust stall meter at the last known percentage.
        if (isActive || isFailed) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MeterTrack)
                    // A hand-drawn meter is invisible to TalkBack: expose it as
                    // a real progress range (kept from the v5 pin).
                    .semantics {
                        progressBarRangeInfo =
                            if (indeterminate) ProgressBarRangeInfo.Indeterminate
                            else ProgressBarRangeInfo(meterFraction, 0f..1f)
                        contentDescription = "Download progress"
                    }
            ) {
                if (indeterminate) {
                    val sweepWidth = 64.dp
                    val sweep by rememberInfiniteTransition(label = "indeterminate").animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(durationMillis = 1100, easing = LinearEasing)),
                        label = "indeterminateValue"
                    )
                    Box(
                        modifier = Modifier
                            .width(sweepWidth)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(999.dp))
                            .offset(x = (maxWidth - sweepWidth) * sweep)
                            .background(LiveFill)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(if (isFailed) meterTarget else meterFraction)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (isFailed) FailRust else LiveFill)
                    )
                }
            }
        }

        // Failed extras (mock .errline): dashed rust rule, then the humanized
        // failure reason on one clipped line, in the failed word colour.
        // SWITCH SITE pill intentionally OMITTED — the pipeline exposes no
        // source-preference / retry-with-different-source knob a card could
        // call (AppSettings has none; DownloadEngine.retry re-resolves the
        // same sourceUrl), and a dead button would be worse than none.
        if (isFailed) {
            Spacer(modifier = Modifier.height(10.dp))
            DashedSeparator()
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = humanizeFailure(task.errorMessage ?: "").ifBlank { "Download failed" },
                fontSize = 11.sp,
                color = FailedWord,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Solid 38dp ink circle (mock .iconbtn): ink fill, white glyph — the card's
 *  primary control (Pause / Resume / Play). */
@Composable
private fun SolidActionButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(TextPrimary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier.size(13.dp)
        )
    }
}

/** Filled rust RETRY circle (mock .iconbtn.fretry): 38dp, FailRust fill,
 *  white 10sp/800 label — the failed card's primary verb (mock pins the
 *  font smaller, this change's 10sp hard floor governs). */
@Composable
private fun RetryCircleButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(FailRust)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "RETRY",
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.3.sp,
            maxLines = 1,
            softWrap = false
        )
    }
}

/** Status dot (mock .dot): 6dp circle; the live dot pulses between full and
 *  25% alpha (mock @keyframes pulse, 1.6s). */
@Composable
private fun StatusDot(color: Color, pulse: Boolean = false) {
    val background = if (pulse) {
        val alpha by rememberInfiniteTransition(label = "liveDot").animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(
                tween(durationMillis = 800, easing = LinearEasing),
                RepeatMode.Reverse
            ),
            label = "liveDotAlpha"
        )
        color.copy(alpha = alpha)
    } else {
        color
    }
    Box(
        modifier = Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(background)
    )
}

/** Site/source chip (mock .sitetag): 10sp/700 with 0.8sp tracking in a soft
 *  rounded chip — tinted for live/failed cards, neutral grey otherwise. */
@Composable
private fun SiteTagChip(site: String, kind: DownloadStateKind) {
    val tinted = when (kind) {
        DownloadStateKind.LIVE -> LiveTint to LiveWord
        DownloadStateKind.FAILED -> FailedTint to FailedWord
        else -> ChipNeutralBg to SiteTagInk
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(tinted.first)
            // Neutral tags keep the mock's hairline border; tinted ones drop it.
            .then(
                if (tinted.first == ChipNeutralBg) {
                    Modifier.border(1.dp, BorderHairline, RoundedCornerShape(5.dp))
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = site,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = tinted.second,
            letterSpacing = 0.8.sp,
            maxLines = 1
        )
    }
}

/** Status legend (mock .legend / .lg): four tinted chips, dot + word,
 *  10sp/800 — the same chroma the cards use. */
@Composable
private fun DownloadsStatusLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LegendChip("Live", LiveWord, LiveTint, pulse = true)
        LegendChip("Done", DoneWord, DoneTint)
        LegendChip("Paused", PausedWord, PausedTint)
        LegendChip("Failed", FailedWord, FailedTint)
    }
}

@Composable
private fun LegendChip(label: String, accent: Color, tint: Color, pulse: Boolean = false) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.full))
            .background(tint)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        StatusDot(color = accent, pulse = pulse)
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            color = accent,
            maxLines = 1,
            softWrap = false
        )
    }
}

/** Card thumbnail (mock .thumb): 46×58, radius 9, hairline border. POSTER ART
 *  through the app's existing Coil pipeline whenever the record carries a
 *  poster URL (HomeScreen's SubcomposeAsyncImage pattern — initials while it
 *  loads, initials on error); initials tile (12sp/800, mock .thumb .init) is
 *  the explicit last-resort fallback. */
@Composable
private fun DownloadThumb(posterUrl: String, initials: String) {
    Box(
        modifier = Modifier
            .size(width = 46.dp, height = 58.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(ThumbTile)
            .border(1.dp, BorderHairline, RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (posterUrl.isNotBlank()) {
            SubcomposeAsyncImage(
                model = posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { ThumbInitials(initials) },
                error = { ThumbInitials(initials) }
            )
        } else {
            ThumbInitials(initials)
        }
    }
}

@Composable
private fun ThumbInitials(initials: String) {
    Text(
        text = initials,
        fontSize = 12.sp,
        fontWeight = FontWeight.ExtraBold,
        color = TextMuted
    )
}

/** 1dp dashed rule (mock .errline border-top: 1px dashed #EFD5D2) — Compose
 *  has no dashed-line modifier primitive, so the mock's dash/gap rhythm
 *  (6dp/4dp) is drawn segment by segment. */
@Composable
private fun DashedSeparator() {
    Canvas(modifier = Modifier.fillMaxWidth().height(1.dp)) {
        val dash = 6.dp.toPx()
        val gap = 4.dp.toPx()
        var x = 0f
        while (x < size.width) {
            drawLine(
                color = DashRust,
                start = Offset(x, 0f),
                end = Offset(minOf(x + dash, size.width), 0f),
                strokeWidth = size.height
            )
            x += dash + gap
        }
    }
}

/** Trailing ⋮ (mock .kebab): text glyph, gray, opens the row actions dialog
 *  (share-or-delete on finished items, retry-or-remove on failed, cancel on
 *  in-flight). */
@Composable
private fun KebabButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "More actions" },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "⋮",
            fontSize = 14.sp,
            fontWeight = FontWeight.Black,
            color = TextSecondary
        )
    }
}

// ─── v6 status vocabulary (mock .stat + :root state chroma) ──────────────────
// State is ALWAYS colour + WORD + GLYPH (never colour alone): live pulses a
// 6dp dot before "Downloading", done / paused / failed lead their words with
// ✓ / ❚❚ / ✕. Mock tokens: live #0E7490 on #E0F2F7 (fill #1591B3), done
// #1F7A4D on #E6F4EC, paused #92610F on #FBF1DE, failed #A84A42 on #F9E9E7.
// Pure + internal so JVM unit tests can pin the mapping without Robolectric
// (see DownloadStatusStyleTest); QUEUED / VALIDATING are not mock states and
// keep neutral chrome but still carry word + glyph.

internal enum class DownloadStateKind { LIVE, DONE, PAUSED, FAILED, NEUTRAL }

internal data class DownloadStatusStyle(
    val kind: DownloadStateKind,
    val word: String,
    val glyph: String,
    val accent: Color,
    val tint: Color
)

internal fun downloadStatusStyle(
    status: TaskStatus,
    neutralInk: Color = Color(0xFF55627A),
    neutralTint: Color = Color(0xFFEEF2F6)
): DownloadStatusStyle = when (status) {
    TaskStatus.DOWNLOADING, TaskStatus.RESOLVING ->
        DownloadStatusStyle(DownloadStateKind.LIVE, "Downloading", "●", LiveWord, LiveTint)
    TaskStatus.COMPLETED ->
        DownloadStatusStyle(DownloadStateKind.DONE, "Done", "✓", DoneWord, DoneTint)
    TaskStatus.PAUSED ->
        DownloadStatusStyle(DownloadStateKind.PAUSED, "Paused", "❚❚", PausedWord, PausedTint)
    TaskStatus.FAILED ->
        DownloadStatusStyle(DownloadStateKind.FAILED, "Failed", "✕", FailedWord, FailedTint)
    TaskStatus.QUEUED ->
        DownloadStatusStyle(DownloadStateKind.NEUTRAL, "Queued", "○", neutralInk, neutralTint)
    TaskStatus.VALIDATING ->
        DownloadStatusStyle(DownloadStateKind.NEUTRAL, "Checking", "●", neutralInk, neutralTint)
}

/** Grey detail after the status word (mock .stat .x), single line: percent,
 *  bytes, speed and ETA only where the record really knows them. Speed/ETA
 *  render while actually transferring only — paused rows never show a stale
 *  rate (v5 rule kept), failed rows freeze at the last known percentage. */
internal fun statusDetailText(task: DownloadTask): String {
    val totalKnown = task.totalBytes > 0
    val pctText = if (totalKnown) {
        ((task.downloadedBytes.toFloat() / task.totalBytes.toFloat()).coerceIn(0f, 1f) * 100)
            .toInt()
            .coerceIn(0, 100)
            .toString() + "%"
    } else null
    val dlText = if (totalKnown) formatBytes(minOf(task.downloadedBytes, task.totalBytes))
    else formatBytes(task.downloadedBytes)

    val transferring = task.status == TaskStatus.DOWNLOADING
    val speedMb = task.speedBytesPerSec / (1024.0 * 1024.0)
    val speedText = when {
        !transferring -> null
        speedMb >= 0.1 -> "%.1f MB/s".format(java.util.Locale.US, speedMb)
        task.speedBytesPerSec >= 1024.0 -> "%.0f KB/s".format(java.util.Locale.US, task.speedBytesPerSec / 1024.0)
        task.speedBytesPerSec > 0.0 -> "%.0f B/s".format(java.util.Locale.US, task.speedBytesPerSec)
        else -> null
    }
    val etaText = if (transferring && task.etaSeconds > 0) formatEta(task.etaSeconds) else null

    return when (task.status) {
        TaskStatus.PAUSED -> when {
            totalKnown -> "· $pctText · $dlText / ${formatBytes(task.totalBytes)}"
            task.downloadedBytes > 0 -> "· $dlText"
            else -> ""
        }
        TaskStatus.DOWNLOADING -> {
            val parts = mutableListOf<String>()
            if (totalKnown) parts.add(pctText!!)
            parts.add(dlText + if (totalKnown) " / ${formatBytes(task.totalBytes)}" else "")
            speedText?.let { parts.add(it) }
            etaText?.let { parts.add(it) }
            if (parts.isEmpty()) "" else "· " + parts.joinToString(" · ")
        }
        TaskStatus.RESOLVING -> if (task.downloadedBytes > 0) "· $dlText" else ""
        TaskStatus.VALIDATING -> ""
        TaskStatus.QUEUED -> "· waiting to start"
        TaskStatus.FAILED -> {
            val stuck = if (totalKnown && task.downloadedBytes > 0) "· stuck at $pctText" else ""
            stuck.ifBlank { "· tap to retry" }
        }
        TaskStatus.COMPLETED -> {
            val sizeText = formatBytes(if (task.totalBytes > 0) task.totalBytes else task.downloadedBytes)
            "· $sizeText"
        }
    }
}

// ─── Failure humanizer (v5 cause line only — user directive 2026-10-15) ─────────
// Failed rows speak in plain words: resolver classes, locker-step internals and
// pipeline jargon never paint as machinery, engine file frames vanish, and a
// full locker URL collapses to its bare host so the row stays one line. Purely
// presentational scrub-and-reword — nothing is invented, and the machine truth
// itself is untouched in the journal, ℹ️ sheet and the engine's own records.
private val resolverClassName = Regex("""\b[A-Z][a-zA-Z]*Resolver\b""")
private val lockerStepName = Regex("""\bDynamicLocker\w*\b""")
private val failureSiteLabels = Regex(
    // Site-label prefixes that name no "*Resolver" class ("Vidsrc: mirror
    // list exhausted") — same plain-words rule as the class names.
    """\b(?:Vidsrc|Vidsrme|LightDL|FivePlay|Vidmoly|Streamwish|Vidhide|Doodstream|Mixdrop|Streamtape|PixelDrain|Pluto)\b""",
    RegexOption.IGNORE_CASE
)
private val resolverChainPhrase = Regex("""resolver chain""", RegexOption.IGNORE_CASE)
private val bareResolverWord = Regex("""\bresolvers?\b""", RegexOption.IGNORE_CASE)
private val otaPipelinePhrase = Regex("""OTA pipeline""")
private val engineClassName = Regex("""\bDownloadEngine\w*\b""")
private val failureCrackWord = Regex("""\bcrack(ed|ing)?\b""", RegexOption.IGNORE_CASE)
private val engineFrameFragment = Regex("""\bengine[/\\]\S*?\.kt:\d+""")
private val urlInFailureMessage = Regex("""https?://([^/\s]+)\S*""")

private fun humanizeFailure(raw: String): String {
    // Locker URLs never read as prose; host only (e.g. "loadedfiles.net —")
    // keeps the cause a single line. Applied first so no later rule ever
    // rewrites a fragment of the URL itself.
    var s = urlInFailureMessage.replace(raw) { it.groupValues[1] }
    // Locker step BEFORE the class-name rule: "DynamicLockerResolver" matches
    // BOTH shapes and must speak as its specific step, not the generic
    // stream finder ("AttemptBudget: DynamicLockerResolver exceeded 12s"
    // used to lose the locker context entirely).
    s = lockerStepName.replace(s, "locker step")
    s = resolverClassName.replace(s, "stream finder")
    s = failureSiteLabels.replace(s, "stream finder")
    s = resolverChainPhrase.replace(s, "stream chain")
    s = bareResolverWord.replace(s, "stream finder")
    s = otaPipelinePhrase.replace(s, "site playbook")
    s = engineClassName.replace(s, "downloader")
    // crack / cracked / cracking → the resolv- word-shape equivalent,
    // preserving the sentence's capitalization.
    s = failureCrackWord.replace(s) { m ->
        val plain = when (m.value.lowercase()) {
            "cracked" -> "resolved"
            "cracking" -> "resolving"
            else -> "resolve"
        }
        if (m.value[0].isUpperCase()) plain.replaceFirstChar { it.uppercaseChar() } else plain
    }
    s = engineFrameFragment.replace(s, "")
    return s.replace(Regex("\\s+"), " ").trim()
        .replace(Regex("""[,;:]+\s*$"""), "").trim()
}

/** Flat monogram initials, remembered per item by the caller. Show episodes
 *  monogram the SHOW (mock "PN" for The Promised Neverland); direct files
 *  monogram their own title. */
private fun monogramFor(task: DownloadTask): String {
    val source = if (task.showTitle.isNotBlank() && task.showTitle != "Direct Downloads") {
        task.showTitle
    } else {
        task.episodeTitle
    }
    val words = source.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        words.size >= 2 -> buildString {
            append(words[0].first())
            append(words[1].first())
        }.uppercase()
        words.isNotEmpty() -> words[0].take(2).uppercase()
        else -> "··"
    }
}

/** Offline-shelf tile (mock .tile): 166×94 deep well, hairline, centered
 *  platform glyph + 34dp glass play (black-50%, white-14% outline, white ▶),
 *  title 11sp/700 + mono "MP4 · 720p · 326.7 KB" beneath. Tap = play;
 *  long-press = share-or-delete dialog. */
@Composable
private fun SocialShelfTile(
    task: DownloadTask,
    onPlay: () -> Unit,
    onOpenActions: () -> Unit
) {
    Column(modifier = Modifier.width(166.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(94.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ShelfTileDeep)
                .border(1.dp, BorderHairline, RoundedCornerShape(10.dp))
                .pointerInput(task.id) {
                    // detectTapGestures (stable API, same idiom as the
                    // scaffold's TabPage): combinedClickable would require an
                    // experimental opt-in this file doesn't carry.
                    detectTapGestures(
                        onTap = { onPlay() },
                        onLongPress = { onOpenActions() }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            // Muted platform glyph — a plain text glyph per spec ("a plain
            // TEXT glyph is fine").
            Text(
                text = "𝕏",
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                color = TextMuted
            )
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(GlassPlayScrim)
                    .border(1.dp, GlassPlayOutline, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "Play",
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
        Text(
            text = task.episodeTitle,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp)
        )
        Text(
            text = shelfMetaText(task),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            color = TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}

/** Mono tile meta (mock "MP4 · 720p · 326.7 KB"): container extension, then
 *  the real resolution/requested quality when known, then the size. */
private fun shelfMetaText(task: DownloadTask): String {
    val extRaw = File(task.filePath).name.substringAfterLast('.', "")
    val extText = if (extRaw.length in 1..5) extRaw.uppercase() else "MP4"
    val quality = (task.resolution ?: task.quality)?.takeIf { it.isNotBlank() }
    val sizeText = formatBytes(if (task.totalBytes > 0) task.totalBytes else task.downloadedBytes)
    return listOfNotNull(extText, quality, sizeText).joinToString(" · ")
}

@Composable
private fun ShowFolderCard(
    showTitle: String,
    items: List<DownloadTask>,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    remainingCount: Int = 0,
    onDownloadRemaining: (() -> Unit)? = null,
    onPlayTask: (DownloadTask) -> Unit,
    onPauseTask: (String) -> Unit,
    onRetryTask: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    onRequestActions: (DownloadTask) -> Unit
) {
    val completedCount = items.count { it.status == TaskStatus.COMPLETED }
    val activeCount = items.count {
        it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.RESOLVING || it.status == TaskStatus.VALIDATING
    }
    val totalBytes = items.sumOf { if (it.totalBytes > 0) it.totalBytes else it.downloadedBytes }

    Card(
        shape = RoundedCornerShape(Radius.lg),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        // Zero-chroma: no active-color border tint — hairline always.
        border = BorderStroke(1.dp, BorderHairline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .padding(Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(Radius.md))
                        .background(SurfaceElevated),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (activeCount > 0) Icons.Rounded.Download else Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(Spacing.md))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = showTitle,
                        fontSize = Type.rowTitle.fontSize,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        Text(
                            text = "${items.size} ep${if (items.size != 1) "s" else ""}",
                            fontSize = Type.caption.fontSize,
                            color = TextSecondary
                        )
                        if (completedCount > 0) {
                            Text(
                                text = "· $completedCount done",
                                fontSize = Type.caption.fontSize,
                                color = TextMuted
                            )
                        }
                        if (activeCount > 0) {
                            Text(
                                text = "· $activeCount active",
                                fontSize = Type.caption.fontSize,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (totalBytes > 0) {
                            Text(
                                text = "· ${formatBytes(totalBytes)}",
                                fontSize = Type.caption.fontSize,
                                color = TextMuted
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(Spacing.sm))

                IconButton(
                    onClick = onToggleExpand,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = TextMuted,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Track 4: one-tap "grab the rest of this series". Rendered above
            // the expanded list so it's reachable in the collapsed card too —
            // refusing the tap just because a folder is folded was the
            // original complaint this feature exists to fix. Restyled inline
            // for v5 (was a bulk chip).
            if (onDownloadRemaining != null && remainingCount > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.xs),
                    horizontalArrangement = Arrangement.Start
                ) {
                    InlineRemainingAction(count = remainingCount, onClick = onDownloadRemaining)
                }
            }

            if (isExpanded) {
                Divider(color = BorderHairline, thickness = 1.dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    items.forEach { task ->
                        ActivityRow(
                            task = task,
                            onPlay = { onPlayTask(task) },
                            onPause = { onPauseTask(task.id) },
                            onRetry = { onRetryTask(task.id) },
                            onCancel = { onCancelTask(task.id) },
                            onOpenActions = { onRequestActions(task) }
                        )
                    }
                }
            }
        }
    }
}

// ─── LEGACY v3 card (NOT rendered by the v5 screen) ────────────────────────────
// DownloadCard is the contract surface for DownloadCardStateTest, which locks
// the per-status copy ("Paused at …", "Queued • Waiting to start", badges…)
// and lives in a read-only test file for this change. The live screen renders
// ActivityRow above. Kept byte-identical on purpose — do not restyle it
// casually; its colored keywords deliberately superseded nowhere visible.

@Composable
private fun BulkActionChip(
    label: String,
    icon: ImageVector,
    iconTint: Color = TextSecondary,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.full))
            .background(SurfaceElevated)
            .border(1.dp, BorderHairline, RoundedCornerShape(Radius.full))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                color = TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun DownloadCard(
    task: DownloadTask,
    context: Context,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit = onCancel,
    remainingCount: Int = 0,
    onDownloadRemaining: (() -> Unit)? = null
) {
    val isCompleted = task.status == TaskStatus.COMPLETED
    val isDownloading = task.status == TaskStatus.DOWNLOADING || task.status == TaskStatus.RESOLVING
    val isPaused = task.status == TaskStatus.PAUSED
    val isFailed = task.status == TaskStatus.FAILED

    // FAILED cards get a subtle error tint on the surface + border, so they read
    // as failed at a glance (not just via the badge).
    val cardSurface = if (isFailed) StatusError.copy(alpha = 0.06f) else SurfaceCard
    val cardBorder = if (isFailed) StatusError.copy(alpha = 0.45f) else BorderHairline

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .background(cardSurface)
            .border(1.dp, cardBorder, RoundedCornerShape(Radius.lg))
            .clickable(enabled = isCompleted, onClick = onPlay)
            .padding(Spacing.lg)
    ) {
        Column {
            // Title & Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.episodeTitle,
                        color = TextPrimary,
                        fontSize = Type.rowTitle.fontSize,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(Spacing.xxs))
                    Text(
                        text = if (task.showTitle != "Direct Downloads") task.showTitle else "Direct Media",
                        color = TextSecondary,
                        fontSize = Type.caption.fontSize,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                StatusBadge(status = task.status)
            }

            Spacer(modifier = Modifier.height(Spacing.sm))

            val totalKnown = task.totalBytes > 0
            val isDownloadingNow = task.status == TaskStatus.DOWNLOADING

            val maxInFlightProgress = if (isCompleted) 1f else 0.99f
            val targetProgress = if (totalKnown) {
                (task.downloadedBytes.toFloat() / task.totalBytes.toFloat()).coerceIn(0f, maxInFlightProgress)
            } else 0f
            val animatedProgress by animateFloatAsState(
                targetValue = targetProgress,
                animationSpec = tween(durationMillis = 300),
                label = "progress"
            )
            val pctInt = if (isCompleted) 100 else minOf(99, (animatedProgress * 100).toInt())

            if (isCompleted) {
                // ---- COMPLETED: ext chip + size + Play / Share / Delete ----
                val sizeText = if (task.totalBytes > 0) formatBytes(task.totalBytes) else formatBytes(task.downloadedBytes)
                // substringAfterLast('.') with NO dot returns the WHOLE string —
                // an extension-less filename rendered its entire name into the
                // 9.sp chip and crushed the size label beside it. Only a real
                // (short) extension gets a chip.
                val extRaw = File(task.filePath).name.substringAfterLast('.', "")
                val extText = if (extRaw.length in 1..5) extRaw.uppercase() else null
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        if (extText != null) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Radius.xs))
                                    .background(SurfaceElevated)
                                    .padding(horizontal = Spacing.sm, vertical = Spacing.xxs)
                            ) {
                                Text(text = extText, color = TextPrimary, fontSize = Type.caption.fontSize, fontWeight = FontWeight.Bold)
                            }
                        }
                        // Quality/resolution chip: the real stream resolution
                        // for HLS (parsed from the master's RESOLUTION during
                        // preflight), the requested quality otherwise.
                        val qualityLabel = task.resolution ?: task.quality
                        if (qualityLabel != null) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Radius.xs))
                                    .background(SurfaceElevated)
                                    .padding(horizontal = Spacing.sm, vertical = Spacing.xxs)
                            ) {
                                Text(text = qualityLabel, color = TextSecondary, fontSize = Type.caption.fontSize, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Text(
                            text = sizeText,
                            color = TextMuted,
                            fontSize = Type.caption.fontSize,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        IconButton(
                            onClick = onPlay,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(AccentPrimary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play", tint = BackgroundDark, modifier = Modifier.size(18.dp))
                            }
                        }

                        IconButton(
                            onClick = { shareMedia(context, task.filePath) },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(SurfaceElevated)
                                    .border(1.dp, BorderHairline, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.Share, contentDescription = "Share", tint = TextSecondary, modifier = Modifier.size(18.dp))
                            }
                        }

                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(SurfaceElevated)
                                    .border(1.dp, BorderHairline, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete", tint = TextMuted, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                // Honest verification note (if any): the engine could not fully
                // prove the file (unknown container verified by decoder, size
                // vs stream estimate, or nothing at all) — amber tells the user
                // to check it plays before keeping.
                if (task.validationNote != null) {
                    Text(
                        text = task.validationNote,
                        color = StatusWarning,
                        fontSize = Type.micro.fontSize,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = Spacing.xs, top = 2.dp)
                    )
                }
            } else {
                // ---- ACTIVE / QUEUED / PAUSED / FAILED ----
                // Seal-Style Visual Segment Progress Bar (frozen at 0 for QUEUED;
                // indeterminate sweep while downloading with an unknown total)
                // Unknown-total + any in-flight state: indeterminate sweep so
                // the user has a visual signal during the engine's RESOLVING
                // phase and during yt-dlp / CDN downloads that don't emit a
                // percentage.  PAUSED / COMPLETED / FAILED fall through to the
                // static-fill branch (sweep would lie about progress).
                val indeterminate = !totalKnown && task.downloadedBytes > 0 &&
                    !isPaused && !isCompleted && !isFailed
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(Radius.xs))
                        .background(SurfaceElevated)
                        // A hand-drawn bar is invisible to TalkBack: expose it
                        // as a real progress range so the card announces
                        // progress, not just the title row.
                        .semantics {
                            progressBarRangeInfo =
                                if (indeterminate) ProgressBarRangeInfo.Indeterminate
                                else ProgressBarRangeInfo(animatedProgress, 0f..1f)
                            contentDescription = "Download progress"
                        }
                ) {
                    if (!indeterminate) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction = animatedProgress)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(Radius.xs))
                                .background(
                                    if (isFailed) Brush.horizontalGradient(listOf(StatusError, StatusError))
                                    else Brush.horizontalGradient(listOf(AccentViolet, AccentPink, AccentPrimary))
                                )
                        )
                    } else {
                        // Indeterminate sweep (only reachable while DOWNLOADING
                        // with an unknown total — CDN without Content-Length,
                        // HLS before the variant is measured): a dead 0% bar
                        // next to "100.7 MB" told the user nothing, so a sliding
                        // segment signals "in flight" without pretending to know
                        // a percentage.
                        val sweepWidth = 64.dp
                        val sweep by rememberInfiniteTransition(label = "indeterminate").animateFloat(
                            initialValue = 0f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(tween(durationMillis = 1100, easing = LinearEasing)),
                            label = "indeterminateValue"
                        )
                        Box(
                            modifier = Modifier
                                .width(sweepWidth)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(Radius.xs))
                                .offset(x = (maxWidth - sweepWidth) * sweep)
                                .background(Brush.horizontalGradient(listOf(AccentViolet, AccentPink, AccentPrimary)))
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.sm))

                // Show byte progress as soon as ANY bytes have landed.  The
                // old 100-byte threshold left a 100-byte dead zone at the
                // start of every download (the card would show "Starting..."
                // until the engine's updateProgress crossed 100 bytes,
                // which on slow CDN links took several seconds and read
                // as a hang).  For unknown-total downloads we only show
                // the downloaded count, not "0 B / 0 B".
                val sizeStr = if (task.totalBytes > 0) {
                    // Clamp the downloaded side to the total: aria2c's no-total
                    // wire bytes can overshoot the inferred total (re-downloaded
                    // pieces + chunk overhead), and "100.7 MB / 84.1 MB" read
                    // as a bug. The progress bar already clamps to 100%.
                    formatBytes(minOf(task.downloadedBytes, task.totalBytes)) + " / " + formatBytes(task.totalBytes)
                } else if (task.downloadedBytes > 0) {
                    formatBytes(task.downloadedBytes)
                } else {
                    ""
                }

                // Speed is only rendered while the task is actually transferring;
                // paused/queued cards never show a stale "0.0 MB/s".
                val speedMb = task.speedBytesPerSec / (1024.0 * 1024.0)
                val speedStr = when {
                    speedMb >= 0.1 -> "%.1f MB/s".format(java.util.Locale.US, speedMb)
                    task.speedBytesPerSec >= 1024.0 -> "%.0f KB/s".format(java.util.Locale.US, task.speedBytesPerSec / 1024.0)
                    task.speedBytesPerSec > 0.0 -> "%.0f B/s".format(java.util.Locale.US, task.speedBytesPerSec)
                    else -> null
                }
                val etaStr = if (task.etaSeconds > 0) formatEta(task.etaSeconds) else null
                // "Estimating..." applies to any in-flight state where we
                // have downloaded bytes but no total + no ETA.  Previously
                // this was gated on (status==DOWNLOADING && speed<1024),
                // which suppressed the label during the engine's 18s
                // of fallback attempts (RESOLVING) and during the first
                // 2s of fast downloads where the speed sample hadn't
                // landed yet.  Showing "Estimating..." the moment any
                // bytes have arrived gives the user a signal that the
                // download is in flight even when the backend (yt-dlp
                // on a segmented CDN) can't emit a percentage.
                val estimating = task.downloadedBytes > 0 &&
                    task.status != TaskStatus.PAUSED &&
                    task.status != TaskStatus.COMPLETED &&
                    (task.totalBytes > task.downloadedBytes || task.totalBytes <= 0L)

                val progressText = when (task.status) {
                    TaskStatus.QUEUED -> "Queued • Waiting to start"
                    TaskStatus.RESOLVING -> {
                        // Bytes already landing during the engine's fallback
                        // chain (aria2c -> turbo -> yt-dlp) while the status
                        // is still RESOLVING: surface the real byte count, speed,
                        // and ETA instead of a stale "Resolving stream link..." that
                        // reads as a hang (dramakey/HLS symptom).
                        if (estimating && sizeStr.isNotBlank()) {
                            val parts = mutableListOf<String>()
                            parts += sizeStr
                            speedStr?.let { parts += it }
                            etaStr?.let { parts += it }
                            if (etaStr == null) parts += "Estimating..."
                            parts.joinToString(" • ")
                        } else "Resolving stream link..."
                    }
                    TaskStatus.VALIDATING -> "Checking downloaded file..."
                    TaskStatus.PAUSED -> {
                        if (task.totalBytes > 0) "Paused at $pctInt% • $sizeStr"
                        else if (sizeStr.isNotBlank()) "Paused at $sizeStr"
                        else "Paused"
                    }
                    TaskStatus.DOWNLOADING -> {
                        val isMagnet = task.sourceUrl.startsWith("magnet:", ignoreCase = true)
                        if (isMagnet && task.downloadedBytes == 0L) {
                            "Connecting to swarm (discovering peers)..."
                        } else {
                            // With an unknown total (e.g. HLS) show size + speed, no percentage.
                            val parts = mutableListOf<String>()
                            // Real stream resolution first when known (HLS masters
                            // carry RESOLUTION; the requested quality falls back
                            // only when resolution was never parsed).
                            (task.resolution ?: task.quality)?.let { parts += it }
                            if (task.totalBytes > 0) parts += "$pctInt%"
                            if (sizeStr.isNotBlank()) parts += sizeStr
                            speedStr?.let { parts += it }
                            etaStr?.let { parts += it }
                            if (estimating && etaStr == null) parts += "Estimating..."
                            parts.joinToString(" • ").ifBlank { "Starting..." }
                        }
                    }
                    TaskStatus.FAILED -> task.errorMessage ?: "Download failed"
                    else -> "Queued • Waiting to start"
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = progressText,
                        color = when {
                            isFailed -> StatusError
                            task.status == TaskStatus.RESOLVING || task.status == TaskStatus.VALIDATING -> AccentPrimary
                            else -> TextMuted
                        },
                        fontSize = Type.caption.fontSize,
                        maxLines = 2,
                        // Churning states (percent/size/speed/ETA all mutate
                        // every tick) reserve BOTH lines: otherwise the
                        // "10:00 left" -> "9:59 left" width wobble flips the
                        // text between 1 and 2 lines and the whole card
                        // visibly jumps mid-download.
                        minLines = if (isDownloadingNow || task.status == TaskStatus.RESOLVING) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        if (isDownloading) {
                            IconButton(
                                onClick = onPause,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Pause,
                                    contentDescription = "Pause",
                                    tint = TextPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        } else if (isPaused || isFailed) {
                            IconButton(
                                onClick = onRetry,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = if (isFailed) Icons.Rounded.Refresh else Icons.Rounded.PlayArrow,
                                    contentDescription = if (isFailed) "Retry" else "Resume",
                                    tint = if (isFailed) StatusError else AccentPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        IconButton(
                            onClick = onCancel,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Cancel",
                                tint = if (isFailed) StatusError else TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // Track 4: series chip in the flat (non-"By Show") sort modes —
            // the folder card carries it in library mode, these cards carry it
            // everywhere else so the shortcut never depends on the sort.
            if (onDownloadRemaining != null && remainingCount > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.xxs),
                    horizontalArrangement = Arrangement.End
                ) {
                    BulkActionChip(
                        label = "Download Remaining ($remainingCount left)",
                        icon = Icons.Rounded.Download,
                        iconTint = AccentPrimary,
                        onClick = onDownloadRemaining
                    )
                }
            }
        }
    }
}

@Composable
fun StatusBadge(status: TaskStatus) {
    val (text, bg, fg) = when (status) {
        TaskStatus.QUEUED -> Triple("QUEUED", SurfaceElevated, TextMuted)
        TaskStatus.RESOLVING -> Triple("RESOLVING", SurfaceElevated, AccentPrimary)
        TaskStatus.DOWNLOADING -> Triple("DOWNLOADING", StatusSuccess.copy(alpha = 0.15f), StatusSuccess)
        TaskStatus.VALIDATING -> Triple("CHECKING", StatusWarning.copy(alpha = 0.15f), StatusWarning)
        TaskStatus.PAUSED -> Triple("PAUSED", SurfaceElevated, TextSecondary)
        TaskStatus.COMPLETED -> Triple("DONE", StatusSuccess.copy(alpha = 0.15f), StatusSuccess)
        TaskStatus.FAILED -> Triple("FAILED", StatusError.copy(alpha = 0.15f), StatusError)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.full))
            .background(bg)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xxs)
    ) {
        Text(text = text, color = fg, fontSize = Type.micro.fontSize, fontWeight = FontWeight.Bold)
    }
}

fun shareMedia(context: Context, filePath: String) {
    try {
        val file = File(filePath)
        if (!file.exists()) return
        val ext = file.extension.lowercase()
        val mime = when (ext) {
            "mp3", "m4a", "aac", "wav", "flac", "opus", "ogg" -> "audio/*"
            "mp4", "mkv", "avi", "mov", "webm", "ts" -> "video/*"
            "zip", "rar", "7z", "tar", "gz" -> "application/zip"
            else -> "*/*"
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(Intent.createChooser(intent, "Share Media"))
    } catch (_: Exception) {}
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> "%.2f GB".format(java.util.Locale.US, gb)
        mb >= 1.0 -> "%.1f MB".format(java.util.Locale.US, mb)
        kb >= 1.0 -> "%.1f KB".format(java.util.Locale.US, kb)
        else -> "$bytes B"
    }
}

fun formatEta(seconds: Long): String {
    if (seconds <= 0) return ""
    val m = seconds / 60
    val s = seconds % 60
    val h = m / 60
    return when {
        h > 0 -> "%02d:%02d:%02d left".format(java.util.Locale.US, h, m % 60, s)
        m > 0 -> "%02d:%02d left".format(java.util.Locale.US, m, s)
        else -> "${s}s left"
    }
}
