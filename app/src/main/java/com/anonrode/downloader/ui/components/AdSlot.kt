package com.anonrode.downloader.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.util.RemoteLogger
import com.anonrode.downloader.util.UpdateChecker
import com.anonrode.downloader.ui.theme.*
import java.time.LocalDate

/**
 * House-ad slot (v3.3.0 phase 2; plan §4.2/§4.6).
 *
 * Reads the signed payload's `ads` section and renders a HOUSE card — the
 * maintainer's own text, which doubles as another arbitrary-message surface.
 * Deliberately the ONLY wired provider path for now: the AppLovin/Unity/AdMob
 * bridges (ads/Ads.kt) initialize inertly and their own ad views land later —
 * this slot renders nothing for those providers, which keeps the ad plane
 * zero-risk until a network is actually chosen.
 *
 * Frequency: at most `frequencyPerDay` impressions per calendar day for the
 * configured house ad, shared across placements (the key is
 * `ad_impressions_<yyyyMMdd>_<houseId>` — one daily budget, whichever slots
 * render it); the impression is recorded once per composition entry via
 * LaunchedEffect, re-reading the stored count at increment time so two slots
 * composing in the same frame cannot lose an update. Telemetry is id-only
 * (`ad_impression`).
 */
@Composable
fun AdSlot(placement: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // F8 (QA-V3): plain read each composition — a payload that lands after
    // the first frame (cold start) must be visible without a restart.
    val cfg = DynamicRulesManager.getAdsConfig() ?: return
    if (!cfg.enabled || cfg.provider != "house" || placement !in cfg.placements || cfg.frequencyPerDay <= 0) {
        return
    }
    val prefs = remember {
        context.getSharedPreferences("downloader_settings", Context.MODE_PRIVATE)
    }
    val day = remember {
        LocalDate.now().toString().replace("-", "")
    }
    val key = "ad_impressions_${day}_${cfg.houseId}"
    var dismissed by remember { mutableStateOf(false) }
    val shownToday = prefs.getInt(key, 0)
    if (dismissed || shownToday >= cfg.frequencyPerDay) return

    LaunchedEffect(key) {
        try {
            // Re-read at increment time: two slots composing in the same frame
            // would otherwise both write the same captured value (lost update).
            prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
        } catch (_: Exception) {
        }
        try {
            RemoteLogger.report(
                type = "ad_impression",
                message = "house ad shown",
                context = mapOf("id" to cfg.houseId, "placement" to placement)
            )
        } catch (_: Exception) {
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm)
            .clip(RoundedCornerShape(Radius.lg))
            .background(SurfaceCard)
            .border(1.dp, BorderHairline, RoundedCornerShape(Radius.lg))
            .padding(Spacing.md),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (cfg.houseTitle.isNotBlank()) {
                Text(
                    text = cfg.houseTitle,
                    color = TextPrimary,
                    fontSize = Type.rowTitle.fontSize,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (cfg.houseBody.isNotBlank()) {
                Text(
                    text = cfg.houseBody,
                    color = TextSecondary,
                    fontSize = Type.caption.fontSize
                )
            }
            if (cfg.houseActionLabel.isNotBlank() && cfg.houseActionUrl.isNotBlank()) {
                Text(
                    text = cfg.houseActionLabel,
                    color = AccentPrimary,
                    fontSize = Type.caption.fontSize,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clickable {
                            UpdateChecker.openInBrowser(context, cfg.houseActionUrl)
                        }
                )
            }
        }
        Text(
            text = "✕",
            color = TextMuted,
            fontSize = Type.caption.fontSize,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(start = Spacing.sm)
                .clickable { dismissed = true }
        )
    }
}
