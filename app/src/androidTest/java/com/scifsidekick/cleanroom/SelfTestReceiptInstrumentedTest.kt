package com.scifsidekick.cleanroom

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.scifsidekick.cleanroom.data.QueueChannel
import com.scifsidekick.cleanroom.service.ReceiptDrainWorker
import com.scifsidekick.cleanroom.service.SelfTestReceipt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The self-test receipt must travel the same queue and worker path as a real command receipt. */
@RunWith(AndroidJUnit4::class)
class SelfTestReceiptInstrumentedTest {
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

    @Test fun queuesThroughTheReceiptPathAndTheWorkerSendsIt() =
        runBlocking {
            val outcome = SelfTestReceipt.send(context, graph, recipientOverride = "owner@example.com")
            assertEquals(SelfTestReceipt.Outcome.Queued("owner@example.com"), outcome)
            assertEquals(1, graph.database.queueDao().queuedEmailCount())

            val worker = TestListenableWorkerBuilder<ReceiptDrainWorker>(context).build()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertEquals(0, graph.database.queueDao().queuedEmailCount())
            assertEquals(1, graph.database.deliveryAttemptDao().countSince(QueueChannel.EMAIL, 0))
        }

    @Test fun withoutAConnectedAccountNothingIsQueued() =
        runBlocking {
            assumeFalse("needs a device with no Gmail account connected", graph.oauth.isAuthorized)
            val outcome = SelfTestReceipt.send(context, graph)
            assertTrue(outcome is SelfTestReceipt.Outcome.NotConnected)
            assertEquals(0, graph.database.queueDao().queuedEmailCount())
        }
}
