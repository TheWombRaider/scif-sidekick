package com.scifsidekick.cleanroom.email

import com.scifsidekick.cleanroom.messaging.EmailPayload

/**
 * One mail provider the app can send through and read from. Everything provider-specific -- search
 * syntax, history cursors, authentication headers, sign-in -- lives behind this interface.
 *
 * The one rule every implementation must keep: [MailMessage.authenticatedFromAddress] is set only
 * when this provider's own evidence shows the sender is who the `From` header claims (a DMARC pass
 * aligned with the `From` domain). Anything missing, unrecognized or ambiguous is null.
 *
 * Ids are scoped to the provider per [MailIds]: [MailMessage.id], [MailMessage.threadId],
 * [MailReceipt.messageId], [MailReceipt.threadId], [BounceNotice.messageId], and the ids passed to
 * [markRead] and [fetchContent] are unprefixed for Gmail and `<providerId>:<native id>` for any
 * other provider.
 */
interface MailTransport {
    /** Stable lowercase id: "gmail" now, "graph" later. Used as the message-id prefix, see [MailIds]. */
    val providerId: String

    /** Human-readable name for log and History text, for example "Gmail". */
    val displayName: String

    /** Whether this transport can currently be used (authorized, or the debug fake is on). */
    val isAvailable: Boolean

    /** The signed-in account's address, or null when signed out or it cannot be read. */
    suspend fun accountEmail(): String?

    /**
     * Sends [payload]. With [verifyPriorDelivery] the transport first checks whether a message
     * with this [deliveryKey] was already accepted and, if so, returns it with
     * [MailReceipt.reconciled] true instead of sending a duplicate.
     * @throws MailAuthRequiredException when the user must reconnect the account.
     */
    suspend fun send(
        payload: EmailPayload,
        attachmentPaths: List<String>,
        deliveryKey: String,
        verifyPriorDelivery: Boolean,
    ): MailReceipt

    /**
     * The already-accepted message for [deliveryKey], as a receipt with [MailReceipt.reconciled]
     * true, or null when nothing was accepted under that key. Never sends. Null (not an error) when
     * signed out.
     */
    suspend fun findSent(deliveryKey: String): MailReceipt?

    /**
     * New candidate replies, skipping [knownMessageIds]. Empty (not an error) when signed out.
     *
     * Must return messages whether or not they are read. Replying inside an already-open thread can
     * deliver a self-addressed reply that is already marked read, so an unread filter silently
     * misses it (no event, nothing queued, no error); that was a real bug. Volume is bounded by a
     * recency window plus [knownMessageIds], never by read state.
     */
    suspend fun pollReplies(knownMessageIds: Set<String>): MailPollResult

    /** Unread command mail matching [search], newest first. Empty (not an error) when signed out. */
    suspend fun findCommands(search: CommandSearch): CommandScan

    /** Fetches the body and image for an already-authorized [message]. */
    suspend fun fetchContent(message: MailMessage): MailMessage

    /** Marks a message read. Idempotent. */
    suspend fun markRead(messageId: String)

    /** Delivery-failure notices that quote one of this installation's own Message-IDs. */
    suspend fun checkForBounces(): List<BounceNotice>

    /** Drops any in-memory cursors or caches. Does not sign out. */
    fun clearSession()
}
