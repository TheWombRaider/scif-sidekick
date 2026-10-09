package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.email.MailAuthRequiredException
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
}
