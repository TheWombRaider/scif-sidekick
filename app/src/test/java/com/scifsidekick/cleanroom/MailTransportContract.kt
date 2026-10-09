package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.email.CommandSearch
import com.scifsidekick.cleanroom.email.MailAuthRequiredException
import com.scifsidekick.cleanroom.email.MailTransport
import com.scifsidekick.cleanroom.messaging.EmailPayload
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The behavior every [MailTransport] must keep. Step 2 runs this same suite against the Microsoft
 * Graph transport's HTTP layer with canned responses.
 */
abstract class MailTransportContract {
    interface Harness {
        val transport: MailTransport

        fun signOut()

        fun deliver(
            id: String,
            subject: String,
            from: String,
            authenticatedFrom: String?,
        )
    }

    abstract fun newHarness(): Harness

    private val search = CommandSearch(listOf("[SCIF:ON]"), listOf("owner@example.com"))

    private fun payload() =
        EmailPayload(
            destinations = listOf("owner@example.com"),
            replyTarget = null,
            senderDisplay = "SCIF Sidekick",
            body = "hello",
            receivedAtMs = 1L,
            source = "test",
            participants = emptyList(),
            attachmentNotice = null,
            renderedSubject = "subject",
            renderedBody = "hello",
            filterName = "(test)",
        )

    @Test fun `a sender nobody vouches for is never authenticated`() =
        runBlocking {
            val h = newHarness()
            h.deliver("m1", "[SCIF:ON]", "owner@example.com", authenticatedFrom = null)
            val scan = h.transport.findCommands(search)
            assertEquals(1, scan.candidates.size)
            assertNull(scan.candidates.single().authenticatedFromAddress)
        }

    @Test fun `findCommands returns only unread mail and markRead is idempotent`() =
        runBlocking {
            val h = newHarness()
            h.deliver("m1", "[SCIF:ON]", "owner@example.com", "owner@example.com")
            assertEquals(1, h.transport.findCommands(search).candidates.size)
            h.transport.markRead("m1")
            h.transport.markRead("m1")
            assertTrue(h.transport.findCommands(search).candidates.isEmpty())
        }

    @Test fun `findCommands ignores mail from addresses outside the search`() =
        runBlocking {
            val h = newHarness()
            h.deliver("m1", "[SCIF:ON]", "stranger@example.com", null)
            assertTrue(h.transport.findCommands(search).candidates.isEmpty())
        }

    @Test fun `pollReplies skips known ids`() =
        runBlocking {
            val h = newHarness()
            h.deliver("m1", "Re: [SCIF:+15551234567]", "owner@example.com", "owner@example.com")
            h.deliver("m2", "Re: [SCIF:+15551234567]", "owner@example.com", "owner@example.com")
            val result = h.transport.pollReplies(setOf("m1"))
            assertEquals(listOf("m2"), result.replies.map { it.id })
        }

    @Test fun `send with verifyPriorDelivery does not duplicate`() =
        runBlocking {
            val h = newHarness()
            val first = h.transport.send(payload(), emptyList(), "key-1", verifyPriorDelivery = false)
            assertFalse(first.reconciled)
            val again = h.transport.send(payload(), emptyList(), "key-1", verifyPriorDelivery = true)
            assertTrue(again.reconciled)
            assertEquals(first.messageId, again.messageId)
        }

    @Test fun `a signed-out transport is inert and send fails loudly`() =
        runBlocking {
            val h = newHarness()
            h.deliver("m1", "[SCIF:ON]", "owner@example.com", "owner@example.com")
            h.signOut()
            assertFalse(h.transport.isAvailable)
            assertNull(h.transport.accountEmail())
            assertTrue(h.transport.pollReplies(emptySet()).replies.isEmpty())
            assertTrue(h.transport.findCommands(search).candidates.isEmpty())
            try {
                h.transport.send(payload(), emptyList(), "key-2", verifyPriorDelivery = false)
                throw AssertionError("send must throw MailAuthRequiredException when signed out")
            } catch (expected: MailAuthRequiredException) {
                // expected
            }
        }
}
