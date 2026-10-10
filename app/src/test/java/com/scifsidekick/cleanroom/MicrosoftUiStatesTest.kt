package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.ui.MicrosoftUiState
import com.scifsidekick.cleanroom.ui.SignInProgress
import com.scifsidekick.cleanroom.ui.microsoftStateFor
import org.junit.Assert.assertEquals
import org.junit.Test

class MicrosoftUiStatesTest {
    private fun state(
        clientId: String = "client-id",
        email: String? = null,
        authorized: Boolean = false,
        signIn: SignInProgress = SignInProgress.None,
    ) = microsoftStateFor(clientId, email, authorized, signIn)

    @Test fun `no client id is NotConfigured`() {
        assertEquals(MicrosoftUiState.NotConfigured, state(clientId = ""))
        assertEquals(MicrosoftUiState.NotConfigured, state(clientId = "   "))
    }

    @Test fun `a client id and no account is Idle`() {
        assertEquals(MicrosoftUiState.Idle(null), state())
    }

    @Test fun `a stored token is Connected with the stored address`() {
        assertEquals(MicrosoftUiState.Connected("me@outlook.com"), state(email = "me@outlook.com", authorized = true))
    }

    @Test fun `a stored token without a known address is still Connected`() {
        assertEquals(MicrosoftUiState.Connected(""), state(authorized = true))
    }

    @Test fun `a remembered address without a token needs reconnecting`() {
        assertEquals(MicrosoftUiState.NeedsReconnect("me@outlook.com"), state(email = "me@outlook.com"))
    }

    @Test fun `a sign-in in progress shows its step over the stored state`() {
        val waiting = SignInProgress.Waiting("ABCD-EFGH", "https://microsoft.com/devicelogin", 1_000L)
        assertEquals(
            MicrosoftUiState.WaitingForCode("ABCD-EFGH", "https://microsoft.com/devicelogin", 1_000L),
            state(email = "me@outlook.com", signIn = waiting),
        )
        assertEquals(MicrosoftUiState.Connecting, state(signIn = SignInProgress.Connecting))
        assertEquals(MicrosoftUiState.Connecting, state(clientId = "", signIn = SignInProgress.Connecting))
    }

    @Test fun `a failed sign-in shows its message, even without a client id`() {
        assertEquals(MicrosoftUiState.Error("Sign-in was declined."), state(signIn = SignInProgress.Failed("Sign-in was declined.")))
        assertEquals(MicrosoftUiState.Error("Enter your Microsoft app ID first"), state(clientId = "", signIn = SignInProgress.Failed("Enter your Microsoft app ID first")))
    }
}
