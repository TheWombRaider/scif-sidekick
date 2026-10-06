package com.scifsidekick.cleanroom.messaging

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import com.scifsidekick.cleanroom.AppGraph
import com.scifsidekick.cleanroom.service.ForwardWakeLock
import com.scifsidekick.cleanroom.service.ForwardingService
import com.scifsidekick.cleanroom.service.QueueWakeSignal
import com.scifsidekick.cleanroom.util.AttachmentStore
import com.scifsidekick.cleanroom.util.ContactResolver
import com.scifsidekick.cleanroom.util.Hashing
import com.scifsidekick.cleanroom.util.suspendRunCatching
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class IncomingMessageReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val receivedAt = System.currentTimeMillis()
        val pending = goAsync()
        // goAsync()'s PendingResult throws if finish() is called more than once, and it now gets
        // called from two different places below (early for MMS, in the finally for everything
        // else) -- this guard is what makes that safe regardless of which path runs or whether an
        // exception lands in between.
        val pendingFinished = AtomicBoolean(false)
        fun finishPending() {
            if (pendingFinished.compareAndSet(false, true)) pending.finish()
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val graph = AppGraph.from(context)
                graph.repository.ensureInitialized()
                // Only worth waking the foreground service (and its mandatory ongoing
                // notification) for a message that will actually be acted on. processIncoming
                // below already no-ops -- log-only, "Skipped -- forwarding was off" -- when the
                // master switch is off, so starting the service here just to have it notice
                // that and immediately stop itself again (see ForwardingService's own loop)
                // would mean every single text while forwarding is off flashes the notification
                // back into the shade for no reason -- exactly the behavior turning the switch
                // off is supposed to prevent.
                if (graph.database.stateDao().get()?.enabled == true) {
                    ForwardWakeLock.acquire(context)
                    runCatching { ForwardingService.start(context) }.onFailure { failure ->
                        graph.repository.recordServiceEvent(
                            "Foreground service start was rejected; the live event was still persisted: ${failure.message}",
                        )
                    }
                }
                when (intent.action) {
                    Telephony.Sms.Intents.SMS_RECEIVED_ACTION -> parseSms(context, intent, receivedAt)
                    Telephony.Sms.Intents.WAP_PUSH_RECEIVED_ACTION -> {
                        // Peeked here purely to skip copying attachment bytes to disk at all when
                        // no enabled filter currently wants MMS -- the authoritative per-filter
                        // check still happens transactionally inside processIncoming below.
                        val mmsForwardingEnabled = graph.database.filterDao().anyEnabledWantsMms()
                        // Everything above this point is quick (a couple of DB reads, starting a
                        // service that's already alive most of the time). What parseMms does next
                        // when MMS forwarding is on -- waiting for the default SMS app to finish
                        // downloading this message into content://mms -- can legitimately run much
                        // longer than a BroadcastReceiver should ever hold goAsync() open; some
                        // OEMs' broadcast watchdogs will ANR well before a slow-network MMS
                        // download completes. Finish the pending result now instead of after that
                        // wait -- the foreground service started above (or already running) keeps
                        // this process alive for the rest of this coroutine regardless, so nothing
                        // downstream depends on the pending result staying open.
                        finishPending()
                        parseMms(context, intent, receivedAt, graph, mmsForwardingEnabled)
                    }
                    else -> emptyList()
                }.forEach { message ->
                    val queued = graph.repository.processIncoming(message)
                    if (queued) {
                        // Cuts ForwardingService's own idle wait short instead of leaving this
                        // forward to wait out the rest of its tick timer -- see
                        // QueueWakeSignal's own doc comment for why this is safe to call
                        // unconditionally, including while the service isn't running.
                        QueueWakeSignal.wake(context)
                    } else if (message.attachmentPaths.isNotEmpty()) {
                        graph.attachments.delete(message.attachmentPaths)
                    }
                }
            } catch (t: Throwable) {
                AppGraph.from(context).repository.recordServiceEvent(
                    "Live receiver failed: ${(t.message ?: t.javaClass.simpleName).take(500)}",
                )
            } finally {
                finishPending()
            }
        }
    }

    private fun parseSms(
        context: Context,
        intent: Intent,
        receivedAt: Long,
    ): List<IncomingMessage> {
        val messages =
            Telephony.Sms.Intents
                .getMessagesFromIntent(intent)
                .toList()
        return messages.groupBy { it.originatingAddress.orEmpty() }.map { (sender, segments) ->
            val stableTimestamp = segments.minOfOrNull { it.timestampMillis } ?: receivedAt
            val body = segments.joinToString("") { it.messageBody.orEmpty() }
            IncomingMessage(
                source = "sms",
                senderAddress = sender,
                senderDisplay = ContactResolver(context).displayName(sender),
                body = body,
                receivedAtMs = receivedAt,
                sourceTimestampMs = stableTimestamp,
            )
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun parseMms(
        context: Context,
        intent: Intent,
        receivedAt: Long,
        graph: AppGraph,
        mmsForwardingEnabled: Boolean,
    ): List<IncomingMessage> {
        val rawPdu = intent.getByteArrayExtra("data") ?: ByteArray(0)
        val intentSender =
            listOf("address", "from", "sender")
                .firstNotNullOfOrNull { intent.getStringExtra(it)?.takeIf(String::isNotBlank) }

        // Stock Android/Samsung deliver only the carrier's WAP notification here -- never the
        // decoded photo/video -- to a non-default SMS app; see docs/DESIGN_NOTES.md
        // "Important Android MMS boundary". When forwarding wants MMS, wait briefly for the default SMS app to finish
        // downloading and writing this *specific*, just-announced message to content://mms, then
        // read only that one row's own parts. See MmsContentFetcher's own doc comment for exactly
        // what this deliberately still refuses to do (browse history, poll, catch up on toggle).
        val fetched =
            if (mmsForwardingEnabled) {
                suspendRunCatching { MmsContentFetcher(context).fetchJustAnnouncedMessage(receivedAt, graph.attachments) }
                    .onFailure { failure ->
                        graph.repository.recordServiceEvent(
                            "Live MMS content lookup failed; falling back to notification-only: ${failure.message}",
                        )
                    }.getOrNull()
            } else {
                null
            }

        val sender =
            intentSender
                ?: fetched?.senderAddress
                ?: extractLikelyAddress(rawPdu)
                ?: return listOf(
                    IncomingMessage(
                        source = "mms",
                        senderAddress = "unknown",
                        senderDisplay = "Unknown MMS sender",
                        body = "An MMS notification arrived, but Android did not expose a safe sender address.",
                        receivedAtMs = receivedAt,
                        sourceTimestampMs = stablePduTimestamp(rawPdu),
                        attachmentNotice = "Not forwarded: the sender could not be safely identified from the live WAP broadcast.",
                        contentFingerprint = rawPdu.takeIf(ByteArray::isNotEmpty)?.let { Hashing.sha256(it.joinToString(",")) },
                    ),
                )

        // When MMS forwarding is off, skip copying any attachment bytes to app storage at all --
        // the message is going to be discarded by processIncoming's gate regardless, so there is
        // no reason to let a possibly sensitive photo/video ever touch disk. This is the whole
        // point of the toggle, not just an optimization.
        val paths: List<String>
        val notice: String?
        if (!mmsForwardingEnabled) {
            paths = emptyList()
            notice = null
        } else {
            val uris = mutableListOf<Uri>()
            (intent.getParcelableArrayListExtra<Uri>("attachments") ?: arrayListOf()).let(uris::addAll)
            intent.getParcelableExtra<Uri>("contentUri")?.let(uris::add)
            val uniqueUris = uris.distinct()
            if (uniqueUris.isNotEmpty()) {
                // An OEM broadcast that already includes live content URIs -- copy directly, the
                // same as before this change; the content://mms lookup above is skipped entirely
                // in this case since there's nothing left for it to add.
                var skippedAttachments = (uniqueUris.size - AttachmentStore.MAX_ATTACHMENTS_PER_MESSAGE).coerceAtLeast(0)
                var copiedBytes = 0L
                val copiedPaths =
                    uniqueUris.take(AttachmentStore.MAX_ATTACHMENTS_PER_MESSAGE).mapIndexedNotNull { index, uri ->
                        val path = graph.attachments.copyFromUri(uri, "mms_attachment_${index + 1}")
                        if (path == null) {
                            skippedAttachments += 1
                            null
                        } else {
                            val size = java.io.File(path).length()
                            if (copiedBytes + size > AttachmentStore.MAX_STORED_ATTACHMENT_BYTES) {
                                graph.attachments.delete(listOf(path))
                                skippedAttachments += 1
                                null
                            } else {
                                copiedBytes += size
                                path
                            }
                        }
                    }
                paths = copiedPaths
                notice =
                    when {
                        skippedAttachments > 0 ->
                            "$skippedAttachments live attachment(s) could not be copied safely or exceeded the " +
                                "${AttachmentStore.MAX_STORED_ATTACHMENT_BYTES}-byte aggregate limit and were not forwarded."
                        paths.isEmpty() ->
                            "Android delivered only the carrier's live WAP notification, not decoded media bytes. " +
                                "This is a known Android limitation."
                        else -> null
                    }
            } else if (fetched != null && (fetched.attachmentPaths.isNotEmpty() || fetched.skippedAnyForSize)) {
                // The common stock-Android/Samsung case: nothing came in the broadcast itself, but
                // the bounded live lookup above found the default SMS app's own freshly-downloaded
                // copy of this exact message.
                paths = fetched.attachmentPaths
                notice =
                    if (fetched.skippedAnyForSize) {
                        "One or more attachments could not be copied safely or exceeded the size limit and were not forwarded."
                    } else {
                        null
                    }
            } else {
                paths = emptyList()
                notice =
                    "Android delivered only the carrier's live WAP notification, not decoded media bytes, and a " +
                        "brief live lookup of this specific message found nothing forwardable yet. This is a " +
                        "disclosed platform/timing limitation, not a bug."
            }
        }
        val participantExtras = intent.getStringArrayListExtra("participants")?.toList().orEmpty()
        val fetchedBody = fetched?.textBody?.takeIf(String::isNotBlank)
        val fetchedParticipants = fetched?.participants?.takeIf(List<String>::isNotEmpty)
        return listOf(
            IncomingMessage(
                source = "mms",
                senderAddress = sender,
                senderDisplay = ContactResolver(context).displayName(sender),
                body = intent.getStringExtra("body") ?: fetchedBody ?: intent.getStringExtra("subject").orEmpty(),
                receivedAtMs = receivedAt,
                sourceTimestampMs = intent.getLongExtra("timestamp", stablePduTimestamp(rawPdu)),
                participants = participantExtras.ifEmpty { fetchedParticipants ?: listOf(sender) },
                attachmentPaths = paths,
                attachmentNotice = notice,
                contentFingerprint = rawPdu.takeIf(ByteArray::isNotEmpty)?.let { Hashing.sha256(it.joinToString(",")) },
            ),
        )
    }

    private fun extractLikelyAddress(raw: ByteArray): String? {
        val printable = raw.toString(Charsets.ISO_8859_1)
        return Regex("(\\+?[1-9]\\d{6,14})/TYPE=PLMN", RegexOption.IGNORE_CASE)
            .find(printable)
            ?.groupValues
            ?.get(1)
            ?: Regex("\\+[1-9]\\d{6,14}").find(printable)?.value
    }

    private fun stablePduTimestamp(raw: ByteArray): Long {
        val prefix = Hashing.sha256(raw.joinToString(",")).take(15)
        return prefix.toLong(16)
    }
}
