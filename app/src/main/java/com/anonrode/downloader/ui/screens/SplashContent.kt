package com.anonrode.downloader.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anonrode.downloader.ui.theme.AccentPrimary
import com.anonrode.downloader.ui.theme.BackgroundDark
import com.anonrode.downloader.ui.theme.SurfaceElevated
import com.anonrode.downloader.ui.theme.TextPrimary
import com.anonrode.downloader.ui.theme.TextSecondary

/**
 * A designed Compose splash rendered as the FIRST screen.
 *
 * The system SplashScreen API dismisses the moment the first frame is drawn, so
 * on a fast device it's never perceived -- which is why "the splash doesn't show
 * at startup." This composable is held for a short fixed beat by MainActivity
 * (a plain delay), guaranteeing the brand is actually seen. Pure black to match
 * the AMOLED theme; the wordmark fades in so it doesn't pop.
 */
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
            .background(BackgroundDark),
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
                    .clip(RoundedCornerShape(18.dp))
                    .background(SurfaceElevated),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.ArrowDownward,
                    contentDescription = null,
                    tint = AccentPrimary,
                    modifier = Modifier.size(34.dp)
                )
            }

            Text(
                text = "ANONRODE",
                color = TextPrimary,
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 3.sp,
                modifier = Modifier.padding(top = 20.dp)
            )
            Text(
                text = "100% Serverless Downloader",
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        LinearProgressIndicator(
            color = AccentPrimary,
            trackColor = SurfaceElevated,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 64.dp)
                .height(3.dp)
                .size(width = 120.dp, height = 3.dp)
        )
    }
}
