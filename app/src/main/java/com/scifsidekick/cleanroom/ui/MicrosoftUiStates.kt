package com.scifsidekick.cleanroom.ui

/** What the Microsoft (Outlook.com) account card shows. */
sealed interface MicrosoftUiState {
    /** No app (client) ID entered yet. */
    data object NotConfigured : MicrosoftUiState

    /** Ready to connect. */
    data class Idle(
        val email: String?,
    ) : MicrosoftUiState

    /** The user must open [uri] and enter [code] before [expiresAtMs]. */
    data class WaitingForCode(
        val code: String,
        val uri: String,
        val expiresAtMs: Long,
    ) : MicrosoftUiState

    data object Connecting : MicrosoftUiState

    /** Signed in. [email] is blank when the address could not be read. */
    data class Connected(
        val email: String,
    ) : MicrosoftUiState

    /** An account was connected but its sign-in is gone (revoked, expired or unreadable). */
    data class NeedsReconnect(
        val email: String?,
    ) : MicrosoftUiState

    /** The last sign-in attempt failed; [message] is a fixed, secret-free text. */
    data class Error(
        val message: String,
    ) : MicrosoftUiState
}

/** Where an interactive sign-in is, held by the view model alongside the stored account state. */
sealed interface SignInProgress {
    data object None : SignInProgress

    data class Waiting(
        val code: String,
        val uri: String,
        val expiresAtMs: Long,
    ) : SignInProgress

    data object Connecting : SignInProgress

    data class Failed(
        val message: String,
    ) : SignInProgress
}

/**
 * The card state from the stored settings ([clientId], the remembered [connectedEmail]), whether a
 * refresh token is stored ([authorized]) and the sign-in in progress. A sign-in in progress or its
 * failure wins over the stored state; a remembered address without a token needs reconnecting. A
 * blank client id means NotConfigured only when no account is stored: a connected account still
 * shows as Connected or NeedsReconnect.
 */
fun microsoftStateFor(
    clientId: String,
    connectedEmail: String?,
    authorized: Boolean,
    signIn: SignInProgress,
): MicrosoftUiState =
    when {
        signIn is SignInProgress.Waiting -> MicrosoftUiState.WaitingForCode(signIn.code, signIn.uri, signIn.expiresAtMs)
        signIn is SignInProgress.Connecting -> MicrosoftUiState.Connecting
        signIn is SignInProgress.Failed -> MicrosoftUiState.Error(signIn.message)
        clientId.isBlank() && connectedEmail == null && !authorized -> MicrosoftUiState.NotConfigured
        authorized -> MicrosoftUiState.Connected(connectedEmail.orEmpty())
        connectedEmail != null -> MicrosoftUiState.NeedsReconnect(connectedEmail)
        else -> MicrosoftUiState.Idle(null)
    }
