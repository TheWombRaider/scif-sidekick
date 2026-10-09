package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.email.CommandSearch
import org.junit.Assert.assertThrows
import org.junit.Test

class CommandSearchTest {
    @Test fun `empty tags are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { CommandSearch(emptyList(), listOf("a@example.com")) }
    }

    @Test fun `empty senders are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { CommandSearch(listOf("[SCIF:ON]"), emptyList()) }
    }
}
