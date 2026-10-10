package com.scifsidekick.cleanroom

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scifsidekick.cleanroom.data.QueueChannel
import com.scifsidekick.cleanroom.email.MailRouter
import com.scifsidekick.cleanroom.messaging.EmailPayload
import com.scifsidekick.cleanroom.messaging.MmsGateway
import com.scifsidekick.cleanroom.messaging.SmsGateway
import com.scifsidekick.cleanroom.service.AlertNotifier
import com.scifsidekick.cleanroom.service.QueueProcessor
import com.scifsidekick.cleanroom.service.RollingRateLimiter
import com.scifsidekick.cleanroom.util.AttachmentStore
import com.scifsidekick.cleanroom.util.Hashing
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The real send queue over a router of two fake mailboxes: failover and reconciliation end to end. */
@RunWith(AndroidJUnit4::class)
class MailRouterQueueInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val graph = AppGraph.from(context)
    private val gmail = FakeMailTransport("gmail", "Gmail")
    private val outlook = FakeMailTransport("graph", "Outlook")
    private val router = MailRouter(members = { listOf(gmail, outlook) })

    @Before fun setUp() {
        graph.database.clearAllTables()
        graph.mail = router
    }

    @After fun tearDown() {
        graph.mail = graph.mailRouter
        graph.database.clearAllTables()
    }

    private fun processor() =
        QueueProcessor(
            graph.database,
            graph.repository,
            RollingRateLimiter(graph.database.deliveryAttemptDao()),
            graph.mail,
            SmsGateway(context),
            MmsGateway(context),
            AttachmentStore(context),
            AlertNotifier(context),
        )

    private suspend fun enqueue(): Long =
        graph.repository.enqueueSystemEmail(
            recipients = listOf("owner@example.com"),
            subject = "router test",
            body = "body",
            reason = "router test",
        )!!

    @Test fun preferredSignedOutSendsThroughTheFallbackOnce() =
        runBlocking {
            enqueue()
            gmail.signedIn = false
            processor().drain(QueueChannel.EMAIL, 5)
            assertEquals(0, gmail.sendCount)
            assertEquals(1, outlook.sent.size)
            assertEquals("router test", outlook.sent.single().payload.renderedSubject)
            assertEquals(1, graph.database.deliveryAttemptDao().countSince(QueueChannel.EMAIL, 0))
            assertEquals(0, graph.database.queueDao().queuedEmailCount())
        }

    @Test fun aRetryTheFallbackAlreadySentCompletesWithoutSendingAgain() =
        runBlocking {
            val id = enqueue()
            // An earlier attempt that went out through Outlook but was never recorded as sent.
            graph.database.queueDao().retry(id, "simulated interrupted attempt", notBefore = 0L)
            val row = graph.database.queueDao().get(id)!!
            assertTrue(row.attemptCount > 0)
            val deliveryKey = "${row.id}:${row.createdAtMs}:${Hashing.sha256(row.payloadJson)}"
            outlook.send(earlierPayload(), emptyList(), deliveryKey, verifyPriorDelivery = false)

            processor().drain(QueueChannel.EMAIL, 5)

            assertEquals(0, gmail.sendCount)
            assertEquals(1, outlook.sendCount) // only the earlier one
            assertEquals(1, outlook.sent.size)
            assertEquals(0, graph.database.queueDao().queuedEmailCount())
        }

    private fun earlierPayload() =
        EmailPayload(
            destinations = listOf("owner@example.com"),
            replyTarget = null,
            senderDisplay = "SCIF Sidekick",
            body = "body",
            receivedAtMs = 1L,
            source = "system",
            participants = emptyList(),
            attachmentNotice = null,
            renderedSubject = "router test",
            renderedBody = "body",
            filterName = "(system)",
        )
}
