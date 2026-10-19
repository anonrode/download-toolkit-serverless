package com.anonrode.downloader.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.anonrode.downloader.ui.theme.*
import com.anonrode.downloader.ui.util.pressScale
import com.anonrode.downloader.ui.util.tick

/**
 * The one selection language for option bars on sheets: a hairline bar of
 * cells where the chosen cell fills AccentPrimary with BackgroundDark ink —
 * the same selected-state the sort bars and every other control in the app
 * use. Cells press-scale at 0.97 (large shared surface, subtler than a lone
 * button's 0.96).
 */
@Composable
fun SegmentBar(
    modifier: Modifier = Modifier,
    // v5 Downloads sort rail (notebook §3 "Sort & Segmented Bars"): the
    // canonical container insets its cells by 3dp. Drawer/sheet bars keep
    // their historical 4dp (Spacing.xs) via the default.
    innerPadding: Dp = Spacing.xs,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(SurfaceCard)
            .border(1.dp, BorderHairline, RoundedCornerShape(Radius.md))
            .padding(innerPadding),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        content = content
    )
}

@Composable
fun SegmentCell(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    // Canon (notebook §2): the Downloads sort bar is EXACTLY 32dp tall;
    // drawer/sheet cells keep their 42dp look via the default.
    cellHeight: Dp = 42.dp
) {
    val src = remember { MutableInteractionSource() }
    // Selection tick (Haptics verb): every cell tap acknowledges.
    val view = LocalView.current
    // Content Swap: the selection fill glides and the ink follows it, so the
    // label never flashes dark ink on an unfilled cell mid-swap.
    val fill by animateColorAsState(
        targetValue = if (selected) AccentPrimary else Color.Transparent,
        animationSpec = tween(Motion.DurationFast),
        label = "segmentFill"
    )
    val ink by animateColorAsState(
        targetValue = if (selected) BackgroundDark else TextSecondary,
        animationSpec = tween(Motion.DurationFast),
        label = "segmentInk"
    )
    Row(
        modifier = modifier
            .height(cellHeight)
            .pressScale(src, Motion.PressScaleLarge)
            .clip(RoundedCornerShape(Radius.sm))
            .background(fill)
            .clickable(
                interactionSource = src,
                indication = null,
                onClick = {
                    view.tick()
                    onClick()
                }
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(Spacing.xs))
        }
        Text(
            text = label,
            fontSize = Type.caption.fontSize,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = ink
        )
    }
}
