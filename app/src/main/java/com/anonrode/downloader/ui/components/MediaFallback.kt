package com.anonrode.downloader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage

/**
 * §4.1 / §1.4 / §5.4 stylized poster-fallback composable. Replaces the flat
 * single-letter gray blocks ("A", "P", "M") and the glaring light monogram
 * tiles ("MN", "JK", "PS", "SO"):
 *   - a dark gradient well (0xFF181824 -> 0x0C0C12, spec §4.1) that reads as
 *     deliberate art in BOTH themes (never a white brick on a light page);
 *   - one subtle centered media icon (alpha 0.18, spec §4.1);
 *   - optionally the show's title wrapped cleanly inside the frame (poster
 *     branch) — or no text at all for the small social/direct thumb (§5.4).
 *
 * Pure presentation: callers pass the size/shape; the well is identical
 * everywhere so every fallback surface sings the same note.
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
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF181824), Color(0xFF0C0C12))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        if (title.isNullOrBlank()) {
            // Small-well mode (52×72 download thumbs, §5.4): icon alone.
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.18f),
                modifier = Modifier.size(iconSize)
            )
        } else {
            // Poster mode (§4.1): icon above, title centered in the frame.
            Text(
                text = title,
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 10.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.18f),
                modifier = Modifier
                    .size(iconSize)
                    .padding(bottom = 26.dp)
                    .align(Alignment.TopCenter)
            )
        }
    }
}

/**
 * Poster thumb WITH art: SubcomposeAsyncImage over the same well (loading /
 * error states fall back to the stylized well — never a white brick).
 */
@Composable
fun PosterImageOrFallback(
    posterUrl: String,
    showTitle: String,
    modifier: Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    fallbackIcon: ImageVector = Icons.Rounded.PlayArrow,
    showFallbackTitle: Boolean = true
) {
    if (posterUrl.isNotBlank()) {
        SubcomposeAsyncImage(
            model = posterUrl,
            contentDescription = showTitle,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape),
            loading = {
                PosterFallbackWell(
                    modifier = Modifier.fillMaxSize(),
                    shape = shape,
                    icon = fallbackIcon,
                    title = if (showFallbackTitle) showTitle else null
                )
            },
            error = {
                PosterFallbackWell(
                    modifier = Modifier.fillMaxSize(),
                    shape = shape,
                    icon = fallbackIcon,
                    title = if (showFallbackTitle) showTitle else null
                )
            }
        )
    } else {
        PosterFallbackWell(
            modifier = modifier,
            shape = shape,
            icon = fallbackIcon,
            title = if (showFallbackTitle) showTitle else null
        )
    }
}
