package com.scifsidekick.cleanroom.email

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google Identity Services owns the OAuth token cache. The app never exposes or
 * persists an access token or refresh token. This replaces custom-scheme AppAuth,
 * which Google no longer supports for Android OAuth clients.
 */
class GmailOAuthManager(
    private val context: Context,
) {
    private val prefs = context.getSharedPreferences("google_authorization_state_v1", Context.MODE_PRIVATE)

    @Volatile private var lastAccessToken: String? = null

    @Volatile private var lastAccount: Account? = null
    val isAuthorized: Boolean get() = prefs.getBoolean(KEY_GRANTED, false)

    /** True once Gmail was ever granted on this install; nothing clears the key, so an expired grant still counts. */
    val everGranted: Boolean get() = prefs.contains(KEY_GRANTED)

    data class ConsentStep(
        val pendingIntent: PendingIntent?,
    )

    suspend fun beginAuthorization(activity: Activity): ConsentStep {
        // forceAccountPicker: this is the only entry point an explicit "Connect Gmail" tap goes
        // through, whether that's a first-time connection or a reconnect after Disconnect. Google
        // Identity Services will otherwise happily resolve silently against whatever account it
        // last used -- no chooser UI at all -- which is exactly why disconnecting and reconnecting
        // never actually offered a different account before this. freshAccessToken's background
        // token refresh deliberately does NOT set this: forcing a chooser on every silent refresh
        // during normal polling would turn a background operation into an interactive one.
        val result =
            Identity
                .getAuthorizationClient(activity)
                .authorize(request(forceAccountPicker = true, includePubSub = pushScopeWanted))
                .await()
        if (!result.hasResolution()) {
            rememberGrant(result)
            if (pushScopeWanted) setPubSubGranted(true)
        }
        return ConsentStep(result.pendingIntent)
    }

    fun consumeAuthorizationResult(intent: Intent?): String {
        val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(intent)
        return rememberGrant(result).also { if (pushScopeWanted) setPubSubGranted(true) }
    }

    /** Best-known state of the Pub/Sub grant, for showing a "grant push access" prompt. */
    val pubSubGranted: Boolean get() = prefs.getBoolean(KEY_PUBSUB_GRANTED, false)

    private fun setPubSubGranted(granted: Boolean) {
        prefs.edit().putBoolean(KEY_PUBSUB_GRANTED, granted).apply()
    }

    /** Whether the next interactive "Connect Gmail" also asks for Pub/Sub access -- true only
     *  while Gmail push (beta) is switched on, so nobody else grants a Cloud-wide scope. */
    val pushScopeWanted: Boolean get() = prefs.getBoolean(KEY_PUSH_SCOPE_WANTED, false)

    fun setPushScopeWanted(wanted: Boolean) {
        prefs.edit().putBoolean(KEY_PUSH_SCOPE_WANTED, wanted).apply()
    }

    /** [includePubSub] is for the push pull only. A missing Pub/Sub grant throws
     *  [PubSubConsentRequiredException] without marking Gmail itself unauthorized, so an
     *  unfinished push opt-in can never stop ordinary forwarding. */
    suspend fun freshAccessToken(includePubSub: Boolean = false): String {
        if (!isAuthorized) throw ReauthorizationRequiredException()
        val result = Identity.getAuthorizationClient(context).authorize(request(includePubSub = includePubSub)).await()
        if (result.hasResolution()) {
            if (includePubSub) {
                setPubSubGranted(false)
                throw PubSubConsentRequiredException()
            }
            markAuthorizationRequired()
            throw ReauthorizationRequiredException()
        }
        if (includePubSub) setPubSubGranted(true)
        return rememberGrant(result)
    }

    /**
     * [fallbackAccountEmail], when supplied, is used only if neither [lastAccount] nor the
     * persisted account name is available -- which the deprecated `toGoogleSignInAccount()`
     * bridge [rememberGrant] relies on can leave null even for a genuinely active grant (see that
     * function's own doc comment). Without any account to name, [RevokeAccessRequest] has nothing
     * to revoke and this silently degrades to "clear the local token only" -- the caller should
     * pass the account email from [com.scifsidekick.cleanroom.email.GmailGateway.accountEmail]
     * (fetched *before* calling this, since the token this needs is about to be cleared) so the
     * common case actually revokes server-side access instead of just hiding the disconnected
     * state locally.
     */
    suspend fun disconnect(fallbackAccountEmail: String? = null): Boolean {
        val token = lastAccessToken
        val account =
            lastAccount
                ?: prefs.getString(KEY_ACCOUNT_NAME, null)?.let { name ->
                    Account(name, prefs.getString(KEY_ACCOUNT_TYPE, GOOGLE_ACCOUNT_TYPE) ?: GOOGLE_ACCOUNT_TYPE)
                }
                ?: fallbackAccountEmail?.let { email -> Account(email, GOOGLE_ACCOUNT_TYPE) }
        markAuthorizationRequired()
        val revoked =
            if (account == null) {
                false
            } else {
                try {
                    Identity
                        .getAuthorizationClient(context)
                        .revokeAccess(
                            RevokeAccessRequest
                                .builder()
                                .setAccount(account)
                                .setScopes(SCOPES + Scope(SCOPE_PUBSUB))
                                .build(),
                        ).await()
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
            }
        if (token != null) {
            try {
                Identity
                    .getAuthorizationClient(context)
                    .clearToken(ClearTokenRequest.builder().setToken(token).build())
                    .await()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
        }
        lastAccessToken = null
        lastAccount = null
        prefs
            .edit()
            .remove(KEY_ACCOUNT_NAME)
            .remove(KEY_ACCOUNT_TYPE)
            .remove(KEY_PUBSUB_GRANTED)
            .apply()
        return revoked
    }

    suspend fun clearRejectedToken(token: String) {
        try {
            Identity
                .getAuthorizationClient(context)
                .clearToken(ClearTokenRequest.builder().setToken(token).build())
                .await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }
        if (lastAccessToken == token) lastAccessToken = null
    }

    fun markAuthorizationRequired() {
        prefs.edit().putBoolean(KEY_GRANTED, false).apply()
    }

    private fun rememberGrant(result: AuthorizationResult): String {
        val token = result.accessToken ?: throw IllegalStateException("Google authorization returned no access token")

        @Suppress("DEPRECATION")
        val account = result.toGoogleSignInAccount()?.account
        lastAccessToken = token
        lastAccount = account
        prefs
            .edit()
            .putBoolean(KEY_GRANTED, true)
            .apply {
                if (account != null) {
                    putString(KEY_ACCOUNT_NAME, account.name)
                    putString(KEY_ACCOUNT_TYPE, account.type)
                }
            }.apply()
        return token
    }

    private fun request(
        forceAccountPicker: Boolean = false,
        includePubSub: Boolean = false,
    ): AuthorizationRequest =
        AuthorizationRequest
            .builder()
            .setRequestedScopes(if (includePubSub) SCOPES + Scope(SCOPE_PUBSUB) else SCOPES)
            .apply { if (forceAccountPicker) setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT) }
            .build()

    private suspend fun <T> Task<T>.await(): T =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { continuation.resume(it) }
            addOnFailureListener { continuation.resumeWithException(it) }
            addOnCanceledListener { continuation.cancel() }
        }

    companion object {
        const val SCOPE_SEND = "https://www.googleapis.com/auth/gmail.send"
        const val SCOPE_MODIFY = "https://www.googleapis.com/auth/gmail.modify"
        // Gmail push (beta) only: grants access to every Pub/Sub resource the account can reach,
        // so it is requested only while push is on -- see pushScopeWanted.
        const val SCOPE_PUBSUB = "https://www.googleapis.com/auth/pubsub"
        private val SCOPES = listOf(Scope(SCOPE_SEND), Scope(SCOPE_MODIFY))
        private const val KEY_GRANTED = "grant_observed"
        private const val KEY_PUSH_SCOPE_WANTED = "push_scope_wanted"
        private const val KEY_PUBSUB_GRANTED = "pubsub_granted"
        private const val KEY_ACCOUNT_NAME = "account_name"
        private const val KEY_ACCOUNT_TYPE = "account_type"
        private const val GOOGLE_ACCOUNT_TYPE = "com.google"
    }
}

class ReauthorizationRequiredException : MailAuthRequiredException("Open the app and reconnect Gmail", "gmail", "Gmail")

class PubSubConsentRequiredException : Exception("Tap \"Grant push access\" in Settings to allow Pub/Sub access for Gmail push (beta)")
