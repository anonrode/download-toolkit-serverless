package com.anonrode.downloader.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** OTA site kill-switch must remove the Home chip, not just block downloads. */
class HomeSiteFiltersTest {

    @Test
    fun disabledSitesDisappearFromTheChipRow() {
        val disabled = setOf("nepu", "asianc", "anitaku")
        val filters = homeSiteFilters { it !in disabled }
        val ids = filters.map { it.first }
        assertTrue(ids.containsAll(listOf("all", "torrents")))
        assertFalse(ids.contains("nepu"))
        assertFalse(ids.contains("asianc"))
        assertFalse(ids.contains("anitaku"))
        // The other providers stay.
        assertTrue(ids.containsAll(listOf("nkiri", "9jarocks", "dramakey", "pluto")))
        assertEquals("All Sites", filters.first().second)
    }

    @Test
    fun malformedPayloadFailOpenKeepsEveryChip() {
        val filters = homeSiteFilters { true }
        assertEquals(12, filters.size)
    }
}
