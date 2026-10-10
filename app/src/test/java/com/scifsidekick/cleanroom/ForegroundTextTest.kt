package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.data.ForwardingStateEntity
import com.scifsidekick.cleanroom.service.AlertNotifier
import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundTextTest {
    private val on = ForwardingStateEntity(enabled = true)

    @Test fun `Gmail-only texts are exactly what they were`() {
        assertEquals("Forwarding waiting for Gmail connection", AlertNotifier.foregroundText(on, 3, mailAvailable = false))
        assertEquals("Forwarding active • 3 queued", AlertNotifier.foregroundText(on, 3, mailAvailable = true))
        assertEquals("Forwarding active", AlertNotifier.foregroundText(on, null, mailAvailable = true))
        assertEquals("Forwarding paused — too many failures", AlertNotifier.foregroundText(on.copy(emailCircuitOpen = true), 0, mailAvailable = false))
        assertEquals("Forwarding is off", AlertNotifier.foregroundText(null, null, mailAvailable = true))
        assertEquals("Forwarding is off", AlertNotifier.foregroundText(on.copy(enabled = false), 0, mailAvailable = false))
        assertEquals(
            "Forwarding waiting for Gmail connection",
            AlertNotifier.foregroundText(on, 0, mailAvailable = false, outlookConfigured = false),
        )
    }

    @Test fun `with Outlook set up and no account available it waits for a mail connection`() {
        assertEquals(
            "Forwarding waiting for a mail connection",
            AlertNotifier.foregroundText(on, 0, mailAvailable = false, outlookConfigured = true),
        )
        assertEquals("Forwarding active • 0 queued", AlertNotifier.foregroundText(on, 0, mailAvailable = true, outlookConfigured = true))
    }
}
