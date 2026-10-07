package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.service.RemoteHelpManual
import com.scifsidekick.cleanroom.service.SelfTestReceipt
import com.scifsidekick.cleanroom.ui.Changelog
import com.scifsidekick.cleanroom.ui.CommandHelp
import com.scifsidekick.cleanroom.util.RemoteCommand
import com.scifsidekick.cleanroom.util.RemoteCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandHelpTest {
    private fun entry(title: String) = CommandHelp.entries.single { it.title == title }

    @Test fun `documented tag subjects parse to the command they are documented as`() {
        assertEquals(RemoteCommand.ENABLE, RemoteCommands.parse(entry("Forwarding on").subject))
        assertEquals(RemoteCommand.DISABLE, RemoteCommands.parse(entry("Forwarding off").subject))
        assertEquals(RemoteCommand.STATUS, RemoteCommands.parse(entry("Status").subject))
        assertEquals(RemoteCommand.HELP, RemoteCommands.parse(entry("Help").subject))
    }

    @Test fun `the compose example is not mistaken for a tag command`() {
        assertNull(RemoteCommands.parse(CommandHelp.COMPOSE_EXAMPLE))
        assertTrue(CommandHelp.COMPOSE_EXAMPLE.startsWith("TEXT+"))
    }

    @Test fun `every command names a permission that exists in the settings screen`() {
        val known = setOf("Compose", "Enable", "Disable", "Status")
        assertEquals(known, CommandHelp.entries.mapNotNull { it.permission }.toSet())
    }

    @Test fun `help is the only command open to any listed address`() {
        assertEquals(listOf("Help"), CommandHelp.entries.filter { it.permission == null }.map { it.title })
    }

    @Test fun `manual names every command and its subject`() {
        val manual = RemoteHelpManual.body("9.9.9")
        assertTrue(manual.contains("9.9.9"))
        CommandHelp.entries.forEach { assertTrue(it.subject, manual.contains(it.subject)) }
        assertTrue(manual.contains(CommandHelp.REPLY_TAG_EXAMPLE))
        listOf("NAME", "SYNOPSIS", "DESCRIPTION", "COMMANDS", "NOTES", "DIAGNOSTICS", "LICENSE").forEach {
            assertTrue(it, manual.contains("\n$it\n") || manual.startsWith("$it\n"))
        }
    }

    @Test fun `manual lines fit a narrow mail view`() {
        RemoteHelpManual.body("1.2.3").lines().forEach { assertTrue(it, it.length <= 80) }
    }

    @Test fun `manual subject is not itself a command`() {
        assertNull(RemoteCommands.parse(RemoteHelpManual.SUBJECT))
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

    @Test fun `changelog newest entry is the running version with a date`() {
        val newest = Changelog.entries.first()
        assertEquals(BuildConfig.VERSION_NAME, newest.version)
        assertTrue(newest.date.orEmpty().matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
    }

    @Test fun `every changelog entry is a non-empty bullet list with no duplicate versions`() {
        Changelog.entries.forEach { entry ->
            assertTrue(entry.version, entry.changes.isNotEmpty())
            entry.changes.forEach { assertFalse(entry.version, it.isBlank()) }
        }
        assertEquals(Changelog.entries.size, Changelog.entries.map { it.version }.toSet().size)
    }

    @Test fun `license notice names 0BSD`() {
        assertTrue(Changelog.LICENSE_NOTICE.contains("0BSD"))
    }
}
