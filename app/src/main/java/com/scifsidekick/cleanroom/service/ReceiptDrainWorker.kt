package com.scifsidekick.cleanroom.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.scifsidekick.cleanroom.AppGraph
import com.scifsidekick.cleanroom.data.QueueChannel
import com.scifsidekick.cleanroom.util.suspendRunCatching
import java.util.concurrent.TimeUnit

/**
 * Sends queued system emails (command receipts, status replies) outside any service's lifetime.
 *
 * The receipt for `[SCIF:OFF]` used to be drained from the reply poll inside [ForwardingService],
 * which stops itself on its next tick once forwarding is off and cancels that work with it. A
 * WorkManager job is not tied to the service, runs whether forwarding is on or off, and survives
 * process death. It retries a few times because a row released from an interrupted send waits out
 * a reconciliation delay before it is eligible again.
 */
class ReceiptDrainWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = AppGraph.from(applicationContext)
        graph.queueProcessor.releaseStaleEmailClaims()
        val failure = suspendRunCatching { graph.queueProcessor.drain(QueueChannel.EMAIL, 5) }.exceptionOrNull()
        if (failure != null) {
            graph.repository.recordServiceEvent(
                "Receipt could not be sent yet: " + (failure.message ?: failure.javaClass.simpleName).take(300),
            )
        }
        val stillQueued = graph.database.queueDao().queuedEmailCount() > 0
        return if ((failure != null || stillQueued) && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "receipt_drain"
        private const val MAX_ATTEMPTS = 6

        fun enqueue(context: Context) {
            val request =
                OneTimeWorkRequestBuilder<ReceiptDrainWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
