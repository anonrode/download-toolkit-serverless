package com.anonrode.downloader.ui.screens

import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Mechanism 3 of the "Download Remaining (episodes)" trace
 * (investigation/12_remaining_button_trace.md): in date/status/size sorts
 * one group header mixes shows — the TODAY bucket holds every show touched
 * today — and the per-show chip only rendered when the whole group
 * belonged to ONE show (singleOrNull gate), leaving every mixed bucket
 * without any shortcut.
 *
 * mixedGroupShowSlices decides the rendering: single-show groups (or
 * groups with no actionable show) keep the exact previous header; a mixed
 * group with an actionable show slices into per-show row-runs so each
 * show's sub-header can carry its own chip.
 */
class MixedGroupRemainingTest {

    private fun task(
        id: String,
        showTitle: String,
        status: TaskStatus = TaskStatus.COMPLETED
    ) = DownloadTask(
        id = id,
        showTitle = showTitle,
        episodeNum = 1,
        episodeTitle = "E $id",
        directUrl = "https://cdn/$id.bin",
        sourceUrl = "https://site/$id",
        status = status,
        site = "nepu"
    )

    @Test
    fun singleShowGroupKeepsTheOldRendering() {
        val group = listOf(
            task("t1", "Show A"),
            task("t2", "Show A", status = TaskStatus.PAUSED)
        )
        assertNull(mixedGroupShowSlices(group) { it == "Show A" })
    }

    @Test
    fun mixedGroupWithoutActionableShowKeepsTheOldRendering() {
        // Two shows, neither chip-eligible (e.g. only non-series or
        // unstamped tasks in the bucket) — no slices, header unchanged.
        val group = listOf(
            task("t1", "Torrents"),
            task("t2", "Direct Downloads")
        )
        assertNull(mixedGroupShowSlices(group) { false })
    }

    @Test
    fun mixedGroupSlicesPerShowInFirstSeenOrder() {
        val g1 = task("a1", "Show A")
        val b1 = task("b1", "Show B", status = TaskStatus.PAUSED)
        val g2 = task("a2", "Show A")
        val junk = task("x1", "Torrents")
        val b2 = task("b2", "Show B")
        val group = listOf(g1, b1, g2, junk, b2)
        assertEquals(
            listOf(
                ShowSlice(showTitle = "Show A", tasks = listOf(g1, g2)),
                ShowSlice(showTitle = "Show B", tasks = listOf(b1, b2)),
                ShowSlice(showTitle = "Torrents", tasks = listOf(junk))
            ),
            mixedGroupShowSlices(group) { title -> title != "Torrents" }
        )
    }

    @Test
    fun everyShowIsSlicedEvenWhenOnlySomeAreEligible() {
        // Segmentation is complete: a non-eligible show still gets its own
        // sub-rows (without a chip) so rows never float headerless.
        val group = listOf(
            task("t1", "Show A"),
            task("t2", "Social/YouTube")
        )
        val slices = mixedGroupShowSlices(group) { title -> title == "Show A" }
        assertEquals(2, slices?.size)
        assertEquals("Social/YouTube", slices?.last()?.showTitle)
    }
}
