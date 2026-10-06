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
import com.scifsidekick.cleanroom.BuildConfig
import com.scifsidekick.cleanroom.data.QueueChannel
import com.scifsidekick.cleanroom.util.PayloadCodec
import com.scifsidekick.cleanroom.util.suspendRunCatching
import java.util.concurrent.TimeUnit

/**
 * Sends an "I am still here" email on a fixed cadence.
 *
 * Every way this app currently reports trouble -- the circuit breaker opening, Gmail needing
 * reauthorization, a delivery needing review -- is an on-device notification posted by
 * [AlertNotifier], which is exactly useless in the situation this app is built for: the phone is
 * somewhere you are not. Worse, the failure that matters most is silent by construction. Gmail
 * authorization in a Testing-mode OAuth project expires on a schedule; when it does, forwarding
 * stops and *no* error email can be sent to tell you, because sending email is the thing that
 * broke.
 *
 * A heartbeat inverts that. The signal is not the mail arriving, it is the mail failing to. A
 * missing 08:00 heartbeat means something is wrong even when the app is in no position to say so,
 * which is the one thing a notification on a locked-away phone can never do.
 *
 * Runs on the same 15-minute WorkManager floor as [WatchdogWorker], [GmailWatchRenewalWorker] and
 * [RemoteEnableWorker], and does real work only on the tick where the configured interval has
 * actually elapsed. It deliberately does *not* require forwarding to be on: "forwarding is off"
 * is itself a thing worth learning from a distance, and a heartbeat that goes quiet whenever the
 * master switch flips would be indistinguishable from the app dying.
 */
class HeartbeatEmailWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = AppGraph.from(applicationContext)
        val settings = graph.repository.currentAppSettings()
        if (!settings.heartbeatEnabled) return Result.success()
        val recipients = PayloadCodec.pathsFromJson(settings.heartbeatRecipientsJson)
        if (recipients.isEmpty()) return Result.success()

        val now = System.currentTimeMillis()
        val intervalMs = settings.heartbeatIntervalHours.coerceAtLeast(1) * 60L * 60_000L
        val lastSent = graph.database.stateDao().get()?.lastHeartbeatEmailMs ?: 0L
        // 0 means one has never been queued, so the first eligible check sends immediately rather
        // than waiting a full interval to prove the feature works at all.
        if (lastSent > 0L && now - lastSent < intervalMs) return Result.success()

        // Not gated on gmail.isAvailable: an unauthorized Gmail is precisely the failure this
        // exists to surface, and the queued mail will go out the moment authorization returns
        // rather than being silently dropped now. The marker is only advanced once the row is
        // actually queued, so a refused enqueue retries on the next tick instead of skipping a
        // whole interval.
        val summary = graph.repository.buildStatusSummary(graph.gmail.isAvailable, now)
        val queueId =
            graph.repository.enqueueSystemEmail(
                recipients = recipients,
                subject = "SCIF Sidekick heartbeat — still running",
                body =
                    "This is SCIF Sidekick's scheduled heartbeat, sent every " +
                        "${settings.heartbeatIntervalHours} hour(s) from version ${BuildConfig.VERSION_NAME}.\n\n" +
                        summary +
                        "\n\nWhat matters about this message is that it arrived. If one of these stops " +
                        "showing up, treat that as the alert: the app cannot email you about a failure " +
                        "whose cause is that it can no longer send email.",
                reason = "scheduled heartbeat email",
                nowMs = now,
            ) ?: return Result.success()

        graph.repository.recordHeartbeatEmailSent(now)
        graph.repository.recordServiceEvent("Queued scheduled heartbeat email (queue #$queueId)")

        // Queuing alone is not enough when forwarding is off, which is one of the states this
        // heartbeat most needs to be able to report. ForwardingService is the only thing that
        // drains send_queue, and its loop stopSelf()s on finding the master switch off *before* it
        // reaches its drain calls -- so a row queued in that state would sit there until forwarding
        // came back on, i.e. exactly until it no longer mattered. Draining here is safe to do
        // concurrently with that service: claiming a row goes through the same single-winner
        // queueDao().claim() guard, so whichever gets there first sends it and the other skips it.
        suspendRunCatching { graph.queueProcessor.drain(QueueChannel.EMAIL, 5) }
            .onFailure { failure ->
                graph.repository.recordServiceEvent(
                    "Heartbeat email queued but could not be sent yet: " +
                        (failure.message ?: failure.javaClass.simpleName).take(300),
                )
            }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "heartbeat_email"

        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<HeartbeatEmailWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
