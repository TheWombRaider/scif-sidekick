package com.scifsidekick.cleanroom.util

import kotlinx.coroutines.CancellationException

/**
 * `runCatching` around a `suspend` block is a well-known Kotlin coroutines trap: `Cancellation
 * Exception` is a `RuntimeException`, so a plain `catch (Throwable)` -- which is exactly what
 * `runCatching` does -- silently absorbs cancellation into a normal `Result.failure` instead of
 * letting it propagate. A coroutine that should have stopped instead keeps running its
 * `.onFailure`/`.getOrNull()` handler as if a real error occurred: writing to state a cancelled
 * screen no longer owns, retrying work a torn-down service no longer needs to do, or emitting to
 * a flow nothing is collecting anymore. This is the drop-in replacement used everywhere in this
 * app that wraps a suspend call in a try/catch-shaped result -- same call shape as `runCatching`,
 * but a `CancellationException` is rethrown instead of captured.
 */
suspend fun <T> suspendRunCatching(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        Result.failure(t)
    }
