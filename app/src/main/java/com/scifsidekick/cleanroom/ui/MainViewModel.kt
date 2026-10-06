package com.scifsidekick.cleanroom.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scifsidekick.cleanroom.AppGraph
import com.scifsidekick.cleanroom.BuildConfig
import com.scifsidekick.cleanroom.data.AppSettingsEntity
import com.scifsidekick.cleanroom.data.EventLogEntity
import com.scifsidekick.cleanroom.data.EventType
import com.scifsidekick.cleanroom.data.ForwardingFilterEntity
import com.scifsidekick.cleanroom.data.ForwardingStateEntity
import com.scifsidekick.cleanroom.messaging.EmailPayload
import com.scifsidekick.cleanroom.messaging.IncomingMessage
import com.scifsidekick.cleanroom.service.ForwardingService
import com.scifsidekick.cleanroom.service.SnoozeWorker
import com.scifsidekick.cleanroom.service.StatusWidgetProvider
import com.scifsidekick.cleanroom.util.BackupCrypto
import com.scifsidekick.cleanroom.util.MessageLogExport
import com.scifsidekick.cleanroom.util.suspendRunCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class MainViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val graph = AppGraph.from(application)
    val state: StateFlow<ForwardingStateEntity> =
        graph.repository.state
            .map { it ?: ForwardingStateEntity() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ForwardingStateEntity())
    val events: StateFlow<List<EventLogEntity>> =
        graph.repository.recentEvents
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val queued: StateFlow<Int> =
        graph.repository.queuedCount
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val filters: StateFlow<List<ForwardingFilterEntity>> =
        graph.repository.filters
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val appSettings: StateFlow<AppSettingsEntity> =
        graph.repository.appSettings
            .map { it ?: AppSettingsEntity() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettingsEntity())
    val lastSentAt: StateFlow<Long?> =
        graph.repository.lastSentAt
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    val oauthAuthorized get() = graph.oauth.isAuthorized
    val pubSubGranted get() = graph.oauth.pubSubGranted
    val isDebug get() = BuildConfig.DEBUG

    suspend fun fetchGmailAccountEmail(): String? = graph.gmail.currentAccountEmail()
    val fakeTransport get() = graph.debug.fakeEmailTransport

    /** Allows the connected Gmail account to ask for status the first time it's known -- see
     *  [SidekickRepository.seedRemoteControlOwnerIfEmpty]'s own doc comment. */
    fun seedRemoteControlOwnerIfEmpty(accountEmail: String) =
        viewModelScope.launch {
            graph.repository.seedRemoteControlOwnerIfEmpty(accountEmail)
        }

    init {
        viewModelScope.launch {
            graph.repository.ensureInitialized()
            graph.oauth.setPushScopeWanted(graph.repository.currentAppSettings().gmailPushEnabled)
        }
    }

    fun isValidEmail(email: String): Boolean = graph.repository.isValidEmail(email)

    fun setForwarding(enabled: Boolean) =
        viewModelScope.launch {
            if (enabled && filters.value.none { it.enabled && it.recipientsHasAny() }) {
                messages.emit("Add and enable at least one filter with a recipient before turning forwarding on")
                return@launch
            }
            if (
                enabled &&
                (
                    getApplication<Application>().checkSelfPermission(Manifest.permission.RECEIVE_SMS) !=
                        PackageManager.PERMISSION_GRANTED ||
                        getApplication<Application>().checkSelfPermission(Manifest.permission.SEND_SMS) !=
                        PackageManager.PERMISSION_GRANTED
                )
            ) {
                messages.emit("Grant SMS receive and send permissions before enabling forwarding")
                return@launch
            }
            if (enabled && !graph.oauth.isAuthorized && !graph.debug.fakeEmailTransport) {
                messages.emit("Connect Gmail before enabling forwarding")
                return@launch
            }
            graph.repository.setForwarding(enabled)
            if (enabled) {
                // Cancels a still-pending snooze re-enable job if the user turned forwarding back
                // on manually before it fired -- harmless either way (setForwarding(true) is
                // idempotent), but leaves nothing to later fire a confusing "re-enabled" log entry
                // at a moment the user didn't ask for.
                SnoozeWorker.cancel(getApplication())
                val failure = runCatching { ForwardingService.start(getApplication()) }.exceptionOrNull()
                if (failure != null) {
                    graph.repository.setForwarding(false)
                    graph.repository.recordServiceEvent(
                        "Forwarding activation failed; switch reverted to off: ${failure.message}",
                    )
                    messages.emit("Forwarding could not start, so it was switched back off")
                }
            }
            StatusWidgetProvider.requestUpdate(getApplication())
        }

    /** "Pause for 2 hours" instead of relying on remembering to flip the toggle back -- the actual
     *  re-enable is [SnoozeWorker], a scheduled job that survives the app or the phone restarting
     *  during the snooze window, not a coroutine delay this ViewModel owns. */
    fun snoozeForwarding(durationMs: Long) =
        viewModelScope.launch {
            val untilMs = System.currentTimeMillis() + durationMs
            graph.repository.snoozeForwarding(untilMs)
            SnoozeWorker.schedule(getApplication(), durationMs)
            StatusWidgetProvider.requestUpdate(getApplication())
            messages.emit("Forwarding snoozed for ${durationMs / 60_000} minutes")
        }

    /** Cancels a pending snooze and turns forwarding back on immediately -- distinct from
     *  [setForwarding] only in that the caller doesn't need to separately know to cancel the
     *  scheduled re-enable job themselves. */
    fun cancelSnooze() = setForwarding(true)

    private fun ForwardingFilterEntity.recipientsHasAny(): Boolean =
        com.scifsidekick.cleanroom.util.PayloadCodec.pathsFromJson(recipientsJson).isNotEmpty()

    fun resetCircuit() =
        viewModelScope.launch {
            graph.repository.resetCircuitBreaker()
            graph.alerts.clearCircuitBreaker()
            messages.emit("Sending manually resumed")
        }

    // ------------------------------------------------------------------------------- filters

    fun createFilter(
        name: String,
        onCreated: (Long) -> Unit,
    ) = viewModelScope.launch {
        val id = graph.repository.createFilter(name)
        onCreated(id)
    }

    fun updateFilter(
        filter: ForwardingFilterEntity,
        onSaved: () -> Unit = {},
    ) = viewModelScope.launch {
        graph.repository.updateFilter(filter)
        messages.emit("Saved '${filter.name}'")
        onSaved()
    }

    /** The Filters list screen's own switch, separate from [updateFilter] -- flipping a filter
     *  on or off at a glance shouldn't also pop a "Saved" snackbar every time, the way saving a
     *  full edit does. */
    fun setFilterEnabled(
        id: Long,
        enabled: Boolean,
    ) = viewModelScope.launch {
        val current = filters.value.firstOrNull { it.id == id } ?: return@launch
        if (current.enabled != enabled) {
            graph.repository.updateFilter(current.copy(enabled = enabled))
        }
    }

    fun duplicateFilter(
        id: Long,
        onDuplicated: (Long) -> Unit = {},
    ) = viewModelScope.launch {
        val newId = graph.repository.duplicateFilter(id)
        if (newId != null) {
            messages.emit("Filter duplicated")
            onDuplicated(newId)
        }
    }

    fun reorderFilters(orderedIds: List<Long>) =
        viewModelScope.launch {
            graph.repository.reorderFilters(orderedIds)
        }

    fun deleteFilter(
        id: Long,
        onDeleted: () -> Unit = {},
    ) = viewModelScope.launch {
        graph.repository.deleteFilter(id)
        onDeleted()
    }

    fun deleteFilters(
        ids: List<Long>,
        onDeleted: () -> Unit = {},
    ) = viewModelScope.launch {
        val count = graph.repository.deleteFilters(ids)
        if (count > 0) messages.emit(if (count == 1) "Filter deleted" else "$count filters deleted")
        onDeleted()
    }

    // -------------------------------------------------------------------------- app settings

    fun updateAppSettings(transform: (AppSettingsEntity) -> AppSettingsEntity) =
        viewModelScope.launch {
            graph.repository.updateAppSettings(transform)
            val pushEnabled = graph.repository.currentAppSettings().gmailPushEnabled
            if (pushEnabled && !graph.oauth.pushScopeWanted && graph.oauth.isAuthorized) {
                messages.emit("Push needs Pub/Sub access: tap \"Grant push access\" in Settings once")
            }
            graph.oauth.setPushScopeWanted(pushEnabled)
        }

    // -------------------------------------------------------------------------------- backup

    suspend fun exportBackup(passphrase: String?): String {
        val json = graph.repository.exportBackup()
        return if (passphrase.isNullOrBlank()) json else BackupCrypto.encrypt(json, passphrase)
    }

    fun isBackupEncrypted(text: String): Boolean = BackupCrypto.isEncrypted(text)

    fun importBackup(
        text: String,
        passphrase: String?,
        onResult: (Result<Int>) -> Unit,
    ) = viewModelScope.launch {
        val json =
            if (BackupCrypto.isEncrypted(text)) {
                if (passphrase.isNullOrBlank()) {
                    onResult(Result.failure(BackupCrypto.WrongPassphraseException()))
                    return@launch
                }
                val decrypted = runCatching { BackupCrypto.decrypt(text, passphrase) }
                val decryptFailure = decrypted.exceptionOrNull()
                if (decryptFailure != null) {
                    onResult(Result.failure(decryptFailure))
                    return@launch
                }
                decrypted.getOrThrow()
            } else {
                text
            }
        val result = graph.repository.importBackup(json)
        result.onSuccess { count -> messages.emit("Restored $count filter(s) from backup") }
        result.onFailure { messages.emit("Backup could not be read: ${it.message}") }
        onResult(result)
    }

    // --------------------------------------------------------------------------------- gmail

    fun disconnectGmail() =
        viewModelScope.launch {
            if (state.value.enabled) graph.repository.setForwarding(false)
            // Fetched before disconnect() clears the token it needs -- the one reliable way to
            // recover an account to revoke against when the deprecated sign-in bridge disconnect()
            // otherwise falls back to has come back empty. See GmailOAuthManager.disconnect's own
            // doc comment for why this matters.
            val fallbackEmail = suspendRunCatching { graph.gmail.currentAccountEmail() }.getOrNull()
            val revoked = graph.oauth.disconnect(fallbackEmail)
            graph.gmail.clearSession()
            graph.alerts.clearAuthorizationRequired()
            graph.repository.recordEvent(
                EventType.AUTH,
                if (revoked) {
                    "Gmail access revoked; forwarding switched off and queued email retained"
                } else {
                    "Local Gmail access disabled, but Google grant revocation could not be confirmed; forwarding switched off"
                },
            )
            messages.emit(
                if (revoked) {
                    "Gmail disconnected and access revoked; forwarding switched off"
                } else {
                    "Local Gmail access disabled; remove SCIF Sidekick in Google Account permissions if needed"
                },
            )
        }

    fun onGmailConnected() {
        graph.alerts.clearAuthorizationRequired()
        messages.tryEmit("Gmail connected")
    }

    // --------------------------------------------------------------------------- diagnostics

    /**
     * Runs a synthetic message through the exact real filter pipeline ([sourceType] simulates
     * SMS/MMS/RCS/a missed call; [senderAddress] lets the user test a specific allow/block-listed
     * number, [body] a specific keyword condition) and reports back exactly what happened --
     * which filter matched and queued, or why none did -- so this genuinely validates the user's
     * own configuration instead of just checking that the button works. [imageUris] (MMS only)
     * are copied into the same app-private attachment store every other live/reply attachment
     * uses, through the same size caps; copies that end up unused (nothing queued them) are
     * deleted immediately rather than left for the 24-hour orphan sweep.
     */
    fun sendTestMessage(
        sourceType: String,
        senderAddress: String,
        body: String,
        imageUris: List<Uri> = emptyList(),
    ) = viewModelScope.launch {
        val attachmentPaths =
            imageUris.mapIndexedNotNull { index, uri ->
                graph.attachments.copyFromUri(uri, "test_mms_${System.currentTimeMillis()}_$index")
            }
        val skippedAttachments = imageUris.size - attachmentPaths.size
        val outcome = graph.repository.sendTestMessage(sourceType, senderAddress, body, attachmentPaths)
        if (!outcome.queued && attachmentPaths.isNotEmpty()) graph.attachments.delete(attachmentPaths)
        if (state.value.enabled) runCatching { ForwardingService.start(getApplication()) }
        val summary =
            outcome.trail
                .map { it.reason }
                .ifEmpty { listOf("Nothing was logged for this test message") }
                .joinToString(" → ") +
                if (skippedAttachments > 0) " ($skippedAttachments photo(s) could not be copied and were skipped)" else ""
        messages.emit(if (outcome.queued) "Test message queued: $summary" else "Test message not forwarded: $summary")
    }

    /**
     * Sends one plain email directly to the connected Gmail account itself, completely bypassing
     * filters, templates, and the send queue -- the point is to isolate "is OAuth/Gmail API
     * connectivity itself working" from "do my filter rules match," which [sendTestMessage]
     * already covers. Sending to the signed-in account rather than an arbitrary address needs no
     * extra input and can't reach anyone else.
     */
    fun testGmailConnectivity() =
        viewModelScope.launch {
            if (!graph.oauth.isAuthorized && !graph.debug.fakeEmailTransport) {
                messages.emit("Connect Gmail before testing connectivity")
                return@launch
            }
            val now = System.currentTimeMillis()
            val result =
                suspendRunCatching {
                    val toAddress = graph.gmail.currentAccountEmail() ?: error("Could not read the connected Gmail account's address")
                    val sentAt = DateFormat.getDateTimeInstance().format(Date(now))
                    graph.gmail.send(
                        payload =
                            EmailPayload(
                                destinations = listOf(toAddress),
                                replyTarget = null,
                                senderDisplay = "SCIF Sidekick",
                                body = "SCIF Sidekick Gmail connectivity test, sent $sentAt.",
                                receivedAtMs = now,
                                source = "test",
                                participants = emptyList(),
                                attachmentNotice = null,
                                renderedSubject = "SCIF Sidekick connectivity test",
                                renderedBody =
                                    "This is a direct Gmail connectivity test from SCIF Sidekick, sent $sentAt.\n\n" +
                                        "It bypasses your filters entirely -- if you're reading this, OAuth and the " +
                                        "Gmail send API are both working.",
                                filterName = "(connectivity test)",
                            ),
                        attachmentPaths = emptyList(),
                        deliveryKey = "connectivity-test-$now",
                        verifyPriorDelivery = false,
                    )
                }
            result
                .onSuccess {
                    graph.repository.recordEvent(EventType.SENT, "Gmail connectivity test sent successfully")
                    messages.emit("Test email sent to your connected Gmail account — check your inbox")
                }.onFailure { failure ->
                    graph.repository.recordEvent(EventType.SEND_FAILED, "Gmail connectivity test failed: ${failure.message}")
                    messages.emit("Connectivity test failed: ${failure.message}")
                }
        }

    /** Gmail push (beta) diagnostic: calls users.watch() once, then immediately pulls the
     *  subscription once (there is nothing to receive yet since no mail changed, so a bare pull
     *  is expected to return `false` -- reaching that point without an exception is itself the
     *  proof both calls are authorized and both resource names are valid). Never touches reply
     *  processing or the 30s poll -- purely a "is this configured correctly" check. */
    fun testPushSetup() =
        viewModelScope.launch {
            if (!graph.oauth.isAuthorized && !graph.debug.fakeEmailTransport) {
                messages.emit("Connect Gmail before testing push setup")
                return@launch
            }
            val settings = graph.repository.currentAppSettings()
            if (settings.pubsubTopicName.isBlank() || settings.pubsubSubscriptionName.isBlank()) {
                messages.emit("Enter a Pub/Sub topic and subscription name first")
                return@launch
            }
            val result =
                suspendRunCatching {
                    val expirationMs = graph.gmailPush.startWatch(settings.pubsubTopicName)
                    graph.database.stateDao().updateGmailWatchExpiration(expirationMs)
                    graph.gmailPush.pollPush(settings.pubsubSubscriptionName)
                }
            result
                .onSuccess {
                    graph.repository.recordEvent(EventType.PUSH_WATCH_RENEWED, "Push setup test succeeded")
                    messages.emit("Push setup looks good — watch active, subscription reachable")
                }.onFailure { failure ->
                    graph.repository.recordEvent(EventType.PUSH_WATCH_FAILED, "Push setup test failed: ${failure.message}")
                    messages.emit("Push setup test failed: ${failure.message}")
                }
        }

    /** Best-effort: tells Gmail to stop publishing to whatever topic was watched, called when the
     *  user flips the Gmail push (beta) switch off. See [GmailPushGateway.stopWatch]'s own doc
     *  comment for why a failure here is a non-issue either way. */
    fun disablePush() = viewModelScope.launch { graph.gmailPush.stopWatch() }

    // ------------------------------------------------------------------------------- export

    /** Builds the CSV for [startMs, endMs] on a background dispatcher (this can scan a lot of
     *  rows) and hands the caller the finished text to write wherever it chose via SAF. */
    suspend fun exportMessageLog(
        startMs: Long,
        endMs: Long,
        includeBody: Boolean,
    ): String =
        withContext(Dispatchers.IO) {
            MessageLogExport.toCsv(graph.repository.messagesForExport(startMs, endMs), includeBody)
        }

    // -------------------------------------------------------------------------------- debug

    fun setFakeTransport(enabled: Boolean) {
        graph.debug.fakeEmailTransport = enabled
        if (enabled) graph.alerts.clearAuthorizationRequired()
        messages.tryEmit("Debug email transport ${if (enabled) "enabled" else "disabled"}")
    }

    fun injectSynthetic(count: Int) =
        viewModelScope.launch(Dispatchers.IO) {
            if (!BuildConfig.DEBUG) return@launch
            val bounded = count.coerceIn(1, 2_000)
            val base = System.currentTimeMillis()
            repeat(bounded) { index ->
                graph.repository.processIncoming(
                    IncomingMessage(
                        source = "sms",
                        senderAddress = "+15550001000",
                        senderDisplay = "Synthetic sender",
                        body = "Synthetic rate-limit test message ${base}_$index",
                        receivedAtMs = base + index + 1L,
                        sourceTimestampMs = base + index + 1L,
                    ),
                )
            }
            messages.emit("Injected $bounded synthetic live events into the normal pipeline")
        }

    fun failNextFive() {
        if (!BuildConfig.DEBUG) return
        graph.debug.failNextEmailAttempts(5)
        messages.tryEmit("The next five email attempts will fail")
    }
}
