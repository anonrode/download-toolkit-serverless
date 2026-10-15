package com.anonrode.downloader.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import androidx.lifecycle.viewModelScope
import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.ui.components.EpisodeListSkeleton
import com.anonrode.downloader.ui.theme.*
import com.anonrode.downloader.viewmodel.MainViewModel
import com.anonrode.downloader.ui.util.confirmHaptic
import com.anonrode.downloader.ui.util.tick
import com.anonrode.downloader.ui.util.pressScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * ───────────────────────────────────────── THE MATH CONTRACT ─────────────────────────────────────────
 * "Plex hero" half-sheet. Let W = container width, H = full screen height.
 *   • Banner:   bannerH = W * 9/16 (≈219dp at W=390). Edge-to-edge under the status bar; top scrim
 *               92dp (black 62% → 0) for clock legibility; bottom scrim 92dp fading into #181B22.
 *   • Poster:   96×144dp (2:3), radius 12, shadow 0/18/42 black-62%, outline 1dp white-10%;
 *               anchored left 16dp, top = bannerH − 66dp.
 *   • Title:    x = 16+96+16 = 128dp, aligned to poster's upper third; title 20sp/800;
 *               meta 11sp (seasons · eps — the site never paints here).
 *   • Close ✕:  38dp glass circle (black-72% bg, white-9% outline, blur 6 approximated — no stock
 *               backdrop-blur in Compose) at top-right 16dp, y = 52dp.
 *   • Story:    top = (bannerH − 66 + 144) + 24; 2-line clamp with inline "Read more"; card 76dp.
 *   • Chips:    top = storyTop + storyCardH(76dp) + 40dp; 8px/13px padding, radius full,
 *               canonical #181B22 + hairline; SELECTED = AccentPrimary fill + background ink.
 *   • Range:    top = chipsTop + 65dp, height 42dp, full-width gutter.
 *   • Season:   top = rangeTop + 59dp; then rows every 74dp (row 66dp + 8 gap); row = number tile
 *               34dp + title 11.5sp/700 + mono quality·codec·size 9.5sp + 24dp info circle +
 *               19dp checkbox (selected = AccentPrimary fill + check in background color).
 *   • DETENTS:  HALF = 0.60 × H visible. FULL = H (full-bleed; scrim under clock). Detent switch:
 *               a drag of > 0.12 × H between detents or velocity > ~1000dp/s commits the next
 *               state, otherwise springs back. Spring = Motion.Spatial settings (Tokens.kt).
 * All positions below are COMPUTED EXPRESSIONS of these formulas, never bare magic numbers.
 * ─────────────────────────────────────────────────────────────────────────────────────────────────────
 *
 * ARCHITECTURE NOTE (why not M3 ModalBottomSheet): the contract fixes HALF at 0.60 × H, but stock
 * ModalBottomSheet derives its HalfExpanded anchor from the measured content height internally and
 * exposes no override. Per the contract's fallback branch this drawer is a custom sheet:
 *   • Host: a plain androidx.ui.window.Dialog with usePlatformDefaultWidth=false +
 *     decorFitsSystemWindows=false — the activity already runs edge-to-edge, so the sheet content
 *     extends under the status bar at FULL and the modal still covers the bottom nav bar exactly
 *     like the old ModalBottomSheet. System back → onDismissRequest, no BackHandler needed.
 *   • One full-height content layout is TRANSLATED between detents (offset-lambda reads in the
 *     layout phase) — the user literally drags one continuous surface, no reflow, no second layout.
 *   • Gestures: Modifier.draggable on the hero/handle plus a NestedScrollConnection on the episode
 *     list — scroll-down at the list's top collapses the sheet and continues to dismiss; scroll-up
 *     while not fully open expands it before handing the scroll to the list. All stable APIs.
 * Season grouping, range parsing, Track-4 "Download Remaining" chips and the enquiry/enqueue
 * behavior are carried over verbatim from the previous drawer.
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
     *  the normal sticky Queue bar, and any manual deselection wins. */
    preselectRemaining: Boolean = false
) {
    val uiState by viewModel.uiState.collectAsState()
    val episodes = uiState.drawerEpisodes
    val isLoading = uiState.isEpisodesLoading
    val context = LocalContext.current
    // Haptic engine handle (UI research round): queueing a download is the
    // app's main commitment moment, and multi-select needs per-item
    // acknowledgement. Default flags only — the system haptics toggle wins.
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

    val allTasks by viewModel.engine.tasks.collectAsState()
    val downloadedOrQueuedUrls = remember(allTasks, show.title) {
        val cleanTitle = show.title.trim().lowercase()
        allTasks.filter { task ->
            task.showTitle.trim().lowercase() == cleanTitle ||
            (cleanTitle.isNotBlank() && task.showTitle.contains(show.title, ignoreCase = true))
        }.map { it.sourceUrl }.filter { it.isNotBlank() }.toSet()
    }
    val remainingEpisodes = remember(episodes, downloadedOrQueuedUrls) {
        episodes.filter { it.url !in downloadedOrQueuedUrls }
    }

    // One-shot auto-selection: fires once the drawer's live scrape lands. The
    // applied flag keeps a later episodes emission (provider refresh, cache
    // update) from re-selecting episodes the user manually deselected.
    var autoApplied by remember(show.url) { mutableStateOf(false) }
    LaunchedEffect(preselectRemaining, episodes, downloadedOrQueuedUrls) {
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
        updateSelected(episodes.filter { it.episodeNum in targetNums }.map { it.url }.toSet())
    }

    // Dynamic Season Grouping for 1-Tap Filter Chips
    val seasonGroups = remember(episodes) {
        val groups = mutableMapOf<Int, MutableList<EpisodeItem>>()
        for (ep in episodes) {
            val seasonMatch = Regex("S([0-9]{1,2})(?:E([0-9]{1,3}))?", RegexOption.IGNORE_CASE).find(ep.title)
            val sNum = seasonMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("/watch/(?:tv|movie)/\\d+/(\\d+)(?:/|$)", RegexOption.IGNORE_CASE)
                    .find(ep.url)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: if (ep.episodeNum >= 100) ep.episodeNum / 100 else 1
            groups.getOrPut(sNum) { mutableListOf() }.add(ep)
        }
        groups.toSortedMap().mapValues { (_, values) -> values.sortedBy { it.episodeNum } }
    }
    var expandedSeasons by remember(show.url, seasonGroups.keys) {
        mutableStateOf(seasonGroups.keys)
    }
    val uniqueSelectedCount = selectedEpisodes.distinctBy { it.url }.size

    fun enqueueSelected() {
        if (enqueued || isEnqueuing) return
        // Combined provider pages may intentionally reuse one anchor URL for
        // multiple episodes. Keep every selected row visible, but never enqueue
        // that source more than once in the same batch.
        val toEnqueue = selectedEpisodes
            .distinctBy { it.url }
            .sortedWith(compareBy(
                { episode -> seasonGroups.entries.firstOrNull { it.value.any { it === episode } }?.key ?: Int.MAX_VALUE },
                { it.episodeNum }
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
                toEnqueue.forEach { ep ->
                    viewModel.engine.enqueue(
                        showTitle = cleanFolder,
                        episodeNum = ep.episodeNum,
                        episodeTitle = com.anonrode.downloader.util.NameSanitizer.formatEpisodeTitle(
                            showTitle = show.title,
                            episodeNum = ep.episodeNum,
                            rawEpisodeLabel = ep.title
                        ),
                        sourceUrl = ep.url,
                        mirrorUrls = ep.mirrorUrls,
                        isDirect = false,
                        backend = "aria2c",
                        site = ep.site.ifBlank { show.site },
                        parallelSockets = viewModel.engine.parallelSocketsPerFile,
                        verifiedDirectUrl = com.anonrode.downloader.pipeline.ResultVerifier.verifiedDirect(ep.url),
                        seriesUrl = show.url,
                        seriesTotal = episodes.size
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
            // Full-bleed: the banner must run under the status bar at FULL.
            decorFitsSystemWindows = false
        )
    ) {
        // The dialog covers the whole screen and its sheet is dark in BOTH
        // themes, but a Light-theme user's dialog window inherits the light
        // appearance flag — dark status-bar icons painted over the dark
        // sheet, i.e. an invisible clock at FULL. Force light (white) icons
        // on the dialog's own window; the app window's flag re-applies
        // untouched the moment the dialog dismisses.
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.let { w ->
                // WindowCompat (core, not the ktx extension) — the plain
                // `window.isAppearanceLightStatusBars` property needs a newer
                // core-ktx than the dependency graph carries.
                WindowCompat.getInsetsController(w, w.decorView)
                    .isAppearanceLightStatusBars = false
            }
        }
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // W = container width, H = full screen height (MATH CONTRACT inputs).
            val Hpx = constraints.maxHeight.toFloat()
            val Wdp = maxWidth
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

            // Gesture-area constants for the drag surface: the hero (banner +
            // poster/title strip) and the half-detent pill are all draggable.
            fun Modifier.sheetDrag() = this.draggable(
                state = sheetDragState,
                orientation = Orientation.Vertical,
                onDragStarted = { _ -> dragAccumPx = 0f },
                onDragStopped = { velocity -> commitAfterRelease(anim.value, velocity, dragAccumPx) }
            )

            // ── CONTRACT LAYOUT MATH (dp expressions of the formulas above) ──────
            val bannerH = Wdp * 9f / 16f                              // bannerH = W * 9/16
            val posterW = 96.dp                                       // 96×144dp (2:3)
            val posterH = 144.dp
            val posterTop = bannerH - 66.dp                           // top = bannerH − 66dp
            val titleX = Spacing.lg + posterW + Spacing.lg            // x = 16+96+16 = 128dp
            val titleTop = posterTop + 17.dp                          // poster's upper third (mock: 153+17=170)
            val storyTop = posterTop + posterH + 24.dp                // (bannerH − 66 + 144) + 24
            val storyCardH = 76.dp
            val chipsTop = storyTop + storyCardH + 40.dp              // storyTop + 76 + 40
            val rangeTop = chipsTop + 65.dp                           // chipsTop + 65
            val seasonTop = rangeTop + 59.dp                          // rangeTop + 59

            val scrimAlphaMax = 0.32f  // M3 modal default, kept where the spec is silent
            // Bottom inset so the sticky action bar never hides behind the
            // gesture navigation bar at FULL.
            val navBottomDp = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }

            val sheetShape = RoundedCornerShape(topStart = Radius.xl, topEnd = Radius.xl)

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
                    .background(SurfaceElevated)
                    // An empty sheet region (below the sticky bar, etc.) must not
                    // let taps fall through to the scrim — only the scrim itself
                    // dismisses. Children (clickables, the list) still win first.
                    .pointerInput(Unit) { detectTapGestures(onTap = { }) }
            ) {
                // ── BANNER (W × bannerH = W * 9/16, full-bleed under the clock) ──
                Box(
                    modifier = Modifier
                        .sheetDrag()
                        .fillMaxWidth()
                        .height(bannerH)
                ) {
                    // Data-driven hero: no new network — the poster the drawer
                    // already has is reused as the banner fill. Blur comes from
                    // Compose's stable Modifier.blur (coil 2.7 ships no
                    // coil.transform.BlurTransformation), darkening via the 40%
                    // black overlay + scrims below (contract fallback).
                    if (show.posterUrl.isNotBlank()) {
                        AsyncImage(
                            model = show.posterUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .blur(24.dp)
                        )
                    } else {
                        // Neutral charcoal fallback: the hero is never dead black,
                        // but the monochrome doctrine bars colored art fills.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.linearGradient(
                                        listOf(Color(0xFF232733), Color(0xFF0D0F14))
                                    )
                                )
                        )
                    }
                    // 40% black overlay over the blurred art (contract).
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.40f))
                    )
                    // Top scrim 92dp: black 62% → 0 for clock legibility.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(92.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Black.copy(alpha = 0.62f), Color.Black.copy(alpha = 0f))
                                )
                            )
                    )
                    // Bottom scrim 92dp fading into the sheet color (#181B22).
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(92.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(SurfaceElevated.copy(alpha = 0f), SurfaceElevated)
                                )
                            )
                    )
                }

                // ── POSTER CARD 96×144, left 16, top = bannerH − 66 ──
                Box(
                    modifier = Modifier
                        .sheetDrag()
                        .offset(x = Spacing.lg, y = posterTop)
                        // Shadow 0/18/42 black-62% (contract): Modifier.shadow
                        // cannot offset a shadow, so the 42px blur is emulated
                        // with a large 18dp elevation around the poster shape.
                        .shadow(
                            elevation = 18.dp,
                            shape = RoundedCornerShape(12.dp),
                            ambientColor = Color.Black.copy(alpha = 0.62f),
                            spotColor = Color.Black.copy(alpha = 0.62f)
                        )
                        .size(width = posterW, height = posterH)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceCard)
                        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                ) {
                    if (show.posterUrl.isNotBlank()) {
                        AsyncImage(
                            model = show.posterUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            text = show.title.trim().split(Regex("\\s+")).take(2)
                                .mapNotNull { it.firstOrNull()?.uppercaseChar() }
                                .joinToString(""),
                            fontSize = 24.sp, fontWeight = FontWeight.Black,
                            color = AccentPrimary, modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }

                // ── TITLE BLOCK: x = 128dp, upper third of the poster ──
                Box(
                    modifier = Modifier
                        .sheetDrag()
                        .offset(x = titleX, y = titleTop)
                        .width(Wdp - titleX - Spacing.lg)
                ) {
                    Column {
                        Text(
                            text = show.title,
                            fontSize = 20.sp,               // CONTRACT: 20sp/800
                            fontWeight = FontWeight.ExtraBold,
                            lineHeight = 23.sp,
                            color = TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        // Meta line: seasons · eps. User directive 2026-10-15 — the
                        // site stays in the model, the ℹ️ info sheet and the journal;
                        // presentation never paints it. Blank parts (synthetic
                        // Downloads-series cards) never join the line.
                        val metaLine = listOf(
                            if (episodes.isEmpty()) null else
                                if (seasonGroups.size > 1) "${seasonGroups.size} seasons" else null,
                            if (episodes.isEmpty()) null else "${episodes.size} eps"
                        ).filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
                        Text(
                            text = metaLine,
                            fontSize = 11.sp,               // CONTRACT: 11sp meta
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 7.dp)
                        )
                    }
                }

                // ── CLOSE ✕: 38dp glass circle, top-right 16dp, y = 52dp ──
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-Spacing.lg), y = 52.dp)
                        .size(38.dp)
                        // Glass = black-72% bg + white-9% hairline; the 6px
                        // backdrop blur has no stock Compose equivalent and is
                        // approximated by the translucent fill alone.
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.72f))
                        .border(1.dp, Color.White.copy(alpha = 0.09f), CircleShape)
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = TextSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                }

                // ── HALF-DETENT HANDLE + pull hint (fade out toward FULL) ──
                // The alpha reads anim.value in the LAYER phase (graphicsLayer
                // lambda), so detent changes never recompose the handle.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .graphicsLayer {
                            // Visible at HALF, gone at FULL — alpha from the
                            // sheet top position itself (1 at halfTop, 0 at 0).
                            alpha = (anim.value / halfTopPx).coerceIn(0f, 1f)
                        }
                        .sheetDrag()
                        .padding(top = 10.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(width = 36.dp, height = 4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(BorderHairline)
                        )
                        Text(
                            text = "Pull up for the full view",
                            color = TextMuted,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }

                // ── STORY CARD: top = (bannerH − 66 + 144) + 24, 76dp, 2-line clamp ──
                if (uiState.drawerSynopsis.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .offset(x = Spacing.lg, y = storyTop)
                            .width(Wdp - Spacing.lg - Spacing.lg)
                            .height(storyCardH)
                            .clip(RoundedCornerShape(14.dp))
                            .background(SurfaceCard)
                            .border(1.dp, BorderHairline, RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        var storyExpanded by rememberSaveable { mutableStateOf(false) }
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "STORY",
                                    color = TextMuted,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.sp
                                )
                                Spacer(Modifier.width(10.dp))
                                // Inline "Read more" on the STORY header line.
                                if (uiState.drawerSynopsis.length > 140) {
                                    Text(
                                        text = if (storyExpanded) "Show less" else "Read more",
                                        color = AccentPrimary,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clickable { storyExpanded = !storyExpanded }
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            // 2-line clamp at rest; expanded text scrolls inside
                            // the fixed 76dp card so the CONTRACT geometry below
                            // (chips/range/season tops) never shifts.
                            val storyScroll = rememberScrollState()
                            Text(
                                text = uiState.drawerSynopsis,
                                color = TextSecondary,
                                fontSize = 11.5.sp,
                                lineHeight = 17.sp,
                                maxLines = if (storyExpanded) Int.MAX_VALUE else 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .padding(top = 5.dp)
                                    .then(
                                        if (storyExpanded) Modifier
                                            .height(34.dp)
                                            .verticalScroll(storyScroll)
                                        else Modifier
                                    )
                            )
                        }
                    }
                }

                // ── SEASON CHIPS ROW: top = storyTop + 76 + 40 ──
                if (episodes.isNotEmpty()) {
                    // 1-Tap Batch Season Selector Chips Row — 8dp gaps (design
                    // pass: interactive chips need mis-touch breathing room).
                    // Doctrine §3: chip = full radius, SurfaceElevated + hairline;
                    // SELECTED = AccentPrimary fill + background-ink text; no
                    // pastel/violet fills, status colors only as 13dp icon tints.
                    Row(
                        modifier = Modifier
                            .offset(x = Spacing.lg, y = chipsTop)
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        // All Chip
                        val isAllSelected = selectedUrls.size == episodes.size
                        FilterChip(
                            selected = isAllSelected,
                            onClick = {
                                updateSelected(if (isAllSelected) emptySet() else episodes.map { it.url }.toSet())
                            },
                            shape = RoundedCornerShape(Radius.full),
                            label = { Text("All (${episodes.size})", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (isAllSelected) BackgroundDark else TextPrimary) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentPrimary,
                                selectedLabelColor = BackgroundDark,
                                containerColor = SurfaceElevated,
                                labelColor = TextPrimary
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                borderColor = BorderHairline,
                                selectedBorderColor = AccentPrimary,
                                enabled = true,
                                selected = isAllSelected
                            )
                        )

                        // Remaining Episodes Chip (when some episodes are already downloaded/queued)
                        if (downloadedOrQueuedUrls.isNotEmpty() && remainingEpisodes.isNotEmpty()) {
                            val isRemainingSelected = remainingEpisodes.all { it.url in selectedUrls }
                            FilterChip(
                                selected = isRemainingSelected,
                                onClick = {
                                    updateSelected(if (isRemainingSelected) emptySet() else remainingEpisodes.map { it.url }.toSet())
                                },
                                shape = RoundedCornerShape(Radius.full),
                                label = {
                                    Text(
                                        "Remaining (${remainingEpisodes.size})",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isRemainingSelected) BackgroundDark else TextPrimary
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Rounded.Download,
                                        contentDescription = null,
                                        tint = if (isRemainingSelected) BackgroundDark else TextSecondary,
                                        modifier = Modifier.size(13.dp)
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AccentPrimary,
                                    selectedLabelColor = BackgroundDark,
                                    containerColor = SurfaceElevated,
                                    labelColor = TextPrimary
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    borderColor = BorderHairline,
                                    selectedBorderColor = AccentPrimary,
                                    enabled = true,
                                    selected = isRemainingSelected
                                )
                            )
                        }

                        // Individual Season Chips
                        if (seasonGroups.size > 1) {
                            for ((sNum, sEps) in seasonGroups) {
                                val isSeasonSelected = sEps.all { it.url in selectedUrls }
                                FilterChip(
                                    selected = isSeasonSelected,
                                    onClick = {
                                        val seasonUrls = sEps.map { it.url }.toSet()
                                        updateSelected(
                                            if (isSeasonSelected) {
                                                selectedUrls - seasonUrls
                                            } else {
                                                selectedUrls + seasonUrls
                                            }
                                        )
                                    },
                                    shape = RoundedCornerShape(Radius.full),
                                    label = { Text("Season $sNum (${sEps.size})", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (isSeasonSelected) BackgroundDark else TextSecondary) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AccentPrimary,
                                        selectedLabelColor = BackgroundDark,
                                        containerColor = SurfaceElevated,
                                        labelColor = TextSecondary
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        borderColor = BorderHairline,
                                        selectedBorderColor = AccentPrimary,
                                        enabled = true,
                                        selected = isSeasonSelected
                                    )
                                )
                            }
                        }

                        // Clear / Invert Chip — status color is ONLY the 13dp icon
                        // tint, per the monochrome doctrine (no pastel fills).
                        if (selectedEpisodes.isNotEmpty()) {
                            FilterChip(
                                selected = false,
                                shape = RoundedCornerShape(Radius.full),
                                onClick = { updateSelected(emptySet()) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = null,
                                        tint = StatusError,
                                        modifier = Modifier.size(13.dp)
                                    )
                                },
                                label = {
                                    Text(
                                        text = "Clear (${selectedEpisodes.size})",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = SurfaceElevated,
                                    labelColor = TextPrimary
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    borderColor = BorderHairline,
                                    enabled = true,
                                    selected = false
                                )
                            )
                        }
                    }

                    // ── RANGE FIELD: top = chipsTop + 65dp, height 42dp ──
                    Box(
                        modifier = Modifier
                            .offset(x = Spacing.lg, y = rangeTop)
                            .width(Wdp - Spacing.lg - Spacing.lg)
                    ) {
                        OutlinedTextField(
                            value = rangeText,
                            onValueChange = {
                                rangeText = it
                                applyRange(it)
                            },
                            placeholder = { Text("Range (e.g. 1-5, 8, 10)", color = TextMuted, fontSize = 10.5.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Done
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentPrimary,
                                unfocusedBorderColor = BorderHairline,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(Radius.md)
                        )
                    }
                }

                // ── SEASON SECTION + EPISODE LIST: top = rangeTop + 59dp ──
                // Rows ride the 74dp rhythm (row 66dp + 8 gap) via spacedBy(8dp)
                // and the 66dp row spec in EpisodeRow.
                Column(
                    modifier = Modifier
                        .offset(y = seasonTop)
                        .fillMaxWidth()
                        .height(Hdp - seasonTop)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (isLoading) {
                            // Skeleton rows in the drawer's own 44dp rhythm, with the honest
                            // one-line status kept above them (UI research round): a
                            // multi-hop locker scrape outlasts a spinner's usefulness, and
                            // the copy is what says "this is still working".
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.lg)
                            ) {
                                Spacer(modifier = Modifier.height(Spacing.sm))
                                Text(
                                    "Scraping episode locker streams…",
                                    color = TextSecondary,
                                    fontSize = Type.label.fontSize
                                )
                                Spacer(modifier = Modifier.height(Spacing.sm))
                                EpisodeListSkeleton()
                            }
                        } else if (episodes.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(150.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    if (uiState.episodesError != null) {
                                        Text(
                                            text = uiState.episodesError ?: "No stream links found",
                                            color = StatusError,
                                            fontSize = Type.body.fontSize,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(Spacing.xs))
                                        Text("Close and try again", color = TextMuted, fontSize = Type.caption.fontSize)
                                    } else {
                                        Text("No stream links found for this title.", color = TextMuted, fontSize = Type.body.fontSize)
                                    }
                                }
                            }
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .nestedScroll(scrollConnection),
                                verticalArrangement = Arrangement.spacedBy(Spacing.sm), // 8dp gap of the 74dp rhythm
                                contentPadding = PaddingValues(
                                    start = 14.dp,
                                    end = 14.dp,
                                    // Keep the last rows reachable above the sticky
                                    // bar and the gesture nav bar.
                                    bottom = 88.dp + navBottomDp
                                )
                            ) {
                                seasonGroups.forEach { (season, seasonEpisodes) ->
                                    item(key = "season-$season") {
                                        val isExpanded = season in expandedSeasons
                                        val selectedInSeason = seasonEpisodes.count { it.url in selectedUrls }
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    expandedSeasons = if (isExpanded) expandedSeasons - season else expandedSeasons + season
                                                }
                                                // Season header: 10dp/6dp padding (mock).
                                                .padding(top = 10.dp, bottom = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                                        ) {
                                            Icon(
                                                imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                                contentDescription = if (isExpanded) "Collapse season $season" else "Expand season $season",
                                                tint = TextSecondary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = "Season $season",
                                                color = TextPrimary,
                                                fontSize = 13.5.sp,     // mock: 13.5px bold
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                text = "$selectedInSeason/${seasonEpisodes.size} selected",
                                                color = TextMuted,
                                                fontSize = 11.sp        // mock: 11px
                                            )
                                        }
                                    }
                                    if (season in expandedSeasons) {
                                        itemsIndexed(
                                            items = seasonEpisodes,
                                            key = { i, ep -> "season-$season-${ep.url}|$i" }
                                        ) { _, ep ->
                                            val isSelected = ep.url in selectedUrls
                                            EpisodeRow(
                                                episode = ep,
                                                isSelected = isSelected,
                                                isDownloaded = ep.url in downloadedOrQueuedUrls,
                                                onToggle = {
                                                    hapticView.tick()
                                                    updateSelected(if (isSelected) selectedUrls - ep.url else selectedUrls + ep.url)
                                                },
                                                onDownloadSingle = {
                                                    if (!enqueued && !isEnqueuing) {
                                                        enqueued = true
                                                        hapticView.confirmHaptic()
                                                        viewModel.engine.enqueue(
                                                            showTitle = com.anonrode.downloader.util.NameSanitizer.cleanShowFolder(show.title),
                                                            episodeNum = ep.episodeNum,
                                                            episodeTitle = com.anonrode.downloader.util.NameSanitizer.formatEpisodeTitle(
                                                                showTitle = show.title,
                                                                episodeNum = ep.episodeNum,
                                                                rawEpisodeLabel = ep.title
                                                            ),
                                                            sourceUrl = ep.url,
                                                            mirrorUrls = ep.mirrorUrls,
                                                            isDirect = false,
                                                            backend = "aria2c",
                                                            site = ep.site.ifBlank { show.site },
                                                            parallelSockets = viewModel.engine.parallelSocketsPerFile,
                                                            verifiedDirectUrl = com.anonrode.downloader.pipeline.ResultVerifier.verifiedDirect(ep.url),
                                                            seriesUrl = show.url,
                                                            seriesTotal = episodes.size
                                                        )
                                                        onDismiss()
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Sticky Bottom Floating Batch Action Bar (visible at FULL;
                    // at HALF the chips above carry the selection feedback).
                    AnimatedVisibility(
                        visible = selectedEpisodes.isNotEmpty(),
                        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                    ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.sm)
                                .padding(bottom = navBottomDp),
                            shape = RoundedCornerShape(Radius.lg),
                            color = SurfaceCard,
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderHairline)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "$uniqueSelectedCount Selected",
                                        color = TextPrimary,
                                        fontSize = Type.body.fontSize,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Ready to queue",
                                        color = TextSecondary,
                                        fontSize = Type.caption.fontSize
                                    )
                                }

                                val queueSrc = remember { MutableInteractionSource() }
                                Button(
                                    onClick = {
                                        val uniqueCount = selectedEpisodes.distinctBy { it.url }.size
                                        if (uniqueCount > 10) {
                                            showBatchConfirmation = true
                                        } else {
                                            enqueueSelected()
                                        }
                                    },
                                    enabled = !isEnqueuing,
                                    interactionSource = queueSrc,
                                    modifier = Modifier.pressScale(queueSrc),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = AccentPrimary,
                                        contentColor = BackgroundDark
                                    ),
                                    shape = RoundedCornerShape(Radius.md)
                                ) {
                                    if (isEnqueuing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = BackgroundDark
                                        )
                                        Spacer(modifier = Modifier.width(Spacing.xs))
                                        Text("Queueing...", fontWeight = FontWeight.Bold, fontSize = Type.label.fontSize)
                                    } else {
                                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(Spacing.xs))
                                        Text("Download ($uniqueSelectedCount)", fontWeight = FontWeight.Bold, fontSize = Type.label.fontSize)
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
                        title = { Text("Queue $uniqueCount episodes?") },
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
                                    enqueueSelected()
                                }
                            ) {
                                Text("Queue $uniqueCount")
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
 * Episode row — the CONTRACT row spec: number tile 34dp + title 11.5sp/700 +
 * mono quality·codec·size subtitle 9.5sp + 24dp info circle + 19dp checkbox
 * (selected = AccentPrimary
 * fill + check in background color). 34dp tile + 2×16dp vertical padding keeps
 * the row at 66dp; the LazyColumn adds the 8dp gap of the 74dp rhythm.
 */
@Composable
fun EpisodeRow(
    episode: EpisodeItem,
    isSelected: Boolean,
    onToggle: () -> Unit,
    isDownloaded: Boolean = false,
    onDownloadSingle: () -> Unit
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(if (isSelected || expanded) SurfaceCard else Color.Transparent)
            .border(
                1.dp,
                if (isSelected) AccentPrimary.copy(alpha = 0.5f) else BorderHairline.copy(alpha = 0.4f),
                RoundedCornerShape(13.dp)
            )
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            // 34dp number tile + 2×16dp vertical padding = 66dp row (74dp rhythm).
            .padding(horizontal = 13.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Episode-number tile: the row's spine. Scanning a 130-row list is a
        // number-lookup, and a 34dp tile keeps every number on the same
        // optical left edge — the checkbox never has to do this job.
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (isSelected) SurfaceCard else SurfaceElevated)
                .border(1.dp, if (isSelected) AccentPrimary.copy(alpha = 0.5f) else BorderHairline, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "%02d".format(episode.episodeNum),
                fontSize = Type.label.fontSize,
                fontWeight = FontWeight.ExtraBold,
                color = when {
                    isSelected -> TextPrimary
                    isDownloaded -> TextMuted
                    else -> TextSecondary
                }
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = episode.title,
                // Already-downloaded rows dim (Track 4): still selectable — a
                // re-download is a legitimate choice — but the muted title +
                // done-tick makes the state scannable in a 130-row list.
                color = when {
                    isSelected -> TextPrimary
                    isDownloaded -> TextMuted
                    else -> TextSecondary
                },
                fontSize = 11.5.sp,                 // CONTRACT: 11.5sp/700
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Subtitle: quality · codec · size — the why-pick-this-one facts,
            // not the plumbing. User directive 2026-10-15 — presentation never
            // paints site/resolver/locker-host names; the host stays behind the
            // ℹ️ panel below (progressive disclosure) and in the journal. Parts
            // derive in order (blanks skipped, joined " · ") from data already
            // in hand — nothing is invented; when the source offers nothing the
            // bare "—" fallback remains. Monospace 9.5sp (CONTRACT).
            val qualityTag = qualityInText.find(episode.title)?.value?.lowercase()
                ?: qualityInText.find(lockerFile)?.value?.lowercase()
            val codecTag = codecInText.find(lockerFile)?.value?.lowercase()
            val subLine = listOfNotNull(
                qualityTag,
                codecTag,
                episode.sizeText.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            Text(
                text = subLine.ifBlank { "—" },
                color = TextMuted,
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (isDownloaded) {
            Spacer(modifier = Modifier.width(Spacing.xs))
            Icon(
                imageVector = Icons.Rounded.DownloadDone,
                contentDescription = "Downloaded or queued",
                // Status colors appear ONLY as 13dp icon tints (doctrine §3).
                tint = StatusSuccess,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(Spacing.xs))
        }

        // 24dp info circle (CONTRACT row spec) — details preview toggle.
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .border(1.dp, if (expanded) AccentPrimary.copy(alpha = 0.6f) else BorderHairline, CircleShape)
                .clickable { expanded = !expanded },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = "Preview details",
                tint = if (expanded) AccentPrimary else TextMuted,
                modifier = Modifier.size(11.dp)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // 19dp checkbox (CONTRACT): selected = AccentPrimary fill + background-
        // colored check. The row click also toggles; both consume the tap.
        Box(
            modifier = Modifier
                .size(19.dp)
                .clip(RoundedCornerShape(5.dp))
                .then(
                    if (isSelected) {
                        Modifier.background(AccentPrimary)
                    } else {
                        Modifier.border(1.5.dp, TextMuted, RoundedCornerShape(5.dp))
                    }
                )
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = if (isSelected) "Selected" else "Not selected",
                    tint = BackgroundDark,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }

    AnimatedVisibility(visible = expanded) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.sm)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
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
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = "Site-reported size: ${episode.sizeText}",
                    color = TextSecondary,
                    fontSize = Type.label.fontSize
                )
            }
            Spacer(modifier = Modifier.height(Spacing.sm))
            // The row's own download action lives here now: three trailing
            // controls per row (info / download / checkbox) read as noise —
            // one decision per glance, the heavier action one tap deeper
            // behind the details it belongs with.
            val singleSrc = remember { MutableInteractionSource() }
            FilledTonalButton(
                onClick = onDownloadSingle,
                interactionSource = singleSrc,
                shape = RoundedCornerShape(Radius.sm),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = SurfaceCard,
                    contentColor = TextPrimary
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderHairline),
                modifier = Modifier.pressScale(singleSrc)
            ) {
                Icon(
                    Icons.Rounded.DownloadForOffline,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = AccentPrimary
                )
                Spacer(modifier = Modifier.width(Spacing.xs))
                Text("Queue this episode", fontSize = Type.label.fontSize, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Queue now, or check this link on a browser first.",
                color = TextMuted,
                fontSize = Type.micro.fontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
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

@Composable
private fun PreviewChip(label: String, value: String, modifier: Modifier = Modifier) {    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.sm))
            // SurfaceElevated, not a background token with alpha (design
            // pass): BackgroundDark.copy(0.5f) inverted into a washed-out
            // half-white chip the moment the light theme renders it.
            .background(SurfaceElevated)
            .padding(horizontal = Spacing.sm, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label.uppercase(), color = TextMuted, fontSize = Type.micro.fontSize, fontWeight = FontWeight.Bold)
        Text(
            value,
            color = TextSecondary,
            fontSize = Type.caption.fontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
