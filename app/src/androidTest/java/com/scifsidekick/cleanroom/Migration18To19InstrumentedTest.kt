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
class Migration18To19InstrumentedTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SidekickDatabase::class.java)

    @Test
    fun migrationLeavesBatteryAlertsOffWithTheDocumentedDefaults() {
        helper.createDatabase(DB_NAME, 18).apply {
            execSQL(
                "INSERT INTO app_settings(id, appLockEnabled, fontScaleKey, duplicateSuppressionEnabled, " +
                    "duplicateWindowMinutes, softEmailPerMinuteCap, softSmsPerMinuteCap, retryOnNetworkReconnect, " +
                    "composeViaEmailEnabled, authorizedComposeSendersJson, messageRetentionDays, eventRetentionDays, " +
                    "gmailPushEnabled, pubsubTopicName, pubsubSubscriptionName, remoteEnableViaEmailEnabled, " +
                    "authorizedRemoteEnableSendersJson, remoteDisableViaEmailEnabled, " +
                    "authorizedRemoteDisableSendersJson, replyConfirmationsEnabled, heartbeatEnabled, " +
                    "heartbeatIntervalHours, heartbeatRecipientsJson, remoteStatusViaEmailEnabled, " +
                    "authorizedRemoteStatusSendersJson, outboundSubscriptionId, remoteControlEnabled, " +
                    "remoteControlSendersJson, updatedAtMs) " +
                    "VALUES(1, 0, 'default', 1, 1, 0, 0, 1, 0, '[]', 30, 90, 0, '', '', 0, '[]', 0, '[]', 1, 1, " +
                    "12, '[\"owner@example.com\"]', 0, '[]', -1, 1, '[]', 1000)",
            )
            close()
        }

        helper.runMigrationsAndValidate(DB_NAME, 19, true, SidekickDatabase.MIGRATION_18_19).apply {
            query(
                "SELECT batteryAlertsEnabled, batteryAlertThresholdPercent, batteryAlertStepPercent, " +
                    "batteryAlertMaxCount, heartbeatIntervalHours, heartbeatRecipientsJson " +
                    "FROM app_settings WHERE id = 1",
            ).use { cursor ->
                cursor.moveToFirst()
                // Off, so upgrading sends nothing new to anybody.
                assertEquals(0, cursor.getInt(0))
                assertEquals(20, cursor.getInt(1))
                assertEquals(5, cursor.getInt(2))
                assertEquals(3, cursor.getInt(3))
                // What the install already had is untouched.
                assertEquals(12, cursor.getInt(4))
                assertEquals("[\"owner@example.com\"]", cursor.getString(5))
            }
            // The counter is state, not a setting. Preparing this query fails if the column is
            // missing, even with no forwarding_state row to read.
            query("SELECT batteryAlertsFired FROM forwarding_state WHERE id = 1").use { cursor ->
                if (cursor.moveToFirst()) assertEquals(0, cursor.getInt(0))
            }
            close()
        }
    }

    private companion object {
        const val DB_NAME = "migration-18-19"
    }
}
