package com.anonrode.downloader.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.anonrode.downloader.BuildConfig
import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.update.UpdateManager
import com.anonrode.downloader.util.RemoteLogger
import com.anonrode.downloader.util.UpdateChecker
import com.anonrode.downloader.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Root-level update prompt + force gate (v3.3.0 phase 2; plan §4.2/§4.5).
 *
 * Renders ONLY from [UpdateManager.state] (evaluated against the signed
 * payload; every brick-safety rail lives in the manager — a stale or failed
 * evaluation can never produce FORCED):
 *  - AVAILABLE → dismissible soft card ("Later" = snooze for
 *    force.snoozeHours, default 24h);
 *  - FORCED    → non-dismissible dialog (no back / no outside tap) with
 *    "Update now" (in-app download →  SHA-256-verified → installer) and a
 *    release-page fallback. ESCAPE HATCH: long-press the title 5× → a 24h
 *    bypass + an id-only `gate_bypass` telemetry record, so a bad gate can
 *    never truly brick anyone.
 *
 * The gate blocks UI ONLY — the engine keeps downloading underneath. All
 * telemetry is stage/id-only through the existing encrypted sink.
 */
@Composable
fun UpdateGate() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by UpdateManager.state.collectAsState()
    val message by UpdateManager.message.collectAsState()
    val prefs = remember {
        context.getSharedPreferences("downloader_settings", Context.MODE_PRIVATE)
    }
    // F8 (QA-V3): plain reads each composition — a payload that lands after
    // the first frame (cold start) must be visible without a restart.
    val policy = DynamicRulesManager.getUpdatePolicy()

    var snoozeUntil by remember { mutableStateOf(prefs.getLong("update_snooze_until_ms", 0L)) }
    val snoozeResetTick by UpdateManager.snoozeResetTick.collectAsState()
    LaunchedEffect(snoozeResetTick) {
        if (snoozeResetTick > 0L) {
            snoozeUntil = prefs.getLong("update_snooze_until_ms", 0L)
        }
    }
    var bypassUntil by remember { mutableStateOf(prefs.getLong("update_gate_bypass_ms", 0L)) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var longPresses by remember { mutableStateOf(0) }

    // gate_shown: once per transition into a visible state (id-only).
    var lastShown by remember { mutableStateOf<UpdateManager.State?>(null) }
    LaunchedEffect(state) {
        if (state != lastShown) {
            if (state == UpdateManager.State.AVAILABLE || state == UpdateManager.State.FORCED) {
                try {
                    RemoteLogger.report(
                        type = "gate_shown",
                        message = "update gate shown",
                        context = mapOf(
                            "mode" to if (state == UpdateManager.State.FORCED) "hard" else "soft",
                            "version" to BuildConfig.VERSION_CODE,
                            "rev" to DynamicRulesManager.getActiveRev()
                        )
                    )
                } catch (_: Exception) {
                }
            }
            lastShown = state
        }
    }

    val now = System.currentTimeMillis()
    val isForced = state == UpdateManager.State.FORCED
    // QA-V3 F2: force.mode selects PRESENTATION — "hard" is the locked
    // dialog, "soft" (the default) is the dismissible card. Before this
    // fix, every force config rendered the locked dialog while the ops doc
    // promised "soft = dismissible".
    val hardGate = isForced && (policy?.force?.mode?.lowercase() ?: "soft") == "hard"
    val visible = when (state) {
        // The escape hatch suppresses the HARD gate for 24h, then it returns
        // (a nudge, never a brick). A SOFT force behaves like AVAILABLE
        // (snooze-able) per the payload's force.mode semantics.
        UpdateManager.State.FORCED ->
            if (hardGate) now >= bypassUntil else now >= snoozeUntil
        UpdateManager.State.AVAILABLE -> now >= snoozeUntil
        else -> false
    }
    if (!visible) return

    val body = message.ifBlank {
        if (isForced) "This version is no longer supported. Please update to continue."
        else "A new version is available."
    }

    val startDownload: () -> Unit = {
        if (!busy) {
            busy = true
            error = null
            progress = "Starting…"
            scope.launch {
                val file = UpdateManager.downloadApk(context) { downloaded, total ->
                    progress = if (total > 0L) "${downloaded * 100 / total}%"
                    else "${downloaded / 1_048_576} MB"
                }
                if (file != null) {
                    val launched = UpdateManager.installApk(context, file)
                    if (!launched) error = "Couldn't open the installer — use the release page."
                } else {
                    error = "Download failed or the file didn't verify — use the release page."
                }
                busy = false
            }
        }
    }
    val escape: () -> Unit = {
        bypassUntil = now + 24L * 60 * 60 * 1000
        prefs.edit().putLong("update_gate_bypass_ms", bypassUntil).apply()
        try {
            RemoteLogger.report(
                type = "gate_bypass",
                message = "user bypassed the update gate",
                context = mapOf(
                    "version" to BuildConfig.VERSION_CODE,
                    "rev" to DynamicRulesManager.getActiveRev()
                )
            )
        } catch (_: Exception) {
        }
    }
    val snooze: () -> Unit = {
        val hours = policy?.force?.snoozeHours?.takeIf { it > 0 } ?: 24
        snoozeUntil = now + hours * 60L * 60 * 1000
        prefs.edit().putLong("update_snooze_until_ms", snoozeUntil).apply()
    }

    if (hardGate) {
        AlertDialog(
            onDismissRequest = { /* hard gate: outside/back dismiss disabled */ },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            ),
            containerColor = SurfaceCard,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = {
                Text(
                    text = "Update required",
                    fontWeight = FontWeight.Bold,
                    fontSize = Type.itemTitle.fontSize,
                    // Escape hatch: five long-presses arm a 24h bypass.
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures(onLongPress = {
                            longPresses += 1
                            if (longPresses >= 5) escape()
                        })
                    }
                )
            },
            text = {
                Column {
                    Text(body, fontSize = Type.body.fontSize)
                    if (busy || progress.isNotBlank()) {
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        Text(
                            text = if (busy) "Downloading… $progress" else progress,
                            color = TextMuted,
                            fontSize = Type.caption.fontSize
                        )
                    }
                    error?.let {
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        Text(it, color = StatusWarning, fontSize = Type.caption.fontSize)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = startDownload, enabled = !busy) {
                    Text(
                        text = if (busy) "Downloading…" else "Update now",
                        color = AccentPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { UpdateChecker.openInBrowser(context, UpdateChecker.RELEASES_PAGE) }) {
                    Text("Release page", color = TextSecondary)
                }
            }
        )
    } else {
        // Soft prompt — non-modal bottom card (touches outside pass through).
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.md)
                    .clip(RoundedCornerShape(Radius.lg))
                    .background(SurfaceCard)
                    .border(1.dp, BorderHairline, RoundedCornerShape(Radius.lg))
                    .navigationBarsPadding()
                    .padding(Spacing.md)
            ) {
                Text(
                    text = if (isForced) "Update required" else "Update available",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = Type.rowTitle.fontSize
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = body, color = TextSecondary, fontSize = Type.caption.fontSize)
                if (busy || progress.isNotBlank()) {
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        text = if (busy) "Downloading… $progress" else progress,
                        color = TextMuted,
                        fontSize = Type.caption.fontSize
                    )
                }
                error?.let {
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(it, color = StatusWarning, fontSize = Type.caption.fontSize)
                }
                Spacer(modifier = Modifier.height(Spacing.xs))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = snooze) {
                        Text("Later", color = TextSecondary, fontWeight = FontWeight.SemiBold)
                    }
                    TextButton(onClick = startDownload, enabled = !busy) {
                        Text(
                            text = if (busy) "Downloading…" else "Update now",
                            color = AccentPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
