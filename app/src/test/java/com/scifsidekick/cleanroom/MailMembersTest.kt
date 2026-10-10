package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.email.gmailDisconnectedAfterOutlookSignIn
import com.scifsidekick.cleanroom.email.gmailIsMember
import com.scifsidekick.cleanroom.email.mailAccountStatuses
import com.scifsidekick.cleanroom.email.mailMemberIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MailMembersTest {
    private val never: () -> Boolean = { error("Gmail's state must not be read here") }

    @Test fun `without Microsoft, Gmail is always a member whatever happened to it`() {
        for (onPurpose in listOf(false, true)) {
            assertTrue(gmailIsMember(onPurpose, microsoftConfigured = false, gmailAvailable = never))
            assertEquals(listOf("gmail"), mailMemberIds(onPurpose, microsoftConfigured = false, preferGraph = true, gmailAvailable = never))
        }
    }

    @Test fun `a Gmail disconnected on purpose drops out once Microsoft is set up`() {
        assertFalse(gmailIsMember(true, microsoftConfigured = true) { false })
        assertEquals(listOf("graph"), mailMemberIds(true, microsoftConfigured = true, preferGraph = false) { false })
    }

    @Test fun `a Gmail that is signed in again counts at once`() {
        assertTrue(gmailIsMember(true, microsoftConfigured = true) { true })
        assertEquals(listOf("gmail", "graph"), mailMemberIds(true, microsoftConfigured = true, preferGraph = false) { true })
    }

    @Test fun `a Gmail that merely needs reconnecting stays a member so it is still alerted`() {
        assertTrue(gmailIsMember(false, microsoftConfigured = true) { false })
        assertEquals(listOf("gmail", "graph"), mailMemberIds(false, microsoftConfigured = true, preferGraph = false) { false })
    }

    @Test fun `the preferred provider goes first`() {
        assertEquals(listOf("graph", "gmail"), mailMemberIds(false, microsoftConfigured = true, preferGraph = true) { true })
        assertEquals(listOf("gmail", "graph"), mailMemberIds(false, microsoftConfigured = true, preferGraph = false) { true })
    }

    @Test fun `Gmail-only status lists just Gmail, exactly as before`() {
        assertEquals(listOf("Gmail" to true), mailAccountStatuses(true, { true }, microsoftConfigured = false) { error("no Outlook") })
        assertEquals(listOf("Gmail" to false), mailAccountStatuses(true, { false }, microsoftConfigured = false) { error("no Outlook") })
    }

    @Test fun `with Outlook set up both accounts are listed, Gmail first`() {
        assertEquals(listOf("Gmail" to false, "Outlook" to true), mailAccountStatuses(true, { false }, microsoftConfigured = true) { true })
    }

    @Test fun `a Gmail that is not a member is left out of the status list`() {
        assertEquals(listOf("Outlook" to true), mailAccountStatuses(false, never, microsoftConfigured = true) { true })
    }

    @Test fun `an Outlook sign-in without a usable Gmail marks Gmail as not wanted`() {
        assertTrue(gmailDisconnectedAfterOutlookSignIn(current = false, gmailAvailable = false))
        assertTrue(gmailDisconnectedAfterOutlookSignIn(current = true, gmailAvailable = false))
    }

    @Test fun `an Outlook sign-in leaves a connected Gmail alone`() {
        assertFalse(gmailDisconnectedAfterOutlookSignIn(current = false, gmailAvailable = true))
        assertTrue(gmailDisconnectedAfterOutlookSignIn(current = true, gmailAvailable = true))
    }
}
