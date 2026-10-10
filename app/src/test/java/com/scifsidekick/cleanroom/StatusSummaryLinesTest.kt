package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.data.accountLines
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
}
