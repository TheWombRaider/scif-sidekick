package com.scifsidekick.cleanroom.email

/** One inbox message, as any [MailTransport] reports it. */
data class MailMessage(
    val id: String,
    val threadId: String,
    val subject: String,
    val body: String,
    val referencedMessageIds: Set<String>,
    val rfcMessageId: String,
    // The raw `From` header, exactly as the provider returned it -- may be a bare address or a
    // "Display Name <address>" form; see ComposeAuthorization.extractAddress.
    val fromHeader: String,
    // The sender address if, and only if, this provider's own authentication evidence vouches for
    // it. Null means "cannot be trusted as a command source".
    val authenticatedFromAddress: String?,
    // Body and image data are deliberately absent from the metadata poll. They are fetched only
    // after ForwardingService has verified this message's route and authenticated sender.
    val imageMimeType: String? = null,
    val imageBytes: ByteArray? = null,
)

/** [candidates] are newest first; [unreadable] are ids whose headers couldn't be fetched or parsed. */
data class CommandScan(
    val candidates: List<MailMessage>,
    val unreadable: List<String>,
)

data class MailPollResult(
    val replies: List<MailMessage>,
    val fetchFailures: List<String>,
)

data class MailReceipt(
    val messageId: String,
    val threadId: String,
    val rfcMessageId: String,
    val reconciled: Boolean,
)

/** One inbox message that looks like a delivery-status notification and quotes at least one of
 *  this installation's own RFC Message-IDs somewhere in its content -- see
 *  [MailTransport.checkForBounces]. */
data class BounceNotice(
    val messageId: String,
    val referencedRfcMessageIds: Set<String>,
    val summary: String,
)

/**
 * Which command mail to look for, independent of any provider's search syntax. [senders] are
 * canonical lowercase addresses and never empty (see RemoteCommandSearch.plan). Each transport
 * turns this into its own query.
 */
data class CommandSearch(
    val tags: List<String>,
    val senders: List<String>,
) {
    init {
        require(tags.isNotEmpty()) { "CommandSearch needs at least one tag" }
        require(senders.isNotEmpty()) { "CommandSearch needs at least one sender" }
    }
}
