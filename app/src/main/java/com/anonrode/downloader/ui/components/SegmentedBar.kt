package com.anonrode.downloader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anonrode.downloader.ui.theme.*
import com.anonrode.downloader.ui.util.pressScale

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
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(SurfaceCard)
            .border(1.dp, BorderHairline, RoundedCornerShape(Radius.md))
            .padding(Spacing.xs),
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
    icon: ImageVector? = null
) {
    val src = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .height(42.dp)
            .pressScale(src, 0.97f)
            .clip(RoundedCornerShape(Radius.sm))
            .background(if (selected) AccentPrimary else Color.Transparent)
            .clickable(
                interactionSource = src,
                indication = null,
                onClick = onClick
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) BackgroundDark else TextSecondary,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(Spacing.xs))
        }
        Text(
            text = label,
            fontSize = Type.caption.fontSize,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) BackgroundDark else TextSecondary
        )
    }
}
