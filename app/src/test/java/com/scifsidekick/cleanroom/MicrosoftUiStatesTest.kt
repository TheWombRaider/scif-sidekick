package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.email.graph.DeviceCode
import com.scifsidekick.cleanroom.ui.MicrosoftAccountFacts
import com.scifsidekick.cleanroom.ui.MicrosoftUiState
import com.scifsidekick.cleanroom.ui.OutlookChipState
import com.scifsidekick.cleanroom.ui.SignInProgress
import com.scifsidekick.cleanroom.ui.microsoftStateFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun `a stored account still shows when the client id is blanked`() {
        assertEquals(MicrosoftUiState.Connected("me@outlook.com"), state(clientId = "", email = "me@outlook.com", authorized = true))
        assertEquals(MicrosoftUiState.Connected(""), state(clientId = " ", authorized = true))
        assertEquals(MicrosoftUiState.NeedsReconnect("me@outlook.com"), state(clientId = "", email = "me@outlook.com"))
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

    // ---------------------------------------------------------------- Home chip

    @Test fun `chip is hidden without a stored account`() {
        assertEquals(OutlookChipState.HIDDEN, OutlookChipState.of(accountStored = false, authorized = false))
    }

    @Test fun `chip is OK whenever a token is stored`() {
        assertEquals(OutlookChipState.OK, OutlookChipState.of(accountStored = true, authorized = true))
        assertEquals(OutlookChipState.OK, OutlookChipState.of(accountStored = false, authorized = true))
    }

    @Test fun `chip warns for a stored account without a token`() {
        assertEquals(OutlookChipState.WARN, OutlookChipState.of(accountStored = true, authorized = false))
    }

    @Test fun `a failed or pending reconnect keeps the chip at WARN while the card shows the sign-in step`() {
        val facts = MicrosoftAccountFacts(email = "me@outlook.com", authorized = false)
        assertTrue(facts.stored)
        // The card moves on to the sign-in step, but the chip only looks at the stored facts.
        assertEquals(MicrosoftUiState.Error("Sign-in was declined."), state(email = facts.email, signIn = SignInProgress.Failed("Sign-in was declined.")))
        assertEquals(OutlookChipState.WARN, OutlookChipState.of(facts.stored, facts.authorized))
        assertEquals(MicrosoftUiState.Connecting, state(email = facts.email, signIn = SignInProgress.Connecting))
        assertEquals(OutlookChipState.WARN, OutlookChipState.of(facts.stored, facts.authorized))
    }

    @Test fun `account facts are stored with an address or a token`() {
        assertFalse(MicrosoftAccountFacts(null, false).stored)
        assertTrue(MicrosoftAccountFacts("me@outlook.com", false).stored)
        assertTrue(MicrosoftAccountFacts(null, true).stored)
    }

    // ------------------------------------------------------------- redaction

    @Test fun `toString never contains the user code or the device code`() {
        val userCode = "QWER7788"
        val deviceCode = "DEVICE-SECRET-123456"
        val printed =
            listOf(
                DeviceCode(userCode, "https://microsoft.com/devicelogin", deviceCode, 900, 5, "Open the page"),
                MicrosoftUiState.WaitingForCode(userCode, "https://microsoft.com/devicelogin", 1_000L),
                SignInProgress.Waiting(userCode, "https://microsoft.com/devicelogin", 1_000L),
            ).map { it.toString() }
        printed.forEach { text ->
            assertFalse(text, text.contains(userCode))
            assertFalse(text, text.contains(deviceCode))
            assertTrue(text, text.contains("<redacted>"))
        }
    }
}
