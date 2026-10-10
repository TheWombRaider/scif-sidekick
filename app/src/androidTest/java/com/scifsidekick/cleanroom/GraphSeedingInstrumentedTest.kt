package com.scifsidekick.cleanroom

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scifsidekick.cleanroom.data.REMOTE_GRAPH_SEEDED
import com.scifsidekick.cleanroom.data.SidekickDatabase
import com.scifsidekick.cleanroom.data.SidekickRepository
import com.scifsidekick.cleanroom.util.RemoteControlCodec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The Outlook address is authorized for remote control once, next to the Gmail owner, and never re-added. */
@RunWith(AndroidJUnit4::class)
class GraphSeedingInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: SidekickDatabase
    private lateinit var repository: SidekickRepository

    @Before fun setUp() {
        context.getSharedPreferences("setup_flags_v1", Context.MODE_PRIVATE).edit().clear().commit()
        db =
            Room
                .inMemoryDatabaseBuilder(context, SidekickDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository = SidekickRepository(context, db)
        runBlocking { repository.ensureInitialized() }
    }

    @After fun tearDown() {
        db.close()
        context.getSharedPreferences("setup_flags_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private suspend fun senders() = RemoteControlCodec.fromJson(repository.currentAppSettings().remoteControlSendersJson)

    @Test fun outlookIsAddedOnceWithAllFourCommandsNextToTheGmailOwnerAndNeverReAdded() =
        runBlocking {
            repository.seedRemoteControlOwnerIfEmpty("owner@gmail.com")
            repository.seedRemoteControlSender("Me@Outlook.com", REMOTE_GRAPH_SEEDED)
            assertEquals(listOf("owner@gmail.com", "me@outlook.com"), senders().map { it.address })
            val outlook = senders().last()
            assertTrue(outlook.canCompose && outlook.canEnable && outlook.canDisable && outlook.canStatus)

            // A second connection (even of another account) changes nothing.
            repository.seedRemoteControlSender("other@outlook.com", REMOTE_GRAPH_SEEDED)
            assertEquals(listOf("owner@gmail.com", "me@outlook.com"), senders().map { it.address })

            // Removed on purpose: stays removed.
            val remaining = RemoteControlCodec.toJson(senders().filter { it.address != "me@outlook.com" })
            repository.updateAppSettings { it.copy(remoteControlSendersJson = remaining) }
            repository.seedRemoteControlSender("me@outlook.com", REMOTE_GRAPH_SEEDED)
            assertEquals(listOf("owner@gmail.com"), senders().map { it.address })
        }

    @Test fun anAddressAlreadyListedIsLeftAsItIs() =
        runBlocking {
            val statusOnly = RemoteControlCodec.Sender("me@outlook.com", canCompose = false, canEnable = false, canDisable = false, canStatus = true)
            repository.updateAppSettings { it.copy(remoteControlSendersJson = RemoteControlCodec.toJson(listOf(statusOnly))) }
            repository.seedRemoteControlSender("ME@outlook.com", REMOTE_GRAPH_SEEDED)
            assertEquals(listOf(statusOnly), senders())
        }

    @Test fun outlookSeedsIntoAnEmptyListAndThenGmailOwnerSeedingAddsNothingSinceTheListIsNotEmpty() =
        runBlocking {
            repository.seedRemoteControlSender("me@outlook.com", REMOTE_GRAPH_SEEDED)
            assertEquals(listOf("me@outlook.com"), senders().map { it.address })
            // Gmail's rule is unchanged: it only seeds into an empty list.
            repository.seedRemoteControlOwnerIfEmpty("owner@gmail.com")
            assertEquals(listOf("me@outlook.com"), senders().map { it.address })
        }
}
