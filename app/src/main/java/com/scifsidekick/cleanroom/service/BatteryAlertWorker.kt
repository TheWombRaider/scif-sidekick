package com.scifsidekick.cleanroom.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.scifsidekick.cleanroom.AppGraph
import com.scifsidekick.cleanroom.data.QueueChannel
import com.scifsidekick.cleanroom.util.PayloadCodec
import com.scifsidekick.cleanroom.util.suspendRunCatching
import java.util.concurrent.TimeUnit

/**
 * Emails a warning while the phone's battery is running down, before it dies.
 *
 * A phone with a dead battery forwards nothing and cannot say so, and the heartbeat only notices
 * after the fact: a missing message. This warns while there is still charge left to send it. The
 * thresholds come from the Settings screen; the decision itself is [evaluateBatteryAlert].
 *
 * Runs on the same 15-minute WorkManager floor as [HeartbeatEmailWorker] and, like it, does not
 * require forwarding to be on: a phone that is off the charger and forwarding nothing is still a
 * phone whose battery matters. Alerts go to the heartbeat recipients, so there is one list to keep.
 *
 * WorkManager may stretch a run under Doze or an OEM battery manager, so an alert can arrive later
 * than the crossing. The rule sends the alert for the deepest level crossed, so a late run still
 * says the right thing.
 */
class BatteryAlertWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = AppGraph.from(applicationContext)
        val settings = graph.repository.currentAppSettings()
        if (!settings.batteryAlertsEnabled) return Result.success()
        val recipients = PayloadCodec.pathsFromJson(settings.heartbeatRecipientsJson)
        if (recipients.isEmpty()) return Result.success()

        val battery = readBatteryReading(applicationContext) ?: return Result.success()
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
            return Result.success()
        }

        val now = System.currentTimeMillis()
        val totalAlerts = batteryAlertLevels(
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
            ) ?: return Result.success()

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
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "battery_alert"

        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<BatteryAlertWorker>(15, TimeUnit.MINUTES)
                    // Marking an alert as sent while offline would be a lie; the run happens when
                    // the network returns and then sends the right alert for the level it finds.
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
