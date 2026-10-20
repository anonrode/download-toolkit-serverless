package com.anonrode.downloader.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import coil.size.Scale
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.providers.PosterNetwork
import com.anonrode.downloader.providers.PosterStore
import com.anonrode.downloader.ui.theme.AccentPrimary
import com.anonrode.downloader.ui.theme.SurfaceElevated
import com.anonrode.downloader.ui.theme.TextMuted
import com.anonrode.downloader.ui.theme.TextSecondary
import kotlinx.coroutines.flow.collect
import java.util.LinkedHashSet

/**
 * Shared poster request factory.
 *
 * Every poster surface goes through here so cache keys, decode precision,
 * cache policies and the "loaded" callback agree. Calls NEVER carry
 * Size.ORIGINAL: prefetch/sheet callers pass display pixels, visible cards
 * keep Coil's layout-derived size (no explicit size call).
 */
object PosterRequests {

    private const val MAX_SEEN = 512
    private val seenLock = Any()
    private val seen = LinkedHashSet<String>()

    /**
     * Process-wide in-flight/requested guard used by the prefetcher. Returns
     * true only the first time a URL is seen (bounded LRU-ish set), so
     * streaming partials can never enqueue the same URL twice.
     */
    fun markSeen(url: String): Boolean {
        val key = PosterStore.normalizePosterUrl(url)
        if (key.isBlank()) return false
        synchronized(seenLock) {
            if (seen.contains(key)) return false
            seen.add(key)
            while (seen.size > MAX_SEEN) {
                val it = seen.iterator()
                if (it.hasNext()) {
                    it.next()
                    it.remove()
                } else break
            }
        }
        return true
    }

    fun wasSeen(url: String): Boolean {
        val key = PosterStore.normalizePosterUrl(url)
        if (key.isBlank()) return false
        return synchronized(seenLock) { seen.contains(key) }
    }

    internal fun clearSeenForTest() {
        synchronized(seenLock) { seen.clear() }
    }

    fun request(
        context: Context,
        url: String,
        widthPx: Int? = null,
        heightPx: Int? = null,
        fallbackUrl: String? = null,
        /** false = cache-only (sheet backdrop layer), never touches network. */
        networkAllowed: Boolean = true,
        onLoaded: ((String) -> Unit)? = null
    ): ImageRequest {
        val normalized = PosterStore.normalizePosterUrl(url)
        val builder = ImageRequest.Builder(context)
            .data(normalized)
            .memoryCacheKey(normalized)
            .diskCacheKey(normalized)
            .precision(Precision.INEXACT)
            .scale(Scale.FILL)
            .crossfade(120)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(
                if (networkAllowed && PosterNetwork.isOnline) CachePolicy.ENABLED
                else CachePolicy.DISABLED
            )
        if (widthPx != null && heightPx != null) {
            builder.size(widthPx, heightPx)
        }
        if (!fallbackUrl.isNullOrBlank()) {
            builder.placeholderMemoryCacheKey(PosterStore.normalizePosterUrl(fallbackUrl))
        }
        if (onLoaded != null) {
            builder.listener(object : ImageRequest.Listener {
                override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                    onLoaded(url)
                }
            })
        }
        return builder.build()
    }
}

/**
 * Freezes visible grid content while the user is scrolling.
 *
 * Foundation 1.7.0's LazyGridSpanLayoutProvider crashes
 * (`currentLine > lineIndex`) when the item count/spans mutate while its
 * line prefetcher is scheduling. Streaming partials and load-more both do
 * exactly that. We render the last stable snapshot during scroll and apply
 * the newest pending snapshot once idle, clamping the scroll offset first
 * when the list shrank.
 */
@Composable
fun rememberScrollFrozenList(
    items: List<ShowCard>,
    gridState: LazyGridState
): List<ShowCard> {
    var rendered by remember { mutableStateOf(items) }
    var pending by remember { mutableStateOf(items) }

    LaunchedEffect(items) {
        pending = items
        if (!gridState.isScrollInProgress) rendered = items
    }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) {
                val next = pending
                if (next !== rendered) {
                    if (next.size < rendered.size && next.isNotEmpty()) {
                        val target = gridState.firstVisibleItemIndex.coerceAtMost(next.lastIndex)
                        gridState.scrollToItem(target)
                    }
                    rendered = next
                }
            }
        }
    }
    return rendered
}

/**
 * The single grid implementation for both poster screens.
 *
 * Differences from the old inline LazyVerticalGrid:
 *  - NO conditional full-span footer item (the load-more spinner is an
 *    overlay outside the grid, so span topology never changes on a toggle);
 *  - item content is a frozen snapshot while scrolling;
 *  - duplicate keys are removed before they can crash LazyGrid;
 *  - no animateItem on grid cells (placement animation during streaming was
 *    extra layout churn on top of the span bug).
 */
@Composable
fun StablePosterGrid(
    items: List<ShowCard>,
    gridState: LazyGridState = rememberLazyGridState(),
    modifier: Modifier = Modifier,
    columns: Int = 3,
    contentPadding: PaddingValues = PaddingValues(),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(8.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    isLoadingMore: Boolean = false,
    itemContent: @Composable (ShowCard) -> Unit
) {
    val frozen = rememberScrollFrozenList(items, gridState)
    val unique = remember(frozen) { frozen.distinctBy { it.url } }

    Box(modifier = modifier) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(columns),
            modifier = Modifier
                .fillMaxSize()
                .testTag("stable_poster_grid"),
            contentPadding = contentPadding,
            horizontalArrangement = horizontalArrangement,
            verticalArrangement = verticalArrangement
        ) {
            items(unique, key = { it.url }) { show -> itemContent(show) }
        }
        if (isLoadingMore) {
            CircularProgressIndicator(
                color = AccentPrimary,
                strokeWidth = 2.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .size(24.dp)
            )
        }
    }
}

/**
 * Neutral missing-art placeholder. Deliberately NOT decorative generated
 * art: a flat token surface, the title and one low-emphasis glyph. Used only
 * when no real URL exists or the real fallback also failed.
 */
@Composable
fun PosterFallbackWell(
    modifier: Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    icon: ImageVector = Icons.Rounded.Videocam,
    iconSize: androidx.compose.ui.unit.Dp = 22.dp,
    title: String? = null
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(SurfaceElevated),
        contentAlignment = Alignment.Center
    ) {
        if (title.isNullOrBlank()) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TextMuted.copy(alpha = 0.35f),
                modifier = Modifier.size(iconSize)
            )
        } else {
            Text(
                text = title,
                color = TextSecondary,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TextMuted.copy(alpha = 0.25f),
                modifier = Modifier
                    .size(iconSize)
                    .padding(bottom = 26.dp)
                    .align(Alignment.TopCenter)
            )
        }
    }
}

/**
 * Poster thumb with layered real art:
 *  - `posterUrl` is the incoming primary (live/feed URL);
 *  - `fallbackPosterUrl` is the last URL whose bytes actually loaded;
 *  - the fallback layer is cache-only (no network) and sits UNDER the
 *    primary, so a loading/failing primary never produces a blank frame;
 *  - offline, the last-good URL is promoted to primary and the network is
 *    disabled, so a cold start paints entirely from disk;
 *  - no generated poster art is ever substituted.
 */
@Composable
fun PosterImageOrFallback(
    posterUrl: String,
    showTitle: String,
    modifier: Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    fallbackIcon: ImageVector = Icons.Rounded.PlayArrow,
    showFallbackTitle: Boolean = true,
    fallbackPosterUrl: String? = null,
    onLoaded: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val onLoadedState = rememberUpdatedState(onLoaded)
    val online = PosterNetwork.isOnline
    val lastGood = fallbackPosterUrl
        ?.takeIf { it.isNotBlank() }
        ?.takeIf { PosterStore.normalizePosterUrl(it) != PosterStore.normalizePosterUrl(posterUrl) }
    val effectivePrimary = when {
        posterUrl.isBlank() && lastGood != null -> lastGood
        !online && lastGood != null -> lastGood
        else -> posterUrl
    }
    val underlay = lastGood?.takeIf {
        PosterStore.normalizePosterUrl(it) != PosterStore.normalizePosterUrl(effectivePrimary)
    }

    val placeholder: @Composable () -> Unit = {
        PosterFallbackWell(
            modifier = Modifier.fillMaxSize(),
            shape = shape,
            icon = fallbackIcon,
            title = if (showFallbackTitle) showTitle else null
        )
    }

    if (effectivePrimary.isBlank()) {
        Box(modifier = modifier.clip(shape)) { placeholder() }
        return
    }

    Box(modifier = modifier.clip(shape)) {
        if (underlay != null) {
            val underRequest = remember(context, underlay) {
                PosterRequests.request(
                    context = context,
                    url = underlay,
                    networkAllowed = false,
                    onLoaded = { PosterStore.markUrlLoaded(it) }
                )
            }
            SubcomposeAsyncImage(
                model = underRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                // If the last-good bytes were evicted, show the neutral well
                // instead of letting a transparent underlay reveal nothing.
                loading = { placeholder() },
                error = { placeholder() }
            )
        }
        val primaryRequest = remember(context, effectivePrimary, underlay, online) {
            PosterRequests.request(
                context = context,
                url = effectivePrimary,
                fallbackUrl = underlay,
                onLoaded = { loaded ->
                    onLoadedState.value?.invoke(loaded)
                    PosterStore.markUrlLoaded(loaded)
                }
            )
        }
        SubcomposeAsyncImage(
            model = primaryRequest,
            contentDescription = showTitle,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = { if (underlay == null) placeholder() },
            error = { if (underlay == null) placeholder() }
        )
    }
}
