package com.scifsidekick.cleanroom.service

import com.scifsidekick.cleanroom.AppGraph
import com.scifsidekick.cleanroom.BuildConfig
import com.scifsidekick.cleanroom.data.AppSettingsEntity
import com.scifsidekick.cleanroom.data.EventType
import com.scifsidekick.cleanroom.data.QueueChannel
import com.scifsidekick.cleanroom.email.MailMessage
import com.scifsidekick.cleanroom.ui.CommandHelp
import com.scifsidekick.cleanroom.util.RemoteControlCodec
import com.scifsidekick.cleanroom.util.suspendRunCatching

/**
 * The text emailed back for `[SCIF:HELP]`, laid out like a Unix man page. Built from
 * [CommandHelp], the same data the Commands screen shows, so the two can't drift apart. Plain
 * text with hard-wrapped lines, because that is what a mail client shows in a monospace view and
 * what survives a forward.
 *
 * The subject it is sent under must never parse as a command, or replying to it could be read as
 * one -- see [SUBJECT].
 */
object RemoteHelpManual {
    const val SUBJECT = "SCIF Sidekick manual"
    private const val WIDTH = 72
    private const val INDENT = "    "

    fun body(version: String): String =
        buildString {
            section("NAME")
            line("SCIF Sidekick $version - email commands for a phone you can't touch")

            section("SYNOPSIS")
            CommandHelp.entries.forEach { line("Subject: ${it.subject}") }
            line("Subject: <anything> ${CommandHelp.REPLY_TAG_EXAMPLE}   (reply to a forwarded text)")

            section("DESCRIPTION")
            wrapped(
                "Email the Gmail account SCIF Sidekick watches, with a command in the subject line. " +
                    "The app picks it up, acts, and emails the answer back to you. Tags are not " +
                    "case-sensitive and can sit anywhere in the subject, so a Re: or Fwd: prefix is fine.",
            )

            section("COMMANDS")
            CommandHelp.entries.forEachIndexed { index, entry ->
                if (index > 0) append('\n')
                line(entry.subject)
                wrapped(entry.effect, depth = 2)
                wrapped("Reply: ${entry.reply}", depth = 2)
                wrapped(entry.permissionLine, depth = 2)
            }

            section("NOTES")
            CommandHelp.rules.forEach { wrapped("* $it", hanging = true) }

            section("DIAGNOSTICS")
            wrapped(
                "No reply at all usually means one of: remote control is switched off in Settings, " +
                    "the sender is not on the authorized list, the checkbox for that command is not " +
                    "ticked, or the subject carried two command tags. Rejected commands are logged " +
                    "in Activity on the phone.",
            )
            wrapped(
                "Send [SCIF:STATUS] to see whether the app is running and whether Gmail is still " +
                    "authorized.",
            )

            section("SEE ALSO")
            line("https://github.com/TheWombRaider/scif-sidekick")

            section("LICENSE")
            wrapped("BSD Zero Clause License (0BSD). Free to use, copy, modify and distribute, no attribution required.")
        }.trimEnd() + "\n"

    private fun StringBuilder.section(title: String) {
        if (isNotEmpty()) append('\n')
        append(title).append('\n')
    }

    private fun StringBuilder.line(text: String) {
        append(INDENT).append(text).append('\n')
    }

    /** Greedy word wrap to [WIDTH]; a [hanging] item indents its continuation lines two more spaces. */
    private fun StringBuilder.wrapped(
        text: String,
        depth: Int = 1,
        hanging: Boolean = false,
    ) {
        val first = INDENT.repeat(depth)
        val rest = if (hanging) "$first  " else first
        var current = first
        var currentHasWord = false
        for (word in text.split(' ')) {
            if (currentHasWord && current.length + 1 + word.length > WIDTH) {
                append(current).append('\n')
                current = rest
                currentHasWord = false
            }
            current += (if (currentHasWord) " " else "") + word
            currentHasWord = true
        }
        append(current).append('\n')
    }
}

/**
 * Answers a `[SCIF:HELP]` email with [RemoteHelpManual].
 *
 * Shaped like [RemoteStatusResponder] and shared by the same two callers for the same reason: it
 * is meaningful whether forwarding is on or off, so both the service's reply poll and the worker
 * have to be able to answer it. It needs no per-command permission, but the sender must be on the
 * authorized list: anyone else gets no reply, so a stranger can't use the app to learn that the
 * mailbox is live.
 */
object RemoteHelpResponder {
    suspend fun answer(
        graph: AppGraph,
        candidate: MailMessage,
        settings: AppSettingsEntity,
        drainAfterQueueing: Boolean,
    ) {
        // Read before any early return, for the same reason as the status responder.
        suspendRunCatching { graph.mail.markRead(candidate.id) }
        if (!settings.remoteControlEnabled) return

        if (!RemoteControlCodec.isListed(settings.remoteControlSendersJson, candidate.authenticatedFromAddress)) {
            graph.repository.recordBlockedAttempt(
                "Blocked remote-help email command: sender " +
                    (candidate.authenticatedFromAddress ?: "could not be authenticated") +
                    " is not on the authorized list",
            )
            return
        }

        val queued =
            graph.repository.enqueueSystemEmail(
                recipients = listOfNotNull(candidate.authenticatedFromAddress),
                subject = RemoteHelpManual.SUBJECT,
                body = RemoteHelpManual.body(BuildConfig.VERSION_NAME),
                reason = "remote help reply",
                nowMs = System.currentTimeMillis(),
            )
        if (queued == null) return
        graph.repository.recordEvent(
            EventType.SERVICE,
            "Answered remote help request from ${candidate.authenticatedFromAddress}",
        )

        // Only the worker needs this: with forwarding off, ForwardingService isn't there to drain
        // the queue. See RemoteStatusResponder for the full story.
        if (drainAfterQueueing) {
            suspendRunCatching { graph.queueProcessor.drain(QueueChannel.EMAIL, 5) }
                .onFailure { failure ->
                    graph.repository.recordServiceEvent(
                        "Help reply queued but could not be sent yet: " +
                            (failure.message ?: failure.javaClass.simpleName).take(300),
                    )
                }
        }
    }
}
