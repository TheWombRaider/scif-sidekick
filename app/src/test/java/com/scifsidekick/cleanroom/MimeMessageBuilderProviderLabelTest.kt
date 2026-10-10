package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.email.MimeMessageBuilder
import com.scifsidekick.cleanroom.messaging.EmailPayload
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/** The omission disclosure names the provider; the default stays Gmail's exact wording. */
class MimeMessageBuilderProviderLabelTest {
    private val files = mutableListOf<File>()

    @After fun cleanUp() {
        files.forEach { it.delete() }
    }

    private fun attachment(bytes: Int): String =
        File.createTempFile("label", ".jpg").also {
            it.writeBytes(ByteArray(bytes) { i -> (i % 251).toByte() })
            files += it
        }.path

    private val payload =
        EmailPayload(
            destinations = listOf("someone@example.com"),
            replyTarget = "+15551234567",
            senderDisplay = "Test",
            body = "hello",
            receivedAtMs = 1_700_000_000_000L,
            source = "sms",
            participants = emptyList(),
            attachmentNotice = null,
            renderedSubject = "Subject",
            renderedBody = "Rendered body",
            filterName = "filter",
        )

    private fun bodyOf(raw: String): String {
        val message = String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)
        return String(Base64.getMimeDecoder().decode(message.substringAfterLast("\r\n\r\n").trim()), Charsets.UTF_8)
    }

    @Test fun `the default disclosure still names Gmail`() {
        val built = MimeMessageBuilder.build(payload, listOf(attachment(2_000)), "key", "from@example.com", maxAttachmentBytes = 1_000)
        assertEquals(2_000L, built.omittedAttachmentBytes)
        assertTrue(bodyOf(built.rawBase64Url).contains("Attachment not forwarded: 2000 bytes exceeds the safe Gmail message-size budget."))
    }

    @Test fun `an explicit Gmail label is byte identical to the default`() {
        val path = attachment(2_000)
        assertEquals(
            MimeMessageBuilder.build(payload, listOf(path), "key", "from@example.com", maxAttachmentBytes = 1_000).rawBase64Url,
            MimeMessageBuilder.build(payload, listOf(path), "key", "from@example.com", maxAttachmentBytes = 1_000, providerLabel = "Gmail").rawBase64Url,
        )
    }

    @Test fun `the Outlook label names Outlook`() {
        val built =
            MimeMessageBuilder.build(payload, listOf(attachment(2_000)), "key", "from@example.com", maxAttachmentBytes = 1_000, providerLabel = "Outlook")
        val body = bodyOf(built.rawBase64Url)
        assertTrue(body.contains("Attachment not forwarded: 2000 bytes exceeds the safe Outlook message-size budget."))
        assertFalse(body.contains("Gmail"))
    }

    @Test fun `the label does not appear when nothing is omitted`() {
        val built = MimeMessageBuilder.build(payload, emptyList(), "key", "from@example.com", providerLabel = "Outlook")
        assertFalse(bodyOf(built.rawBase64Url).contains("Outlook"))
    }
}
