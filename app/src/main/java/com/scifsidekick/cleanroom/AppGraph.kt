package com.scifsidekick.cleanroom

import android.content.Context
import com.scifsidekick.cleanroom.data.SidekickDatabase
import com.scifsidekick.cleanroom.data.SidekickRepository
import com.scifsidekick.cleanroom.email.DebugControls
import com.scifsidekick.cleanroom.email.GmailGateway
import com.scifsidekick.cleanroom.email.GmailOAuthManager
import com.scifsidekick.cleanroom.email.GmailPushGateway
import com.scifsidekick.cleanroom.email.MailRouter
import com.scifsidekick.cleanroom.email.MailTransport
import com.scifsidekick.cleanroom.messaging.MmsGateway
import com.scifsidekick.cleanroom.messaging.SmsGateway
import com.scifsidekick.cleanroom.service.AlertNotifier
import com.scifsidekick.cleanroom.service.QueueProcessor
import com.scifsidekick.cleanroom.service.RollingRateLimiter
import com.scifsidekick.cleanroom.util.AttachmentStore
import java.util.concurrent.ConcurrentHashMap

class AppGraph private constructor(
    context: Context,
) {
    private val app = context.applicationContext
    val database = SidekickDatabase.get(app)
    val repository = SidekickRepository(app, database)
    val oauth = GmailOAuthManager(app)
    val debug = DebugControls(app)
    val gmail = GmailGateway(oauth, debug)
    val alerts = AlertNotifier(app)

    // Providers whose reconnect alert the router itself raised. A later success clears that alert
    // once, instead of posting a cancel to the notification service after every successful poll.
    private val routerAlertedProviders = ConcurrentHashMap.newKeySet<String>()

    /** Failover over the connected mailboxes. Gmail is the only member for now (a pass-through). */
    val mailRouter =
        MailRouter(
            members = { listOf(gmail) },
            onAuthRequired = {
                routerAlertedProviders += it.providerId
                alerts.showAuthorizationRequired(it.providerId, it.displayName)
            },
            onRecovered = { if (routerAlertedProviders.remove(it)) alerts.clearAuthorizationRequired(it) },
            logPossibleDuplicate = { repository.recordServiceEvent(it) },
        )

    /** What the rest of the app talks to. Tests may install a fake transport or another router. */
    @Volatile internal var mail: MailTransport = mailRouter
    val gmailPush = GmailPushGateway(oauth, debug)
    val attachments = AttachmentStore(app)
    val queueProcessor =
        QueueProcessor(
            database,
            repository,
            RollingRateLimiter(database.deliveryAttemptDao()),
            mailRouter,
            SmsGateway(app),
            MmsGateway(app),
            attachments,
            alerts,
        )

    companion object {
        @Volatile private var instance: AppGraph? = null

        fun from(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context).also { instance = it }
            }
    }
}
