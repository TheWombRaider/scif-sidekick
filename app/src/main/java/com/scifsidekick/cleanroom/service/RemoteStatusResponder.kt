package com.scifsidekick.cleanroom.service

import com.scifsidekick.cleanroom.AppGraph
import com.scifsidekick.cleanroom.BuildConfig
import com.scifsidekick.cleanroom.data.AppSettingsEntity
import com.scifsidekick.cleanroom.data.EventType
import com.scifsidekick.cleanroom.data.QueueChannel
import com.scifsidekick.cleanroom.email.MailMessage
import com.scifsidekick.cleanroom.util.RemoteControlCodec
import com.scifsidekick.cleanroom.util.suspendRunCatching

/**
 * Answers a `[SCIF:STATUS]` query email.
 *
 * Shared by both sides on purpose. Unlike the two master-switch commands, a status query is
 * meaningful whether forwarding is on or off, so it is the one command both the worker and
 * [ForwardingService]'s reply poll have to be able to handle -- whichever of the two is alive in
 * the current state is the only one that can see the mail, and they are never both alive at once.
 * Keeping the authorization check, the reply body, and the "mark it read whatever happens"
 * handling in a single place is what stops those two paths drifting into answering the same
 * question with different rules.
 */
object RemoteStatusResponder {
    suspend fun answer(
        graph: AppGraph,
        candidate: MailMessage,
        settings: AppSettingsEntity,
        drainAfterQueueing: Boolean,
    ) {
        // Read before any early return: a rejected or disabled query must still be consumed, or it
        // sits unread and is re-evaluated on every single tick for the next two days.
        suspendRunCatching { graph.mail.markRead(candidate.id) }
        if (!settings.remoteControlEnabled) return

        val authorized =
            RemoteControlCodec.isAuthorized(
                settings.remoteControlSendersJson,
                candidate.authenticatedFromAddress,
                RemoteControlCodec.Sender::canStatus,
            )
        if (!authorized) {
            graph.repository.recordBlockedAttempt(
                "Blocked remote-status email command: sender " +
                    (candidate.authenticatedFromAddress ?: "could not be authenticated") +
                    " is not on the authorized list",
            )
            return
        }

        val now = System.currentTimeMillis()
        val summary = graph.statusSummary(now)
        val queued =
            graph.repository.enqueueSystemEmail(
                recipients = listOfNotNull(candidate.authenticatedFromAddress),
                subject = "SCIF Sidekick status",
                body =
                    "Status requested by email, answered by SCIF Sidekick ${BuildConfig.VERSION_NAME}.\n\n" +
                        summary,
                reason = "remote status reply",
                nowMs = now,
            )
        if (queued == null) return
        graph.repository.recordEvent(
            EventType.SERVICE,
            "Answered remote status request from ${candidate.authenticatedFromAddress}",
        )

        // Only the worker needs this. It runs when forwarding is off, and ForwardingService -- the
        // only thing that otherwise drains send_queue -- stops itself in that state before ever
        // reaching its drain calls, so an unanswered status reply would sit queued until forwarding
        // came back on. That is the same trap the heartbeat hit in 1.19.0. When the service is the
        // caller it is already draining on its own loop and a second drain here would be redundant.
        if (drainAfterQueueing) {
            suspendRunCatching { graph.queueProcessor.drain(QueueChannel.EMAIL, 5) }
                .onFailure { failure ->
                    graph.repository.recordServiceEvent(
                        "Status reply queued but could not be sent yet: " +
                            (failure.message ?: failure.javaClass.simpleName).take(300),
                    )
                }
        }
    }
}
