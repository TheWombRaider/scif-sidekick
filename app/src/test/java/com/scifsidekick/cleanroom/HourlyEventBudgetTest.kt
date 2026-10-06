package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.util.HourlyEventBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HourlyEventBudgetTest {
    private val hour = 60L * 60_000L

    @Test fun `events up to the limit are logged and the rest are suppressed`() {
        val budget = HourlyEventBudget(limit = 3)
        assertTrue((1..3).all { budget.take(1_000L + it).log })
        assertFalse(budget.take(2_000L).log)
        assertFalse(budget.take(3_000L).log)
    }

    @Test fun `the next window reports how many were suppressed and starts fresh`() {
        val budget = HourlyEventBudget(limit = 2)
        repeat(2) { budget.take(1_000L) }
        repeat(5) { budget.take(2_000L) }
        val next = budget.take(1_000L + hour)
        assertTrue(next.log)
        assertEquals(5, next.previouslySuppressed)
        assertEquals(0, budget.take(1_001L + hour).previouslySuppressed)
    }

    @Test fun `a clock that jumps backwards starts a new window instead of locking logging off`() {
        val budget = HourlyEventBudget(limit = 1)
        assertTrue(budget.take(10 * hour).log)
        assertFalse(budget.take(10 * hour + 1).log)
        assertTrue(budget.take(hour).log)
    }
}
