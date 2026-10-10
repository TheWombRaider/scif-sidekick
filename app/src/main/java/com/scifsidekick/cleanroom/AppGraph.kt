package com.scifsidekick.cleanroom

import android.content.Context
import androidx.core.content.edit
import com.scifsidekick.cleanroom.data.SidekickDatabase
import com.scifsidekick.cleanroom.data.SidekickRepository
import com.scifsidekick.cleanroom.email.DebugControls
import com.scifsidekick.cleanroom.email.GmailGateway
import com.scifsidekick.cleanroom.email.GmailOAuthManager
import com.scifsidekick.cleanroom.email.GmailPushGateway
import com.scifsidekick.cleanroom.email.MailRouter
import com.scifsidekick.cleanroom.email.MailTransport
import com.scifsidekick.cleanroom.email.gmailIsMember
import com.scifsidekick.cleanroom.email.mailMemberIds
import com.scifsidekick.cleanroom.email.graph.GraphGateway
import com.scifsidekick.cleanroom.email.graph.KeystoreRefreshTokenStore
import com.scifsidekick.cleanroom.email.graph.MsAccountPreferences
import com.scifsidekick.cleanroom.email.graph.MsOAuthManager
import com.scifsidekick.cleanroom.messaging.MmsGateway
import com.scifsidekick.cleanroom.messaging.SmsGateway
import com.scifsidekick.cleanroom.service.AlertNotifier
import com.scifsidekick.cleanroom.service.AuthAlertCoordinator
import com.scifsidekick.cleanroom.service.QueueProcessor
import com.scifsidekick.cleanroom.service.RollingRateLimiter
import com.scifsidekick.cleanroom.util.AttachmentStore
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

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

    // Shared by Microsoft sign-in and Graph mail; the same timeouts GmailGateway uses.
    private val microsoftHttp =
        OkHttpClient
            .Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    val msPrefs = MsAccountPreferences(app)
    val msOAuth =
        MsOAuthManager(
            clientId = { msPrefs.clientId.ifBlank { null } },
            store = KeystoreRefreshTokenStore(app),
            client = microsoftHttp,
            log = { repository.recordServiceEvent(it) },
        )
    val graphMail = GraphGateway(msOAuth, client = microsoftHttp, log = { repository.recordServiceEvent(it) })

    /**
     * A Microsoft account is set up when one was connected (its address is remembered) or a token is
     * stored. A set-up account whose sign-in is gone stays a member, so it is still reported and alerted.
     */
    fun microsoftConfigured(): Boolean = msPrefs.accountEmail != null || msOAuth.isAuthorized

    private val mailAccountPrefs = app.getSharedPreferences("mail_accounts_v1", Context.MODE_PRIVATE)

    /**
     * Set when the user disconnects Gmail, cleared when Gmail connects again. With a Microsoft
     * account set up, a Gmail removed on purpose leaves the router (see [gmailIsMember]).
     */
    var gmailDisconnectedOnPurpose: Boolean
        get() = mailAccountPrefs.getBoolean(KEY_GMAIL_DISCONNECTED, false)
        set(value) = mailAccountPrefs.edit { putBoolean(KEY_GMAIL_DISCONNECTED, value) }

    /** The router's members in preference order (see [mailMemberIds]). Gmail-only installs: just Gmail. */
    private fun mailMembers(): List<MailTransport> {
        if (!microsoftConfigured()) return listOf(gmail)
        return mailMemberIds(
            gmailDisconnectedOnPurpose = gmailDisconnectedOnPurpose,
            microsoftConfigured = true,
            preferGraph = msPrefs.preferredProvider == MsAccountPreferences.PROVIDER_GRAPH,
            gmailAvailable = { gmail.isAvailable },
        ).map { if (it == gmail.providerId) gmail else graphMail }
    }

    /** The one owner of the per-provider reconnect alerts. */
    val authAlerts =
        AuthAlertCoordinator(
            providers = { listOf(gmail, graphMail) },
            configured = ::mailMembers,
            isShowing = alerts::isAuthorizationRequiredShowing,
            show = alerts::showAuthorizationRequired,
            clear = alerts::clearAuthorizationRequired,
        )

    /** Failover over the connected mailboxes. With Gmail alone it is a pass-through. */
    val mailRouter =
        MailRouter(
            members = ::mailMembers,
            onAuthRequired = { authAlerts.authRequired(it) },
            onRecovered = { authAlerts.recovered(it) },
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
            onAuthRequired = { authAlerts.authRequired(it) },
        )

    /** Each account's name and whether it can send now: Gmail first, then Outlook when set up. */
    fun accountStatuses(): List<Pair<String, Boolean>> =
        buildList {
            add("Gmail" to gmail.isAvailable)
            if (microsoftConfigured()) add("Outlook" to graphMail.isAvailable)
        }

    /**
     * The status summary every report uses. Gmail alone: exactly what it was before Outlook existed
     * (the Gmail line follows [mail], which is Gmail behind a pass-through router). With Outlook set
     * up, the Gmail line is Gmail's own state and Outlook gets its own line.
     */
    suspend fun statusSummary(nowMs: Long): String {
        val accounts = accountStatuses()
        val gmailAvailable = if (accounts.size == 1) mail.isAvailable else accounts.first().second
        return repository.buildStatusSummary(gmailAvailable, nowMs, accounts.drop(1))
    }

    companion object {
        private const val KEY_GMAIL_DISCONNECTED = "gmail_disconnected_on_purpose"

        @Volatile private var instance: AppGraph? = null

        fun from(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context).also { instance = it }
            }
    }
}
