package com.anonrode.downloader.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import coil.compose.SubcomposeAsyncImage
import androidx.lifecycle.viewModelScope
import com.anonrode.downloader.data.ContinueState
import com.anonrode.downloader.data.SeriesContinueCard
import com.anonrode.downloader.data.WatchHistoryCore
import com.anonrode.downloader.data.WatchHistoryStore
import com.anonrode.downloader.data.WatchRecord
import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.TaskStatus
import com.anonrode.downloader.providers.PosterStore
import com.anonrode.downloader.util.SeasonEpisode
import com.anonrode.downloader.util.ShowIdentity
import com.anonrode.downloader.ui.components.PosterImageOrFallback
import com.anonrode.downloader.ui.components.PosterRequests
import com.anonrode.downloader.ui.components.SkeletonBox
import com.anonrode.downloader.ui.components.rememberInfiniteSweep
import com.anonrode.downloader.ui.components.rememberReduceMotion
import com.anonrode.downloader.ui.components.rememberShimmerX
import com.anonrode.downloader.ui.theme.AccentViolet
import com.anonrode.downloader.ui.theme.AnonTheme
import com.anonrode.downloader.ui.theme.BorderHairline
import com.anonrode.downloader.ui.theme.Motion
import com.anonrode.downloader.ui.theme.Radius
import com.anonrode.downloader.ui.theme.SurfaceElevated
import com.anonrode.downloader.ui.theme.TextPrimary
import com.anonrode.downloader.ui.theme.TextSecondary
import com.anonrode.downloader.ui.util.TitleFormatting
import com.anonrode.downloader.viewmodel.MainViewModel
import com.anonrode.downloader.ui.util.confirmHaptic
import com.anonrode.downloader.ui.util.tick
import com.anonrode.downloader.ui.util.pressScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** "S01E04" when the coordinates are known; "Ep 4" otherwise (drawer). */
private fun drawerEpLabel(season: Int, episode: Int): String =
    if (season > 0 || episode > 0) "S%02dE%02d".format(season, episode) else "Ep $episode"

/**
 * Shared "remaining" semantics for the episode drawer and the Downloads
 * series card.
 *
 * PAUSED is NOT in flight. It is remaining work that the user can resume,
 * and re-enqueueing that source URL resumes the existing task (see
 * DownloadEngine.enqueue's resumableExisting branch) rather than creating a
 * duplicate. FAILED is also remaining. Only COMPLETED and the four active /
 * queued statuses count as done-or-in-flight.
 */
internal fun isInFlightStatus(status: TaskStatus): Boolean = when (status) {
    TaskStatus.QUEUED,
    TaskStatus.RESOLVING,
    TaskStatus.DOWNLOADING,
    TaskStatus.VALIDATING -> true
    else -> false
}

internal fun remainingEpisodesOf(
    episodes: List<EpisodeItem>,
    completedUrls: Set<String>,
    inFlightUrls: Set<String>,
    /** Watched episodes are excluded even when their file was deleted
     *  (directive §4: Remaining must never re-download something you watched). */
    watchedUrls: Set<String> = emptySet()
): List<EpisodeItem> = episodes.filter {
    it.url !in completedUrls && it.url !in inFlightUrls && it.url !in watchedUrls
}

/** Downloads-card hint: seriesTotal minus completed/in-flight tasks.
 *  PAUSED and FAILED therefore keep the "Download remaining N" action
 *  visible instead of silently decrementing it to zero. */
internal fun seriesRemainingCount(seriesTotal: Int, statuses: List<TaskStatus>): Int {
    val doneOrInFlight = statuses.count {
        it == TaskStatus.COMPLETED || isInFlightStatus(it)
    }
    return (seriesTotal - doneOrInFlight).coerceAtLeast(0)
}

/*
 * ───────────────────────────────────────── THE MATH CONTRACT (proposal v6 drawer) ────────────────────────────────
 * "Plex-style" drawer, one translated surface, two detents. W = container width (393dp reference),
 * H = full screen height. Grid: gutter G = 16 · row gap 8 · row H 64 · radius 14/12/999.
 *
 *   • SHARED COMPACT HEADER (both detents, top-anchored):
 *       art band 104dp tall — blurred show poster (blur 24) + black 38% overlay + bottom 48dp fade
 *       into the sheet color, painted OVER a neutral charcoal gradient (#3A4254 → #1B2029) so the
 *       band never reads empty when the image fails;
 *       poster 76×112 radius 12, shadow 0/18/42 black-62%, border white-10%, at left 20;
 *       title x = 20+76+16 = 112, 20sp/ExtraBold white, 2-line ellipsis; meta 11sp text-sub;
 *       close ✕ 38dp glass circle (black-72% bg, white-9% border, white-90% glyph) at right 16.
 *   • HALF (0.60 × H): grip zone 30dp at the very top (36×4 bar white-32% + 10sp/700 uppercase
 *       hint, letter-spacing 1.8); art band sheet-y 30→134; poster @ (20, 38); selection pill in
 *       the title block; chips row y=166; episode rows y=214; range input and footer hidden.
 *   • FULL (100%): same header pinned under the status bar — band from the status-bar inset
 *       (34dp reference) → +104; poster @ (20, 48); ✕ @ 48; then ONE continuous scroll:
 *       chips y=176 → range 42dp @ 224 → season slabs → rows (~300); sticky footer 76dp
 *       (sunk 97%, top hairline, padding 12/16 + navigationBars inset) with the selection
 *       summary and the white Download pill.
 *   • ROWS: 64dp card, hairline border, radius 14; number tile 34dp r9 (sunk, 11.5sp/800);
 *       title 12sp/700 single line; quality meta 10sp muted mono — only the parts the data
 *       actually carries (quality/codec/size), nothing invented; 24dp info circle; 19dp checkbox
 *       r6 (unselected white-35% border, selected white fill + dark check).
 *   • LOADING: skeleton rows in the EXACT row geometry, stage line (two rotating stages) and a
 *       thin 5dp accent progress track. FAILURE: card + rust headline/reason + RETRY pill wired
 *       to the same scrape call the drawer opened with.
 *   • PALETTE: pinned dark hexes declared at the bottom of this file — the drawer NEVER resolves
 *       colors through the app theme (the shipped light-theme smear bug).
 * All positions are COMPUTED EXPRESSIONS of these formulas + the real insets, never eyeballed.
 * ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────
 *
 * ARCHITECTURE NOTE (unchanged chassis): a plain Dialog with usePlatformDefaultWidth=false +
 * decorFitsSystemWindows=false hosts ONE full-height sheet Box TRANSLATED between detents via an
 * offset lambda (drag frames never recompose). Gestures: Modifier.draggable on the header surfaces
 * plus a NestedScrollConnection on the episode list (scroll-down at the list top collapses →
 * dismiss; scroll-up expands before handing the scroll to the list). The detent phase (isFull) is
 * a derivedStateOf boolean — it flips only on detent commits, so the HALF/FULL layout swap costs
 * two recompositions per gesture, never per drag frame.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeDrawer(
    show: ShowCard,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    /** Track 4 ("Download Remaining"): launched from a Downloads-series card
     *  with the explicit intent to grab the rest of the show. The first load
     *  auto-selects exactly the un-downloaded episodes — the user confirms via
     *  the sticky footer bar, and any manual deselection wins. */
    preselectRemaining: Boolean = false
) {
    val uiState by viewModel.uiState.collectAsState()

    // ── §1/§2 theme handoff: the sheet resolves ONE pinned palette for its
    //    whole lifetime (the drawer's theme-proof doctrine — hexes stay
    //    pinned at the bottom of this file, never re-resolved mid-layout),
    //    but now holds BOTH an obsidian-dark and a clean-light instance so
    //    the sheet follows the app theme instead of smearing navy in dark
    //    and ignoring light entirely.
    val pal = sheetPalette(AnonTheme.colors.isDark)

    // ── §4.4 episode-sheet TTL cache (UI edge — MainViewModel untouched) ────
    // Re-opening a show used to re-scrape for 7-8s before a single row
    // painted. The last successful scrape per `source + showUrl` parks in an
    // in-memory map with a 30-minute TTL; the next open paints it
    // INSTANTLY and silently swaps in the fresh list when the scrape lands.
    // The ViewModel is not edited and no new state is added there: this is a
    // read-only presentation cache over data the drawer already received.
    val freshEpisodes = uiState.drawerEpisodes
    LaunchedEffect(freshEpisodes, show.url) {
        if (freshEpisodes.isNotEmpty()) {
            cachedEpisodeLists[show.site + "|" + show.url] = CachedEpisodeList(
                episodes = freshEpisodes,
                synopsis = uiState.drawerSynopsis,
                savedAtMs = System.currentTimeMillis()
            )
        }
    }
    val cachedHit = remember(show.site, show.url) {
        cachedEpisodeLists[show.site + "|" + show.url]
            ?.takeIf { System.currentTimeMillis() - it.savedAtMs <= EPISODE_CACHE_TTL_MS }
            ?.episodes
            .orEmpty()
    }
    val episodes = if (freshEpisodes.isNotEmpty()) freshEpisodes else cachedHit
    val isLoading = uiState.isEpisodesLoading && episodes.isEmpty()
    val showSynopsis = if (uiState.drawerSynopsis.isNotBlank()) uiState.drawerSynopsis
        else remember(show.site, show.url) {
            cachedEpisodeLists[show.site + "|" + show.url]
                ?.takeIf { System.currentTimeMillis() - it.savedAtMs <= EPISODE_CACHE_TTL_MS }
                ?.synopsis
                .orEmpty()
        }
    val context = LocalContext.current
    // Haptic engine handle: queueing a download is the app's main commitment
    // moment, and multi-select needs per-item acknowledgement. Default flags
    // only — the system haptics toggle wins.
    val hapticView = LocalView.current

    // Keying by the show (rather than EpisodeItem equality or the full episode
    // list) keeps checks through harmless episode refreshes, while a different
    // show starts with a clean selection.
    var selectedUrlsList by rememberSaveable(key = show.url) { mutableStateOf(listOf<String>()) }
    val selectedUrls = selectedUrlsList.toSet()
    fun updateSelected(newSet: Set<String>) { selectedUrlsList = newSet.toList() }
    val selectedEpisodes = episodes.filter { it.url in selectedUrls }
    var rangeText by rememberSaveable { mutableStateOf("") }
    var enqueued by remember { mutableStateOf(false) }
    var isEnqueuing by remember { mutableStateOf(false) }
    var showBatchConfirmation by rememberSaveable { mutableStateOf(false) }
    // §2.7: the batch confirm must remember it holds a "Download All" (zero
    // picked) vs a plain selection batch — the confirm button enqueues
    // accordingly.
    var pendingForceAll by rememberSaveable { mutableStateOf(false) }

    val allTasks by viewModel.engine.tasks.collectAsState()
    // Task join shared by both URL sets below.
    // AUDIT FIX (2026-10-19, cross-doc audit BUG 4 / wiring F-10): every
    // enqueue path stamps task.showTitle with
    // NameSanitizer.cleanShowFolder(show.title) — which strips "[site]",
    // "(year)" and "| Download …" and replaces ":" — so comparing against the
    // RAW title left "✓ Downloaded" and "Remaining (N)" broken for any title
    // the sanitizer changes (e.g. "Teetotaler (2023) - Nollywood Comedy").
    // Match the stamped series URL first (the strongest key), then BOTH title
    // normalizations, then the legacy contains-fallback.
    val showTasks = remember(allTasks, show.title, show.url) {
        val rawLower = show.title.trim().lowercase()
        val folderLower = com.anonrode.downloader.util.NameSanitizer
            .cleanShowFolder(show.title).trim().lowercase()
        allTasks.filter { task ->
            val t = task.showTitle.trim().lowercase()
            (task.seriesUrl.isNotBlank() && task.seriesUrl.equals(show.url, ignoreCase = true)) ||
                t == rawLower ||
                (folderLower.isNotBlank() && t == folderLower) ||
                (rawLower.isNotBlank() && task.showTitle.contains(show.title, ignoreCase = true))
        }
    }
    // COMPLETED only: the green "✓ Downloaded" pill (and the 0.78 dim) must
    // never claim a FAILED/QUEUED/PAUSED episode. PAUSED is intentionally NOT
    // in flight: it is remaining work, and re-enqueueing it resumes the same
    // task (DownloadEngine.enqueue's resumableExisting branch).
    val completedUrls = remember(showTasks) {
        showTasks.filter { it.status == TaskStatus.COMPLETED }
            .map { it.sourceUrl }.filter { it.isNotBlank() }.toSet()
    }
    // In-flight set (running or waiting) — excluded from Remaining, but
    // deliberately NOT shown as downloaded.
    val activeOrQueuedUrls = remember(showTasks) {
        showTasks.filter { isInFlightStatus(it.status) }
            .map { it.sourceUrl }.filter { it.isNotBlank() }.toSet()
    }
    // Watch history (directive §4): watched episodes are excluded from
    // Remaining even after their files were deleted; matching is URL-first
    // then season/episode, the same rule the core uses everywhere.
    val watchRecords by WatchHistoryStore.recordsFlow.collectAsState()
    val watchByUrl = remember(watchRecords) {
        watchRecords.filter { it.sourceUrl.isNotBlank() }.associateBy { it.sourceUrl }
    }
    fun epSeason(ep: EpisodeItem): Int = SeasonEpisode.seasonOf(ep)
    fun epEpisode(ep: EpisodeItem): Int = SeasonEpisode.episodeOf(ep)
    fun watchFor(ep: EpisodeItem): WatchRecord? =
        watchByUrl[ep.url] ?: watchRecords.firstOrNull { r ->
            r.season > 0 && r.season == epSeason(ep) && r.episode == epEpisode(ep) &&
                ShowIdentity.normalizeShowKey(r.showTitle) == ShowIdentity.normalizeShowKey(show.title)
        }
    val watchedUrls = remember(watchRecords, episodes, show.title) {
        episodes.filter { watchFor(it)?.isWatched == true }.map { it.url }.toSet()
    }
    // Remaining = not completed AND not in flight AND not watched: PAUSED and
    // FAILED episodes stay here so they can be resumed/retried in one tap.
    val remainingEpisodes = remember(episodes, completedUrls, activeOrQueuedUrls, watchedUrls) {
        remainingEpisodesOf(episodes, completedUrls, activeOrQueuedUrls, watchedUrls)
    }

    // Resume/Up-Next banner (directive §4): only when the target file is on
    // disk — FETCH_NEXT has no file and belongs to the shelf, not the banner.
    val drawerContinue: SeriesContinueCard? = remember(watchRecords, allTasks, show.title) {
        WatchHistoryCore.resolveContinueCards(watchRecords, allTasks)
            .firstOrNull {
                it.showKey == ShowIdentity.normalizeShowKey(show.title) &&
                    it.state != ContinueState.FETCH_NEXT &&
                    it.task != null
            }
    }

    // One-shot auto-selection: fires once the drawer's live scrape lands. The
    // applied flag keeps a later episodes emission (provider refresh, cache
    // update) from re-selecting episodes the user manually deselected.
    var autoApplied by remember(show.url) { mutableStateOf(false) }
    LaunchedEffect(preselectRemaining, episodes, completedUrls, activeOrQueuedUrls) {
        if (preselectRemaining && !autoApplied && !enqueued && remainingEpisodes.isNotEmpty()) {
            autoApplied = true
            selectedUrlsList = remainingEpisodes.map { it.url }
        }
    }

    // Parse range string (e.g. "1-5, 8, 10-12", "all", "none")
    fun applyRange(input: String) {
        val clean = input.trim()
        if (clean.isBlank() || clean.equals("none", ignoreCase = true) || clean.equals("clear", ignoreCase = true) || clean.equals("deselect", ignoreCase = true)) {
            updateSelected(emptySet())
            return
        }
        if (clean.equals("all", ignoreCase = true) || clean.equals("*", ignoreCase = true)) {
            updateSelected(episodes.map { it.url }.toSet())
            return
        }
        val targetNums = mutableSetOf<Int>()
        val parts = clean.split(',', ';', ' ')
        for (part in parts) {
            val p = part.trim()
            if (p.isBlank()) continue
            if (p.contains('-')) {
                val start = p.substringBefore('-').trim().toIntOrNull()
                val end = p.substringAfter('-').trim().toIntOrNull()
                if (start != null && end != null) {
                    val rMin = minOf(start, end)
                    val rMax = maxOf(start, end)
                    targetNums.addAll(rMin..rMax)
                }
            } else {
                p.toIntOrNull()?.let { targetNums.add(it) }
            }
        }
        // While TYPING a range the intermediate states are meaningless, not
        // a command: "10-" (end not typed yet) or pure garbage must not wipe
        // the existing selection — that made the checkboxes flicker with
        // every keystroke. Only a parsed non-empty set re-marks the list
        // (clearing stays available via "none"/backspace-to-empty/above).
        if (targetNums.isEmpty()) return
        updateSelected(
            episodes.filter { ep ->
                SeasonEpisode.episodeOf(ep) in targetNums ||
                    ep.episodeNum in targetNums ||
                    SeasonEpisode.sortKey(SeasonEpisode.seasonOf(ep), SeasonEpisode.episodeOf(ep)) in targetNums
            }.map { it.url }.toSet()
        )
    }

    // Dynamic Season Grouping for 1-Tap Filter Chips.
    // Season identity is explicit (EpisodeItem.season) when the parser knows
    // it. Fallbacks: an SxxEyy title token, a "season-N" page/URL token, the
    // NEPU-style season path, then the legacy episodeNum convention. The old
    // unconditional `episodeNum / 100` is what moved 9jaRocks S01E101+ (and
    // flat per-season 1..N pages) into a fictional Season 2.
    val seasonGroups = remember(episodes) {
        val groups = mutableMapOf<Int, MutableList<EpisodeItem>>()
        for (ep in episodes) {
            val titleToken = Regex("S([0-9]{1,2})(?:E([0-9]{1,3}))?", RegexOption.IGNORE_CASE)
                .find(ep.title)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val sNum = ep.season.takeIf { it > 0 }
                ?: titleToken
                ?: Regex("season[\\s._-]*(\\d{1,2})", RegexOption.IGNORE_CASE)
                    .find("${ep.title} ${ep.url}")?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("/watch/(?:tv|movie)/\\d+/(\\d+)(?:/|$)", RegexOption.IGNORE_CASE)
                    .find(ep.url)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: SeasonEpisode.seasonOf(ep)
            groups.getOrPut(sNum) { mutableListOf() }.add(ep)
        }
        groups.toSortedMap().mapValues { (_, values) ->
            values.sortedWith(compareBy({ SeasonEpisode.episodeOf(it) }, { it.title }))
        }
    }
    var expandedSeasons by remember(show.url, seasonGroups.keys) {
        mutableStateOf(seasonGroups.keys)
    }
    val uniqueSelectedCount = selectedEpisodes.distinctBy { it.url }.size

    fun enqueueSelected(forceAll: Boolean = false) {
        if (enqueued || isEnqueuing) return
        // §2.7 zero-selection CTA: the Download button is rendered as
        // "Download All (N)" when nothing is picked, and it MEANS it —
        // forceAll enqueues every listed episode in the same batch order.
        // Combined provider pages may intentionally reuse one anchor URL for
        // multiple episodes. Keep every selected row visible, but never enqueue
        // that source more than once in the same batch.
        val picked = if (forceAll) episodes else selectedEpisodes
        val toEnqueue = picked
            .distinctBy { it.url }
            .sortedWith(compareBy(
                { episode -> seasonGroups.entries.firstOrNull { it.value.any { it === episode } }?.key ?: Int.MAX_VALUE },
                { SeasonEpisode.episodeOf(it) },
                { it.title }
            ))
        if (toEnqueue.isEmpty()) {
            Toast.makeText(context, "Select at least one episode first", Toast.LENGTH_SHORT).show()
            return
        }
        isEnqueuing = true
        enqueued = true
        hapticView.confirmHaptic()
        viewModel.viewModelScope.launch(Dispatchers.Default) {
            try {
                val cleanFolder = com.anonrode.downloader.util.NameSanitizer.cleanShowFolder(show.title)
                // AUDIT FIX (2026-10-19, cross-doc audit BUG 7): enqueue in
                // REVERSE order with queue processing deferred to the final
                // call. repository.addFirst prepends, so the LAST enqueued
                // task lands at index 0: ascending enqueue put E8 at the top
                // of the queue list, and once the initial wave saturated the
                // concurrency slots every later slot-free picked
                // firstOrNull{QUEUED} = E8, E7, … — the tail of any batch
                // larger than the concurrency limit downloaded IN REVERSE.
                // Reversed + a single final trigger: E1 sits at index 0 and
                // starts first; engine semantics and QueueOrderTest untouched.
                toEnqueue.asReversed().forEachIndexed { i, ep ->
                    val isFinalEnqueue = i == toEnqueue.lastIndex
                    viewModel.engine.enqueue(
                        showTitle = cleanFolder,
                        episodeNum = ep.episodeNum,
                        season = ep.season,
                        episode = ep.episode,
                        episodeTitle = com.anonrode.downloader.util.NameSanitizer.formatEpisodeTitle(
                            showTitle = show.title,
                            episodeNum = ep.episodeNum,
                            rawEpisodeLabel = ep.title,
                            season = ep.season,
                            episode = ep.episode,
                            site = ep.site.ifBlank { show.site }
                        ),
                        sourceUrl = ep.url,
                        mirrorUrls = ep.mirrorUrls,
                        isDirect = false,
                        backend = "aria2c",
                        site = ep.site.ifBlank { show.site },
                        parallelSockets = viewModel.engine.parallelSocketsPerFile,
                        verifiedDirectUrl = com.anonrode.downloader.pipeline.ResultVerifier.verifiedDirect(ep.url),
                        seriesUrl = show.url,
                        seriesTotal = maxOf(episodes.size, show.totalEpisodes),
                        posterUrl = show.posterUrl,
                        triggerQueueProcessing = isFinalEnqueue
                    )
                }
                withContext(Dispatchers.Main) { onDismiss() }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    isEnqueuing = false
                    enqueued = false
                    Toast.makeText(context, "Could not queue downloads. Try again.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ═════════════════════════ HALF-SHEET CHASSIS (detents + gestures) ═════════════════════════
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // Full-bleed: the sheet content extends under the status bar at FULL.
            decorFitsSystemWindows = false
        )
    ) {
        // The sheet follows the app theme (DarkSheetPalette / LightSheetPalette),
        // so the dialog window's status-bar icons must follow it too — white
        // icons over the dark sheet, DARK icons over the light sheet. Forcing
        // light icons unconditionally painted an invisible clock over the
        // light sheet at the FULL detent. The app window's own flag re-applies
        // untouched the moment the dialog dismisses.
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        // Read the sheet's dark state HERE: AnonTheme.colors is a @Composable
        // getter, so it cannot be read from the non-composable SideEffect below.
        val sheetIsDark = AnonTheme.colors.isDark
        SideEffect {
            dialogWindow?.let { w ->
                // WindowCompat (core, not the ktx extension) — the plain
                // `window.isAppearanceLightStatusBars` property needs a newer
                // core-ktx than the dependency graph carries.
                WindowCompat.getInsetsController(w, w.decorView)
                    .isAppearanceLightStatusBars = !sheetIsDark
            }
        }
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // W = container width, H = full screen height (MATH CONTRACT inputs).
            val Hpx = constraints.maxHeight.toFloat()
            val Hdp = maxHeight
            val density = LocalDensity.current

            // ── DETENT MATH CONTRACT ─────────────────────────────────────────────
            // HALF = 0.60 × H (sheet height visible at the half detent);
            // FULL = H (top at 0). The sheet's top position is the animated value:
            //   halfTop = H − 0.60·H      (≡ 0.40 × H from the screen top)
            //   fullTop = 0
            // Detent switch threshold: drag > 0.12 × H OR velocity > ~1000dp/s
            // commits the next state, otherwise springs back (Motion.Spatial).
            val halfSheetHeightPx = Hpx * 0.60f            // CONTRACT: HALF = 0.60 × H
            val halfTopPx = Hpx - halfSheetHeightPx        // sheet top at the HALF detent
            val commitDragPx = Hpx * 0.12f                 // CONTRACT: drag > 0.12 × H
            val commitVelocityPx = with(density) { 1000.dp.toPx() }  // CONTRACT: > ~1000dp/s
            val rubberBottomPx = halfTopPx + commitDragPx  // past-half rubber band = one commit distance

            // Single source of truth for the sheet's top edge, animated with
            // Motion.Spatial settings. Gesture paths snapTo() (no animation),
            // release paths animateTo() the winning detent.
            val anim = remember { Animatable(Hpx) }        // enters off-screen, springs to HALF
            val scope = rememberCoroutineScope()
            var settleJob by remember { mutableStateOf<Job?>(null) }
            // Distance travelled since the current touch began — the CONTRACT's
            // "drag of > 0.12 × H between detents" is measured on this accumulator.
            var dragAccumPx by remember { mutableFloatStateOf(0f) }
            var nestedAccumPx by remember { mutableFloatStateOf(0f) }

            /** Cancel any in-flight detent spring and hand-follow by `dy` px. */
            fun rawFollow(dy: Float) {
                settleJob?.cancel()
                dragAccumPx += dy
                val next = (anim.value + dy).coerceIn(0f, Hpx)
                scope.launch { anim.snapTo(next) }
            }

            /** Spring the sheet to `target` (Motion.Spatial default stiffness). */
            fun springTo(target: Float, stiffness: Float = Motion.SpatialStiffnessDefault) {
                settleJob?.cancel()
                settleJob = scope.launch {
                    anim.animateTo(
                        target,
                        spring(dampingRatio = Motion.SpatialDamping, stiffness = stiffness)
                    )
                }
            }

            /** Slide off the bottom of the screen, then hand control back to the caller. */
            fun dismissWithSpring() {
                settleJob?.cancel()
                settleJob = scope.launch {
                    anim.animateTo(
                        Hpx,
                        spring(dampingRatio = Motion.SpatialDamping, stiffness = Motion.SpatialStiffnessFast)
                    )
                    onDismiss()
                }
            }

            /** Nearest of the two detents (FULL = 0, HALF = halfTop). */
            fun nearestDetent(topPx: Float): Float =
                if (abs(topPx) < abs(topPx - halfTopPx)) 0f else halfTopPx

            /**
             * THE DETENT CONTRACT, in one place: a drag of > 0.12 × H between
             * detents or velocity > ~1000dp/s commits the next state, otherwise
             * springs back. Called with (current top, end velocity, accumulated
             * drag since the touch began) from BOTH gesture providers.
             */
            fun commitAfterRelease(currentTop: Float, velocityPx: Float, dragPx: Float) {
                // The release ends the gesture: the 0.12 × H drag rule is measured
                // per gesture, so both accumulators start fresh next time. (Reset
                // up front — the branches below early-return.)
                dragAccumPx = 0f
                nestedAccumPx = 0f
                // Pulled past the HALF detent: the next state below HALF is dismissed.
                // (A hand-drag all the way to the bottom edge lands here too.)
                if (currentTop > halfTopPx + 1f) {
                    if (velocityPx > commitVelocityPx || (currentTop - halfTopPx) > commitDragPx) {
                        dismissWithSpring()
                    } else {
                        springTo(halfTopPx)
                    }
                    return
                }
                val downCommitted = velocityPx > commitVelocityPx || dragPx > commitDragPx
                val upCommitted = velocityPx < -commitVelocityPx || dragPx < -commitDragPx
                when {
                    // At/past HALF and moving down → dismiss; from FULL → collapse.
                    downCommitted && currentTop > halfTopPx - 1f -> dismissWithSpring()
                    downCommitted -> springTo(halfTopPx)
                    upCommitted -> springTo(0f)
                    else -> springTo(nearestDetent(currentTop))
                }
            }

            // Open at the HALF detent (sheet top = 0.40 × H), sliding up from
            // off-screen with the Motion.Spatial spring.
            LaunchedEffect(halfTopPx) {
                if (anim.value >= Hpx - 0.5f) springTo(halfTopPx)
            }

            // Hero/handle drag: the primary gesture path. On release the CONTRACT
            // threshold decides: commit (detent/dismiss) or spring back.
            val sheetDragState = rememberDraggableState { dy -> rawFollow(dy) }

            // ── NESTED-SCROLL CHOREOGRAPHY (episode list ↔ sheet) ────────────────
            // The list scrolls naturally at FULL. Scroll-down while the list is at
            // its scroll-top is handed to the sheet: it collapses toward HALF and
            // keeps rubber-banding past it, so the release can still resolve a
            // full dismiss. Scroll-up while not fully open expands the sheet FIRST
            // and only hands the scroll to the list once it is completely open.
            val listState = rememberLazyListState()
            val scrollConnection = remember {
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        val dy = available.y
                        if (dy < 0f && anim.value > 0f) {  // content pulled up → expand
                            settleJob?.cancel()
                            if (nestedAccumPx > 0f) nestedAccumPx = 0f  // direction flip
                            nestedAccumPx += dy
                            val next = (anim.value + dy).coerceAtLeast(0f)
                            val consumed = next - anim.value
                            scope.launch { anim.snapTo(next) }
                            return Offset(0f, consumed)
                        }
                        return Offset.Zero
                    }

                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                        val dy = available.y
                        // Only leftovers reach here: at FULL that means the list
                        // hit its scroll-top while scrolling down → collapse.
                        if (dy > 0f && anim.value < Hpx - 1f) {
                            settleJob?.cancel()
                            if (nestedAccumPx < 0f) nestedAccumPx = 0f  // direction flip
                            nestedAccumPx += dy
                            val next = (anim.value + dy).coerceAtMost(rubberBottomPx)
                            val consumedY = next - anim.value
                            scope.launch { anim.snapTo(next) }
                            return Offset(0f, consumedY)
                        }
                        return Offset.Zero
                    }

                    override suspend fun onPreFling(available: Velocity): Velocity {
                        // Sheet away from FULL: the detent contract resolves the
                        // fling; the list does not scroll under a moving sheet.
                        if (anim.value < 1f) return Velocity.Zero
                        commitAfterRelease(anim.value, available.y, nestedAccumPx)
                        return available
                    }

                    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                        // Fully open + leftover downward fling speed = the list was
                        // at its scroll-top → collapse per the velocity rule.
                        if (available.y > commitVelocityPx) {
                            commitAfterRelease(anim.value, available.y, 0f)
                            return available
                        }
                        return Velocity.Zero
                    }
                }
            }

            // Gesture-area constants for the drag surface: the grip zone and the
            // whole compact header (art band + poster/title/close strip) drag.
            fun Modifier.sheetDrag() = this.draggable(
                state = sheetDragState,
                orientation = Orientation.Vertical,
                onDragStarted = { _ -> dragAccumPx = 0f },
                onDragStopped = { velocity -> commitAfterRelease(anim.value, velocity, dragAccumPx) }
            )

            // ── v6 CONTRACT GEOMETRY (dp expressions of the formulas above) ─────
            // Detent phase as a discrete boolean: derivedStateOf reads the sheet
            // top in the snapshot layer and flips twice per detent change — drag
            // frames re-read anim.value only in the offset/layer lambdas below.
            val isFull by remember { derivedStateOf { anim.value <= halfTopPx / 2f } }

            val statusBarDp = with(density) { WindowInsets.statusBars.getBottom(density).toDp() }

            val gutter = 16.dp                                           // grid gutter G = 16
            val gripZoneH = 30.dp                                        // HALF grip zone (sheet-y 0→30)
            // CONTRACT: poster 76×108 (§2.3 geometry: radius 10, 1dp white-10%
            // hairline — was 76×112/12).
            val posterW = 76.dp
            val posterH = 108.dp
            val posterLeft = 20.dp                                       // CONTRACT: left 20
            // title x = 20 + 76 + 14 = 110 (spec header Row: spacedBy 14)
            // artTop: HALF = below the 30dp grip · FULL = the real status-bar inset.
            val artTop = if (isFull) statusBarDp else gripZoneH
            // poster y: +8 under the band top at HALF · +14 under the status bar at FULL.
            val posterTop = artTop + if (isFull) 14.dp else 8.dp
            // close ✕ offset from the poster top: 0 at FULL · −2dp at HALF.
            val closeTopOffset = if (isFull) 0.dp else (-2).dp
            // Art band: grows with the header so EVERY pinned header element
            // (poster, title, synopsis, pills) always sits on the theme-proof
            // dark imagery — in light mode a white sheet would swallow the
            // light header text otherwise. 14dp of band below the poster,
            // then the 48dp fade into the sheet color.
            val artBandH = (posterTop - artTop) + posterH + 14.dp        // was fixed 104dp
            val artFadeH = 48.dp                                         // CONTRACT: bottom 48dp fade
            // List column top: chips row top. HALF mock y=166 · FULL y=176 (kept;
            // the Selection/Range chips now PIN here instead of scrolling away — §2.3).
            val listTop = if (isFull) 176.dp else 166.dp

            val scrimAlphaMax = 0.32f  // M3 modal default, kept where the spec is silent
            val sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)  // mock: 24px top corners

            // ── SCRAPING STAGE (loading contract) ───────────────────────────────
            // The pipeline exposes no discrete stage text, so two honest stages
            // rotate: "Finding episode pages…" → "Unlocking stream locker…" after
            // ~4s (a multi-hop locker scrape outlasts a spinner's usefulness).
            var lateStage by remember(show.url) { mutableStateOf(false) }
            LaunchedEffect(isLoading, show.url) {
                lateStage = false
                if (isLoading) {
                    delay(4000)
                    lateStage = true
                }
            }
            val stageText = if (lateStage) STAGE_UNLOCKING else STAGE_FINDING

            // "X GB" total over the selection (null when the source reports no
            // sizes — the pill/footer then show the count alone, nothing invented).
            val selectedSizeLabel = sumSelectedSizeLabel(selectedEpisodes)

            // Scrims: M3-black 32% behind everything, alpha derived from the sheet
            // position (read in the DRAW phase — dragging costs no recomposition).
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val a = (scrimAlphaMax * (1f - (anim.value / Hpx))).coerceIn(0f, 1f)
                        drawRect(Color.Black.copy(alpha = a))
                    }
                    .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) }
            )

            // ═════════ THE SHEET — one full-H surface translated between detents ═════════
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Hdp)
                    // Lambda offset: the detent position is applied in the
                    // placement phase; drag frames do not recompose the content.
                    .offset { IntOffset(0, anim.value.roundToInt()) }
                    .clip(sheetShape)
                    .background(pal.bg)
                    // An empty sheet region (below the sticky bar, etc.) must not
                    // let taps fall through to the scrim — only the scrim itself
                    // dismisses. Children (clickables, the list) still win first.
                    .pointerInput(Unit) { detectTapGestures(onTap = { }) }
            ) {
                // ── ART BAND: self-contained dark hero marquee flush to sheet top corners ──
                Box(
                    modifier = Modifier
                        .sheetDrag()
                        .offset(y = artTop)
                        .fillMaxWidth()
                        .height(artBandH)
                        .clip(sheetShape)
                ) {
                    // Obsidian gradient base (was navy-slate #3A4254→#1B2029;
                    // the band is theme-independent dark imagery, so it now
                    // matches the OLED palette's bandStart→bandEnd).
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0xFF1C1C24), Color(0xFF0B0B0E))
                                )
                            )
                    )
                    // Data-driven fill with cached poster. Cache-only: the
                    // visible 76x108 poster performs the single network
                    // request; this blurred band must never re-request the
                    // same URL (it simply stays on the gradient when cold).
                    if (show.posterUrl.isNotBlank()) {
                        val backdropReq = PosterRequests.request(
                            context = LocalContext.current,
                            url = show.posterUrl,
                            widthPx = 360,
                            heightPx = 360,
                            fallbackUrl = PosterStore.lastGoodFor(show),
                            networkAllowed = false
                        )
                        SubcomposeAsyncImage(
                            model = backdropReq,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .blur(24.dp),
                            loading = {},
                            error = {}
                        )
                    }
                    // Rich dark scrim across entire height (preserves dark marquee in both Light and Dark mode)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Black.copy(alpha = 0.45f), Color(0xEB0B0B0E))
                                )
                            )
                    )
                }

                // ── GRIP ZONE (HALF, sheet-y 0→30): 36×4 drag-handle pill overlaid in banner ──
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(gripZoneH)
                        .graphicsLayer {
                            alpha = (anim.value / halfTopPx).coerceIn(0f, 1f)
                        }
                        .sheetDrag(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.45f))
                    )
                }

                // ── SHARED HEADER ROW (§2.3): poster 76×108 @ left 20 · pills / title column · glass ✕ ──
                Row(
                    modifier = Modifier
                        .sheetDrag()
                        .offset(x = posterLeft, y = posterTop)
                        .padding(end = gutter),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Poster card 76×108 radius 10 with fast cache key reuse
                    Box(
                        modifier = Modifier
                            .shadow(
                                elevation = 18.dp,
                                shape = RoundedCornerShape(10.dp),
                                ambientColor = Color.Black.copy(alpha = 0.62f),
                                spotColor = Color.Black.copy(alpha = 0.62f)
                            )
                            .size(width = posterW, height = posterH)
                            .clip(RoundedCornerShape(10.dp))
                            .background(BandPosterWell)
                            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(10.dp))
                    ) {
                        PosterImageOrFallback(
                            posterUrl = show.posterUrl,
                            showTitle = show.title,
                            modifier = Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(10.dp),
                            fallbackIcon = Icons.Rounded.PlayArrow,
                            showFallbackTitle = false,
                            fallbackPosterUrl = PosterStore.lastGoodFor(show),
                            onLoaded = { PosterStore.markLoaded(show, it) }
                        )
                    }

                    // Right column: badges row on top, title immediately under
                    // it. No heightIn(min=posterH)/SpaceBetween: that pinned
                    // the title to the poster's bottom edge (where the old
                    // layout collided with the poster and left a dead gap).
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .offset(y = 16.dp),
                        verticalArrangement = Arrangement.Top
                    ) {
                        val isSingleMovie = episodes.size <= 1 && seasonGroups.size <= 1
                        val badgeLine = buildList {
                            if (show.site.isNotBlank()) add(show.site.uppercase())
                            if (isSingleMovie) add("MOVIE")
                            else {
                                if (episodes.isNotEmpty()) add("${episodes.size} EPS")
                                if (seasonGroups.size > 1) add("${seasonGroups.size} SEASONS")
                            }
                        }
                        if (badgeLine.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                badgeLine.forEachIndexed { i, label ->
                                    Text(
                                        text = label,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 0.8.sp,
                                        color = if (i == 0) Color(0xFFFFFFFF) else Color.White.copy(alpha = 0.72f),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(Radius.pill))
                                            .background(
                                                if (i == 0) Color.White.copy(alpha = 0.16f)
                                                else Color.Black.copy(alpha = 0.35f)
                                            )
                                            .border(1.dp, BandHairline, RoundedCornerShape(Radius.pill))
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Column {
                            Text(
                                text = show.title,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                lineHeight = 23.sp,
                                // Deliberately the LIGHT band ink in BOTH themes:
                                // the header lives on the art band, which is
                                // theme-independent dark imagery (see the over-band
                                // constants below). pal.textPrimary would paint
                                // LIGHT-theme dark ink (#0F1115) on the dark band —
                                // the exact invisibility this constant avoids.
                                color = Color(0xFFF4F4F6),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // ── CLOSE ✕: 38dp glass circle at right 16 — inset top 12
                    //    from the post-status-bar header line per §2.2. ──
                    Box(
                        modifier = Modifier
                            .offset(y = closeTopOffset)
                            .size(38.dp)
                            // Glass = black-72% bg + white-9% hairline; the 6px
                            // backdrop blur has no stock Compose equivalent and is
                            // approximated by the translucent fill alone.
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.72f))
                            .border(1.dp, BandHairline, CircleShape)
                            .clickable { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White.copy(alpha = 0.90f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                // ── CONTENT REGION (§2.1/§2.3/§2.4): from listTop down, a pinned
                //    Column = Selection Rail (chips + toggleable range field)
                //    then the episode LazyColumn — so the controls NEVER scroll
                //    away and the list is the only thing that scrolls. ──
                Box(
                    modifier = Modifier
                        .offset(y = listTop)
                        .fillMaxWidth()
                        // ── VISIBLE-BOTTOM CLAMP (handover fix, 2026-10) ─────────
                        // The sheet is a FULL-H layout slid down by anim.value, so
                        // at the HALF detent the raw region height (H − listTop)
                        // extended 0.40·H past the screen edge: the list's
                        // viewport bottom (and every row past ~60%) lived
                        // off-screen. Measure against the CURRENTLY VISIBLE
                        // height instead. Reading anim.value during MEASURE
                        // invalidates layout only (never composition), so the
                        // "drag frames do not recompose" contract above holds.
                        .layout { measurable, constraints ->
                            val visiblePx = (Hpx - anim.value).roundToInt().coerceAtLeast(0)
                            val clamped = (visiblePx - listTop.roundToPx())
                                .coerceAtLeast(120.dp.roundToPx())
                                .coerceAtMost(constraints.maxHeight)
                            val placeable = measurable.measure(
                                constraints.copy(minHeight = 0, maxHeight = clamped)
                            )
                            layout(placeable.width, placeable.height) {
                                placeable.place(0, 0)
                            }
                        }
                ) {
                    when {
                        isLoading -> {
                            DrawerLoadingState(stageText = stageText)
                        }
                        episodes.isEmpty() -> {
                            val err = uiState.episodesError
                            DrawerFailureState(
                                reason = err ?: "No stream links found",
                                failedFetch = err != null && err.startsWith("Failed to load", ignoreCase = true),
                                onRetry = {
                                    // Same scrape path the drawer opened with:
                                    // openEpisodeDrawer resets the drawer state and
                                    // relaunches ProviderRegistry.loadEpisodes(show).
                                    viewModel.openEpisodeDrawer(show, preselectRemaining)
                                }
                            )
                        }
                        else -> {
                            val isSingleMovie = episodes.size <= 1 && seasonGroups.size <= 1
                            var showRangeInput by remember(show.url) { mutableStateOf(false) }
                            Column(modifier = Modifier.fillMaxSize()) {
                                // ── DEDICATED SYNOPSIS CONTAINER (below hero banner, on solid sheet background) ──
                                if (showSynopsis.isNotBlank()) {
                                    var synopsisExpanded by rememberSaveable(show.url) { mutableStateOf(false) }
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = gutter, vertical = 6.dp)
                                            // Content Reveal: the synopsis grows on the
                                            // app's spatial spring instead of the stock
                                            // default spec.
                                            .animateContentSize(
                                                spring(Motion.SpatialDamping, Motion.SpatialStiffnessDefault)
                                            )
                                            .clickable { synopsisExpanded = !synopsisExpanded }
                                    ) {
                                        Text(
                                            text = showSynopsis,
                                            fontSize = 13.sp,
                                            lineHeight = 18.sp,
                                            color = pal.textPrimary.copy(alpha = 0.85f),
                                            maxLines = if (synopsisExpanded) Int.MAX_VALUE else 3,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (showSynopsis.length > 90) {
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = if (synopsisExpanded) "Less ▴" else "More ▾",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = pal.accent
                                            )
                                        }
                                    }
                                }

                                // ── RESUME / UP NEXT BANNER (directive §4) ──
                                drawerContinue?.let { card ->
                                    val bannerLabel = if (card.state == ContinueState.RESUME) {
                                        "LAST WATCHED · ${drawerEpLabel(card.season, card.episode)}"
                                    } else {
                                        "UP NEXT · ${drawerEpLabel(card.season, card.episode)}"
                                    }
                                    val bannerSub = if (card.state == ContinueState.RESUME) {
                                        WatchHistoryCore.formatTimeLeft(card.positionMs, card.durationMs)
                                    } else {
                                        "Ready to play"
                                    }
                                    val fraction = if (card.state == ContinueState.RESUME &&
                                        card.durationMs > 0L
                                    ) {
                                        (card.positionMs.toFloat() / card.durationMs.toFloat())
                                            .coerceIn(0.03f, 0.98f)
                                    } else 0f
                                    // Directive §4: tapping the banner OR the
                                    // play circle resumes playback and closes
                                    // the drawer. The whole surface is the
                                    // target, matching the shelf cards.
                                    val playBanner: () -> Unit = {
                                        card.task?.let { target ->
                                            viewModel.activePlayingTaskId.value = target.id
                                            onDismiss()
                                        }
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = gutter, vertical = 4.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(SurfaceElevated)
                                            .border(
                                                1.dp,
                                                AccentViolet.copy(alpha = 0.4f),
                                                RoundedCornerShape(12.dp)
                                            )
                                            .clickable(onClickLabel = "Play") { playBanner() }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(6.dp)
                                                        .clip(CircleShape)
                                                        .background(AccentViolet)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = bannerLabel,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    letterSpacing = 0.4.sp,
                                                    color = AccentViolet
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = bannerSub,
                                                fontSize = 12.sp,
                                                color = TextSecondary
                                            )
                                            if (card.state == ContinueState.RESUME) {
                                                Spacer(modifier = Modifier.height(6.dp))
                                                LinearProgressIndicator(
                                                    progress = { fraction },
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(3.dp),
                                                    color = AccentViolet,
                                                    trackColor = Color(0x33FFFFFF),
                                                    strokeCap = StrokeCap.Square
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(CircleShape)
                                                .background(Color.White),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.PlayArrow,
                                                contentDescription = "Play",
                                                tint = Color(0xFF090B10),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }

                                // ── SELECTION RAIL (pinned): All · [Remaining] · [Seasons…] · [Range] · Clear. ──
                                if (!isSingleMovie) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState())
                                            .padding(start = gutter, end = gutter, bottom = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        // All Chip
                                        val isAllSelected = selectedUrls.size == episodes.size
                                        DrawerChip(
                                            label = "All (${episodes.size})",
                                            selected = isAllSelected,
                                            onClick = {
                                                updateSelected(
                                                    if (isAllSelected) emptySet() else episodes.map { it.url }.toSet()
                                                )
                                            }
                                        )

                                        // Remaining Episodes Chip (when some episodes are already
                                        // completed or in flight). FAILED episodes count as
                                        // Remaining, so they are re-downloadable from here.
                                        if ((completedUrls.isNotEmpty() ||
                                                activeOrQueuedUrls.isNotEmpty() ||
                                                watchedUrls.isNotEmpty()) &&
                                            remainingEpisodes.isNotEmpty()
                                        ) {
                                            val isRemainingSelected = remainingEpisodes.all { it.url in selectedUrls }
                                            DrawerChip(
                                                label = "Remaining (${remainingEpisodes.size})",
                                                selected = isRemainingSelected,
                                                onClick = {
                                                    updateSelected(
                                                        if (isRemainingSelected) emptySet() else remainingEpisodes.map { it.url }.toSet()
                                                    )
                                                },
                                                leading = {
                                                    Icon(
                                                        Icons.Rounded.Download,
                                                        contentDescription = null,
                                                        tint = if (isRemainingSelected) pal.chipInk else pal.textSub,
                                                        modifier = Modifier.size(13.dp)
                                                    )
                                                }
                                            )
                                        }

                                        // Individual Season Chips
                                        if (seasonGroups.size > 1) {
                                            for ((sNum, sEps) in seasonGroups) {
                                                val isSeasonSelected = sEps.all { it.url in selectedUrls }
                                                DrawerChip(
                                                    label = "S$sNum (${sEps.size})",
                                                    selected = isSeasonSelected,
                                                    onClick = {
                                                        val seasonUrls = sEps.map { it.url }.toSet()
                                                        updateSelected(
                                                            if (isSeasonSelected) selectedUrls - seasonUrls else selectedUrls + seasonUrls
                                                        )
                                                    }
                                                )
                                            }
                                        }

                                        // Range toggle chip
                                        if (episodes.size > 6) {
                                            DrawerChip(
                                                label = "Range",
                                                selected = showRangeInput,
                                                onClick = { showRangeInput = !showRangeInput },
                                                leading = {
                                                    Icon(
                                                        Icons.Rounded.FormatListNumbered,
                                                        contentDescription = null,
                                                        tint = if (showRangeInput) pal.chipInk else pal.textSub,
                                                        modifier = Modifier.size(13.dp)
                                                    )
                                                }
                                            )
                                        }

                                        // Clear Chip
                                        if (selectedEpisodes.isNotEmpty()) {
                                            DrawerChip(
                                                label = "Clear (${selectedEpisodes.size})",
                                                selected = false,
                                                onClick = { updateSelected(emptySet()) },
                                                leading = {
                                                    Icon(
                                                        Icons.Rounded.Close,
                                                        contentDescription = null,
                                                        tint = pal.textSub,
                                                        modifier = Modifier.size(13.dp)
                                                    )
                                                }
                                            )
                                        }
                                    }

                                    // Range input — Content Reveal on the shared tokens
                                    // (was AnimatedVisibility on stock defaults).
                                    AnimatedVisibility(
                                        visible = showRangeInput && episodes.size > 6,
                                        enter = fadeIn(tween(Motion.DurationFast)) +
                                            expandVertically(tween(Motion.DurationFast)),
                                        exit = fadeOut(tween(Motion.DurationNavExit)) +
                                            shrinkVertically(tween(Motion.DurationNavExit))
                                    ) {
                                        OutlinedTextField(
                                            value = rangeText,
                                            onValueChange = {
                                                rangeText = it
                                                applyRange(it)
                                            },
                                            placeholder = {
                                                Text(
                                                    "Range (e.g. 1-5, 8, 10)",
                                                    color = pal.textMuted,
                                                    fontSize = 12.5.sp
                                                )
                                            },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(
                                                keyboardType = KeyboardType.Text,
                                                imeAction = ImeAction.Done
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = gutter, end = gutter, bottom = 8.dp)
                                                .height(42.dp),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedContainerColor = pal.card,
                                                unfocusedContainerColor = pal.card,
                                                focusedBorderColor = pal.textSub,
                                                unfocusedBorderColor = pal.hairline,
                                                focusedTextColor = pal.textPrimary,
                                                unfocusedTextColor = pal.textPrimary,
                                                cursorColor = pal.textPrimary
                                            ),
                                            shape = RoundedCornerShape(Radius.md)
                                        )
                                    }
                                }

                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .nestedScroll(scrollConnection),
                                    contentPadding = PaddingValues(
                                        start = gutter,
                                        end = gutter,
                                        // The docked footer below owns the nav-bar
                                        // inset now; the list just needs breathing
                                        // room above the footer hairline.
                                        bottom = 12.dp
                                    )
                                ) {
                                    // ── SEASON SLABS + GROUPED INSET CONTAINERS
                                    //    (§2.5): each season renders as one inset
                                    //    surface block — rows are compact 50dp-inset
                                    //    rows separated by hairline dividers, no more
                                    //    separate floating bordered cards. ──
                                    seasonGroups.forEach { (season, seasonEpisodes) ->
                                        item(key = "season-$season") {
                                            val isExpanded = season in expandedSeasons
                                            // Season slab (CONTRACT): uppercase micro-label +
                                            // full-width hairline rule; tap still collapses the
                                            // season (existing behavior, chevron dropped per mock).
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        expandedSeasons =
                                                            if (isExpanded) expandedSeasons - season else expandedSeasons + season
                                                    }
                                                    .padding(top = 14.dp, bottom = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text(
                                                    text = "Season $season · ${seasonEpisodes.size} episodes".uppercase(),
                                                    color = pal.textMuted,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    letterSpacing = 1.4.sp
                                                )
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(1.dp)
                                                        .background(pal.hairline)
                                                )
                                            }
                                        }
                                        if (season in expandedSeasons) {
                                            itemsIndexed(
                                                items = seasonEpisodes,
                                                key = { i, ep -> "season-$season-${ep.url}|$i" }
                                            ) { i, ep ->
                                                val isSelected = ep.url in selectedUrls
                                                val isDownloaded = ep.url in completedUrls
                                                EpisodeRow(
                                                    episode = ep,
                                                    isSelected = isSelected,
                                                    isDownloaded = isDownloaded,
                                                    isWatched = watchedUrls.contains(ep.url),
                                                    watchTimeLeft = watchFor(ep)
                                                        ?.takeIf { !it.isWatched && it.positionMs > 0L }
                                                        ?.let {
                                                            WatchHistoryCore.formatTimeLeft(
                                                                it.positionMs,
                                                                it.durationMs
                                                            )
                                                        },
                                                    onToggle = {
                                                        hapticView.tick()
                                                        updateSelected(
                                                            if (isSelected) selectedUrls - ep.url else selectedUrls + ep.url
                                                        )
                                                    },
                                                    onDownloadSingle = {
                                                        if (!enqueued && !isEnqueuing) {
                                                            enqueued = true
                                                            hapticView.confirmHaptic()
                                                            viewModel.engine.enqueue(
                                                                showTitle = com.anonrode.downloader.util.NameSanitizer.cleanShowFolder(show.title),
                                                                episodeNum = ep.episodeNum,
                                                                season = ep.season,
                                                                episode = ep.episode,
                                                                episodeTitle = com.anonrode.downloader.util.NameSanitizer.formatEpisodeTitle(
                                                                    showTitle = show.title,
                                                                    episodeNum = ep.episodeNum,
                                                                    rawEpisodeLabel = ep.title,
                                                                    season = ep.season,
                                                                    episode = ep.episode,
                                                                    site = ep.site.ifBlank { show.site }
                                                                ),
                                                                sourceUrl = ep.url,
                                                                mirrorUrls = ep.mirrorUrls,
                                                                isDirect = false,
                                                                backend = "aria2c",
                                                                site = ep.site.ifBlank { show.site },
                                                                parallelSockets = viewModel.engine.parallelSocketsPerFile,
                                                                verifiedDirectUrl = com.anonrode.downloader.pipeline.ResultVerifier.verifiedDirect(ep.url),
                                                                seriesUrl = show.url,
                                                                seriesTotal = maxOf(episodes.size, show.totalEpisodes),
                                                                posterUrl = show.posterUrl
                                                            )
                                                            onDismiss()
                                                        }
                                                    },
                                                    // §2.6 sanitizer: raw scraper labels
                                                    // ("01 Download") and bracketed ids
                                                    // never paint; junk titles fall back
                                                    // to the real episode number.
                                                    displayTitle = TitleFormatting.sanitizeEpisodeTitle(
                                                        rawTitle = ep.title,
                                                        index = if (ep.episodeNum > 0) ep.episodeNum - 1 else i,
                                                        seasonNumber = season
                                                    ),
                                                    groupIndex = i,
                                                    groupSize = seasonEpisodes.size,
                                                    groupColor = pal.group
                                                )
                                            }
                                        }
                                    }
                                }

                                // ── BOTTOM-DOCKED ACTION FOOTER (handover fix, 2026-10)
                                //    The CTA used to sit ABOVE the list while the list's
                                //    viewport ran off-screen at the HALF detent. Now the
                                //    list (weight 1f) ends at the clamped visible bottom
                                //    and this footer is the last child — a thumb-reach
                                //    target in BOTH detents. It owns the nav-bar inset. ──
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(pal.card)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(1.dp)
                                            .background(pal.hairline)
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .navigationBarsPadding()
                                            .padding(start = gutter, end = gutter, top = 8.dp, bottom = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = buildAnnotatedString {
                                                append("Selected ")
                                                withStyle(SpanStyle(color = pal.textPrimary, fontWeight = FontWeight.ExtraBold)) {
                                                    append("$uniqueSelectedCount episode${if (uniqueSelectedCount == 1) "" else "s"}")
                                                }
                                                selectedSizeLabel?.let { append(" · $it") }
                                            },
                                            fontSize = 12.5.sp,
                                            color = pal.textSub,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )

                                        Spacer(modifier = Modifier.width(10.dp))

                                        val queueSrc = remember { MutableInteractionSource() }
                                        val isDownloadAll = uniqueSelectedCount == 0
                                        val ctaLabel = when {
                                            isDownloadAll && isSingleMovie -> "⬇ Download Movie"
                                            isDownloadAll -> "⬇ Download All (${episodes.size})"
                                            else -> "⬇ Download Selected ($uniqueSelectedCount)"
                                        }

                                        Button(
                                            onClick = {
                                                val uniqueCount = selectedEpisodes.distinctBy { it.url }.size
                                                pendingForceAll = uniqueCount == 0
                                                when {
                                                    pendingForceAll ->
                                                        if (episodes.distinctBy { it.url }.size > 10) showBatchConfirmation = true
                                                        else enqueueSelected(forceAll = true)
                                                    uniqueCount > 10 -> showBatchConfirmation = true
                                                    else -> enqueueSelected()
                                                }
                                            },
                                            enabled = !isEnqueuing && !enqueued,
                                            interactionSource = queueSrc,
                                            modifier = Modifier
                                                .height(38.dp)
                                                .pressScale(queueSrc),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = pal.textPrimary,
                                                contentColor = pal.chipInk,
                                                disabledContainerColor = pal.textPrimary.copy(alpha = 0.35f),
                                                disabledContentColor = pal.chipInk.copy(alpha = 0.60f)
                                            ),
                                            shape = RoundedCornerShape(Radius.pill),
                                            contentPadding = PaddingValues(horizontal = 16.dp)
                                        ) {
                                            if (isEnqueuing) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(14.dp),
                                                    strokeWidth = 2.dp,
                                                    color = pal.chipInk
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Queueing...", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                                            } else {
                                                Text(
                                                    text = ctaLabel,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.ExtraBold
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }


                if (showBatchConfirmation) {
                    val uniqueCount = selectedEpisodes.distinctBy { it.url }.size
                    AlertDialog(
                        onDismissRequest = { showBatchConfirmation = false },
                        title = { Text("Queue ${if (pendingForceAll) episodes.distinctBy { it.url }.size else uniqueCount} episodes?") },
                        text = {
                            Text(
                                "This starts one download task per selected episode and may use a large amount of mobile data."
                            )
                        },
                        confirmButton = {
                            TextButton(
                                enabled = !isEnqueuing && !enqueued,
                                onClick = {
                                    showBatchConfirmation = false
                                    enqueueSelected(forceAll = pendingForceAll)
                                }
                            ) {
                                Text("Queue ${if (pendingForceAll) episodes.distinctBy { it.url }.size else uniqueCount}")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showBatchConfirmation = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }
            }
        }
    }
}

/**
 * Episode row — §2.5 grouped-inset row: a compact `heightIn(min = 50.dp)`
 * row inside its season's inset container block (the block paints itself,
 * index-aware: first row rounds its top corners, last row its bottom, and a
 * 0.5dp hairline divider sits above every following row — the old separate
 * 64dp floating bordered cards are gone while LazyColumn recycling KEYS are
 * preserved, which a single container item would have flattened for
 * 100+-episode seasons).
 *
 * Geometry per row: monospace episode number (13sp, own 28dp column — the
 * bulky sunk number TILE is gone), sanitized title 14sp medium with an
 * inline emerald "✓ Downloaded" pill when completed (row content dims to
 * 0.78f), quality/codec/size meta when the data carries it, the 24dp info
 * circle (Feature #26 preview — real per-episode metadata exists, so the
 * spec's "hide unless metadata" condition is met), and a 22dp rounded check
 * indicator (selected = white fill + dark check; unselected = hairline).
 */
@Composable
internal fun EpisodeRow(
    episode: EpisodeItem,
    isSelected: Boolean,
    onToggle: () -> Unit,
    isDownloaded: Boolean = false,
    /** Directive §4: permanent completion outranks Downloaded in the badge. */
    isWatched: Boolean = false,
    /** Non-null when the episode is mid-watch (e.g. "23m left"). */
    watchTimeLeft: String? = null,
    onDownloadSingle: () -> Unit,
    displayTitle: String = episode.title,
    groupIndex: Int = 0,
    groupSize: Int = 1,
    groupColor: Color = Color(0xFF141419)
) {
    // Feature #26: details preview BEFORE enqueue — an info toggle that shows
    // which locker will serve this episode and the filename its URL carries,
    // so a wrong-quality or dead-looking link is visible before a task is
    // queued. Zero network: every field is parsed from the URL already in
    // hand — the real resolve still happens exactly once, at engine start.
    // rememberSaveable, not remember: rows are disposed as the LazyColumn
    // scrolls, and a plain remember silently collapses the panel the user
    // just opened the moment it recycles off-screen.
    var expanded by rememberSaveable { mutableStateOf(false) }
    val (lockerHost, lockerFile) = remember(episode.url) { lockerPreview(episode.url) }
    // Row chrome rides the same palette instance the caller painted the
    // group block with (resolved per composition — the sheet is one theme
    // for its lifetime).
    val pal = sheetPalette(AnonTheme.colors.isDark)
    val groupShape = groupedRowShape(groupIndex, groupSize)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(groupShape)
            .background(groupColor)
    ) {
        if (groupIndex > 0) {
            // 0.5dp hairline divider flush to the group's previous row (§2.5).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(pal.hairline)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp)
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Episode number: clean monospace column, its own left edge.
            Text(
                text = "%02d".format(episode.episodeNum),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = if (isDownloaded) pal.textMuted else pal.textSub,
                modifier = Modifier.width(28.dp)
            )

            // Content Swap: the downloaded dim glides (read in the DRAW phase)
            // and the pill fades in — was an instant 0.78 snap + hard pill.
            val rowDim by animateFloatAsState(
                targetValue = if (isDownloaded) 0.78f else 1f,
                animationSpec = tween(Motion.DurationFast),
                label = "episodeRowDim"
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer { alpha = rowDim },
                verticalArrangement = Arrangement.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayTitle,
                        color = if (isDownloaded) pal.textSub else pal.textPrimary,
                        fontSize = 14.sp,                    // §2.5: 14sp medium
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isWatched || watchTimeLeft != null || isDownloaded) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(modifier = Modifier.width(6.dp))
                            // Directive §4 badge precedence: Watched (violet) >
                            // in-progress time-left > Downloaded (emerald).
                            when {
                                isWatched -> Text(
                                    text = "✓ Watched",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 0.4.sp,
                                    color = Color(0xFFC4B5FD),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(Radius.pill))
                                        .background(Color(0x268B5CF6))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                                watchTimeLeft != null -> Text(
                                    text = watchTimeLeft,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFC4B5FD),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(Radius.pill))
                                        .background(Color(0x268B5CF6))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                                else -> Text(
                                    // §2.6 emerald state pill: ✓ Downloaded / already queued —
                                    // a scannable tick in a 130-row list, colour+word+glyph.
                                    text = "✓ Downloaded",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 0.4.sp,
                                    color = pal.emeraldText,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(Radius.pill))
                                        .background(pal.emeraldTint)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
                // Subtitle: quality · codec · size — parts derive in order
                // (blanks skipped, joined " · ") from data already in hand;
                // when the source offers nothing the whole line is omitted —
                // nothing is invented. The locker host stays behind the ℹ️
                // panel below (presentation never paints resolver names).
                val qualityTag = qualityInText.find(episode.title)?.value?.lowercase()
                    ?: qualityInText.find(lockerFile)?.value?.lowercase()
                val codecTag = codecInText.find(lockerFile)?.value?.lowercase()
                val subLine = listOfNotNull(
                    qualityTag,
                    codecTag,
                    episode.sizeText.takeIf { it.isNotBlank() }
                ).joinToString(" · ")
                if (subLine.isNotBlank()) {
                    Text(
                        text = subLine,
                        color = pal.textMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }

            // 24dp info circle (CONTRACT) — details preview toggle. The glyph
            // "i" is its only child, so the label rides in semantics explicitly.
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .border(1.dp, if (expanded) pal.textSub else pal.hairline, CircleShape)
                    .clickable { expanded = !expanded }
                    .semantics {
                        contentDescription = if (expanded) "Hide episode details" else "Episode details"
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "i",
                    fontSize = 11.sp,
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Bold,
                    color = if (expanded) pal.textPrimary else pal.textMuted
                )
            }

            // (The 34dp 1-tap download circle retired 2026-10: it sat between
            // the ℹ️ toggle and the checkbox, and a mis-tap launched a silent
            // single download which immediately dismissed the whole drawer.
            // Single-episode download still lives in the ℹ️ panel below;
            // deliberate flows go through selection + the docked CTA.)

            // Checkbox indicator with high contrast in both Light and Dark mode
            // (press-scale rides the 22dp control only — never the row, whose
            // grouped segment paint would flash through the seams).
            val checkSrc = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .pressScale(checkSrc)
                    .clip(RoundedCornerShape(7.dp))
                    .then(
                        if (isSelected) {
                            Modifier.background(pal.textPrimary)
                        } else {
                            Modifier.border(1.5.dp, pal.textPrimary.copy(alpha = 0.45f), RoundedCornerShape(7.dp))
                        }
                    )
                    // toggleable, not clickable: the tick icon only exists when
                    // checked, so the unchecked state had no semantics node at
                    // all. The checkbox role + checked state now ride in the
                    // semantics for TalkBack.
                    .toggleable(
                        value = isSelected,
                        interactionSource = checkSrc,
                        indication = null,
                        role = Role.Checkbox,
                        onValueChange = { onToggle() }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = pal.chipInk,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        // Episode details panel — Content Reveal on the shared tokens
        // (was AnimatedVisibility on stock defaults).
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(Motion.DurationFast)) +
                expandVertically(tween(Motion.DurationFast)),
            exit = fadeOut(tween(Motion.DurationNavExit)) +
                shrinkVertically(tween(Motion.DurationNavExit))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 12.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PreviewChip(label = "locker", value = lockerHost)
                    if (lockerFile.isNotBlank()) {
                        PreviewChip(
                            label = "file",
                            value = if (lockerFile.length > 34) lockerFile.takeLast(34) else lockerFile,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
                if (episode.sizeText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Site-reported size: ${episode.sizeText}",
                        color = pal.textSub,
                        fontSize = 11.sp
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                // The row's own download action lives here: three trailing
                // controls per row read as noise — one decision per glance,
                // the heavier action one tap deeper behind the details it
                // belongs with.
                val singleSrc = remember { MutableInteractionSource() }
                FilledTonalButton(
                    onClick = onDownloadSingle,
                    interactionSource = singleSrc,
                    shape = RoundedCornerShape(Radius.sm),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = pal.sunk,
                        contentColor = pal.textPrimary
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, pal.hairline),
                    modifier = Modifier.pressScale(singleSrc)
                ) {
                    Icon(
                        Icons.Rounded.DownloadForOffline,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = pal.textSub
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Queue this episode", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Queue now, or check this link on a browser first.",
                    color = pal.textMuted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ═══════════════════════ LOADING (scraping) STATE ═══════════════════════
// §5.1: a grouped inset container (surface-variant block, radius 14) holding
// 5 compact 50dp shimmer rows separated by hairlines replaces the old 4
// separate double-bordered navy boxes; the stage line reads as honest STEP
// progress ("Step 1/2 · Finding episode pages…") and a thin 5dp accent
// progress track sweeps beneath it.
@Composable
private fun DrawerLoadingState(stageText: String) {
    val reduceMotion = rememberReduceMotion()
    val skeletonX = rememberShimmerX(reduceMotion)
    val pal = sheetPalette(AnonTheme.colors.isDark)
    // Reduce-motion: no transition is created at all (the old code only froze
    // the draw, so the tick kept running forever = pure battery).
    val sweepState = rememberInfiniteSweep(Motion.DurationScrapeSweep, "scrapeProgress")
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
                color = pal.accent,
                trackColor = pal.skeleton
            )
            Text(
                text = stageText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = pal.textSub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Thin 5dp full-width rounded track with an animated accent segment;
        // the sweep position is read in the DRAW phase — no per-frame
        // recomposition of this column.
        Box(
            modifier = Modifier
                .padding(top = 12.dp, bottom = 14.dp)
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(Radius.pill))
                .background(pal.skeleton)
                .drawBehind {
                    val w = size.width
                    if (w <= 0f) return@drawBehind
                    val segW = w * 0.34f
                    val x = sweepState * (w - segW)
                    drawRoundRect(
                        color = pal.accent,
                        topLeft = Offset(x, 0f),
                        size = Size(segW, size.height),
                        cornerRadius = CornerRadius(size.height / 2f)
                    )
                }
        )
        // §5.1 grouped container: ONE inset block, five 50dp rows, hairline
        // dividers — the exact shape the real grouped episode list lands in.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(pal.group)
                .border(1.dp, pal.hairline, RoundedCornerShape(14.dp))
        ) {
            repeat(5) { row ->
                if (row > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(pal.hairline)
                    )
                }
                DrawerSkeletonRow(x = skeletonX, pal = pal)
            }
        }
    }
}

/** Skeleton row in the grouped 50dp inset geometry (§5.1). */
@Composable
private fun DrawerSkeletonRow(x: Float, pal: SheetPalette) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SkeletonBox(
            modifier = Modifier.size(22.dp),
            shape = RoundedCornerShape(5.dp),
            x = x,
            base = pal.skeleton,
            highlight = pal.skeletonHighlight
        )
        Column(modifier = Modifier.weight(1f)) {
            SkeletonBox(
                modifier = Modifier.fillMaxWidth(0.66f).height(10.dp),
                shape = RoundedCornerShape(5.dp),
                x = x,
                base = pal.skeleton,
                highlight = pal.skeletonHighlight
            )
            Spacer(modifier = Modifier.height(7.dp))
            SkeletonBox(
                modifier = Modifier.fillMaxWidth(0.38f).height(7.dp),
                shape = RoundedCornerShape(4.dp),
                x = x,
                base = pal.skeleton,
                highlight = pal.skeletonHighlight
            )
        }
    }
}

// ═══════════════════════ FAILURE STATE ═══════════════════════
// Card (radius 16, hairline) with a rust headline + reason line and a RETRY
// pill wired back into the drawer's own scrape path — the way out is always
// on the table.
@Composable
private fun DrawerFailureState(reason: String, failedFetch: Boolean, onRetry: () -> Unit) {
    val pal = sheetPalette(AnonTheme.colors.isDark)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(pal.group)
                .border(1.dp, pal.hairline, RoundedCornerShape(16.dp))
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (failedFetch) "Couldn't reach the episode locker." else "No playable episodes found.",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = pal.rust
                    )
                    if (reason.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = reason,
                            fontSize = 11.5.sp,
                            color = pal.textSub,
                            lineHeight = 17.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                // RETRY pill (CONTRACT): rust-14% bg, rust-50% border, 10sp/800.
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(pal.rustAccent.copy(alpha = 0.14f))
                        .border(1.dp, pal.rustBorder, RoundedCornerShape(Radius.pill))
                        .clickable(onClick = onRetry)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "RETRY",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = pal.rust,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
    }
}

/** Season/All/Remaining/Clear chip per the mock: pill, hairline, inset-card
 *  bg; selected = text-primary fill with ink label. Palette-selected per
 *  composition so the sheet follows dark AND light themes. */
@Composable
private fun DrawerChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null
) {
    val pal = sheetPalette(AnonTheme.colors.isDark)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(if (selected) pal.textPrimary else pal.card)
            .border(
                1.dp,
                if (selected) pal.textPrimary else pal.hairline,
                RoundedCornerShape(Radius.pill)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (leading != null) leading()
        Text(
            text = label,
            fontSize = 11.5.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            color = if (selected) pal.chipInk else pal.textSub
        )
    }
}

/** Info-panel chip ("locker" / "file"): sunk bg + hairline, content unchanged. */
@Composable
private fun PreviewChip(label: String, value: String, modifier: Modifier = Modifier) {
    val pal = sheetPalette(AnonTheme.colors.isDark)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(pal.sunk)
            .border(1.dp, pal.hairline, RoundedCornerShape(Radius.sm))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label.uppercase(), color = pal.textMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(
            value,
            color = pal.textSub,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// Row-subtitle derivation (presentation only — the facts already riding on the
// title / locker file name, in the approved contract form):
//   qualityInText → "1080p" out of a title or file name
//   codecInText   → "x265" / "hevc" / "h.264" / "av1" out of a file name
// Both normalize lowercase; neither ever invents a value that wasn't there.
private val qualityInText = Regex("""\b(\d{3,4}p)\b""", RegexOption.IGNORE_CASE)
private val codecInText = Regex("""x26[45]|hevc|h\.?264|av1""", RegexOption.IGNORE_CASE)

/** Parse (host, file name) from a locker URL without touching the network.
 *  The name comes from the last path segment; query strings (tokens, ?pt=,
 *  &e=…) are never part of the name. */
private fun lockerPreview(url: String): Pair<String, String> {
    val host = try {
        java.net.URI(url.substringBefore('#')).host ?: "—"
    } catch (_: Exception) {
        url.substringAfter("://").substringBefore('/').substringBefore(':').ifBlank { "—" }
    }
    val seg = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
    val name = try {
        java.net.URLDecoder.decode(seg, "UTF-8")
    } catch (_: Exception) {
        seg
    }
    return host to name
}

/** Parse "2.8 GB" / "412 MB" style site-reported sizes into bytes. Null when
 *  the text carries no recognizable size. Deliberately Regex-free — no new
 *  patterns for the generated RegexCompatCatalog to chase. */
private fun sizeTextToBytes(text: String): Double? {
    val t = text.trim().uppercase()
    var i = 0
    while (i < t.length && (t[i].isDigit() || t[i] == '.' || t[i] == ',')) i++
    if (i == 0) return null
    val num = t.substring(0, i).replace(',', '.').toDoubleOrNull() ?: return null
    val unit = t.substring(i).trim()
    return when {
        unit.startsWith("GIB") || unit.startsWith("GB") -> num * 1024.0 * 1024.0 * 1024.0
        unit.startsWith("MIB") || unit.startsWith("MB") -> num * 1024.0 * 1024.0
        unit.startsWith("KIB") || unit.startsWith("KB") -> num * 1024.0
        else -> null
    }
}

/** "4.2 GB" / "316 MB" total over the selected episodes (distinct URLs only —
 *  shared locker anchors must not double-count). Null when the source reports
 *  no sizes at all: the pill and footer then show the count alone. */
private fun sumSelectedSizeLabel(eps: List<EpisodeItem>): String? {
    var bytes = 0.0
    var any = false
    for (ep in eps.distinctBy { it.url }) {
        sizeTextToBytes(ep.sizeText)?.let {
            bytes += it
            any = true
        }
    }
    if (!any) return null
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        java.lang.String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0)
    } else {
        java.lang.String.format(java.util.Locale.US, "%.0f MB", mb)
    }
}

// ── PINNED DUAL PALETTE (drawer contract, theme-proof) ──────────────────────
// Doctrine kept: the drawer NEVER resolves colors through app theme tokens
// mid-layout — it resolves ONE pinned SheetPalette per composition (dark or
// light, chosen once from AnonTheme.colors.isDark). §1's obsidian values are
// the dark instance; light carries the spec's clean light set. Hexes mirror
// the overhaul mockup exactly.
data class SheetPalette(
    val bg: Color,            // sheet background (spec surface: 0xFF0B0B0E / 0xFFFFFFFF)
    val sunk: Color,          // sticky footer / preview chips / queue wells
    val card: Color,          // chips + range field wells (spec surfaceVariant family)
    val group: Color,         // grouped episode inset container (spec surfaceVariant)
    val skeleton: Color,      // skeleton + progress track base
    val skeletonHighlight: Color,
    val hairline: Color,      // 7% white / 8% black — never a bright opaque gray
    val textPrimary: Color,   // 0xFFF4F4F6 / 0xFF0F1115 (spec onSurface)
    val textSub: Color,       // 0xFF8E8E99 / 0xFF5C606B (spec onSurfaceVariant)
    val textMuted: Color,
    val chipInk: Color,       // ink on white/ink selected-chip fills
    val accent: Color,        // progress/accent bars + initials
    val rust: Color,          // failure headline + RETRY text
    val rustAccent: Color,    // RETRY pill fill (14% alpha at use site)
    val rustBorder: Color,
    val emeraldText: Color,   // §2.6 "✓ Downloaded" pill text
    val emeraldTint: Color,   // §2.6 "✓ Downloaded" pill background
    val handle: Color         // drag-handle pill (theme-contrast on sheet bg)
)

private val DarkSheetPalette = SheetPalette(
    bg = Color(0xFF0B0B0E),
    sunk = Color(0xFF141419),
    card = Color(0xFF141419),
    group = Color(0xFF141419),
    skeleton = Color(0xFF22222A),
    skeletonHighlight = Color(0xFF2E2E38),
    hairline = Color(0x12FFFFFF),
    textPrimary = Color(0xFFF4F4F6),
    textSub = Color(0xFF8E8E99),
    textMuted = Color(0xFF84848E),
    chipInk = Color(0xFF0B0B0E),
    accent = Color(0xFF4FB3D9),
    rust = Color(0xFFE4908A),
    rustAccent = Color(0xFFC25B52),
    rustBorder = Color(0x80C25B52),
    emeraldText = Color(0xFF10B981),
    emeraldTint = Color(0x1F10B981),
    handle = Color.White.copy(alpha = 0.32f)
)

private val LightSheetPalette = SheetPalette(
    bg = Color(0xFFFFFFFF),
    sunk = Color(0xFFE9ECEF),
    card = Color(0xFFF1F3F5),
    group = Color(0xFFF1F3F5),
    skeleton = Color(0xFFE2E6EA),
    skeletonHighlight = Color(0xFFF0F2F4),
    hairline = Color(0x14000000),
    textPrimary = Color(0xFF0F1115),
    textSub = Color(0xFF5C606B),
    textMuted = Color(0xFF686D79),
    chipInk = Color(0xFFFFFFFF),
    accent = Color(0xFF0E7490),
    rust = Color(0xFFB03E36),
    rustAccent = Color(0xFFC25B52),
    rustBorder = Color(0x80C25B52),
    emeraldText = Color(0xFF059669),
    emeraldTint = Color(0x1F059669),
    handle = Color.Black.copy(alpha = 0.28f)
)

/** One palette per composition — the sheet never mixes dark and light roles. */
private fun sheetPalette(isDark: Boolean): SheetPalette =
    if (isDark) DarkSheetPalette else LightSheetPalette

// Over-band constants: the art band is theme-independent dark IMAGERY, so the
// header's poster well / hairlines keep the dark values in BOTH themes —
// light-theme text on the dark band must stay light.
private val BandHairline = Color(0x12FFFFFF)
private val BandPosterWell = Color(0xFF1E1E26)   // §1.4 fallback-art well

/** §2.5 grouped-inset row shape: first row rounds its top corners, last row
 *  its bottom — interior rows stay square so the block reads as ONE surface
 *  (the old separate floating cards are gone). */
private fun groupedRowShape(index: Int, count: Int, radius: Dp = 14.dp): Shape {
    if (count <= 1) return RoundedCornerShape(radius)
    val top = if (index == 0) radius else 0.dp
    val bottom = if (index == count - 1) radius else 0.dp
    return RoundedCornerShape(
        topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom
    )
}

// ── §4.4 episode-sheet TTL cache (in-memory, 30 minutes) ─────────────────────
// Keyed by `source + showUrl`; stores the last SUCCESSFUL scrape so reopening
// a sheet paints instantly instead of re-crawling for 7-8s. The MainViewModel
// stays untouched (strictly off-limits): this is a presentation-layer cache
// the drawer itself populates from the state it already receives. Nothing
// persists to disk — a process kill simply restores the old re-scrape path.
private data class CachedEpisodeList(
    val episodes: List<EpisodeItem>,
    val synopsis: String,
    val savedAtMs: Long
)

private const val EPISODE_CACHE_TTL_MS = 30L * 60L * 1000L   // §4.4: 30-minute TTL
private val cachedEpisodeLists = ConcurrentHashMap<String, CachedEpisodeList>()

private const val STAGE_FINDING = "Step 1/2 · Finding episode pages…"
private const val STAGE_UNLOCKING = "Step 2/2 · Unlocking stream links…"
