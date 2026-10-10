package com.scifsidekick.cleanroom.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.scifsidekick.cleanroom.R
import com.scifsidekick.cleanroom.data.ForwardingStateEntity
import com.scifsidekick.cleanroom.ui.MainActivity

class AlertNotifier(
    private val context: Context,
) {
    fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        // Importance is locked in once a channel is first created -- re-creating SERVICE_CHANNEL
        // with a different importance is silently ignored on phones that already have an earlier
        // version, so each importance change has to land on a new channel id. v2 briefly tried
        // IMPORTANCE_MIN to tuck the foreground-service notification into the shade's collapsed
        // section; confirmed via dumpsys that Android floors any foreground-service notification's
        // effective importance at LOW regardless of the channel's setting (mOriginalImp=1 but
        // mImportance=2 on a real device) -- the OS-level anti-abuse floor from the same "can't
        // hide it while running" family this class already documents elsewhere, not a bug. v3
        // just asks for LOW outright since MIN bought nothing. Deleting the orphaned earlier
        // channels keeps them from lingering as dead "Forwarding status" entries in Android's
        // per-app notification settings once nothing posts to them anymore.
        manager.deleteNotificationChannel(LEGACY_SERVICE_CHANNEL_V1)
        manager.deleteNotificationChannel(LEGACY_SERVICE_CHANNEL_V2)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(SERVICE_CHANNEL, "Forwarding status", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(ALERT_CHANNEL, "Forwarding alerts", NotificationManager.IMPORTANCE_HIGH),
            ),
        )
    }

    fun foreground(
        state: ForwardingStateEntity?,
        queued: Int? = null,
        gmailAvailable: Boolean = true,
    ): Notification {
        val text =
            when {
                state?.emailCircuitOpen == true -> "Forwarding paused — too many failures"
                state?.enabled == true && !gmailAvailable -> "Forwarding waiting for Gmail connection"
                state?.enabled == true -> "Forwarding active${queued?.let { " • $it queued" }.orEmpty()}"
                else -> "Forwarding is off"
            }
        return NotificationCompat
            .Builder(context, SERVICE_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SCIF Sidekick")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .build()
    }

    fun showCircuitBreaker() {
        val notification =
            NotificationCompat
                .Builder(context, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Forwarding paused")
                .setContentText("Too many failures — tap to review and manually resume")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "Forwarding paused — too many failures, tap to review. Queued messages remain stored and no automatic retry will occur until you reset the circuit breaker.",
                    ),
                ).setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(contentIntent())
                .build()
        context.getSystemService(NotificationManager::class.java).notify(CIRCUIT_NOTIFICATION_ID, notification)
    }

    fun clearCircuitBreaker() {
        context.getSystemService(NotificationManager::class.java).cancel(CIRCUIT_NOTIFICATION_ID)
    }

    fun showAuthorizationRequired(
        providerId: String = "gmail",
        displayName: String = "Gmail",
    ) {
        val notification =
            NotificationCompat
                .Builder(context, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Reconnect $displayName")
                .setContentText("Email forwarding is queued until $displayName access is restored")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "SCIF Sidekick needs you to reconnect $displayName. Incoming eligible messages remain safely queued; no automatic email attempts will occur until access is restored.",
                    ),
                ).setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(contentIntent())
                .build()
        context.getSystemService(NotificationManager::class.java).notify(authorizationNotificationId(providerId), notification)
    }

    fun clearAuthorizationRequired(providerId: String = "gmail") {
        context.getSystemService(NotificationManager::class.java).cancel(authorizationNotificationId(providerId))
    }

    /** Whether [providerId]'s reconnect alert is currently posted (a binder call; see [AuthAlertCoordinator]). */
    fun isAuthorizationRequiredShowing(providerId: String): Boolean {
        val id = authorizationNotificationId(providerId)
        return context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == id }
    }

    fun showDeliveryReviewRequired(count: Int) {
        val notification =
            NotificationCompat
                .Builder(context, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Delivery needs review")
                .setContentText("$count message delivery outcome(s) were blocked for safety")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "$count message delivery outcome(s) could not be safely retried. " +
                            "Open SCIF Sidekick and review the event log; automatic resend was blocked to prevent duplicates or excessive SMS segments.",
                    ),
                ).setAutoCancel(true)
                .setContentIntent(contentIntent())
                .build()
        context.getSystemService(NotificationManager::class.java).notify(DELIVERY_REVIEW_NOTIFICATION_ID, notification)
    }

    private fun contentIntent(): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        const val LEGACY_SERVICE_CHANNEL_V1 = "forwarding_service_v1"
        const val LEGACY_SERVICE_CHANNEL_V2 = "forwarding_service_v2"
        const val SERVICE_CHANNEL = "forwarding_service_v3"
        const val ALERT_CHANNEL = "forwarding_alerts_v1"
        const val FOREGROUND_NOTIFICATION_ID = 4101
        const val CIRCUIT_NOTIFICATION_ID = 4102
        const val AUTHORIZATION_NOTIFICATION_ID = 4103
        const val DELIVERY_REVIEW_NOTIFICATION_ID = 4104
        const val GRAPH_AUTHORIZATION_NOTIFICATION_ID = 4110
        const val OTHER_AUTHORIZATION_NOTIFICATION_ID = 4111

        /**
         * Gmail keeps the original reconnect id; Outlook and anything else get their own, clear of
         * every other id above (NotificationIdsTest checks that every *_NOTIFICATION_ID is distinct).
         */
        internal fun authorizationNotificationId(providerId: String): Int =
            when (providerId) {
                "gmail" -> AUTHORIZATION_NOTIFICATION_ID
                "graph" -> GRAPH_AUTHORIZATION_NOTIFICATION_ID
                else -> OTHER_AUTHORIZATION_NOTIFICATION_ID
            }
    }
}
