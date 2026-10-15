package com.anonrode.downloader.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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
import com.anonrode.downloader.ui.components.SkeletonBox
import com.anonrode.downloader.ui.components.rememberReduceMotion
import com.anonrode.downloader.ui.components.rememberShimmerX
import com.anonrode.downloader.ui.theme.Motion
import com.anonrode.downloader.ui.theme.Radius
import com.anonrode.downloader.viewmodel.MainViewModel
import com.anonrode.downloader.ui.util.confirmHaptic
import com.anonrode.downloader.ui.util.tick
import com.anonrode.downloader.ui.util.pressScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

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
    val episodes = uiState.drawerEpisodes
    val isLoading = uiState.isEpisodesLoading
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
                        seriesTotal = episodes.size,
                        posterUrl = show.posterUrl
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
            // Bottom inset so the sticky footer and the last rows never hide
            // behind the gesture navigation bar.
            val navBottomDp = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }

            val gutter = 16.dp                                           // grid gutter G = 16
            val gripZoneH = 30.dp                                        // HALF grip zone (sheet-y 0→30)
            val artBandH = 104.dp                                        // CONTRACT: art band 104
            val artFadeH = 48.dp                                         // CONTRACT: bottom 48dp fade
            val posterW = 76.dp                                          // CONTRACT: poster 76×112
            val posterH = 112.dp
            val posterLeft = 20.dp                                       // CONTRACT: left 20
            // title x = 20 + 76 + 16 = 112 (CONTRACT)
            // artTop: HALF = below the 30dp grip (mock sheet-y 30) · FULL = the real
            // status-bar inset (mock device 34dp → band 34→138)
            val artTop = if (isFull) statusBarDp else gripZoneH
            // poster y: +8 under the band top at HALF (mock (20, 38)) · +14 under the
            // status bar at FULL (mock (20, 48))
            val posterTop = artTop + if (isFull) 14.dp else 8.dp
            // close ✕ offset from the poster top: 0 at FULL (mock ✕ @ 48) ·
            // −2dp at HALF (mock ✕ @ 36, two above the poster)
            val closeTopOffset = if (isFull) 0.dp else (-2).dp
            // List column top: the chips row is item 0, so the chips land exactly
            // on it. HALF mock y=166 (range absent; rows reach 214 = 166+32+16) ·
            // FULL mock y=176 (range at 224 = 176+32+16; rows from ~300)
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
                    .background(SheetBg)
                    // An empty sheet region (below the sticky bar, etc.) must not
                    // let taps fall through to the scrim — only the scrim itself
                    // dismisses. Children (clickables, the list) still win first.
                    .pointerInput(Unit) { detectTapGestures(onTap = { }) }
            ) {
                // ── ART BAND 104dp: charcoal gradient → blurred poster → black 38%
                //    → bottom 48dp fade into the sheet color (CONTRACT header). ──
                Box(
                    modifier = Modifier
                        .sheetDrag()
                        .offset(y = artTop)
                        .fillMaxWidth()
                        .height(artBandH)
                ) {
                    // Neutral charcoal base: the band is never dead black even
                    // when the image fails (mock: #3A4254 → #1B2029).
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0xFF3A4254), Color(0xFF1B2029))
                                )
                            )
                    )
                    // Data-driven fill: the poster the drawer already has is
                    // reused as the band art (Compose's stable Modifier.blur —
                    // coil 2.7 ships no BlurTransformation). No URL → the
                    // charcoal base shows through the overlays.
                    if (show.posterUrl.isNotBlank()) {
                        AsyncImage(
                            model = show.posterUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .blur(24.dp)
                        )
                    }
                    // 38% black overlay over the blurred art (CONTRACT).
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.38f))
                    )
                    // Bottom 48dp fade into the sheet color (CONTRACT).
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(artFadeH)
                            .background(
                                Brush.verticalGradient(
                                    listOf(SheetBg.copy(alpha = 0f), SheetBg)
                                )
                            )
                    )
                }

                // ── GRIP ZONE (HALF, sheet-y 0→30): 36×4 bar + uppercase pull hint.
                //    Alpha reads anim.value in the LAYER phase so detent drags
                //    never recompose. Drawn after the art band so the hint never
                //    sinks under it. ──
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(gripZoneH)
                        .graphicsLayer {
                            // Visible at HALF, gone at FULL — alpha from the
                            // sheet top position itself (1 at halfTop, 0 at 0).
                            alpha = (anim.value / halfTopPx).coerceIn(0f, 1f)
                        }
                        .sheetDrag(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .padding(top = 9.dp)
                                .size(width = 36.dp, height = 4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.White.copy(alpha = 0.32f))
                        )
                        Text(
                            text = PULL_HINT.uppercase(),
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 10.sp,               // user floor: mock 8.5 → 10
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.8.sp,
                            modifier = Modifier.padding(top = 7.dp)
                        )
                    }
                }

                // ── SHARED HEADER ROW: poster 76×112 @ left 20 · title @ x=112 ·
                //    glass ✕ right 16. Everything here drags the sheet. ──
                Row(
                    modifier = Modifier
                        .sheetDrag()
                        .offset(x = posterLeft, y = posterTop)
                        .padding(end = gutter),
                    verticalAlignment = Alignment.Top
                ) {
                    // Poster card 76×112 radius 12 — shadow 0/18/42 black-62%
                    // (Modifier.shadow cannot offset a shadow; the 42px blur is
                    // emulated with an 18dp elevation around the poster shape),
                    // white-10% border; initials fallback in accent on card bg.
                    Box(
                        modifier = Modifier
                            .shadow(
                                elevation = 18.dp,
                                shape = RoundedCornerShape(12.dp),
                                ambientColor = Color.Black.copy(alpha = 0.62f),
                                spotColor = Color.Black.copy(alpha = 0.62f)
                            )
                            .size(width = posterW, height = posterH)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SheetCard)
                            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                    ) {
                        if (show.posterUrl.isNotBlank()) {
                            AsyncImage(
                                model = show.posterUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            // Initials: first letters of up to 2 words, 24sp/Black
                            // in the pinned accent on the card background.
                            Text(
                                text = show.title.trim().split(Regex("\\s+")).take(2)
                                    .mapNotNull { it.firstOrNull()?.uppercaseChar() }
                                    .joinToString(""),
                                fontSize = 24.sp, fontWeight = FontWeight.Black,
                                color = SheetAccent, modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(gutter))  // title lands at x = 112

                    // Title block: 16dp below the poster's top in BOTH detents
                    // (mock HALF y=54, FULL y=64 — posterTop + 16).
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .offset(y = 16.dp)
                    ) {
                        Text(
                            text = show.title,
                            fontSize = 20.sp,               // CONTRACT: 20sp/ExtraBold
                            fontWeight = FontWeight.ExtraBold,
                            lineHeight = 24.sp,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        // Meta line: seasons · eps. User directive 2026-10-15 — the
                        // site stays in the model, the ℹ️ info panel and the journal;
                        // presentation never paints it. Blank parts (synthetic
                        // Downloads-series cards) never join the line.
                        val metaLine = listOf(
                            if (episodes.isEmpty()) null else
                                if (seasonGroups.size > 1) "${seasonGroups.size} seasons" else null,
                            if (episodes.isEmpty()) null else "${episodes.size} eps"
                        ).filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
                        if (metaLine.isNotBlank()) {
                            Text(
                                text = metaLine,
                                fontSize = 11.sp,               // CONTRACT: 11sp text-sub
                                color = SheetTextSub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                        // Selection summary pill (CONTRACT): bold-white count +
                        // size on black-35%, hairline border — only when a
                        // selection exists.
                        if (uniqueSelectedCount > 0) {
                            Text(
                                text = buildAnnotatedString {
                                    withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.ExtraBold)) {
                                        append("$uniqueSelectedCount selected")
                                    }
                                    selectedSizeLabel?.let { append(" · $it") }
                                },
                                fontSize = 10.sp,               // CONTRACT: 10sp/800
                                fontWeight = FontWeight.ExtraBold,
                                color = SheetTextSub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .padding(top = 10.dp)
                                    .clip(RoundedCornerShape(Radius.pill))
                                    .background(Color.Black.copy(alpha = 0.35f))
                                    .border(1.dp, SheetHairline, RoundedCornerShape(Radius.pill))
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // ── CLOSE ✕: 38dp glass circle at right 16 ──
                    Box(
                        modifier = Modifier
                            .offset(y = closeTopOffset)
                            .size(38.dp)
                            // Glass = black-72% bg + white-9% hairline; the 6px
                            // backdrop blur has no stock Compose equivalent and is
                            // approximated by the translucent fill alone.
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.72f))
                            .border(1.dp, SheetHairline, CircleShape)
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

                // ── CONTENT REGION: everything from listTop down is ONE LazyColumn
                //    (chips → range[FULL only] → season slabs → rows), so HALF gets
                //    four rows + a peek in 511dp ((511−214)/72 = 4.1) and FULL gets
                //    one continuous scroll under the sticky footer. Loading and
                //    failure states occupy the same box (no content jumps). ──
                Box(
                    modifier = Modifier
                        .offset(y = listTop)
                        .fillMaxWidth()
                        .height((Hdp - listTop).coerceAtLeast(120.dp))
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
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .nestedScroll(scrollConnection),
                                contentPadding = PaddingValues(
                                    start = gutter,
                                    end = gutter,
                                    // FULL: keep the last rows reachable above the
                                    // 76dp sticky footer + gesture nav bar; HALF has
                                    // no footer — a breathing gap + the nav bar.
                                    bottom = (if (isFull) 92.dp else 24.dp) + navBottomDp
                                )
                            ) {
                                // ── SEASON CHIPS ROW (item 0 → chips land on listTop) ──
                                item(key = "chips") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState())
                                            .padding(bottom = 16.dp),  // HALF: 166+32+16 = rows @ 214
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

                                        // Remaining Episodes Chip (when some episodes are
                                        // already downloaded/queued — Track 4)
                                        if (downloadedOrQueuedUrls.isNotEmpty() && remainingEpisodes.isNotEmpty()) {
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
                                                        tint = if (isRemainingSelected) SheetChipInk else SheetTextSub,
                                                        modifier = Modifier.size(13.dp)
                                                    )
                                                }
                                            )
                                        }

                                        // Individual Season Chips (compact "S1 (10)" per mock)
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

                                        // Clear Chip — neutral (no status color fills
                                        // outside the failure box, palette doctrine).
                                        if (selectedEpisodes.isNotEmpty()) {
                                            DrawerChip(
                                                label = "Clear (${selectedEpisodes.size})",
                                                selected = false,
                                                onClick = { updateSelected(emptySet()) },
                                                leading = {
                                                    Icon(
                                                        Icons.Rounded.Close,
                                                        contentDescription = null,
                                                        tint = SheetTextSub,
                                                        modifier = Modifier.size(13.dp)
                                                    )
                                                }
                                            )
                                        }
                                    }
                                }

                                // ── RANGE FIELD: contract 42dp, radius 12, card bg —
                                //    visible only at FULL (hidden at HALF). Its top lands
                                //    exactly at mock y=224 (176 + chips 32 + gap 16). ──
                                if (isFull) {
                                    item(key = "range") {
                                        Box(modifier = Modifier.padding(bottom = 16.dp)) {
                                            OutlinedTextField(
                                                value = rangeText,
                                                onValueChange = {
                                                    rangeText = it
                                                    applyRange(it)
                                                },
                                                placeholder = {
                                                    Text(
                                                        "Range (e.g. 1-5, 8, 10)",
                                                        color = SheetTextMuted,
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
                                                    .height(42.dp),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedContainerColor = SheetCard,
                                                    unfocusedContainerColor = SheetCard,
                                                    focusedBorderColor = Color.White.copy(alpha = 0.35f),
                                                    unfocusedBorderColor = SheetHairline,
                                                    focusedTextColor = SheetTextPrimary,
                                                    unfocusedTextColor = SheetTextPrimary,
                                                    cursorColor = SheetTextPrimary
                                                ),
                                                shape = RoundedCornerShape(Radius.md)
                                            )
                                        }
                                    }
                                }

                                // ── SEASON SLABS + EPISODE ROWS (72dp rhythm: 64 + 8) ──
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
                                                color = SheetTextMuted,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                letterSpacing = 1.4.sp
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(1.dp)
                                                    .background(SheetHairline)
                                            )
                                        }
                                    }
                                    if (season in expandedSeasons) {
                                        itemsIndexed(
                                            items = seasonEpisodes,
                                            key = { i, ep -> "season-$season-${ep.url}|$i" }
                                        ) { _, ep ->
                                            val isSelected = ep.url in selectedUrls
                                            Box(modifier = Modifier.padding(bottom = 8.dp)) {
                                                EpisodeRow(
                                                    episode = ep,
                                                    isSelected = isSelected,
                                                    isDownloaded = ep.url in downloadedOrQueuedUrls,
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
                                                                seriesTotal = episodes.size,
                                                                posterUrl = show.posterUrl
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
                    }
                }

                // ── STICKY FOOTER (FULL only): 76dp, sunk-97%, top hairline,
                //    padding 12/16 + navigationBars inset. Left = selection
                //    summary with bold-white counts; right = white Download pill. ──
                AnimatedVisibility(
                    visible = isFull && episodes.isNotEmpty(),
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SheetSunk.copy(alpha = 0.97f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(SheetHairline)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(75.dp + navBottomDp)  // 75 + hairline 1 = the 76dp CONTRACT (+ nav inset)
                                .padding(start = gutter, end = gutter)
                                .padding(top = 12.dp)
                                .padding(bottom = 12.dp + navBottomDp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = buildAnnotatedString {
                                    append("Selected ")
                                    withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.ExtraBold)) {
                                        append("$uniqueSelectedCount episode${if (uniqueSelectedCount == 1) "" else "s"}")
                                    }
                                    selectedSizeLabel?.let { append(" · $it") }
                                },
                                fontSize = 12.5.sp,             // CONTRACT: 12.5sp text-sub
                                color = SheetTextSub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )

                            Spacer(modifier = Modifier.width(12.dp))

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
                                enabled = uniqueSelectedCount > 0 && !isEnqueuing && !enqueued,
                                interactionSource = queueSrc,
                                modifier = Modifier
                                    .height(44.dp)              // CONTRACT: ~44dp pill
                                    .pressScale(queueSrc),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SheetTextPrimary,
                                    contentColor = SheetChipInk,
                                    disabledContainerColor = SheetTextPrimary.copy(alpha = 0.35f),
                                    disabledContentColor = SheetChipInk.copy(alpha = 0.60f)
                                ),
                                shape = RoundedCornerShape(Radius.pill),
                                contentPadding = PaddingValues(horizontal = 20.dp)
                            ) {
                                if (isEnqueuing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = SheetChipInk
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Queueing...", fontSize = 12.5.sp, fontWeight = FontWeight.ExtraBold)
                                } else {
                                    Icon(
                                        Icons.Rounded.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Download", fontSize = 12.5.sp, fontWeight = FontWeight.ExtraBold)
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
 * Episode row — the CONTRACT row spec: 64dp tall, card bg, hairline border,
 * radius 14; number tile 34dp radius 9 (sunk bg, hairline, 11.5sp/800);
 * title 12sp/700 single line; quality meta 10sp muted mono (only the parts the
 * data actually provides — quality/codec/size, blanks skipped, nothing
 * invented); 24dp info circle (hairline border, italic i); 19dp checkbox
 * radius 6 (unselected: white-35% 1.5dp border; selected: white #F2F5F9 fill +
 * dark #0B0E13 check). The row click also toggles; both consume the tap.
 * Rows already downloaded/queued stay selectable but dim (Track 4 scan-ability).
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
            .clip(RoundedCornerShape(14.dp))
            .background(SheetCard)
            .border(1.dp, SheetHairline, RoundedCornerShape(14.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Episode-number tile: the row's spine. Scanning a 130-row list is a
            // number-lookup, and a 34dp tile keeps every number on the same
            // optical left edge — the checkbox never has to do this job.
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(SheetSunk)
                    .border(1.dp, SheetHairline, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "%02d".format(episode.episodeNum),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isDownloaded) SheetTextMuted else SheetTextSub
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = episode.title,
                    // Already-downloaded rows dim (Track 4): still selectable — a
                    // re-download is a legitimate choice — but the muted title
                    // makes the state scannable in a 130-row list.
                    color = if (isDownloaded) SheetTextSub else SheetTextPrimary,
                    fontSize = 12.sp,                    // CONTRACT: 12sp/700
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Subtitle: quality · codec · size — parts derive in order
                // (blanks skipped, joined " · ") from data already in hand;
                // when the source offers nothing the whole line is omitted —
                // nothing is invented. User directive 2026-10-15 — presentation
                // never paints site/resolver/locker-host names here; the host
                // stays behind the ℹ️ panel below.
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
                        color = SheetTextMuted,
                        fontSize = 10.sp,                // contract 9.5 → 10sp user floor
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }

            // 24dp info circle (CONTRACT) — details preview toggle.
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .border(1.dp, if (expanded) SheetTextSub else SheetHairline, CircleShape)
                    .clickable { expanded = !expanded },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "i",
                    fontSize = 11.sp,
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Bold,
                    color = if (expanded) SheetTextPrimary else SheetTextMuted
                )
            }

            // 19dp checkbox (CONTRACT, radius 6): selected = white #F2F5F9 fill +
            // dark #0B0E13 check; unselected = white-35% 1.5dp border.
            Box(
                modifier = Modifier
                    .size(19.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .then(
                        if (isSelected) {
                            Modifier.background(SheetTextPrimary)
                        } else {
                            Modifier.border(1.5.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                        }
                    )
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = if (isSelected) "Selected" else "Not selected",
                        tint = SheetChipInk,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
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
                        color = SheetTextSub,
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
                        containerColor = SheetSunk,
                        contentColor = SheetTextPrimary
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SheetHairline),
                    modifier = Modifier.pressScale(singleSrc)
                ) {
                    Icon(
                        Icons.Rounded.DownloadForOffline,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = SheetTextSub
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Queue this episode", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Queue now, or check this link on a browser first.",
                    color = SheetTextMuted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ═══════════════════════ LOADING (scraping) STATE ═══════════════════════
// Must be VISIBLE on the dark sheet: skeleton rows in the EXACT episode-row
// geometry (64dp card, 34dp tile, two bars 66%/38% in the pinned #2A303C),
// an honest stage line, and a thin 5dp full-width accent progress track.
@Composable
private fun DrawerLoadingState(stageText: String) {
    val reduceMotion = rememberReduceMotion()
    val skeletonX = rememberShimmerX(reduceMotion)
    val sweepState = rememberInfiniteTransition(label = "scrapeProgress").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scrapeSweep"
    )
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
                color = SheetAccent,
                trackColor = SheetSkeleton
            )
            Text(
                text = stageText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = SheetTextSub,
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
                .background(SheetSkeleton)
                .drawBehind {
                    val w = size.width
                    if (w <= 0f) return@drawBehind
                    val segW = w * 0.34f
                    val x = if (reduceMotion) 0f else sweepState.value * (w - segW)
                    drawRoundRect(
                        color = SheetAccent,
                        topLeft = Offset(x, 0f),
                        size = Size(segW, size.height),
                        cornerRadius = CornerRadius(size.height / 2f)
                    )
                }
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(5) { DrawerSkeletonRow(x = skeletonX) }
        }
    }
}

/** Skeleton row in the exact 64dp episode-row geometry (CONTRACT loading). */
@Composable
private fun DrawerSkeletonRow(x: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(SheetCard)
            .border(1.dp, SheetHairline, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SkeletonBox(
            modifier = Modifier.size(34.dp),
            shape = RoundedCornerShape(9.dp),
            x = x,
            base = SheetSkeleton,
            highlight = SkeletonHighlight
        )
        Column(modifier = Modifier.weight(1f)) {
            SkeletonBox(
                modifier = Modifier.fillMaxWidth(0.66f).height(10.dp),
                shape = RoundedCornerShape(5.dp),
                x = x,
                base = SheetSkeleton,
                highlight = SkeletonHighlight
            )
            Spacer(modifier = Modifier.height(7.dp))
            SkeletonBox(
                modifier = Modifier.fillMaxWidth(0.38f).height(7.dp),
                shape = RoundedCornerShape(4.dp),
                x = x,
                base = SheetSkeleton,
                highlight = SkeletonHighlight
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
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(SheetCard)
                .border(1.dp, SheetHairline, RoundedCornerShape(16.dp))
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (failedFetch) "Couldn't reach the episode locker." else "No playable episodes found.",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = SheetRust
                    )
                    if (reason.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = reason,
                            fontSize = 11.5.sp,
                            color = SheetTextSub,
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
                        .background(SheetRustAccent.copy(alpha = 0.14f))
                        .border(1.dp, SheetRustBorder, RoundedCornerShape(Radius.pill))
                        .clickable(onClick = onRetry)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "RETRY",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = SheetRust,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
    }
}

/** Season/All/Remaining/Clear chip per the mock: pill, hairline, card bg;
 *  selected = white #F2F5F9 fill with dark #0B0E13 ink. */
@Composable
private fun DrawerChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(if (selected) SheetTextPrimary else SheetCard)
            .border(
                1.dp,
                if (selected) SheetTextPrimary else SheetHairline,
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
            color = if (selected) SheetChipInk else SheetTextSub
        )
    }
}

/** Info-panel chip ("locker" / "file"): sunk bg + hairline, content unchanged. */
@Composable
private fun PreviewChip(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(SheetSunk)
            .border(1.dp, SheetHairline, RoundedCornerShape(Radius.sm))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label.uppercase(), color = SheetTextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(
            value,
            color = SheetTextSub,
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

// ── PINNED DARK PALETTE (drawer contract, theme-proof) ──────────────────────
// NEVER resolve these through the app theme tokens: the shipped bug was the
// drawer reading light-theme colors and smearing. Hexes mirror the approved
// mockup exactly (declaration order follows the palette contract).
private val SheetBg = Color(0xFF171B23)                 // sheet background
private val SheetSunk = Color(0xFF12141A)               // sunk wells / footer / preview chips
private val SheetCard = Color(0xFF1D222C)               // cards, chips, poster fallback
private val SheetSkeleton = Color(0xFF2A303C)           // skeletons + progress track
private val SkeletonHighlight = Color(0xFF383E4A)       // skeleton shimmer highlight (2A303C + white 7%)
private val SheetHairline = Color.White.copy(alpha = 0.09f)
private val SheetTextPrimary = Color(0xFFF2F5F9)        // titles, selected chip fill, button fill
private val SheetTextSub = Color.White.copy(alpha = 0.66f)
private val SheetTextMuted = Color.White.copy(alpha = 0.42f)
private val SheetChipInk = Color(0xFF0B0E13)            // ink on white fills
private val SheetAccent = Color(0xFF4FB3D9)             // progress/accent bars on dark + initials
private val SheetRust = Color(0xFFE4908A)               // failure headline + RETRY text
private val SheetRustAccent = Color(0xFFC25B52)         // RETRY pill fill (14% alpha at use site)
private val SheetRustBorder = Color(0x80C25B52)         // RETRY pill border (50% alpha)

private const val PULL_HINT = "Pull up for the full view"
private const val STAGE_FINDING = "Finding episode pages…"
private const val STAGE_UNLOCKING = "Unlocking stream locker…"
