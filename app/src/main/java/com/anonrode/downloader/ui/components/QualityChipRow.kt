package com.anonrode.downloader.ui.components

import com.anonrode.downloader.ui.theme.Type

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anonrode.downloader.ui.theme.AccentPrimary
import com.anonrode.downloader.ui.theme.BackgroundDark
import com.anonrode.downloader.ui.theme.BorderHairline
import com.anonrode.downloader.ui.theme.Motion
import com.anonrode.downloader.ui.theme.Radius
import com.anonrode.downloader.ui.theme.Spacing
import com.anonrode.downloader.ui.theme.SurfaceCard
import com.anonrode.downloader.ui.theme.TextSecondary
import com.anonrode.downloader.ui.util.pressScale
import com.anonrode.downloader.ui.util.tick

/** Equal-width quality selector chips shared by the social downloader and
 *  quick-share sheets, so both entry points offer the same 48dp targets,
 *  corner radius and selection treatment — the SAME accent-fill language as
 *  SegmentBar (accent fill + dark ink = selected). */
@Composable
fun QualityChipRow(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        options.forEach { option ->
            val isSelected = option.equals(selected, ignoreCase = true)
            // Press personality + selection tick — the SegmentCell language,
            // worn by the sibling chip row.
            val chipSrc = remember { MutableInteractionSource() }
            val hapticView = LocalView.current
            // Content Swap: fill, hairline and ink glide on the shared fast
            // tween — the selection language matches SegmentBar's glide.
            val fill by animateColorAsState(
                targetValue = if (isSelected) AccentPrimary else SurfaceCard,
                animationSpec = tween(Motion.DurationFast),
                label = "qualityChipFill"
            )
            val outline by animateColorAsState(
                targetValue = if (isSelected) AccentPrimary else BorderHairline,
                animationSpec = tween(Motion.DurationFast),
                label = "qualityChipOutline"
            )
            val ink by animateColorAsState(
                targetValue = if (isSelected) BackgroundDark else TextSecondary,
                animationSpec = tween(Motion.DurationFast),
                label = "qualityChipInk"
            )
            // selectable(), not clickable(): the accent fill + bold text are
            // VISUAL-only selection signals — TalkBack heard "button" for both
            // the selected and the unselected chip. RadioButton role + the
            // selected state now ride in the semantics.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .pressScale(chipSrc)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(
                        fill,
                        RoundedCornerShape(Radius.md)
                    )
                    .border(
                        width = 1.dp,
                        color = outline,
                        shape = RoundedCornerShape(Radius.md)
                    )
                    .selectable(
                        selected = isSelected,
                        interactionSource = chipSrc,
                        indication = null,
                        role = Role.RadioButton,
                        onClick = {
                            hapticView.tick()
                            onSelect(option)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = option,
                    fontSize = Type.body.fontSize,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = ink
                )
            }
        }
    }
}
