package com.scifsidekick.cleanroom.service

import com.scifsidekick.cleanroom.email.MailAuthRequiredException
import com.scifsidekick.cleanroom.email.MailTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One provider's inputs to [authAlertActions]. [alertShown] is null when the system could not be asked. */
data class ProviderAlertState(
    val providerId: String,
    val displayName: String,
    val configured: Boolean,
    val available: Boolean,
    val authFailed: Boolean,
    val alertShown: Boolean?,
)

sealed interface AuthAlertAction {
    data class Show(
        val providerId: String,
        val displayName: String,
    ) : AuthAlertAction

    data class Clear(
        val providerId: String,
    ) : AuthAlertAction
}

/**
 * The reconnect-alert decision, per provider:
 *
 * - Needs reconnecting: configured, and either an auth failure since its last success or unavailable.
 * - A shown alert that is no longer needed is cleared.
 * - A missing alert is raised here only with [showUnavailable] (service start, or a failure no
 *   account owns) for a configured, unavailable provider; auth failures raise theirs when reported,
 *   so a dismissed alert is not re-posted every tick.
 *
 * An unknown [ProviderAlertState.alertShown] acts: showing or clearing again is harmless.
 */
fun authAlertActions(
    states: List<ProviderAlertState>,
    showUnavailable: Boolean,
): List<AuthAlertAction> =
    states.mapNotNull { s ->
        val needsReconnect = s.configured && (s.authFailed || !s.available)
        when {
            needsReconnect && s.alertShown != true && showUnavailable && !s.available ->
                AuthAlertAction.Show(s.providerId, s.displayName)
            !needsReconnect && s.alertShown != false -> AuthAlertAction.Clear(s.providerId)
            else -> null
        }
    }

/**
 * The single owner of the per-provider "Reconnect <provider>" alerts.
 *
 * Decisions come from current state ([MailTransport.isAvailable], router membership, whether the
 * alert is showing) plus the members with an auth failure since their last success, which covers a
 * provider that fails auth while still reading available (Outlook after a second 401). That set is
 * lost on process death and the alerts are re-derived.
 *
 * Only configured members and providers this coordinator posted for are touched, so a Gmail-only
 * install never queries or cancels any id but Gmail's. Calls are serialized and idempotent;
 * [isShowing] is asked only while an alert might be up, since nothing else posts these alerts.
 */
class AuthAlertCoordinator(
    /** Every provider that can hold an alert, configured or not. */
    private val providers: () -> List<MailTransport>,
    /** The router's members: the providers the user has set up. */
    private val configured: () -> List<MailTransport>,
    private val isShowing: (providerId: String) -> Boolean,
    private val show: (providerId: String, displayName: String) -> Unit,
    private val clear: (providerId: String) -> Unit,
) {
    private val lock = Mutex()
    private val authFailed = mutableSetOf<String>()
    private val knownHidden = mutableSetOf<String>()
    private val postedHere = mutableSetOf<String>()

    /**
     * An auth failure. Configured account: show its alert unless already up. No longer configured
     * (a call in flight at disconnect): nothing. Owned by no account (the router's "no account is
     * connected"): alert each configured signed-out account under its own name.
     */
    suspend fun authRequired(failure: MailAuthRequiredException) =
        lock.withLock {
            val id = failure.providerId
            when {
                configured().any { it.providerId == id } -> {
                    authFailed += id
                    if (shown(id) != true) doShow(id, failure.displayName)
                }
                providers().any { it.providerId == id } -> Unit
                else -> apply(authAlertActions(states(), showUnavailable = true))
            }
        }

    /** A call through [providerId] succeeded: its alert, if any, is cleared. */
    suspend fun recovered(providerId: String) =
        lock.withLock {
            authFailed -= providerId
            apply(authAlertActions(states(only = providerId), showUnavailable = false))
        }

    /** Forwarding started: alert every configured provider that is unavailable, under its own name. */
    suspend fun serviceStarted() = lock.withLock { apply(authAlertActions(states(), showUnavailable = true)) }

    /** The periodic check: clears alerts that are no longer true. Never raises one. */
    suspend fun reconcile() = lock.withLock { apply(authAlertActions(states(), showUnavailable = false)) }

    /** The user disconnected [providerId]: forget its failure and remove its alert. */
    suspend fun disconnected(providerId: String) =
        lock.withLock {
            authFailed -= providerId
            doClear(providerId)
        }

    private fun states(only: String? = null): List<ProviderAlertState> {
        val memberIds = configured().map { it.providerId }.toSet()
        authFailed.retainAll(memberIds)
        return providers()
            .filter { (only == null || it.providerId == only) && (it.providerId in memberIds || it.providerId in postedHere) }
            .map { provider ->
                val isMember = provider.providerId in memberIds
                ProviderAlertState(
                    providerId = provider.providerId,
                    displayName = provider.displayName,
                    configured = isMember,
                    // Only read for members: for anything else it does not matter, and it can cost a Keystore read.
                    available = isMember && provider.isAvailable,
                    authFailed = provider.providerId in authFailed,
                    alertShown = shown(provider.providerId),
                )
            }
    }

    private fun shown(providerId: String): Boolean? {
        if (providerId in knownHidden) return false
        val showing =
            try {
                isShowing(providerId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return null
            }
        if (!showing) knownHidden += providerId
        return showing
    }

    private fun apply(actions: List<AuthAlertAction>) =
        actions.forEach { action ->
            when (action) {
                is AuthAlertAction.Show -> doShow(action.providerId, action.displayName)
                is AuthAlertAction.Clear -> doClear(action.providerId)
            }
        }

    private fun doShow(
        providerId: String,
        displayName: String,
    ) {
        knownHidden -= providerId
        postedHere += providerId
        show(providerId, displayName)
    }

    private fun doClear(providerId: String) {
        clear(providerId)
        knownHidden += providerId
        postedHere -= providerId
    }
}
