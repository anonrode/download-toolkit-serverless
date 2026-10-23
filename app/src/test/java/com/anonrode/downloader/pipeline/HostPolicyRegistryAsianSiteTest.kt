package com.anonrode.downloader.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression for the 10-22 anime failover misclassification. */
class HostPolicyRegistryAsianSiteTest {

    @Test
    fun animeSitesAreAsian() {
        assertTrue(HostPolicyRegistry.isAsianSite("anitaku"))
        assertTrue(HostPolicyRegistry.isAsianSite("ANITAKU"))
        assertTrue(HostPolicyRegistry.isAsianSite("gogoanime"))
        assertTrue(HostPolicyRegistry.isAsianSite("asianc"))
        assertTrue(HostPolicyRegistry.isAsianSite("dramarain"))
        assertTrue(HostPolicyRegistry.isAsianSite("dramakey"))
        assertTrue(HostPolicyRegistry.isAsianSite("nepu"))
    }

    @Test
    fun westernSitesStayWestern() {
        assertFalse(HostPolicyRegistry.isAsianSite("nkiri"))
        assertFalse(HostPolicyRegistry.isAsianSite("9jarocks"))
        assertFalse(HostPolicyRegistry.isAsianSite("naijavault"))
        assertFalse(HostPolicyRegistry.isAsianSite("naijaprey"))
        assertFalse(HostPolicyRegistry.isAsianSite(""))
    }
}
