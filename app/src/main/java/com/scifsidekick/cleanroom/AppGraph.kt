package com.scifsidekick.cleanroom

import android.content.Context
import com.scifsidekick.cleanroom.data.SidekickDatabase
import com.scifsidekick.cleanroom.data.SidekickRepository
import com.scifsidekick.cleanroom.email.DebugControls
import com.scifsidekick.cleanroom.email.GmailGateway
import com.scifsidekick.cleanroom.email.GmailOAuthManager
import com.scifsidekick.cleanroom.email.GmailPushGateway
import com.scifsidekick.cleanroom.email.MailTransport
import com.scifsidekick.cleanroom.messaging.MmsGateway
import com.scifsidekick.cleanroom.messaging.SmsGateway
import com.scifsidekick.cleanroom.service.AlertNotifier
import com.scifsidekick.cleanroom.service.QueueProcessor
import com.scifsidekick.cleanroom.service.RollingRateLimiter
import com.scifsidekick.cleanroom.util.AttachmentStore

class AppGraph private constructor(
    context: Context,
) {
    private val app = context.applicationContext
    val database = SidekickDatabase.get(app)
    val repository = SidekickRepository(app, database)
    val oauth = GmailOAuthManager(app)
    val debug = DebugControls(app)
    val gmail = GmailGateway(oauth, debug)

    /** What the rest of the app talks to. Today that is Gmail; a router replaces it in a later step. */
    val mail: MailTransport = gmail
    val gmailPush = GmailPushGateway(oauth, debug)
    val attachments = AttachmentStore(app)
    val alerts = AlertNotifier(app)
    val queueProcessor =
        QueueProcessor(
            database,
            repository,
            RollingRateLimiter(database.deliveryAttemptDao()),
            mail,
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
