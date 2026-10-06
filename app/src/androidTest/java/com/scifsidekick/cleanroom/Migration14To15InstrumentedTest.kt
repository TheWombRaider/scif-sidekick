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
class Migration14To15InstrumentedTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SidekickDatabase::class.java)

    @Test
    fun migrationAddsHeartbeatDefaultsAcrossBothTables() {
        helper.createDatabase(DB_NAME, 14).apply {
            execSQL(
                "INSERT INTO app_settings(id, appLockEnabled, fontScaleKey, duplicateSuppressionEnabled, " +
                    "duplicateWindowMinutes, softEmailPerMinuteCap, softSmsPerMinuteCap, retryOnNetworkReconnect, " +
                    "composeViaEmailEnabled, authorizedComposeSendersJson, messageRetentionDays, eventRetentionDays, " +
                    "gmailPushEnabled, pubsubTopicName, pubsubSubscriptionName, remoteEnableViaEmailEnabled, " +
                    "authorizedRemoteEnableSendersJson, remoteDisableViaEmailEnabled, " +
                    "authorizedRemoteDisableSendersJson, replyConfirmationsEnabled, updatedAtMs) " +
                    "VALUES(1, 0, 'default', 1, 1, 0, 0, 1, 0, '[]', 30, 90, 0, '', '', 0, '[]', 0, '[]', 1, 1000)",
            )
            close()
        }

        helper.runMigrationsAndValidate(DB_NAME, 15, true, SidekickDatabase.MIGRATION_14_15).apply {
            query("SELECT heartbeatEnabled, heartbeatIntervalHours, heartbeatRecipientsJson FROM app_settings WHERE id = 1")
                .use { cursor ->
                    cursor.moveToFirst()
                    assertEquals(0, cursor.getInt(0))
                    assertEquals(24, cursor.getInt(1))
                    assertEquals("[]", cursor.getString(2))
                }
            // The marker lives on forwarding_state, not app_settings, so it stays out of
            // backup/restore -- a restored backup must not be able to convince the app it already
            // sent today's heartbeat. 0 means "never sent", which makes the first check send one.
            query("SELECT lastHeartbeatEmailMs FROM forwarding_state WHERE id = 1").use { cursor ->
                if (cursor.moveToFirst()) assertEquals(0L, cursor.getLong(0))
            }
            close()
        }
    }

    private companion object {
        const val DB_NAME = "migration-14-15"
    }
}
