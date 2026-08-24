package com.anonrode.downloader.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * Sleep [totalMs] in short [sliceMs] slices so a paused/cancelled job aborts
 * within ~[sliceMs] instead of mid-sleep. Thread.sleep is not interruptible by
 * coroutine cancellation, and v3.0.4 teardowns landed 4-17s after the user
 * tapped Pause. Every slice re-checks [isActiveCheck] first and throws
 * [CancellationException] on a dead coroutine.
 */
internal suspend fun cancellableRetryWait(
    totalMs: Long,
    sliceMs: Long = 250L,
    isActiveCheck: suspend () -> Boolean = { coroutineContext.isActive }
) {
    require(sliceMs > 0) { "sliceMs must be > 0" }
    var remaining = totalMs
    while (remaining > 0) {
        if (!isActiveCheck()) throw CancellationException("Task was cancelled during retry wait")
        val slice = minOf(sliceMs, remaining)
        delay(slice)
        remaining -= slice
    }
}
