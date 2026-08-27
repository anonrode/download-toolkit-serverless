package com.anonrode.downloader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anonrode.downloader.ui.theme.AccentViolet
import com.anonrode.downloader.ui.theme.BorderHairline
import com.anonrode.downloader.ui.theme.Radius
import com.anonrode.downloader.ui.theme.Spacing
import com.anonrode.downloader.ui.theme.SurfaceCard
import com.anonrode.downloader.ui.theme.TextSecondary

/** Equal-width quality selector chips shared by the social downloader and
 *  quick-share sheets, so both entry points offer the same 48dp targets,
 *  corner radius and selection treatment (violet = selected state). */
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
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(
                        if (isSelected) AccentViolet else SurfaceCard,
                        RoundedCornerShape(Radius.md)
                    )
                    .border(
                        width = 1.dp,
                        color = if (isSelected) AccentViolet else BorderHairline,
                        shape = RoundedCornerShape(Radius.md)
                    )
                    .clickable { onSelect(option) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = option,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) Color.White else TextSecondary
                )
            }
        }
    }
}
