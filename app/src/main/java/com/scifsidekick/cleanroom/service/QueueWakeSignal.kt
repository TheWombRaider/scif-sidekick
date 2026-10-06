package com.scifsidekick.cleanroom.service

import android.content.Context
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lets a freshly queued item cut [ForwardingService]'s own idle wait short instead of waiting out
 * the rest of its `QUEUE_TICK_MS` timer -- see [com.scifsidekick.cleanroom.messaging.IncomingMessageReceiver]'s
 * call site. Without this, a text that arrives right after a drain pass starts waits out almost
 * the full tick before its forward is even attempted, on top of whatever the Gmail call itself
 * takes; conflating the wake into the same loop instead of adding a second, parallel send path
 * keeps every safety property that already applies to that loop's drain calls (rate limits, the
 * circuit breaker, single-winner claiming) intact -- this only ever makes the loop check sooner.
 *
 * Conflated: any number of wakes before the loop next checks collapse to one, since the loop
 * always re-reads the queue from scratch regardless of how many rows are waiting on it. A wake
 * sent while nothing is listening (forwarding off, service not running) is simply dropped -- there
 * is nothing for it to have woken, and the row it was queued for is picked up in the ordinary way
 * the next time the service starts.
 */
object QueueWakeSignal {
    private val channel = Channel<Unit>(capacity = Channel.CONFLATED)

    /** Also (re)takes [ForwardWakeLock], so the CPU stays up through the pass that handles this. */
    fun wake(context: Context) {
        ForwardWakeLock.acquire(context)
        signal()
    }

    internal fun signal() {
        channel.trySend(Unit)
    }

    suspend fun awaitOrTimeout(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { channel.receive() }
    }
}
