package com.anonrode.downloader.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the CONTINUE & MEDIA visibility contract (directive §2.2): the shelf
 * only renders under All and Done, and never when empty.
 */
class DownloadShelfPolicyTest {

    @Test
    fun shelf_visible_under_all_and_done_when_content_exists() {
        assertTrue(DownloadsShelfPolicy.isShelfVisible("all", true, false))
        assertTrue(DownloadsShelfPolicy.isShelfVisible("done", false, true))
    }

    @Test
    fun shelf_hidden_under_transfer_filters() {
        assertFalse(DownloadsShelfPolicy.isShelfVisible("active", true, true))
        assertFalse(DownloadsShelfPolicy.isShelfVisible("paused", true, true))
        assertFalse(DownloadsShelfPolicy.isShelfVisible("failed", true, true))
    }

    @Test
    fun shelf_hidden_when_both_lists_are_empty() {
        assertFalse(DownloadsShelfPolicy.isShelfVisible("all", false, false))
        assertFalse(DownloadsShelfPolicy.isShelfVisible("done", false, false))
    }
}
