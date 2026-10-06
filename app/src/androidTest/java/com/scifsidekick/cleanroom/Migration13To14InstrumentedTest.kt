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
class Migration13To14InstrumentedTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SidekickDatabase::class.java)

    @Test
    fun migrationTurnsReplyConfirmationsOnForUpgradingInstalls() {
        helper.createDatabase(DB_NAME, 13).apply {
            execSQL(
                "INSERT INTO app_settings(id, appLockEnabled, fontScaleKey, duplicateSuppressionEnabled, " +
                    "duplicateWindowMinutes, softEmailPerMinuteCap, softSmsPerMinuteCap, retryOnNetworkReconnect, " +
                    "composeViaEmailEnabled, authorizedComposeSendersJson, messageRetentionDays, eventRetentionDays, " +
                    "gmailPushEnabled, pubsubTopicName, pubsubSubscriptionName, remoteEnableViaEmailEnabled, " +
                    "authorizedRemoteEnableSendersJson, remoteDisableViaEmailEnabled, " +
                    "authorizedRemoteDisableSendersJson, updatedAtMs) " +
                    "VALUES(1, 0, 'default', 1, 1, 0, 0, 1, 0, '[]', 30, 90, 0, '', '', 0, '[]', 0, '[]', 1000)",
            )
            close()
        }

        helper.runMigrationsAndValidate(DB_NAME, 14, true, SidekickDatabase.MIGRATION_13_14).apply {
            query("SELECT replyConfirmationsEnabled FROM app_settings WHERE id = 1").use { cursor ->
                cursor.moveToFirst()
                // Deliberately 1, not 0: this one grants nobody anything, and an upgrading install
                // is better served knowing its replies landed than having to find the switch first.
                assertEquals(1, cursor.getInt(0))
            }
            close()
        }
    }

    private companion object {
        const val DB_NAME = "migration-13-14"
    }
}
