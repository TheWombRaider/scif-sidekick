package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.service.RemoteCommandReceipt
import com.scifsidekick.cleanroom.util.RemoteCommand
import com.scifsidekick.cleanroom.util.RemoteCommandPlanner
import com.scifsidekick.cleanroom.util.RemoteCommandPlanner.Action
import com.scifsidekick.cleanroom.util.RemoteCommandPlanner.Candidate
import com.scifsidekick.cleanroom.util.RemoteCommandQuery
import com.scifsidekick.cleanroom.util.RemoteControlCodec
import com.scifsidekick.cleanroom.util.RemoteControlCodec.Sender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCommandPlannerTest {
    private val owner = "owner@example.com"
    private val senders = RemoteControlCodec.toJson(listOf(Sender(owner)))
    private val tags = listOf("[SCIF:ON]", "[SCIF:STATUS]")

    private fun candidate(
        id: String,
        subject: String,
        from: String? = owner,
    ) = Candidate(id, subject, from)

    @Test
    fun realEnableIsFoundBehindJunk() {
        val junk = (1..19).map { candidate("junk$it", "[SCIF:ON]", "stranger$it@example.org") }
        val steps = RemoteCommandPlanner.plan(junk + candidate("real", "[SCIF:ON]"), senders)
        assertEquals(20, steps.size)
        assertTrue(steps.take(19).all { it.action == Action.REJECT })
        assertEquals(Action.APPLY_ENABLE, steps.last().action)
        assertEquals("real", steps.last().candidate.id)
    }

    @Test
    fun forgedOrUnauthenticatedEnableIsRejectedNotApplied() {
        val steps = RemoteCommandPlanner.plan(listOf(candidate("a", "[SCIF:ON]", from = null)), senders)
        assertEquals(listOf(Action.REJECT), steps.map { it.action })
    }

    @Test
    fun enableNeedsTheEnablePermissionSpecifically() {
        val statusOnly = RemoteControlCodec.toJson(listOf(Sender(owner, canCompose = false, canEnable = false, canDisable = false)))
        val steps = RemoteCommandPlanner.plan(listOf(candidate("a", "[SCIF:ON]")), statusOnly)
        assertEquals(listOf(Action.REJECT), steps.map { it.action })
    }

    @Test
    fun ambiguousAndNonCommandSubjectsAreConsumed() {
        val steps =
            RemoteCommandPlanner.plan(
                listOf(candidate("a", "[SCIF:ON] [SCIF:OFF]"), candidate("b", "re: scif on")),
                senders,
            )
        assertEquals(listOf(Action.CONSUME, Action.CONSUME), steps.map { it.action })
    }

    @Test
    fun nothingAfterAnAppliedEnableIsExamined() {
        val steps = RemoteCommandPlanner.plan(listOf(candidate("a", "[SCIF:ON]"), candidate("b", "[SCIF:ON]", "x@example.org")), senders)
        assertEquals(listOf(Action.APPLY_ENABLE), steps.map { it.action })
    }

    @Test
    fun statusIsHandedToTheResponderForEveryCandidate() {
        val steps = RemoteCommandPlanner.plan(listOf(candidate("a", "[SCIF:STATUS]", "x@example.org")), senders)
        assertEquals(listOf(Action.ANSWER_STATUS), steps.map { it.action })
    }

    @Test
    fun onlyTheFirstTwentyCandidatesAreExamined() {
        val many = (1..30).map { candidate("m$it", "[SCIF:ON]", "x$it@example.org") }
        assertEquals(RemoteCommandPlanner.MAX_CANDIDATES, RemoteCommandPlanner.plan(many, senders).size)
    }

    @Test
    fun queryRestrictsToAllowlistedSenders() {
        val query = RemoteCommandQuery.build(tags, RemoteControlCodec.toJson(listOf(Sender(owner), Sender("Boss@Agency.gov"))))!!
        assertEquals(
            "in:inbox is:unread newer_than:2d {subject:\"[SCIF:ON]\" subject:\"[SCIF:STATUS]\"} {from:owner@example.com from:boss@agency.gov}",
            query,
        )
    }

    @Test
    fun queryHasNoSenderFilterIfAnAddressIsNotSafelyExpressible() {
        val query = RemoteCommandQuery.build(tags, RemoteControlCodec.toJson(listOf(Sender(owner), Sender("o'neil@example.com"))))!!
        assertFalse(query.contains("from:"))
        assertTrue(query.startsWith("in:inbox is:unread newer_than:2d "))
    }

    @Test
    fun noAllowlistMeansNoQuery() {
        assertNull(RemoteCommandQuery.build(tags, "[]"))
        assertNull(RemoteCommandQuery.build(emptyList(), senders))
        assertNull(RemoteCommandQuery.build(tags, "not json"))
    }

    @Test
    fun receiptBodyIsUnchangedWhenTheServiceStarted() {
        val body = RemoteCommandReceipt.body(RemoteCommand.ENABLE, "1.2.3", "Forwarding: ON", RemoteCommandReceipt.ServiceStart.STARTED)
        assertEquals(
            "Your [SCIF:ON] command was received and applied by SCIF Sidekick 1.2.3. Forwarding is now ENABLED.\n\n" +
                "Current state:\nForwarding: ON",
            body,
        )
    }

    @Test
    fun receiptBodyWarnsWhenTheServiceCouldNotStart() {
        val body = RemoteCommandReceipt.body(RemoteCommand.ENABLE, "1.2.3", "Forwarding: ON", RemoteCommandReceipt.ServiceStart.FAILED)
        assertTrue(body.contains("Warning: Android would not start the forwarding service"))
        assertTrue(body.contains("Current state:\nForwarding: ON"))
    }

    @Test
    fun disableReceiptNamesTheOffTag() {
        val body = RemoteCommandReceipt.body(RemoteCommand.DISABLE, "1.2.3", "Forwarding: OFF")
        assertTrue(body.startsWith("Your [SCIF:OFF] command"))
        assertTrue(body.contains("Forwarding is now DISABLED."))
    }
}
