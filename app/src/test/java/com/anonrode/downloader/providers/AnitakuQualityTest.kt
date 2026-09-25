package com.anonrode.downloader.providers

import com.anonrode.downloader.data.rules.DynamicRulesManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnitakuQualityTest {

    @Test
    fun testRefererResolutionForAnimeCdns() {
        val nexabloomUrl = "https://fetch.nexabloom.top/anime/82cec96096d4281b7c95cd7e74623496/master.m3u8"
        val vyrnexUrl = "https://jwcif.vyrnex.top/anime/ba3d8556d0fe1001c8e9bc0c667d3ee0/index-f2.m3u8"
        val megaplaysUrl = "https://megaplays.se/api/playlist.php?url=something"

        assertEquals("https://megaplay.buzz/", DynamicRulesManager.resolveReferer(nexabloomUrl))
        assertEquals("https://megaplays.se/", DynamicRulesManager.resolveReferer(vyrnexUrl))
        assertEquals("https://megaplays.se/", DynamicRulesManager.resolveReferer(megaplaysUrl))
    }

    @Test
    fun testQualitySortingLogic() {
        val dlMap = mapOf(
            360 to "https://cdn.example.com/video_360p.mp4",
            720 to "https://cdn.example.com/video_720p.mp4",
            1080 to "https://cdn.example.com/video_1080p.mp4"
        )

        // When user asks for 1080p, 1080p should be first
        val req1080 = 1080
        val sorted1080 = dlMap.entries
            .sortedWith(compareBy({ (h, _) -> if (h <= req1080) 0 else 1 }, { (h, _) -> Math.abs(h - req1080) }))
            .map { it.value }
        assertEquals("https://cdn.example.com/video_1080p.mp4", sorted1080.first())

        // When user asks for 720p, 720p should be first
        val req720 = 720
        val sorted720 = dlMap.entries
            .sortedWith(compareBy({ (h, _) -> if (h <= req720) 0 else 1 }, { (h, _) -> Math.abs(h - req720) }))
            .map { it.value }
        assertEquals("https://cdn.example.com/video_720p.mp4", sorted720.first())

        // When user asks for 480p, closest <= requested or nearest should be 360p
        val req480 = 480
        val sorted480 = dlMap.entries
            .sortedWith(compareBy({ (h, _) -> if (h <= req480) 0 else 1 }, { (h, _) -> Math.abs(h - req480) }))
            .map { it.value }
        assertEquals("https://cdn.example.com/video_360p.mp4", sorted480.first())
    }
}
