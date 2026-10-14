package com.scifsidekick.cleanroom.service

import android.content.Context
import com.scifsidekick.cleanroom.AppGraph
import com.scifsidekick.cleanroom.data.AppSettingsEntity
import com.scifsidekick.cleanroom.data.QueueChannel
import com.scifsidekick.cleanroom.util.suspendRunCatching

/**
 * Emails a warning while the phone's battery is running down, before it dies.
 *
 * A phone with a dead battery forwards nothing and cannot say so, and the heartbeat only notices
 * after the fact: a missing message. This warns while there is still charge left to send it. The
 * thresholds come from the Settings screen; the decision itself is [evaluateBatteryAlert].
 *
 * It is part of the heartbeat, not a job of its own: [HeartbeatEmailWorker] calls it on every tick,
 * so it shares the heartbeat's 15-minute schedule, its recipients and its status report. It does
 * not require forwarding to be on, since a phone that is off the charger and forwarding nothing is
 * still a phone whose battery matters.
 *
 * WorkManager may stretch a run under Doze or an OEM battery manager, so an alert can arrive later
 * than the crossing. The rule sends the alert for the deepest level crossed, so a late run still
 * says the right thing.
 */
object BatteryAlertCheck {
    suspend fun run(
        context: Context,
        graph: AppGraph,
        settings: AppSettingsEntity,
        recipients: List<String>,
    ) {
        if (!settings.batteryAlertsEnabled) return

        val battery = readBatteryReading(context) ?: return
        val fired = graph.database.stateDao().get()?.batteryAlertsFired ?: 0
        val decision =
            evaluateBatteryAlert(
                level = battery.percent,
                charging = battery.isCharging,
                thresholdPercent = settings.batteryAlertThresholdPercent,
                stepPercent = settings.batteryAlertStepPercent,
                maxCount = settings.batteryAlertMaxCount,
                firedCount = fired,
            )
        if (!decision.send) {
            if (decision.newFiredCount != fired) graph.repository.recordBatteryAlertsFired(decision.newFiredCount)
            return
        }

        val now = System.currentTimeMillis()
        val totalAlerts =
            batteryAlertLevels(
                settings.batteryAlertThresholdPercent,
                settings.batteryAlertStepPercent,
                settings.batteryAlertMaxCount,
            ).size
        val queueId =
            graph.repository.enqueueSystemEmail(
                recipients = recipients,
                subject = "SCIF Sidekick battery low - ${battery.percent}%",
                body =
                    "Battery alert ${decision.alertNumber} of $totalAlerts: this phone's battery is at " +
                        "${battery.percent}% and it is not charging. A phone that runs out of battery stops " +
                        "forwarding messages, so charge it or swap it soon.\n\n" +
                        graph.statusSummary(now),
                reason = "battery alert email",
                nowMs = now,
            ) ?: return

        // Advanced only once the row is queued, so a refused enqueue retries on the next tick
        // instead of using up an alert that never went out.
        graph.repository.recordBatteryAlertsFired(decision.newFiredCount)
        graph.repository.recordServiceEvent(
            "Queued battery alert ${decision.alertNumber} of $totalAlerts at ${battery.percent}% (queue #$queueId)",
        )

        // Same reasoning as the heartbeat: with forwarding off nothing else drains the queue, and an
        // alert that waits for forwarding to come back on has stopped being a warning.
        suspendRunCatching { graph.queueProcessor.drain(QueueChannel.EMAIL, 5) }
            .onFailure { failure ->
                graph.repository.recordServiceEvent(
                    "Battery alert queued but could not be sent yet: " +
                        (failure.message ?: failure.javaClass.simpleName).take(300),
                )
            }
    }
}
