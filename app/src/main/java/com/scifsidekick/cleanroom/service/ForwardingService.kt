package com.scifsidekick.cleanroom.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.IBinder
import android.provider.Telephony
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import com.scifsidekick.cleanroom.AppGraph
import com.scifsidekick.cleanroom.data.AppSettingsEntity
import com.scifsidekick.cleanroom.data.EventType
import com.scifsidekick.cleanroom.data.ForwardingStateEntity
import com.scifsidekick.cleanroom.data.QueueChannel
import com.scifsidekick.cleanroom.data.QueueCapacityException
import com.scifsidekick.cleanroom.email.GmailReply
import com.scifsidekick.cleanroom.messaging.IncomingMessageReceiver
import com.scifsidekick.cleanroom.util.PhoneNumbers
import com.scifsidekick.cleanroom.util.PremiumNumbers
import com.scifsidekick.cleanroom.util.RemoteCommand
import com.scifsidekick.cleanroom.util.RemoteCommands
import com.scifsidekick.cleanroom.util.RemoteControlCodec
import com.scifsidekick.cleanroom.util.ReplyBodyCleaner
import com.scifsidekick.cleanroom.util.ReplySafetyPolicy
import com.scifsidekick.cleanroom.util.suspendRunCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ForwardingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var graph: AppGraph
    private val dynamicReceiver = IncomingMessageReceiver()
    private var loop: Job? = null
    private var replyPollJob: Job? = null
    private var lastReplyPollMs = 0L
    private var bounceCheckJob: Job? = null
    private var lastBounceCheckMs = 0L
    private var pushPollJob: Job? = null
    private var lastPushPullMs = 0L
    private var lastPushFailureLogMs = 0L
    private var lastHeartbeatWriteMs = 0L
    private var lastMaintenanceMs = 0L
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastPostedNotificationKey: Triple<ForwardingStateEntity?, Int, Boolean>? = null

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.from(this)
        graph.alerts.createChannels()
        startForeground(AlertNotifier.FOREGROUND_NOTIFICATION_ID, graph.alerts.foreground(null))
        registerLiveReceivers()
        registerNetworkCallback()
        loop =
            scope.launch {
                graph.repository.ensureInitialized()
                graph.queueProcessor.recoverInterruptedWork()
                if (graph.database
                        .stateDao()
                        .get()
                        ?.enabled == true && !graph.gmail.isAvailable
                ) {
                    graph.alerts.showAuthorizationRequired()
                }
                while (isActive) {
                    val passStartedAtMs = System.currentTimeMillis()
                    try {
                        val state = graph.database.stateDao().get()
                        if (state?.enabled != true) {
                            // Nothing left for this loop to usefully do, and Android requires a
                            // foreground service to keep an ongoing notification pinned in the
                            // shade the entire time it's alive -- staying running with nothing
                            // to do would leave that notification stuck there for no reason,
                            // exactly what turning the master switch off is supposed to stop.
                            // IncomingMessageReceiver only starts this service when forwarding
                            // is already on (see its own comment), so the common path never even
                            // reaches this branch; it exists for the other entry points --
                            // MainActivity opening, BootReceiver -- that start the service
                            // unconditionally, self-correcting here within one tick instead of
                            // duplicating an enabled check at every call site that can start it.
                            graph.repository.recordServiceEvent("Forwarding is off; stopping until re-enabled")
                            ForwardWakeLock.releaseIfAcquiredBefore(Long.MAX_VALUE)
                            stopSelf()
                            break
                        }
                        graph.queueProcessor.drainTelephony(10)
                        graph.queueProcessor.drain(QueueChannel.EMAIL, 20)
                        val now = System.currentTimeMillis()
                        if (now - lastReplyPollMs >= REPLY_POLL_INTERVAL_MS && replyPollJob?.isActive != true) {
                            lastReplyPollMs = now
                            replyPollJob = scope.launch { pollReplies() }
                        }
                        if (now - lastBounceCheckMs >= BOUNCE_CHECK_INTERVAL_MS && bounceCheckJob?.isActive != true) {
                            lastBounceCheckMs = now
                            bounceCheckJob = scope.launch { checkBounces() }
                        }
                        if (now - lastPushPullMs >= PUSH_PULL_INTERVAL_MS && pushPollJob?.isActive != true) {
                            lastPushPullMs = now
                            pushPollJob = scope.launch { pollPush() }
                        }
                        // A liveness signal for WatchdogWorker, not a per-tick write -- see
                        // SidekickRepository.recordHeartbeat's own doc comment.
                        if (now - lastHeartbeatWriteMs >= HEARTBEAT_INTERVAL_MS) {
                            lastHeartbeatWriteMs = now
                            graph.repository.recordHeartbeat(now)
                            // Piggybacks on the same once-a-minute cadence rather than owning a
                            // separate timer -- keeps the widget's queued count and "last sent"
                            // detail from drifting noticeably stale while forwarding runs, without
                            // pushing a render on every single queue drain tick.
                            StatusWidgetProvider.requestUpdate(applicationContext)
                        }
                        if (now - lastMaintenanceMs >= MAINTENANCE_INTERVAL_MS) {
                            lastMaintenanceMs = now
                            graph.queueProcessor.recoverTimedOutTelephonyWork()
                        }
                        updateNotification(state)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        graph.repository.recordServiceEvent(
                            "Service loop error: ${(failure.message ?: failure.javaClass.simpleName).take(500)}",
                        )
                    }
                    ForwardWakeLock.releaseIfAcquiredBefore(passStartedAtMs)
                    QueueWakeSignal.awaitOrTimeout(QUEUE_TICK_MS)
                }
            }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ForwardWakeLock.releaseIfAcquiredBefore(Long.MAX_VALUE)
        loop?.cancel()
        scope.coroutineContext[Job]?.cancel()
        runCatching { unregisterReceiver(dynamicReceiver) }
        networkCallback?.let { callback ->
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
        }
        super.onDestroy()
    }

    /** Makes every backed-off queue row immediately eligible again the moment connectivity
     *  returns, instead of waiting out a retry backoff that may have nothing to do with why it
     *  originally failed -- bounded by the fact that the hard and soft rate ceilings still gate
     *  whether anything actually sends. Opt-out via Settings -> Retry when network reconnects. */
    private fun registerNetworkCallback() {
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch {
                        if (graph.repository.currentAppSettings().retryOnNetworkReconnect) {
                            val reset = graph.database.queueDao().resetQueuedNotBefore(System.currentTimeMillis())
                            if (reset > 0) {
                                graph.repository.recordServiceEvent(
                                    "Network reconnected; $reset queued item(s) made immediately eligible for retry",
                                )
                            }
                        }
                    }
                }
            }
        runCatching {
            val connectivityManager =
                getSystemService(ConnectivityManager::class.java) ?: error("ConnectivityManager unavailable")
            connectivityManager.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
            networkCallback = callback
        }.onFailure { failure ->
            scope.launch {
                graph.repository.recordServiceEvent("Network reconnect listener could not be registered: ${failure.message}")
            }
        }
    }

    private fun registerLiveReceivers() {
        // The broadcastPermission argument restricts who may deliver these actions to this
        // receiver to callers holding BROADCAST_SMS (the telephony stack only) -- without it,
        // any app could hand this exported, dynamically-registered copy a forged SMS/MMS intent.
        runCatching {
            ContextCompat.registerReceiver(
                this,
                dynamicReceiver,
                IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION),
                android.Manifest.permission.BROADCAST_SMS,
                null,
                ContextCompat.RECEIVER_EXPORTED,
            )
        }.onFailure { failure ->
            scope.launch {
                graph.repository.recordServiceEvent(
                    "Dynamic SMS receiver registration failed; manifest receiver remains active: ${failure.message}",
                )
            }
        }
        val mms =
            IntentFilter(Telephony.Sms.Intents.WAP_PUSH_RECEIVED_ACTION).apply {
                addDataType("application/vnd.wap.mms-message")
            }
        runCatching {
            ContextCompat.registerReceiver(
                this,
                dynamicReceiver,
                mms,
                android.Manifest.permission.BROADCAST_SMS,
                null,
                ContextCompat.RECEIVER_EXPORTED,
            )
        }.onFailure { failure ->
            scope.launch {
                graph.repository.recordServiceEvent(
                    "Dynamic MMS receiver registration failed; manifest receiver remains active: ${failure.message}",
                )
            }
        }
    }

    private suspend fun pollReplies() {
        val result =
            try {
                graph.gmail.unreadReplies(graph.repository.recentProcessedGmailIds())
            } catch (required: com.scifsidekick.cleanroom.email.ReauthorizationRequiredException) {
                graph.alerts.showAuthorizationRequired()
                graph.repository.recordEvent(
                    EventType.AUTH_REQUIRED,
                    "Gmail reply polling paused until the user reconnects",
                )
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (graph.oauth.isAuthorized) {
                    graph.repository.recordServiceEvent(
                        "Gmail reply poll failed: ${(failure.message ?: failure.javaClass.simpleName).take(500)}",
                    )
                }
                return
            }

        result.fetchFailures.forEach { failure ->
            graph.repository.recordServiceEvent("Gmail reply could not be parsed or fetched: $failure")
        }
        val settings = graph.repository.currentAppSettings()
        result.replies.forEach { reply ->
            if (graph.repository.wasSentByThisApp(reply.id, reply.rfcMessageId)) {
                // Our own forwarded notification landed back in a self-forwarded inbox -- never
                // a reply or compose-new attempt, whatever its subject happens to contain.
                graph.repository.recordIgnoredGmailCandidate(reply.id)
                markGmailMessageRead(reply.id)
                return@forEach
            }
            // Checked here rather than in RemoteEnableWorker, and before the target extraction
            // below, because this loop is the only thing that reliably sees such a message at all:
            // it runs only while forwarding is on -- exactly when a "turn it off" command is
            // meaningful -- and the `target == null` branch further down would otherwise consume
            // this subject as an unrecognized routing command (marking it read and recording it as
            // an ignored candidate) within 30 seconds, long before any 15-minute worker could find
            // it still unread. See RemoteCommands' own doc comment for the full split.
            if (RemoteCommands.isDisableForwardingCommand(reply.subject)) {
                handleRemoteDisableCommand(reply, settings)
                return@forEach
            }
            // A status query is meaningful in either state, so both this poll and RemoteEnableWorker
            // can answer one; this is simply the path that is alive while forwarding is on. No
            // drain needed here -- the loop this poll belongs to is already draining the queue.
            if (RemoteCommands.isStatusCommand(reply.subject)) {
                graph.repository.recordIgnoredGmailCandidate(reply.id)
                RemoteStatusResponder.answer(graph, reply, settings, drainAfterQueueing = false)
                return@forEach
            }
            // Same shape as the status query above: either state can answer it, no drain needed here.
            if (RemoteCommands.isHelpCommand(reply.subject)) {
                graph.repository.recordIgnoredGmailCandidate(reply.id)
                RemoteHelpResponder.answer(graph, reply, settings, drainAfterQueueing = false)
                return@forEach
            }
            val replyTarget = PhoneNumbers.extractFromSubject(reply.subject)
            val composeTarget = if (replyTarget == null) PhoneNumbers.extractComposeTarget(reply.subject) else null
            val target = replyTarget ?: composeTarget
            if (target == null) {
                graph.repository.recordIgnoredGmailCandidate(reply.id)
                if (looksLikeRoutingCommand(reply.subject)) markGmailMessageRead(reply.id)
                return@forEach
            }

            val authorized =
                if (replyTarget != null) {
                    graph.repository.isAuthorizedReply(
                        targetNumber = target,
                        threadId = reply.threadId,
                        referencedMessageIds = reply.referencedMessageIds,
                        authenticatedSender = reply.authenticatedFromAddress,
                    )
                } else {
                    settings.remoteControlEnabled &&
                        RemoteControlCodec.isAuthorized(
                            settings.remoteControlSendersJson,
                            reply.authenticatedFromAddress,
                            RemoteControlCodec.Sender::canCompose,
                        )
                }
            if (!authorized) {
                // The security gate above is intentionally one strict boolean -- see
                // isAuthorizedReply/isAuthorizedCompose -- but a single generic log line for every
                // way it can fail ("route or authenticated sender did not match") makes a real
                // failure indistinguishable from all the others after the fact, purely a
                // diagnostics gap, not a security one. This breaks the two most common causes
                // apart without touching the actual gate: no aligned DMARC pass at all (a sender
                // domain's own DKIM/SPF misconfiguration -- outside this app's control) versus a
                // present, authenticated sender whose reply carried no In-Reply-To/References
                // header at all (some mail clients drop threading headers on reply) -- both would
                // otherwise look identical in History.
                val reason =
                    if (replyTarget != null) {
                        when {
                            reply.authenticatedFromAddress == null ->
                                "Blocked email-to-SMS request: sender could not be authenticated -- Gmail did not " +
                                    "report an aligned DMARC pass for this sender's domain (check that domain's own " +
                                    "SPF/DKIM/DMARC setup, not just this app's configuration)"
                            reply.referencedMessageIds.isEmpty() ->
                                "Blocked email-to-SMS request: sender was authenticated, but the reply carried no " +
                                    "In-Reply-To/References header at all, so it could not be linked to the original " +
                                    "forward (some mail clients drop threading headers on reply)"
                            else -> {
                                // The exact-match comparison itself, made visible: authorizedReplySendersJson is
                                // populated from the filter's configured recipient list at send time, so this is
                                // the single most likely place a same-person-different-alias mismatch (a second
                                // address on the same mailbox, say) would otherwise stay invisible forever.
                                val authorizedSenders =
                                    graph.repository.authorizedSendersForRoute(reply.threadId, reply.referencedMessageIds, target)
                                "Blocked email-to-SMS request: reply authenticated as '${reply.authenticatedFromAddress}', " +
                                    "but the route to $target only authorizes " +
                                    (authorizedSenders.joinToString(", ").ifBlank { "(no senders recorded on that route)" })
                            }
                        }
                    } else {
                        "Blocked compose-new request: authenticated sender was not allowed, or the feature is off"
                    }
                graph.repository.recordReplyWithoutSms(
                    gmailMessageId = reply.id,
                    targetNumber = target,
                    reason = reason,
                    eventType = EventType.SECURITY,
                )
                markGmailMessageRead(reply.id)
                return@forEach
            }

            val content =
                try {
                    graph.gmail.fetchContent(reply)
                } catch (required: com.scifsidekick.cleanroom.email.ReauthorizationRequiredException) {
                    graph.alerts.showAuthorizationRequired()
                    graph.repository.recordEvent(
                        EventType.AUTH_REQUIRED,
                        "Gmail reply content could not be fetched until the account is reconnected",
                    )
                    return@forEach
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    graph.repository.recordServiceEvent(
                        "Authorized Gmail reply content could not be fetched: ${(failure.message ?: failure.javaClass.simpleName).take(300)}",
                    )
                    return@forEach
                }
            val cleanBody = ReplyBodyCleaner.clean(content.body)
            val rejection =
                ReplySafetyPolicy.rejectionReason(cleanBody, allowBlank = content.imageBytes != null)
                    ?: PremiumNumbers.rejectionReason(target)
            if (rejection != null) {
                graph.repository.recordReplyWithoutSms(
                    gmailMessageId = reply.id,
                    targetNumber = target,
                    reason = "Reply not sent: $rejection",
                )
                markGmailMessageRead(reply.id)
                return@forEach
            }

            // Applies identically whether this is a reply to an existing thread or a compose-new
            // trigger -- an attached image turns either one into a picture message the same way,
            // there is nothing trigger-type-specific about how the attachment gets stored and
            // queued.
            try {
                val imageBytes = content.imageBytes
                if (imageBytes != null) {
                    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(content.imageMimeType ?: "image/jpeg") ?: "jpg"
                    val imagePath = graph.attachments.writeBytes(imageBytes, "mms_${reply.id}.$extension")
                    if (imagePath != null) {
                        val queued =
                            runCatching {
                                graph.repository.enqueueMmsReplyIfNew(
                                    reply.id,
                                    target,
                                    cleanBody,
                                    imagePath,
                                    reply.authenticatedFromAddress,
                                    reply.threadId,
                                )
                            }
                                .getOrElse { failure ->
                                    graph.attachments.delete(listOf(imagePath))
                                    throw failure
                                }
                        if (!queued) graph.attachments.delete(listOf(imagePath))
                    } else if (cleanBody.isBlank()) {
                        graph.repository.recordReplyWithoutSms(
                            gmailMessageId = reply.id,
                            targetNumber = target,
                            reason = "Picture reply was not sent because its image could not be stored and it had no text caption",
                        )
                    } else {
                        graph.repository.recordServiceEvent(
                            "Attached reply image could not be stored (too large or unreadable); sent as text-only instead",
                        )
                        graph.repository.enqueueReplyIfNew(
                            reply.id,
                            target,
                            cleanBody,
                            reply.authenticatedFromAddress,
                            reply.threadId,
                        )
                    }
                } else {
                    graph.repository.enqueueReplyIfNew(
                        reply.id,
                        target,
                        cleanBody,
                        reply.authenticatedFromAddress,
                        reply.threadId,
                    )
                }
            } catch (_: QueueCapacityException) {
                graph.repository.recordServiceEvent("Reply queue is full; Gmail command ${reply.id} was left unread for later retry")
                return@forEach
            }
            // Marking after the idempotent transaction means a failed modify call
            // is harmless: the next poll cannot queue a duplicate, then retries this.
            markGmailMessageRead(reply.id)
        }
    }

    /** "Gmail accepted a forward" (a successful [com.scifsidekick.cleanroom.email.GmailGateway.send]
     *  call) can never mean "the recipient's mail server actually delivered it" -- only a later
     *  delivery-status notification in the same inbox can reveal that. See
     *  [com.scifsidekick.cleanroom.email.GmailGateway.checkForBounces] for what counts as a match
     *  and its disclosed limitations. */
    private suspend fun checkBounces() {
        val notices =
            try {
                graph.gmail.checkForBounces()
            } catch (required: com.scifsidekick.cleanroom.email.ReauthorizationRequiredException) {
                // The reply poll already surfaces reauthorization; avoid a second, redundant alert.
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.repository.recordServiceEvent(
                    "Delivery-failure check failed: ${(failure.message ?: failure.javaClass.simpleName).take(300)}",
                )
                return
            }
        notices.forEach { notice ->
            var matchedAnySend = false
            notice.referencedRfcMessageIds.forEach { rfcMessageId ->
                val matched = graph.repository.recordBounce(rfcMessageId, "Delivery failure reported: ${notice.summary}")
                matchedAnySend = matchedAnySend || matched
            }
            if (!matchedAnySend) {
                graph.repository.recordServiceEvent(
                    "A delivery-failure notice arrived but could not be matched to a message this app sent: ${notice.summary}",
                )
            }
            markGmailMessageRead(notice.gmailMessageId)
        }
        if (notices.isNotEmpty()) graph.alerts.showDeliveryReviewRequired(notices.size)
    }

    /** Gmail push (beta): a short Pub/Sub pull, entirely separate from and much more frequent than
     *  [pollReplies]'s own 30s timer. Never touches reply processing itself -- a `true` result
     *  from [com.scifsidekick.cleanroom.email.GmailPushGateway.pollPush] just resets
     *  [lastReplyPollMs] to 0 so the unmodified [pollReplies] runs on the very next tick instead of
     *  waiting out the rest of its own interval. A no-op whenever push isn't configured, and any
     *  failure here (misconfigured Pub/Sub, missing scope grant, network) is swallowed and logged
     *  at most once an hour -- it can never block or slow down the 30s poll, which keeps running
     *  exactly as it does today regardless of whether this succeeds, fails, or is never set up. */
    private suspend fun pollPush() {
        val settings = graph.repository.currentAppSettings()
        if (!settings.gmailPushEnabled || settings.pubsubSubscriptionName.isBlank()) return
        try {
            if (graph.gmailPush.pollPush(settings.pubsubSubscriptionName)) {
                lastReplyPollMs = 0L
            }
        } catch (required: com.scifsidekick.cleanroom.email.ReauthorizationRequiredException) {
            // The reply poll already surfaces reauthorization; avoid a second, redundant alert.
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val now = System.currentTimeMillis()
            if (now - lastPushFailureLogMs >= PUSH_FAILURE_LOG_COOLDOWN_MS) {
                lastPushFailureLogMs = now
                graph.repository.recordEvent(
                    EventType.PUSH_PULL_FAILED,
                    "Gmail push pull failed: ${(failure.message ?: failure.javaClass.simpleName).take(300)}",
                )
            }
        }
    }

    /**
     * Turns the master switch off on an authorized `[SCIF:OFF]` command. Every exit path records
     * the candidate in the processed ledger and marks it read, so a command that was rejected can
     * never sit in the inbox being re-evaluated on each of this loop's 30-second ticks.
     *
     * No explicit service stop call here, deliberately: this loop notices the flag on its very next
     * tick and stops itself, which is the one place that is handled rather than duplicated at every
     * site that can flip the switch off (see [ForwardingTileService.onClick]'s own note). Turning
     * off also writes no watermark -- only an off-to-on transition does -- so a later re-enable
     * still starts forwarding from that moment and never backfills what arrived in between.
     */
    private suspend fun handleRemoteDisableCommand(
        reply: GmailReply,
        settings: AppSettingsEntity,
    ) {
        graph.repository.recordIgnoredGmailCandidate(reply.id)
        markGmailMessageRead(reply.id)
        if (!settings.remoteControlEnabled) return
        val authorized =
            RemoteControlCodec.isAuthorized(
                settings.remoteControlSendersJson,
                reply.authenticatedFromAddress,
                RemoteControlCodec.Sender::canDisable,
            )
        if (!authorized) {
            graph.repository.recordBlockedAttempt(
                "Blocked remote-disable email command: sender " +
                    (reply.authenticatedFromAddress ?: "could not be authenticated") +
                    " is not on the authorized list",
            )
            return
        }
        graph.repository.setForwarding(false)
        graph.repository.recordEvent(
            EventType.SERVICE,
            "Forwarding disabled remotely by email command from ${reply.authenticatedFromAddress}",
        )
        StatusWidgetProvider.requestUpdate(applicationContext)
        RemoteCommandReceipt.send(applicationContext, graph, RemoteCommand.DISABLE, reply)
    }

    private fun looksLikeRoutingCommand(subject: String): Boolean {
        val trimmed = subject.trimStart()
        return trimmed.contains("[SCIF:", ignoreCase = true) || trimmed.startsWith("TEXT+", ignoreCase = true)
    }

    private suspend fun markGmailMessageRead(messageId: String) {
        suspendRunCatching { graph.gmail.markRead(messageId) }
            .onFailure { failure ->
                graph.repository.recordServiceEvent(
                    "Could not mark Gmail reply $messageId read: ${(failure.message ?: failure.javaClass.simpleName).take(500)}",
                )
            }
    }

    /** [state] is the same row the loop just fetched for its own enabled check -- re-querying it
     *  here would just be a second identical read on every tick for no reason. Posting a fresh
     *  [android.app.Notification] via [android.app.NotificationManager.notify] is a binder call to
     *  system_server, not a free local operation; doing that on every single tick around the
     *  clock, even the overwhelming majority where nothing about the notification's content
     *  actually changed, measurably keeps the device from settling into a lower-power state over
     *  a full day. Skipping the call entirely when the key inputs haven't moved since the last
     *  post costs one cheap in-memory comparison instead. */
    private suspend fun updateNotification(state: ForwardingStateEntity?) {
        val queued = graph.database.queueDao().queuedCount()
        val gmailAvailable = graph.gmail.isAvailable
        val key = Triple(state, queued, gmailAvailable)
        if (key == lastPostedNotificationKey) return
        lastPostedNotificationKey = key
        getSystemService(android.app.NotificationManager::class.java).notify(
            AlertNotifier.FOREGROUND_NOTIFICATION_ID,
            graph.alerts.foreground(state, queued, gmailAvailable),
        )
    }

    companion object {
        // This is now a backstop ceiling, not the usual path: a freshly queued item wakes the
        // loop immediately via QueueWakeSignal (see IncomingMessageReceiver's call site), so the
        // common case is "next tick" in name only -- actual latency is the drain call plus the
        // Gmail round-trip, not this timer. The timer still matters for everything that becomes
        // ready without a fresh broadcast to wake on: a backed-off retry whose delay has elapsed,
        // a soft/hard rate-limit deferral clearing, or the queue depth shown in the foreground
        // notification going stale. 5s (17,280 ticks/day) measurably kept the device from
        // settling into a lower-power state; 10s halves that, and no longer trades away forward
        // latency to do it now that arrival is wake-driven instead of poll-driven.
        private const val QUEUE_TICK_MS = 10_000L
        private const val REPLY_POLL_INTERVAL_MS = 30_000L
        // Bounces are rarely time-critical the way a reply is, and every check is a full-content
        // Gmail fetch per candidate -- a slower cadence than the reply poll is a deliberate cost
        // tradeoff, not an oversight.
        private const val BOUNCE_CHECK_INTERVAL_MS = 10L * 60_000L
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
        private const val MAINTENANCE_INTERVAL_MS = 5L * 60_000L
        // Gmail push (beta): a short Pub/Sub pull, independent of and much shorter than
        // REPLY_POLL_INTERVAL_MS above. A `true` result just resets lastReplyPollMs so the real
        // poll runs on the very next QUEUE_TICK_MS tick instead of waiting out its own 30s timer --
        // collapsing worst-case reply latency to roughly this interval plus one tick. Never used at
        // all unless the user has configured push in Settings; see pollPush()'s own doc comment.
        private const val PUSH_PULL_INTERVAL_MS = 12_000L
        // Deliberately coarse: a misconfigured Pub/Sub subscription would otherwise log a failure
        // on every single PUSH_PULL_INTERVAL_MS tick forever. The 30s Gmail poll is never gated on
        // this succeeding, so there's no urgency to surface it more than about once an hour.
        private const val PUSH_FAILURE_LOG_COOLDOWN_MS = 60L * 60_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ForwardingService::class.java))
        }
    }
}
