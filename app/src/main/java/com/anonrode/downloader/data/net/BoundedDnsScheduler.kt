package com.anonrode.downloader.data.net

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Bounded scheduler for the hybrid-DNS "system lookup under a deadline" race.
 *
 * The pre-existing implementation submitted every lookup to an unbounded
 * cachedThreadPool and only *abandoned* the future after 3 s
 * (future.get(3s) + cancel(true)). That fixed the caller's latency but NOT
 * the resource leak: Thread.interrupt() does not break a getaddrinfo() call
 * blocked in the kernel, so a carrier DNS that silently swallows queries left
 * one leaked thread per lookup, and a burst of lookups (a page with a dozen
 * hostnames) could stack unbounded threads, each pinned for up to the OS
 * getaddrinfo timeout (~75 s on the affected networks).
 *
 * This class bounds BOTH dimensions:
 *  - at most [maxInFlight] lookups actually executing, plus
 *  - at most [maxQueued] waiting in the executor queue,
 * so the worst case is [maxInFlight] + [maxQueued] parked lookups on
 * [maxInFlight] fixed daemon threads — never unbounded.
 *
 * When the bound is saturated (or the executor rejects, e.g. after shutdown)
 * the lookup does NOT queue and wait: it goes straight to the DoH
 * [fallback]. Waiting behind a hung getaddrinfo would reintroduce the exact
 * latency the 3 s deadline exists to prevent; DoH is the healthy path in
 * every scenario where system DNS is wedged.
 *
 * Interruption/cancellation: if the calling (OkHttp) thread is interrupted
 * while waiting on the future, the underlying attempt is cancelled, the
 * interrupt flag is RESTORED (never swallowed), and we still attempt the DoH
 * fallback — for a cancelled OkHttp call the subsequent connect fails fast
 * anyway, but a cancelled DNS must not take the whole request down with a
 * naked InterruptedException.
 *
 * Pure and JVM-testable: [primary]/[fallback] are lambdas and [executor] is
 * injectable (constructor-injected Executor), so tests exercise the real
 * scheduling matrix with latches and direct executors — no network, no
 * Android classes.
 */
internal class BoundedDnsScheduler(
    /** The fast primary resolver (system DNS in production). */
    private val primary: (String) -> List<InetAddress>,
    /** DoH fallback used on timeout, saturation, rejection, or primary error. */
    private val fallback: (String) -> List<InetAddress>,
    /** Deadline for one primary attempt. */
    private val primaryTimeoutMs: Long = 3_000L,
    /** Hard cap on concurrently executing primary lookups. */
    private val maxInFlight: Int = 4,
    /** Hard cap on lookups queued behind them. */
    private val maxQueued: Int = 16,
    /** Executor running the primary lookups. Defaults to a FIXED pool of
     *  [maxInFlight] daemon threads with a bounded queue of [maxQueued] and
     *  an AbortPolicy — the fixed size + AbortPolicy is what makes the bound
     *  real (a cachedThreadPool would grow without limit). */
    executor: Executor = ThreadPoolExecutor(
        maxInFlight, maxInFlight, 30L, TimeUnit.SECONDS,
        LinkedBlockingQueue(maxQueued),
        { r -> Thread(r, "hybrid-dns-timeout").also { it.isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )
) {
    /** Outstanding = executing + queued primary attempts. */
    private val outstanding = AtomicInteger(0)

    val saturated: Boolean
        get() = outstanding.get() >= maxInFlight + maxQueued

    fun lookup(hostname: String): List<InetAddress> {
        val task = FutureTask<List<InetAddress>> { primary(hostname) }
        var submitted = false
        // Reserve a slot BEFORE touching the executor: reserve-then-execute
        // makes the bound airtight even when many threads race this method.
        if (outstanding.incrementAndGet() <= maxInFlight + maxQueued) {
            try {
                exec.execute {
                    try {
                        task.run()
                    } finally {
                        outstanding.decrementAndGet()
                    }
                }
                submitted = true
            } catch (_: RejectedExecutionException) {
                // Executor at its own internal bound or shut down: unreserve
                // and fall through to DoH — never block the caller.
                outstanding.decrementAndGet()
            }
        } else {
            outstanding.decrementAndGet()
        }

        if (submitted) {
            val result: List<InetAddress>? = try {
                task.get(primaryTimeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                // Cannot interrupt getaddrinfo, but abandon the attempt; the
                // slot frees when the kernel finally releases the thread.
                task.cancel(true)
                null
            } catch (_: InterruptedException) {
                // Caller (OkHttp thread) interrupted — e.g. call cancelled.
                task.cancel(true)
                Thread.currentThread().interrupt() // restore, never swallow
                null
            } catch (_: ExecutionException) {
                // Primary threw (NXDOMAIN etc.). null -> DoH fallback, which
                // is the pre-existing hybrid semantics.
                null
            } catch (_: IllegalStateException) {
                // FutureTask in an unexpected state: treat as primary failure.
                null
            }
            if (result != null) return result
        }
        // Timed out / interrupted / saturated / rejected / primary error.
        return try {
            fallback(hostname)
        } catch (e: UnknownHostException) {
            throw e
        } catch (e: Exception) {
            throw UnknownHostException("$hostname: primary and DoH fallback both failed (${e.javaClass.simpleName})")
        }
    }

    private val exec: Executor = executor
}
