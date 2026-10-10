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
import com.scifsidekick.cleanroom.data.REMOTE_GRAPH_SEEDED
import com.scifsidekick.cleanroom.email.gmailDisconnectedAfterOutlookSignIn
import com.scifsidekick.cleanroom.email.graph.DeviceCodeResult
import com.scifsidekick.cleanroom.email.graph.MsAccountPreferences
import com.scifsidekick.cleanroom.messaging.EmailPayload
import com.scifsidekick.cleanroom.messaging.IncomingMessage
import com.scifsidekick.cleanroom.service.ForwardingService
import com.scifsidekick.cleanroom.service.SelfTestReceipt
import com.scifsidekick.cleanroom.service.SnoozeWorker
import com.scifsidekick.cleanroom.service.StatusWidgetProvider
import com.scifsidekick.cleanroom.util.BackupCrypto
import com.scifsidekick.cleanroom.util.MessageLogExport
import com.scifsidekick.cleanroom.util.suspendRunCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
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

    suspend fun fetchGmailAccountEmail(): String? = graph.gmail.accountEmail()
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
            if (enabled && !graph.debug.fakeEmailTransport) {
                // Any connected account will do: Gmail, or Outlook when it is set up. Gmail-only
                // installs see exactly the old check and text (the router is Gmail alone there).
                val (anyAvailable, outlookSetUp) = withContext(Dispatchers.IO) { graph.mail.isAvailable to graph.microsoftConfigured() }
                if (!anyAvailable) {
                    messages.emit(if (outlookSetUp) "Connect Gmail or Outlook before enabling forwarding" else "Connect Gmail before enabling forwarding")
                    return@launch
                }
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
            // Outlook, when it is connected, keeps forwarding going; otherwise switch it off as before.
            val outlookCarriesOn = withContext(Dispatchers.IO) { graph.microsoftConfigured() && graph.graphMail.isAvailable }
            if (state.value.enabled && !outlookCarriesOn) graph.repository.setForwarding(false)
            graph.gmailDisconnectedOnPurpose = true
            // Fetched before disconnect() clears the token it needs -- the one reliable way to
            // recover an account to revoke against when the deprecated sign-in bridge disconnect()
            // otherwise falls back to has come back empty. See GmailOAuthManager.disconnect's own
            // doc comment for why this matters.
            val fallbackEmail = suspendRunCatching { graph.gmail.accountEmail() }.getOrNull()
            val revoked = graph.oauth.disconnect(fallbackEmail)
            graph.gmail.clearSession()
            graph.alerts.clearAuthorizationRequired()
            graph.repository.recordEvent(
                EventType.AUTH,
                when {
                    outlookCarriesOn && revoked -> "Gmail access revoked; Outlook keeps forwarding"
                    outlookCarriesOn -> "Local Gmail access disabled, but Google grant revocation could not be confirmed; Outlook keeps forwarding"
                    revoked -> "Gmail access revoked; forwarding switched off and queued email retained"
                    else -> "Local Gmail access disabled, but Google grant revocation could not be confirmed; forwarding switched off"
                },
            )
            messages.emit(
                when {
                    outlookCarriesOn && revoked -> "Gmail disconnected and access revoked; Outlook keeps forwarding"
                    outlookCarriesOn -> "Local Gmail access disabled; Outlook keeps forwarding. Remove SCIF Sidekick in Google Account permissions if needed"
                    revoked -> "Gmail disconnected and access revoked; forwarding switched off"
                    else -> "Local Gmail access disabled; remove SCIF Sidekick in Google Account permissions if needed"
                },
            )
        }

    fun onGmailConnected() {
        graph.gmailDisconnectedOnPurpose = false
        graph.alerts.clearAuthorizationRequired()
        messages.tryEmit("Gmail connected")
    }

    // ----------------------------------------------------------------------------- microsoft

    private var signInJob: Job? = null
    private var signInProgress: SignInProgress = SignInProgress.None
    private val microsoftStateFlow = MutableStateFlow<MicrosoftUiState>(MicrosoftUiState.NotConfigured)

    /** The Microsoft (Outlook.com) card. Call [refreshMicrosoftState] when the screen is shown again. */
    val microsoftState: StateFlow<MicrosoftUiState> = microsoftStateFlow.asStateFlow()

    private val microsoftAccountFlow = MutableStateFlow(MicrosoftAccountFacts(email = null, authorized = false))

    /** The stored account alone (no sign-in in progress): drives the Home chip and the card's Disconnect. */
    val microsoftAccount: StateFlow<MicrosoftAccountFacts> = microsoftAccountFlow.asStateFlow()

    /** A sign-in code the user copied, to clear from the clipboard once that sign-in is over. */
    private var copiedSignInCode: String? = null

    val preferredProvider: String get() = graph.msPrefs.preferredProvider

    /** The stored Application (client) ID, "" when unset. */
    val microsoftClientId: String get() = graph.msPrefs.clientId

    init {
        refreshMicrosoftState()
    }

    /** Re-reads the stored account state (a sign-in can be revoked or expire while the app is closed). */
    fun refreshMicrosoftState() =
        viewModelScope.launch {
            val (clientId, email, authorized) =
                withContext(Dispatchers.IO) { Triple(graph.msPrefs.clientId, graph.msPrefs.accountEmail, graph.msOAuth.isAuthorized) }
            microsoftAccountFlow.value = MicrosoftAccountFacts(email, authorized)
            microsoftStateFlow.value = microsoftStateFor(clientId, email, authorized, signInProgress)
        }

    private fun setSignInProgress(progress: SignInProgress) {
        signInProgress = progress
        refreshMicrosoftState()
        if (progress !is SignInProgress.Waiting) clearCopiedSignInCode(final = false)
    }

    fun onSignInCodeCopied(code: String) {
        copiedSignInCode = code
    }

    /**
     * Best effort: once the sign-in has left the code step (connected, cancelled, expired, failed),
     * removes the copied code from the clipboard if it is still the current clip. Android only
     * lets a focused app read the clipboard, so the activity calls this again with [final] when it
     * regains focus; [final] forgets the code even when the clip could not be read.
     */
    fun clearCopiedSignInCode(final: Boolean) {
        val code = copiedSignInCode ?: return
        if (signInProgress is SignInProgress.Waiting) return
        if (SignInCodeClipboard.clearIfCurrent(getApplication(), code) || final) copiedSignInCode = null
    }

    fun setMicrosoftClientId(id: String) {
        graph.msPrefs.clientId = id
        if (signInProgress is SignInProgress.Failed) signInProgress = SignInProgress.None
        refreshMicrosoftState()
    }

    fun setPreferredProvider(provider: String) {
        graph.msPrefs.preferredProvider = provider
    }

    /**
     * Device-code sign-in: shows the code, waits for the user to enter it on any device, then
     * remembers the address, authorizes it for remote control once, and clears any Outlook alert.
     */
    fun startMicrosoftSignIn() {
        if (signInJob?.isActive == true) return
        signInJob =
            viewModelScope.launch {
                setSignInProgress(SignInProgress.Connecting)
                graph.graphMail.clearSession() // forget a previous account's cached address
                // With no account connected, any stored ciphertext or Keystore key is leftover (or
                // corrupt) and would make every sign-in fail to save; start clean.
                withContext(Dispatchers.IO) { if (!graph.msOAuth.isAuthorized) graph.msOAuth.disconnect() }
                val code =
                    try {
                        graph.msOAuth.startDeviceCode()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        setSignInProgress(SignInProgress.Failed(signInFailureMessage(failure)))
                        return@launch
                    }
                setSignInProgress(
                    SignInProgress.Waiting(code.userCode, code.verificationUri, System.currentTimeMillis() + code.expiresInSec * 1_000L),
                )
                when (val result = graph.msOAuth.awaitDeviceCode(code)) {
                    is DeviceCodeResult.Connected -> {
                        setSignInProgress(SignInProgress.Connecting)
                        val email = graph.graphMail.accountEmail() ?: result.accountEmail
                        withContext(Dispatchers.IO) {
                            graph.msPrefs.accountEmail = email
                            // Outlook-only install: no Gmail to remind the user about (connecting Gmail clears this).
                            graph.gmailDisconnectedOnPurpose =
                                gmailDisconnectedAfterOutlookSignIn(graph.gmailDisconnectedOnPurpose, graph.gmail.isAvailable)
                        }
                        email?.let { graph.repository.seedRemoteControlSender(it, REMOTE_GRAPH_SEEDED) }
                        withContext(Dispatchers.IO) { graph.authAlerts.recovered(MsAccountPreferences.PROVIDER_GRAPH) }
                        graph.repository.recordEvent(EventType.AUTH, "Outlook connected${email?.let { " ($it)" }.orEmpty()}")
                        setSignInProgress(SignInProgress.None)
                        messages.emit("Outlook connected")
                    }
                    is DeviceCodeResult.Failed -> setSignInProgress(SignInProgress.Failed(result.reason))
                    DeviceCodeResult.Cancelled -> setSignInProgress(SignInProgress.None)
                }
            }
    }

    fun cancelMicrosoftSignIn() {
        signInJob?.cancel()
        signInJob = null
        setSignInProgress(SignInProgress.None)
    }

    /** Signs Outlook out on this phone. Gmail and the forwarding switch are not touched. */
    fun disconnectMicrosoft() {
        signInJob?.cancel()
        signInJob = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                graph.msOAuth.disconnect()
                graph.msPrefs.clearAccount()
            }
            graph.graphMail.clearSession()
            withContext(Dispatchers.IO) { graph.authAlerts.disconnected(MsAccountPreferences.PROVIDER_GRAPH) }
            graph.repository.recordEvent(EventType.AUTH, "Outlook disconnected on this phone")
            setSignInProgress(SignInProgress.None)
            messages.emit("Outlook disconnected")
        }
    }

    /** Fixed texts only: an exception message from the network layer could carry hosts or ids. */
    private fun signInFailureMessage(failure: Exception): String =
        when (failure) {
            is IOException -> "Could not reach Microsoft. Check the connection and try again."
            // MsOAuthManager's own failures carry fixed, secret-free texts.
            is IllegalStateException -> failure.message ?: GENERIC_SIGN_IN_FAILURE
            else -> GENERIC_SIGN_IN_FAILURE
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
                    val toAddress = graph.gmail.accountEmail() ?: error("Could not read the connected Gmail account's address")
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

    /**
     * Queues a test receipt to the connected Gmail account through the real receipt path (see
     * [SelfTestReceipt]). The snackbar says what was queued, not that it arrived: arrival is the proof.
     */
    fun sendTestReceipt() =
        viewModelScope.launch {
            val message =
                when (val outcome = suspendRunCatching { withContext(Dispatchers.IO) { SelfTestReceipt.send(getApplication(), graph) } }.getOrNull()) {
                    is SelfTestReceipt.Outcome.Queued ->
                        "Test receipt queued to ${outcome.recipient} via ${outcome.via}. It should arrive within a minute or two."
                    SelfTestReceipt.Outcome.NotConnected ->
                        if (withContext(Dispatchers.IO) { graph.microsoftConfigured() }) {
                            "Connect a mail account before sending a test receipt"
                        } else {
                            "Connect Gmail before sending a test receipt"
                        }
                    SelfTestReceipt.Outcome.NotQueued -> "Test receipt was not queued (storage limit reached). See Activity."
                    null -> "Test receipt could not be queued. See Activity."
                }
            messages.emit(message)
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

    private companion object {
        const val GENERIC_SIGN_IN_FAILURE = "Microsoft sign-in failed. Try again."
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
