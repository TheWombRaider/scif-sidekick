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
class Migration16To17InstrumentedTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SidekickDatabase::class.java)

    @Test
    fun migrationLeavesOutboundSimOnTheSystemDefault() {
        helper.createDatabase(DB_NAME, 16).apply {
            execSQL(
                "INSERT INTO app_settings(id, appLockEnabled, fontScaleKey, duplicateSuppressionEnabled, " +
                    "duplicateWindowMinutes, softEmailPerMinuteCap, softSmsPerMinuteCap, retryOnNetworkReconnect, " +
                    "composeViaEmailEnabled, authorizedComposeSendersJson, messageRetentionDays, eventRetentionDays, " +
                    "gmailPushEnabled, pubsubTopicName, pubsubSubscriptionName, remoteEnableViaEmailEnabled, " +
                    "authorizedRemoteEnableSendersJson, remoteDisableViaEmailEnabled, " +
                    "authorizedRemoteDisableSendersJson, replyConfirmationsEnabled, heartbeatEnabled, " +
                    "heartbeatIntervalHours, heartbeatRecipientsJson, remoteStatusViaEmailEnabled, " +
                    "authorizedRemoteStatusSendersJson, updatedAtMs) " +
                    "VALUES(1, 0, 'default', 1, 1, 0, 0, 1, 0, '[]', 30, 90, 0, '', '', 0, '[]', 0, '[]', 1, 0, " +
                    "24, '[]', 0, '[]', 1000)",
            )
            close()
        }

        helper.runMigrationsAndValidate(DB_NAME, 17, true, SidekickDatabase.MIGRATION_16_17).apply {
            query("SELECT outboundSubscriptionId FROM app_settings WHERE id = 1").use { cursor ->
                cursor.moveToFirst()
                // -1 is SimSelection.SYSTEM_DEFAULT, which is not a new behavior to opt out of --
                // it IS what every release before this one did unconditionally. An upgrading
                // install must keep sending on exactly the line it used yesterday.
                assertEquals(-1, cursor.getInt(0))
            }
            close()
        }
    }

    private companion object {
        const val DB_NAME = "migration-16-17"
    }
}
