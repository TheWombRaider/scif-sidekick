package com.scifsidekick.cleanroom.service

import android.content.Context
import com.scifsidekick.cleanroom.AppGraph
import com.scifsidekick.cleanroom.BuildConfig
import com.scifsidekick.cleanroom.data.EventType
import com.scifsidekick.cleanroom.email.GmailReply
import com.scifsidekick.cleanroom.util.RemoteCommand

/**
 * Emails the sender a verification after an authorized `[SCIF:ON]` / `[SCIF:OFF]` command has
 * actually been applied.
 *
 * Sent only on success and only to the already-authenticated, already-authorized sender. A rejected
 * command gets no reply at all: answering an unauthorized address would confirm to a stranger that
 * the mailbox is live and runs this app, and a forged From would turn the reply into backscatter at
 * someone else. Rejections stay in the event log as SECURITY entries, as before.
 *
 * The body is built from [com.scifsidekick.cleanroom.data.SidekickRepository.buildStatusSummary]
 * after the switch was flipped, so the receipt reports what the database now says rather than what
 * the command asked for. Like the status reply it never includes message text, phone numbers, or
 * sender addresses beyond the recipient's own.
 */
object RemoteCommandReceipt {
    /** Whether the forwarding service could be started after an ON command, for the receipt text. */
    enum class ServiceStart { STARTED, FAILED }

    fun body(
        command: RemoteCommand,
        version: String,
        summary: String,
        serviceStart: ServiceStart? = null,
    ): String {
        val verb = if (command == RemoteCommand.ENABLE) "ENABLED" else "DISABLED"
        val tag = if (command == RemoteCommand.ENABLE) "ON" else "OFF"
        val serviceLine =
            when (serviceStart) {
                ServiceStart.FAILED ->
                    "\n\nWarning: Android would not start the forwarding service from the background. " +
                        "The switch is on and the watchdog will retry within about 15 minutes; " +
                        "opening the app starts it immediately."
                else -> ""
            }
        return "Your [SCIF:$tag] command was received and applied by SCIF Sidekick $version. " +
            "Forwarding is now $verb.$serviceLine\n\nCurrent state:\n$summary"
    }

    suspend fun send(
        context: Context,
        graph: AppGraph,
        command: RemoteCommand,
        candidate: GmailReply,
        serviceStart: ServiceStart? = null,
    ) {
        val recipient = candidate.authenticatedFromAddress ?: return
        val verb =
            when (command) {
                RemoteCommand.ENABLE -> "ENABLED"
                RemoteCommand.DISABLE -> "DISABLED"
                RemoteCommand.STATUS, RemoteCommand.HELP -> return
            }
        val now = System.currentTimeMillis()
        val summary = graph.repository.buildStatusSummary(graph.gmail.isAvailable, now)
        val queued =
            graph.repository.enqueueSystemEmail(
                recipients = listOf(recipient),
                subject = "SCIF Sidekick: forwarding $verb",
                body = body(command, BuildConfig.VERSION_NAME, summary, serviceStart),
                reason = "remote command receipt",
                nowMs = now,
            )
        if (queued == null) return
        graph.repository.recordEvent(EventType.SERVICE, "Sent $verb receipt to $recipient")

        // Sent by a WorkManager job, not inline: ON arrives with the service just being started and
        // OFF arrives from a service that stops itself on its next tick, so neither is a reliable
        // place to finish a network send. Nothing else would drain this while forwarding is off.
        ReceiptDrainWorker.enqueue(context)
    }
}
