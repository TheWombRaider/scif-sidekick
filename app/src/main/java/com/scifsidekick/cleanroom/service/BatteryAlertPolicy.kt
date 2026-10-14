package com.scifsidekick.cleanroom.service

/** Valid ranges for the battery-alert settings. Enforced where the values are used, not when typed. */
object BatteryAlertLimits {
    val THRESHOLD = 5..50
    val STEP = 1..25
    val COUNT = 1..10
}

/** What [evaluateBatteryAlert] decided. [alertNumber] is 1-based and 0 when nothing is sent. */
data class BatteryAlertDecision(
    val send: Boolean,
    val alertNumber: Int,
    val newFiredCount: Int,
)

/**
 * The levels alerts fire at, deepest last: `threshold - k * step` for each of [maxCount] alerts,
 * with every input clamped into [BatteryAlertLimits] and any level below 1% dropped.
 */
fun batteryAlertLevels(
    thresholdPercent: Int,
    stepPercent: Int,
    maxCount: Int,
): List<Int> {
    val threshold = thresholdPercent.coerceIn(BatteryAlertLimits.THRESHOLD)
    val step = stepPercent.coerceIn(BatteryAlertLimits.STEP)
    val count = maxCount.coerceIn(BatteryAlertLimits.COUNT)
    return (0 until count).map { threshold - it * step }.filter { it >= 1 }
}

/**
 * Decides whether a battery alert is owed.
 *
 * - Above the threshold: nothing is sent and the fired count resets. This is the only reset, so
 *   plugging in at a low level and unplugging again cannot repeat an alert already sent.
 * - Charging: nothing is sent and the count is kept.
 * - Otherwise the deepest level at or above the current one is due. It is sent once, and a fast
 *   drop past several levels sends one alert, not one per level skipped.
 */
fun evaluateBatteryAlert(
    level: Int,
    charging: Boolean,
    thresholdPercent: Int,
    stepPercent: Int,
    maxCount: Int,
    firedCount: Int,
): BatteryAlertDecision {
    val levels = batteryAlertLevels(thresholdPercent, stepPercent, maxCount)
    if (levels.isEmpty() || level > levels.first()) return BatteryAlertDecision(false, 0, 0)
    if (charging) return BatteryAlertDecision(false, 0, firedCount)
    val due = levels.indexOfLast { level <= it }
    return if (due >= firedCount) {
        BatteryAlertDecision(true, due + 1, due + 1)
    } else {
        BatteryAlertDecision(false, 0, firedCount)
    }
}
