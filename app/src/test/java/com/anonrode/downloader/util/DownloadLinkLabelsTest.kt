package com.anonrode.downloader.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the mirror/part label rules behind the 2026-09-13 film-as-episodes
 * drawer bug (Rocks 'Safety' film) shared into every numbering fallback path.
 */
class DownloadLinkLabelsTest {

    @Test fun `server anchors label as Server N, case-insensitive`() {
        assertEquals("Server 1", DownloadLinkLabels.serverOrPart("DOWNLOAD VIDEO SERVER 1"))
        assertEquals("Server 2", DownloadLinkLabels.serverOrPart("Download video server 2"))
        assertEquals("Server 3", DownloadLinkLabels.serverOrPart(null, "  SERVER 3  "))
    }

    @Test fun `server wins over part in the same text`() {
        assertEquals("Server 2", DownloadLinkLabels.serverOrPart("Movie Part 1 Server 2"))
    }

    @Test fun `part anchors label as Part N`() {
        assertEquals("Part 1", DownloadLinkLabels.serverOrPart("safety part 1 720p"))
        assertEquals("Part 12", DownloadLinkLabels.serverOrPart("FILE PART 12"))
    }

    @Test fun `apartment and party do NOT match part`() {
        assertNull(DownloadLinkLabels.serverOrPart("The Apartment 2011"))
        assertNull(DownloadLinkLabels.serverOrPart("Christmas Party 1"))
    }

    @Test fun `plain episode and generic texts stay null (numbering path wins)`() {
        assertNull(DownloadLinkLabels.serverOrPart("Episode 5"))
        assertNull(DownloadLinkLabels.serverOrPart("Download"))
        assertNull(DownloadLinkLabels.serverOrPart("WATCH & DOWNLOAD MOVIE HERE"))
        assertNull(DownloadLinkLabels.serverOrPart(null, null, ""))
    }

    @Test fun `first marking text wins across arguments`() {
        assertEquals("Server 1", DownloadLinkLabels.serverOrPart("", "  server 1 ", "Episode 9"))
    }
}
