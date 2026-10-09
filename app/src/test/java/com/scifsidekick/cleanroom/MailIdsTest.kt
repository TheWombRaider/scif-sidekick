package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.email.MailIds
import org.junit.Assert.assertEquals
import org.junit.Test

class MailIdsTest {
    @Test fun `an unprefixed id is a gmail id`() {
        assertEquals("gmail", MailIds.providerOf("18f3a9c2b4d5e6f7"))
        assertEquals("18f3a9c2b4d5e6f7", MailIds.nativeId("18f3a9c2b4d5e6f7"))
    }

    @Test fun `a prefixed id names its provider and keeps the native id intact`() {
        val id = "graph:AAMkAGI2:with:colons="
        assertEquals("graph", MailIds.providerOf(id))
        assertEquals("AAMkAGI2:with:colons=", MailIds.nativeId(id))
    }

    @Test fun `scoped ids round trip, and gmail ids are never prefixed`() {
        assertEquals("abc123", MailIds.scoped("gmail", "abc123"))
        assertEquals("graph:abc123", MailIds.scoped("graph", "abc123"))
        assertEquals("graph", MailIds.providerOf(MailIds.scoped("graph", "abc123")))
        assertEquals("abc123", MailIds.nativeId(MailIds.scoped("graph", "abc123")))
    }

    @Test fun `an empty prefix is not a provider`() {
        assertEquals("gmail", MailIds.providerOf(":oddity"))
        assertEquals(":oddity", MailIds.nativeId(":oddity"))
    }
}
