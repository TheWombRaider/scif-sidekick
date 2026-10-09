package com.scifsidekick.cleanroom

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scifsidekick.cleanroom.data.QueueChannel
import com.scifsidekick.cleanroom.messaging.MmsGateway
import com.scifsidekick.cleanroom.messaging.SmsGateway
import com.scifsidekick.cleanroom.service.AlertNotifier
import com.scifsidekick.cleanroom.service.QueueProcessor
import com.scifsidekick.cleanroom.service.RollingRateLimiter
import com.scifsidekick.cleanroom.util.AttachmentStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The send queue must work with any [com.scifsidekick.cleanroom.email.MailTransport], not just Gmail. */
@RunWith(AndroidJUnit4::class)
class MailTransportSeamInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val graph = AppGraph.from(context)

    @Before fun setUp() {
        graph.database.clearAllTables()
    }

    @After fun tearDown() {
        graph.database.clearAllTables()
    }

    @Test fun queueProcessorSendsThroughAnyMailTransport() =
        runBlocking {
            val fake = FakeMailTransport()
            graph.repository.enqueueSystemEmail(
                recipients = listOf("owner@example.com"),
                subject = "seam test",
                body = "body",
                reason = "seam test",
            )!!
            val processor =
                QueueProcessor(
                    graph.database,
                    graph.repository,
                    RollingRateLimiter(graph.database.deliveryAttemptDao()),
                    fake,
                    SmsGateway(context),
                    MmsGateway(context),
                    AttachmentStore(context),
                    AlertNotifier(context),
                )
            processor.drain(QueueChannel.EMAIL, 5)
            assertEquals(1, fake.sent.size)
            assertEquals("seam test", fake.sent.single().payload.renderedSubject)
            assertEquals(0, graph.database.queueDao().queuedEmailCount())
        }
}
