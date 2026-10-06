package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.data.ForwardedMessageEntity
import com.scifsidekick.cleanroom.util.MessageLogExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageLogExportTest {
    private fun message(
        source: String = "sms",
        sender: String = "+15551234567",
        display: String = "Alex",
        body: String = "hello",
        forwarded: Boolean = true,
        attempts: Int = 1,
        error: String? = null,
        receivedAtMs: Long = 1_700_000_000_000L,
    ) = ForwardedMessageEntity(
        source = source,
        senderAddress = sender,
        senderDisplay = display,
        body = body,
        bodyHash = "hash",
        receivedAtMs = receivedAtMs,
        sourceTimestampMs = receivedAtMs,
        forwarded = forwarded,
        sendAttemptCount = attempts,
        lastError = error,
    )

    @Test fun `header omits body column when body excluded`() {
        val csv = MessageLogExport.toCsv(listOf(message()), includeBody = false)
        val header = csv.lineSequence().first()
        assertFalse(header.contains("Message Body"))
        assertFalse(csv.contains("hello"))
    }

    @Test fun `header includes body column and content when body included`() {
        val csv = MessageLogExport.toCsv(listOf(message(body = "hello there")), includeBody = true)
        val header = csv.lineSequence().first()
        assertTrue(header.contains("Message Body"))
        assertTrue(csv.contains("hello there"))
    }

    @Test fun `forwarded flag renders as Yes or No`() {
        val csv = MessageLogExport.toCsv(listOf(message(forwarded = true), message(forwarded = false)), includeBody = false)
        val rows = csv.lines().filter { it.isNotBlank() }.drop(1)
        assertEquals(2, rows.size)
        assertTrue(rows[0].contains(",Yes,"))
        assertTrue(rows[1].contains(",No,"))
    }

    @Test fun `fields containing commas or quotes are RFC 4180 escaped`() {
        val csv =
            MessageLogExport.toCsv(
                listOf(message(display = "Doe, Jane \"JJ\"", error = "line1\nline2")),
                includeBody = false,
            )
        assertTrue(csv.contains("\"Doe, Jane \"\"JJ\"\"\""))
        assertTrue(csv.contains("\"line1\nline2\""))
    }

    @Test fun `empty input still produces just a header line`() {
        val csv = MessageLogExport.toCsv(emptyList(), includeBody = false)
        assertEquals(1, csv.lines().count { it.isNotBlank() })
    }
}
