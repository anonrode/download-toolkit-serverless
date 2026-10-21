package com.anonrode.downloader.pipeline

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RC-4 (2026-10-21): per-site 451 legal-block search ledger. nepu.gd answers
 * 451 "Unavailable For Legal Reasons" for specific title keywords while
 * generic words pass (reproduced live from the workstation). The ledger makes
 * the blocked (site, query) pair skip its own fan-out for a TTL — site-scoped
 * (other providers keep serving the title), in-memory (never persisted),
 * self-healing (the upstream block list is dynamic), and recordable ONLY from
 * genuine HTTP 451 evidence so transport noise can never silence a provider.
 */
class SearchBlockLedgerTest {

    @Before
    fun reset() = SearchBlockLedger.clear()

    @After
    fun cleanup() = SearchBlockLedger.clear()

    @Test
    fun `451 detection matches the real failure shapes and nothing else`() {
        // The exact HttpClient failure text from the activity logs.
        assertTrue(SearchBlockLedger.isLegalBlockMessage("HTTP 451 for https://nepu.gd/api/search?q=servamp"))
        assertTrue(SearchBlockLedger.isLegalBlockMessage("http 451 for https://nepu.gd/api/search"))
        assertTrue(SearchBlockLedger.isLegalBlockMessage("timeout of 15002ms… HTTP 451"))
        // Transport/other failures must never plant a block.
        assertFalse(SearchBlockLedger.isLegalBlockMessage("SocketTimeoutException: Read timed out after 15002ms"))
        assertFalse(SearchBlockLedger.isLegalBlockMessage("HTTP 403 for https://fetch.nexabloom.top/anime/x/master.m3u8"))
        assertFalse(SearchBlockLedger.isLegalBlockMessage("HTTP 429 for https://vidsrc.buzz/embed/tv/67710/1/1"))
        assertFalse(SearchBlockLedger.isLegalBlockMessage(null))
        assertFalse(SearchBlockLedger.isLegalBlockMessage(""))
        // A number that merely contains "451" digits is not a legal block.
        assertFalse(SearchBlockLedger.isLegalBlockMessage("HTTP 4510 unsupported"))
        assertFalse(SearchBlockLedger.isLegalBlockMessage("got 24512 bytes"))
    }

    @Test
    fun `block is site-scoped and query-scoped`() {
        SearchBlockLedger.recordBlocked("nepu", "servamp")
        // Same site + query → blocked.
        assertTrue(SearchBlockLedger.isBlocked("nepu", "servamp"))
        assertTrue(SearchBlockLedger.isBlocked("NEPU", " SERVAMP ")) // normalization is case+trim
        // Other providers keep answering the same query.
        assertFalse(SearchBlockLedger.isBlocked("anitaku", "servamp"))
        // Other queries still pass on the blocked site.
        assertFalse(SearchBlockLedger.isBlocked("nepu", "fight"))
        assertFalse(SearchBlockLedger.isBlocked("nepu", "servamp second season"))
    }

    @Test
    fun `blank sites and queries are never recorded`() {
        SearchBlockLedger.recordBlocked("", "servamp")
        SearchBlockLedger.recordBlocked("nepu", "")
        SearchBlockLedger.recordBlocked("nepu", "   ")
        assertFalse(SearchBlockLedger.isBlocked("", "servamp"))
        assertFalse(SearchBlockLedger.isBlocked("nepu", ""))
        assertFalse(SearchBlockLedger.isBlocked("nepu", "servamp"))
    }
}
