package com.scifsidekick.cleanroom.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

object QueueChannel {
    const val EMAIL = "EMAIL"
    const val SMS = "SMS"
    // An email-triggered outbound picture message. SMS and MMS share one combined telephony
    // accounting bucket; MMS-out is not a separate class of traffic with its own allowance.
    const val MMS = "MMS"
}

object QueueStatus {
    const val QUEUED = "QUEUED"
    const val SENDING = "SENDING"
    const val SENT = "SENT"
    const val DEAD = "DEAD"
}

object ContactFilterMode {
    const val OFF = "OFF"
    const val WHITELIST = "WHITELIST"
    const val BLACKLIST = "BLACKLIST"
}

object KeywordFilterMode {
    const val OFF = "OFF"
    const val MUST_CONTAIN = "MUST_CONTAIN"
    const val MUST_NOT_CONTAIN = "MUST_NOT_CONTAIN"
}

object FilterConditionMode {
    const val ALL = "ALL"
    const val CONDITIONS = "CONDITIONS"
}

object EventType {
    const val RECEIVED = "RECEIVED"
    const val SKIPPED = "SKIPPED"
    const val QUEUED = "QUEUED"
    const val SEND_ATTEMPT = "SEND_ATTEMPT"
    const val SENT = "SENT"
    const val SEND_FAILED = "SEND_FAILED"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val CIRCUIT_OPENED = "CIRCUIT_OPENED"
    const val CIRCUIT_RESET = "CIRCUIT_RESET"
    const val REPLY_DETECTED = "REPLY_DETECTED"
    const val SERVICE = "SERVICE"
    const val AUTH = "AUTH"
    const val AUTH_REQUIRED = "AUTH_REQUIRED"
    const val SECURITY = "SECURITY"
    // A forwarded email was accepted by the Gmail send API but a delivery-status notification
    // later reported the recipient's own mail server rejected or could not deliver it -- a real
    // failure mode "Gmail accepted the send" alone can never surface. See GmailGateway.checkForBounces.
    const val DELIVERY_BOUNCED = "DELIVERY_BOUNCED"
    // GmailWatchRenewalWorker successfully called users.watch(); ForwardingStateEntity.gmailWatchExpirationMs
    // was updated to the new expiration it returned.
    const val PUSH_WATCH_RENEWED = "PUSH_WATCH_RENEWED"
    // GmailWatchRenewalWorker's daily users.watch() call failed (bad topic name, missing IAM grant
    // on it, or a plain network error). The 30s Gmail poll is unaffected either way -- this only
    // means the push-pull fast path in ForwardingService keeps finding nothing until the next
    // day's retry or a manual "Test Push Setup."
    const val PUSH_WATCH_FAILED = "PUSH_WATCH_FAILED"
    // A Pub/Sub pull in ForwardingService's loop failed (subscription not configured, IAM grant
    // missing, network error). Logged at most once an hour, not once per pull attempt -- see
    // ForwardingService's lastPushFailureLogMs. Never blocks the existing 30s Gmail poll.
    const val PUSH_PULL_FAILED = "PUSH_PULL_FAILED"
}

@Entity(tableName = "forwarding_state")
data class ForwardingStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val enabled: Boolean = false,
    val watermarkMs: Long = Long.MAX_VALUE,
    val destinationEmail: String = "",
    val emailCircuitOpen: Boolean = false,
    val consecutiveEmailFailures: Int = 0,
    // Preserves pre-existing behavior for upgrading installs: MMS was always forwarded before
    // this toggle existed, so it defaults on. Call forwarding defaults on too, by request --
    // notification access is already required for RCS, so enabling it by default requests no
    // new permission.
    val mmsForwardingEnabled: Boolean = true,
    val callNotificationsEnabled: Boolean = true,
    val contactFilterMode: String = ContactFilterMode.OFF,
    val contactFilterNumbersJson: String = "[]",
    val updatedAtMs: Long = System.currentTimeMillis(),
    // Written by ForwardingService's own loop roughly once a minute while it's actually running
    // (not once per 5-second tick -- that would just be a write-amplification cost for no extra
    // signal). WatchdogWorker compares this against "now" to tell "the service died and needs
    // restarting" apart from "nothing has arrived to forward lately," which message volume alone
    // can't distinguish -- an idle inbox and a killed service look identical from the outside.
    val lastHeartbeatMs: Long = 0L,
    // Display-only hint for the Home screen ("Snoozed until 3:45 PM") -- the actual re-enable is
    // a scheduled WorkManager one-off job, not a value this field's own presence enforces. 0 means
    // not snoozed. Cleared back to 0 by whatever actually flips forwarding back on, whether that's
    // the snooze job firing or the user manually re-enabling early.
    val snoozedUntilMs: Long = 0L,
    // Epoch ms when the current Gmail users.watch() Pub/Sub subscription expires (Gmail caps this
    // at 7 days out from the call that created it). GmailWatchRenewalWorker compares this against
    // "now" the same way WatchdogWorker compares lastHeartbeatMs -- 0 means no watch has ever
    // succeeded. Display/diagnostic use only; a stale or expired watch never blocks forwarding,
    // it just means the push-pull fast path in ForwardingService stops finding anything and the
    // existing 30s Gmail poll silently remains the only path, exactly as if push were never set up.
    val gmailWatchExpirationMs: Long = 0L,
    // When the last heartbeat email was successfully queued. State, not a setting, so it lives
    // here rather than on app_settings and is deliberately not carried in backup/restore -- a
    // restored backup should not be able to convince the app it already sent today's heartbeat.
    // 0 means none has ever been queued, which makes the first eligible check send one.
    val lastHeartbeatEmailMs: Long = 0L,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Entity(
    tableName = "forwarded_messages",
    indices = [
        Index(value = ["senderAddress", "sourceTimestampMs", "bodyHash"], unique = true),
    ],
)
data class ForwardedMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    val senderAddress: String,
    val senderDisplay: String,
    val body: String,
    val bodyHash: String,
    val receivedAtMs: Long,
    val sourceTimestampMs: Long,
    val forwarded: Boolean = false,
    val sendAttemptCount: Int = 0,
    val lastError: String? = null,
    // Whitespace/case-normalized form of the body (see util.normalizeForDedupe), used only for
    // the cross-source duplicate comparison -- never for what actually gets forwarded -- so a
    // raw SMS body and a cleaned-up RCS notification for the same text still match.
    val dedupeText: String = "",
)

@Entity(
    tableName = "send_queue",
    indices = [
        Index(value = ["channel", "status", "notBeforeMs"]),
        Index(value = ["replyDedupeKey", "channel", "createdAtMs"]),
    ],
)
data class SendQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channel: String,
    val payloadJson: String,
    val attachmentPathsJson: String = "[]",
    val sourceMessageId: Long? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val notBeforeMs: Long = 0,
    val status: String = QueueStatus.QUEUED,
    val attemptCount: Int = 0,
    val lastError: String? = null,
    // SHA-256(targetNumber|normalized body), set only for SMS/MMS reply rows (see
    // SidekickRepository.enqueueReplyIfNew) -- lets a short window suppress a second outbound
    // text with the same destination and content even when it arrives under a genuinely
    // different Gmail message id (an email client silently re-sending the same reply produces
    // exactly this: distinct gmailMessageIds, identical target+body). Null for every other row.
    val replyDedupeKey: String? = null,
)

@Entity(tableName = "event_log", indices = [Index(value = ["timestampMs"])])
data class EventLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long = System.currentTimeMillis(),
    val type: String,
    val reason: String,
    val messageKey: String? = null,
    val queueId: Long? = null,
)

@Entity(tableName = "delivery_attempts", indices = [Index(value = ["channel", "attemptedAtMs"])])
data class DeliveryAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channel: String,
    val attemptedAtMs: Long,
    val queueId: Long,
    val succeeded: Boolean?,
    val detail: String,
)

@Entity(tableName = "processed_replies")
data class ProcessedReplyEntity(
    @PrimaryKey val gmailMessageId: String,
    val processedAtMs: Long,
    val targetNumber: String,
)

@Entity(
    tableName = "sent_email_routes",
    indices = [
        Index(value = ["gmailMessageId"], unique = true),
        Index(value = ["gmailThreadId", "targetNumber"]),
        Index(value = ["rfcMessageId"], unique = true),
    ],
)
data class SentEmailRouteEntity(
    @PrimaryKey val queueId: Long,
    val gmailMessageId: String,
    val gmailThreadId: String,
    val rfcMessageId: String,
    val targetNumber: String,
    val authorizedReplySendersJson: String,
    val sentAtMs: Long,
)

/** Every Gmail message emitted by this installation, including no-reply forwards. */
@Entity(
    tableName = "sent_gmail_messages",
    indices = [Index(value = ["queueId"], unique = true), Index(value = ["rfcMessageId"], unique = true)],
)
data class SentGmailMessageEntity(
    @PrimaryKey val gmailMessageId: String,
    val queueId: Long,
    val rfcMessageId: String,
    val sentAtMs: Long,
)

/**
 * A reply capability published before Gmail is called. This closes the interval in which Gmail
 * has accepted a forward but the process has not yet committed its returned message/thread ids.
 */
@Entity(
    tableName = "pending_email_routes",
    indices = [Index(value = ["rfcMessageId"], unique = true)],
)
data class PendingEmailRouteEntity(
    @PrimaryKey val queueId: Long,
    val rfcMessageId: String,
    val targetNumber: String?,
    val authorizedReplySendersJson: String,
    val createdAtMs: Long,
)

/** Durable, idempotent sent-result callbacks for one SMS segment or one MMS submission. */
@Entity(
    tableName = "telephony_part_results",
    primaryKeys = ["attemptId", "partIndex"],
    indices = [Index(value = ["queueId"])],
)
data class TelephonyPartResultEntity(
    val attemptId: Long,
    val queueId: Long,
    val partIndex: Int,
    val partCount: Int,
    val resultCode: Int,
    val receivedAtMs: Long,
)

/**
 * One forwarding rule. Replaces the pre-1.4.0 single global destination/MMS-toggle/call-toggle/
 * contact-filter fields on [ForwardingStateEntity] -- an incoming message is recorded once (see
 * [SidekickRepository.processIncoming]) and then evaluated against every enabled filter, each
 * queuing its own email when it matches. [ForwardingStateEntity] keeps owning only what stays
 * global and safety-critical: the master switch, the watermark, and the email circuit breaker.
 */
@Entity(tableName = "forwarding_filters")
data class ForwardingFilterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val includeSms: Boolean = true,
    val includeMms: Boolean = true,
    val includeRcs: Boolean = true,
    val includeCalls: Boolean = false,
    val recipientsJson: String = "[]",
    val conditionMode: String = FilterConditionMode.ALL,
    val contactMode: String = ContactFilterMode.OFF,
    val contactNumbersJson: String = "[]",
    val keywordMode: String = KeywordFilterMode.OFF,
    val keywordsJson: String = "[]",
    // When true, a message that looks like an OTP/security code bypasses this filter's contact
    // and keyword conditions (never its message-type or schedule gates) -- the same instinct as
    // never wanting to accidentally block a bank code because a number wasn't on an allow list.
    val alwaysAllowOtp: Boolean = true,
    val subjectTemplate: String = MessageTemplateDefaults.SUBJECT,
    val bodyTemplate: String = MessageTemplateDefaults.BODY,
    val replaceRulesJson: String = "[]",
    val scheduleEnabled: Boolean = false,
    val scheduleDaysMask: Int = 127,
    val scheduleStartMinute: Int = 0,
    val scheduleEndMinute: Int = 1440,
    val saveResults: Boolean = true,
    val sendResultNotifications: Boolean = false,
    // Mirrors "stop processing more rules" in an email-client filter chain: when this filter
    // both matches and actually queues a forward, no filter after it (by sortOrder) is
    // evaluated for the same message. Off by default so existing multi-filter setups keep
    // fanning out to every match, exactly as before this option existed.
    val stopOnMatch: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
)

object MessageTemplateDefaults {
    const val SUBJECT = "[{Reply Tag}] {Verb} from {Contact Name}"
    const val BODY = "From: {Incoming Number} ({Contact Name})\nReceived: {Received Time}\n\n{Message Body}"
}

/** Cross-cutting settings that apply to the whole app rather than to one filter. */
@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val appLockEnabled: Boolean = false,
    val fontScaleKey: String = "default",
    val duplicateSuppressionEnabled: Boolean = true,
    // A minute, not an hour: the same word twice inside an hour of ordinary conversation ("ok"
    // ... "ok") is common, not a duplicate notification repost. A short window still catches the
    // actual target -- an OS/app reposting the identical notification for the identical message.
    val duplicateWindowMinutes: Int = 1,
    // Soft ceilings a user can dial tighter than the non-configurable hard limits in
    // HardRateLimits; 0 means "no additional soft ceiling" (the hard limits still always apply).
    val softEmailPerMinuteCap: Int = 0,
    val softSmsPerMinuteCap: Int = 0,
    val retryOnNetworkReconnect: Boolean = true,
    // Superseded by remoteControlEnabled/remoteControlSendersJson below (version 18) -- kept
    // declared here only so the Room schema history stays intact for anyone upgrading from an
    // older version; MIGRATION_17_18 reads these once to seed the new unified list and nothing
    // reads them afterward. See RemoteControlCodec's own doc comment for the current model.
    val composeViaEmailEnabled: Boolean = false,
    val authorizedComposeSendersJson: String = "[]",
    // User-facing knobs over what were previously fixed constants in QueueProcessor -- how long
    // completed message payloads and diagnostic/route records are kept before being pruned. Kept
    // as whole days (not milliseconds) since that's the unit anyone would actually reason about
    // when choosing a value; QueueProcessor converts at the point of use. <= 0 means "keep
    // forever" (see QueueProcessor.retentionSettings) -- a real setting via the Settings screen's
    // own "Keep forever" switch, not something the user needs to fake with an enormous day count.
    val messageRetentionDays: Int = 30,
    val eventRetentionDays: Int = 90,
    // Gmail push (beta): off by default, exactly like composeViaEmailEnabled above -- an empty
    // topic/subscription name means push simply never activates and ForwardingService's 30s
    // Gmail poll remains the only reply-detection path, unchanged. Full Pub/Sub resource names
    // (e.g. "projects/P/topics/T", "projects/P/subscriptions/S"), not bare names -- see docs/DESIGN_NOTES.md
    // "Gmail push (beta)" for how these are created in Google Cloud Console.
    val gmailPushEnabled: Boolean = false,
    val pubsubTopicName: String = "",
    val pubsubSubscriptionName: String = "",
    // Superseded by remoteControlEnabled/remoteControlSendersJson below -- see the comment on
    // composeViaEmailEnabled above.
    val remoteEnableViaEmailEnabled: Boolean = false,
    val authorizedRemoteEnableSendersJson: String = "[]",
    // Superseded by remoteControlEnabled/remoteControlSendersJson below -- see the comment on
    // composeViaEmailEnabled above.
    val remoteDisableViaEmailEnabled: Boolean = false,
    val authorizedRemoteDisableSendersJson: String = "[]",
    // On by default, unlike the remote-command toggles above: this grants nobody anything and
    // widens no trust boundary -- it only tells whoever already sent an authorized reply whether
    // the text they asked for actually went out. Without it, an email reply sent from somewhere
    // the phone isn't reachable succeeds or dies in total silence; the outcome is recorded only in
    // event_log, the in-app History, and (after five failed attempts) an on-device notification --
    // all of which require the phone this feature exists to do without.
    val replyConfirmationsEnabled: Boolean = true,
    // An "I am still here" email on a fixed cadence, off by default. Every way this app reports
    // trouble -- circuit breaker open, Gmail reauthorization required, delivery review needed --
    // is an on-device notification (see AlertNotifier), which is useless in the one situation this
    // app is built for: the phone is somewhere you are not. A heartbeat inverts that. The useful
    // signal is not the mail arriving, it is the mail *failing to*: if Gmail authorization is what
    // broke, no error email can reach you either, but a missing 08:00 heartbeat still can.
    val heartbeatEnabled: Boolean = false,
    val heartbeatIntervalHours: Int = 24,
    val heartbeatRecipientsJson: String = "[]",
    // Superseded by remoteControlEnabled/remoteControlSendersJson below -- see the comment on
    // composeViaEmailEnabled above.
    val remoteStatusViaEmailEnabled: Boolean = false,
    val authorizedRemoteStatusSendersJson: String = "[]",
    // Which SIM outbound texts leave on. -1 (SimSelection.SYSTEM_DEFAULT) means "whatever Android's
    // own default SMS subscription is", which is exactly what this app did unconditionally before
    // the setting existed -- so the default value is the old code path, not a new one. A specific
    // id is honored only when SimSelection can confirm that subscription is still active; see its
    // doc comment for why the check is written as "confirm, or fall back" rather than "try it".
    val outboundSubscriptionId: Int = -1,
    // The unified remote-control-by-email allowlist -- see RemoteControlCodec's own doc comment
    // for the full model. On by default (personal, single-owner use); an address still has to be
    // deliberately added to remoteControlSendersJson before any command from it does anything, so
    // this switch alone never widens who can do what -- it only gates whether the feature answers
    // at all, as a single kill switch covering all four commands at once.
    val remoteControlEnabled: Boolean = true,
    val remoteControlSendersJson: String = "[]",
    val updatedAtMs: Long = System.currentTimeMillis(),
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Dao
interface FilterDao {
    @Query("SELECT * FROM forwarding_filters ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<ForwardingFilterEntity>>

    @Query("SELECT * FROM forwarding_filters ORDER BY sortOrder, id")
    suspend fun getAll(): List<ForwardingFilterEntity>

    @Query("SELECT * FROM forwarding_filters WHERE enabled = 1 ORDER BY sortOrder, id")
    suspend fun getEnabled(): List<ForwardingFilterEntity>

    @Query("SELECT * FROM forwarding_filters WHERE id = :id")
    suspend fun get(id: Long): ForwardingFilterEntity?

    @Insert
    suspend fun insert(filter: ForwardingFilterEntity): Long

    @Update
    suspend fun update(filter: ForwardingFilterEntity)

    @Query("DELETE FROM forwarding_filters WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM forwarding_filters")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM forwarding_filters")
    suspend fun count(): Int

    // Lets the live MMS receiver decide whether to copy attachment bytes to disk at all before
    // any filter has actually been evaluated -- if nothing enabled wants MMS right now, the
    // bytes never touch storage in the first place. The transactional per-filter check inside
    // processIncoming remains the sole authority; this is purely to avoid an unnecessary copy of
    // what may be sensitive media.
    @Query("SELECT EXISTS(SELECT 1 FROM forwarding_filters WHERE enabled = 1 AND includeMms = 1)")
    suspend fun anyEnabledWantsMms(): Boolean
}

@Dao
interface AppSettingsDao {
    @Query("SELECT * FROM app_settings WHERE id = 1")
    fun observe(): Flow<AppSettingsEntity?>

    @Query("SELECT * FROM app_settings WHERE id = 1")
    suspend fun get(): AppSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDefault(settings: AppSettingsEntity): Long

    @Update
    suspend fun update(settings: AppSettingsEntity)
}

@Dao
interface StateDao {
    @Query("SELECT * FROM forwarding_state WHERE id = 1")
    fun observe(): Flow<ForwardingStateEntity?>

    @Query("SELECT * FROM forwarding_state WHERE id = 1")
    suspend fun get(): ForwardingStateEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDefault(state: ForwardingStateEntity): Long

    @Update
    suspend fun update(state: ForwardingStateEntity)

    // A dedicated single-column update rather than a full read-modify-write of the entity --
    // ForwardingService calls this roughly once a minute purely as a liveness signal, and there's
    // no reason for that to race with or clobber a concurrent update to any other field.
    @Query("UPDATE forwarding_state SET lastHeartbeatMs = :nowMs WHERE id = 1")
    suspend fun updateHeartbeat(nowMs: Long)

    @Query("UPDATE forwarding_state SET snoozedUntilMs = :untilMs WHERE id = 1")
    suspend fun updateSnoozedUntil(untilMs: Long)

    // Same dedicated single-column shape as updateHeartbeat above, for the same reason: the
    // heartbeat worker writes only this field and must not clobber a concurrent change to the
    // master switch made by the very email command it might be reporting on.
    @Query("UPDATE forwarding_state SET lastHeartbeatEmailMs = :nowMs WHERE id = 1")
    suspend fun updateLastHeartbeatEmail(nowMs: Long)

    // Same dedicated single-column shape as updateHeartbeat -- GmailWatchRenewalWorker writes
    // this once a day (or on manual "Test Push Setup"), independent of anything else in the row.
    @Query("UPDATE forwarding_state SET gmailWatchExpirationMs = :expirationMs WHERE id = 1")
    suspend fun updateGmailWatchExpiration(expirationMs: Long)
}

/** Narrow projection for [MessageDao.crossSourceCandidates] -- just enough to run the
 *  sender-equivalence check against, without paying for the rest of a full row. */
data class CrossSourceCandidate(
    val senderAddress: String,
    val senderDisplay: String,
)

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: ForwardedMessageEntity): Long

    @Query("SELECT * FROM forwarded_messages WHERE id = :id")
    suspend fun get(id: Long): ForwardedMessageEntity?

    // Deliberately no sender filter in SQL -- see SidekickRepository.sendersLikelyMatch for why an
    // exact string comparison isn't reliable across the two delivery channels this is comparing
    // (a carrier SMS broadcast vs Google Messages' own notification), and does the actual
    // sender-equivalence check in Kotlin against this candidate list instead.
    @Query(
        "SELECT senderAddress, senderDisplay FROM forwarded_messages " +
            "WHERE source != :source AND dedupeText = :dedupeText " +
            "AND receivedAtMs BETWEEN :windowStartMs AND :windowEndMs",
    )
    suspend fun crossSourceCandidates(
        source: String,
        dedupeText: String,
        windowStartMs: Long,
        windowEndMs: Long,
    ): List<CrossSourceCandidate>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM forwarded_messages " +
            "WHERE source = :source AND senderAddress = :senderAddress AND dedupeText = :dedupeText " +
            "AND receivedAtMs BETWEEN :windowStartMs AND :windowEndMs)",
    )
    suspend fun hasIdenticalRecentMessage(
        source: String,
        senderAddress: String,
        dedupeText: String,
        windowStartMs: Long,
        windowEndMs: Long,
    ): Boolean

    @Query("UPDATE forwarded_messages SET forwarded = 1, lastError = NULL WHERE id = :id")
    suspend fun markForwarded(id: Long)

    @Query("UPDATE forwarded_messages SET sendAttemptCount = sendAttemptCount + 1, lastError = :error WHERE id = :id")
    suspend fun markAttemptFailed(
        id: Long,
        error: String,
    )

    @Query("UPDATE forwarded_messages SET body = '' WHERE id = :id")
    suspend fun redactBody(id: Long)

    @Query("DELETE FROM forwarded_messages WHERE forwarded = 1 AND receivedAtMs < :beforeMs")
    suspend fun pruneForwarded(beforeMs: Long): Int

    @Query("DELETE FROM forwarded_messages WHERE receivedAtMs < :beforeMs")
    suspend fun pruneAll(beforeMs: Long): Int

    // Powers "Export message log": one row per live message actually recorded in the requested
    // window, regardless of whether it ended up forwarded. `body` is already '' for any message
    // no matching filter chose to save (see processIncoming's redactBody call) -- export never
    // sees more than what's already retained at rest.
    @Query("SELECT * FROM forwarded_messages WHERE receivedAtMs BETWEEN :startMs AND :endMs ORDER BY receivedAtMs DESC")
    suspend fun forExport(
        startMs: Long,
        endMs: Long,
    ): List<ForwardedMessageEntity>
}

@Dao
interface QueueDao {
    @Insert
    suspend fun insert(item: SendQueueEntity): Long

    @Query(
        "SELECT * FROM send_queue WHERE channel = :channel AND status = 'QUEUED' AND notBeforeMs <= :now ORDER BY createdAtMs, id LIMIT 1",
    )
    suspend fun nextReady(
        channel: String,
        now: Long,
    ): SendQueueEntity?

    @Query(
        "SELECT * FROM send_queue WHERE channel IN ('SMS', 'MMS') AND status = 'QUEUED' " +
            "AND notBeforeMs <= :now ORDER BY createdAtMs, id LIMIT 1",
    )
    suspend fun nextReadyTelephony(now: Long): SendQueueEntity?

    @Query("UPDATE send_queue SET status = 'SENDING' WHERE id = :id AND status = 'QUEUED'")
    suspend fun claim(id: Long): Int

    @Query("SELECT * FROM send_queue WHERE id = :id")
    suspend fun get(id: Long): SendQueueEntity?

    @Query("UPDATE send_queue SET status = 'SENT', lastError = NULL WHERE id = :id")
    suspend fun markSent(id: Long)

    @Query(
        "UPDATE send_queue SET status = 'QUEUED', attemptCount = attemptCount + 1, lastError = :error, notBeforeMs = :notBefore WHERE id = :id",
    )
    suspend fun retry(
        id: Long,
        error: String,
        notBefore: Long,
    )

    @Query("UPDATE send_queue SET status = 'QUEUED', notBeforeMs = :notBefore WHERE id = :id")
    suspend fun defer(
        id: Long,
        notBefore: Long,
    )

    @Query("UPDATE send_queue SET status = 'DEAD', attemptCount = attemptCount + 1, lastError = :error WHERE id = :id")
    suspend fun markDead(
        id: Long,
        error: String,
    )

    @Query(
        "UPDATE send_queue SET status = 'QUEUED', attemptCount = attemptCount + 1, " +
            "lastError = 'Previous process stopped during delivery; remote reconciliation required', " +
            "notBeforeMs = :notBeforeMs WHERE status = 'SENDING' AND channel = 'EMAIL' " +
            "AND id NOT IN (SELECT queueId FROM delivery_attempts WHERE attemptedAtMs > :activeSinceMs)",
    )
    suspend fun releaseInterruptedEmailClaims(
        notBeforeMs: Long,
        activeSinceMs: Long,
    ): Int

    @Query("SELECT COUNT(*) FROM send_queue WHERE channel = 'EMAIL' AND status = 'QUEUED'")
    suspend fun queuedEmailCount(): Int

    @Query(
        "UPDATE send_queue SET status = 'DEAD', attemptCount = attemptCount + 1, " +
            "lastError = 'No telephony result callback arrived; automatic retry blocked to prevent a duplicate' " +
            "WHERE id = :queueId AND status = 'SENDING'",
    )
    suspend fun quarantineTimedOutTelephonyClaim(queueId: Long): Int

    @Query(
        "SELECT q.* FROM send_queue q JOIN delivery_attempts a ON a.queueId = q.id " +
            "WHERE q.status = 'SENDING' AND q.channel IN ('SMS', 'MMS') AND a.succeeded IS NULL " +
            "AND a.attemptedAtMs <= :beforeMs AND a.id = " +
            "(SELECT MAX(a2.id) FROM delivery_attempts a2 WHERE a2.queueId = q.id)",
    )
    suspend fun timedOutTelephonyClaims(beforeMs: Long): List<SendQueueEntity>

    @Query("SELECT COUNT(*) FROM send_queue WHERE status = 'QUEUED'")
    fun observeQueuedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM send_queue WHERE status = 'QUEUED'")
    suspend fun queuedCount(): Int

    @Query("SELECT COUNT(*) FROM send_queue WHERE status IN ('QUEUED', 'SENDING', 'DEAD')")
    suspend fun retainedWorkCount(): Int

    @Query(
        "SELECT COALESCE(SUM(LENGTH(CAST(payloadJson AS BLOB)) + LENGTH(CAST(attachmentPathsJson AS BLOB))), 0) " +
            "FROM send_queue WHERE status IN ('QUEUED', 'SENDING', 'DEAD')",
    )
    suspend fun retainedPayloadBytes(): Long

    @Query("SELECT COUNT(*) FROM send_queue WHERE channel = :channel AND status IN ('QUEUED', 'SENDING', 'DEAD')")
    suspend fun retainedChannelCount(channel: String): Int

    // EMAIL and MMS are the only two channels whose payload can carry a stored attachment path
    // (SMS is plain text) -- both must be covered here, not just EMAIL, or a still-queued MMS
    // row's image file is invisible to both the orphan-attachment pruning sweep and the
    // shared-attachment reference check in QueueProcessor.recordSuccess, and can be garbage
    // collected out from under a legitimately pending send.
    @Query("SELECT attachmentPathsJson FROM send_queue WHERE status IN ('QUEUED', 'SENDING') AND channel IN ('EMAIL', 'MMS')")
    suspend fun activeAttachmentPathJsons(): List<String>

    @Query("DELETE FROM send_queue WHERE status = 'SENT' AND createdAtMs < :beforeMs")
    suspend fun pruneSent(beforeMs: Long): Int

    @Query("DELETE FROM send_queue WHERE status = 'DEAD' AND createdAtMs < :beforeMs")
    suspend fun pruneDead(beforeMs: Long): Int

    // Makes every still-queued row immediately eligible again on network reconnect, without
    // bypassing either the hard or soft rate ceilings -- this only advances *when* a row is
    // reconsidered, never how many can actually send in a given window.
    @Query("UPDATE send_queue SET notBeforeMs = 0 WHERE status = 'QUEUED' AND notBeforeMs > :now")
    suspend fun resetQueuedNotBefore(now: Long): Int

    // Powers reply-send duplicate suppression: true when an SMS/MMS reply with this exact
    // target+body fingerprint was already queued within the window, regardless of its status
    // (QUEUED/SENDING/SENT/DEAD all count -- a duplicate that's still in flight or already
    // delivered is equally a reason not to queue a second one).
    @Query(
        "SELECT EXISTS(SELECT 1 FROM send_queue WHERE channel IN ('SMS', 'MMS') " +
            "AND replyDedupeKey = :key AND createdAtMs >= :windowStartMs)",
    )
    suspend fun hasRecentReplyDedupeKey(
        key: String,
        windowStartMs: Long,
    ): Boolean
}

@Dao
interface EventLogDao {
    @Insert
    suspend fun insert(event: EventLogEntity): Long

    @Query("SELECT * FROM event_log ORDER BY timestampMs DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<EventLogEntity>>

    // Every event a single processIncoming call produced, in the order they were logged --
    // powers the "why did/didn't my test message forward" trail shown after Send Test Message.
    @Query("SELECT * FROM event_log WHERE messageKey = :key ORDER BY id")
    suspend fun forKey(key: String): List<EventLogEntity>

    // Powers the Home screen's "Last forwarded" line -- a real send, not just an attempt, so a
    // string of failures never gets reported as recent activity.
    @Query("SELECT MAX(timestampMs) FROM event_log WHERE type = 'SENT'")
    fun observeLastSentAt(): Flow<Long?>

    // One-shot counterpart of observeLastSentAt for callers that aren't already collecting a
    // Flow -- the home-screen widget's RemoteViews refresh, which runs as a single suspend call
    // per update rather than staying subscribed between them.
    @Query("SELECT MAX(timestampMs) FROM event_log WHERE type = 'SENT'")
    suspend fun lastSentAtOnce(): Long?

    @Query("DELETE FROM event_log WHERE timestampMs < :beforeMs")
    suspend fun prune(beforeMs: Long): Int
}

@Dao
interface DeliveryAttemptDao {
    @Insert
    suspend fun insert(attempt: DeliveryAttemptEntity): Long

    @Query("UPDATE delivery_attempts SET succeeded = :succeeded, detail = :detail WHERE id = :id")
    suspend fun finish(
        id: Long,
        succeeded: Boolean,
        detail: String,
    )

    @Query("SELECT MAX(id) FROM delivery_attempts WHERE queueId = :queueId")
    suspend fun latestAttemptId(queueId: Long): Long?

    @Query("SELECT COUNT(*) FROM delivery_attempts WHERE channel = :channel AND attemptedAtMs > :sinceMs")
    suspend fun countSince(
        channel: String,
        sinceMs: Long,
    ): Int

    @Query("SELECT MIN(attemptedAtMs) FROM delivery_attempts WHERE channel = :channel AND attemptedAtMs > :sinceMs")
    suspend fun oldestSince(
        channel: String,
        sinceMs: Long,
    ): Long?

    @Query("SELECT COUNT(*) FROM delivery_attempts WHERE channel IN ('SMS', 'MMS') AND attemptedAtMs > :sinceMs")
    suspend fun countTelephonySince(sinceMs: Long): Int

    @Query("SELECT MIN(attemptedAtMs) FROM delivery_attempts WHERE channel IN ('SMS', 'MMS') AND attemptedAtMs > :sinceMs")
    suspend fun oldestTelephonySince(sinceMs: Long): Long?

    @Query("DELETE FROM delivery_attempts WHERE attemptedAtMs < :beforeMs")
    suspend fun prune(beforeMs: Long): Int
}

@Dao
interface ProcessedReplyDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(reply: ProcessedReplyEntity): Long

    // This ledger includes commands that were rejected as well as unread, non-command candidates.
    // Keeping it local means the latter remain unread and visible in Gmail, yet cannot consume every
    // future polling cycle just because their ordinary subject happened to contain "SCIF" or "TEXT".
    @Query("SELECT gmailMessageId FROM processed_replies WHERE processedAtMs >= :sinceMs")
    suspend fun idsSince(sinceMs: Long): List<String>

    @Query("DELETE FROM processed_replies WHERE processedAtMs < :beforeMs")
    suspend fun prune(beforeMs: Long): Int
}

@Dao
interface SentEmailRouteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(route: SentEmailRouteEntity)

    @Query("SELECT * FROM sent_email_routes WHERE gmailThreadId = :threadId AND targetNumber = :targetNumber")
    suspend fun routesForThread(
        threadId: String,
        targetNumber: String,
    ): List<SentEmailRouteEntity>

    @Query("SELECT * FROM sent_email_routes WHERE rfcMessageId = :rfcMessageId AND targetNumber = :targetNumber")
    suspend fun routesForReference(
        rfcMessageId: String,
        targetNumber: String,
    ): List<SentEmailRouteEntity>

    @Query("DELETE FROM sent_email_routes WHERE sentAtMs < :beforeMs")
    suspend fun prune(beforeMs: Long): Int

}

@Dao
interface SentGmailMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: SentGmailMessageEntity)

    @Query(
        "SELECT EXISTS(SELECT 1 FROM sent_gmail_messages " +
            "WHERE gmailMessageId = :gmailMessageId OR rfcMessageId = :rfcMessageId)",
    )
    suspend fun isOwnSentMessage(gmailMessageId: String, rfcMessageId: String): Boolean

    // Powers bounce detection: a delivery-status notification quotes the original message's own
    // Message-ID, so recovering which queued send that was is a lookup by rfcMessageId alone.
    @Query("SELECT queueId FROM sent_gmail_messages WHERE rfcMessageId = :rfcMessageId")
    suspend fun queueIdForRfcMessageId(rfcMessageId: String): Long?

    @Query("DELETE FROM sent_gmail_messages WHERE sentAtMs < :beforeMs")
    suspend fun prune(beforeMs: Long): Int
}

@Dao
interface PendingEmailRouteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(route: PendingEmailRouteEntity)

    @Query("SELECT * FROM pending_email_routes WHERE rfcMessageId = :rfcMessageId AND targetNumber = :targetNumber")
    suspend fun routesForReference(rfcMessageId: String, targetNumber: String): List<PendingEmailRouteEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM pending_email_routes WHERE rfcMessageId = :rfcMessageId)")
    suspend fun isOwnPendingMessage(rfcMessageId: String): Boolean

    @Query("DELETE FROM pending_email_routes WHERE queueId = :queueId")
    suspend fun delete(queueId: Long)

    @Query("DELETE FROM pending_email_routes WHERE createdAtMs < :beforeMs")
    suspend fun prune(beforeMs: Long): Int
}

@Dao
interface TelephonyPartResultDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(result: TelephonyPartResultEntity): Long

    @Query("SELECT * FROM telephony_part_results WHERE attemptId = :attemptId ORDER BY partIndex")
    suspend fun forAttempt(attemptId: Long): List<TelephonyPartResultEntity>

    @Query("DELETE FROM telephony_part_results WHERE receivedAtMs < :beforeMs")
    suspend fun prune(beforeMs: Long): Int
}

@Database(
    entities = [
        ForwardingStateEntity::class,
        ForwardedMessageEntity::class,
        SendQueueEntity::class,
        EventLogEntity::class,
        DeliveryAttemptEntity::class,
        ProcessedReplyEntity::class,
        SentEmailRouteEntity::class,
        SentGmailMessageEntity::class,
        PendingEmailRouteEntity::class,
        TelephonyPartResultEntity::class,
        ForwardingFilterEntity::class,
        AppSettingsEntity::class,
    ],
    version = 18,
    exportSchema = true,
)
abstract class SidekickDatabase : RoomDatabase() {
    abstract fun stateDao(): StateDao

    abstract fun messageDao(): MessageDao

    abstract fun queueDao(): QueueDao

    abstract fun eventLogDao(): EventLogDao

    abstract fun deliveryAttemptDao(): DeliveryAttemptDao

    abstract fun processedReplyDao(): ProcessedReplyDao

    abstract fun sentEmailRouteDao(): SentEmailRouteDao

    abstract fun sentGmailMessageDao(): SentGmailMessageDao

    abstract fun pendingEmailRouteDao(): PendingEmailRouteDao

    abstract fun telephonyPartResultDao(): TelephonyPartResultDao

    abstract fun filterDao(): FilterDao

    abstract fun appSettingsDao(): AppSettingsDao

    companion object {
        const val DB_NAME = "scif-sidekick-cleanroom-v1.db"

        @Volatile private var instance: SidekickDatabase? = null

        fun get(context: Context): SidekickDatabase =
            instance ?: synchronized(this) {
                instance ?: buildDatabase(context).also { instance = it }
            }

        private fun buildDatabase(context: Context): SidekickDatabase {
            val builder =
                Room
                    .databaseBuilder(
                        context.applicationContext,
                        SidekickDatabase::class.java,
                        DB_NAME,
                    ).addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                        MIGRATION_16_17,
                        MIGRATION_17_18,
                    )
            return builder.build()
        }

        private val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS sent_email_routes (" +
                            "queueId INTEGER NOT NULL, " +
                            "gmailMessageId TEXT NOT NULL, " +
                            "gmailThreadId TEXT NOT NULL, " +
                            "rfcMessageId TEXT NOT NULL, " +
                            "targetNumber TEXT NOT NULL, " +
                            "sentAtMs INTEGER NOT NULL, " +
                            "PRIMARY KEY(queueId))",
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_sent_email_routes_gmailMessageId " +
                            "ON sent_email_routes(gmailMessageId)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_sent_email_routes_gmailThreadId_targetNumber " +
                            "ON sent_email_routes(gmailThreadId, targetNumber)",
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_sent_email_routes_rfcMessageId " +
                            "ON sent_email_routes(rfcMessageId)",
                    )
                }
            }

        private val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE forwarding_state ADD COLUMN mmsForwardingEnabled INTEGER NOT NULL DEFAULT 1",
                    )
                    db.execSQL(
                        "ALTER TABLE forwarding_state ADD COLUMN callNotificationsEnabled INTEGER NOT NULL DEFAULT 1",
                    )
                    db.execSQL(
                        "ALTER TABLE forwarding_state ADD COLUMN contactFilterMode TEXT NOT NULL DEFAULT 'OFF'",
                    )
                    db.execSQL(
                        "ALTER TABLE forwarding_state ADD COLUMN contactFilterNumbersJson TEXT NOT NULL DEFAULT '[]'",
                    )
                }
            }

        // Corrects the launch default for installs that already ran the 2->3 migration above
        // before callNotificationsEnabled was decided to default on. A no-op for anyone who
        // migrated straight to version 4 (they already got the corrected default in that ALTER).
        private val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("UPDATE forwarding_state SET callNotificationsEnabled = 1 WHERE id = 1")
                }
            }

        // Introduces per-rule forwarding filters and app-wide settings (release 1.4.0). The old
        // single global destination/MMS/call/contact-filter columns on forwarding_state are left
        // in place, frozen and unread by the app from here on, rather than dropped -- avoiding a
        // destructive column-drop migration -- and are used here only to seed one filter so an
        // upgrading install keeps forwarding exactly what it forwarded before.
        private val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE forwarded_messages ADD COLUMN dedupeText TEXT NOT NULL DEFAULT ''")

                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS forwarding_filters (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "name TEXT NOT NULL, enabled INTEGER NOT NULL, sortOrder INTEGER NOT NULL, " +
                            "includeSms INTEGER NOT NULL, includeMms INTEGER NOT NULL, " +
                            "includeRcs INTEGER NOT NULL, includeCalls INTEGER NOT NULL, " +
                            "recipientsJson TEXT NOT NULL, conditionMode TEXT NOT NULL, " +
                            "contactMode TEXT NOT NULL, contactNumbersJson TEXT NOT NULL, " +
                            "keywordMode TEXT NOT NULL, keywordsJson TEXT NOT NULL, " +
                            "alwaysAllowOtp INTEGER NOT NULL, subjectTemplate TEXT NOT NULL, " +
                            "bodyTemplate TEXT NOT NULL, replaceRulesJson TEXT NOT NULL, " +
                            "scheduleEnabled INTEGER NOT NULL, scheduleDaysMask INTEGER NOT NULL, " +
                            "scheduleStartMinute INTEGER NOT NULL, scheduleEndMinute INTEGER NOT NULL, " +
                            "saveResults INTEGER NOT NULL, sendResultNotifications INTEGER NOT NULL, " +
                            "createdAtMs INTEGER NOT NULL, updatedAtMs INTEGER NOT NULL)",
                    )

                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS app_settings (" +
                            "id INTEGER PRIMARY KEY NOT NULL, appLockEnabled INTEGER NOT NULL, " +
                            "fontScaleKey TEXT NOT NULL, duplicateSuppressionEnabled INTEGER NOT NULL, " +
                            "duplicateWindowMinutes INTEGER NOT NULL, softEmailPerMinuteCap INTEGER NOT NULL, " +
                            "softSmsPerMinuteCap INTEGER NOT NULL, retryOnNetworkReconnect INTEGER NOT NULL, " +
                            "composeViaEmailEnabled INTEGER NOT NULL, updatedAtMs INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "INSERT OR IGNORE INTO app_settings (id, appLockEnabled, fontScaleKey, " +
                            "duplicateSuppressionEnabled, duplicateWindowMinutes, softEmailPerMinuteCap, " +
                            "softSmsPerMinuteCap, retryOnNetworkReconnect, composeViaEmailEnabled, updatedAtMs) " +
                            "VALUES (1, 0, 'default', 1, 1, 0, 0, 1, 0, ${System.currentTimeMillis()})",
                    )

                    db.query(
                        "SELECT destinationEmail, mmsForwardingEnabled, callNotificationsEnabled, " +
                            "contactFilterMode, contactFilterNumbersJson FROM forwarding_state WHERE id = 1",
                    ).use { cursor ->
                        if (cursor.moveToFirst()) {
                            val destination = cursor.getString(0).orEmpty()
                            val mmsEnabled = cursor.getInt(1)
                            val callsEnabled = cursor.getInt(2)
                            val contactMode = cursor.getString(3)?.takeIf(String::isNotBlank) ?: "OFF"
                            val contactNumbers = cursor.getString(4)?.takeIf(String::isNotBlank) ?: "[]"
                            val recipientsJson = if (destination.isBlank()) "[]" else JSONArray(listOf(destination)).toString()
                            val conditionMode = if (contactMode == "OFF") "ALL" else "CONDITIONS"
                            val now = System.currentTimeMillis()
                            db.execSQL(
                                "INSERT INTO forwarding_filters (name, enabled, sortOrder, includeSms, " +
                                    "includeMms, includeRcs, includeCalls, recipientsJson, conditionMode, " +
                                    "contactMode, contactNumbersJson, keywordMode, keywordsJson, alwaysAllowOtp, " +
                                    "subjectTemplate, bodyTemplate, replaceRulesJson, scheduleEnabled, " +
                                    "scheduleDaysMask, scheduleStartMinute, scheduleEndMinute, saveResults, " +
                                    "sendResultNotifications, createdAtMs, updatedAtMs) VALUES " +
                                    "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                arrayOf<Any>(
                                    "Default",
                                    1,
                                    0,
                                    1,
                                    mmsEnabled,
                                    1,
                                    callsEnabled,
                                    recipientsJson,
                                    conditionMode,
                                    contactMode,
                                    contactNumbers,
                                    "OFF",
                                    "[]",
                                    1,
                                    MessageTemplateDefaults.SUBJECT,
                                    MessageTemplateDefaults.BODY,
                                    "[]",
                                    0,
                                    127,
                                    0,
                                    1440,
                                    1,
                                    0,
                                    now,
                                    now,
                                ),
                            )
                        }
                    }
                }
            }

        // Adds the per-filter "stop processing further filters" checkbox (release 1.5.0).
        private val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE forwarding_filters ADD COLUMN stopOnMatch INTEGER NOT NULL DEFAULT 0")
                }
            }

        // Release 1.7.0: replaces the self-sent-only compose-new trust model with a plain
        // authorized-sender allow list (the connected Gmail account is frequently unreachable
        // from the phone's actual email client, so "email yourself" was never workable for the
        // intended use case), and corrects the duplicate-suppression window's default -- only
        // for installs that never changed it away from the old default, never overwriting a
        // value the user set deliberately.
        private val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE app_settings ADD COLUMN authorizedComposeSendersJson TEXT NOT NULL DEFAULT '[]'")
                    db.execSQL("UPDATE app_settings SET duplicateWindowMinutes = 1 WHERE id = 1 AND duplicateWindowMinutes = 60")
                }
            }

        val MIGRATION_7_8 =
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE sent_email_routes ADD COLUMN authorizedReplySendersJson TEXT NOT NULL DEFAULT '[]'")
                    // v8 stores only a SHA-256 dedupe key in this legacy-named column. Existing
                    // plaintext normalized bodies cannot be transformed with SQLite alone, so
                    // remove that redundant copy; the ordinary body/history column is preserved.
                    db.execSQL("UPDATE forwarded_messages SET dedupeText = ''")
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS sent_gmail_messages (" +
                            "gmailMessageId TEXT NOT NULL, queueId INTEGER NOT NULL, rfcMessageId TEXT NOT NULL, " +
                            "sentAtMs INTEGER NOT NULL, PRIMARY KEY(gmailMessageId))",
                    )
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sent_gmail_messages_queueId ON sent_gmail_messages(queueId)")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sent_gmail_messages_rfcMessageId ON sent_gmail_messages(rfcMessageId)")
                    db.execSQL(
                        "INSERT OR IGNORE INTO sent_gmail_messages (gmailMessageId, queueId, rfcMessageId, sentAtMs) " +
                            "SELECT gmailMessageId, queueId, rfcMessageId, sentAtMs FROM sent_email_routes",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS pending_email_routes (" +
                            "queueId INTEGER NOT NULL, rfcMessageId TEXT NOT NULL, targetNumber TEXT, " +
                            "authorizedReplySendersJson TEXT NOT NULL, createdAtMs INTEGER NOT NULL, PRIMARY KEY(queueId))",
                    )
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_pending_email_routes_rfcMessageId ON pending_email_routes(rfcMessageId)")
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS telephony_part_results (" +
                            "attemptId INTEGER NOT NULL, queueId INTEGER NOT NULL, partIndex INTEGER NOT NULL, " +
                            "partCount INTEGER NOT NULL, resultCode INTEGER NOT NULL, receivedAtMs INTEGER NOT NULL, " +
                            "PRIMARY KEY(attemptId, partIndex))",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_telephony_part_results_queueId ON telephony_part_results(queueId)")
                }
            }

        // Release 1.9.1: adds reply-send duplicate suppression. An outbound SMS/MMS reply was
        // observed reaching the recipient multiple times for what looked like a single user
        // reply -- the existing `processed_replies` unique-by-gmailMessageId guard is correct but
        // insufficient if more than one genuinely distinct Gmail message (a client-side retry
        // that produced separate sent messages, for instance) carries the same target number and
        // body. `replyDedupeKey` lets SidekickRepository.enqueueReplyIfNew/enqueueMmsReplyIfNew
        // catch that case too, reusing the same duplicateSuppressionEnabled/duplicateWindowMinutes
        // setting already governing incoming-message dedup.
        val MIGRATION_8_9 =
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE send_queue ADD COLUMN replyDedupeKey TEXT")
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_send_queue_replyDedupeKey_channel_createdAtMs " +
                            "ON send_queue(replyDedupeKey, channel, createdAtMs)",
                    )
                }
            }

        // Adds: a service liveness heartbeat and a display-only snooze marker on forwarding_state
        // (WatchdogWorker and the Home-screen snooze feature); user-configurable message/event
        // retention windows on app_settings, replacing what were previously fixed constants in
        // QueueProcessor.
        val MIGRATION_9_10 =
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE forwarding_state ADD COLUMN lastHeartbeatMs INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE forwarding_state ADD COLUMN snoozedUntilMs INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE app_settings ADD COLUMN messageRetentionDays INTEGER NOT NULL DEFAULT 30")
                    db.execSQL("ALTER TABLE app_settings ADD COLUMN eventRetentionDays INTEGER NOT NULL DEFAULT 90")
                }
            }

        // Adds: the Gmail watch() expiration watermark on forwarding_state (GmailWatchRenewalWorker),
        // and the opt-in Gmail push (beta) toggle + Pub/Sub topic/subscription resource names on
        // app_settings. All default off/empty -- an upgrading install keeps polling exactly as
        // before until the user deliberately sets push up (see docs/DESIGN_NOTES.md "Gmail push (beta)").
        val MIGRATION_10_11 =
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE forwarding_state ADD COLUMN gmailWatchExpirationMs INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE app_settings ADD COLUMN gmailPushEnabled INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE app_settings ADD COLUMN pubsubTopicName TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE app_settings ADD COLUMN pubsubSubscriptionName TEXT NOT NULL DEFAULT ''")
                }
            }

        // Adds: the opt-in "enable forwarding by email" toggle + its own authorized-sender
        // allowlist on app_settings, exactly the same shape as composeViaEmailEnabled /
        // authorizedComposeSendersJson above -- see RemoteEnableWorker's doc comment for why this
        // needs a dedicated toggle rather than reusing the compose one. Off/empty by default, so
        // an upgrading install authorizes nothing until the user deliberately opts in.
        val MIGRATION_11_12 =
            object : Migration(11, 12) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE app_settings ADD COLUMN remoteEnableViaEmailEnabled INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE app_settings ADD COLUMN authorizedRemoteEnableSendersJson TEXT NOT NULL DEFAULT '[]'")
                }
            }

        // Adds: the opt-in "disable forwarding by email" toggle + its own authorized-sender
        // allowlist, the counterpart to the 11->12 enable pair. Kept a separate toggle/allowlist
        // rather than widening the enable one, so upgrading cannot turn an existing authorization
        // to switch forwarding *on* into an authorization to switch it *off*. Off/empty by
        // default.
        val MIGRATION_12_13 =
            object : Migration(12, 13) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE app_settings ADD COLUMN remoteDisableViaEmailEnabled INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE app_settings ADD COLUMN authorizedRemoteDisableSendersJson TEXT NOT NULL DEFAULT '[]'")
                }
            }

        // Adds: reply confirmations, defaulted to 1 rather than 0. Every other opt-in added here
        // defaults off because it widens a trust boundary; this one widens none -- it only reports
        // an outcome back to someone who already proved they were authorized to cause it -- and an
        // upgrading install is better served knowing its replies landed than having to discover
        // the switch first.
        val MIGRATION_13_14 =
            object : Migration(13, 14) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE app_settings ADD COLUMN replyConfirmationsEnabled INTEGER NOT NULL DEFAULT 1")
                }
            }

        // Adds: the heartbeat email's opt-in, its cadence and its recipients on app_settings, plus
        // the "when did one last go out" marker on forwarding_state. The marker is state rather
        // than a setting deliberately -- see lastHeartbeatEmailMs' own comment -- so it sits on the
        // state table and stays out of backup/restore.
        val MIGRATION_14_15 =
            object : Migration(14, 15) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE app_settings ADD COLUMN heartbeatEnabled INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE app_settings ADD COLUMN heartbeatIntervalHours INTEGER NOT NULL DEFAULT 24")
                    db.execSQL("ALTER TABLE app_settings ADD COLUMN heartbeatRecipientsJson TEXT NOT NULL DEFAULT '[]'")
                    db.execSQL("ALTER TABLE forwarding_state ADD COLUMN lastHeartbeatEmailMs INTEGER NOT NULL DEFAULT 0")
                }
            }

        // Adds: the "[SCIF:STATUS]" query's own toggle and allowlist. A third independent pair
        // rather than a reuse of the enable/disable ones, for the same reason those two are
        // separate from each other -- see remoteStatusViaEmailEnabled's own comment. Off/empty by
        // default, so upgrading discloses nothing to anybody.
        val MIGRATION_15_16 =
            object : Migration(15, 16) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE app_settings ADD COLUMN remoteStatusViaEmailEnabled INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE app_settings ADD COLUMN authorizedRemoteStatusSendersJson TEXT NOT NULL DEFAULT '[]'")
                }
            }

        // Adds: the outbound SIM choice, defaulted to -1 (SimSelection.SYSTEM_DEFAULT). That value
        // is not a new behavior to opt out of -- it *is* the behavior every prior release had, so
        // an upgrading install keeps sending on exactly the line it sent on yesterday and nothing
        // about telephony changes until the setting is deliberately touched.
        val MIGRATION_16_17 =
            object : Migration(16, 17) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE app_settings ADD COLUMN outboundSubscriptionId INTEGER NOT NULL DEFAULT -1")
                }
            }

        // Adds the unified remote-control-by-email allowlist (see RemoteControlCodec), replacing
        // the four separate toggle+allowlist pairs above (compose/remote-enable/remote-disable/
        // remote-status). Those eight old columns are left in the table -- and in AppSettingsEntity
        // -- purely for schema history; nothing reads them after this migration runs.
        // remoteControlEnabled defaults to on (personal, single-owner use), but an upgrading
        // install never gains a *new* authorized address or capability it didn't already have: any
        // address already present in one of the four old lists, with that list's own toggle already
        // on, is carried forward with exactly the capability bit(s) it already had, and nothing
        // else. An address present in more than one old list keeps every capability it had across
        // all of them, merged into one row.
        val MIGRATION_17_18 =
            object : Migration(17, 18) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE app_settings ADD COLUMN remoteControlEnabled INTEGER NOT NULL DEFAULT 1")
                    db.execSQL("ALTER TABLE app_settings ADD COLUMN remoteControlSendersJson TEXT NOT NULL DEFAULT '[]'")
                    db.query(
                        "SELECT composeViaEmailEnabled, authorizedComposeSendersJson, " +
                            "remoteEnableViaEmailEnabled, authorizedRemoteEnableSendersJson, " +
                            "remoteDisableViaEmailEnabled, authorizedRemoteDisableSendersJson, " +
                            "remoteStatusViaEmailEnabled, authorizedRemoteStatusSendersJson " +
                            "FROM app_settings WHERE id = 1",
                    ).use { cursor ->
                        if (!cursor.moveToFirst()) return
                        // address (lowercased) -> [canCompose, canEnable, canDisable, canStatus]
                        val merged = LinkedHashMap<String, BooleanArray>()
                        fun grant(
                            enabled: Int,
                            sendersJson: String?,
                            bitIndex: Int,
                        ) {
                            if (enabled == 0 || sendersJson.isNullOrBlank()) return
                            runCatching { JSONArray(sendersJson) }.getOrNull()?.let { array ->
                                for (i in 0 until array.length()) {
                                    val address = array.optString(i).trim().lowercase()
                                    if (address.isEmpty()) continue
                                    merged.getOrPut(address) { BooleanArray(4) }[bitIndex] = true
                                }
                            }
                        }
                        grant(cursor.getInt(0), cursor.getString(1), 0)
                        grant(cursor.getInt(2), cursor.getString(3), 1)
                        grant(cursor.getInt(4), cursor.getString(5), 2)
                        grant(cursor.getInt(6), cursor.getString(7), 3)
                        if (merged.isNotEmpty()) {
                            val mergedJson =
                                JSONArray()
                                    .apply {
                                        merged.forEach { (address, bits) ->
                                            put(
                                                JSONObject().apply {
                                                    put("address", address)
                                                    put("canCompose", bits[0])
                                                    put("canEnable", bits[1])
                                                    put("canDisable", bits[2])
                                                    put("canStatus", bits[3])
                                                },
                                            )
                                        }
                                    }.toString()
                            db.execSQL("UPDATE app_settings SET remoteControlSendersJson = ? WHERE id = 1", arrayOf<Any>(mergedJson))
                        }
                    }
                }
            }
    }
}
