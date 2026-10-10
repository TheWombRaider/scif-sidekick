package com.scifsidekick.cleanroom.data

import android.content.Context
import android.util.Patterns
import androidx.core.content.edit
import androidx.room.withTransaction
import com.scifsidekick.cleanroom.messaging.EmailPayload
import com.scifsidekick.cleanroom.messaging.IncomingMessage
import com.scifsidekick.cleanroom.messaging.MmsReplyPayload
import com.scifsidekick.cleanroom.messaging.SmsReplyPayload
import com.scifsidekick.cleanroom.util.ComposeAuthorization
import com.scifsidekick.cleanroom.util.FilterConditionEvaluator
import com.scifsidekick.cleanroom.util.Hashing
import com.scifsidekick.cleanroom.util.HourlyEventBudget
import com.scifsidekick.cleanroom.util.MessageTemplateEngine
import com.scifsidekick.cleanroom.util.MessageVariables
import com.scifsidekick.cleanroom.util.OtpDetector
import com.scifsidekick.cleanroom.util.PayloadCodec
import com.scifsidekick.cleanroom.util.PhoneNumbers
import com.scifsidekick.cleanroom.util.RemoteControlCodec
import com.scifsidekick.cleanroom.util.ReplaceRuleCodec
import com.scifsidekick.cleanroom.util.ReplaceRuleEngine
import com.scifsidekick.cleanroom.util.ScheduleWindow
import com.scifsidekick.cleanroom.util.WatermarkPolicy
import com.scifsidekick.cleanroom.util.normalizeForDedupe
import com.scifsidekick.cleanroom.util.suspendRunCatching
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SidekickRepository(
    private val context: Context,
    private val db: SidekickDatabase,
) {
    val state: Flow<ForwardingStateEntity?> = db.stateDao().observe()
    val recentEvents: Flow<List<EventLogEntity>> = db.eventLogDao().observeRecent()
    val queuedCount: Flow<Int> = db.queueDao().observeQueuedCount()
    val filters: Flow<List<ForwardingFilterEntity>> = db.filterDao().observeAll()
    val appSettings: Flow<AppSettingsEntity?> = db.appSettingsDao().observe()
    val lastSentAt: Flow<Long?> = db.eventLogDao().observeLastSentAt()

    suspend fun ensureInitialized() {
        db.stateDao().insertDefault(ForwardingStateEntity())
        db.appSettingsDao().insertDefault(AppSettingsEntity())
    }

    // ---------------------------------------------------------------- master switch / breaker

    suspend fun setForwarding(
        enabled: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        db.withTransaction {
            val current = db.stateDao().get() ?: ForwardingStateEntity()
            val next =
                if (enabled) {
                    // The watermark is advanced atomically on every OFF -> ON action. Nothing
                    // anywhere in this application queries Telephony history to catch up.
                    // snoozedUntilMs is cleared here too -- this is the one path every way of
                    // turning forwarding back on (the user directly, or SnoozeWorker firing)
                    // funnels through, so it's the one place that needs to clear the display hint.
                    current.copy(enabled = true, watermarkMs = nowMs, updatedAtMs = nowMs, snoozedUntilMs = 0L)
                } else {
                    current.copy(enabled = false, updatedAtMs = nowMs, snoozedUntilMs = 0L)
                }
            if (db.stateDao().get() == null) db.stateDao().insertDefault(next) else db.stateDao().update(next)
            logLocked(EventType.SERVICE, "Forwarding ${if (enabled) "enabled; watermark=$nowMs" else "disabled"}")
        }
    }

    /** Turns forwarding off now and records when it's scheduled to come back on -- the actual
     *  re-enable is a separate scheduled WorkManager job (see SnoozeWorker), not something reading
     *  this field enforces; [snoozedUntilMs] is a display-only hint for the Home screen. Writes
     *  both fields in one transaction, deliberately not composed from [setForwarding] (which
     *  always resets the marker to 0 on its own OFF branch). */
    suspend fun snoozeForwarding(
        untilMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        db.withTransaction {
            val current = db.stateDao().get() ?: ForwardingStateEntity()
            val next = current.copy(enabled = false, snoozedUntilMs = untilMs, updatedAtMs = nowMs)
            if (db.stateDao().get() == null) db.stateDao().insertDefault(next) else db.stateDao().update(next)
            logLocked(EventType.SERVICE, "Forwarding snoozed until $untilMs")
        }
    }

    /** A liveness signal, not a business event -- deliberately not routed through [logLocked]/
     *  event_log, which would otherwise grow one row per call for something with no diagnostic
     *  value beyond "the loop is still ticking." See [Database.StateDao.updateHeartbeat] for why
     *  this is a single-column update rather than a full entity read-modify-write. */
    suspend fun recordHeartbeat(nowMs: Long = System.currentTimeMillis()) {
        db.stateDao().updateHeartbeat(nowMs)
    }

    suspend fun resetCircuitBreaker() =
        db.withTransaction {
            val current = db.stateDao().get() ?: ForwardingStateEntity()
            db.stateDao().update(
                current.copy(emailCircuitOpen = false, consecutiveEmailFailures = 0, updatedAtMs = System.currentTimeMillis()),
            )
            logLocked(EventType.CIRCUIT_RESET, "Email circuit breaker manually reset")
        }

    // ------------------------------------------------------------------------------- filters

    suspend fun createFilter(name: String): Long =
        db.withTransaction {
            val id = db.filterDao().insert(ForwardingFilterEntity(name = name.ifBlank { "New filter" }, includeCalls = true))
            logLocked(EventType.SERVICE, "Created filter '${name.ifBlank { "New filter" }}'")
            id
        }

    suspend fun updateFilter(filter: ForwardingFilterEntity) =
        db.withTransaction {
            db.filterDao().update(
                filter.copy(
                    contactNumbersJson = normalizeContactNumbersJson(filter.contactNumbersJson),
                    updatedAtMs = System.currentTimeMillis(),
                ),
            )
        }

    fun isValidEmail(email: String): Boolean = Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()

    /** Persists a new drag-and-drop order in one transaction. [orderedIds] must contain every
     *  existing filter id exactly once -- an id that doesn't resolve to a current row (already
     *  deleted from under the reorder, for instance) is skipped rather than failing the whole
     *  operation. */
    suspend fun reorderFilters(orderedIds: List<Long>) =
        db.withTransaction {
            orderedIds.forEachIndexed { index, id ->
                db.filterDao().get(id)?.let { existing ->
                    if (existing.sortOrder != index) db.filterDao().update(existing.copy(sortOrder = index))
                }
            }
        }

    /** Clones every setting of an existing filter -- recipients, conditions, templates, schedule,
     *  everything but identity -- as a fast starting point for "one more filter that's almost
     *  the same as this one." The clone lands disabled and last in sort order regardless of the
     *  source's own state, so duplicating an active filter can never silently double-send a
     *  message while the user is still editing the copy. */
    suspend fun duplicateFilter(id: Long): Long? =
        db.withTransaction {
            val source = db.filterDao().get(id) ?: return@withTransaction null
            val maxSortOrder = db.filterDao().getAll().maxOfOrNull { it.sortOrder } ?: -1
            val now = System.currentTimeMillis()
            val clone =
                source.copy(
                    id = 0,
                    name = "Copy of ${source.name}",
                    enabled = false,
                    sortOrder = maxSortOrder + 1,
                    createdAtMs = now,
                    updatedAtMs = now,
                )
            val newId = db.filterDao().insert(clone)
            logLocked(EventType.SERVICE, "Duplicated filter '${source.name}' as 'Copy of ${source.name}'")
            newId
        }

    suspend fun deleteFilter(id: Long) =
        db.withTransaction {
            val filter = db.filterDao().get(id)
            db.filterDao().delete(id)
            filter?.let { logLocked(EventType.SERVICE, "Deleted filter '${it.name}'") }
        }

    /** Multi-select delete from the Filters list's long-press selection mode -- one transaction
     *  and one log line for the whole batch, not [deleteFilter] called once per id, so deleting
     *  ten filters doesn't also write ten separate "Deleted filter" event-log rows. */
    suspend fun deleteFilters(ids: List<Long>): Int =
        db.withTransaction {
            val names = ids.mapNotNull { id -> db.filterDao().get(id)?.name }
            ids.forEach { id -> db.filterDao().delete(id) }
            if (names.isNotEmpty()) {
                logLocked(
                    EventType.SERVICE,
                    if (names.size == 1) {
                        "Deleted filter '${names.first()}'"
                    } else {
                        "Deleted ${names.size} filters: ${names.joinToString(", ")}"
                    },
                )
            }
            names.size
        }

    // -------------------------------------------------------------------------- app settings

    suspend fun currentAppSettings(): AppSettingsEntity = db.appSettingsDao().get() ?: AppSettingsEntity()

    suspend fun updateAppSettings(transform: (AppSettingsEntity) -> AppSettingsEntity) =
        db.withTransaction {
            val current = db.appSettingsDao().get() ?: AppSettingsEntity()
            val transformed = transform(current)
            val normalizedHeartbeatRecipients =
                PayloadCodec.pathsFromJson(transformed.heartbeatRecipientsJson)
                    .mapNotNull(ComposeAuthorization::canonicalAddress)
                    .distinct()
            // Canonicalize each address and merge duplicates by OR-ing their capability bits,
            // rather than dropping the earlier or later entry -- defense in depth against a direct
            // settings.copy() call that introduces the same address twice with different bits; the
            // UI itself never allows that, since it only ever adds an address once.
            val normalizedRemoteControlSenders =
                RemoteControlCodec.fromJson(transformed.remoteControlSendersJson)
                    .mapNotNull { sender -> ComposeAuthorization.canonicalAddress(sender.address)?.let { sender.copy(address = it) } }
                    .groupBy(RemoteControlCodec.Sender::address)
                    .map { (address, group) ->
                        RemoteControlCodec.Sender(
                            address = address,
                            canCompose = group.any(RemoteControlCodec.Sender::canCompose),
                            canEnable = group.any(RemoteControlCodec.Sender::canEnable),
                            canDisable = group.any(RemoteControlCodec.Sender::canDisable),
                            canStatus = group.any(RemoteControlCodec.Sender::canStatus),
                        )
                    }
            val next =
                transformed.copy(
                    heartbeatRecipientsJson = PayloadCodec.pathsToJson(normalizedHeartbeatRecipients),
                    remoteControlSendersJson = RemoteControlCodec.toJson(normalizedRemoteControlSenders),
                    updatedAtMs = System.currentTimeMillis(),
                )
            if (db.appSettingsDao().get() == null) db.appSettingsDao().insertDefault(next) else db.appSettingsDao().update(next)
        }

    /**
     * Remote control defaults to on ([AppSettingsEntity.remoteControlEnabled]), but an empty
     * address list still authorizes nobody -- the switch alone was never the actual boundary. Once
     * the connected Gmail account is known, this seeds it as the one authorized address with all
     * four permissions on (Compose, Enable, Disable, Status), so remote control works out of the
     * box for the single owner this app is built for. Runs once per install: after that, or if any
     * address is already listed, it does nothing, so an address removed on purpose stays removed.
     * Installs that already seeded a Status-only owner keep what they have.
     */
    suspend fun seedRemoteControlOwnerIfEmpty(accountEmail: String) = seedRemoteControlSender(accountEmail, REMOTE_OWNER_SEEDED)

    /**
     * Authorizes a newly connected mailbox's own address for all four remote commands, once per
     * install per [flagKey]: after the flag is set it never runs again, so an address the owner
     * removed stays removed.
     *
     * Gmail ([seedRemoteControlOwnerIfEmpty]'s flag) seeds only into an empty list, as it always has.
     * Any other account ([REMOTE_GRAPH_SEEDED] for Outlook) is added unless it is already listed,
     * even when the list is not empty: the Gmail owner is usually already there, and a command
     * mailed from one account to the other is the case this exists for.
     */
    suspend fun seedRemoteControlSender(
        accountEmail: String,
        flagKey: String,
    ) {
        val gmailOwner = flagKey == REMOTE_OWNER_SEEDED
        val canonical = ComposeAuthorization.canonicalAddress(accountEmail) ?: return
        val flags = context.getSharedPreferences(SETUP_FLAGS, Context.MODE_PRIVATE)
        if (flags.getBoolean(flagKey, false)) return
        if (gmailOwner && RemoteControlCodec.fromJson(currentAppSettings().remoteControlSendersJson).isNotEmpty()) {
            flags.edit { putBoolean(flagKey, true) }
            return
        }
        db.withTransaction {
            val existing = db.appSettingsDao().get()
            val current = existing ?: AppSettingsEntity()
            val senders = RemoteControlCodec.fromJson(current.remoteControlSendersJson)
            if (gmailOwner && senders.isNotEmpty()) return@withTransaction
            if (senders.any { ComposeAuthorization.canonicalAddress(it.address) == canonical }) return@withTransaction
            val next =
                current.copy(
                    remoteControlSendersJson =
                        RemoteControlCodec.toJson(
                            senders +
                                RemoteControlCodec.Sender(
                                    canonical,
                                    canCompose = true,
                                    canEnable = true,
                                    canDisable = true,
                                    canStatus = true,
                                ),
                        ),
                    updatedAtMs = System.currentTimeMillis(),
                )
            if (existing == null) db.appSettingsDao().insertDefault(next) else db.appSettingsDao().update(next)
            val source = if (gmailOwner) "first Gmail connection" else "first Outlook connection"
            logLocked(EventType.SERVICE, "Remote control: $canonical authorized for Compose, Enable, Disable and Status ($source)")
        }
        flags.edit { putBoolean(flagKey, true) }
    }

    // ------------------------------------------------------------------------------- backup

    /** Filters and app settings only -- never credentials, since none are stored in this app;
     *  Google Play services owns the OAuth token cache. */
    suspend fun exportBackup(): String {
        val filterRows = db.filterDao().getAll()
        val settings = db.appSettingsDao().get() ?: AppSettingsEntity()
        val filtersJson =
            JSONArray().apply {
                filterRows.forEach { filter ->
                    put(
                        JSONObject().apply {
                            put("name", filter.name)
                            put("enabled", filter.enabled)
                            put("sortOrder", filter.sortOrder)
                            put("includeSms", filter.includeSms)
                            put("includeMms", filter.includeMms)
                            put("includeRcs", filter.includeRcs)
                            put("includeCalls", filter.includeCalls)
                            put("recipientsJson", filter.recipientsJson)
                            put("conditionMode", filter.conditionMode)
                            put("contactMode", filter.contactMode)
                            put("contactNumbersJson", filter.contactNumbersJson)
                            put("keywordMode", filter.keywordMode)
                            put("keywordsJson", filter.keywordsJson)
                            put("alwaysAllowOtp", filter.alwaysAllowOtp)
                            put("subjectTemplate", filter.subjectTemplate)
                            put("bodyTemplate", filter.bodyTemplate)
                            put("replaceRulesJson", filter.replaceRulesJson)
                            put("scheduleEnabled", filter.scheduleEnabled)
                            put("scheduleDaysMask", filter.scheduleDaysMask)
                            put("scheduleStartMinute", filter.scheduleStartMinute)
                            put("scheduleEndMinute", filter.scheduleEndMinute)
                            put("saveResults", filter.saveResults)
                            put("sendResultNotifications", filter.sendResultNotifications)
                            put("stopOnMatch", filter.stopOnMatch)
                        },
                    )
                }
            }
        return JSONObject()
            .apply {
                put("backupFormatVersion", 1)
                put("filters", filtersJson)
                put(
                    "appSettings",
                    JSONObject().apply {
                        put("appLockEnabled", settings.appLockEnabled)
                        put("fontScaleKey", settings.fontScaleKey)
                        put("duplicateSuppressionEnabled", settings.duplicateSuppressionEnabled)
                        put("duplicateWindowMinutes", settings.duplicateWindowMinutes)
                        put("softEmailPerMinuteCap", settings.softEmailPerMinuteCap)
                        put("softSmsPerMinuteCap", settings.softSmsPerMinuteCap)
                        put("retryOnNetworkReconnect", settings.retryOnNetworkReconnect)
                        put("remoteControlEnabled", settings.remoteControlEnabled)
                        put("remoteControlSendersJson", settings.remoteControlSendersJson)
                        put("replyConfirmationsEnabled", settings.replyConfirmationsEnabled)
                        put("heartbeatEnabled", settings.heartbeatEnabled)
                        put("heartbeatIntervalHours", settings.heartbeatIntervalHours)
                        put("heartbeatRecipientsJson", settings.heartbeatRecipientsJson)
                    // outboundSubscriptionId is deliberately NOT exported: a subscription id is
                    // meaningful only on the phone that issued it, so restoring one onto a
                    // different handset would point at either nothing or, worse, somebody else's
                    // line. A restored install falls back to the system default, which is the
                    // safe answer on any device.
                    },
                )
            }.toString(2)
    }

    /** Replaces every existing filter with the backup's filters and overwrites app settings.
     *  Returns the number of filters restored, or fails without changing anything on malformed
     *  input. */
    suspend fun importBackup(json: String): Result<Int> =
        suspendRunCatching {
            val root = JSONObject(json)
            val filtersJson = root.getJSONArray("filters")
            val restored =
                (0 until filtersJson.length()).map { index ->
                    val obj = filtersJson.getJSONObject(index)
                    ForwardingFilterEntity(
                        name = obj.getString("name"),
                        enabled = obj.optBoolean("enabled", true),
                        sortOrder = obj.optInt("sortOrder", 0),
                        includeSms = obj.optBoolean("includeSms", true),
                        includeMms = obj.optBoolean("includeMms", true),
                        includeRcs = obj.optBoolean("includeRcs", true),
                        includeCalls = obj.optBoolean("includeCalls", false),
                        recipientsJson = obj.optString("recipientsJson", "[]"),
                        conditionMode = obj.optString("conditionMode", FilterConditionMode.ALL),
                        contactMode = obj.optString("contactMode", ContactFilterMode.OFF),
                        contactNumbersJson = normalizeContactNumbersJson(obj.optString("contactNumbersJson", "[]")),
                        keywordMode = obj.optString("keywordMode", KeywordFilterMode.OFF),
                        keywordsJson = obj.optString("keywordsJson", "[]"),
                        alwaysAllowOtp = obj.optBoolean("alwaysAllowOtp", true),
                        subjectTemplate = obj.optString("subjectTemplate", MessageTemplateDefaults.SUBJECT),
                        bodyTemplate = obj.optString("bodyTemplate", MessageTemplateDefaults.BODY),
                        replaceRulesJson = obj.optString("replaceRulesJson", "[]"),
                        scheduleEnabled = obj.optBoolean("scheduleEnabled", false),
                        scheduleDaysMask = obj.optInt("scheduleDaysMask", 127),
                        scheduleStartMinute = obj.optInt("scheduleStartMinute", 0),
                        scheduleEndMinute = obj.optInt("scheduleEndMinute", 1440),
                        saveResults = obj.optBoolean("saveResults", true),
                        sendResultNotifications = obj.optBoolean("sendResultNotifications", false),
                        stopOnMatch = obj.optBoolean("stopOnMatch", false),
                    )
                }
            val settingsJson = root.optJSONObject("appSettings")
            db.withTransaction {
                db.filterDao().deleteAll()
                restored.forEach { db.filterDao().insert(it) }
                if (settingsJson != null) {
                    val current = db.appSettingsDao().get() ?: AppSettingsEntity()
                    val (importedRemoteControlEnabled, importedRemoteControlSendersJson) =
                        importRemoteControlSettings(settingsJson, current.remoteControlEnabled)
                    val next =
                        current.copy(
                            appLockEnabled = settingsJson.optBoolean("appLockEnabled", current.appLockEnabled),
                            fontScaleKey = settingsJson.optString("fontScaleKey", current.fontScaleKey),
                            duplicateSuppressionEnabled =
                                settingsJson.optBoolean("duplicateSuppressionEnabled", current.duplicateSuppressionEnabled),
                            duplicateWindowMinutes = settingsJson.optInt("duplicateWindowMinutes", current.duplicateWindowMinutes),
                            softEmailPerMinuteCap = settingsJson.optInt("softEmailPerMinuteCap", current.softEmailPerMinuteCap),
                            softSmsPerMinuteCap = settingsJson.optInt("softSmsPerMinuteCap", current.softSmsPerMinuteCap),
                            retryOnNetworkReconnect =
                                settingsJson.optBoolean("retryOnNetworkReconnect", current.retryOnNetworkReconnect),
                            remoteControlEnabled = importedRemoteControlEnabled,
                            remoteControlSendersJson = importedRemoteControlSendersJson,
                            // Not gated on an allowlist the way the remote-control switch above
                            // are -- there is no allowlist to pair it with, and it authorizes
                            // nothing. A backup predating it restores the current value.
                            replyConfirmationsEnabled =
                                settingsJson.optBoolean("replyConfirmationsEnabled", current.replyConfirmationsEnabled),
                            // Like the remote-command pairs above, the switch is only honored
                            // together with the recipients that make it meaningful: restoring
                            // "heartbeat on" with nowhere to send it would be a feature that
                            // silently does nothing while claiming to be enabled.
                            heartbeatEnabled =
                                settingsJson.optBoolean("heartbeatEnabled", false) &&
                                    settingsJson.has("heartbeatRecipientsJson"),
                            heartbeatIntervalHours =
                                settingsJson.optInt("heartbeatIntervalHours", current.heartbeatIntervalHours),
                            heartbeatRecipientsJson =
                                PayloadCodec.pathsToJson(
                                    PayloadCodec
                                        .pathsFromJson(settingsJson.optString("heartbeatRecipientsJson", "[]"))
                                        .mapNotNull(ComposeAuthorization::canonicalAddress)
                                        .distinct(),
                                ),
                            updatedAtMs = System.currentTimeMillis(),
                        )
                    if (db.appSettingsDao().get() == null) db.appSettingsDao().insertDefault(next) else db.appSettingsDao().update(next)
                }
                logLocked(EventType.SERVICE, "Restored ${restored.size} filter(s) from backup")
            }
            restored.size
        }

    /**
     * Builds the imported (remoteControlEnabled, remoteControlSendersJson) pair from a backup's
     * `appSettings` object, handling both shapes a backup file can carry:
     *
     * - **Current format** (has `remoteControlSendersJson`): each entry's address is canonicalized
     *   and validated -- an invalid one fails the whole import, same as every other allowlist field
     *   here, so a corrupted backup can never partially apply. The master switch is honored only
     *   together with the list that gates it, the same "restores atomically or not at all" rule
     *   every trust boundary in this file already follows.
     * - **Legacy format** (predates this consolidation): merges the four old toggle+allowlist pairs
     *   the same way [Database.MIGRATION_17_18] merges them on-device -- an address keeps exactly
     *   the capability bit(s) it already had under whichever old lists it appeared in with that
     *   list's own toggle on, and gains nothing it wasn't already granted. If that merge produces at
     *   least one address, the master switch turns on to make it usable; an old backup carrying
     *   nothing restores [currentEnabled] unchanged rather than forcing the switch either way.
     */
    private fun importRemoteControlSettings(
        settingsJson: JSONObject,
        currentEnabled: Boolean,
    ): Pair<Boolean, String> {
        if (settingsJson.has("remoteControlSendersJson")) {
            val raw = settingsJson.getString("remoteControlSendersJson")
            val parsed = JSONArray(raw)
            val senders =
                (0 until parsed.length()).map { index ->
                    val obj = parsed.getJSONObject(index)
                    val address =
                        ComposeAuthorization.canonicalAddress(obj.getString("address"))
                            ?: error("Backup contains an invalid remote-control sender address")
                    RemoteControlCodec.Sender(
                        address = address,
                        canCompose = obj.optBoolean("canCompose", false),
                        canEnable = obj.optBoolean("canEnable", false),
                        canDisable = obj.optBoolean("canDisable", false),
                        canStatus = obj.optBoolean("canStatus", false),
                    )
                }.distinctBy(RemoteControlCodec.Sender::address)
            val enabled = settingsJson.optBoolean("remoteControlEnabled", false) && settingsJson.has("remoteControlSendersJson")
            return enabled to RemoteControlCodec.toJson(senders)
        }

        val merged = LinkedHashMap<String, BooleanArray>()
        fun grant(
            enabledKey: String,
            sendersKey: String,
            bitIndex: Int,
        ) {
            if (!settingsJson.optBoolean(enabledKey, false) || !settingsJson.has(sendersKey)) return
            val addresses = runCatching { JSONArray(settingsJson.getString(sendersKey)) }.getOrNull() ?: return
            for (index in 0 until addresses.length()) {
                val address = ComposeAuthorization.canonicalAddress(addresses.optString(index)) ?: continue
                merged.getOrPut(address) { BooleanArray(4) }[bitIndex] = true
            }
        }
        grant("composeViaEmailEnabled", "authorizedComposeSendersJson", 0)
        grant("remoteEnableViaEmailEnabled", "authorizedRemoteEnableSendersJson", 1)
        grant("remoteDisableViaEmailEnabled", "authorizedRemoteDisableSendersJson", 2)
        grant("remoteStatusViaEmailEnabled", "authorizedRemoteStatusSendersJson", 3)
        val senders =
            merged.map { (address, bits) ->
                RemoteControlCodec.Sender(address = address, canCompose = bits[0], canEnable = bits[1], canDisable = bits[2], canStatus = bits[3])
            }
        val enabled = if (senders.isNotEmpty()) true else currentEnabled
        return enabled to RemoteControlCodec.toJson(senders)
    }

    // -------------------------------------------------------------------------- live receive

    private fun receivedKey(message: IncomingMessage): String =
        Hashing.sha256("${message.source}|${message.senderAddress}|${message.receivedAtMs}").take(24)

    suspend fun processIncoming(message: IncomingMessage): Boolean =
        db.withTransaction {
            val key = receivedKey(message)
            logLocked(EventType.RECEIVED, "${message.source.uppercase()} received", key)

            val state = db.stateDao().get() ?: ForwardingStateEntity()
            if (!state.enabled) {
                logLocked(EventType.SKIPPED, "Skipped — forwarding was off", key)
                return@withTransaction false
            }
            if (!WatermarkPolicy.isEligible(true, state.watermarkMs, message.receivedAtMs)) {
                logLocked(
                    EventType.SKIPPED,
                    "Skipped — received timestamp ${message.receivedAtMs} was not strictly after watermark ${state.watermarkMs}",
                    key,
                )
                return@withTransaction false
            }

            val dedupeText = Hashing.sha256(normalizeForDedupe(message.body))
            val settings = db.appSettingsDao().get() ?: AppSettingsEntity()
            if (settings.duplicateSuppressionEnabled && settings.duplicateWindowMinutes > 0) {
                val windowStart = message.receivedAtMs - settings.duplicateWindowMinutes * 60_000L
                if (
                    db.messageDao().hasIdenticalRecentMessage(
                        source = message.source,
                        senderAddress = message.senderAddress,
                        dedupeText = dedupeText,
                        windowStartMs = windowStart,
                        windowEndMs = message.receivedAtMs,
                    )
                ) {
                    logLocked(EventType.SKIPPED, "Skipped — identical notification suppressed within the duplicate window", key)
                    return@withTransaction false
                }
            }

            val duplicateWindowStart = (message.receivedAtMs - CROSS_SOURCE_DEDUP_WINDOW_MS).coerceAtLeast(0L)
            val duplicateWindowEnd =
                (message.receivedAtMs + CROSS_SOURCE_DEDUP_WINDOW_MS)
                    .coerceAtLeast(message.receivedAtMs)
            val crossSourceMatch =
                message.source in CROSS_SOURCE_MESSAGE_SOURCES &&
                    db.messageDao()
                        .crossSourceCandidates(
                            source = message.source,
                            dedupeText = dedupeText,
                            windowStartMs = duplicateWindowStart,
                            windowEndMs = duplicateWindowEnd,
                        ).any { candidate ->
                            sendersLikelyMatch(
                                candidate.senderAddress,
                                candidate.senderDisplay,
                                message.senderAddress,
                                message.senderDisplay,
                            )
                        }
            if (crossSourceMatch) {
                logLocked(
                    EventType.SKIPPED,
                    "Skipped — matching SMS/MMS and messaging-notification event already processed",
                    key,
                )
                return@withTransaction false
            }

            val messageId =
                db.messageDao().insert(
                    ForwardedMessageEntity(
                        source = message.source,
                        senderAddress = message.senderAddress,
                        senderDisplay = message.senderDisplay,
                        body = message.body,
                        bodyHash =
                            message.contentFingerprint ?: Hashing.sha256(
                                listOf(
                                    message.source,
                                    message.body,
                                    message.participants.joinToString(","),
                                    message.attachmentNotice.orEmpty(),
                                    message.attachmentPaths.joinToString(",") { File(it).length().toString() },
                                ).joinToString("|"),
                            ),
                        receivedAtMs = message.receivedAtMs,
                        sourceTimestampMs = message.sourceTimestampMs,
                        dedupeText = dedupeText,
                    ),
                )
            if (messageId == -1L) {
                logLocked(EventType.SKIPPED, "Skipped — duplicate live broadcast", key)
                return@withTransaction false
            }

            val replyTarget = PhoneNumbers.normalizeToE164(context, message.senderAddress)
            if (replyTarget == null) {
                val hint =
                    if (message.source == "rcs") {
                        " (RCS has no reliable sender number from Android itself; this usually means the " +
                            "matching contact has more than one number typed Mobile, or none -- see " +
                            "ContactResolver.uniquePhoneNumberForDisplayName)"
                    } else {
                        ""
                    }
                logLocked(EventType.SERVICE, "Sender could not be normalized to E.164; forwarding as no-reply$hint", key)
            }
            val isOtp = OtpDetector.looksLikeOtp(message.body)

            val enabledFilters = db.filterDao().getEnabled()
            if (enabledFilters.isEmpty()) {
                logLocked(EventType.SKIPPED, "Skipped — no forwarding filter is configured", key)
                return@withTransaction false
            }

            var queuedAny = false
            var saveHistory = false
            var fanOutCount = 0
            for (filter in enabledFilters) {
                if (!FilterConditionEvaluator.matchesMessageType(filter, message.source)) continue
                if (filter.scheduleEnabled &&
                    !ScheduleWindow.isActiveNow(filter.scheduleDaysMask, filter.scheduleStartMinute, filter.scheduleEndMinute)
                ) {
                    logLocked(EventType.SKIPPED, "Skipped by '${filter.name}' — outside its scheduled hours", key)
                    continue
                }
                val normalizedFilter = filter.copy(contactNumbersJson = normalizeContactNumbersJson(filter.contactNumbersJson))
                if (!FilterConditionEvaluator.matchesConditions(normalizedFilter, replyTarget, message.body, isOtp)) {
                    logLocked(EventType.SKIPPED, "Skipped by '${filter.name}' — did not match its forwarding conditions", key)
                    continue
                }
                val recipients = PayloadCodec.pathsFromJson(filter.recipientsJson)
                if (recipients.isEmpty()) {
                    logLocked(EventType.SKIPPED, "Skipped by '${filter.name}' — no recipients configured", key)
                    continue
                }
                if (fanOutCount >= MAX_FILTER_FAN_OUT) {
                    logLocked(EventType.RATE_LIMITED, "Per-message filter fan-out limit reached; remaining filters were skipped", key)
                    break
                }

                val vars = MessageVariables.forMessage(message, replyTarget)
                val subject = MessageTemplateEngine.render(filter.subjectTemplate, vars)
                val renderedBody =
                    ReplaceRuleEngine.apply(
                        MessageTemplateEngine.render(filter.bodyTemplate, vars),
                        ReplaceRuleCodec.fromJson(filter.replaceRulesJson),
                    )
                val payload =
                    EmailPayload(
                        destinations = recipients,
                        replyTarget = replyTarget,
                        senderDisplay = message.senderDisplay,
                        body = message.body,
                        receivedAtMs = message.receivedAtMs,
                        source = message.source,
                        participants = message.participants,
                        attachmentNotice = message.attachmentNotice,
                        renderedSubject = subject,
                        renderedBody = renderedBody,
                        filterName = filter.name,
                    )
                val payloadJson = PayloadCodec.emailToJson(payload)
                val attachmentJson = PayloadCodec.pathsToJson(message.attachmentPaths)
                if (!canAdmitLocked(QueueChannel.EMAIL, payloadJson, attachmentJson)) {
                    logLocked(EventType.RATE_LIMITED, "Email queue storage limit reached; forward was not admitted", key)
                    break
                }
                val queueId =
                    db.queueDao().insert(
                        SendQueueEntity(
                            channel = QueueChannel.EMAIL,
                            payloadJson = payloadJson,
                            attachmentPathsJson = attachmentJson,
                            sourceMessageId = messageId,
                        ),
                    )
                logLocked(EventType.QUEUED, "Queued email forward via filter '${filter.name}'", key, queueId)
                queuedAny = true
                saveHistory = saveHistory || filter.saveResults
                fanOutCount++
                if (filter.stopOnMatch) {
                    logLocked(EventType.SERVICE, "'${filter.name}' is set to stop processing further filters; skipping the rest", key)
                    break
                }
            }
            if (!saveHistory) db.messageDao().redactBody(messageId)
            queuedAny
        }

    // ------------------------------------------------------------------------- diagnostics/test

    data class TestMessageOutcome(
        val queued: Boolean,
        val trail: List<EventLogEntity>,
    )

    /**
     * Builds a synthetic message and runs it through the exact same [processIncoming] pipeline a
     * real message would hit -- same watermark/dedupe/filter-matching/template/queue logic -- so
     * the result is a genuine end-to-end check of the user's actual configuration, not a
     * simulated one. [sourceType] is one of "sms"/"mms"/"rcs"/"call" and controls which filters'
     * message-type scope it can match; [senderAddress] lets the caller test a specific
     * allow/block-listed number (contact conditions normalize it the same way a real sender is
     * normalized); [body] lets the caller test a specific keyword condition, or a specific
     * template rendering; [attachmentPaths] (already copied to app-private storage by the caller,
     * exactly like a live MMS's attachments) let an "mms"-typed test exercise a filter's MMS
     * handling with a real photo. The returned trail is every `event_log` row this one call
     * produced, in order, so the caller can show *why* it did or didn't forward -- "no filter
     * matched," "outside schedule," "queued via 'Work'" -- not just a pass/fail boolean.
     */
    suspend fun sendTestMessage(
        sourceType: String,
        senderAddress: String,
        body: String,
        attachmentPaths: List<String> = emptyList(),
    ): TestMessageOutcome {
        val now = System.currentTimeMillis()
        val message =
            IncomingMessage(
                source = sourceType,
                senderAddress = senderAddress.ifBlank { DEFAULT_TEST_SENDER },
                senderDisplay = "SCIF Sidekick (test)",
                body = body.ifBlank { DEFAULT_TEST_BODY },
                receivedAtMs = now,
                sourceTimestampMs = now,
                attachmentPaths = attachmentPaths,
            )
        val key = receivedKey(message)
        val queued = processIncoming(message)
        return TestMessageOutcome(queued, db.eventLogDao().forKey(key))
    }

    // ------------------------------------------------------------------------------- export

    /** Every live message recorded within [startMs, endMs] (inclusive), newest first -- the data
     *  source behind the History screen's "Export message log" action. See [MessageDao.forExport]
     *  for the retention/redaction note. */
    suspend fun messagesForExport(
        startMs: Long,
        endMs: Long,
    ): List<ForwardedMessageEntity> = db.messageDao().forExport(startMs, endMs)

    // --------------------------------------------------------------------------- reply / compose

    suspend fun enqueueReplyIfNew(
        gmailMessageId: String,
        targetNumber: String,
        body: String,
        initiatorAddress: String? = null,
        initiatorThreadId: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean =
        db.withTransaction {
            val inserted =
                db.processedReplyDao().insert(
                    ProcessedReplyEntity(gmailMessageId, nowMs, targetNumber),
                )
            if (inserted == -1L) return@withTransaction false
            val dedupeKey = replyDedupeKey(targetNumber, body)
            if (isRecentDuplicateReplyLocked(dedupeKey, nowMs)) {
                logLocked(
                    EventType.SKIPPED,
                    "Skipped -- an identical reply to this number was already queued/sent within the " +
                        "duplicate-suppression window, under a different Gmail message id",
                    gmailMessageId,
                )
                return@withTransaction false
            }
            val payloadJson =
                PayloadCodec.smsToJson(
                    SmsReplyPayload(targetNumber, body, gmailMessageId, initiatorAddress, initiatorThreadId),
                )
            if (!canAdmitLocked(QueueChannel.SMS, payloadJson, "[]")) throw QueueCapacityException()
            val queueId =
                db.queueDao().insert(
                    SendQueueEntity(
                        channel = QueueChannel.SMS,
                        payloadJson = payloadJson,
                        replyDedupeKey = dedupeKey,
                    ),
                )
            logLocked(EventType.REPLY_DETECTED, "Authorized email reply detected and SMS queued", gmailMessageId, queueId)
            true
        }

    /** Mirrors [enqueueReplyIfNew] for a picture message: same idempotency guard (one row per
     *  Gmail message id, first writer wins), same reply-content dedupe window, same event log,
     *  just a different queue channel and a stored attachment path riding along in the column
     *  every other queued attachment already uses. */
    suspend fun enqueueMmsReplyIfNew(
        gmailMessageId: String,
        targetNumber: String,
        body: String,
        imagePath: String,
        initiatorAddress: String? = null,
        initiatorThreadId: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean =
        db.withTransaction {
            val inserted =
                db.processedReplyDao().insert(
                    ProcessedReplyEntity(gmailMessageId, nowMs, targetNumber),
                )
            if (inserted == -1L) return@withTransaction false
            val dedupeKey = replyDedupeKey(targetNumber, body)
            if (isRecentDuplicateReplyLocked(dedupeKey, nowMs)) {
                logLocked(
                    EventType.SKIPPED,
                    "Skipped -- an identical reply to this number was already queued/sent within the " +
                        "duplicate-suppression window, under a different Gmail message id",
                    gmailMessageId,
                )
                return@withTransaction false
            }
            val payloadJson =
                PayloadCodec.mmsToJson(
                    MmsReplyPayload(targetNumber, body, gmailMessageId, initiatorAddress, initiatorThreadId),
                )
            val attachmentJson = PayloadCodec.pathsToJson(listOf(imagePath))
            if (!canAdmitLocked(QueueChannel.MMS, payloadJson, attachmentJson)) throw QueueCapacityException()
            val queueId =
                db.queueDao().insert(
                    SendQueueEntity(
                        channel = QueueChannel.MMS,
                        payloadJson = payloadJson,
                        attachmentPathsJson = attachmentJson,
                        replyDedupeKey = dedupeKey,
                    ),
                )
            logLocked(EventType.REPLY_DETECTED, "Authorized email reply detected and MMS queued", gmailMessageId, queueId)
            true
        }

    /**
     * Queues an email the app itself originated -- a reply confirmation, and later the same path
     * for any other app-generated mail -- rather than sending it straight through [GmailGateway].
     *
     * Deliberately the queue and not a direct send: a direct call would bypass the reservation in
     * `delivery_attempts` and with it all three rolling caps, the circuit breaker, the retry
     * backoff, and durability across process death -- every one of which is a stated safety
     * invariant, and none of which should have an exception carved into it just because this app
     * wrote the message instead of forwarding someone else's. Being queued also means a
     * confirmation can never jump ahead of, or steal capacity from, the actual forwarding it is
     * reporting on.
     *
     * The subject deliberately carries no `[SCIF:` tag: this mail lands back in the same inbox the
     * reply poller reads, and while [wasSentByThisApp] already recognizes the app's own mail, a
     * subject that merely *looks* like a routing command is worth not creating in the first place.
     *
     * Returns the queue row id, or null when the storage ceiling refused it (logged, never thrown:
     * a confirmation is strictly less important than the forwarding whose capacity it would be
     * competing for).
     */
    suspend fun enqueueSystemEmail(
        recipients: List<String>,
        subject: String,
        body: String,
        reason: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Long? =
        db.withTransaction {
            val destinations = recipients.mapNotNull(ComposeAuthorization::canonicalAddress).distinct()
            if (destinations.isEmpty()) return@withTransaction null
            val payload =
                EmailPayload(
                    destinations = destinations,
                    replyTarget = null,
                    senderDisplay = "SCIF Sidekick",
                    body = body,
                    receivedAtMs = nowMs,
                    source = "system",
                    participants = emptyList(),
                    attachmentNotice = null,
                    renderedSubject = subject,
                    renderedBody = body,
                    filterName = "(SCIF Sidekick)",
                )
            val payloadJson = PayloadCodec.emailToJson(payload)
            if (!canAdmitLocked(QueueChannel.EMAIL, payloadJson, "[]")) {
                logLocked(EventType.RATE_LIMITED, "Email queue storage limit reached; $reason was not queued")
                return@withTransaction null
            }
            val queueId =
                db.queueDao().insert(
                    SendQueueEntity(
                        channel = QueueChannel.EMAIL,
                        payloadJson = payloadJson,
                    ),
                )
            logLocked(EventType.QUEUED, "Queued $reason", null, queueId)
            queueId
        }

    /**
     * A plain-text snapshot of everything worth knowing from somewhere the phone isn't: what the
     * master switch is doing, whether anything is stuck, and whether the parts that go quiet when
     * they break are still alive.
     *
     * Deliberately carries no message content, no phone numbers and no sender addresses. It is
     * emailed on a schedule and, once the status command exists, on request, so it should stay
     * useful to whoever is allowed to see it without becoming a way to read the message history
     * out of the phone.
     *
     * "Service last seen" is [ForwardingStateEntity.lastHeartbeatMs] -- the once-a-minute liveness
     * write the forwarding loop makes while it is genuinely running -- which is the one field here
     * that distinguishes "nothing has arrived lately" from "the service is dead", something
     * message volume alone cannot show.
     */
    suspend fun buildStatusSummary(
        gmailAvailable: Boolean,
        nowMs: Long = System.currentTimeMillis(),
        otherAccounts: List<Pair<String, Boolean>> = emptyList(),
    ): String {
        val state = db.stateDao().get()
        val queued = db.queueDao().queuedCount()
        val emailsLastDay = db.deliveryAttemptDao().countSince(QueueChannel.EMAIL, nowMs - 24L * 60L * 60_000L)
        val serviceSeen =
            when {
                state?.enabled != true -> "not running (forwarding is off)"
                state.lastHeartbeatMs <= 0L -> "no liveness signal recorded yet"
                else -> "${(nowMs - state.lastHeartbeatMs) / 60_000L} minute(s) ago"
            }
        return buildString {
            appendLine("Forwarding: ${if (state?.enabled == true) "ON" else "OFF"}")
            appendLine("Service last seen: $serviceSeen")
            appendLine("Gmail authorization: ${if (gmailAvailable) "OK" else "NEEDS RECONNECTING"}")
            accountLines(otherAccounts).forEach { appendLine(it) }
            appendLine("Email circuit breaker: ${if (state?.emailCircuitOpen == true) "OPEN -- sending is paused" else "closed"}")
            appendLine("Queued and waiting to send: $queued")
            appendLine("Email send attempts in the last 24h: $emailsLastDay")
            if (state?.snoozedUntilMs != null && state.snoozedUntilMs > nowMs) {
                appendLine("Snoozed until: ${java.util.Date(state.snoozedUntilMs)}")
            }
        }.trimEnd()
    }

    suspend fun recordHeartbeatEmailSent(nowMs: Long = System.currentTimeMillis()) {
        db.stateDao().updateLastHeartbeatEmail(nowMs)
    }

    /** A short, content-based fingerprint for an outbound reply -- distinct from
     *  [ProcessedReplyEntity]'s per-Gmail-message-id idempotency, which cannot catch two
     *  genuinely different Gmail messages (an email client silently resubmitting the same reply,
     *  say) that carry the same destination number and text. */
    private fun replyDedupeKey(
        targetNumber: String,
        body: String,
    ): String = Hashing.sha256("$targetNumber|${normalizeForDedupe(body)}")

    /** Used to reuse Settings -> duplicate window wholesale, including its enabled toggle -- but
     *  that toggle governs a genuinely different concern (re-showing a *notification* for an
     *  incoming message the user already saw) and coupling it here meant turning that off, or
     *  setting its window to 0, silently also disabled "never text the same person the same reply
     *  twice." Nobody actually wants that, and it is exactly the shape of bug that produced a
     *  real double-send. The window length itself still follows the user's configured value when
     *  they've set it higher, but a 1-minute floor now always applies to outgoing replies
     *  specifically, regardless of the notification-dedup toggle or a smaller/zero window there. */
    private suspend fun isRecentDuplicateReplyLocked(
        dedupeKey: String,
        nowMs: Long,
    ): Boolean {
        val settings = db.appSettingsDao().get() ?: AppSettingsEntity()
        val effectiveWindowMinutes = settings.duplicateWindowMinutes.coerceAtLeast(1)
        val windowStart = nowMs - effectiveWindowMinutes * 60_000L
        return db.queueDao().hasRecentReplyDedupeKey(dedupeKey, windowStart)
    }

    /** True for every app-emitted Gmail message, including no-reply forwards and the brief
     *  pre-response interval represented by a pending deterministic RFC Message-ID. */
    suspend fun wasSentByThisApp(
        gmailMessageId: String,
        rfcMessageId: String,
    ): Boolean =
        db.sentGmailMessageDao().isOwnSentMessage(gmailMessageId, rfcMessageId) ||
            (rfcMessageId.isNotBlank() && db.pendingEmailRouteDao().isOwnPendingMessage(rfcMessageId))

    suspend fun isAuthorizedReply(
        targetNumber: String,
        threadId: String,
        referencedMessageIds: Set<String>,
        authenticatedSender: String?,
    ): Boolean =
        db.withTransaction {
            val sender = ComposeAuthorization.canonicalAddress(authenticatedSender) ?: return@withTransaction false
            // The RFC In-Reply-To/References link to an exact message this app emitted (matched
            // by exact rfcMessageId string) is the strict anti-forgery/anti-replay core and always
            // wins outright. But this app sets a synthetic, non-existent-domain Message-ID
            // (scif-<hash>@scif-sidekick.invalid) on every forward it sends, and real-world mail
            // paths don't always preserve a client-supplied Message-ID verbatim end to end -- an
            // outbound relay or the replying client's own threading logic can end up quoting
            // something other than the exact original string, even for a completely genuine reply.
            // SentEmailRouteDao.routesForThread exists for exactly this: Gmail's own thread
            // grouping (subject + participants heuristics) is far more forgiving of that kind of
            // mangling. It is never used alone, though -- Gmail can also group a same-subject
            // brand-new message into an old thread, which is not a reply at all -- so a thread
            // match only ever counts alongside referencedMessageIds being non-empty: the candidate
            // has to at least be asserting it's a reply to *something* via its own In-Reply-To/
            // References, even when the exact id inside them isn't a string match.
            val byReference =
                referencedMessageIds.flatMap { messageId ->
                    db.sentEmailRouteDao().routesForReference(messageId, targetNumber).map { it.authorizedReplySendersJson }
                } +
                    referencedMessageIds.flatMap { messageId ->
                        db.pendingEmailRouteDao().routesForReference(messageId, targetNumber).map { it.authorizedReplySendersJson }
                    }
            val byThread =
                if (referencedMessageIds.isNotEmpty()) {
                    db.sentEmailRouteDao().routesForThread(threadId, targetNumber).map { it.authorizedReplySendersJson }
                } else {
                    emptyList()
                }
            val routeAuthorizedSendersJsons = byReference + byThread
            if (routeAuthorizedSendersJsons.isEmpty()) return@withTransaction false
            if (routeAuthorizedSendersJsons.any { senderIsAuthorized(sender, it) }) return@withTransaction true
            // Alias fallback: a genuine route to targetNumber exists and the sender passed DMARC,
            // but isn't the filter's exact recipient address (typically a second alias on the same
            // mailbox). Accept it only if remote control is on and this address is checked for
            // Compose -- anyone trusted to start a brand-new text may reply to an existing one, but
            // remote control being off, or this address not having that capability, grants nothing.
            val settings = currentAppSettings()
            val viaAlias =
                settings.remoteControlEnabled &&
                    RemoteControlCodec.isAuthorized(settings.remoteControlSendersJson, sender, RemoteControlCodec.Sender::canCompose)
            if (viaAlias) {
                logLocked(
                    EventType.SECURITY,
                    "Reply from $sender accepted via the remote-control Compose permission (not the filter's recipient address)",
                )
            }
            viaAlias
        }

    /**
     * Diagnostic-only: the reply-authorized sender addresses recorded on whichever sent/pending
     * route(s) actually matched [referencedMessageIds] + [targetNumber] -- i.e. exactly what
     * [isAuthorizedReply] compared the candidate reply's authenticated sender against. Never
     * itself part of the authorization decision, purely so a "blocked" log line can show precisely
     * why: [authorizedReplySendersJson] is populated from the filter's own recipient list at send
     * time (see QueueProcessor.performDelivery/recordSuccess), so a reply sent from a *different*,
     * equally legitimate address than the one configured as the filter's recipient -- a second
     * alias on the same mailbox, for instance -- fails this exact-match check even though a human
     * would recognize it as the same person. This surfaces that mismatch instead of leaving both
     * sides invisible.
     */
    suspend fun authorizedSendersForRoute(
        threadId: String,
        referencedMessageIds: Set<String>,
        targetNumber: String,
    ): List<String> =
        db.withTransaction {
            val sent = referencedMessageIds.flatMap { db.sentEmailRouteDao().routesForReference(it, targetNumber) }
            val pending = referencedMessageIds.flatMap { db.pendingEmailRouteDao().routesForReference(it, targetNumber) }
            val byThread =
                if (referencedMessageIds.isNotEmpty()) db.sentEmailRouteDao().routesForThread(threadId, targetNumber) else emptyList()
            (sent.map { it.authorizedReplySendersJson } + pending.map { it.authorizedReplySendersJson } + byThread.map { it.authorizedReplySendersJson })
                .flatMap(PayloadCodec::pathsFromJson)
                .distinct()
        }

    suspend fun recordReplyWithoutSms(
        gmailMessageId: String,
        targetNumber: String,
        reason: String,
        eventType: String = EventType.SKIPPED,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean =
        db.withTransaction {
            val inserted =
                db.processedReplyDao().insert(
                    ProcessedReplyEntity(gmailMessageId, nowMs, targetNumber),
                )
            if (inserted == -1L) return@withTransaction false
            if (eventType != EventType.SECURITY || blockedLogAllowed(nowMs)) logLocked(eventType, reason, gmailMessageId)
            true
        }

    /** Records an inbox candidate that is not a routing command without changing its Gmail read
     *  state. The polling gateway consults this bounded ledger before fetching message bodies or
     *  attachments again. */
    suspend fun recordIgnoredGmailCandidate(
        gmailMessageId: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        db.processedReplyDao().insert(ProcessedReplyEntity(gmailMessageId, nowMs, ""))
    }

    suspend fun recentProcessedGmailIds(nowMs: Long = System.currentTimeMillis()): Set<String> =
        db.processedReplyDao().idsSince(nowMs - PROCESSED_REPLY_RETENTION_MS).toSet()

    suspend fun recordServiceEvent(reason: String) {
        db.eventLogDao().insert(EventLogEntity(type = EventType.SERVICE, reason = reason))
    }

    /** Records a delivery-status notification against whichever queued send it referenced, if any
     *  -- "Gmail accepted the send" alone can never surface a bounce; only a later notification
     *  from the recipient's own mail server can. Returns whether a matching send was actually
     *  found, purely so the caller can decide whether to still mark the bounce email read (a
     *  bounce this app can't attribute to anything it sent is still worth a generic log entry,
     *  never silently dropped). */
    suspend fun recordBounce(
        rfcMessageId: String,
        detail: String,
    ): Boolean {
        val queueId = db.sentGmailMessageDao().queueIdForRfcMessageId(rfcMessageId)
        db.eventLogDao().insert(EventLogEntity(type = EventType.DELIVERY_BOUNCED, reason = detail, queueId = queueId))
        return queueId != null
    }

    suspend fun recordEvent(
        type: String,
        reason: String,
        key: String? = null,
    ) {
        db.eventLogDao().insert(EventLogEntity(type = type, reason = reason, messageKey = key))
    }

    private val blockedBudget = HourlyEventBudget()

    /** Logs a rejected remote command or forged route as a SECURITY event, capped per hour (see
     *  [HourlyEventBudget]) so junk mail can't grow the log without bound. */
    suspend fun recordBlockedAttempt(reason: String) {
        if (blockedLogAllowed(System.currentTimeMillis())) logLocked(EventType.SECURITY, reason)
    }

    /** True if another blocked-attempt row may be written now; writes the summary row for the
     *  previous window's skipped events the first time a new window opens. */
    private suspend fun blockedLogAllowed(nowMs: Long): Boolean {
        val decision = blockedBudget.take(nowMs)
        if (decision.previouslySuppressed > 0) {
            logLocked(
                EventType.SECURITY,
                "${decision.previouslySuppressed} more blocked attempt(s) were not logged individually in the previous hour",
            )
        }
        return decision.log
    }

    private suspend fun logLocked(
        type: String,
        reason: String,
        key: String? = null,
        queueId: Long? = null,
    ) {
        db.eventLogDao().insert(EventLogEntity(type = type, reason = reason, messageKey = key, queueId = queueId))
    }

    private fun senderIsAuthorized(
        sender: String,
        authorizedSendersJson: String,
    ): Boolean = ComposeAuthorization.isAuthorizedSender(sender, PayloadCodec.pathsFromJson(authorizedSendersJson))

    /**
     * Tolerant sender-equivalence check for cross-source dedup. Exact string equality still
     * counts (covers a non-numeric identifier like "rcs-group:..." or a bare display name), but
     * two numeric identifiers also count as the same sender when their last 10 digits match.
     * Confirmed necessary on a physical device: a message sent from a Google Voice number arrived
     * via both the raw SMS broadcast and Google Messages' own notification at effectively the same
     * moment, with identical body text, yet still wasn't recognized as the same sender by exact
     * string equality. Google Voice's SMS relay is a documented source of exactly this kind of
     * divergence between what a carrier's SMS broadcast reports as the originating address and
     * what Google's own notification/contact-resolved display attributes the message to.
     */
    private fun sendersLikelyMatch(
        addressA: String,
        displayA: String,
        addressB: String,
        displayB: String,
    ): Boolean {
        if (addressA == addressB || addressA == displayB || displayA == addressB || displayA == displayB) return true
        val a = lastDigits(addressA) ?: lastDigits(displayA)
        val b = lastDigits(addressB) ?: lastDigits(displayB)
        return a != null && b != null && a == b
    }

    /** Strips everything but digits and keeps the last 10 (a NANP national significant number) --
     *  tolerant of a leading +1/country code, punctuation, or extension formatting differing
     *  between two representations of the same real number. Null when there aren't even 10 digits
     *  to work with, so a short numeric string (a group-chat placeholder, say) never accidentally
     *  collapses into a false match. */
    private fun lastDigits(value: String): String? = value.filter(Char::isDigit).takeLast(10).takeIf { it.length == 10 }

    private suspend fun canAdmitLocked(
        channel: String,
        payloadJson: String,
        attachmentPathsJson: String,
    ): Boolean {
        val queue = db.queueDao()
        if (queue.retainedWorkCount() >= MAX_RETAINED_WORK_ROWS) return false
        if (channel == QueueChannel.EMAIL && queue.retainedChannelCount(QueueChannel.EMAIL) >= MAX_RETAINED_EMAIL_ROWS) return false
        val addedBytes = payloadJson.toByteArray(Charsets.UTF_8).size + attachmentPathsJson.toByteArray(Charsets.UTF_8).size
        return queue.retainedPayloadBytes() + addedBytes <= MAX_RETAINED_PAYLOAD_BYTES
    }

    private fun normalizeContactNumbersJson(json: String): String =
        PayloadCodec.pathsFromJson(json)
            .mapNotNull { raw -> PhoneNumbers.normalizeToE164(context, raw) }
            .distinct()
            .let(PayloadCodec::pathsToJson)

    private companion object {
        const val SETUP_FLAGS = "setup_flags_v1"
        const val REMOTE_OWNER_SEEDED = "remote_owner_seeded"
        // Confirmed too tight on a physical device: an identical plain SMS ("Test") arrived via
        // both the raw SMS broadcast and Google Messages' own notification, both displayed as the
        // same minute, but far enough apart in actual receivedAtMs to miss a 15s window -- Google
        // Messages posting its own notification is a wholly separate OS delivery path from the
        // SMS broadcast, subject to its own scheduling/battery-optimization jitter, and
        // RcsNotificationListenerService.CROSS_SOURCE_DEDUP_DELAY_MS's own deliberate 2s head start
        // already eats into whatever budget existed. 60s matches the same order of magnitude as
        // this app's other duplicate-suppression default (Settings' duplicateWindowMinutes = 1).
        const val CROSS_SOURCE_DEDUP_WINDOW_MS = 60_000L
        const val PROCESSED_REPLY_RETENTION_MS = 90L * 24L * 60L * 60L * 1_000L
        const val MAX_FILTER_FAN_OUT = 25
        const val MAX_RETAINED_WORK_ROWS = 2_000
        const val MAX_RETAINED_EMAIL_ROWS = 1_800
        const val MAX_RETAINED_PAYLOAD_BYTES = 25L * 1024L * 1024L
        val CROSS_SOURCE_MESSAGE_SOURCES = setOf("sms", "mms", "rcs")
        const val DEFAULT_TEST_SENDER = "+15555550100"
        const val DEFAULT_TEST_BODY = "[TEST] This is a test message from SCIF Sidekick, sent to verify your filters are working."
    }
}

class QueueCapacityException : Exception("Queue storage limit reached; command left unread for later retry")

/** [SidekickRepository.seedRemoteControlSender]'s once-per-install flag for the Outlook address. */
const val REMOTE_GRAPH_SEEDED = "remote_graph_seeded"

/** The status summary's line for each account after Gmail, in order. */
internal fun accountLines(otherAccounts: List<Pair<String, Boolean>>): List<String> =
    otherAccounts.map { (name, ok) -> "$name authorization: ${if (ok) "OK" else "NEEDS RECONNECTING"}" }
