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
import com.scifsidekick.cleanroom.data.EventType
import java.util.concurrent.TimeUnit

/**
 * Gmail push (beta)'s `users.watch()` subscription expires after 7 days at the outside (Gmail's
 * own cap, not a value this app chooses). This is the daily keep-alive, mirroring
 * [WatchdogWorker]'s own periodic-job shape. A no-op whenever push isn't configured -- turning it
 * on/off, or never touching it at all, is exactly as safe as with [WatchdogWorker] itself.
 */
class GmailWatchRenewalWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = AppGraph.from(applicationContext)
        val settings = graph.repository.currentAppSettings()
        if (!settings.gmailPushEnabled || settings.pubsubTopicName.isBlank()) return Result.success()

        runCatching { graph.gmailPush.startWatch(settings.pubsubTopicName) }
            .onSuccess { expirationMs ->
                graph.database.stateDao().updateGmailWatchExpiration(expirationMs)
                graph.repository.recordEvent(EventType.PUSH_WATCH_RENEWED, "Gmail push watch renewed")
            }.onFailure { failure ->
                graph.repository.recordEvent(
                    EventType.PUSH_WATCH_FAILED,
                    "Gmail push watch renewal failed: ${(failure.message ?: failure.javaClass.simpleName).take(500)}",
                )
            }
        // Always success -- a failed renewal just means the push fast path keeps finding nothing
        // until tomorrow's retry or a manual "Test Push Setup," never something worth WorkManager
        // retrying more aggressively than that. The existing 30s Gmail poll is unaffected either way.
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "gmail-watch-renewal"

        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<GmailWatchRenewalWorker>(1, TimeUnit.DAYS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
