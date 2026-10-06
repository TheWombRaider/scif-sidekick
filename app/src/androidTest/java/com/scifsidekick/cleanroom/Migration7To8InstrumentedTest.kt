package com.scifsidekick.cleanroom

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scifsidekick.cleanroom.data.SidekickDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration7To8InstrumentedTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SidekickDatabase::class.java)

    @Test
    fun migrationPreservesRoutesAndCreatesDurableDispatchState() {
        helper.createDatabase(DB_NAME, 7).apply {
            execSQL(
                "INSERT INTO sent_email_routes(queueId,gmailMessageId,gmailThreadId,rfcMessageId,targetNumber,sentAtMs) " +
                    "VALUES(1,'gmail-1','thread-1','rfc-1@example','+15551234567',1000)",
            )
            close()
        }

        helper.runMigrationsAndValidate(DB_NAME, 8, true, SidekickDatabase.MIGRATION_7_8).apply {
            query("SELECT authorizedReplySendersJson FROM sent_email_routes WHERE queueId=1").use { cursor ->
                cursor.moveToFirst()
                assertEquals("[]", cursor.getString(0))
            }
            query("SELECT gmailMessageId,rfcMessageId FROM sent_gmail_messages WHERE queueId=1").use { cursor ->
                cursor.moveToFirst()
                assertEquals("gmail-1", cursor.getString(0))
                assertEquals("rfc-1@example", cursor.getString(1))
            }
            close()
        }
    }

    private companion object {
        const val DB_NAME = "migration-7-8"
    }
}
