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
import com.scifsidekick.cleanroom.email.GmailReply
import com.scifsidekick.cleanroom.util.RemoteCommand
import com.scifsidekick.cleanroom.util.RemoteCommandPlanner
import com.scifsidekick.cleanroom.util.RemoteCommandQuery
import com.scifsidekick.cleanroom.util.RemoteCommands
import com.scifsidekick.cleanroom.util.suspendRunCatching
import java.util.concurrent.TimeUnit

/**
 * Turning forwarding back on from inside this app normally requires the app to already be
 * running -- exactly the thing that is *not* true at the one moment this exists to help with:
 * forwarding was left off, the phone itself is out of reach (locked away outside a SCIF, the
 * whole reason it can't just be walked over to), and [ForwardingService] only runs -- and only
 * polls Gmail at all -- while forwarding is already on (see that class's own loop, which
 * `stopSelf()`s the instant it finds the master switch off). So the ordinary 30-second reply poll
 * can never be the thing that turns it back on; nothing that depends on the service being alive
 * can be.
 *
 * This worker is the separate, always-scheduled path for exactly that: a 15-minute WorkManager
 * floor check (WorkManager's own minimum for periodic work, the same shape as [WatchdogWorker]
 * and [GmailWatchRenewalWorker]) that runs whether forwarding is on or off, and does real work
 * only on the rare tick where it's off, the feature has been deliberately turned on, and a real
 * command email is waiting. 15 minutes of worst-case latency is a real, disclosed trade-off --
 * true push would need a server component this single-user app doesn't have -- but it is
 * negligible next to the problem this solves: walking out of a SCIF to go get a phone.
 *
 * The trust boundary deliberately mirrors compose-new's own gate, not
 * [SidekickRepository.isAuthorizedReply]'s thread-matching one: there is no prior SMS thread to
 * authorize a bare command like this against, and requiring the trigger email come from the same
 * connected Gmail account ("email yourself") is routinely unworkable from inside a SCIF, which is
 * the entire reason this feature exists. Authorization runs through the same unified
 * [RemoteControlCodec] allowlist as every other remote command, checked against this specific
 * command's own capability bit -- so a user can authorize a colleague's work address for Enable
 * without also handing them Compose or Disable, or vice versa.
 */
class RemoteEnableWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = AppGraph.from(applicationContext)
        val state = graph.database.stateDao().get()
        // Already on: nothing to do, and ForwardingService's own 30s reply poll is the live path
        // from here on regardless of whether a stray "[SCIF:ON]" email is still sitting unread.
        // That poll is also where the "[SCIF:OFF]" counterpart is handled, for the symmetric
        // reason this worker exists at all -- see RemoteCommands' doc comment.
        if (state?.enabled == true) return Result.success()
        val settings = graph.repository.currentAppSettings()
        // The master switch gates both tags at once now; which specific address may actually act
        // on either one is checked per-command below, once the candidate's subject is parsed.
        if (!settings.remoteControlEnabled) return Result.success()
        val wantedTags = listOf(RemoteCommands.ENABLE_TAG, RemoteCommands.STATUS_TAG, RemoteCommands.HELP_TAG)
        if (!graph.gmail.isAvailable) return Result.success()
        // Searches only the allowlisted senders, so mail from anyone else never competes for a
        // slot; with nobody allowlisted there is nothing to act on.
        val query = RemoteCommandQuery.build(wantedTags, settings.remoteControlSendersJson) ?: return Result.success()

        val scan =
            suspendRunCatching { graph.gmail.findRemoteCommands(query) }
                .getOrElse { failure ->
                    graph.repository.recordServiceEvent(
                        "Remote command email check failed: ${(failure.message ?: failure.javaClass.simpleName).take(300)}",
                    )
                    return Result.success()
                }
        // A message whose headers couldn't be read must not be re-fetched every run.
        scan.unreadable.forEach { id -> suspendRunCatching { graph.gmail.markRead(id) } }

        // Gmail's own subject: search is a loose substring/word match, not an exact tag check --
        // the planner's parse is the real one. Anything that isn't a usable command (a near-miss,
        // or a subject carrying two different tags) is consumed rather than left to clog later
        // runs, and the first authorized enable command wins however much other mail is ahead of it.
        val steps =
            RemoteCommandPlanner.plan(
                scan.candidates.map { RemoteCommandPlanner.Candidate(it.id, it.subject, it.authenticatedFromAddress) },
                settings.remoteControlSendersJson,
            )
        val byId = scan.candidates.associateBy { it.id }
        for (step in steps) {
            val candidate = byId.getValue(step.candidate.id)
            when (step.action) {
                RemoteCommandPlanner.Action.CONSUME -> suspendRunCatching { graph.gmail.markRead(candidate.id) }
                RemoteCommandPlanner.Action.ANSWER_STATUS ->
                    RemoteStatusResponder.answer(graph, candidate, settings, drainAfterQueueing = true)
                RemoteCommandPlanner.Action.ANSWER_HELP ->
                    RemoteHelpResponder.answer(graph, candidate, settings, drainAfterQueueing = true)
                RemoteCommandPlanner.Action.REJECT -> {
                    graph.repository.recordBlockedAttempt(
                        "Blocked remote-enable email command: sender " +
                            (candidate.authenticatedFromAddress ?: "could not be authenticated") +
                            " is not on the authorized list",
                    )
                    suspendRunCatching { graph.gmail.markRead(candidate.id) }
                }
                RemoteCommandPlanner.Action.APPLY_ENABLE -> applyEnable(graph, candidate)
            }
        }
        return Result.success()
    }

    private suspend fun applyEnable(
        graph: AppGraph,
        candidate: GmailReply,
    ) {
        graph.repository.setForwarding(true)
        graph.repository.recordEvent(
            EventType.SERVICE,
            "Forwarding enabled remotely by email command from ${candidate.authenticatedFromAddress}",
        )
        // Same start-and-log shape as SnoozeWorker's own re-enable -- a start failure here still
        // leaves the master switch on for the next watchdog/app-open to pick up, rather than
        // silently reverting it the way the tile does (there is no UI surface here to show a
        // revert message to). The receipt says so instead of claiming the service is running.
        val startFailure = runCatching { ForwardingService.start(applicationContext) }.exceptionOrNull()
        if (startFailure != null) {
            graph.repository.recordServiceEvent(
                "Forwarding service could not be started after remote enable: " +
                    (startFailure.message ?: startFailure.javaClass.simpleName).take(300),
            )
        }
        suspendRunCatching { graph.gmail.markRead(candidate.id) }
        StatusWidgetProvider.requestUpdate(applicationContext)
        RemoteCommandReceipt.send(
            applicationContext,
            graph,
            RemoteCommand.ENABLE,
            candidate,
            if (startFailure == null) RemoteCommandReceipt.ServiceStart.STARTED else RemoteCommandReceipt.ServiceStart.FAILED,
        )
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "remote_enable_check"

        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<RemoteEnableWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
