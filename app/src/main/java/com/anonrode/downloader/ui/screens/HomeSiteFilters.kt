package com.anonrode.downloader.ui.screens

/**
 * Home site-chip list (OTA `contentPolicy.disabledSites`).
 *
 * Extracted from HomeScreen so the kill-switch contract is JVM-testable:
 * disabling a site must remove its chip from the row, while "All Sites" and
 * "Torrents (TPB)" always stay. The caller supplies the enabled predicate so
 * production reads DynamicRulesManager/SiteGate and tests can pass a fake.
 */
internal fun homeSiteFilters(isEnabled: (String) -> Boolean): List<Pair<String, String>> =
    listOf("all" to "All Sites", "torrents" to "Torrents (TPB)") +
        listOf(
            "nkiri" to "NKiri",
            "9jarocks" to "9jaRocks",
            "asianc" to "AsianC",
            "dramakey" to "DramaKey",
            "anitaku" to "Anitaku Anime",
            "pluto" to "Pluto Movies",
            "nepu" to "Nepu HD",
            "naijavault" to "NaijaVault",
            "naijaprey" to "NaijaPrey",
            "dramarain" to "DramaRain"
        ).filter { isEnabled(it.first) }
