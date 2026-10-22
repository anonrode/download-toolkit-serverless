package com.anonrode.downloader.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Board row 4 / trace C2: the display ETA prefers the windowed smoother over
 * the raw per-line yt-dlp ETA. The raw speed sample and the progress
 * watchdog semantics are untouched — only this ranking is asserted.
 */
class YoutubeDlDisplayEtaTest {

    @Test
    fun windowedEtaWinsOverRawLineAndLibrary() {
        assertEquals(
            42L,
            YoutubeDlDownloader.chooseDisplayEta(
                windowedEtaSeconds = 42L,
                libraryEtaSeconds = 7L,
                rawLineEtaSeconds = 5L
            )
        )
    }

    @Test
    fun libraryEtaIsTheFallbackWhileTheWindowWarmsUp() {
        assertEquals(
            7L,
            YoutubeDlDownloader.chooseDisplayEta(
                windowedEtaSeconds = 0L,
                libraryEtaSeconds = 7L,
                rawLineEtaSeconds = 5L
            )
        )
        // A negative smoother reading is "no reading", not a valid ETA.
        assertEquals(
            7L,
            YoutubeDlDownloader.chooseDisplayEta(
                windowedEtaSeconds = -1L,
                libraryEtaSeconds = 7L,
                rawLineEtaSeconds = 5L
            )
        )
    }

    @Test
    fun rawLineEtaIsTheLastResort() {
        assertEquals(
            5L,
            YoutubeDlDownloader.chooseDisplayEta(
                windowedEtaSeconds = 0L,
                libraryEtaSeconds = 0L,
                rawLineEtaSeconds = 5L
            )
        )
    }

    @Test
    fun noSourceYieldsZero() {
        assertEquals(
            0L,
            YoutubeDlDownloader.chooseDisplayEta(
                windowedEtaSeconds = -1L,
                libraryEtaSeconds = -1L,
                rawLineEtaSeconds = 0L
            )
        )
    }
}
