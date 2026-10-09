package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.email.BounceNotice
import com.scifsidekick.cleanroom.email.CommandScan
import com.scifsidekick.cleanroom.email.CommandSearch
import com.scifsidekick.cleanroom.email.MailAuthRequiredException
import com.scifsidekick.cleanroom.email.MailIds
import com.scifsidekick.cleanroom.email.MailMessage
import com.scifsidekick.cleanroom.email.MailPollResult
import com.scifsidekick.cleanroom.email.MailReceipt
import com.scifsidekick.cleanroom.email.MailTransport
import com.scifsidekick.cleanroom.email.MimeMessageBuilder
import com.scifsidekick.cleanroom.messaging.EmailPayload
import com.scifsidekick.cleanroom.util.RemoteCommandPlanner

/** In-memory [MailTransport] for tests. Behaves the way the interface's contract says. */
class FakeMailTransport(
    override val providerId: String = "fake",
    override val displayName: String = "Fake mail",
) : MailTransport {
    data class SentRecord(
        val deliveryKey: String,
        val payload: EmailPayload,
        val receipt: MailReceipt,
    )

    private class Stored(
        val message: MailMessage,
        var unread: Boolean = true,
    )

    @Volatile var signedIn: Boolean = true
    val sent = mutableListOf<SentRecord>()
    var failNextSendWith: Exception? = null

    /** Thrown by every [pollReplies] while set. */
    var pollFailure: Exception? = null

    /** Thrown by every [findSent] while set. */
    var findSentFailure: Exception? = null

    /** Returned as [CommandScan.unreadable] by [findCommands]. */
    val unreadableCommands = mutableListOf<String>()

    /** Every call made to this transport, in order: the method name, plus `:<id>` for [markRead] and [fetchContent]. */
    val calls = mutableListOf<String>()

    /** Number of [send] calls, including failed ones. */
    val sendCount: Int get() = calls.count { it == "send" }
    private val inbox = mutableListOf<Stored>()

    override val isAvailable: Boolean get() = signedIn

    override suspend fun accountEmail(): String? {
        calls += "accountEmail"
        return if (signedIn) "owner@fake.invalid" else null
    }

    override suspend fun send(
        payload: EmailPayload,
        attachmentPaths: List<String>,
        deliveryKey: String,
        verifyPriorDelivery: Boolean,
    ): MailReceipt {
        calls += "send"
        if (!signedIn) throw MailAuthRequiredException("Open the app and reconnect $displayName", providerId, displayName)
        failNextSendWith?.let {
            failNextSendWith = null
            throw it
        }
        val rfc = MimeMessageBuilder.rfcMessageId(deliveryKey)
        if (verifyPriorDelivery) {
            sent.firstOrNull { it.receipt.rfcMessageId == rfc }?.let { return it.receipt.copy(reconciled = true) }
        }
        val receipt =
            MailReceipt(
                messageId = MailIds.scoped(providerId, "fake-${sent.size + 1}"),
                threadId = MailIds.scoped(providerId, "thread-${sent.size + 1}"),
                rfcMessageId = rfc,
                reconciled = false,
            )
        sent += SentRecord(deliveryKey, payload, receipt)
        return receipt
    }

    override suspend fun findSent(deliveryKey: String): MailReceipt? {
        calls += "findSent"
        if (!signedIn) return null
        findSentFailure?.let { throw it }
        val rfc = MimeMessageBuilder.rfcMessageId(deliveryKey)
        return sent.firstOrNull { it.receipt.rfcMessageId == rfc }?.receipt?.copy(reconciled = true)
    }

    override suspend fun pollReplies(knownMessageIds: Set<String>): MailPollResult {
        calls += "pollReplies"
        if (!signedIn) return MailPollResult(emptyList(), emptyList())
        pollFailure?.let { throw it }
        return MailPollResult(inbox.filter { it.message.id !in knownMessageIds }.map { it.message }, emptyList())
    }

    override suspend fun findCommands(search: CommandSearch): CommandScan {
        calls += "findCommands"
        if (!signedIn) return CommandScan(emptyList(), emptyList())
        val found =
            inbox
                .asReversed()
                .filter { stored ->
                    stored.unread &&
                        search.tags.any { stored.message.subject.contains(it, ignoreCase = true) } &&
                        (search.senders.isEmpty() || stored.message.fromHeader.lowercase() in search.senders)
                }.take(RemoteCommandPlanner.MAX_CANDIDATES)
                .map { it.message }
        return CommandScan(found, unreadableCommands.toList())
    }

    override suspend fun fetchContent(message: MailMessage): MailMessage {
        calls += "fetchContent:${message.id}"
        val stored = inbox.firstOrNull { it.message.id == message.id }?.message ?: message
        return stored.copy(body = "body of ${message.id}")
    }

    override suspend fun markRead(messageId: String) {
        calls += "markRead:$messageId"
        inbox.firstOrNull { it.message.id == messageId }?.unread = false
    }

    override suspend fun checkForBounces(): List<BounceNotice> {
        calls += "checkForBounces"
        return emptyList()
    }

    override fun clearSession() {
        calls += "clearSession"
    }

    /**
     * Test control: put a message in the inbox and return its provider-scoped id (see [MailIds]).
     * [authenticatedFrom] is what this provider vouches for.
     */
    fun deliver(
        id: String,
        subject: String,
        from: String,
        authenticatedFrom: String? = null,
    ): String {
        val scopedId = MailIds.scoped(providerId, id)
        inbox +=
            Stored(
                MailMessage(
                    id = scopedId,
                    threadId = MailIds.scoped(providerId, "t-$id"),
                    subject = subject,
                    body = "",
                    referencedMessageIds = emptySet(),
                    rfcMessageId = "rfc-$id@fake.invalid",
                    fromHeader = from,
                    authenticatedFromAddress = authenticatedFrom,
                ),
            )
        return scopedId
    }
}
