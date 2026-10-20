package com.anonrode.downloader.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OTA site kill-switch consumption (rev-4 `contentPolicy.disabledSites`).
 *
 * Default is enabled: an absent/old/malformed payload leaves every site
 * listed. The pure filter is what Home chips and both feeds use, so a
 * disabled site can never be fetched or rendered.
 */
class SiteGateTest {

    private val all = listOf("nkiri", "9jarocks", "naijavault", "naijaprey")

    @Test
    fun absentPayloadKeepsEverySite() {
        assertEquals(all, SiteGate.filterEnabled(all) { true })
    }

    @Test
    fun disabledSiteIsRemovedFromTheFanOut() {
        val enabled = setOf("nkiri", "naijavault")
        assertEquals(listOf("nkiri", "naijavault"), SiteGate.filterEnabled(all) { it in enabled })
    }

    @Test
    fun allDisabledYieldsEmptyListNotAFallback() {
        assertTrue(SiteGate.filterEnabled(all) { false }.isEmpty())
    }
}
