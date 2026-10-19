package com.anonrode.downloader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anonrode.downloader.update.AnnouncementManager
import com.anonrode.downloader.update.UpdateManager
import com.anonrode.downloader.util.UpdateChecker
import com.anonrode.downloader.ui.theme.*

/**
 * Root-level host for OTA announcements (v3.3.0 phase 2; plan §4.2/§4.4).
 *
 * Renders [AnnouncementManager.current] in one of three payload-controlled
 * modes — "dialog" (M3 alert), "sheet" (bottom card over a tap-to-dismiss
 * scrim) or "banner" (top bar) — with an optional action:
 *  - "none"   → the dismiss/action button just closes it;
 *  - "url"    → opens the payload URL in the browser, then closes;
 *  - "update" → closes and promotes the update prompt/gate via
 *               [UpdateManager.evaluate].
 *
 * Eligibility + once-per-id/rev bookkeeping live in [AnnouncementManager]
 * (the shown-marker is persisted at pick time, so nothing here replays it).
 * The banner mode is deliberately non-modal: a full-screen Box exists only
 * for alignment, and touches outside the banner fall through to the app.
 */
@Composable
fun AnnouncementHost() {
    val context = LocalContext.current
    val active by AnnouncementManager.current.collectAsState()
    val ann = active ?: return

    val dismiss: () -> Unit = { AnnouncementManager.dismiss(context, ann.id) }
    val act: () -> Unit = {
        when (ann.action.lowercase()) {
            "url" -> {
                if (ann.actionUrl.isNotBlank()) UpdateChecker.openInBrowser(context, ann.actionUrl)
                dismiss()
            }
            "update" -> {
                dismiss()
                // Promotes the state so UpdateGate renders the prompt/gate.
                UpdateManager.evaluate(context)
            }
            else -> dismiss()
        }
    }
    val hasAction = ann.action != "none" && ann.actionLabel.isNotBlank()

    when (ann.mode.lowercase()) {
        "banner" -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                        .clip(RoundedCornerShape(Radius.md))
                        .background(SurfaceCard)
                        .border(1.dp, BorderHairline, RoundedCornerShape(Radius.md))
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        if (ann.title.isNotBlank()) {
                            Text(
                                text = ann.title,
                                color = TextPrimary,
                                fontSize = Type.rowTitle.fontSize,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (ann.body.isNotBlank()) {
                            Text(
                                text = ann.body,
                                color = TextSecondary,
                                fontSize = Type.caption.fontSize,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (hasAction) {
                            Text(
                                text = ann.actionLabel,
                                color = AccentPrimary,
                                fontSize = Type.caption.fontSize,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .clickable { act() }
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .clickable { dismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "✕",
                            color = TextMuted,
                            fontSize = Type.caption.fontSize,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        "sheet" -> {
            val ui = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(interactionSource = ui, indication = null) { dismiss() },
                contentAlignment = Alignment.BottomCenter
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Inner clickable swallows taps so only the scrim dismisses.
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                        .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                        .background(BackgroundDark)
                        .navigationBarsPadding()
                        .padding(Spacing.lg)
                ) {
                    if (ann.title.isNotBlank()) {
                        Text(
                            text = ann.title,
                            color = TextPrimary,
                            fontSize = Type.itemTitle.fontSize,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(Spacing.sm))
                    }
                    if (ann.body.isNotBlank()) {
                        Text(
                            text = ann.body,
                            color = TextSecondary,
                            fontSize = Type.body.fontSize
                        )
                        Spacer(modifier = Modifier.height(Spacing.md))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = dismiss) {
                            Text("Dismiss", color = TextSecondary, fontWeight = FontWeight.SemiBold)
                        }
                        if (hasAction) {
                            TextButton(onClick = act) {
                                Text(ann.actionLabel, color = AccentPrimary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        else -> {
            AlertDialog(
                onDismissRequest = { dismiss() },
                containerColor = SurfaceCard,
                titleContentColor = TextPrimary,
                textContentColor = TextSecondary,
                title = {
                    if (ann.title.isNotBlank()) {
                        Text(ann.title, fontWeight = FontWeight.SemiBold, fontSize = Type.itemTitle.fontSize)
                    }
                },
                text = {
                    if (ann.body.isNotBlank()) {
                        Text(ann.body, fontSize = Type.body.fontSize)
                    }
                },
                confirmButton = {
                    if (hasAction) {
                        TextButton(onClick = act) {
                            Text(ann.actionLabel, color = AccentPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = dismiss) {
                        Text(if (hasAction) "Later" else "Dismiss", color = TextSecondary)
                    }
                }
            )
        }
    }
}
