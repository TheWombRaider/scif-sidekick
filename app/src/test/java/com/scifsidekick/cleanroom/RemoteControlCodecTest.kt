package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.util.RemoteControlCodec
import com.scifsidekick.cleanroom.util.RemoteControlCodec.Sender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteControlCodecTest {
    @Test fun `round trips every capability bit through json`() {
        val senders = listOf(Sender("a@example.com", canCompose = true, canEnable = false, canDisable = true, canStatus = false))
        val decoded = RemoteControlCodec.fromJson(RemoteControlCodec.toJson(senders))
        assertEquals(senders, decoded)
    }

    @Test fun `malformed json fails safe to an empty list`() {
        assertTrue(RemoteControlCodec.fromJson("not json").isEmpty())
        assertTrue(RemoteControlCodec.fromJson("").isEmpty())
        assertTrue(RemoteControlCodec.fromJson("[{}]").isEmpty())
    }

    @Test fun `a missing permission field defaults to false never true`() {
        val decoded = RemoteControlCodec.fromJson("""[{"address":"a@example.com"}]""")
        val sender = decoded.single()
        assertFalse(sender.canCompose)
        assertFalse(sender.canEnable)
        assertFalse(sender.canDisable)
        assertFalse(sender.canStatus)
    }

    @Test fun `isAuthorized checks the specific capability not just list membership`() {
        val json = RemoteControlCodec.toJson(listOf(Sender("a@example.com", canCompose = true, canEnable = false, canDisable = false, canStatus = false)))
        assertTrue(RemoteControlCodec.isAuthorized(json, "a@example.com", Sender::canCompose))
        assertFalse(RemoteControlCodec.isAuthorized(json, "a@example.com", Sender::canEnable))
        assertFalse(RemoteControlCodec.isAuthorized(json, "a@example.com", Sender::canDisable))
        assertFalse(RemoteControlCodec.isAuthorized(json, "a@example.com", Sender::canStatus))
    }

    @Test fun `isAuthorized canonicalizes both sides of the address comparison`() {
        val json = RemoteControlCodec.toJson(listOf(Sender("Owner@Example.com")))
        assertTrue(RemoteControlCodec.isAuthorized(json, " owner@example.com ", Sender::canCompose))
        assertFalse(RemoteControlCodec.isAuthorized(json, "stranger@example.com", Sender::canCompose))
        assertFalse(RemoteControlCodec.isAuthorized(json, null, Sender::canCompose))
    }

    @Test fun `an empty list authorizes nobody regardless of the master switch`() {
        assertFalse(RemoteControlCodec.isAuthorized("[]", "anyone@example.com", Sender::canCompose))
    }
}
