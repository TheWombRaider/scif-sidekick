package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.service.BatteryAlertDecision
import com.scifsidekick.cleanroom.service.batteryAlertLevels
import com.scifsidekick.cleanroom.service.evaluateBatteryAlert
import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryAlertPolicyTest {
    private fun eval(
        level: Int,
        fired: Int,
        charging: Boolean = false,
        threshold: Int = 20,
        step: Int = 5,
        max: Int = 3,
    ) = evaluateBatteryAlert(level, charging, threshold, step, max, fired)

    @Test fun `levels follow threshold step and count`() {
        assertEquals(listOf(20, 15, 10), batteryAlertLevels(20, 5, 3))
    }

    @Test fun `levels below one percent are dropped`() {
        assertEquals(listOf(5), batteryAlertLevels(5, 5, 3))
        assertEquals(listOf(7, 4, 1), batteryAlertLevels(7, 3, 5))
    }

    @Test fun `out of range settings are clamped when used`() {
        assertEquals(listOf(50, 49, 48), batteryAlertLevels(999, 0, 3))
        assertEquals(listOf(5), batteryAlertLevels(0, 99, 1))
        assertEquals(10, batteryAlertLevels(50, 1, 99).size)
    }

    @Test fun `above the threshold nothing is sent and the count resets`() {
        assertEquals(BatteryAlertDecision(false, 0, 0), eval(level = 21, fired = 2))
    }

    @Test fun `first crossing sends alert one`() {
        assertEquals(BatteryAlertDecision(true, 1, 1), eval(level = 20, fired = 0))
        assertEquals(BatteryAlertDecision(true, 1, 1), eval(level = 18, fired = 0))
    }

    @Test fun `no repeat while still inside the same band`() {
        assertEquals(BatteryAlertDecision(false, 0, 1), eval(level = 16, fired = 1))
    }

    @Test fun `each further step sends the next alert`() {
        assertEquals(BatteryAlertDecision(true, 2, 2), eval(level = 15, fired = 1))
        assertEquals(BatteryAlertDecision(true, 3, 3), eval(level = 10, fired = 2))
    }

    @Test fun `a drop past several levels sends one alert for the deepest level`() {
        assertEquals(BatteryAlertDecision(true, 3, 3), eval(level = 9, fired = 0))
        assertEquals(BatteryAlertDecision(true, 3, 3), eval(level = 2, fired = 1))
    }

    @Test fun `never more than the maximum count`() {
        assertEquals(BatteryAlertDecision(false, 0, 3), eval(level = 3, fired = 3))
        assertEquals(BatteryAlertDecision(false, 0, 3), eval(level = 1, fired = 3))
    }

    @Test fun `charging suppresses sending and keeps the count`() {
        assertEquals(BatteryAlertDecision(false, 0, 1), eval(level = 8, fired = 1, charging = true))
        assertEquals(BatteryAlertDecision(false, 0, 0), eval(level = 8, fired = 0, charging = true))
    }

    @Test fun `charging back above the threshold re-arms`() {
        assertEquals(BatteryAlertDecision(false, 0, 0), eval(level = 45, fired = 3, charging = true))
    }

    @Test fun `unplugging at the same low level does not alert again`() {
        // Alerts at 20 and 15 already went out; 12 is still inside the band of the second one.
        assertEquals(BatteryAlertDecision(false, 0, 2), eval(level = 12, fired = 2, charging = false))
    }

    @Test fun `lowering the maximum below the fired count never sends`() {
        assertEquals(BatteryAlertDecision(false, 0, 3), eval(level = 6, fired = 3, max = 2))
    }

    @Test fun `raising the threshold mid episode sends the newly due alert`() {
        assertEquals(BatteryAlertDecision(true, 2, 2), eval(level = 25, fired = 1, threshold = 30))
    }
}
