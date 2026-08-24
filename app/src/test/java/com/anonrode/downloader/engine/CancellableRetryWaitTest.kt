package com.anonrode.downloader.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlinx.coroutines.delay as coroutineDelay

/**
 * Cancellation responsiveness of the retry waits (v3.0.4 teardowns landed
 * 4-17s after the user tapped Pause because Thread.sleep is not interruptible
 * by coroutine cancellation). A cancelled job must abort within ~one slice.
 */
class CancellableRetryWaitTest {

    @Test
    fun completesAfterTotalDelay() = runBlocking {
        val started = System.currentTimeMillis()
        cancellableRetryWait(totalMs = 120, sliceMs = 40)
        val elapsed = System.currentTimeMillis() - started
        assertTrue("waited only $elapsed ms for a 120ms wait", elapsed >= 100)
        assertTrue("waited $elapsed ms for a 120ms wait", elapsed < 5_000)
    }

    @Test
    fun cancelsPromptlyInsteadOfSleepingFullDuration() = runBlocking {
        val started = System.currentTimeMillis()
        val job = launch {
            try {
                cancellableRetryWait(totalMs = 8_000, sliceMs = 250)
                fail("expected CancellationException")
            } catch (e: CancellationException) {
                // Expected: the wait aborts as soon as the job is cancelled.
            }
        }
        coroutineDelay(50)
        job.cancel()
        job.join()
        val elapsed = System.currentTimeMillis() - started
        assertTrue("cancel took ${elapsed}ms", elapsed < 3_000)
    }

    @Test
    fun falseIsActiveCheckBailsOutImmediately() = runBlocking {
        val started = System.currentTimeMillis()
        val job = launch {
            cancellableRetryWait(totalMs = 8_000, sliceMs = 250, isActiveCheck = { false })
        }
        job.join()
        assertTrue("bailed with no delay", System.currentTimeMillis() - started < 1_000)
    }

    @Test
    fun zeroSliceRejected() = runBlocking {
        var thrown = false
        try {
            cancellableRetryWait(totalMs = 100, sliceMs = 0)
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue("expected IllegalArgumentException for sliceMs=0", thrown)
    }
}
