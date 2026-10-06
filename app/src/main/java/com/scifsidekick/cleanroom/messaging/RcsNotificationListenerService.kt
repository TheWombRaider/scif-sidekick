package com.scifsidekick.cleanroom.messaging

import android.app.Notification
import android.app.Person
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import com.scifsidekick.cleanroom.AppGraph
import com.scifsidekick.cleanroom.service.ForwardWakeLock
import com.scifsidekick.cleanroom.service.ForwardingService
import com.scifsidekick.cleanroom.service.QueueWakeSignal
import com.scifsidekick.cleanroom.util.ContactResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Captures live conversation notifications from supported messaging apps, plus missed-call
 * notifications from the device's default phone/dialer app. This is the only practical
 * third-party path for Google Messages RCS (Android exposes no RCS receive broadcast) and it
 * doubles as the call-forwarding path so that capability needs no extra dangerous permission --
 * notification access, already required for RCS, covers system-wide notifications including
 * calls. The service never queries message or call history and ignores every other app.
 */
class RcsNotificationListenerService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // onNotificationPosted fires for every notification from every app on the device -- not just
    // Messages/dialer -- because that is the only way NotificationListenerService works at all.
    // The default dialer essentially never changes while this service stays connected, so it is
    // read once via TelecomManager (a binder call to system_server) here rather than on every
    // single notification system-wide, exactly the "binder call on every tick" shape called out
    // as the cause of the 1.13.2 battery-drain fix, just per-notification instead of per-timer-
    // tick. `null` means "not yet resolved" and is retried lazily; see [isDialerPackage].
    @Volatile private var cachedDefaultDialer: String? = null

    override fun onListenerConnected() {
        cachedDefaultDialer = runCatching { getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()
        scope.launch {
            AppGraph.from(this@RcsNotificationListenerService).repository.recordServiceEvent(
                "RCS notification access connected",
            )
        }
    }

    override fun onListenerDisconnected() {
        scope.launch {
            AppGraph.from(this@RcsNotificationListenerService).repository.recordServiceEvent(
                "RCS notification access disconnected; RCS forwarding is unavailable",
            )
        }
    }

    override fun onNotificationPosted(notification: StatusBarNotification) {
        if (notification.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val incoming =
            when {
                RcsNotificationPolicy.isSupportedPackage(notification.packageName) -> parse(notification)
                isDialerPackage(notification.packageName) -> parseMissedCall(notification)
                else -> null
            } ?: return

        scope.launch {
            val graph = AppGraph.from(this@RcsNotificationListenerService)
            graph.repository.ensureInitialized()
            val forwardingOn = graph.database.stateDao().get()?.enabled == true
            // Held across the dedup delay below too: an idle phone would otherwise sleep through it.
            if (forwardingOn) ForwardWakeLock.acquire(this@RcsNotificationListenerService)
            // Google Messages also posts an SMS notification. Give the SMS broadcast time to
            // persist first, then let the repository suppress the matching cross-source event.
            delay(CROSS_SOURCE_DEDUP_DELAY_MS)
            // Same gate as IncomingMessageReceiver: starting the service while forwarding is off
            // only flashes its notification back into the shade before it stops itself again.
            if (forwardingOn) {
                val serviceFailure = runCatching { ForwardingService.start(this@RcsNotificationListenerService) }.exceptionOrNull()
                serviceFailure?.let {
                    graph.repository.recordServiceEvent(
                        "Foreground service could not start from an RCS notification: ${it.message}",
                    )
                }
            }
            if (graph.repository.processIncoming(incoming)) QueueWakeSignal.wake(this@RcsNotificationListenerService)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun parse(status: StatusBarNotification): IncomingMessage? {
        val notification = status.notification
        val extras = notification.extras ?: return null
        val selfKey = if (Build.VERSION.SDK_INT >= 28) extras.getParcelable<Person>(Notification.EXTRA_MESSAGING_PERSON)?.key else null
        val newest =
            extras
                .getParcelableArray(Notification.EXTRA_MESSAGES)
                .orEmpty()
                .mapNotNull { it as? Bundle }
                .mapNotNull { parseMessageBundle(it, selfKey) }
                .maxByOrNull(NotificationLine::timestamp)
        // The messaging app re-posts the conversation notification with the user's own reply as
        // its newest line; forwarding that would echo the user's text back as if the contact sent it.
        if (newest?.fromSelf == true) return null
        val body =
            RcsNotificationPolicy.cleanText(
                newest?.text
                    ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT),
            ) ?: return null

        val senderDisplay =
            RcsNotificationPolicy.cleanSender(
                newest?.sender
                    ?: extras.getCharSequence(Notification.EXTRA_TITLE),
            ) ?: return null
        val contactResolver = ContactResolver(this)
        val senderFromUri = RcsNotificationPolicy.phoneFromPersonUri(newest?.senderUri)
        val timestamp = (newest?.timestamp ?: status.postTime).takeIf { it > 0L } ?: status.postTime
        val conversationTitle =
            RcsNotificationPolicy.cleanSender(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE))
        val isGroupConversation =
            extras.getBoolean("android.isGroupConversation") ||
                (conversationTitle != null && !conversationTitle.equals(senderDisplay, ignoreCase = true))
        val senderAddress =
            if (isGroupConversation) {
                "rcs-group:${conversationTitle ?: senderDisplay}"
            } else {
                senderFromUri ?: contactResolver.uniquePhoneNumberForDisplayName(senderDisplay) ?: senderDisplay
            }
        val display =
            if (isGroupConversation && conversationTitle != null) {
                "$senderDisplay in $conversationTitle"
            } else {
                senderDisplay
            }
        val participants = listOfNotNull(conversationTitle, senderDisplay).distinct()
        val notices =
            buildList {
                if (isGroupConversation) {
                    add("Email reply is disabled for this RCS group notification to prevent an accidental direct SMS to one participant.")
                }
                if (RcsNotificationPolicy.looksLikeMediaOnly(body)) {
                    add("Google Messages exposed only the RCS notification text; original media bytes were not available.")
                }
            }

        return IncomingMessage(
            source = "rcs",
            senderAddress = senderAddress,
            senderDisplay = display,
            body = body,
            receivedAtMs = timestamp,
            sourceTimestampMs = timestamp,
            participants = participants,
            attachmentNotice = notices.takeIf(List<String>::isNotEmpty)?.joinToString(" "),
        )
    }

    /** True for the device's current default dialer, plus a small known-package fallback in case
     *  [TelecomManager] cannot report one (some OEM/carrier stacks). Reads [cachedDefaultDialer]
     *  rather than calling [TelecomManager] itself here -- see that field's own doc comment for
     *  why a fresh binder call on every notification from every app is worth avoiding. A `null`
     *  cache (never successfully resolved, e.g. this callback firing before [onListenerConnected]
     *  finished, or that lookup itself failing) is retried here, once, rather than left permanently
     *  unresolved for the rest of this connection's lifetime. */
    private fun isDialerPackage(packageName: String): Boolean {
        val defaultDialer =
            cachedDefaultDialer
                ?: runCatching { getSystemService(TelecomManager::class.java)?.defaultDialerPackage }
                    .getOrNull()
                    ?.also { cachedDefaultDialer = it }
        return packageName == defaultDialer || packageName in KNOWN_DIALER_PACKAGES
    }

    /**
     * Missed-call notifications use the standard [Notification.CATEGORY_MISSED_CALL] category,
     * which is a far more reliable signal than pattern-matching notification text that varies by
     * OEM and locale. Never queries call-log history: only the live notification object supplied
     * by the callback is read.
     */
    @Suppress("DEPRECATION")
    private fun parseMissedCall(status: StatusBarNotification): IncomingMessage? {
        val notification = status.notification
        if (notification.category != Notification.CATEGORY_MISSED_CALL) return null
        val extras = notification.extras ?: return null

        val title = RcsNotificationPolicy.cleanText(extras.getCharSequence(Notification.EXTRA_TITLE)) ?: "Missed call"
        val text = RcsNotificationPolicy.cleanText(extras.getCharSequence(Notification.EXTRA_TEXT))
        val senderDisplay = text ?: title

        val personPhone = extractPeoplePhone(extras)
        val patternPhone = RcsNotificationPolicy.extractLikelyPhoneNumber(senderDisplay)
        val senderAddress = personPhone ?: patternPhone ?: senderDisplay

        return IncomingMessage(
            source = "call",
            senderAddress = senderAddress,
            senderDisplay = senderDisplay,
            body = title,
            receivedAtMs = status.postTime,
            sourceTimestampMs = status.postTime,
        )
    }

    @Suppress("DEPRECATION")
    private fun extractPeoplePhone(extras: Bundle): String? {
        if (Build.VERSION.SDK_INT < 28) return null
        val people = extras.getParcelableArrayList<Person>(Notification.EXTRA_PEOPLE_LIST) ?: return null
        return people.firstNotNullOfOrNull { RcsNotificationPolicy.phoneFromPersonUri(it.uri) }
    }

    @Suppress("DEPRECATION")
    private fun parseMessageBundle(
        bundle: Bundle,
        selfKey: String?,
    ): NotificationLine? {
        val text = bundle.getCharSequence("text") ?: return null
        val legacySender = bundle.getCharSequence("sender")
        if (Build.VERSION.SDK_INT >= 28) {
            val person = bundle.getParcelable<Person>("sender_person")
            return NotificationLine(
                text = text,
                timestamp = bundle.getLong("time"),
                sender = person?.name ?: legacySender,
                senderUri = person?.uri,
                // MessagingStyle's contract: a message with no sender is from the device user.
                fromSelf = (person == null && legacySender == null) || (selfKey != null && person?.key == selfKey),
            )
        }
        return NotificationLine(
            text = text,
            timestamp = bundle.getLong("time"),
            sender = legacySender,
            senderUri = null,
            fromSelf = legacySender == null,
        )
    }

    private data class NotificationLine(
        val text: CharSequence,
        val timestamp: Long,
        val sender: CharSequence?,
        val senderUri: String?,
        val fromSelf: Boolean,
    )

    private companion object {
        const val CROSS_SOURCE_DEDUP_DELAY_MS = 2_000L
        val KNOWN_DIALER_PACKAGES =
            setOf(
                "com.google.android.dialer",
                "com.samsung.android.dialer",
                "com.samsung.android.incallui",
            )
    }
}

object RcsNotificationPolicy {
    private val supportedPackages =
        setOf(
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
        )
    private val mediaOnly =
        setOf(
            "photo",
            "video",
            "image",
            "gif",
            "sticker",
            "audio",
            "voice message",
            "attachment",
        )

    fun isSupportedPackage(packageName: String): Boolean = packageName in supportedPackages

    fun cleanText(value: CharSequence?): String? =
        value
            ?.toString()
            ?.replace('\uFFFC', ' ')
            ?.replace(Regex("[\\r\\n]{3,}"), "\n\n")
            ?.trim()
            ?.take(MAX_TEXT_LENGTH)
            ?.takeIf(String::isNotBlank)

    fun cleanSender(value: CharSequence?): String? =
        value
            ?.toString()
            ?.replace(Regex("[\\r\\n]"), " ")
            ?.trim()
            // Google Messages prefixes a name with "~" when it's the sender's RCS profile name
            // or carrier caller-ID rather than a match from the user's own Contacts -- exactly
            // the case where ContactResolver's exact-name-match lookup would otherwise silently
            // keep failing even after the user saves a matching contact, since nobody types "~"
            // into a contact's name.
            ?.removePrefix("~")
            ?.trim()
            ?.take(MAX_SENDER_LENGTH)
            ?.takeIf(String::isNotBlank)

    fun phoneFromPersonUri(uri: String?): String? {
        val value = uri?.trim().orEmpty()
        if (!value.startsWith("tel:", ignoreCase = true)) return null
        return android.net.Uri.decode(value.substringAfter(':')).takeIf(String::isNotBlank)
    }

    /** Best-effort phone number extraction from missed-call notification text, e.g. "+1 555-123-4567". */
    fun extractLikelyPhoneNumber(text: String): String? = Regex("\\+?\\(?[0-9][0-9 ()-]{5,17}[0-9]").find(text)?.value?.trim()

    fun looksLikeMediaOnly(body: String): Boolean {
        // Real Google/Samsung Messages notification text for a media-only message is rarely the
        // bare word alone: it commonly carries a leading emoji ("📷 Photo"), a "Sent a/an "
        // prefix, or a trailing duration ("Voice message · 0:12"). Strip pictographs/marks, then
        // only tolerate a duration-like suffix (digits/punctuation) after the bare keyword --
        // never arbitrary trailing words, so an ordinary sentence that happens to start with
        // "photo" or "video" is never misclassified as media-only.
        val cleaned =
            body
                .trim()
                .lowercase()
                .replace(Regex("[\\p{So}\\p{Cn}\\p{Mn}]"), "")
                .trim()
        return mediaOnly.any { keyword ->
            cleaned == "sent a $keyword" ||
                cleaned == "sent an $keyword" ||
                cleaned.matches(Regex(Regex.escape(keyword) + "[\\s·:()\\d]*"))
        }
    }

    private const val MAX_TEXT_LENGTH = 20_000
    private const val MAX_SENDER_LENGTH = 200
}
