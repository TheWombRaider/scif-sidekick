package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.email.GmailApiException
import com.scifsidekick.cleanroom.email.MailAuthRequiredException
import com.scifsidekick.cleanroom.email.MailHttpException
import com.scifsidekick.cleanroom.email.ReauthorizationRequiredException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MailTypesTest {
    @Test fun `gmail reauthorization is a mail auth failure so shared catch sites still catch it`() {
        val failure: Throwable = ReauthorizationRequiredException()
        assertTrue(failure is MailAuthRequiredException)
        assertEquals("Open the app and reconnect Gmail", failure.message)
    }

    @Test fun `gmail reauthorization names its provider`() {
        val failure = ReauthorizationRequiredException()
        assertEquals("gmail", failure.providerId)
        assertEquals("Gmail", failure.displayName)
    }

    @Test fun `gmail api failure is a mail http failure with its status and text`() {
        val failure: Throwable = GmailApiException(404, "x")
        assertTrue(failure is MailHttpException)
        assertEquals(404, (failure as MailHttpException).statusCode)
        assertEquals("Gmail API HTTP 404: x", failure.message)
    }
}
