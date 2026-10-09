package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.email.BounceNotice
import com.scifsidekick.cleanroom.email.CommandScan
import com.scifsidekick.cleanroom.email.CommandSearch
import com.scifsidekick.cleanroom.email.MailAuthRequiredException
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
    private val inbox = mutableListOf<Stored>()

    override val isAvailable: Boolean get() = signedIn

    override suspend fun accountEmail(): String? = if (signedIn) "owner@fake.invalid" else null

    override suspend fun send(
        payload: EmailPayload,
        attachmentPaths: List<String>,
        deliveryKey: String,
        verifyPriorDelivery: Boolean,
    ): MailReceipt {
        if (!signedIn) throw MailAuthRequiredException("Open the app and reconnect $displayName")
        failNextSendWith?.let {
            failNextSendWith = null
            throw it
        }
        val rfc = MimeMessageBuilder.rfcMessageId(deliveryKey)
        if (verifyPriorDelivery) {
            sent.firstOrNull { it.receipt.rfcMessageId == rfc }?.let { return it.receipt.copy(reconciled = true) }
        }
        val receipt = MailReceipt(messageId = "fake-${sent.size + 1}", threadId = "thread-${sent.size + 1}", rfcMessageId = rfc, reconciled = false)
        sent += SentRecord(deliveryKey, payload, receipt)
        return receipt
    }

    override suspend fun pollReplies(knownMessageIds: Set<String>): MailPollResult {
        if (!signedIn) return MailPollResult(emptyList(), emptyList())
        return MailPollResult(inbox.filter { it.message.id !in knownMessageIds }.map { it.message }, emptyList())
    }

    override suspend fun findCommands(search: CommandSearch): CommandScan {
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
        return CommandScan(found, emptyList())
    }

    override suspend fun fetchContent(message: MailMessage): MailMessage = message.copy(body = "body of ${message.id}")

    override suspend fun markRead(messageId: String) {
        inbox.firstOrNull { it.message.id == messageId }?.unread = false
    }

    override suspend fun checkForBounces(): List<BounceNotice> = emptyList()

    override fun clearSession() = Unit

    /** Test control: put a message in the inbox. [authenticatedFrom] is what this provider vouches for. */
    fun deliver(
        id: String,
        subject: String,
        from: String,
        authenticatedFrom: String? = null,
    ) {
        inbox +=
            Stored(
                MailMessage(
                    id = id,
                    threadId = "t-$id",
                    subject = subject,
                    body = "",
                    referencedMessageIds = emptySet(),
                    rfcMessageId = "rfc-$id@fake.invalid",
                    fromHeader = from,
                    authenticatedFromAddress = authenticatedFrom,
                ),
            )
    }
}
