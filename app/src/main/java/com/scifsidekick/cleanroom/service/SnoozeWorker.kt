package com.scifsidekick.cleanroom.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.scifsidekick.cleanroom.AppGraph
import java.util.concurrent.TimeUnit

/**
 * The scheduled re-enable behind "Snooze forwarding" (Home screen): a one-time job, not a bare
 * coroutine delay, specifically so a snooze survives the app process dying or the phone
 * rebooting during the snooze window -- a `delay()` inside a ViewModel or Service would not.
 *
 * [com.scifsidekick.cleanroom.data.SidekickRepository.setForwarding]'s own ON branch clears
 * `snoozedUntilMs` and cancels any still-pending snooze work, so this firing after the user
 * already manually re-enabled early is harmless (the unique work name means only one can ever be
 * pending at a time, and canceling on early re-enable stops this from double-logging anyway).
 */
class SnoozeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = AppGraph.from(applicationContext)
        graph.repository.setForwarding(true)
        runCatching { ForwardingService.start(applicationContext) }
        StatusWidgetProvider.requestUpdate(applicationContext)
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "snooze_reenable"

        fun schedule(
            context: Context,
            delayMs: Long,
        ) {
            val request =
                OneTimeWorkRequestBuilder<SnoozeWorker>()
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .build()
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
