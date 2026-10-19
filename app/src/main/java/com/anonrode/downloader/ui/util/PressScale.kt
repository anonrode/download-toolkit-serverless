package com.anonrode.downloader.ui.util

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import com.anonrode.downloader.ui.theme.Motion

/** The app's one press personality (better-ui discipline): a button reads as
 *  physical when its surface shrinks a hair under the finger. Scale 0.96
 *  exactly — smaller reads exaggerated on big controls — on the shared
 *  one-motion curve, and only for as long as the finger is down.
 *
 *  Usage: hold the source yourself so BOTH this modifier and the clickable
 *  see the same presses:
 *
 *      val src = remember { MutableInteractionSource() }
 *      Modifier
 *          .pressScale(src)
 *          .clickable(interactionSource = src, indication = ripple(), onClick = ...)
 */
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = Motion.PressScaleDefault
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        // The one curve nothing in this app is allowed to argue with.
        animationSpec = tween(durationMillis = Motion.DurationPress, easing = Motion.StandardEasing),
        label = "pressScale"
    )
    // Read inside the layer block: the shrink animates on the draw phase,
    // never recomposing the host composable.
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
