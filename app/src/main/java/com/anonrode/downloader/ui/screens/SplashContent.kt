package com.anonrode.downloader.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anonrode.downloader.ui.theme.Radius
import com.anonrode.downloader.ui.theme.SplashBackground
import com.anonrode.downloader.ui.theme.SplashElevated
import com.anonrode.downloader.ui.theme.SplashMuted
import com.anonrode.downloader.ui.theme.SplashOnBackground
import com.anonrode.downloader.ui.theme.Spacing

@Composable
fun SplashContent() {
    var visible by remember { mutableStateOf(false) }
    val fade by animateFloatAsState(
        targetValue = if (visible) 1f else 0.4f,
        animationSpec = tween(400),
        label = "splashFade"
    )

    LaunchedEffect(Unit) {
        visible = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SplashBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.alpha(fade)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(Radius.lg))
                    .background(SplashElevated),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.ArrowDownward,
                    contentDescription = null,
                    // SplashOnBackground, not AccentPrimary: the accent is
                    // theme-aware (#0F172A in light) and would vanish on the
                    // pinned-black splash.
                    tint = SplashOnBackground,
                    modifier = Modifier.size(34.dp)
                )
            }

            Text(
                text = "ANONRODE",
                color = SplashOnBackground,
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 3.sp,
                modifier = Modifier.padding(top = Spacing.lg)
            )
            Text(
                text = "100% Serverless Downloader",
                color = SplashMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = Spacing.xs)
            )
        }

        LinearProgressIndicator(
            color = SplashOnBackground,
            trackColor = SplashElevated,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // system nav clearance — the fixed 48dp lift only
                // *touches* a classic 3-button bar; on gesture-nav devices
                // the pill overlapped the indicator
                .navigationBarsPadding()
                .padding(bottom = Spacing.xxxl)
                .height(3.dp)
                .size(width = 120.dp, height = 3.dp)
        )
    }
}
