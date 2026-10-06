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
class Migration11To12InstrumentedTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SidekickDatabase::class.java)

    @Test
    fun migrationAddsRemoteEnableDefaultsWithoutTouchingExistingRows() {
        helper.createDatabase(DB_NAME, 11).apply {
            execSQL(
                "INSERT INTO app_settings(id, appLockEnabled, fontScaleKey, duplicateSuppressionEnabled, " +
                    "duplicateWindowMinutes, softEmailPerMinuteCap, softSmsPerMinuteCap, retryOnNetworkReconnect, " +
                    "composeViaEmailEnabled, authorizedComposeSendersJson, messageRetentionDays, eventRetentionDays, " +
                    "gmailPushEnabled, pubsubTopicName, pubsubSubscriptionName, updatedAtMs) " +
                    "VALUES(1, 0, 'default', 1, 1, 0, 0, 1, 0, '[]', 30, 90, 0, '', '', 1000)",
            )
            close()
        }

        helper.runMigrationsAndValidate(DB_NAME, 12, true, SidekickDatabase.MIGRATION_11_12).apply {
            query("SELECT remoteEnableViaEmailEnabled, authorizedRemoteEnableSendersJson FROM app_settings WHERE id = 1").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
                assertEquals("[]", cursor.getString(1))
            }
            close()
        }
    }

    private companion object {
        const val DB_NAME = "migration-11-12"
    }
}
