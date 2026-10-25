package com.anonrode.downloader.engine

import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ProgressWatchdog(
    private val repository: DownloadRepository,
    private val computeVerifiedDiskBytes: (DownloadTask) -> Long,
    private val onStallKill: suspend (taskId: String, killCount: Int, exhausted: Boolean, stallMessage: String, task: DownloadTask) -> Unit
) {
    companion object {
        private const val CRAWL_WINDOW_BYTES = 64L * 1024 // 64 KiB floor per stall window
        private const val THROTTLE_SESSION_CAP_MS = 15 * 60_000L
        private const val MAX_STALL_KILLS = 8
    }

    fun launchForTask(
        scope: CoroutineScope,
        initialTask: DownloadTask,
        streamUrl: String,
        stallTimeoutSecProvider: () -> Int
    ): Job = scope.launch {
        val initialDisk = computeVerifiedDiskBytes(initialTask)
        val initialParsed = initialTask.downloadedBytes
        var lastDisk = initialDisk
        var lastParsed = initialParsed
        var lastActivity = System.currentTimeMillis()
        var lastKillTime = 0L
        var stallKills = 0
        var throttleFirstSeen = 0L

        var windowStartDisk = initialDisk
        var windowStartParsed = initialParsed
        var windowStartTime = System.currentTimeMillis()
        var bestWindowBps = 0.0
        var lastProgressLog = 0L
        val watchdogStart = System.currentTimeMillis()

        while (isActive) {
            delay(2000L)
            if (!isActive) break
            val t = repository.find(initialTask.id) ?: break
            when (t.status) {
                TaskStatus.DOWNLOADING -> {}
                TaskStatus.RESOLVING -> continue
                else -> break
            }

            val stallTimeoutSec = stallTimeoutSecProvider().coerceAtLeast(15)
            val stallTimeoutMs = stallTimeoutSec * 1000L
            val now = System.currentTimeMillis()

            // BUG FIX: computeVerifiedDiskBytes reads TurboState sidecar for pre-allocated .part files
            val disk = computeVerifiedDiskBytes(t)
            val parsed = t.downloadedBytes
            val diskDelta = disk - lastDisk
            val diskGrew = disk > lastDisk
            val parsedGrew = parsed > lastParsed

            if (diskGrew) lastDisk = disk
            if (parsedGrew) lastParsed = parsed
            if (diskGrew || parsedGrew) {
                lastActivity = now
                throttleFirstSeen = 0L
            }

            if (TurboDownloader.throttleRemainingMs(initialTask.id) > 0L) {
                if (throttleFirstSeen == 0L) throttleFirstSeen = now
                lastActivity = now
            }
            val throttleGrace = throttleFirstSeen > 0L && (now - throttleFirstSeen) <= THROTTLE_SESSION_CAP_MS

            // BUG FIX: Detect shrinking disk footprint (e.g. yt-dlp/ffmpeg deleting .fNNN shards
            // during muxing) or reset parsed counter BEFORE taking maxOf(diskDelta, parsedDelta).
            if (disk < windowStartDisk || parsed < windowStartParsed) {
                windowStartDisk = disk
                windowStartParsed = parsed
                windowStartTime = now
                lastActivity = now
            }

            val moved = maxOf(disk - windowStartDisk, parsed - windowStartParsed)

            if (moved > 0 && now - lastProgressLog >= 10_000L) {
                lastProgressLog = now
                com.anonrode.downloader.util.DebugLog.engine(
                    "task=${initialTask.id} progress disk=${disk / 1024}KiB parsed=${parsed / 1024}KiB window=${moved / 1024}KiB"
                )
            }

            // Bridge verified filesystem progress to UI when stdout parser lags
            if (disk > t.downloadedBytes) {
                val safeDisk = if (t.totalBytes > 0L && disk >= t.totalBytes) {
                    t.downloadedBytes
                } else {
                    disk
                }
                if (safeDisk > t.downloadedBytes) {
                    val speed = if (t.speedBytesPerSec > 0.0) {
                        t.speedBytesPerSec
                    } else {
                        diskDelta.coerceAtLeast(0L) * 1000.0 / 2000.0
                    }
                    val eta = if (t.etaSeconds > 0L) {
                        t.etaSeconds
                    } else if (speed > 0.0 && t.totalBytes > safeDisk) {
                        ((t.totalBytes - safeDisk) / speed).toLong().coerceAtLeast(1L)
                    } else {
                        0L
                    }
                    repository.updateProgress(
                        taskId = initialTask.id,
                        downloaded = safeDisk,
                        total = t.totalBytes,
                        speed = speed,
                        eta = eta
                    )
                }
            }

            val windowSecs = ((now - windowStartTime).coerceAtLeast(500L)) / 1000.0
            val windowBps = if (windowSecs > 0.0) moved / windowSecs else 0.0
            if (windowBps > bestWindowBps) bestWindowBps = windowBps

            if (moved >= CRAWL_WINDOW_BYTES) {
                windowStartDisk = disk
                windowStartParsed = parsed
                windowStartTime = now
                // Soft reset, never a hard zero: a download that alternates
                // short bursts with stalls must not restart the "kill #1"
                // counter forever (the DEV2 63-kill false-positive loop).
                // Only a genuinely healthy window (>= 64 KiB/s AND at least
                // half the best window) clears the whole count.
                if (windowBps >= 64 * 1024 && windowBps >= bestWindowBps * 0.5) {
                    stallKills = 0
                } else if (stallKills > 0) {
                    stallKills--
                }
            }

            val isStartupPhase = (disk == 0L && parsed == 0L)
            val isExtractor = initialTask.backend == "yt-dlp" ||
                streamUrl.contains("facebook.com", ignoreCase = true) ||
                streamUrl.contains("instagram.com", ignoreCase = true) ||
                streamUrl.contains("youtube.com", ignoreCase = true) ||
                streamUrl.contains("youtu.be", ignoreCase = true) ||
                streamUrl.contains("tiktok.com", ignoreCase = true) ||
                streamUrl.contains("x.com", ignoreCase = true) ||
                streamUrl.contains("twitter.com", ignoreCase = true)

            val effectiveStallTimeoutMs = if (isStartupPhase && isExtractor) {
                maxOf(stallTimeoutMs, 120_000L)
            } else {
                stallTimeoutMs
            }

            val crawlStalled = (now - windowStartTime > effectiveStallTimeoutMs) && (moved < CRAWL_WINDOW_BYTES)
            val magnetTask = streamUrl.startsWith("magnet:", ignoreCase = true)
            val attemptBoundary = if (magnetTask) 90_000L else maxOf(stallKills * effectiveStallTimeoutMs * 2, effectiveStallTimeoutMs)
            val stalledLong = (now - lastActivity) > effectiveStallTimeoutMs
            // A crawl verdict additionally requires NO growth in the last
            // half-timeout: the old code killed active transfers whose 60s
            // window total stayed under the floor even though bytes were
            // arriving every poll (server evidence: idle=0s crawl=true
            // best=940KiB/s x63). Bytes moving = not a stall.
            val recentlyActive = (now - lastActivity) <= (effectiveStallTimeoutMs / 2).coerceAtLeast(1_000L)
            val isStalledOrCrawling = stalledLong || (crawlStalled && !recentlyActive)

            if (!magnetTask &&
                !throttleGrace &&
                isStalledOrCrawling &&
                (now - watchdogStart) > attemptBoundary &&
                (stallKills == 0 || (now - lastKillTime) > effectiveStallTimeoutMs)
            ) {
                // BUG FIX: Compute idleSeconds BEFORE resetting lastActivity = now!
                val idleSeconds = (now - lastActivity) / 1000L
                stallKills++
                lastKillTime = now
                windowStartDisk = disk
                windowStartParsed = parsed
                windowStartTime = now
                lastActivity = now

                com.anonrode.downloader.util.DebugLog.engine(
                    "task=${initialTask.id} watchdog stall kill #$stallKills (idle=${idleSeconds}s crawl=$crawlStalled window=${moved / 1024}KiB best=${(bestWindowBps / 1024).toInt()}KiB/s)"
                )

                val exhausted = stallKills >= MAX_STALL_KILLS
                val stallMsg = "Download stalled — no progress for ${effectiveStallTimeoutMs / 1000L}s across $stallKills attempts"
                onStallKill(initialTask.id, stallKills, exhausted, stallMsg, t)
                if (exhausted) break
            }
        }
    }
}
