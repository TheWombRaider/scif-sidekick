package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.util.ComposeAuthorization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Case folding must never turn a non-ASCII address into an ASCII one. */
class ComposeAuthorizationAsciiTest {
    private val kelvin = "K"

    @Test fun `a Kelvin sign in the domain is rejected rather than folded to k`() {
        assertNull(ComposeAuthorization.canonicalAddress("boss@${kelvin}agency.gov"))
        assertNull(ComposeAuthorization.canonicalAddress("boss@agency.gov$kelvin"))
        assertNull(ComposeAuthorization.extractAddress("Boss <boss@${kelvin}agency.gov>"))
    }

    @Test fun `a Kelvin sign in the local part is rejected rather than folded to k`() {
        assertNull(ComposeAuthorization.canonicalAddress("${kelvin}ate@agency.gov"))
        assertNull(ComposeAuthorization.extractAddress("${kelvin}ate@agency.gov"))
    }

    @Test fun `other non ASCII letters that fold to ASCII are rejected`() {
        // U+0130 (dotted capital I) and U+017F (long s).
        assertNull(ComposeAuthorization.canonicalAddress("boss@İagency.gov"))
        assertNull(ComposeAuthorization.canonicalAddress("boſſ@agency.gov"))
    }

    @Test fun `a Kelvin sign address does not match an allow listed ASCII address`() {
        assertFalse(ComposeAuthorization.isAuthorizedSender("boss@${kelvin}agency.gov", listOf("boss@kagency.gov")))
        assertFalse(ComposeAuthorization.isAuthorizedSender("boss@kagency.gov", listOf("boss@${kelvin}agency.gov")))
    }

    @Test fun `normal mixed case addresses are still lowercased`() {
        assertEquals("boss@agency.gov", ComposeAuthorization.canonicalAddress("  Boss@Agency.GOV "))
        assertEquals("kate@kagency.gov", ComposeAuthorization.canonicalAddress("KATE@KAGENCY.GOV"))
        assertEquals("boss@agency.gov", ComposeAuthorization.extractAddress("\"Boss\" <BOSS@agency.gov>"))
    }
}
