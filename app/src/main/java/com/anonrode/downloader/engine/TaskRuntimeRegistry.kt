package com.anonrode.downloader.engine

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Consolidates all per-task runtime bookkeeping into a single lifecycle-managed entry,
 * replacing the 8 separate ConcurrentHashMaps/Sets in DownloadEngine and TurboDownloader.
 */
class TaskRuntimeRegistry {

    class TaskRuntimeEntry(val taskId: String) {
        @Volatile var activeJob: Job? = null
        val isStarting = AtomicBoolean(false)
        val retryBypassHealth = AtomicBoolean(false)
        val isFullyCancelled = AtomicBoolean(false)
        val parkCycleCount = AtomicInteger(0)
        val firstParkedAtMs = AtomicLong(0L)
        val requeueNotBeforeMs = AtomicLong(0L)
        val triedFailoverSites: MutableSet<String> = ConcurrentHashMap.newKeySet()

        fun resetParkingAndFailoverState() {
            parkCycleCount.set(0)
            firstParkedAtMs.set(0L)
            requeueNotBeforeMs.set(0L)
            triedFailoverSites.clear()
        }
    }

    private val entries = ConcurrentHashMap<String, TaskRuntimeEntry>()

    fun getOrCreate(taskId: String): TaskRuntimeEntry =
        entries.computeIfAbsent(taskId) { TaskRuntimeEntry(it) }

    fun get(taskId: String): TaskRuntimeEntry? = entries[taskId]

    fun getActiveJob(taskId: String): Job? = entries[taskId]?.activeJob

    fun tryAcquireStartLock(taskId: String): Boolean {
        val entry = getOrCreate(taskId)
        val existingJob = entry.activeJob
        if (existingJob != null && existingJob.isActive) return false
        return entry.isStarting.compareAndSet(false, true)
    }

    fun attachJob(taskId: String, job: Job) {
        val entry = getOrCreate(taskId)
        entry.activeJob = job
        entry.isStarting.set(false)
    }

    fun releaseStartLock(taskId: String) {
        entries[taskId]?.isStarting?.set(false)
    }

    fun markForManualRetry(taskId: String) {
        val entry = getOrCreate(taskId)
        entry.isFullyCancelled.set(false)
        entry.retryBypassHealth.set(true)
        entry.resetParkingAndFailoverState()
    }

    /**
     * Stall-kill retry: grants the health-check bypass WITHOUT resetting parking counters,
     * so park/failover backoff state survives repeated watchdog kills.
     */
    fun grantRetryBypass(taskId: String) {
        val entry = getOrCreate(taskId)
        entry.isFullyCancelled.set(false)
        entry.retryBypassHealth.set(true)
    }

    fun consumeRetryBypass(taskId: String): Boolean =
        entries[taskId]?.retryBypassHealth?.getAndSet(false) ?: false

    fun markFullyCancelled(taskId: String) {
        getOrCreate(taskId).isFullyCancelled.set(true)
    }

    fun isFullyCancelled(taskId: String): Boolean =
        entries[taskId]?.isFullyCancelled?.get() ?: false

    /**
     * Called when a task is permanently removed or completed to prevent memory leaks.
     */
    fun remove(taskId: String) {
        entries.remove(taskId)?.activeJob?.cancel()
    }

    /**
     * Terminal cleanup from inside a task's own coroutine: drops the entry WITHOUT
     * cancelling the running job (unlike [remove], which would self-cancel).
     */
    fun forget(taskId: String) {
        entries.remove(taskId)
    }

    fun hasOtherActiveWork(ignoredTaskId: String = ""): Boolean =
        entries.values.any { entry ->
            entry.taskId != ignoredTaskId && (entry.activeJob?.isActive == true || entry.isStarting.get())
        }

    fun activeRunningTaskIds(): Set<String> =
        entries.values.filter { it.activeJob?.isActive == true || it.isStarting.get() }
            .mapTo(HashSet()) { it.taskId }

    fun cancelAndRemove(taskId: String) {
        val entry = entries.remove(taskId)
        entry?.activeJob?.cancel()
    }

    fun isEmpty(): Boolean =
        entries.values.none { it.activeJob?.isActive == true || it.isStarting.get() }
}
