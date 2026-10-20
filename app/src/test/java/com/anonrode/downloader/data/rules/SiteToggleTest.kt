package com.anonrode.downloader.data.rules

import com.anonrode.downloader.providers.ProviderRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OTA per-site kill switch (`contentPolicy.disabledSites`, rev 4).
 *
 * Semantics under test (the contract the operator relies on for a no-APK
 * toggle):
 *  - absent section / absent field / blank array  -> every site enabled;
 *  - explicit string entries                      -> those sites disabled;
 *  - malformed values (non-array, non-string)     -> fail OPEN (all enabled),
 *    so a bad payload can never hide the catalogue.
 * Enforcement is registry-side: search fan-out/providers list exclude a
 * disabled site, while getProvider stays available for in-flight downloads.
 */
class SiteToggleTest {

    private fun apply(json: String) {
        assertTrue("payload must parse: $json", DynamicRulesManager.parseRulesJson(json))
    }

    @Test
    fun absentPayloadSectionKeepsEverySiteEnabled() {
        apply("""{"version":"toggle.absent"}""")
        assertTrue(DynamicRulesManager.isSiteEnabled("nepu"))
        assertTrue(DynamicRulesManager.isSiteEnabled("9jarocks"))
        assertTrue(DynamicRulesManager.isSiteEnabled("anitaku"))
        assertTrue(DynamicRulesManager.isSiteEnabled("asianc"))
        assertTrue(DynamicRulesManager.getDisabledSites().isEmpty())
    }

    @Test
    fun absentDisabledSitesFieldKeepsEverySiteEnabled() {
        apply("""{"version":"toggle.nofield","contentPolicy":{"socketCaps":{"vidsrc.buzz":4}}}""")
        assertTrue(DynamicRulesManager.isSiteEnabled("nepu"))
        assertTrue(DynamicRulesManager.getDisabledSites().isEmpty())
    }

    @Test
    fun explicitDisabledSitesTurnOffExactlyThoseSites() {
        apply(
            """{"version":"toggle.explicit","contentPolicy":{"disabledSites":
               ["nepu","9JAROCKS"," anitaku ","asianc"]}}"""
        )
        assertFalse(DynamicRulesManager.isSiteEnabled("nepu"))
        assertFalse(DynamicRulesManager.isSiteEnabled("9jarocks"))
        assertFalse(DynamicRulesManager.isSiteEnabled("ANITAKU"))
        assertFalse(DynamicRulesManager.isSiteEnabled("asianc"))
        assertTrue(DynamicRulesManager.isSiteEnabled("nkiri"))
        assertEquals(listOf("nepu", "9jarocks", "anitaku", "asianc"), DynamicRulesManager.getDisabledSites())
    }

    @Test
    fun malformedValuesFailOpen() {
        apply("""{"version":"toggle.bad1","contentPolicy":{"disabledSites":"nepu"}}""")
        assertTrue(DynamicRulesManager.isSiteEnabled("nepu"))

        apply("""{"version":"toggle.bad2","contentPolicy":{"disabledSites":{"nepu":true}}}""")
        assertTrue(DynamicRulesManager.isSiteEnabled("nepu"))

        apply("""{"version":"toggle.bad3","contentPolicy":{"disabledSites":[1,null,false,{"x":1},""]}}""")
        assertTrue(DynamicRulesManager.isSiteEnabled("nepu"))
        assertTrue(DynamicRulesManager.getDisabledSites().isEmpty())
    }

    @Test
    fun mixedArrayUsesOnlyUsableStrings() {
        apply("""{"version":"toggle.mixed","contentPolicy":{"disabledSites":[42,null,"nepu",""]}}""")
        assertFalse(DynamicRulesManager.isSiteEnabled("nepu"))
        assertTrue(DynamicRulesManager.isSiteEnabled("9jarocks"))
        assertEquals(listOf("nepu"), DynamicRulesManager.getDisabledSites())
    }

    @Test
    fun snapshotReflectsTheSwitch() {
        apply("""{"version":"toggle.snapshot","contentPolicy":{"disabledSites":["asianc"]}}""")
        val snap = DynamicRulesManager.siteEnabledSnapshot(listOf("nepu", "asianc", "nkiri"))
        assertEquals(mapOf("nepu" to true, "asianc" to false, "nkiri" to true), snap)
    }

    @Test
    fun registryExcludesDisabledSitesFromNewWorkButStillResolvesInFlight() {
        apply("""{"version":"toggle.registry","contentPolicy":{"disabledSites":["nepu"]}}""")
        assertFalse(ProviderRegistry.enabledProviderNames().contains("nepu"))
        assertTrue(ProviderRegistry.enabledProviderNames().contains("nkiri"))
        // In-flight downloads still need their provider object.
        assertNotNull(ProviderRegistry.getProvider("nepu"))
        // Feed/search card filter helper drops the disabled site's cards.
        val cards = listOf(
            com.anonrode.downloader.data.models.ShowCard("A", "https://nepu.gd/watch/tv/1", site = "nepu"),
            com.anonrode.downloader.data.models.ShowCard("B", "https://nkiri.top/x", site = "nkiri")
        )
        assertEquals(listOf("nkiri"), ProviderRegistry.filterEnabledCards(cards).map { it.site })
    }
}
