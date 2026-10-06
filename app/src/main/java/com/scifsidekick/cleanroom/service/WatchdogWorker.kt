package com.scifsidekick.cleanroom.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.scifsidekick.cleanroom.AppGraph
import java.util.concurrent.TimeUnit

/**
 * A periodic (15-minute, WorkManager's own floor for periodic work) liveness check: if forwarding
 * is enabled but [ForwardingService]'s own heartbeat hasn't been written recently, the service has
 * most likely been killed outright by an aggressive OEM battery manager -- this restarts it.
 *
 * Message volume alone can't tell "the service died" apart from "nobody has texted in a while," so
 * this deliberately checks a heartbeat the service writes to itself once a minute while it's
 * actually running (see [com.scifsidekick.cleanroom.data.SidekickRepository.recordHeartbeat]),
 * not "when was the last thing forwarded."
 *
 * Scheduled once from [com.scifsidekick.cleanroom.ScifSidekickApp.onCreate] with
 * [ExistingPeriodicWorkPolicy.KEEP] -- WorkManager persists its own schedule across process death
 * and reboot on its own, so this never needs to be re-armed from [BootReceiver] too.
 */
class WatchdogWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = AppGraph.from(applicationContext)
        val state = graph.database.stateDao().get() ?: return Result.success()
        if (!state.enabled) return Result.success()

        val now = System.currentTimeMillis()
        val staleForMs = now - state.lastHeartbeatMs
        if (staleForMs >= STALE_THRESHOLD_MS) {
            graph.repository.recordServiceEvent(
                "Watchdog: no service heartbeat in ${staleForMs / 60_000}m while forwarding is on -- restarting it",
            )
            runCatching { ForwardingService.start(applicationContext) }
            StatusWidgetProvider.requestUpdate(applicationContext)
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "watchdog"

        // The service writes a heartbeat roughly once a minute while it's actually running (see
        // ForwardingService's HEARTBEAT_INTERVAL_MS); a gap this large only happens if it's
        // genuinely dead, never from ordinary tick jitter or this worker's own 15-minute floor.
        private const val STALE_THRESHOLD_MS = 10L * 60_000L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
