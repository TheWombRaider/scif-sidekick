package com.scifsidekick.cleanroom.util

/**
 * Caps how many events of one kind get written per rolling window, so a flood of junk mail can't
 * grow the event log without bound. Once [limit] is used up, further events are only counted; the
 * first event of the next window reports how many were skipped so a summary row can be written.
 * In-memory only: a process restart simply starts a fresh window.
 */
class HourlyEventBudget(
    private val limit: Int = 20,
    private val windowMs: Long = 60L * 60_000L,
) {
    data class Decision(
        val log: Boolean,
        /** Events skipped in the window that just ended; write one summary row when > 0. */
        val previouslySuppressed: Int = 0,
    )

    private var windowStartMs = Long.MIN_VALUE
    private var used = 0
    private var suppressed = 0

    @Synchronized
    fun take(nowMs: Long): Decision {
        var previous = 0
        if (windowStartMs == Long.MIN_VALUE || nowMs - windowStartMs >= windowMs || nowMs < windowStartMs) {
            previous = suppressed
            windowStartMs = nowMs
            used = 0
            suppressed = 0
        }
        if (used < limit) {
            used++
            return Decision(log = true, previouslySuppressed = previous)
        }
        suppressed++
        return Decision(log = false, previouslySuppressed = previous)
    }
}
