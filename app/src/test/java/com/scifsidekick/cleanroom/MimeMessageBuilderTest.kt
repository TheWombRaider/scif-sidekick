package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.email.MimeMessageBuilder
import com.scifsidekick.cleanroom.messaging.EmailPayload
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class MimeMessageBuilderTest {
    private fun payload(
        replyTarget: String?,
        source: String,
    ) = EmailPayload(
        destinations = listOf("someone@example.com"),
        replyTarget = replyTarget,
        senderDisplay = "Test",
        body = "hello",
        receivedAtMs = 1_700_000_000_000L,
        source = source,
        participants = emptyList(),
        attachmentNotice = null,
        renderedSubject = "Subject",
        renderedBody = "Rendered body",
        filterName = "filter",
    )

    /** Decodes the MIME message all the way down to its plain-text body -- one base64 layer for
     *  the whole raw message, a second for the text/plain part's own Content-Transfer-Encoding. */
    private fun decodedBody(rawBase64Url: String): String {
        val message = String(Base64.getUrlDecoder().decode(rawBase64Url), Charsets.UTF_8)
        val bodyBase64 = message.substringAfterLast("\r\n\r\n").trim()
        return String(Base64.getMimeDecoder().decode(bodyBase64), Charsets.UTF_8)
    }

    @Test fun `a real forwarded message with no reply target discloses why reply-by-email is unavailable`() {
        val mime = MimeMessageBuilder.build(payload(replyTarget = null, source = "sms"), emptyList(), "key1", "from@example.com")
        val body = decodedBody(mime.rawBase64Url)
        assertTrue(body.contains("Reply-by-email is unavailable"))
    }

    @Test fun `a bare connectivity test never claims reply-by-email is unavailable`() {
        val mime = MimeMessageBuilder.build(payload(replyTarget = null, source = "test"), emptyList(), "key2", "from@example.com")
        val body = decodedBody(mime.rawBase64Url)
        assertFalse(body.contains("Reply-by-email is unavailable"))
        assertFalse(body.contains("Reply to this email"))
    }

    @Test fun `a message with a real reply target gets reply instructions regardless of source`() {
        val mime = MimeMessageBuilder.build(payload(replyTarget = "+15551234567", source = "sms"), emptyList(), "key3", "from@example.com")
        val body = decodedBody(mime.rawBase64Url)
        assertTrue(body.contains("Reply to this email to send an SMS to +15551234567"))
    }
}
