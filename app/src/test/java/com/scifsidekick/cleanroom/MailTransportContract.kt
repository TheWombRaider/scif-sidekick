package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.email.CommandSearch
import com.scifsidekick.cleanroom.email.MailAuthRequiredException
import com.scifsidekick.cleanroom.email.MailIds
import com.scifsidekick.cleanroom.email.MailTransport
import com.scifsidekick.cleanroom.messaging.EmailPayload
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

        /** Delivers a message and returns its provider-scoped id (see MailIds). */
        fun deliver(
            id: String,
            subject: String,
            from: String,
            authenticatedFrom: String?,
        ): String
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

    @Test fun `a sender the provider vouches for is authenticated`() =
        runBlocking {
            val h = newHarness()
            h.deliver("m1", "[SCIF:ON]", "owner@example.com", authenticatedFrom = "owner@example.com")
            val scan = h.transport.findCommands(search)
            assertEquals("owner@example.com", scan.candidates.single().authenticatedFromAddress)
        }

    @Test fun `ids the transport returns are scoped to its provider`() =
        runBlocking {
            val h = newHarness()
            val id = h.deliver("m1", "[SCIF:ON]", "owner@example.com", "owner@example.com")
            h.deliver("m2", "Re: [SCIF:+15551234567]", "owner@example.com", "owner@example.com")
            assertEquals(h.transport.providerId, MailIds.providerOf(id))
            val command = h.transport.findCommands(search).candidates.single()
            assertEquals(id, command.id)
            assertEquals(h.transport.providerId, MailIds.providerOf(command.id))
            val replies = h.transport.pollReplies(emptySet()).replies
            assertTrue(replies.isNotEmpty())
            replies.forEach { assertEquals(h.transport.providerId, MailIds.providerOf(it.id)) }
            val receipt = h.transport.send(payload(), emptyList(), "key-scope", verifyPriorDelivery = false)
            assertEquals(h.transport.providerId, MailIds.providerOf(receipt.messageId))
        }

    @Test fun `findSent is null before a send and the reconciled receipt after`() =
        runBlocking {
            val h = newHarness()
            assertNull(h.transport.findSent("key-find"))
            val sent = h.transport.send(payload(), emptyList(), "key-find", verifyPriorDelivery = false)
            val found = h.transport.findSent("key-find")
            assertNotNull(found)
            assertTrue(found!!.reconciled)
            assertEquals(sent.messageId, found.messageId)
            assertEquals(sent.rfcMessageId, found.rfcMessageId)
            assertNull(h.transport.findSent("some-other-key"))
        }

    @Test fun `findCommands returns only unread mail and markRead is idempotent`() =
        runBlocking {
            val h = newHarness()
            val id = h.deliver("m1", "[SCIF:ON]", "owner@example.com", "owner@example.com")
            assertEquals(1, h.transport.findCommands(search).candidates.size)
            h.transport.markRead(id)
            h.transport.markRead(id)
            assertTrue(h.transport.findCommands(search).candidates.isEmpty())
        }

    // Sender narrowing is best-effort: a provider may drop or loosen it (Gmail drops the from: filter
    // when any allowlisted address is not "safe", and its from: match is fuzzy). What keeps strangers
    // out is authenticatedFromAddress plus the allowlist downstream. This only pins the plain case.
    @Test fun `findCommands narrows by sender for a plain safe address`() =
        runBlocking {
            val h = newHarness()
            h.deliver("m1", "[SCIF:ON]", "stranger@example.com", null)
            assertTrue(h.transport.findCommands(search).candidates.isEmpty())
        }

    @Test fun `pollReplies skips known ids`() =
        runBlocking {
            val h = newHarness()
            val first = h.deliver("m1", "Re: [SCIF:+15551234567]", "owner@example.com", "owner@example.com")
            val second = h.deliver("m2", "Re: [SCIF:+15551234567]", "owner@example.com", "owner@example.com")
            val result = h.transport.pollReplies(setOf(first))
            assertEquals(listOf(second), result.replies.map { it.id })
        }

    // Replying inside an open thread can deliver an already-read self-addressed reply, so a poll
    // that filters on unread silently never finds it.
    @Test fun `pollReplies returns messages that are already read`() =
        runBlocking {
            val h = newHarness()
            val id = h.deliver("m1", "Re: [SCIF:+15551234567]", "owner@example.com", "owner@example.com")
            h.transport.markRead(id)
            assertTrue(id in h.transport.pollReplies(emptySet()).replies.map { it.id })
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
            h.transport.send(payload(), emptyList(), "key-before-signout", verifyPriorDelivery = false)
            h.signOut()
            assertFalse(h.transport.isAvailable)
            assertNull(h.transport.findSent("key-before-signout"))
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
