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

/** The optional attachment budget: the default must leave Gmail's output exactly as it was. */
class MimeMessageBuilderBudgetTest {
    private val files = mutableListOf<File>()

    @After fun cleanUp() {
        files.forEach { it.delete() }
    }

    private fun attachment(bytes: Int): String =
        File.createTempFile("budget", ".jpg").also {
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

    private fun decoded(raw: String) = String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)

    /** The multipart boundary is random per build; everything else must match byte for byte. */
    private fun normalized(raw: String) = decoded(raw).replace(Regex("sidekick_[0-9a-f-]{36}"), "BOUNDARY")

    @Test fun `the default budget gives the same output as Gmail's explicit budget`() {
        assertEquals(
            MimeMessageBuilder.build(payload, emptyList(), "key", "from@example.com").rawBase64Url,
            MimeMessageBuilder.build(payload, emptyList(), "key", "from@example.com", MimeMessageBuilder.MAX_SOURCE_ATTACHMENT_BYTES).rawBase64Url,
        )
        val path = attachment(1_000_000)
        val default = MimeMessageBuilder.build(payload, listOf(path), "key", "from@example.com")
        val explicit = MimeMessageBuilder.build(payload, listOf(path), "key", "from@example.com", MimeMessageBuilder.MAX_SOURCE_ATTACHMENT_BYTES)
        assertEquals(normalized(default.rawBase64Url), normalized(explicit.rawBase64Url))
        assertEquals(0L, default.omittedAttachmentBytes)
        assertTrue(decoded(default.rawBase64Url).contains("Content-Disposition: attachment"))
    }

    @Test fun `attachments over a smaller budget are left out and the body says so`() {
        val path = attachment(1_000_000)
        val built = MimeMessageBuilder.build(payload, listOf(path), "key", "from@example.com", maxAttachmentBytes = 500_000)
        assertEquals(1_000_000L, built.omittedAttachmentBytes)
        val message = decoded(built.rawBase64Url)
        assertFalse(message.contains("Content-Disposition: attachment"))
        val body = String(Base64.getMimeDecoder().decode(message.substringAfterLast("\r\n\r\n").trim()), Charsets.UTF_8)
        assertTrue(body.contains("Attachment not forwarded: 1000000 bytes"))
    }
}
