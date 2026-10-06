package com.scifsidekick.cleanroom.messaging

data class IncomingMessage(
    val source: String,
    val senderAddress: String,
    val senderDisplay: String,
    val body: String,
    val receivedAtMs: Long,
    val sourceTimestampMs: Long = receivedAtMs,
    val participants: List<String> = emptyList(),
    val attachmentPaths: List<String> = emptyList(),
    val attachmentNotice: String? = null,
    val contentFingerprint: String? = null,
)

data class EmailPayload(
    val destinations: List<String>,
    val replyTarget: String?,
    val senderDisplay: String,
    val body: String,
    val receivedAtMs: Long,
    val source: String,
    val participants: List<String>,
    val attachmentNotice: String?,
    // Pre-rendered by the matching filter's subject/body template + find-and-replace rules at
    // enqueue time, so a later template edit never changes mail already queued or sent.
    val renderedSubject: String,
    val renderedBody: String,
    val filterName: String,
)

data class SmsReplyPayload(
    val targetNumber: String,
    val body: String,
    val gmailMessageId: String,
    // Who asked for this text and in which Gmail conversation, captured at enqueue time so the
    // confirmation sent once it reaches a terminal state can go back to that same person, in that
    // same thread. Nullable because a row queued by a version before confirmations existed has
    // neither -- such a row is simply never confirmed rather than confirmed to nobody.
    val initiatorAddress: String? = null,
    val initiatorThreadId: String? = null,
)

/** An email-triggered outbound picture message -- same idempotency/authorization story as
 *  [SmsReplyPayload], with the queued image file referenced through the same
 *  [com.scifsidekick.cleanroom.data.SendQueueEntity.attachmentPathsJson] column every other
 *  queued attachment already uses, not a new field of its own. */
data class MmsReplyPayload(
    val targetNumber: String,
    val body: String,
    val gmailMessageId: String,
    /** See [SmsReplyPayload.initiatorAddress]. */
    val initiatorAddress: String? = null,
    val initiatorThreadId: String? = null,
)
