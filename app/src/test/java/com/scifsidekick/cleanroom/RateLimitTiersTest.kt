package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.data.DeliveryAttemptDao
import com.scifsidekick.cleanroom.data.DeliveryAttemptEntity
import com.scifsidekick.cleanroom.data.QueueChannel
import com.scifsidekick.cleanroom.service.HardRateLimits
import com.scifsidekick.cleanroom.service.RollingRateLimiter
import com.scifsidekick.cleanroom.util.PremiumNumbers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimitTiersTest {
    private class FakeAttempts : DeliveryAttemptDao {
        val rows = mutableListOf<Pair<String, Long>>()

        override suspend fun insert(attempt: DeliveryAttemptEntity): Long = 0

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

        override suspend fun countTelephonySince(sinceMs: Long) = rows.count { it.first in setOf("SMS", "MMS") && it.second > sinceMs }

        override suspend fun oldestTelephonySince(sinceMs: Long) =
            rows.filter { it.first in setOf("SMS", "MMS") && it.second > sinceMs }.minOfOrNull { it.second }

        override suspend fun prune(beforeMs: Long) = 0
    }

    private val now = 100_000_000L
    private val day = 24L * 60L * 60_000L

    @Test fun `forwards stop at 420 a day but system mail may use the reserve up to 450`() =
        runBlocking {
            val attempts = FakeAttempts()
            // Spread out so only the daily rule can bind.
            repeat(420) { attempts.rows += QueueChannel.EMAIL to (now - day + 60_000L + it * 150_000L) }
            val limiter = RollingRateLimiter(attempts)
            assertTrue(limiter.nextAllowedAt(QueueChannel.EMAIL, now) > now)
            assertEquals(now, limiter.nextAllowedAt(QueueChannel.EMAIL, now, systemMail = true))
            repeat(30) { attempts.rows += QueueChannel.EMAIL to (now - 1_000L * (it + 1) * 10) }
            assertTrue(limiter.nextAllowedAt(QueueChannel.EMAIL, now, systemMail = true) > now)
        }

    @Test fun `forwards stop at 290 an hour but system mail may continue`() =
        runBlocking {
            val attempts = FakeAttempts()
            repeat(290) { attempts.rows += QueueChannel.EMAIL to (now - 59L * 60_000L + it * 10_000L) }
            val limiter = RollingRateLimiter(attempts)
            assertTrue(limiter.nextAllowedAt(QueueChannel.EMAIL, now) > now)
            assertEquals(now, limiter.nextAllowedAt(QueueChannel.EMAIL, now, systemMail = true))
        }

    @Test fun `the reserve never raises the overall email ceilings`() {
        assertEquals(listOf(20, 300, 450), HardRateLimits.EMAIL.map { it.maxAttempts })
        assertTrue(HardRateLimits.EMAIL_FORWARDS.zip(HardRateLimits.EMAIL).all { (forward, all) -> forward.maxAttempts <= all.maxAttempts })
        assertEquals(HardRateLimits.EMAIL.map { it.windowMs }, HardRateLimits.EMAIL_FORWARDS.map { it.windowMs })
    }

    @Test fun `sms has hourly and daily caps`() =
        runBlocking {
            val hourly = FakeAttempts()
            repeat(60) { hourly.rows += QueueChannel.SMS to (now - 50L * 60_000L + it * 20_000L) }
            assertTrue(RollingRateLimiter(hourly).nextAllowedAt(QueueChannel.SMS, now) > now)

            val daily = FakeAttempts()
            repeat(200) { daily.rows += QueueChannel.SMS to (now - day + 60_000L + it * 400_000L) }
            assertTrue(RollingRateLimiter(daily).nextAllowedAt(QueueChannel.SMS, now) > now)

            val fine = FakeAttempts()
            repeat(59) { fine.rows += QueueChannel.SMS to (now - 50L * 60_000L + it * 20_000L) }
            assertEquals(now, RollingRateLimiter(fine).nextAllowedAt(QueueChannel.SMS, now))
        }

    @Test fun `premium and satellite ranges are blocked`() {
        listOf(
            "+19005551234", "+19765551234", "+449012345678", "+447012345678", "+499001234567", "+491371234567",
            "+33891234567", "+34803123456", "+39892123456", "+611900123456", "+31900123456", "+32900123456",
            "+41900123456", "+43900123456", "+979123456789", "+881234567890", "+870123456789",
        ).forEach {
            assertTrue("$it should be blocked", PremiumNumbers.isPremiumRate(it))
            assertNotNull(PremiumNumbers.rejectionReason(it))
        }
    }

    @Test fun `ordinary numbers are allowed`() {
        listOf(
            "+15551234567", "+12125551234", "+19015551234", "+442071234567", "+447911123456", "+4915112345678",
            "+33612345678", "+34612345678", "+393331234567", "+61412345678", "+31612345678", "+81312345678",
        ).forEach {
            assertFalse("$it should be allowed", PremiumNumbers.isPremiumRate(it))
            assertNull(PremiumNumbers.rejectionReason(it))
        }
    }
}
