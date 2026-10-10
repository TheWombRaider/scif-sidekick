package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.email.BounceNotice
import com.scifsidekick.cleanroom.email.CommandSearch
import com.scifsidekick.cleanroom.email.MailAuthRequiredException
import com.scifsidekick.cleanroom.email.MailHttpException
import com.scifsidekick.cleanroom.email.MailRouter
import com.scifsidekick.cleanroom.email.MailTransport
import com.scifsidekick.cleanroom.messaging.EmailPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class MailRouterTest {
    private val gmail = FakeMailTransport("gmail", "Gmail")
    private val graph = FakeMailTransport("graph", "Outlook")
    private val authRequired = mutableListOf<MailAuthRequiredException>()
    private val recovered = mutableListOf<String>()
    private val duplicates = mutableListOf<String>()
    private val search = CommandSearch(listOf("[SCIF:ON]"), listOf("owner@example.com"))

    private fun router(vararg members: MailTransport) =
        MailRouter(
            members = { members.toList() },
            onAuthRequired = { authRequired += it },
            onRecovered = { recovered += it },
            logPossibleDuplicate = { duplicates += it },
        )

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

    private suspend fun MailTransport.send(
        key: String,
        verify: Boolean = false,
    ) = send(payload(), emptyList(), key, verify)

    private inline fun <reified T : Throwable> thrownBy(block: () -> Unit): T {
        try {
            block()
        } catch (failure: Throwable) {
            if (failure is T) return failure
            throw failure
        }
        fail("expected ${T::class.java.simpleName}")
        throw AssertionError()
    }

    // 1
    @Test fun `a single member is a pass-through`() =
        runBlocking {
            val twin = FakeMailTransport("gmail", "Gmail")
            val r = router(gmail)
            assertEquals("gmail", r.providerId)
            assertEquals("Gmail", r.displayName)
            for (t in listOf(gmail, twin)) {
                t.deliver("m1", "[SCIF:ON]", "owner@example.com", "owner@example.com")
                t.deliver("m2", "Re: TEXT", "owner@example.com")
            }

            assertEquals(twin.send("k1", verify = true), r.send("k1", verify = true))
            assertEquals(twin.send("k2"), r.send("k2"))
            assertEquals(twin.pollReplies(setOf("m2")), r.pollReplies(setOf("m2")))
            assertEquals(twin.findCommands(search), r.findCommands(search))
            twin.markRead("m1")
            r.markRead("m1")
            assertEquals(twin.findCommands(search), r.findCommands(search))
            assertEquals(twin.accountEmail(), r.accountEmail())
            assertEquals(twin.calls, gmail.calls)
            assertTrue(authRequired.isEmpty())
            assertTrue(duplicates.isEmpty())
        }

    // 2
    @Test fun `the preferred member is used first and the other only after a failure`() =
        runBlocking {
            val r = router(gmail, graph)
            r.send("k1")
            assertEquals(1, gmail.sendCount)
            assertEquals(0, graph.sendCount)

            gmail.failNextSendWith = MailHttpException(400, "bad request")
            val receipt = r.send("k2")
            assertEquals(2, gmail.sendCount)
            assertEquals(1, graph.sendCount)
            assertTrue(receipt.messageId.startsWith("graph:"))
        }

    // 3
    @Test fun `a signed-out preferred member is never called`() =
        runBlocking {
            gmail.signedIn = false
            val receipt = router(gmail, graph).send("k1")
            assertTrue(receipt.messageId.startsWith("graph:"))
            assertTrue(gmail.calls.isEmpty())
        }

    // 4
    @Test fun `an auth failure alerts once and falls back`() =
        runBlocking {
            val failure = MailAuthRequiredException("reconnect", "gmail", "Gmail")
            gmail.failNextSendWith = failure
            val receipt = router(gmail, graph).send("k1")
            assertTrue(receipt.messageId.startsWith("graph:"))
            assertSame(failure, authRequired.single())
            assertEquals(1, gmail.sendCount)
            assertEquals(1, graph.sendCount)
        }

    // 5
    @Test fun `a definite failure falls back without verifying`() =
        runBlocking {
            gmail.failNextSendWith = MailHttpException(400, "bad request")
            val receipt = router(gmail, graph).send("k1")
            assertTrue(receipt.messageId.startsWith("graph:"))
            assertEquals(listOf("send"), gmail.calls)
            assertEquals(1, graph.sendCount)
            assertEquals(emptyList<String>(), duplicates)
        }

    // 6
    @Test fun `an ambiguous failure that actually went out returns the reconciled receipt`() =
        runBlocking {
            val first = gmail.send("k1")
            gmail.failNextSendWith = SocketTimeoutException("timeout")
            val receipt = router(gmail, graph).send("k1")
            assertTrue(receipt.reconciled)
            assertEquals(first.messageId, receipt.messageId)
            assertEquals(0, graph.sendCount)
        }

    // 7
    @Test fun `an ambiguous failure that did not go out falls back`() =
        runBlocking {
            gmail.failNextSendWith = SocketTimeoutException("timeout")
            val receipt = router(gmail, graph).send("k1")
            assertTrue(receipt.messageId.startsWith("graph:"))
            assertFalse(receipt.reconciled)
            assertTrue("findSent" in gmail.calls)
            assertEquals(
                listOf(
                    "Gmail send failed ambiguously and was not found in Sent yet; trying the next account (possible duplicate if it was accepted)",
                ),
                duplicates,
            )
        }

    // 8
    @Test fun `an ambiguous failure that cannot be verified falls back and logs a possible duplicate`() =
        runBlocking {
            gmail.failNextSendWith = SocketTimeoutException("timeout")
            gmail.findSentFailure = IOException("offline")
            val receipt = router(gmail, graph).send("k1")
            assertTrue(receipt.messageId.startsWith("graph:"))
            assertEquals(
                listOf("Gmail send was ambiguous and could not be verified; trying the next account (possible duplicate)"),
                duplicates,
            )
        }

    // 9
    @Test fun `prior delivery through the fallback is found before any send`() =
        runBlocking {
            val first = graph.send("k1")
            val receipt = router(gmail, graph).send("k1", verify = true)
            assertTrue(receipt.reconciled)
            assertEquals(first.messageId, receipt.messageId)
            assertEquals(0, gmail.sendCount)
            assertEquals(1, graph.sendCount)
        }

    // 10
    @Test fun `when every member fails the first failure is rethrown`() =
        runBlocking {
            val first = MailHttpException(400, "bad request")
            gmail.failNextSendWith = first
            graph.failNextSendWith = MailHttpException(403, "forbidden")
            val thrown = thrownBy<MailHttpException> { runBlocking { router(gmail, graph).send("k1") } }
            assertSame(first, thrown)
        }

    // 11
    @Test fun `pollReplies concatenates, skips a failing member, and rethrows the first when all fail`() =
        runBlocking {
            val r = router(gmail, graph)
            gmail.deliver("a", "TEXT", "x@example.com")
            graph.deliver("b", "TEXT", "x@example.com")
            assertEquals(listOf("a", "graph:b"), r.pollReplies(emptySet()).replies.map { it.id })

            graph.pollFailure = IOException("graph down")
            assertEquals(listOf("a"), r.pollReplies(emptySet()).replies.map { it.id })

            val first = IOException("gmail down")
            gmail.pollFailure = first
            val thrown = thrownBy<IOException> { runBlocking { r.pollReplies(emptySet()) } }
            assertSame(first, thrown)
        }

    // 12
    @Test fun `findCommands interleaves preferred first and concatenates unreadable ids`() =
        runBlocking {
            listOf("g1", "g2", "g3").forEach { gmail.deliver(it, "[SCIF:ON]", "owner@example.com") }
            graph.deliver("o1", "[SCIF:ON]", "owner@example.com")
            gmail.unreadableCommands += "gx"
            graph.unreadableCommands += "graph:ox"
            val scan = router(gmail, graph).findCommands(search)
            assertEquals(listOf("g3", "graph:o1", "g2", "g1"), scan.candidates.map { it.id })
            assertEquals(listOf("gx", "graph:ox"), scan.unreadable)
        }

    // 13
    @Test fun `markRead and fetchContent route by provider prefix`() =
        runBlocking {
            val r = router(gmail, graph)
            val gmailId = gmail.deliver("g1", "TEXT", "x@example.com")
            val graphId = graph.deliver("o1", "TEXT", "x@example.com")
            r.markRead(graphId)
            assertEquals(listOf("markRead:graph:o1"), graph.calls)
            assertTrue(gmail.calls.isEmpty())

            val message = gmail.pollReplies(emptySet()).replies.single { it.id == gmailId }
            gmail.calls.clear()
            r.fetchContent(message)
            assertEquals(listOf("fetchContent:g1"), gmail.calls)
            assertEquals(listOf("markRead:graph:o1"), graph.calls)

            assertThrows(IllegalArgumentException::class.java) { runBlocking { r.markRead("imap:1") } }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { r.fetchContent(message.copy(id = "imap:1")) }
            }
            Unit
        }

    // 14
    @Test fun `availability and account email follow the available members`() =
        runBlocking {
            val r = router(gmail, graph)
            assertTrue(r.isAvailable)
            gmail.signedIn = false
            assertTrue(r.isAvailable)
            assertEquals("owner@fake.invalid", r.accountEmail())
            assertFalse("accountEmail" in gmail.calls)
            assertTrue("accountEmail" in graph.calls)
            graph.signedIn = false
            assertFalse(r.isAvailable)
            assertNull(r.accountEmail())
        }

    @Test fun `the primary display name is the first available member's`() {
        assertEquals("Gmail", router(gmail, graph).primaryDisplayName())
        assertEquals("Outlook", router(graph, gmail).primaryDisplayName())
        gmail.signedIn = false
        assertEquals("Outlook", router(gmail, graph).primaryDisplayName())
        graph.signedIn = false
        assertEquals("Gmail", router(gmail, graph).primaryDisplayName())
        assertEquals("Gmail", router(gmail).primaryDisplayName())
        assertEquals(MailRouter.ROUTER_NAME, router().primaryDisplayName())
    }

    // 15
    @Test fun `a successful send through the fallback reports it recovered`() =
        runBlocking {
            gmail.failNextSendWith = MailHttpException(400, "bad request")
            router(gmail, graph).send("k1")
            assertEquals(listOf("graph"), recovered)
        }

    @Test fun `a single member's failure is rethrown as is and left to the caller to alert`() =
        runBlocking {
            val r = router(gmail)
            val auth = MailAuthRequiredException("reconnect", "gmail", "Gmail")
            gmail.pollFailure = auth
            assertSame(auth, thrownBy<MailAuthRequiredException> { runBlocking { r.pollReplies(emptySet()) } })

            gmail.failNextSendWith = auth
            assertSame(auth, thrownBy<MailAuthRequiredException> { runBlocking { r.send("k1") } })

            val timeout = SocketTimeoutException("timeout")
            gmail.failNextSendWith = timeout
            assertSame(timeout, thrownBy<SocketTimeoutException> { runBlocking { r.send("k2") } })
            assertFalse("findSent" in gmail.calls)
            assertTrue(authRequired.isEmpty())
            assertTrue(duplicates.isEmpty())
        }

    @Test fun `with two members the absorbed auth failure alerts but the rethrown one does not`() =
        runBlocking {
            val first = IOException("gmail down")
            val auth = MailAuthRequiredException("reconnect", "graph", "Outlook")
            gmail.pollFailure = first
            graph.pollFailure = auth
            assertSame(first, thrownBy<IOException> { runBlocking { router(gmail, graph).pollReplies(emptySet()) } })
            assertEquals(listOf(auth), authRequired)

            authRequired.clear()
            gmail.pollFailure = auth
            graph.pollFailure = null
            router(gmail, graph).pollReplies(emptySet())
            assertEquals(listOf(auth), authRequired)
            assertEquals(listOf("graph"), recovered)
        }

    @Test fun `no usable member means send needs a connected account`() =
        runBlocking {
            gmail.signedIn = false
            graph.signedIn = false
            val both = thrownBy<MailAuthRequiredException> { runBlocking { router(gmail, graph).send("k1") } }
            assertEquals("router", both.providerId)
            val single = thrownBy<MailAuthRequiredException> { runBlocking { router(gmail).send("k1") } }
            assertEquals("gmail", single.providerId)
            assertEquals("Gmail", single.displayName)
            assertTrue(router(gmail, graph).pollReplies(emptySet()).replies.isEmpty())
            assertTrue(router(gmail, graph).findCommands(search).candidates.isEmpty())
            assertTrue(router(gmail, graph).checkForBounces().isEmpty())
            assertNull(router(gmail, graph).findSent("k1"))
        }

    @Test fun `cancellation is never swallowed`() =
        runBlocking {
            gmail.failNextSendWith = CancellationException("cancelled")
            thrownBy<CancellationException> { runBlocking { router(gmail, graph).send("k1") } }
            assertEquals(0, graph.sendCount)

            gmail.pollFailure = CancellationException("cancelled")
            thrownBy<CancellationException> { runBlocking { router(gmail, graph).pollReplies(emptySet()) } }
            assertFalse("pollReplies" in graph.calls)
        }

    @Test fun `clearSession reaches every member, signed in or not`() =
        runBlocking {
            gmail.signedIn = false
            router(gmail, graph).clearSession()
            assertEquals(listOf("clearSession"), gmail.calls)
            assertEquals(listOf("clearSession"), graph.calls)
        }

    @Test fun `findSent returns the first usable member's receipt`() =
        runBlocking {
            val fromGmail = gmail.send("k1")
            val fromGraph = graph.send("k1")
            val r = router(gmail, graph)
            assertEquals(fromGmail.copy(reconciled = true), r.findSent("k1"))
            gmail.signedIn = false
            assertEquals(fromGraph.copy(reconciled = true), r.findSent("k1"))
        }

    @Test fun `a 5xx that actually went out returns the reconciled receipt`() =
        runBlocking {
            val first = gmail.send("k1")
            gmail.failNextSendWith = MailHttpException(503, "unavailable")
            val receipt = router(gmail, graph).send("k1")
            assertTrue(receipt.reconciled)
            assertEquals(first.messageId, receipt.messageId)
            assertEquals(0, graph.sendCount)
        }

    @Test fun `a 5xx that did not go out is verified, then falls back`() =
        runBlocking {
            gmail.failNextSendWith = MailHttpException(503, "unavailable")
            val receipt = router(gmail, graph).send("k1")
            assertTrue(receipt.messageId.startsWith("graph:"))
            assertEquals(listOf("send", "findSent"), gmail.calls)
        }

    @Test fun `connection failures before any request are definite`() =
        runBlocking {
            for (failure in listOf(java.net.ConnectException("refused"), java.net.UnknownHostException("dns"))) {
                gmail.calls.clear()
                gmail.failNextSendWith = failure
                val receipt = router(gmail, graph).send("k-${failure.javaClass.simpleName}")
                assertTrue(receipt.messageId.startsWith("graph:"))
                assertEquals(listOf("send"), gmail.calls)
            }
            assertEquals(emptyList<String>(), duplicates)
        }

    @Test fun `an unknown failure is treated as ambiguous`() =
        runBlocking {
            gmail.failNextSendWith = IllegalStateException("odd")
            val receipt = router(gmail, graph).send("k1")
            assertTrue(receipt.messageId.startsWith("graph:"))
            assertEquals(listOf("send", "findSent"), gmail.calls)
        }

    @Test fun `a failed prior-delivery check does not stop the send and logs a possible duplicate`() =
        runBlocking {
            gmail.findSentFailure = IOException("offline")
            val receipt = router(gmail, graph).send("k1", verify = true)
            assertFalse(receipt.reconciled)
            assertEquals(1, gmail.sendCount)
            assertEquals(
                listOf("Gmail could not check its Sent folder before a retry; continuing (possible duplicate)"),
                duplicates,
            )
            assertTrue(authRequired.isEmpty())
        }

    @Test fun `a prior-delivery check needing reconnection alerts instead of logging a duplicate`() =
        runBlocking {
            val auth = MailAuthRequiredException("reconnect", "gmail", "Gmail")
            gmail.findSentFailure = auth
            val receipt = router(gmail, graph).send("k1", verify = true)
            assertFalse(receipt.reconciled)
            assertEquals(1, gmail.sendCount)
            assertEquals(listOf(auth), authRequired)
            assertEquals(emptyList<String>(), duplicates)
        }

    @Test fun `prior delivery through the preferred member is found without asking the fallback`() =
        runBlocking {
            val first = gmail.send("k1")
            val receipt = router(gmail, graph).send("k1", verify = true)
            assertTrue(receipt.reconciled)
            assertEquals(first.messageId, receipt.messageId)
            assertEquals(1, gmail.sendCount)
            assertTrue(graph.calls.isEmpty())
        }

    @Test fun `an ambiguous failure on the last member rethrows the first failure without a duplicate log`() =
        runBlocking {
            val first = MailHttpException(400, "bad request")
            gmail.failNextSendWith = first
            graph.failNextSendWith = SocketTimeoutException("timeout")
            assertSame(first, thrownBy<MailHttpException> { runBlocking { router(gmail, graph).send("k1") } })
            assertTrue("findSent" in graph.calls)

            gmail.failNextSendWith = first
            graph.failNextSendWith = SocketTimeoutException("timeout")
            graph.findSentFailure = IOException("offline")
            assertSame(first, thrownBy<MailHttpException> { runBlocking { router(gmail, graph).send("k2") } })
            assertEquals(emptyList<String>(), duplicates)
        }

    @Test fun `an auth failure ahead of an ambiguous one rethrows the ambiguous one and alerts once`() =
        runBlocking {
            gmail.failNextSendWith = MailAuthRequiredException("reconnect", "gmail", "Gmail")
            val timeout = SocketTimeoutException("timeout")
            graph.failNextSendWith = timeout
            assertSame(timeout, thrownBy<SocketTimeoutException> { runBlocking { router(gmail, graph).send("k1") } })
            assertEquals(listOf("gmail"), authRequired.map { it.providerId })
        }

    @Test fun `an auth failure ahead of a definite one rethrows the definite one`() =
        runBlocking {
            gmail.failNextSendWith = MailAuthRequiredException("reconnect", "gmail", "Gmail")
            val bad = MailHttpException(400, "bad request")
            graph.failNextSendWith = bad
            assertSame(bad, thrownBy<MailHttpException> { runBlocking { router(gmail, graph).send("k1") } })
            assertEquals(listOf("gmail"), authRequired.map { it.providerId })
        }

    @Test fun `a lone signed-out member answers send itself`() =
        runBlocking {
            gmail.signedIn = false
            val thrown = thrownBy<MailAuthRequiredException> { runBlocking { router(gmail).send("k1") } }
            assertEquals("Open the app and reconnect Gmail", thrown.message)
            assertEquals(listOf("send"), gmail.calls)
        }

    @Test fun `a failing callback never turns a delivered send into a failure`() =
        runBlocking {
            val r =
                MailRouter(
                    members = { listOf(gmail, graph) },
                    onAuthRequired = { error("alert failed") },
                    onRecovered = { error("clear failed") },
                    logPossibleDuplicate = { error("log failed") },
                )
            assertFalse(r.send("k1").reconciled)
            gmail.failNextSendWith = MailAuthRequiredException("reconnect", "gmail", "Gmail")
            assertTrue(r.send("k2").messageId.startsWith("graph:"))
            gmail.failNextSendWith = SocketTimeoutException("timeout")
            assertTrue(r.send("k3").messageId.startsWith("graph:"))
            val single = MailRouter(members = { listOf(gmail) }, onRecovered = { error("clear failed") })
            assertFalse(single.send("k4").reconciled)
        }

    @Test fun `checkForBounces concatenates, skips a failing member, and rethrows the first when all fail`() =
        runBlocking {
            val r = router(gmail, graph)
            val a = BounceNotice("a", setOf("rfc-a"), "bounced a")
            val b = BounceNotice("graph:b", setOf("rfc-b"), "bounced b")
            gmail.bounces += a
            graph.bounces += b
            assertEquals(listOf(a, b), r.checkForBounces())

            graph.bouncesFailure = IOException("graph down")
            assertEquals(listOf(a), r.checkForBounces())

            val first = IOException("gmail down")
            gmail.bouncesFailure = first
            assertSame(first, thrownBy<IOException> { runBlocking { r.checkForBounces() } })
        }

    @Test fun `findSent skips a failing member and finds the message through the next`() =
        runBlocking {
            val fromGraph = graph.send("k1")
            gmail.findSentFailure = IOException("gmail down")
            assertEquals(fromGraph.copy(reconciled = true), router(gmail, graph).findSent("k1"))
            assertTrue("findSent" in graph.calls)
            assertTrue(authRequired.isEmpty())
        }

    @Test fun `findSent returns null when every member fails`() =
        runBlocking {
            graph.send("k1")
            gmail.findSentFailure = IOException("gmail down")
            graph.findSentFailure = MailHttpException(503, "graph down")
            assertNull(router(gmail, graph).findSent("k1"))
        }

    @Test fun `findSent alerts for an auth failure it skips`() =
        runBlocking {
            val fromGraph = graph.send("k1")
            val auth = MailAuthRequiredException("reconnect", "gmail", "Gmail")
            gmail.findSentFailure = auth
            assertEquals(fromGraph.copy(reconciled = true), router(gmail, graph).findSent("k1"))
            assertEquals(listOf(auth), authRequired)
        }

    @Test fun `findSent never swallows cancellation, and a single member still rethrows its own failure`() =
        runBlocking {
            graph.send("k1")
            gmail.findSentFailure = CancellationException("cancelled")
            thrownBy<CancellationException> { runBlocking { router(gmail, graph).findSent("k1") } }
            assertFalse("findSent" in graph.calls)

            val own = IOException("gmail down")
            gmail.findSentFailure = own
            assertSame(own, thrownBy<IOException> { runBlocking { router(gmail).findSent("k1") } })
        }

    @Test fun `cancellation inside a guarded callback is never swallowed`() =
        runBlocking {
            fun cancelling(
                recovered: Boolean = false,
                auth: Boolean = false,
                duplicate: Boolean = false,
            ) = MailRouter(
                members = { listOf(gmail, graph) },
                onAuthRequired = { if (auth) throw CancellationException("cancelled") },
                onRecovered = { if (recovered) throw CancellationException("cancelled") },
                logPossibleDuplicate = { if (duplicate) throw CancellationException("cancelled") },
            )
            thrownBy<CancellationException> { runBlocking { cancelling(recovered = true).send("k1") } }
            thrownBy<CancellationException> { runBlocking { cancelling(recovered = true).pollReplies(emptySet()) } }

            gmail.failNextSendWith = MailAuthRequiredException("reconnect", "gmail", "Gmail")
            thrownBy<CancellationException> { runBlocking { cancelling(auth = true).send("k2") } }

            gmail.failNextSendWith = SocketTimeoutException("timeout")
            thrownBy<CancellationException> { runBlocking { cancelling(duplicate = true).send("k3") } }

            gmail.findSentFailure = MailAuthRequiredException("reconnect", "gmail", "Gmail")
            thrownBy<CancellationException> { runBlocking { cancelling(auth = true).findSent("k4") } }
            Unit
        }
}
