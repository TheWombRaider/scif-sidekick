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
class Migration15To16InstrumentedTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SidekickDatabase::class.java)

    @Test
    fun migrationAddsStatusCommandDefaultsWithoutInheritingOtherAuthorizations() {
        helper.createDatabase(DB_NAME, 15).apply {
            execSQL(
                "INSERT INTO app_settings(id, appLockEnabled, fontScaleKey, duplicateSuppressionEnabled, " +
                    "duplicateWindowMinutes, softEmailPerMinuteCap, softSmsPerMinuteCap, retryOnNetworkReconnect, " +
                    "composeViaEmailEnabled, authorizedComposeSendersJson, messageRetentionDays, eventRetentionDays, " +
                    "gmailPushEnabled, pubsubTopicName, pubsubSubscriptionName, remoteEnableViaEmailEnabled, " +
                    "authorizedRemoteEnableSendersJson, remoteDisableViaEmailEnabled, " +
                    "authorizedRemoteDisableSendersJson, replyConfirmationsEnabled, heartbeatEnabled, " +
                    "heartbeatIntervalHours, heartbeatRecipientsJson, updatedAtMs) " +
                    "VALUES(1, 0, 'default', 1, 1, 0, 0, 1, 0, '[]', 30, 90, 0, '', '', 1, " +
                    "'[\"owner@example.com\"]', 1, '[\"owner@example.com\"]', 1, 0, 24, '[]', 1000)",
            )
            close()
        }

        helper.runMigrationsAndValidate(DB_NAME, 16, true, SidekickDatabase.MIGRATION_15_16).apply {
            query(
                "SELECT remoteStatusViaEmailEnabled, authorizedRemoteStatusSendersJson FROM app_settings WHERE id = 1",
            ).use { cursor ->
                cursor.moveToFirst()
                // Off and empty even though this row is already authorized for BOTH master-switch
                // commands. Being trusted to flip forwarding is not the same as being trusted to
                // ask what it's doing, and upgrading must not silently decide otherwise.
                assertEquals(0, cursor.getInt(0))
                assertEquals("[]", cursor.getString(1))
            }
            close()
        }
    }

    private companion object {
        const val DB_NAME = "migration-15-16"
    }
}
