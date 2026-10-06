package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.service.SelfTestReceipt
import com.scifsidekick.cleanroom.ui.CommandHelp
import com.scifsidekick.cleanroom.util.RemoteCommand
import com.scifsidekick.cleanroom.util.RemoteCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandHelpTest {
    private fun entry(title: String) = CommandHelp.entries.single { it.title == title }

    @Test fun `documented tag subjects parse to the command they are documented as`() {
        assertEquals(RemoteCommand.ENABLE, RemoteCommands.parse(entry("Forwarding on").subject))
        assertEquals(RemoteCommand.DISABLE, RemoteCommands.parse(entry("Forwarding off").subject))
        assertEquals(RemoteCommand.STATUS, RemoteCommands.parse(entry("Status").subject))
    }

    @Test fun `the compose example is not mistaken for a tag command`() {
        assertNull(RemoteCommands.parse(CommandHelp.COMPOSE_EXAMPLE))
        assertTrue(CommandHelp.COMPOSE_EXAMPLE.startsWith("TEXT+"))
    }

    @Test fun `every command names a permission that exists in the settings screen`() {
        val known = setOf("Compose", "Enable", "Disable", "Status")
        assertEquals(known, CommandHelp.entries.map { it.permission }.toSet())
    }

    @Test fun `status line covers off, empty, one and many`() {
        assertTrue(CommandHelp.statusLine(false, 3).contains("off"))
        assertTrue(CommandHelp.statusLine(true, 0).contains("no address"))
        assertTrue(CommandHelp.statusLine(true, 1).contains("1 address is"))
        assertTrue(CommandHelp.statusLine(true, 2).contains("2 addresses"))
    }

    @Test fun `self-test receipt body carries version and status but never a command tag`() {
        val body = SelfTestReceipt.body("9.9.9", "Forwarding: ON")
        assertTrue(body.contains("9.9.9"))
        assertTrue(body.contains("Forwarding: ON"))
        // The receipt text mentions the tags in prose; the subject must not be parseable as one,
        // or replying to it could be read as a command.
        assertNull(RemoteCommands.parse(SelfTestReceipt.SUBJECT))
    }
}
