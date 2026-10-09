package com.scifsidekick.cleanroom.service

import android.app.Activity
import androidx.room.withTransaction
import com.scifsidekick.cleanroom.data.AppSettingsEntity
import com.scifsidekick.cleanroom.data.DeliveryAttemptEntity
import com.scifsidekick.cleanroom.data.EventLogEntity
import com.scifsidekick.cleanroom.data.EventType
import com.scifsidekick.cleanroom.data.ForwardingStateEntity
import com.scifsidekick.cleanroom.data.PendingEmailRouteEntity
import com.scifsidekick.cleanroom.data.QueueChannel
import com.scifsidekick.cleanroom.data.QueueStatus
import com.scifsidekick.cleanroom.data.SendQueueEntity
import com.scifsidekick.cleanroom.data.SentEmailRouteEntity
import com.scifsidekick.cleanroom.data.SentGmailMessageEntity
import com.scifsidekick.cleanroom.data.SidekickDatabase
import com.scifsidekick.cleanroom.data.SidekickRepository
import com.scifsidekick.cleanroom.data.TelephonyPartResultEntity
import com.scifsidekick.cleanroom.email.MailAuthRequiredException
import com.scifsidekick.cleanroom.email.MailReceipt
import com.scifsidekick.cleanroom.email.MailTransport
import com.scifsidekick.cleanroom.messaging.EmailPayload
import com.scifsidekick.cleanroom.messaging.MmsGateway
import com.scifsidekick.cleanroom.messaging.PermanentDeliveryException
import com.scifsidekick.cleanroom.messaging.SimSelection
import com.scifsidekick.cleanroom.messaging.SmsGateway
import com.scifsidekick.cleanroom.util.AttachmentStore
import com.scifsidekick.cleanroom.util.ComposeAuthorization
import com.scifsidekick.cleanroom.util.Hashing
import com.scifsidekick.cleanroom.util.PayloadCodec
import kotlinx.coroutines.CancellationException
import kotlin.math.min

class QueueProcessor(
    private val db: SidekickDatabase,
    private val repository: SidekickRepository,
    private val limiter: RollingRateLimiter,
    private val mail: MailTransport,
    private val sms: SmsGateway,
    private val mms: MmsGateway,
    private val attachments: AttachmentStore,
    private val alerts: AlertNotifier,
) {
    private data class ClaimedDelivery(
        val item: SendQueueEntity,
        val attemptId: Long,
    )

    /** User-configurable message-content and diagnostic/route retention windows, replacing what
     *  were previously fixed constants -- see [AppSettingsEntity.messageRetentionDays]/
     *  [AppSettingsEntity.eventRetentionDays]'s own doc comment for why these two specifically
     *  (not every retention window here) were the ones opened up as a setting. A day count of 0
     *  or less means "keep forever": [Long.MAX_VALUE] as the window pushes every prune query's
     *  `timestampMs < now - window` cutoff to deep in the negative, which no real timestamp is
     *  ever less than, rather than needing a separate "pruning disabled" branch at every call
     *  site below. */
    private suspend fun retentionSettings(): RetentionWindows {
        val settings = db.appSettingsDao().get() ?: AppSettingsEntity()
        return RetentionWindows(
            messageMs = if (settings.messageRetentionDays <= 0) Long.MAX_VALUE else settings.messageRetentionDays * DAY_MS,
            eventMs = if (settings.eventRetentionDays <= 0) Long.MAX_VALUE else settings.eventRetentionDays * DAY_MS,
        )
    }

    private data class RetentionWindows(val messageMs: Long, val eventMs: Long)

    suspend fun recoverInterruptedWork() {
        val now = System.currentTimeMillis()
        val retention = retentionSettings()
        val releasedEmails = releaseStaleEmailClaims(now)
        val ambiguousTelephony = quarantineTimedOutTelephonyWork(now)
        val activeAttachments =
            db
                .queueDao()
                .activeAttachmentPathJsons()
                .flatMap { json -> runCatching { PayloadCodec.pathsFromJson(json) }.getOrDefault(emptyList()) }
                .toSet()
        val removedAttachments = attachments.pruneUnreferenced(activeAttachments, now - ORPHAN_ATTACHMENT_RETENTION_MS)
        mms.pruneTemporaryPdus(now - ORPHAN_ATTACHMENT_RETENTION_MS)
        db.deliveryAttemptDao().prune(now - ATTEMPT_RETENTION_MS)
        db.telephonyPartResultDao().prune(now - ATTEMPT_RETENTION_MS)
        db.eventLogDao().prune(now - retention.eventMs)
        db.queueDao().pruneSent(now - SENT_PAYLOAD_RETENTION_MS)
        db.queueDao().pruneDead(now - DEAD_WORK_RETENTION_MS)
        db.messageDao().pruneAll(now - retention.messageMs)
        db.processedReplyDao().prune(now - retention.eventMs)
        db.sentEmailRouteDao().prune(now - retention.eventMs)
        db.sentGmailMessageDao().prune(now - retention.eventMs)
        db.pendingEmailRouteDao().prune(now - retention.eventMs)

        val reason =
            if (releasedEmails > 0) {
                "Service restarted; released $releasedEmails interrupted email claim(s) for delayed ${mail.displayName} reconciliation. " +
                    "No message history was queried or backfilled."
            } else {
                "Service started. No message-history reconciliation was performed."
            }
        log(EventType.SERVICE, reason)
        if (removedAttachments > 0) {
            log(EventType.SERVICE, "Removed $removedAttachments unreferenced stale attachment file(s)")
        }
        if (ambiguousTelephony > 0) {
            log(
                EventType.SEND_FAILED,
                "$ambiguousTelephony interrupted SMS/MMS delivery outcome(s) require review; automatic resend was blocked to prevent duplicates",
            )
            alerts.showDeliveryReviewRequired(ambiguousTelephony)
        }
    }

    /**
     * Completes an SMS/MMS attempt only after Android invokes every sent-result callback. The
     * `(attemptId, partIndex)` primary key makes duplicate broadcasts harmless, while checking the
     * latest attempt prevents a delayed result from finalizing a newer retry.
     */
    suspend fun recordTelephonyResult(
        queueId: Long,
        attemptId: Long,
        partIndex: Int,
        partCount: Int,
        resultCode: Int,
    ) {
        var completedItem: SendQueueEntity? = null
        var needsAlert = false
        // Captured inside the transaction, acted on outside it: enqueuing the confirmation opens
        // its own transaction, and the terminal outcome must be durably committed first either way
        // -- a confirmation that fails to queue should never be able to roll back the record of the
        // send it was reporting on.
        var confirmation: Pair<SendQueueEntity, String>? = null
        db.withTransaction {
            val item = db.queueDao().get(queueId) ?: return@withTransaction
            if (item.status != QueueStatus.SENDING || item.channel !in setOf(QueueChannel.SMS, QueueChannel.MMS)) {
                return@withTransaction
            }
            if (db.deliveryAttemptDao().latestAttemptId(queueId) != attemptId) return@withTransaction
            val inserted =
                db.telephonyPartResultDao().insert(
                    TelephonyPartResultEntity(
                        attemptId = attemptId,
                        queueId = queueId,
                        partIndex = partIndex,
                        partCount = partCount,
                        resultCode = resultCode,
                        receivedAtMs = System.currentTimeMillis(),
                    ),
                )
            if (inserted == -1L) return@withTransaction
            val results = db.telephonyPartResultDao().forAttempt(attemptId)
            if (results.size < partCount) return@withTransaction
            val completeSet = results.size == partCount && results.map { it.partIndex }.toSet() == (0 until partCount).toSet()
            val consistent = results.all { it.queueId == queueId && it.partCount == partCount }
            if (!completeSet || !consistent) return@withTransaction

            // Activity.RESULT_OK (-1) is the documented success value for an SmsManager sentIntent
            // callback, and every genuine SmsManager.RESULT_ERROR_* failure code is >= 1 -- 0 itself
            // has no defined meaning in that vocabulary at all (it's Activity.RESULT_CANCELED,
            // PendingIntent's generic "nothing set a code" default). Confirmed on a physical device:
            // five real SMS segments were sent -- and received -- while every one of their sentIntent
            // callbacks reported resultCode 0, each one misread as a failure, each one triggering a
            // genuine duplicate retry send. Some OEM telephony stacks evidently report success as 0
            // instead of -1; treating a demonstrated non-failure code as ambiguous-but-not-a-failure
            // is safer than re-sending a real text on every such callback.
            if (results.all { it.resultCode == Activity.RESULT_OK || it.resultCode == 0 }) {
                val detail =
                    if (item.channel == QueueChannel.SMS) {
                        "Android telephony accepted all $partCount SMS segment(s)"
                    } else {
                        "Android telephony reported MMS submission success"
                    }
                db.deliveryAttemptDao().finish(attemptId, true, detail)
                db.queueDao().markSent(queueId)
                db.eventLogDao().insert(EventLogEntity(type = EventType.SENT, reason = detail, queueId = queueId))
                completedItem = item
                confirmation = item to CONFIRM_SENT
            } else {
                val codes = results.filter { it.resultCode != Activity.RESULT_OK }.map { it.resultCode }.distinct().joinToString()
                val detail = "${item.channel} sent-result callback failed (Android result code(s): $codes)"
                db.deliveryAttemptDao().finish(attemptId, false, detail)
                if (item.attemptCount + 1 >= MAX_TELEPHONY_ATTEMPTS) {
                    db.queueDao().markDead(queueId, detail)
                    needsAlert = true
                    // Only on the final attempt: a confirmation per intermediate retry would be
                    // noise, and the outcome isn't decided until the row is dead.
                    confirmation = item to detail
                } else {
                    val backoff = min(15L * 60_000L, 15_000L shl min(item.attemptCount, 5))
                    db.queueDao().retry(queueId, detail, System.currentTimeMillis() + backoff)
                }
                db.eventLogDao().insert(EventLogEntity(type = EventType.SEND_FAILED, reason = detail, queueId = queueId))
            }
        }
        completedItem?.let { cleanupAttachments(it) }
        confirmation?.let { (item, outcome) -> sendReplyConfirmation(item, outcome) }
        if (needsAlert) alerts.showDeliveryReviewRequired(1)
    }

    /**
     * Tells whoever sent the authorizing email whether the text they asked for actually went out.
     *
     * Without this, an email-initiated reply succeeds or dies in silence from the sender's point of
     * view: the outcome is recorded in `event_log`, the in-app History, and -- only after five
     * failed attempts -- an on-device notification, every one of which needs the phone that this
     * whole email-reply path exists to work without.
     *
     * Silently does nothing when the initiating address is unknown, which is the case for any row
     * queued before this feature existed (see [SmsReplyPayload.initiatorAddress]). The address is
     * never taken from the outcome path itself -- it is whatever was captured and authorized at
     * enqueue time -- so a confirmation can only ever go back to the sender who was already
     * authorized to cause the send.
     */
    private suspend fun sendReplyConfirmation(
        item: SendQueueEntity,
        outcome: String,
    ) {
        if (!repository.currentAppSettings().replyConfirmationsEnabled) return
        val payload =
            runCatching {
                when (item.channel) {
                    QueueChannel.SMS -> PayloadCodec.smsFromJson(item.payloadJson).let { it.initiatorAddress to it.targetNumber }
                    QueueChannel.MMS -> PayloadCodec.mmsFromJson(item.payloadJson).let { it.initiatorAddress to it.targetNumber }
                    else -> null
                }
            }.getOrNull() ?: return
        val (initiator, targetNumber) = payload
        if (initiator == null) return
        val succeeded = outcome == CONFIRM_SENT
        val label = if (item.channel == QueueChannel.MMS) "picture message" else "text"
        val subject =
            if (succeeded) "Delivered: your $label to $targetNumber" else "NOT delivered: your $label to $targetNumber"
        val body =
            if (succeeded) {
                "SCIF Sidekick handed your $label for $targetNumber to Android telephony and it was accepted.\n\n" +
                    "That confirms the phone sent it. It is not a read receipt, and carrier delivery to the " +
                    "recipient's handset is not something this app can observe."
            } else {
                "SCIF Sidekick could NOT send your $label to $targetNumber, after $MAX_TELEPHONY_ATTEMPTS attempts.\n\n" +
                    "Reason: $outcome\n\n" +
                    "It will not be retried automatically. Nothing was sent to $targetNumber."
            }
        repository.enqueueSystemEmail(
            recipients = listOf(initiator),
            subject = subject,
            body = body,
            reason = "reply confirmation for queue #${item.id}",
        )
    }

    private fun isSystemEmail(item: SendQueueEntity): Boolean =
        runCatching { PayloadCodec.emailFromJson(item.payloadJson).source == "system" }.getOrDefault(false)

    /** Puts email rows stuck in SENDING back in the queue for delayed provider reconciliation. A row
     *  with a delivery attempt in the last [ACTIVE_CLAIM_GRACE_MS] is left alone: it is being sent
     *  right now by another component (a worker, say) and is not interrupted. */
    suspend fun releaseStaleEmailClaims(now: Long = System.currentTimeMillis()): Int =
        db.queueDao().releaseInterruptedEmailClaims(now + RECONCILIATION_DELAY_MS, now - ACTIVE_CLAIM_GRACE_MS)

    suspend fun recoverTimedOutTelephonyWork() {
        val now = System.currentTimeMillis()
        releaseStaleEmailClaims(now)
        val retention = retentionSettings()
        val count = quarantineTimedOutTelephonyWork(now)
        if (count > 0) alerts.showDeliveryReviewRequired(count)
        val activeAttachments =
            db.queueDao().activeAttachmentPathJsons().flatMap(PayloadCodec::pathsFromJson).toSet()
        attachments.pruneUnreferenced(activeAttachments, now - ORPHAN_ATTACHMENT_RETENTION_MS)
        mms.pruneTemporaryPdus(now - ORPHAN_ATTACHMENT_RETENTION_MS)
        db.deliveryAttemptDao().prune(now - ATTEMPT_RETENTION_MS)
        db.telephonyPartResultDao().prune(now - ATTEMPT_RETENTION_MS)
        db.eventLogDao().prune(now - retention.eventMs)
        db.queueDao().pruneSent(now - SENT_PAYLOAD_RETENTION_MS)
        db.queueDao().pruneDead(now - DEAD_WORK_RETENTION_MS)
        db.messageDao().pruneAll(now - retention.messageMs)
        db.processedReplyDao().prune(now - retention.eventMs)
        db.sentEmailRouteDao().prune(now - retention.eventMs)
        db.sentGmailMessageDao().prune(now - retention.eventMs)
        db.pendingEmailRouteDao().prune(now - retention.eventMs)
    }

    private suspend fun quarantineTimedOutTelephonyWork(nowMs: Long): Int {
        val stale = db.queueDao().timedOutTelephonyClaims(nowMs - TELEPHONY_RESULT_TIMEOUT_MS)
        var count = 0
        stale.forEach { item ->
            db.withTransaction {
                if (db.queueDao().quarantineTimedOutTelephonyClaim(item.id) == 1) {
                    db.deliveryAttemptDao().latestAttemptId(item.id)?.let { attemptId ->
                        db.deliveryAttemptDao().finish(attemptId, false, "Timed out waiting for Android telephony sent-result callback")
                    }
                    db.eventLogDao().insert(
                        EventLogEntity(
                            type = EventType.SEND_FAILED,
                            reason = "${item.channel} result timed out; automatic retry blocked to prevent a duplicate",
                            queueId = item.id,
                        ),
                    )
                    count++
                }
            }
        }
        return count
    }

    suspend fun drain(
        channel: String,
        maxBatch: Int,
    ) {
        val settings = db.appSettingsDao().get()
        val softCap =
            when (channel) {
                QueueChannel.EMAIL -> settings?.softEmailPerMinuteCap ?: 0
                // Shares the SMS soft cap too, not a separate setting -- same reasoning as the
                // hard-limit tier sharing in RollingRateLimiter.
                QueueChannel.SMS, QueueChannel.MMS -> settings?.softSmsPerMinuteCap ?: 0
                else -> 0
            }
        repeat(maxBatch) {
            if (channel == QueueChannel.EMAIL) {
                if (db.stateDao().get()?.emailCircuitOpen == true) return
                if (!mail.isAvailable) return
            }
            val now = System.currentTimeMillis()
            val item = db.queueDao().nextReady(channel, now) ?: return
            val hardAllowedAt = limiter.nextAllowedAt(channel, now, systemMail = channel == QueueChannel.EMAIL && isSystemEmail(item))
            val softAllowedAt = softNextAllowedAt(channel, softCap, now)
            val allowedAt = maxOf(hardAllowedAt, softAllowedAt)
            if (allowedAt > now) {
                val reason =
                    if (softAllowedAt > hardAllowedAt) {
                        "$channel user-configured rate limit reached; queued until $allowedAt"
                    } else {
                        "$channel hard rate limit reached; queued until $allowedAt"
                    }
                db.withTransaction {
                    db.queueDao().defer(item.id, allowedAt)
                    db.eventLogDao().insert(EventLogEntity(type = EventType.RATE_LIMITED, reason = reason, queueId = item.id))
                }
                return
            }
            val claimed = claimAndReserve(item, now)
            if (claimed != null) deliver(claimed)
        }
    }

    /** Oldest-first shared drain so a busy SMS stream cannot consume the combined rate budget
     *  forever while an older MMS waits behind it. */
    suspend fun drainTelephony(maxBatch: Int) {
        val softCap = db.appSettingsDao().get()?.softSmsPerMinuteCap ?: 0
        repeat(maxBatch) {
            val now = System.currentTimeMillis()
            val item = db.queueDao().nextReadyTelephony(now) ?: return
            val hardAllowedAt = limiter.nextAllowedAt(item.channel, now)
            val softAllowedAt = softNextAllowedAt(item.channel, softCap, now)
            val allowedAt = maxOf(hardAllowedAt, softAllowedAt)
            if (allowedAt > now) {
                db.withTransaction {
                    db.queueDao().defer(item.id, allowedAt)
                    db.eventLogDao().insert(
                        EventLogEntity(
                            type = EventType.RATE_LIMITED,
                            reason = "Shared SMS/MMS rate limit reached; queued until $allowedAt",
                            queueId = item.id,
                        ),
                    )
                }
                return
            }
            val claimed = claimAndReserve(item, now)
            if (claimed != null) deliver(claimed)
        }
    }

    /** A user-adjustable ceiling *underneath* [limiter]'s non-configurable hard limits -- 0
     *  (the default) means no additional ceiling; the hard limits still always apply either way. */
    private suspend fun softNextAllowedAt(
        channel: String,
        capPerMinute: Int,
        nowMs: Long,
    ): Long {
        if (capPerMinute <= 0) return nowMs
        val since = nowMs - 60_000L
        val telephony = channel == QueueChannel.SMS || channel == QueueChannel.MMS
        val count =
            if (telephony) db.deliveryAttemptDao().countTelephonySince(since) else db.deliveryAttemptDao().countSince(channel, since)
        if (count < capPerMinute) return nowMs
        val oldest =
            if (telephony) db.deliveryAttemptDao().oldestTelephonySince(since) else db.deliveryAttemptDao().oldestSince(channel, since)
        val oldestAttempt = oldest ?: nowMs
        return oldestAttempt + 60_000L + 1L
    }

    /** Claiming a row and recording its delivery attempt are one durable operation. If the process
     *  dies after this transaction, recovery has a timestamped attempt to quarantine/reconcile;
     *  it can never leave an invisible SENDING row behind. Rate checks run before this call so a
     *  deferred row does not create a fake delivery attempt. */
    private suspend fun claimAndReserve(
        item: SendQueueEntity,
        now: Long,
    ): ClaimedDelivery? =
        db.withTransaction {
            if (db.queueDao().claim(item.id) != 1) return@withTransaction null
            val attemptId =
                db.deliveryAttemptDao().insert(
                    DeliveryAttemptEntity(
                        channel = item.channel,
                        attemptedAtMs = now,
                        queueId = item.id,
                        succeeded = null,
                        detail = "Attempt started",
                    ),
                )
            db.eventLogDao().insert(
                EventLogEntity(type = EventType.SEND_ATTEMPT, reason = "${item.channel} send attempt", queueId = item.id),
            )
            ClaimedDelivery(item.copy(status = QueueStatus.SENDING), attemptId)
        }

    private suspend fun deliver(
        claimed: ClaimedDelivery,
    ) {
        val item = claimed.item
        val attemptId = claimed.attemptId
        val outcome =
            try {
                performDelivery(item, attemptId)
            } catch (required: MailAuthRequiredException) {
                pauseForAuthorization(item, attemptId, required)
                return
            } catch (permanent: PermanentDeliveryException) {
                recordPermanentFailure(item, attemptId, permanent)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                recordFailure(item, attemptId, failure)
                return
            }
        if (!outcome.awaitingTelephonyResult) recordSuccess(item, attemptId, outcome)
    }

    private suspend fun performDelivery(
        item: SendQueueEntity,
        attemptId: Long,
    ): DeliveryOutcome =
        when (item.channel) {
            QueueChannel.EMAIL -> {
                val payload = decodeEmailPayload(item)
                val attachmentPaths = decodeAttachmentPaths(item)
                val deliveryKey = "${item.id}:${item.createdAtMs}:${Hashing.sha256(item.payloadJson)}"
                val rfcMessageId = com.scifsidekick.cleanroom.email.MimeMessageBuilder.rfcMessageId(deliveryKey)
                val authorizedSenders = payload.destinations.mapNotNull(ComposeAuthorization::canonicalAddress).distinct()
                db.pendingEmailRouteDao().upsert(
                    PendingEmailRouteEntity(
                        queueId = item.id,
                        rfcMessageId = rfcMessageId,
                        targetNumber = payload.replyTarget,
                        authorizedReplySendersJson = PayloadCodec.pathsToJson(authorizedSenders),
                        createdAtMs = System.currentTimeMillis(),
                    ),
                )
                val receipt =
                    mail.send(
                        payload = payload,
                        attachmentPaths = attachmentPaths,
                        deliveryKey = deliveryKey,
                        verifyPriorDelivery = item.attemptCount > 0,
                    )
                DeliveryOutcome(
                    detail =
                        if (receipt.reconciled) {
                            "Previously accepted ${mail.displayName} message reconciled; duplicate send suppressed"
                        } else {
                            "${mail.displayName} message ${receipt.messageId} accepted"
                        },
                    emailPayload = payload,
                    emailReceipt = receipt,
                )
            }

            QueueChannel.SMS -> {
                val segments = sms.send(decodeSmsPayload(item), item.id, attemptId, outboundSubscriptionId())
                DeliveryOutcome(
                    detail = "SMS submitted to Android telephony as $segments segment(s); awaiting sent result",
                    awaitingTelephonyResult = true,
                )
            }

            QueueChannel.MMS -> {
                val imagePath =
                    decodeAttachmentPaths(item).firstOrNull()
                        ?: throw PermanentDeliveryException("Queued MMS has no attached image")
                mms.send(decodeMmsPayload(item), imagePath, item.id, attemptId, outboundSubscriptionId())
                DeliveryOutcome(
                    detail = "MMS submitted to Android telephony; awaiting sent result",
                    awaitingTelephonyResult = true,
                )
            }

            else -> throw PermanentDeliveryException("Unsupported queue channel ${item.channel}")
        }

    /** The user's configured SIM choice, passed down to the gateway to resolve. Reading it here
     *  rather than inside the gateway keeps database access off the telephony path and out of a
     *  non-suspending `send`. Falls back to the system default if settings can't be read at all,
     *  which is the same thing every prior release did unconditionally. */
    private suspend fun outboundSubscriptionId(): Int =
        db.appSettingsDao().get()?.outboundSubscriptionId ?: SimSelection.SYSTEM_DEFAULT

    private fun decodeEmailPayload(item: SendQueueEntity): EmailPayload =
        try {
            PayloadCodec.emailFromJson(item.payloadJson)
        } catch (failure: Exception) {
            throw PermanentDeliveryException("Invalid queued email payload: ${failure.message}")
        }

    private fun decodeSmsPayload(item: SendQueueEntity) =
        try {
            PayloadCodec.smsFromJson(item.payloadJson)
        } catch (failure: Exception) {
            throw PermanentDeliveryException("Invalid queued SMS payload: ${failure.message}")
        }

    private fun decodeMmsPayload(item: SendQueueEntity) =
        try {
            PayloadCodec.mmsFromJson(item.payloadJson)
        } catch (failure: Exception) {
            throw PermanentDeliveryException("Invalid queued MMS payload: ${failure.message}")
        }

    private fun decodeAttachmentPaths(item: SendQueueEntity): List<String> =
        try {
            PayloadCodec.pathsFromJson(item.attachmentPathsJson)
        } catch (failure: Exception) {
            throw PermanentDeliveryException("Invalid queued attachment metadata: ${failure.message}")
        }

    private suspend fun recordSuccess(
        item: SendQueueEntity,
        attemptId: Long,
        outcome: DeliveryOutcome,
    ) {
        db.withTransaction {
            db.deliveryAttemptDao().finish(attemptId, true, outcome.detail.take(500))
            db.queueDao().markSent(item.id)
            item.sourceMessageId?.let { db.messageDao().markForwarded(it) }
            val arrivedAtMs = item.sourceMessageId?.let { db.messageDao().get(it)?.receivedAtMs }
            val sentDetail =
                if (arrivedAtMs != null) {
                    "${outcome.detail} -- ${ForwardLatency.describe(System.currentTimeMillis() - arrivedAtMs)} after the message arrived"
                } else {
                    outcome.detail
                }
            if (item.channel == QueueChannel.EMAIL) {
                val state = db.stateDao().get() ?: ForwardingStateEntity()
                if (state.consecutiveEmailFailures != 0) {
                    db.stateDao().update(state.copy(consecutiveEmailFailures = 0, updatedAtMs = System.currentTimeMillis()))
                }
                val target = outcome.emailPayload?.replyTarget
                val receipt = outcome.emailReceipt
                if (receipt != null) {
                    db.sentGmailMessageDao().upsert(
                        SentGmailMessageEntity(
                            gmailMessageId = receipt.messageId,
                            queueId = item.id,
                            rfcMessageId = receipt.rfcMessageId,
                            sentAtMs = System.currentTimeMillis(),
                        ),
                    )
                }
                val authorizedSenders =
                    outcome.emailPayload?.destinations?.mapNotNull(ComposeAuthorization::canonicalAddress)?.distinct().orEmpty()
                if (target != null && receipt != null && authorizedSenders.isNotEmpty()) {
                    db.sentEmailRouteDao().upsert(
                        SentEmailRouteEntity(
                            queueId = item.id,
                            gmailMessageId = receipt.messageId,
                            gmailThreadId = receipt.threadId,
                            rfcMessageId = receipt.rfcMessageId,
                            targetNumber = target,
                            authorizedReplySendersJson = PayloadCodec.pathsToJson(authorizedSenders),
                            sentAtMs = System.currentTimeMillis(),
                        ),
                    )
                }
                db.pendingEmailRouteDao().delete(item.id)
            }
            db.eventLogDao().insert(
                EventLogEntity(type = EventType.SENT, reason = sentDetail, queueId = item.id),
            )
        }
        if (item.channel == QueueChannel.EMAIL || item.channel == QueueChannel.MMS) {
            // A single incoming message can fan out to more than one filter, each with its own
            // queue row referencing the same copied attachment file(s), and an MMS row's own
            // image file is stored through this exact same column -- see MmsReplyPayload's doc
            // comment. Only delete a path once no other still-active (QUEUED/SENDING) row
            // references it, so filter B isn't left pointing at a file filter A already deleted
            // after its own successful send.
            cleanupAttachments(item)
        }
    }

    private suspend fun cleanupAttachments(item: SendQueueEntity) {
        val paths = PayloadCodec.pathsFromJson(item.attachmentPathsJson)
        if (paths.isEmpty()) return
        val stillReferenced =
            db
                .queueDao()
                .activeAttachmentPathJsons()
                .flatMap { json -> runCatching { PayloadCodec.pathsFromJson(json) }.getOrDefault(emptyList()) }
                .toSet()
        val safeToDelete = paths.filterNot { it in stillReferenced }
        if (safeToDelete.isNotEmpty()) attachments.delete(safeToDelete)
    }

    private suspend fun pauseForAuthorization(
        item: SendQueueEntity,
        attemptId: Long,
        failure: Exception,
    ) {
        val detail = (failure.message ?: "${mail.displayName} authorization is required").take(1_000)
        db.withTransaction {
            db.deliveryAttemptDao().finish(attemptId, false, detail)
            db.queueDao().defer(item.id, System.currentTimeMillis() + AUTHORIZATION_RETRY_DELAY_MS)
            db.eventLogDao().insert(
                EventLogEntity(
                    type = EventType.AUTH_REQUIRED,
                    reason = "${mail.displayName} authorization requires user interaction; email queue paused",
                    queueId = item.id,
                ),
            )
        }
        alerts.showAuthorizationRequired()
    }

    private suspend fun recordPermanentFailure(
        item: SendQueueEntity,
        attemptId: Long,
        failure: PermanentDeliveryException,
    ) {
        val detail = (failure.message ?: "Permanent delivery failure").take(1_000)
        db.withTransaction {
            db.deliveryAttemptDao().finish(attemptId, false, detail)
            db.queueDao().markDead(item.id, detail)
            if (item.channel == QueueChannel.EMAIL) db.pendingEmailRouteDao().delete(item.id)
            item.sourceMessageId?.let { db.messageDao().markAttemptFailed(it, detail) }
            db.eventLogDao().insert(
                EventLogEntity(
                    type = EventType.SEND_FAILED,
                    reason = "${item.channel} permanently blocked: $detail",
                    queueId = item.id,
                ),
            )
        }
        alerts.showDeliveryReviewRequired(1)
    }

    private suspend fun recordFailure(
        item: SendQueueEntity,
        attemptId: Long,
        throwable: Exception,
    ) {
        val error = (throwable.message ?: throwable.javaClass.simpleName).take(1_000)
        val calculatedBackoff = min(15L * 60_000L, 15_000L shl min(item.attemptCount, 5))
        val backoff = if (item.channel == QueueChannel.EMAIL) maxOf(MIN_EMAIL_RETRY_DELAY_MS, calculatedBackoff) else calculatedBackoff
        var circuitOpened = false
        db.withTransaction {
            db.deliveryAttemptDao().finish(attemptId, false, error)
            db.queueDao().retry(item.id, error, System.currentTimeMillis() + backoff)
            item.sourceMessageId?.let { db.messageDao().markAttemptFailed(it, error) }
            db.eventLogDao().insert(
                EventLogEntity(type = EventType.SEND_FAILED, reason = "${item.channel} failed: $error", queueId = item.id),
            )
            if (item.channel == QueueChannel.EMAIL) {
                val state = db.stateDao().get() ?: ForwardingStateEntity()
                val failures = CircuitPolicy.failuresAfter(state.consecutiveEmailFailures, succeeded = false)
                circuitOpened = CircuitPolicy.isOpen(failures)
                db.stateDao().update(
                    state.copy(
                        consecutiveEmailFailures = failures,
                        emailCircuitOpen = circuitOpened,
                        updatedAtMs = System.currentTimeMillis(),
                    ),
                )
                if (circuitOpened) {
                    db.eventLogDao().insert(
                        EventLogEntity(
                            type = EventType.CIRCUIT_OPENED,
                            reason = "Circuit breaker tripped after $failures consecutive email failures; manual reset required",
                            queueId = item.id,
                        ),
                    )
                }
            }
        }
        if (circuitOpened) alerts.showCircuitBreaker()
    }

    private suspend fun log(
        type: String,
        reason: String,
    ) {
        db.eventLogDao().insert(EventLogEntity(type = type, reason = reason))
    }

    private data class DeliveryOutcome(
        val detail: String,
        val emailPayload: EmailPayload? = null,
        val emailReceipt: MailReceipt? = null,
        val awaitingTelephonyResult: Boolean = false,
    )

    private companion object {
        const val RECONCILIATION_DELAY_MS = 5L * 60_000L
        const val ACTIVE_CLAIM_GRACE_MS = 3L * 60_000L
        const val MIN_EMAIL_RETRY_DELAY_MS = 5L * 60_000L
        const val AUTHORIZATION_RETRY_DELAY_MS = 30_000L
        const val ORPHAN_ATTACHMENT_RETENTION_MS = 24L * 60L * 60_000L
        const val ATTEMPT_RETENTION_MS = 25L * 60L * 60_000L
        const val SENT_PAYLOAD_RETENTION_MS = 7L * 24L * 60L * 60_000L
        const val DEAD_WORK_RETENTION_MS = 30L * 24L * 60L * 60_000L
        const val DAY_MS = 24L * 60L * 60_000L
        const val TELEPHONY_RESULT_TIMEOUT_MS = 15L * 60_000L
        const val MAX_TELEPHONY_ATTEMPTS = 5

        /** Sentinel distinguishing the success case from a failure detail string in the
         *  confirmation the telephony-result path hands to [sendReplyConfirmation]. */
        private const val CONFIRM_SENT = "__sent__"
    }
}
