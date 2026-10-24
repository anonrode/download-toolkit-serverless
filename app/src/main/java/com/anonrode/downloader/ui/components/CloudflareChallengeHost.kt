package com.anonrode.downloader.ui.components

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.anonrode.downloader.data.net.CloudflareSolver
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.ByteArrayInputStream

/**
 * Root in-window overlay host for Cloudflare Turnstile & bot-check challenges.
 *
 * Implements the 2-stage promotion without creating a separate Android Dialog window:
 *  - Stage 1 (0-4s): AndroidView(WebView) renders in the existing window at offset y=2000dp.
 *    Non-interactive JS challenges solve here with zero UI disruption.
 *  - Stage 2 (4s+): Animates in-window Surface up to y=0dp, presenting Turnstile checkbox.
 *
 * Prevents multi-window reparenting crashes (IllegalStateException) by keeping the
 * WebView continuously attached to the main window hierarchy.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CloudflareChallengeHost() {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> CloudflareSolver.isHostActive = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> CloudflareSolver.isHostActive = false
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val request by CloudflareSolver.challengeState.collectAsState()
    val activeRequest = request ?: return

    var showInteractive by remember(activeRequest) { mutableStateOf(false) }
    var webViewRef by remember(activeRequest) { mutableStateOf<WebView?>(null) }

    BackHandler(enabled = showInteractive) {
        CloudflareSolver.resolve(activeRequest, false)
    }

    val sheetOffsetY by animateDpAsState(
        targetValue = if (showInteractive) 0.dp else 2000.dp,
        animationSpec = tween(durationMillis = 300),
        label = "sheetOffsetY"
    )

    val scrimAlpha by animateFloatAsState(
        targetValue = if (showInteractive) 0.6f else 0.0f,
        animationSpec = tween(durationMillis = 300),
        label = "scrimAlpha"
    )

    DisposableEffect(activeRequest) {
        onDispose {
            webViewRef?.let { wv ->
                try {
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                    wv.clearHistory()
                    wv.removeAllViews()
                    wv.destroy()
                } catch (_: Exception) {}
            }
            webViewRef = null
        }
    }

    LaunchedEffect(activeRequest) {
        val cookieManager = CookieManager.getInstance()
        var elapsedMs = 0L
        while (isActive && !activeRequest.deferred.isCompleted) {
            delay(250)
            elapsedMs += 250
            if (elapsedMs >= 4000L && !showInteractive) {
                showInteractive = true
            }

            val cookieStr = try {
                cookieManager.getCookie(activeRequest.targetUrl)
            } catch (_: Exception) {
                null
            }

            val tokenPair = CloudflareSolver.extractToken(cookieStr)
            if (tokenPair != null) {
                val (tokenName, tokenValue) = tokenPair
                if (tokenValue != activeRequest.staleClearance) {
                    CloudflareSolver.resolve(
                        request = activeRequest,
                        success = true,
                        clearanceToken = tokenValue,
                        tokenName = tokenName
                    )
                    break
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Scrim backdrop when promoted to Stage 2
        if (showInteractive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(scrimAlpha)
                    .background(Color.Black)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        CloudflareSolver.resolve(activeRequest, false)
                    }
            )
        }

        // Single in-window Surface containing the active WebView
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = sheetOffsetY)
                    .heightIn(min = 460.dp, max = 560.dp),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = SurfaceCard,
                shadowElevation = 16.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    // Drag indicator handle
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(TextSecondary.copy(alpha = 0.4f))
                            .align(Alignment.CenterHorizontally)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Header row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Verification Required",
                                style = Type.itemTitle,
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Cloudflare verification for ${activeRequest.host}",
                                style = Type.caption,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = { CloudflareSolver.resolve(activeRequest, false) },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Cancel verification",
                                tint = TextSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Embedded WebView container
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(380.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.databaseEnabled = false
                                    settings.blockNetworkImage = true
                                    settings.userAgentString = HttpClient.DEFAULT_UA

                                    val cookieManager = CookieManager.getInstance()
                                    cookieManager.setAcceptCookie(true)
                                    cookieManager.setAcceptThirdPartyCookies(this, true)

                                    webViewClient = object : WebViewClient() {
                                        override fun shouldInterceptRequest(
                                            view: WebView?,
                                            request: WebResourceRequest?
                                        ): WebResourceResponse? {
                                            val uri = request?.url ?: return super.shouldInterceptRequest(view, request)
                                            if (!CloudflareSolver.shouldAllowSubresource(uri.toString(), activeRequest.host)) {
                                                return WebResourceResponse(
                                                    "text/plain",
                                                    "UTF-8",
                                                    204,
                                                    "No Content",
                                                    emptyMap(),
                                                    ByteArrayInputStream(ByteArray(0))
                                                )
                                            }
                                            return super.shouldInterceptRequest(view, request)
                                        }
                                    }
                                    if (!activeRequest.referer.isNullOrBlank()) {
                                        loadUrl(activeRequest.targetUrl, mapOf("Referer" to activeRequest.referer))
                                    } else {
                                        loadUrl(activeRequest.targetUrl)
                                    }
                                    webViewRef = this
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

/**
 * Skeleton Subresource Firewall rule evaluator.
 *
 * Blocks heavy non-critical assets (images, media, fonts, stylesheets on non-Cloudflare hosts)
 * returning HTTP 204 No Content, while whitelisting Cloudflare challenge engines.
 */
internal fun shouldBlockSubresource(uri: Uri): Boolean {
    return !CloudflareSolver.shouldAllowSubresource(uri.toString(), "")
}

internal fun shouldBlockSubresource(scheme: String, host: String, path: String): Boolean {
    val fullUrl = if (scheme.isBlank()) host else "$scheme://$host$path"
    return !CloudflareSolver.shouldAllowSubresource(fullUrl, host)
}
