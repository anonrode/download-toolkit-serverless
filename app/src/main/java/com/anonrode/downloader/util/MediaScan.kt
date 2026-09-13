package com.anonrode.downloader.util

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Index a freshly written media file so the device gallery picks it up
 * without the user having to share it out of the app first.
 *
 * Why this wrapper exists: MediaScannerConnection binds an asynchronous
 * system service, and the two-step convenience scanFile needs a Looper to
 * receive the bind callback. The engine's completion path runs on an IO
 * coroutine (no Looper), where the connection NEVER BINDS — scanFile returns
 * cleanly, nothing throws, and the file simply never enters MediaStore. That
 * is exactly why social downloads appeared in the gallery only sporadically
 * (a later system-wide rescan rescued some folders). Hop to the MAIN looper
 * where the bind always lands, then scan.
 *
 * Posting is fire-and-forget on purpose: gallery indexing must never delay
 * or fail a download completion, and the media provider is async anyway.
 */
object MediaScan {
    fun notifyFile(context: Context, file: File) {
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            runCatching {
                MediaScannerConnection.scanFile(
                    app,
                    arrayOf(file.absolutePath),
                    null,
                    null
                )
            }
        }
    }
}
