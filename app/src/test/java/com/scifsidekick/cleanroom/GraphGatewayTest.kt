package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.email.CommandSearch
import com.scifsidekick.cleanroom.email.MailAuthRequiredException
import com.scifsidekick.cleanroom.email.MailMessage
import com.scifsidekick.cleanroom.email.MimeMessageBuilder
import com.scifsidekick.cleanroom.email.graph.GraphApiException
import com.scifsidekick.cleanroom.email.graph.GraphGateway
import com.scifsidekick.cleanroom.email.graph.GraphResponseTooLargeException
import com.scifsidekick.cleanroom.email.graph.MsOAuthManager
import com.scifsidekick.cleanroom.messaging.EmailPayload
import com.scifsidekick.cleanroom.util.RemoteCommandPlanner
import com.scifsidekick.cleanroom.util.base64UrlDecodeBytes
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.After
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.Base64

class GraphGatewayTest {
    private val server = FakeGraphServer()
    private val store = InMemoryRefreshTokenStore("refresh-1")
    private val oauth = MsOAuthManager(clientId = { "client-id" }, store = store, client = server.client(), nowMs = { server.nowMs })
    private val logs = mutableListOf<String>()
    private val sleeps = mutableListOf<Long>()
    private val gateway = gatewayOver(oauth)

    private fun gatewayOver(manager: MsOAuthManager) =
        GraphGateway(manager, server.client(), nowMs = { server.nowMs }, log = { logs += it }, sleep = { sleeps += it })

    private val search = CommandSearch(listOf("[SCIF:ON]", "[SCIF:OFF]"), listOf("owner@example.com", "boss@agency.gov"))

    private val tempFiles = mutableListOf<File>()

    @After fun deleteTempFiles() {
        tempFiles.forEach { it.delete() }
    }

    private fun attachmentFile(bytes: Int): String =
        File.createTempFile("graph", ".jpg").also {
            it.writeBytes(ByteArray(bytes) { i -> (i % 251).toByte() })
            tempFiles += it
        }.path

    private fun createRequest() = server.graphRequests.single { it.method == "POST" && it.path == "/v1.0/me/messages" }

    private fun draftText() = String(server.messagesIn(FakeGraphServer.SENT).single().mime!!, Charsets.UTF_8)

    private val day = 24L * 3_600_000

    private fun pass(domain: String) =
        "spf=pass smtp.mailfrom=$domain; dkim=pass header.d=$domain; dmarc=pass action=none header.from=$domain; compauth=pass reason=100"

    private fun deliver(
        subject: String,
        from: String = "owner@example.com",
        isRead: Boolean = false,
        authenticated: Boolean = true,
        extraHeaders: List<Pair<String, String>> = emptyList(),
        body: String = "hello",
        htmlOnly: Boolean = false,
    ): String {
        server.nowMs += 1_000
        val domain = from.substringAfter('@').trimEnd('>')
        val headers = (if (authenticated) listOf("Authentication-Results" to pass(domain)) else emptyList()) + extraHeaders
        return server.deliver(from, subject, body, headers, isRead = isRead, htmlOnly = htmlOnly)
    }

    private fun payload(subject: String = "[SCIF:+15551234567] hello") =
        EmailPayload(
            destinations = listOf("owner@example.com"),
            replyTarget = "+15551234567",
            senderDisplay = "SCIF Sidekick",
            body = "hello",
            receivedAtMs = 1_700_000_000_000L,
            source = "sms",
            participants = emptyList(),
            attachmentNotice = null,
            renderedSubject = subject,
            renderedBody = "hello",
            filterName = "(test)",
        )

    private fun inboxLists() = server.graphRequests.filter { it.path == "/v1.0/me/mailFolders/inbox/messages" }

    private fun filterOf(seen: FakeGraphServer.Seen) = seen.url.queryParameter("\$filter")

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("expected ${T::class.java.simpleName}, got $e", e)
        }
        fail("expected ${T::class.java.simpleName}")
        throw AssertionError()
    }

    // ---- send ----

    @Test fun `send creates a MIME draft then sends it`() =
        runBlocking<Unit> {
            val receipt = gateway.send(payload(), emptyList(), "key-1", verifyPriorDelivery = false)

            val graph = server.graphRequests
            val sentId = server.messagesIn(FakeGraphServer.SENT).single().id
            assertEquals(
                listOf("GET /v1.0/me", "POST /v1.0/me/messages", "POST /v1.0/me/messages/$sentId/send"),
                graph.map { "${it.method} ${it.path}" },
            )
            assertEquals("mail,userPrincipalName", graph[0].url.queryParameter("\$select"))
            val create = graph[1]
            assertEquals("text/plain", create.contentType)
            val built = MimeMessageBuilder.build(payload(), emptyList(), "key-1", "me@outlook.com")
            val expectedBytes = built.rawBase64Url.base64UrlDecodeBytes()
            assertEquals(Base64.getEncoder().encodeToString(expectedBytes), create.body)
            assertArrayEquals(expectedBytes, server.messagesIn(FakeGraphServer.SENT).single().mime)
            assertEquals("", graph[2].body)

            val sent = server.messagesIn(FakeGraphServer.SENT).single()
            assertEquals("graph:${sent.id}", receipt.messageId)
            assertEquals("graph:${sent.conversationId}", receipt.threadId)
            assertEquals(MimeMessageBuilder.rfcMessageId("key-1"), receipt.rfcMessageId)
            assertFalse(receipt.reconciled)
            assertTrue(server.messagesIn(FakeGraphServer.DRAFTS).isEmpty())
            assertTrue(logs.isEmpty())
        }

    @Test fun `every Graph request asks for immutable ids so a sent draft keeps its id`() =
        runBlocking<Unit> {
            gateway.send(payload(), emptyList(), "key-1", verifyPriorDelivery = false)
            deliver("Re: [SCIF:+1555]")
            gateway.pollReplies(emptySet())
            server.graphRequests.forEach { seen ->
                assertTrue("${seen.method} ${seen.path}", seen.headers.values("Prefer").any { "IdType=\"ImmutableId\"" in it })
            }
        }

    @Test fun `the receipt carries the Message-ID Outlook stamped and the rewrite is logged once`() =
        runBlocking<Unit> {
            server.rewriteMessageId = true
            val first = gateway.send(payload(), emptyList(), "key-1", verifyPriorDelivery = false)
            val second = gateway.send(payload(), emptyList(), "key-2", verifyPriorDelivery = false)

            val stamped = server.messagesIn(FakeGraphServer.SENT).map { it.internetMessageId.trim('<', '>') }
            assertEquals(stamped, listOf(first.rfcMessageId, second.rfcMessageId))
            assertTrue(first.rfcMessageId != MimeMessageBuilder.rfcMessageId("key-1"))
            assertEquals(1, logs.size)
            assertTrue(logs.single().contains("Message-ID"))
        }

    @Test fun `findSent queries Sent Items by the bracketed Message-ID`() =
        runBlocking<Unit> {
            assertNull(gateway.findSent("key-1"))
            val sent = gateway.send(payload(), emptyList(), "key-1", verifyPriorDelivery = false)
            val found = gateway.findSent("key-1")!!
            assertTrue(found.reconciled)
            assertEquals(sent.messageId, found.messageId)
            assertEquals(sent.threadId, found.threadId)
            val query = server.graphRequests.last { it.path == "/v1.0/me/mailFolders/sentitems/messages" }
            assertEquals("internetMessageId eq '<${MimeMessageBuilder.rfcMessageId("key-1")}>'", filterOf(query))
            assertEquals("id,conversationId,internetMessageId", query.url.queryParameter("\$select"))
        }

    @Test fun `findSent in the same process also finds a send whose Message-ID Outlook replaced`() =
        runBlocking<Unit> {
            server.rewriteMessageId = true
            val sent = gateway.send(payload(), emptyList(), "key-1", verifyPriorDelivery = false)
            val found = gateway.findSent("key-1")
            assertNotNull(found)
            assertEquals(sent.messageId, found!!.messageId)
            assertEquals(sent.rfcMessageId, found.rfcMessageId)
        }

    @Test fun `verifyPriorDelivery returns the earlier send instead of sending again`() =
        runBlocking<Unit> {
            val first = gateway.send(payload(), emptyList(), "key-1", verifyPriorDelivery = false)
            val again = gateway.send(payload(), emptyList(), "key-1", verifyPriorDelivery = true)
            assertTrue(again.reconciled)
            assertEquals(first.messageId, again.messageId)
            assertEquals(1, server.messagesIn(FakeGraphServer.SENT).size)
        }

    @Test fun `a definite failure of the send step deletes the draft and rethrows`() =
        runBlocking<Unit> {
            server.failNext(400, matching = { it.path.endsWith("/send") })
            val e = assertThrows<GraphApiException> { runBlocking { gateway.send(payload(), emptyList(), "key-1", false) } }
            assertEquals(400, e.statusCode)
            assertTrue(server.messagesIn(FakeGraphServer.DRAFTS).isEmpty())
            assertTrue(server.messagesIn(FakeGraphServer.SENT).isEmpty())
            val last = server.graphRequests.takeLast(2)
            assertEquals("GET", last[0].method)
            assertEquals("isDraft", last[0].url.queryParameter("\$select"))
            assertEquals("DELETE", last[1].method)
        }

    @Test fun `a send that went out but answered 404 on a silent retry keeps the Sent copy`() =
        runBlocking<Unit> {
            // The first /send reaches the mailbox; the retry hits an item that is no longer a draft.
            server.handleTwiceNext { it.path.endsWith("/send") }
            val e = assertThrows<GraphApiException> { runBlocking { gateway.send(payload(), emptyList(), "key-1", false) } }
            assertEquals(404, e.statusCode)
            assertEquals(1, server.messagesIn(FakeGraphServer.SENT).size)
            assertTrue(server.graphRequests.none { it.method == "DELETE" })
            assertNotNull(gateway.findSent("key-1"))
        }

    @Test fun `a draft whose state cannot be checked is not deleted`() =
        runBlocking<Unit> {
            server.failNext(400, matching = { it.path.endsWith("/send") })
            server.failNext(500, matching = { it.method == "GET" && it.url.queryParameter("\$select") == "isDraft" })
            assertThrows<GraphApiException> { runBlocking { gateway.send(payload(), emptyList(), "key-1", false) } }
            assertEquals(1, server.messagesIn(FakeGraphServer.DRAFTS).size)
            assertTrue(server.graphRequests.none { it.method == "DELETE" })
        }

    @Test fun `a draft whose isDraft state is not reported is not deleted`() =
        runBlocking<Unit> {
            server.failNext(400, matching = { it.path.endsWith("/send") })
            server.failNext(200, body = """{"id":"x"}""", matching = { it.method == "GET" && it.url.queryParameter("\$select") == "isDraft" })
            assertThrows<GraphApiException> { runBlocking { gateway.send(payload(), emptyList(), "key-1", false) } }
            assertEquals(1, server.messagesIn(FakeGraphServer.DRAFTS).size)
            assertTrue(server.graphRequests.none { it.method == "DELETE" })
        }

    // ---- send size budget ----

    @Test fun `a 2 MB attachment is just over the Graph budget and is left out`() =
        runBlocking<Unit> {
            gateway.send(payload(), listOf(attachmentFile(2_000_000)), "key-1", false)
            assertFalse(draftText().contains("Content-Disposition: attachment"))
            assertTrue(createRequest().body!!.length < 3_500_000)
        }

    @Test fun `a 1 MB attachment is attached`() =
        runBlocking<Unit> {
            gateway.send(payload(), listOf(attachmentFile(1_000_000)), "key-1", false)
            assertTrue(draftText().contains("Content-Disposition: attachment"))
            assertTrue(createRequest().body!!.length < 3_500_000)
        }

    @Test fun `attachments at the Graph budget still fit under 3_5 MB`() =
        runBlocking<Unit> {
            gateway.send(payload(), listOf(attachmentFile(1_912_568)), "key-1", false)
            assertTrue(draftText().contains("Content-Disposition: attachment"))
            val length = createRequest().body!!.length
            assertTrue(length.toString(), length < 3_500_000)
        }

    @Test fun `a 3 MB attachment is left out with the disclosure and the request stays under 3_5 MB`() =
        runBlocking<Unit> {
            gateway.send(payload(), listOf(attachmentFile(3_000_000)), "key-1", false)
            val mime = draftText()
            assertFalse(mime.contains("Content-Disposition: attachment"))
            val body = String(Base64.getMimeDecoder().decode(mime.substringAfterLast("\r\n\r\n").trim()), Charsets.UTF_8)
            assertTrue(body.contains("Attachment not forwarded: 3000000 bytes"))
            assertTrue(createRequest().body!!.length < 3_500_000)
        }

    @Test fun `the omission disclosure names Outlook, not Gmail`() =
        runBlocking<Unit> {
            gateway.send(payload(), listOf(attachmentFile(3_000_000)), "key-1", false)
            val mime = draftText()
            val body = String(Base64.getMimeDecoder().decode(mime.substringAfterLast("\r\n\r\n").trim()), Charsets.UTF_8)
            assertTrue(body, body.contains("exceeds the safe Outlook message-size budget."))
            assertFalse(body, body.contains("Gmail"))
        }

    @Test fun `a message still too large is refused with 413 before anything is sent`() =
        runBlocking<Unit> {
            val huge = payload().copy(renderedBody = "z".repeat(3_000_000))
            val e = assertThrows<GraphApiException> { runBlocking { gateway.send(huge, emptyList(), "key-1", false) } }
            assertEquals(413, e.statusCode)
            assertTrue(server.graphRequests.none { it.method == "POST" })
        }

    @Test fun `a refused connection on the send step also deletes the draft`() =
        runBlocking<Unit> {
            server.failNextWith(ConnectException("refused"), matching = { it.path.endsWith("/send") })
            assertThrows<ConnectException> { runBlocking { gateway.send(payload(), emptyList(), "key-1", false) } }
            assertTrue(server.messagesIn(FakeGraphServer.DRAFTS).isEmpty())
        }

    @Test fun `a failed draft delete does not hide the send failure`() =
        runBlocking<Unit> {
            server.failNext(403, matching = { it.path.endsWith("/send") })
            server.failNext(500, matching = { it.method == "DELETE" })
            val e = assertThrows<GraphApiException> { runBlocking { gateway.send(payload(), emptyList(), "key-1", false) } }
            assertEquals(403, e.statusCode)
        }

    @Test fun `an ambiguous failure of the send step keeps the draft`() =
        runBlocking<Unit> {
            server.failNextWith(SocketTimeoutException("timeout"), matching = { it.path.endsWith("/send") })
            assertThrows<SocketTimeoutException> { runBlocking { gateway.send(payload(), emptyList(), "key-1", false) } }
            assertEquals(1, server.messagesIn(FakeGraphServer.DRAFTS).size)
            assertTrue(server.graphRequests.none { it.method == "DELETE" })

            server.failNext(502, matching = { it.path.endsWith("/send") })
            val e = assertThrows<GraphApiException> { runBlocking { gateway.send(payload(), emptyList(), "key-2", false) } }
            assertEquals(502, e.statusCode)
            assertEquals(2, server.messagesIn(FakeGraphServer.DRAFTS).size)
            assertTrue(server.graphRequests.none { it.method == "DELETE" })
        }

    @Test fun `the send step is not retried on 503 because it may already have gone out`() =
        runBlocking<Unit> {
            server.failNext(503, headers = mapOf("Retry-After" to "1"), matching = { it.path.endsWith("/send") })
            val e = assertThrows<GraphApiException> { runBlocking { gateway.send(payload(), emptyList(), "key-1", false) } }
            assertEquals(503, e.statusCode)
            assertEquals(1, server.graphRequests.count { it.path.endsWith("/send") })
        }

    @Test fun `signed out send throws MailAuthRequiredException and the reads are empty`() =
        runBlocking<Unit> {
            deliver("[SCIF:ON]")
            oauth.disconnect()
            assertFalse(gateway.isAvailable)
            val e = assertThrows<MailAuthRequiredException> { runBlocking { gateway.send(payload(), emptyList(), "key-1", false) } }
            assertEquals("graph", e.providerId)
            assertEquals("Outlook", e.displayName)
            assertTrue(gateway.pollReplies(emptySet()).replies.isEmpty())
            assertTrue(gateway.findCommands(search).candidates.isEmpty())
            assertTrue(gateway.checkForBounces().isEmpty())
            assertNull(gateway.findSent("key-1"))
            assertNull(gateway.accountEmail())
            assertTrue(server.requests.isEmpty())
        }

    @Test fun `accountEmail falls back to the user principal name`() =
        runBlocking<Unit> {
            server.mail = null
            server.userPrincipalName = "someone@outlook.com"
            assertEquals("someone@outlook.com", gateway.accountEmail())
        }

    // ---- execute: auth, throttling, errors ----

    @Test fun `a 401 refreshes the token once and retries the same request`() =
        runBlocking<Unit> {
            assertEquals("me@outlook.com", gateway.accountEmail())
            assertEquals(1, server.tokenRequests)
            val oldToken = server.validAccessToken
            server.require401Once()
            deliver("Re: [SCIF:+1555]")
            val before = server.graphRequests.size
            assertEquals(1, gateway.pollReplies(emptySet()).replies.size)
            assertEquals(2, server.tokenRequests)
            val calls = server.graphRequests.drop(before)
            assertEquals(calls[0].url, calls[1].url)
            assertEquals("Bearer $oldToken", calls[0].headers["Authorization"])
            assertEquals("Bearer ${server.validAccessToken}", calls[1].headers["Authorization"])
        }

    @Test fun `a second 401 is MailAuthRequiredException for Outlook`() =
        runBlocking<Unit> {
            server.failNext(401)
            server.failNext(401)
            val e = assertThrows<MailAuthRequiredException> { runBlocking { gateway.pollReplies(emptySet()) } }
            assertEquals("graph", e.providerId)
            assertEquals("Outlook", e.displayName)
            assertEquals("Open the app and reconnect Outlook", e.message)
        }

    @Test fun `invalid_grant on refresh is MailAuthRequiredException`() =
        runBlocking<Unit> {
            server.invalidGrant = true
            val e = assertThrows<MailAuthRequiredException> { runBlocking { gateway.send(payload(), emptyList(), "key-1", false) } }
            assertEquals("graph", e.providerId)
            assertTrue(server.graphRequests.isEmpty())
            assertFalse(gateway.isAvailable)
        }

    @Test fun `a refresh that fails for another reason is a definite GraphApiException with status 0`() =
        runBlocking<Unit> {
            server.failNext(503, body = "<html>down</html>", matching = { !it.isGraph })
            val e = assertThrows<GraphApiException> { runBlocking { gateway.send(payload(), emptyList(), "key-1", false) } }
            assertEquals(0, e.statusCode)
            assertEquals("Graph API HTTP 0: Outlook sign-in could not be refreshed", e.message)

            server.failNextWith(IOException("offline"), matching = { !it.isGraph })
            val io = assertThrows<GraphApiException> { runBlocking { gateway.pollReplies(emptySet()) } }
            assertEquals(0, io.statusCode)
            assertTrue(server.graphRequests.isEmpty())
            assertTrue(gateway.isAvailable)
        }

    @Test fun `429 with Retry-After waits and retries once, capped at 60 s`() =
        runBlocking<Unit> {
            server.failNext(429, headers = mapOf("Retry-After" to "7"))
            assertEquals("me@outlook.com", gateway.accountEmail())
            assertEquals(listOf(7_000L), sleeps)

            gateway.clearSession()
            server.failNext(503, headers = mapOf("Retry-After" to "3600"))
            assertEquals("me@outlook.com", gateway.accountEmail())
            assertEquals(listOf(7_000L, 60_000L), sleeps)
        }

    @Test fun `a second 429 surfaces as GraphApiException`() =
        runBlocking<Unit> {
            server.failNext(429, headers = mapOf("Retry-After" to "1"))
            server.failNext(429, headers = mapOf("Retry-After" to "1"))
            val e = assertThrows<GraphApiException> { runBlocking { gateway.pollReplies(emptySet()) } }
            assertEquals(429, e.statusCode)
            assertEquals(listOf(1_000L), sleeps)
        }

    @Test fun `429 without Retry-After is not retried`() =
        runBlocking<Unit> {
            server.failNext(429)
            val e = assertThrows<GraphApiException> { runBlocking { gateway.pollReplies(emptySet()) } }
            assertEquals(429, e.statusCode)
            assertTrue(sleeps.isEmpty())
        }

    @Test fun `a negative, HTTP-date or non-numeric Retry-After is not retried`() =
        runBlocking<Unit> {
            for (value in listOf("-5", "Wed, 21 Oct 2015 07:28:00 GMT", "soon")) {
                server.failNext(429, headers = mapOf("Retry-After" to value))
                val e = assertThrows<GraphApiException> { runBlocking { gateway.pollReplies(emptySet()) } }
                assertEquals(value, 429, e.statusCode)
            }
            assertTrue(sleeps.isEmpty())
        }

    @Test fun `500 surfaces as GraphApiException with the status and a bounded detail`() =
        runBlocking<Unit> {
            server.failNext(500, body = "x".repeat(5_000))
            val e = assertThrows<GraphApiException> { runBlocking { gateway.pollReplies(emptySet()) } }
            assertEquals(500, e.statusCode)
            assertTrue(e.message!!.length <= "Graph API HTTP 500: ".length + 1_000)
        }

    @Test fun `a network failure during a request propagates unchanged`() =
        runBlocking<Unit> {
            server.failNextWith(ConnectException("refused"))
            assertThrows<ConnectException> { runBlocking { gateway.pollReplies(emptySet()) } }
            server.failNextWith(SocketTimeoutException("slow"))
            assertThrows<SocketTimeoutException> { runBlocking { gateway.pollReplies(emptySet()) } }
        }

    @Test fun `no token or message body appears in an error message`() =
        runBlocking<Unit> {
            gateway.accountEmail()
            val token = server.validAccessToken!!
            server.failNext(400, body = """{"error":{"code":"BadRequest","message":"bad"}}""")
            val e = assertThrows<GraphApiException> { runBlocking { gateway.send(payload(), emptyList(), "key-1", false) } }
            assertFalse(e.message!!.contains(token))
            assertFalse(e.message!!.contains("hello"))
        }

    @Test fun `an oversize response body is refused`() =
        runBlocking<Unit> {
            server.failNext(200, body = "{\"value\":[],\"pad\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}")
            assertThrows<GraphResponseTooLargeException> { runBlocking { gateway.pollReplies(emptySet()) } }
        }

    // ---- pollReplies ----

    @Test fun `pollReplies returns already-read mail and keeps only SCIF or TEXT subjects`() =
        runBlocking<Unit> {
            val read = deliver("Re: [SCIF:+15551234567] hi", isRead = true)
            val text = deliver("re: text me")
            deliver("Lunch?")
            val replies = gateway.pollReplies(emptySet()).replies
            assertEquals(setOf("graph:$read", "graph:$text"), replies.map { it.id }.toSet())
        }

    @Test fun `pollReplies sweeps 90 days first, then polls from the newest message seen, then sweeps again after 6 h`() =
        runBlocking<Unit> {
            deliver("hello")
            val tm = server.nowMs
            gateway.pollReplies(emptySet())
            val first = inboxLists().last()
            assertEquals("receivedDateTime ge ${FakeGraphServer.iso(tm - 90 * day)}", filterOf(first))
            assertEquals("receivedDateTime desc", first.url.queryParameter("\$orderby"))
            assertEquals("50", first.url.queryParameter("\$top"))
            assertEquals("id,conversationId,subject,from,internetMessageId,receivedDateTime", first.url.queryParameter("\$select"))

            server.nowMs = tm + 30_000
            gateway.pollReplies(emptySet())
            assertEquals("receivedDateTime ge ${FakeGraphServer.iso(tm - 10L * 60_000)}", filterOf(inboxLists().last()))

            val later = deliver("later")
            val tm2 = server.nowMs
            gateway.pollReplies(emptySet())
            gateway.pollReplies(emptySet())
            assertEquals("receivedDateTime ge ${FakeGraphServer.iso(tm2 - 10L * 60_000)}", filterOf(inboxLists().last()))

            // Only an older message left in the window (the newest was moved away): the cursor
            // never moves backwards.
            server.message(later)!!.folder = "archive"
            server.deliver("owner@example.com", "late arrival", "x", receivedMs = tm2 - 5 * 60_000)
            gateway.pollReplies(emptySet())
            gateway.pollReplies(emptySet())
            assertEquals("receivedDateTime ge ${FakeGraphServer.iso(tm2 - 10L * 60_000)}", filterOf(inboxLists().last()))

            val t3 = tm + 6 * 3_600_000L + 60_000
            server.nowMs = t3
            gateway.pollReplies(emptySet())
            assertEquals("receivedDateTime ge ${FakeGraphServer.iso(t3 - 90 * day)}", filterOf(inboxLists().last()))

            gateway.clearSession()
            gateway.pollReplies(emptySet())
            assertEquals("receivedDateTime ge ${FakeGraphServer.iso(t3 - 90 * day)}", filterOf(inboxLists().last()))
        }

    @Test fun `the poll cursor follows the server's clock, not the phone's`() =
        runBlocking<Unit> {
            for (skew in listOf(3 * 3_600_000L, -3 * 3_600_000L)) {
                val skewed = GraphGateway(oauth, server.client(), nowMs = { server.nowMs + skew }, sleep = {})
                deliver("hello $skew")
                val tm = server.nowMs
                skewed.pollReplies(emptySet())
                assertEquals("receivedDateTime ge ${FakeGraphServer.iso(tm + skew - 90 * day)}", filterOf(inboxLists().last()))
                server.nowMs += 30_000
                skewed.pollReplies(emptySet())
                assertEquals("receivedDateTime ge ${FakeGraphServer.iso(tm - 10L * 60_000)}", filterOf(inboxLists().last()))
            }
        }

    @Test fun `with no mail seen yet every poll is a sweep`() =
        runBlocking<Unit> {
            gateway.pollReplies(emptySet())
            server.nowMs += 30_000
            gateway.pollReplies(emptySet())
            assertEquals("receivedDateTime ge ${FakeGraphServer.iso(server.nowMs - 90 * day)}", filterOf(inboxLists().last()))
        }

    @Test fun `a failed poll does not advance the cursor`() =
        runBlocking<Unit> {
            deliver("hello")
            val tm = server.nowMs
            gateway.pollReplies(emptySet())
            deliver("newer")
            server.failNext(500, matching = { it.path.endsWith("/inbox/messages") })
            assertThrows<GraphApiException> { runBlocking { gateway.pollReplies(emptySet()) } }
            gateway.pollReplies(emptySet())
            assertEquals("receivedDateTime ge ${FakeGraphServer.iso(tm - 10L * 60_000)}", filterOf(inboxLists().last()))
        }

    @Test fun `a poll cut off by the page cap keeps its cursor and brings the sweep forward`() =
        runBlocking<Unit> {
            deliver("hello")
            val tm = server.nowMs
            gateway.pollReplies(emptySet())
            repeat(210) { deliver("newsletter $it") }
            val before = inboxLists().size
            gateway.pollReplies(emptySet())
            assertEquals(4, inboxLists().size - before)
            // Same window again: the cursor did not move past mail it never read.
            server.nowMs += 30_000
            gateway.pollReplies(emptySet())
            assertEquals("receivedDateTime ge ${FakeGraphServer.iso(tm - 10L * 60_000)}", filterOf(inboxLists().last()))
            // Within 5 minutes, a sweep instead of waiting 6 h.
            server.nowMs += 5 * 60_000L
            gateway.pollReplies(emptySet())
            assertEquals("receivedDateTime ge ${FakeGraphServer.iso(server.nowMs - 90 * day)}", filterOf(inboxLists().last()))
        }

    @Test fun `a sweep that hits the candidate cap sweeps again on the next poll, then stops sweeping`() =
        runBlocking<Unit> {
            // 50 older candidates, then 100 newer ones an hour later: the first sweep fetches only
            // the newest 100, and the older 50 lie outside a normal poll's 10-minute overlap.
            val old = (1..50).map { deliver("Re: [SCIF:+15551234567] old $it") }
            server.nowMs += 3_600_000L
            val recent = (1..100).map { deliver("Re: [SCIF:+15551234567] new $it") }
            val newest = server.nowMs
            val known = mutableSetOf<String>()

            val first = gateway.pollReplies(known).replies.map { it.id }
            assertEquals(recent.map { "graph:$it" }.toSet(), first.toSet())
            known += first

            server.nowMs += 30_000
            val second = gateway.pollReplies(known).replies.map { it.id }
            assertEquals("receivedDateTime ge ${FakeGraphServer.iso(server.nowMs - 90 * day)}", filterOf(inboxLists().last()))
            assertEquals(old.map { "graph:$it" }.toSet(), second.toSet())
            known += second

            // Every fetched id is now known: the next polls are normal polls, not sweeps.
            repeat(2) {
                server.nowMs += 30_000
                assertTrue(gateway.pollReplies(known).replies.isEmpty())
                assertEquals("receivedDateTime ge ${FakeGraphServer.iso(newest - 10L * 60_000)}", filterOf(inboxLists().last()))
            }
        }

    @Test fun `timestamps are UTC with Z and no fractional seconds`() =
        runBlocking<Unit> {
            server.nowMs = 1_760_000_000_123L
            gateway.pollReplies(emptySet())
            val filter = filterOf(inboxLists().last())!!
            assertTrue(filter, Regex("receivedDateTime ge \\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z").matches(filter))
        }

    @Test fun `pollReplies follows nextLink paging`() =
        runBlocking<Unit> {
            val wanted = deliver("Re: [SCIF:+15551234567] oldest")
            repeat(110) { deliver("newsletter $it") }
            val replies = gateway.pollReplies(emptySet()).replies
            assertEquals(listOf("graph:$wanted"), replies.map { it.id })
            assertEquals(3, inboxLists().size)
        }

    @Test fun `pollReplies reads at most 4 pages`() =
        runBlocking<Unit> {
            deliver("Re: [SCIF:+15551234567] too old to reach")
            repeat(200) { deliver("newsletter $it") }
            assertTrue(gateway.pollReplies(emptySet()).replies.isEmpty())
            assertEquals(4, inboxLists().size)
        }

    @Test fun `a nextLink to another host stops paging, keeps what was read and is logged once`() =
        runBlocking<Unit> {
            val id = deliver("Re: [SCIF:+15551234567] hi")
            val summary =
                JSONObject()
                    .put("id", id)
                    .put("subject", "Re: [SCIF:+15551234567] hi")
                    .put("receivedDateTime", FakeGraphServer.iso(server.nowMs))
            val page =
                JSONObject()
                    .put("value", org.json.JSONArray().put(summary))
                    .put("@odata.nextLink", "https://evil.example.com/v1.0/me/mailFolders/inbox/messages?\$skiptoken=SECRET")
                    .toString()
            repeat(2) {
                server.failNext(200, body = page, matching = { it.path.endsWith("/inbox/messages") })
                assertEquals(listOf("graph:$id"), gateway.pollReplies(emptySet()).replies.map { it.id })
            }
            assertTrue(server.requests.none { it.url.host == "evil.example.com" })
            val pagingLogs = logs.filter { "paging" in it }
            assertEquals(1, pagingLogs.size)
            assertFalse(pagingLogs.single().contains("SECRET"))
            assertFalse(pagingLogs.single().contains("evil"))
        }

    @Test fun `pollReplies skips known ids and builds the message from its headers`() =
        runBlocking<Unit> {
            val known = deliver("Re: [SCIF:+1555] old")
            val id =
                deliver(
                    "Re: [SCIF:+15551234567] hi",
                    from = "Owner <owner@example.com>",
                    extraHeaders =
                        listOf(
                            "In-Reply-To" to "<scif-abc@scif-sidekick.invalid>",
                            "References" to "<a@x> <b@y>",
                        ),
                )
            val result = gateway.pollReplies(setOf("graph:$known"))
            val message = result.replies.single()
            val stored = server.message(id)!!
            assertEquals("graph:$id", message.id)
            assertEquals("graph:${stored.conversationId}", message.threadId)
            assertEquals("Re: [SCIF:+15551234567] hi", message.subject)
            assertEquals(setOf("scif-abc@scif-sidekick.invalid", "a@x", "b@y"), message.referencedMessageIds)
            assertEquals(stored.internetMessageId.trim('<', '>'), message.rfcMessageId)
            assertEquals("Owner <owner@example.com>", message.fromHeader)
            assertEquals("owner@example.com", message.authenticatedFromAddress)
            assertEquals("", message.body)
            val metadata = server.graphRequests.last()
            assertEquals("/v1.0/me/messages/$id", metadata.path)
            assertEquals("internetMessageHeaders,from,subject,conversationId,internetMessageId", metadata.url.queryParameter("\$select"))
        }

    @Test fun `references are capped at 50`() =
        runBlocking<Unit> {
            val refs = (1..80).joinToString(" ") { "<r$it@x>" }
            deliver("Re: [SCIF:+1555]", extraHeaders = listOf("References" to refs))
            assertEquals(50, gateway.pollReplies(emptySet()).replies.single().referencedMessageIds.size)
        }

    @Test fun `without a From header the from object is used`() =
        runBlocking<Unit> {
            server.nowMs += 1_000
            server.deliver(
                "Owner <owner@example.com>",
                "Re: [SCIF:+1555]",
                "hi",
                listOf("Authentication-Results" to pass("example.com")),
                includeFromHeader = false,
            )
            val message = gateway.pollReplies(emptySet()).replies.single()
            assertEquals("Owner <owner@example.com>", message.fromHeader)
            assertEquals("owner@example.com", message.authenticatedFromAddress)
        }

    @Test fun `a sender without Authentication-Results is not authenticated`() =
        runBlocking<Unit> {
            deliver("Re: [SCIF:+1555]", authenticated = false)
            assertNull(gateway.pollReplies(emptySet()).replies.single().authenticatedFromAddress)
        }

    @Test fun `mail without any Authentication-Results header is logged once, without content`() =
        runBlocking<Unit> {
            deliver("Re: [SCIF:+1555] signed", authenticated = true)
            gateway.pollReplies(emptySet())
            assertTrue(logs.isEmpty())
            deliver("Re: [SCIF:+1555] first secret", authenticated = false)
            deliver("Re: [SCIF:+1555] second secret", authenticated = false)
            server.nowMs += 30_000
            gateway.pollReplies(emptySet())
            gateway.clearSession()
            gateway.pollReplies(emptySet())
            assertEquals(listOf("Outlook messages arrived without an Authentication-Results header, so senders cannot be authenticated"), logs)
        }

    @Test fun `a per-message failure is reported and the rest of the poll continues`() =
        runBlocking<Unit> {
            val bad = deliver("Re: [SCIF:+1555] one")
            val good = deliver("Re: [SCIF:+1555] two")
            server.failNext(500, matching = { it.path == "/v1.0/me/messages/$bad" })
            val result = gateway.pollReplies(emptySet())
            assertEquals(listOf("graph:$good"), result.replies.map { it.id })
            assertEquals(1, result.fetchFailures.size)
            assertTrue(result.fetchFailures.single().startsWith("graph:$bad"))
        }

    @Test fun `an auth failure on a message aborts the poll`() =
        runBlocking<Unit> {
            val id = deliver("Re: [SCIF:+1555] one")
            server.failNext(401, matching = { it.path == "/v1.0/me/messages/$id" })
            server.failNext(401, matching = { it.path == "/v1.0/me/messages/$id" })
            assertThrows<MailAuthRequiredException> { runBlocking { gateway.pollReplies(emptySet()) } }
            Unit
        }

    // ---- findCommands ----

    @Test fun `findCommands keeps only unread recent mail with a tag from a listed sender, newest first`() =
        runBlocking<Unit> {
            val a = deliver("[SCIF:ON]", from = "owner@example.com")
            deliver("[SCIF:ON]", from = "stranger@example.com")
            deliver("Hello", from = "owner@example.com")
            deliver("[SCIF:ON]", from = "owner@example.com", isRead = true)
            server.deliver("owner@example.com", "[SCIF:ON]", "old", receivedMs = server.nowMs - 3 * day)
            val b = deliver("Re: [scif:off] please", from = "Boss <BOSS@agency.gov>")
            deliver("[SCIF:BOGUS]", from = "boss@agency.gov")
            val scan = gateway.findCommands(search)
            assertEquals(listOf("graph:$b", "graph:$a"), scan.candidates.map { it.id })
        }

    @Test fun `findCommands matches tags and senders client-side, newest first, capped`() =
        runBlocking<Unit> {
            deliver("[SCIF:ON]", from = "stranger@example.com")
            deliver("Hello", from = "owner@example.com")
            deliver("[SCIF:ON]", from = "owner@example.com", isRead = true)
            val ids = (1..25).map { deliver(if (it % 2 == 0) "[scif:off] now" else "Fwd: [SCIF:ON]", from = if (it % 3 == 0) "Boss <BOSS@agency.gov>" else "owner@example.com") }
            val scan = gateway.findCommands(search)
            assertEquals(ids.reversed().take(RemoteCommandPlanner.MAX_CANDIDATES).map { "graph:$it" }, scan.candidates.map { it.id })
            assertTrue(scan.unreadable.isEmpty())
            val list = inboxLists().first()
            assertEquals("receivedDateTime ge ${FakeGraphServer.iso(server.nowMs - 2L * 24 * 3_600_000)} and isRead eq false", filterOf(list))
            assertEquals("receivedDateTime desc", list.url.queryParameter("\$orderby"))
        }

    @Test fun `findCommands reports an unreadable candidate by id`() =
        runBlocking<Unit> {
            val bad = deliver("[SCIF:ON]")
            val good = deliver("[SCIF:ON]")
            server.failNext(500, matching = { it.path == "/v1.0/me/messages/$bad" })
            val scan = gateway.findCommands(search)
            assertEquals(listOf("graph:$good"), scan.candidates.map { it.id })
            assertEquals(listOf("graph:$bad"), scan.unreadable)
        }

    // ---- markRead / fetchContent ----

    @Test fun `markRead PATCHes isRead true and is idempotent`() =
        runBlocking<Unit> {
            val id = deliver("[SCIF:ON]")
            gateway.markRead("graph:$id")
            gateway.markRead("graph:$id")
            val patches = server.graphRequests.filter { it.method == "PATCH" }
            assertEquals(2, patches.size)
            patches.forEach {
                assertEquals("/v1.0/me/messages/$id", it.path)
                assertEquals(true, JSONObject(it.body!!).getBoolean("isRead"))
                assertEquals(1, JSONObject(it.body!!).length())
            }
            assertTrue(server.message(id)!!.isRead)
        }

    private fun metadataFor(id: String): MailMessage =
        MailMessage("graph:$id", "graph:t", "s", "", emptySet(), "", "owner@example.com", "owner@example.com")

    @Test fun `fetchContent returns the text body and the first image within the size cap`() =
        runBlocking<Unit> {
            val id = deliver("[SCIF:ON]", body = "Send this")
            val png = byteArrayOf(1, 2, 3, 4)
            server.addAttachment(id, FakeGraphServer.Attachment("a1", "notes.pdf", "application/pdf", byteArrayOf(9)))
            server.addAttachment(id, FakeGraphServer.Attachment("a2", "big.jpg", "image/jpeg", byteArrayOf(7), declaredSize = 9L * 1024 * 1024))
            server.addAttachment(id, FakeGraphServer.Attachment("a3", "photo.png", "image/png", png))
            val content = gateway.fetchContent(metadataFor(id))
            assertEquals("Send this", content.body)
            assertEquals("image/png", content.imageMimeType)
            assertArrayEquals(png, content.imageBytes)
            val bodyRequest = server.graphRequests.first { it.path == "/v1.0/me/messages/$id" }
            assertEquals("body", bodyRequest.url.queryParameter("\$select"))
            assertTrue(bodyRequest.headers.values("Prefer").any { "outlook.body-content-type=\"text\"" in it })
            assertTrue(server.graphRequests.none { it.path.endsWith("/attachments/a2") || it.path.endsWith("/attachments/a1") })
        }

    @Test fun `fetchContent without an image leaves the image empty`() =
        runBlocking<Unit> {
            val id = deliver("[SCIF:ON]", body = "Only text")
            server.addAttachment(id, FakeGraphServer.Attachment("a1", "notes.pdf", "application/pdf", byteArrayOf(9)))
            val content = gateway.fetchContent(metadataFor(id))
            assertEquals("Only text", content.body)
            assertNull(content.imageMimeType)
            assertNull(content.imageBytes)
        }

    @Test fun `fetchContent rejects image bytes larger than the cap even if the declared size was small`() =
        runBlocking<Unit> {
            val id = deliver("[SCIF:ON]", body = "x")
            server.addAttachment(id, FakeGraphServer.Attachment("a1", "lie.png", "image/png", ByteArray(8 * 1024 * 1024 + 1), declaredSize = 10))
            assertNull(gateway.fetchContent(metadataFor(id)).imageBytes)
        }

    @Test fun `an image whose base64 is too long is refused before decoding`() =
        runBlocking<Unit> {
            val id = deliver("[SCIF:ON]", body = "x")
            // Decodes to 3 bytes (the MIME decoder skips the spaces), but the encoded text alone is
            // longer than an 8 MB image's base64, so it is refused before decoding.
            val padded = "AQID" + " ".repeat(11_300_000)
            server.addAttachment(id, FakeGraphServer.Attachment("a1", "odd.png", "image/png", byteArrayOf(1, 2, 3), declaredSize = 3, contentBytesOverride = padded))
            assertNull(gateway.fetchContent(metadataFor(id)).imageBytes)
        }

    @Test fun `an attachment listed without a type annotation is judged by its content type`() =
        runBlocking<Unit> {
            val id = deliver("[SCIF:ON]", body = "x")
            server.addAttachment(id, FakeGraphServer.Attachment("a1", "notes.pdf", "application/pdf", byteArrayOf(9), odataType = null))
            server.addAttachment(id, FakeGraphServer.Attachment("a2", "photo.png", "image/png", byteArrayOf(5, 6), odataType = null))
            val content = gateway.fetchContent(metadataFor(id))
            assertEquals("image/png", content.imageMimeType)
            assertArrayEquals(byteArrayOf(5, 6), content.imageBytes)
        }

    @Test fun `an item attachment is never taken for an image`() =
        runBlocking<Unit> {
            val id = deliver("[SCIF:ON]", body = "x")
            server.addAttachment(id, FakeGraphServer.Attachment("a1", "fwd", "image/png", byteArrayOf(5), odataType = "#microsoft.graph.itemAttachment"))
            assertNull(gateway.fetchContent(metadataFor(id)).imageBytes)
        }

    @Test fun `a body too large to read is dropped and the image still arrives`() =
        runBlocking<Unit> {
            val id = deliver("[SCIF:ON]", body = "y".repeat(2_200_000))
            server.addAttachment(id, FakeGraphServer.Attachment("a1", "photo.png", "image/png", byteArrayOf(1, 2)))
            val content = gateway.fetchContent(metadataFor(id))
            assertEquals("", content.body)
            assertArrayEquals(byteArrayOf(1, 2), content.imageBytes)
        }

    @Test fun `an HTML-only message comes back as the server-converted text`() =
        runBlocking<Unit> {
            val id = deliver("[SCIF:ON]", body = "<p>Hello <b>there</b></p>", htmlOnly = true)
            assertEquals("Hello there", gateway.fetchContent(metadataFor(id)).body)
        }

    @Test fun `a huge body is truncated to 64,000 characters`() =
        runBlocking<Unit> {
            val id = deliver("[SCIF:ON]", body = "y".repeat(70_000))
            assertEquals(64_000, gateway.fetchContent(metadataFor(id)).body.length)
        }

    // ---- checkForBounces ----

    @Test fun `checkForBounces finds a bounce quoting an own Message-ID and ignores others`() =
        runBlocking<Unit> {
            val own = MimeMessageBuilder.rfcMessageId("key-1")
            val bounce = deliver("Delivery Status Notification (Failure)", from = "mailer-daemon@googlemail.com", body = "Your message <$own> could not be delivered")
            deliver("Undeliverable: hi", from = "postmaster@outlook.com", body = "Some other message <x@y> failed")
            val viaHeader = deliver("Undeliverable: hello", from = "postmaster@outlook.com", body = "no id here", extraHeaders = listOf("References" to "<$own>"))
            deliver("Re: [SCIF:+1555] mentions <$own>", from = "owner@example.com", body = "<$own>")
            deliver("Delivery has failed", from = "postmaster@outlook.com", body = "<$own>", isRead = true)
            val notices = gateway.checkForBounces()
            assertEquals(setOf("graph:$bounce", "graph:$viaHeader"), notices.map { it.messageId }.toSet())
            notices.forEach { assertEquals(setOf(own), it.referencedRfcMessageIds) }
            inboxLists().forEach {
                assertEquals("receivedDateTime ge ${FakeGraphServer.iso(server.nowMs - 7 * day)} and isRead eq false", filterOf(it))
            }
        }

    // ---- session ----

    @Test fun `clearSession drops the cached address`() =
        runBlocking<Unit> {
            assertEquals("me@outlook.com", gateway.accountEmail())
            server.mail = "other@outlook.com"
            assertEquals("me@outlook.com", gateway.accountEmail())
            gateway.clearSession()
            assertEquals("other@outlook.com", gateway.accountEmail())
        }

    @Test fun `ids from another provider are refused`() =
        runBlocking<Unit> {
            assertThrows<IllegalArgumentException> { runBlocking { gateway.markRead("gmail-hex-id") } }
            Unit
        }

    @Test fun `the fake server rejects queries and paths it does not implement`() =
        runBlocking<Unit> {
            gateway.accountEmail() // issues a token
            val client = server.client()
            fun status(url: String): Int =
                client
                    .newCall(
                        okhttp3.Request
                            .Builder()
                            .url(url)
                            .header("Authorization", "Bearer ${server.validAccessToken}")
                            .build(),
                    ).execute()
                    .use { it.code }
            val inbox = "https://graph.microsoft.com/v1.0/me/mailFolders/inbox/messages"
            assertEquals(200, status("$inbox?\$filter=receivedDateTime ge 2026-10-01T00:00:00Z and isRead eq false&\$orderby=receivedDateTime desc"))
            assertEquals(400, status("$inbox?\$filter=startswith(subject,'SCIF')"))
            assertEquals(400, status("$inbox?\$filter=receivedDateTime ge 2026-10-01T00:00:00.123Z"))
            assertEquals(400, status("$inbox?\$filter=isRead eq false and receivedDateTime ge 2026-10-01T00:00:00Z&\$orderby=receivedDateTime desc"))
            assertEquals(400, status("$inbox?\$select=sender"))
            assertEquals(400, status("$inbox?\$search=SCIF"))
            assertEquals(400, status("https://graph.microsoft.com/v1.0/me/mailFolders/junkemail/messages"))
            assertEquals(400, status("https://graph.microsoft.com/v1.0/me/events"))
        }

    @Test fun `providerId and displayName`() {
        assertEquals("graph", gateway.providerId)
        assertEquals("Outlook", gateway.displayName)
        assertTrue(gateway.isAvailable)
    }
}
