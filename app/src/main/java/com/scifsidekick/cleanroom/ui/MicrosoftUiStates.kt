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
    ) : MicrosoftUiState {
        // The user code is shown in the panel only; keep it out of anything that prints this object.
        override fun toString(): String = "WaitingForCode(code=<redacted>, uri=$uri, expiresAtMs=$expiresAtMs)"
    }

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
    ) : SignInProgress {
        override fun toString(): String = "Waiting(code=<redacted>, uri=$uri, expiresAtMs=$expiresAtMs)"
    }

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

/**
 * The stored Microsoft account facts, independent of any sign-in in progress: [email] is the
 * remembered address, [authorized] whether a refresh token is stored.
 */
data class MicrosoftAccountFacts(
    val email: String?,
    val authorized: Boolean,
) {
    /** An account was connected on this phone (and not disconnected), working or not. */
    val stored: Boolean get() = email != null || authorized
}

/** The Home screen's Outlook chip, decided from the stored account only, never from a sign-in in progress. */
enum class OutlookChipState {
    HIDDEN,
    OK,
    WARN,
    ;

    companion object {
        fun of(
            accountStored: Boolean,
            authorized: Boolean,
        ): OutlookChipState =
            when {
                authorized -> OK
                accountStored -> WARN
                else -> HIDDEN
            }
    }
}
