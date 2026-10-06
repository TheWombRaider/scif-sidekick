package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.data.DeliveryAttemptDao
import com.scifsidekick.cleanroom.data.DeliveryAttemptEntity
import com.scifsidekick.cleanroom.data.QueueChannel
import com.scifsidekick.cleanroom.service.ForwardLatency
import com.scifsidekick.cleanroom.service.QueueWakeSignal
import com.scifsidekick.cleanroom.service.RollingRateLimiter
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServicePolicyTest {
    private class FakeAttempts : DeliveryAttemptDao {
        val rows = mutableListOf<Pair<String, Long>>()

        override suspend fun insert(attempt: DeliveryAttemptEntity): Long {
            rows += attempt.channel to attempt.attemptedAtMs
            return rows.size.toLong()
        }

        override suspend fun finish(
            id: Long,
            succeeded: Boolean,
            detail: String,
        ) = Unit

        override suspend fun latestAttemptId(queueId: Long): Long? = null

        override suspend fun countSince(
            channel: String,
            sinceMs: Long,
        ) = rows.count { it.first == channel && it.second > sinceMs }

        override suspend fun oldestSince(
            channel: String,
            sinceMs: Long,
        ) = rows.filter { it.first == channel && it.second > sinceMs }.minOfOrNull { it.second }

        override suspend fun countTelephonySince(sinceMs: Long) = rows.count { it.first in TELEPHONY && it.second > sinceMs }

        override suspend fun oldestTelephonySince(sinceMs: Long) =
            rows.filter { it.first in TELEPHONY && it.second > sinceMs }.minOfOrNull { it.second }

        override suspend fun prune(beforeMs: Long) = 0

        fun add(
            channel: String,
            atMs: Long,
        ) {
            rows += channel to atMs
        }

        companion object {
            val TELEPHONY = setOf(QueueChannel.SMS, QueueChannel.MMS)
        }
    }

    private val now = 10_000_000L

    @Test fun `email under the per-minute cap is allowed immediately`() =
        runBlocking {
            val attempts = FakeAttempts()
            repeat(19) { attempts.add(QueueChannel.EMAIL, now - 30_000L + it) }
            assertEquals(now, RollingRateLimiter(attempts).nextAllowedAt(QueueChannel.EMAIL, now))
        }

    @Test fun `twentieth email in a minute defers until the oldest ages out`() =
        runBlocking {
            val attempts = FakeAttempts()
            val oldest = now - 50_000L
            repeat(20) { attempts.add(QueueChannel.EMAIL, oldest + it) }
            assertEquals(oldest + 60_000L + 1L, RollingRateLimiter(attempts).nextAllowedAt(QueueChannel.EMAIL, now))
        }

    @Test fun `hourly email cap applies even when the last minute is quiet`() =
        runBlocking {
            val attempts = FakeAttempts()
            val oldest = now - 50L * 60_000L
            repeat(300) { attempts.add(QueueChannel.EMAIL, oldest + it * 1_000L) }
            assertEquals(oldest + 60L * 60_000L + 1L, RollingRateLimiter(attempts).nextAllowedAt(QueueChannel.EMAIL, now))
        }

    @Test fun `sms and mms share one telephony budget`() =
        runBlocking {
            val attempts = FakeAttempts()
            repeat(5) { attempts.add(QueueChannel.SMS, now - 10_000L + it) }
            repeat(5) { attempts.add(QueueChannel.MMS, now - 5_000L + it) }
            val limiter = RollingRateLimiter(attempts)
            assertTrue(limiter.nextAllowedAt(QueueChannel.MMS, now) > now)
            assertTrue(limiter.nextAllowedAt(QueueChannel.SMS, now) > now)
            assertEquals(now, limiter.nextAllowedAt(QueueChannel.EMAIL, now))
        }

    @Test fun `forward latency is described at a readable resolution`() {
        assertEquals("under 1s", ForwardLatency.describe(999))
        assertEquals("4.2s", ForwardLatency.describe(4_200))
        assertEquals("3m 5s", ForwardLatency.describe(185_000))
        assertEquals("2h 1m", ForwardLatency.describe(7_260_000))
        assertEquals("under 1s", ForwardLatency.describe(-50))
    }

    @Test fun `a wake cuts the queue loop's idle wait short`() =
        runBlocking {
            QueueWakeSignal.awaitOrTimeout(1)
            val started = System.currentTimeMillis()
            val waiter = async { QueueWakeSignal.awaitOrTimeout(10_000) }
            delay(50)
            QueueWakeSignal.signal()
            waiter.await()
            assertTrue(System.currentTimeMillis() - started < 5_000)
        }

    @Test fun `wakes sent before the loop checks collapse into one`() =
        runBlocking {
            QueueWakeSignal.awaitOrTimeout(1)
            repeat(5) { QueueWakeSignal.signal() }
            val first = System.currentTimeMillis()
            QueueWakeSignal.awaitOrTimeout(2_000)
            assertTrue(System.currentTimeMillis() - first < 1_000)
            val second = System.currentTimeMillis()
            QueueWakeSignal.awaitOrTimeout(300)
            assertTrue(System.currentTimeMillis() - second >= 250)
        }
}
