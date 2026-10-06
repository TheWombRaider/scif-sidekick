package com.scifsidekick.cleanroom

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.scifsidekick.cleanroom.data.QueueChannel
import com.scifsidekick.cleanroom.service.ReceiptDrainWorker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs against the app's own database on the test device, using the debug fake email transport. */
@RunWith(AndroidJUnit4::class)
class ReceiptDrainWorkerInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val graph = AppGraph.from(context)

    @Before fun setUp() {
        graph.database.clearAllTables()
        graph.debug.fakeEmailTransport = true
    }

    @After fun tearDown() {
        graph.debug.fakeEmailTransport = false
        graph.database.clearAllTables()
    }

    @Test fun sendsAQueuedReceiptExactlyOnce() =
        runBlocking {
            graph.repository.enqueueSystemEmail(
                recipients = listOf("owner@example.com"),
                subject = "SCIF Sidekick: forwarding DISABLED",
                body = "receipt body",
                reason = "test receipt",
            )!!
            assertEquals(1, graph.database.queueDao().queuedEmailCount())

            val worker = TestListenableWorkerBuilder<ReceiptDrainWorker>(context).build()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertEquals(0, graph.database.queueDao().queuedEmailCount())
            assertEquals(1, graph.database.deliveryAttemptDao().countSince(QueueChannel.EMAIL, 0))

            // A second run finds nothing left to send.
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertEquals(1, graph.database.deliveryAttemptDao().countSince(QueueChannel.EMAIL, 0))
        }
}
