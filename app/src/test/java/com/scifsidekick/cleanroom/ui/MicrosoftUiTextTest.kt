package com.scifsidekick.cleanroom.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrosoftUiTextTest {
    // ------------------------------------------------------------------ statusLine

    @Test fun `statusLine covers every state`() {
        assertEquals("Not set up", MicrosoftUiText.statusLine(MicrosoftUiState.NotConfigured))
        assertEquals("Not connected", MicrosoftUiText.statusLine(MicrosoftUiState.Idle(null)))
        assertEquals("Not connected", MicrosoftUiText.statusLine(MicrosoftUiState.Idle("a@outlook.com")))
        assertEquals(
            "Waiting for you to enter the code",
            MicrosoftUiText.statusLine(MicrosoftUiState.WaitingForCode("ABCD1234", "https://microsoft.com/devicelogin", 0L)),
        )
        assertEquals("Finishing sign-in...", MicrosoftUiText.statusLine(MicrosoftUiState.Connecting))
        assertEquals("Connected as a@outlook.com", MicrosoftUiText.statusLine(MicrosoftUiState.Connected("a@outlook.com")))
        assertEquals("Connected", MicrosoftUiText.statusLine(MicrosoftUiState.Connected("")))
        assertEquals(
            "a@outlook.com needs to be connected again",
            MicrosoftUiText.statusLine(MicrosoftUiState.NeedsReconnect("a@outlook.com")),
        )
        assertEquals("The account needs to be connected again", MicrosoftUiText.statusLine(MicrosoftUiState.NeedsReconnect(null)))
        assertEquals("Microsoft sign-in failed. Try again.", MicrosoftUiText.statusLine(MicrosoftUiState.Error("Microsoft sign-in failed. Try again.")))
    }

    @Test fun `statusLine for waiting never contains the code`() {
        val line = MicrosoftUiText.statusLine(MicrosoftUiState.WaitingForCode("SECRET42", "https://microsoft.com/devicelogin", 0L))
        assertFalse(line.contains("SECRET42"))
    }

    // ------------------------------------------------------------------- spacedCode

    @Test fun `spacedCode separates every character`() {
        assertEquals("A B C D 1 2 3 4", MicrosoftUiText.spacedCode("ABCD1234"))
    }

    @Test fun `spacedCode drops existing whitespace and handles empty`() {
        assertEquals("A B C", MicrosoftUiText.spacedCode(" A B\tC "))
        assertEquals("", MicrosoftUiText.spacedCode(""))
    }

    // -------------------------------------------------------------------- countdown

    @Test fun `countdown formats minutes and zero-padded seconds`() {
        assertEquals("15:00", MicrosoftUiText.countdown(expiresAtMs = 900_000L, nowMs = 0L))
        assertEquals("1:05", MicrosoftUiText.countdown(expiresAtMs = 65_000L, nowMs = 0L))
        assertEquals("0:09", MicrosoftUiText.countdown(expiresAtMs = 10_000L, nowMs = 1_000L))
    }

    @Test fun `countdown rounds a partial second up so it reaches 0 00 only at expiry`() {
        assertEquals("0:01", MicrosoftUiText.countdown(expiresAtMs = 1_000L, nowMs = 1L))
        assertEquals("0:00", MicrosoftUiText.countdown(expiresAtMs = 1_000L, nowMs = 1_000L))
    }

    @Test fun `countdown is never negative`() {
        assertEquals("0:00", MicrosoftUiText.countdown(expiresAtMs = 1_000L, nowMs = 999_999L))
    }

    // --------------------------------------------------------- isTrustedVerificationUri

    @Test fun `microsoft verification pages are trusted`() {
        assertTrue(MicrosoftUiText.isTrustedVerificationUri("https://microsoft.com/devicelogin"))
        assertTrue(MicrosoftUiText.isTrustedVerificationUri("https://www.microsoft.com/link"))
        assertTrue(MicrosoftUiText.isTrustedVerificationUri("https://login.microsoft.com/device"))
        assertTrue(MicrosoftUiText.isTrustedVerificationUri("https://login.microsoftonline.com/common/oauth2/deviceauth"))
        assertTrue(MicrosoftUiText.isTrustedVerificationUri("HTTPS://Login.Microsoft.COM/device"))
    }

    @Test fun `an explicit default https port is trusted`() {
        assertTrue(MicrosoftUiText.isTrustedVerificationUri("https://login.microsoft.com:443/x"))
    }

    @Test fun `the trusted uri returned for launching is the trimmed, validated text`() {
        assertEquals(
            "https://microsoft.com/devicelogin",
            MicrosoftUiText.trustedVerificationUri("  https://microsoft.com/devicelogin \n"),
        )
        assertTrue(MicrosoftUiText.isTrustedVerificationUri("\thttps://microsoft.com/devicelogin "))
        assertNull(MicrosoftUiText.trustedVerificationUri("  https://microsoft.com.evil.com/ "))
        assertNull(MicrosoftUiText.trustedVerificationUri("https://microsoft.com@evil.com/"))
    }

    @Test fun `non-https is rejected`() {
        assertFalse(MicrosoftUiText.isTrustedVerificationUri("http://microsoft.com/devicelogin"))
        assertFalse(MicrosoftUiText.isTrustedVerificationUri("javascript:alert(1)"))
        assertFalse(MicrosoftUiText.isTrustedVerificationUri("intent://microsoft.com/#Intent;end"))
        assertFalse(MicrosoftUiText.isTrustedVerificationUri("file:///sdcard/microsoft.com"))
        assertFalse(MicrosoftUiText.isTrustedVerificationUri("microsoft.com/devicelogin"))
    }

    @Test fun `look-alike hosts are rejected`() {
        assertFalse(MicrosoftUiText.isTrustedVerificationUri("https://microsoft.com.evil.com/devicelogin"))
        assertFalse(MicrosoftUiText.isTrustedVerificationUri("https://evilmicrosoft.com/devicelogin"))
        assertFalse(MicrosoftUiText.isTrustedVerificationUri("https://evilmicrosoftonline.com/x"))
        assertFalse(MicrosoftUiText.isTrustedVerificationUri("https://microsoftonline.com.evil.com/x"))
        assertFalse(MicrosoftUiText.isTrustedVerificationUri("https://microsoft.co/devicelogin"))
        assertFalse(MicrosoftUiText.isTrustedVerificationUri("https://microsoft.com./devicelogin"))
    }

    @Test fun `userinfo and authority tricks are rejected`() {
        assertFalse(MicrosoftUiText.isTrustedVerificationUri("https://microsoft.com@evil.com/devicelogin"))
        assertFalse(MicrosoftUiText.isTrustedVerificationUri("https://user:pass@microsoft.com/devicelogin"))
        assertFalse(MicrosoftUiText.isTrustedVerificationUri("https://evil.com\\@microsoft.com/"))
        assertFalse(MicrosoftUiText.isTrustedVerificationUri("https://evil.com#@microsoft.com/"))
        assertFalse(MicrosoftUiText.isTrustedVerificationUri("https://microsoft.com:8443/devicelogin"))
    }

    @Test fun `blank and malformed input is rejected`() {
        assertFalse(MicrosoftUiText.isTrustedVerificationUri(""))
        assertFalse(MicrosoftUiText.isTrustedVerificationUri("   "))
        assertFalse(MicrosoftUiText.isTrustedVerificationUri("https://"))
        assertFalse(MicrosoftUiText.isTrustedVerificationUri("https:// microsoft.com/"))
    }

    // ------------------------------------------------------------- normalizeClientId

    @Test fun `normalizeClientId accepts a guid with surrounding spaces`() {
        assertEquals(
            "0f1e2d3c-4b5a-6978-8a9b-acbdcedf0011",
            MicrosoftUiText.normalizeClientId("  0f1e2d3c-4b5a-6978-8a9b-acbdcedf0011 \n"),
        )
        assertEquals(
            "0F1E2D3C-4B5A-6978-8A9B-ACBDCEDF0011",
            MicrosoftUiText.normalizeClientId("0F1E2D3C-4B5A-6978-8A9B-ACBDCEDF0011"),
        )
    }

    @Test fun `normalizeClientId rejects anything that is not a guid`() {
        assertNull(MicrosoftUiText.normalizeClientId(""))
        assertNull(MicrosoftUiText.normalizeClientId("not-a-guid"))
        assertNull(MicrosoftUiText.normalizeClientId("0f1e2d3c4b5a69788a9bacbdcedf0011"))
        assertNull(MicrosoftUiText.normalizeClientId("0f1e2d3c-4b5a-6978-8a9b-acbdcedf001"))
        assertNull(MicrosoftUiText.normalizeClientId("0f1e2d3c-4b5a-6978-8a9b-acbdcedf0011x"))
        assertNull(MicrosoftUiText.normalizeClientId("0f1e2d3c-4b5a-6978-8a9b-acbdcedf00g1"))
        assertNull(MicrosoftUiText.normalizeClientId("0f1e2d3c-4b5a-6978 8a9b-acbdcedf0011"))
    }
}
