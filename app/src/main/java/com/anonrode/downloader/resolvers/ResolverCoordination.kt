package com.anonrode.downloader.resolvers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** A sliding window: both active work and child coroutine count stay bounded. */
internal suspend fun <T, R : Any> boundedFirstSuccess(
    candidates: List<T>,
    maxConcurrency: Int,
    attempt: suspend (T) -> R?
): R? = supervisorScope {
    currentCoroutineContext().ensureActive()
    val pending = mutableListOf<Deferred<R?>>()
    val iterator = candidates.iterator()
    fun launchNext() {
        if (!iterator.hasNext()) return
        val candidate = iterator.next()
        pending += async {
            currentCoroutineContext().ensureActive()
            val result = try {
                attempt(candidate)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            currentCoroutineContext().ensureActive()
            result
        }
    }
    try {
        repeat(minOf(candidates.size, maxConcurrency.coerceIn(1, 6))) { launchNext() }
        while (pending.isNotEmpty()) {
            val (done, result) = select<Pair<Deferred<R?>, R?>> {
                pending.forEach { work -> work.onAwait { work to it } }
            }
            pending.remove(done)
            if (result != null) return@supervisorScope result
            launchNext()
        }
        null
    } finally {
        pending.forEach { it.cancel() }
    }
}

/** Attempt-local evidence; legacy nulls cannot identify a network failure. */
internal sealed interface ResolverOutcome {
    data class Success(val url: String) : ResolverOutcome
    data object NoMatch : ResolverOutcome
    data class Failure(val reason: String, val retryable: Boolean = false) : ResolverOutcome
}

internal fun resolverFailure(error: Exception): ResolverOutcome.Failure = ResolverOutcome.Failure(
    reason = "${error.javaClass.simpleName}: ${error.message}",
    retryable = error is UnknownHostException || error is ConnectException ||
        error is SocketTimeoutException || error is SocketException
)

internal suspend fun captureResolverOutcome(attempt: suspend () -> String?): ResolverOutcome {
    currentCoroutineContext().ensureActive()
    val outcome = try {
        attempt()?.takeIf { it.isNotBlank() }?.let { ResolverOutcome.Success(it) }
            ?: ResolverOutcome.NoMatch
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        resolverFailure(error)
    }
    // Legacy resolvers may swallow an I/O exception caused by cancellation.
    currentCoroutineContext().ensureActive()
    return outcome
}
