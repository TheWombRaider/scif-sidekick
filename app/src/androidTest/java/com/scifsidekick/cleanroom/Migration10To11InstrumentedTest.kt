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
class Migration10To11InstrumentedTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SidekickDatabase::class.java)

    @Test
    fun migrationAddsGmailPushDefaultsWithoutTouchingExistingRows() {
        helper.createDatabase(DB_NAME, 10).apply {
            execSQL(
                "INSERT INTO forwarding_state(id, enabled, watermarkMs, destinationEmail, emailCircuitOpen, " +
                    "consecutiveEmailFailures, mmsForwardingEnabled, callNotificationsEnabled, contactFilterMode, " +
                    "contactFilterNumbersJson, updatedAtMs, lastHeartbeatMs, snoozedUntilMs) " +
                    "VALUES(1, 1, 1000, '', 0, 0, 1, 1, 'OFF', '[]', 1000, 0, 0)",
            )
            execSQL(
                "INSERT INTO app_settings(id, appLockEnabled, fontScaleKey, duplicateSuppressionEnabled, " +
                    "duplicateWindowMinutes, softEmailPerMinuteCap, softSmsPerMinuteCap, retryOnNetworkReconnect, " +
                    "composeViaEmailEnabled, authorizedComposeSendersJson, messageRetentionDays, eventRetentionDays, " +
                    "updatedAtMs) VALUES(1, 0, 'default', 1, 1, 0, 0, 1, 0, '[]', 30, 90, 1000)",
            )
            close()
        }

        helper.runMigrationsAndValidate(DB_NAME, 11, true, SidekickDatabase.MIGRATION_10_11).apply {
            query("SELECT gmailWatchExpirationMs FROM forwarding_state WHERE id = 1").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0L, cursor.getLong(0))
            }
            query("SELECT gmailPushEnabled, pubsubTopicName, pubsubSubscriptionName FROM app_settings WHERE id = 1").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
                assertEquals("", cursor.getString(1))
                assertEquals("", cursor.getString(2))
            }
            close()
        }
    }

    private companion object {
        const val DB_NAME = "migration-10-11"
    }
}
