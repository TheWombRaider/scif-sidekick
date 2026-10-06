package com.scifsidekick.cleanroom.service

import com.scifsidekick.cleanroom.data.DeliveryAttemptDao
import com.scifsidekick.cleanroom.data.QueueChannel

data class RateRule(
    val maxAttempts: Int,
    val windowMs: Long,
)

object HardRateLimits {
    // Deliberately compile-time, non-disableable safety limits.
    val EMAIL =
        listOf(
            RateRule(maxAttempts = 20, windowMs = 60_000L),
            RateRule(maxAttempts = 300, windowMs = 60L * 60_000L),
            RateRule(maxAttempts = 450, windowMs = 24L * 60L * 60_000L),
        )

    // Ordinary forwards stop slightly short of EMAIL so receipts, status replies and the heartbeat
    // always have a last slice (10/hour, 30/day) even if a flood of inbound texts uses the rest.
    // Attempts are counted together, so the total can never exceed EMAIL.
    val EMAIL_FORWARDS =
        listOf(
            RateRule(maxAttempts = 20, windowMs = 60_000L),
            RateRule(maxAttempts = 290, windowMs = 60L * 60_000L),
            RateRule(maxAttempts = 420, windowMs = 24L * 60L * 60_000L),
        )
    val SMS =
        listOf(
            RateRule(maxAttempts = 10, windowMs = 60_000L),
            RateRule(maxAttempts = 60, windowMs = 60L * 60_000L),
            RateRule(maxAttempts = 200, windowMs = 24L * 60L * 60_000L),
        )
    const val CIRCUIT_FAILURE_THRESHOLD = 5
}

object CircuitPolicy {
    fun failuresAfter(
        currentFailures: Int,
        succeeded: Boolean,
    ): Int = if (succeeded) 0 else currentFailures + 1

    fun isOpen(failures: Int): Boolean = failures >= HardRateLimits.CIRCUIT_FAILURE_THRESHOLD
}

class RollingRateLimiter(
    private val attempts: DeliveryAttemptDao,
) {
    suspend fun nextAllowedAt(
        channel: String,
        nowMs: Long,
        systemMail: Boolean = false,
    ): Long {
        val rules =
            when (channel) {
                QueueChannel.EMAIL -> if (systemMail) HardRateLimits.EMAIL else HardRateLimits.EMAIL_FORWARDS
                // MMS-out shares the SMS tier, not a separate ceiling of its own -- see the
                // QueueChannel.MMS doc comment in Database.kt for why.
                QueueChannel.SMS, QueueChannel.MMS -> HardRateLimits.SMS
                else -> error("Unknown channel $channel")
            }
        var next = nowMs
        for (rule in rules) {
            val since = nowMs - rule.windowMs
            val telephony = channel == QueueChannel.SMS || channel == QueueChannel.MMS
            val count = if (telephony) attempts.countTelephonySince(since) else attempts.countSince(channel, since)
            if (count >= rule.maxAttempts) {
                val oldest =
                    if (telephony) attempts.oldestTelephonySince(since) else attempts.oldestSince(channel, since)
                val oldestAttempt = oldest ?: nowMs
                next = maxOf(next, oldestAttempt + rule.windowMs + 1L)
            }
        }
        return next
    }
}
