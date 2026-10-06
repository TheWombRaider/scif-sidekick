package com.scifsidekick.cleanroom

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scifsidekick.cleanroom.data.SidekickDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration8To9InstrumentedTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SidekickDatabase::class.java)

    @Test
    fun migrationAddsNullableReplyDedupeKeyWithoutTouchingExistingRows() {
        helper.createDatabase(DB_NAME, 8).apply {
            execSQL(
                "INSERT INTO send_queue(id, channel, payloadJson, attachmentPathsJson, createdAtMs, notBeforeMs, status, attemptCount) " +
                    "VALUES(1, 'SMS', '{}', '[]', 1000, 0, 'QUEUED', 0)",
            )
            close()
        }

        helper.runMigrationsAndValidate(DB_NAME, 9, true, SidekickDatabase.MIGRATION_8_9).apply {
            query("SELECT replyDedupeKey, channel FROM send_queue WHERE id = 1").use { cursor ->
                cursor.moveToFirst()
                assertNull(cursor.getString(0))
                assertEquals("SMS", cursor.getString(1))
            }
            close()
        }
    }

    private companion object {
        const val DB_NAME = "migration-8-9"
    }
}
