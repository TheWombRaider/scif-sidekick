package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.data.accountLines
import com.scifsidekick.cleanroom.data.gmailAuthorizationLine
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusSummaryLinesTest {
    @Test fun `no other accounts adds no lines`() {
        assertEquals(emptyList<String>(), accountLines(emptyList()))
    }

    @Test fun `a connected Outlook account reads OK`() {
        assertEquals(listOf("Outlook authorization: OK"), accountLines(listOf("Outlook" to true)))
    }

    @Test fun `an Outlook account that needs reconnecting says so`() {
        assertEquals(listOf("Outlook authorization: NEEDS RECONNECTING"), accountLines(listOf("Outlook" to false)))
    }

    @Test fun `two accounts keep their order`() {
        assertEquals(
            listOf("Outlook authorization: NEEDS RECONNECTING", "Other authorization: OK"),
            accountLines(listOf("Outlook" to false, "Other" to true)),
        )
    }

    @Test fun `the Gmail line of a Gmail member is unchanged`() {
        assertEquals("Gmail authorization: OK", gmailAuthorizationLine(gmailAvailable = true))
        assertEquals("Gmail authorization: NEEDS RECONNECTING", gmailAuthorizationLine(gmailAvailable = false))
        assertEquals("Gmail authorization: OK", gmailAuthorizationLine(gmailAvailable = true, gmailIsMember = true))
        assertEquals("Gmail authorization: NEEDS RECONNECTING", gmailAuthorizationLine(gmailAvailable = false, gmailIsMember = true))
    }

    @Test fun `a Gmail that is not a member reads not connected`() {
        assertEquals("Gmail authorization: not connected", gmailAuthorizationLine(gmailAvailable = false, gmailIsMember = false))
    }
}
