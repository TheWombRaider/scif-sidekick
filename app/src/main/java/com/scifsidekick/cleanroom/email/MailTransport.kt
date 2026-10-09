package com.scifsidekick.cleanroom.email

import com.scifsidekick.cleanroom.messaging.EmailPayload

/**
 * One mail provider the app can send through and read from. Everything provider-specific -- search
 * syntax, history cursors, authentication headers, sign-in -- lives behind this interface.
 *
 * The one rule every implementation must keep: [MailMessage.authenticatedFromAddress] is set only
 * when this provider's own evidence shows the sender is who the `From` header claims (a DMARC pass
 * aligned with the `From` domain). Anything missing, unrecognized or ambiguous is null.
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

    /** New candidate replies, skipping [knownMessageIds]. Empty (not an error) when signed out. */
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
