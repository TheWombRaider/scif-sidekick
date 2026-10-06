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
class Migration9To10InstrumentedTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SidekickDatabase::class.java)

    @Test
    fun migrationAddsHeartbeatSnoozeAndRetentionDefaultsWithoutTouchingExistingRows() {
        helper.createDatabase(DB_NAME, 9).apply {
            execSQL(
                "INSERT INTO forwarding_state(id, enabled, watermarkMs, destinationEmail, emailCircuitOpen, " +
                    "consecutiveEmailFailures, mmsForwardingEnabled, callNotificationsEnabled, contactFilterMode, " +
                    "contactFilterNumbersJson, updatedAtMs) VALUES(1, 1, 1000, '', 0, 0, 1, 1, 'OFF', '[]', 1000)",
            )
            execSQL(
                "INSERT INTO app_settings(id, appLockEnabled, fontScaleKey, duplicateSuppressionEnabled, " +
                    "duplicateWindowMinutes, softEmailPerMinuteCap, softSmsPerMinuteCap, retryOnNetworkReconnect, " +
                    "composeViaEmailEnabled, authorizedComposeSendersJson, updatedAtMs) " +
                    "VALUES(1, 0, 'default', 1, 1, 0, 0, 1, 0, '[]', 1000)",
            )
            close()
        }

        helper.runMigrationsAndValidate(DB_NAME, 10, true, SidekickDatabase.MIGRATION_9_10).apply {
            query("SELECT lastHeartbeatMs, snoozedUntilMs FROM forwarding_state WHERE id = 1").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0L, cursor.getLong(0))
                assertEquals(0L, cursor.getLong(1))
            }
            query("SELECT messageRetentionDays, eventRetentionDays FROM app_settings WHERE id = 1").use { cursor ->
                cursor.moveToFirst()
                assertEquals(30, cursor.getInt(0))
                assertEquals(90, cursor.getInt(1))
            }
            close()
        }
    }

    private companion object {
        const val DB_NAME = "migration-9-10"
    }
}
