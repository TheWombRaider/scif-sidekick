package com.scifsidekick.cleanroom

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scifsidekick.cleanroom.data.SidekickDatabase
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration17To18InstrumentedTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SidekickDatabase::class.java)

    private fun insertV17Row(
        composeEnabled: Int = 0,
        composeSenders: String = "[]",
        remoteEnableEnabled: Int = 0,
        remoteEnableSenders: String = "[]",
        remoteDisableEnabled: Int = 0,
        remoteDisableSenders: String = "[]",
        remoteStatusEnabled: Int = 0,
        remoteStatusSenders: String = "[]",
    ) {
        helper.createDatabase(DB_NAME, 17).apply {
            execSQL(
                "INSERT INTO app_settings(id, appLockEnabled, fontScaleKey, duplicateSuppressionEnabled, " +
                    "duplicateWindowMinutes, softEmailPerMinuteCap, softSmsPerMinuteCap, retryOnNetworkReconnect, " +
                    "composeViaEmailEnabled, authorizedComposeSendersJson, messageRetentionDays, eventRetentionDays, " +
                    "gmailPushEnabled, pubsubTopicName, pubsubSubscriptionName, remoteEnableViaEmailEnabled, " +
                    "authorizedRemoteEnableSendersJson, remoteDisableViaEmailEnabled, " +
                    "authorizedRemoteDisableSendersJson, replyConfirmationsEnabled, heartbeatEnabled, " +
                    "heartbeatIntervalHours, heartbeatRecipientsJson, remoteStatusViaEmailEnabled, " +
                    "authorizedRemoteStatusSendersJson, outboundSubscriptionId, updatedAtMs) " +
                    "VALUES(1, 0, 'default', 1, 1, 0, 0, 1, " +
                    "$composeEnabled, '$composeSenders', 30, 90, 0, '', '', " +
                    "$remoteEnableEnabled, '$remoteEnableSenders', $remoteDisableEnabled, '$remoteDisableSenders', " +
                    "1, 0, 24, '[]', $remoteStatusEnabled, '$remoteStatusSenders', -1, 1000)",
            )
            close()
        }
    }

    @Test
    fun freshInstallDefaultsToOnWithAnEmptyList() {
        insertV17Row()
        helper.runMigrationsAndValidate(DB_NAME, 18, true, SidekickDatabase.MIGRATION_17_18).apply {
            query("SELECT remoteControlEnabled, remoteControlSendersJson FROM app_settings WHERE id = 1").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
                assertEquals("[]", cursor.getString(1))
            }
            close()
        }
    }

    @Test
    fun upgradingInstallCarriesForwardOnlyWhatWasAlreadyAuthorized() {
        insertV17Row(
            composeEnabled = 1,
            composeSenders = "[\"a@example.com\",\"b@example.com\"]",
            remoteEnableEnabled = 0,
            remoteEnableSenders = "[\"unused@example.com\"]",
            remoteDisableEnabled = 1,
            remoteDisableSenders = "[\"b@example.com\"]",
            remoteStatusEnabled = 1,
            remoteStatusSenders = "[\"a@example.com\"]",
        )
        helper.runMigrationsAndValidate(DB_NAME, 18, true, SidekickDatabase.MIGRATION_17_18).apply {
            query("SELECT remoteControlEnabled, remoteControlSendersJson FROM app_settings WHERE id = 1").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
                val senders = JSONArray(cursor.getString(1))
                assertEquals(2, senders.length())
                val byAddress = (0 until senders.length()).associate { i -> senders.getJSONObject(i).getString("address") to senders.getJSONObject(i) }
                // a@example.com: compose (on) + status (on), never enable (its list carried it but
                // the enable toggle itself was off) or disable (never listed there at all).
                assertTrue(byAddress.getValue("a@example.com").getBoolean("canCompose"))
                assertTrue(byAddress.getValue("a@example.com").getBoolean("canStatus"))
                assertEquals(false, byAddress.getValue("a@example.com").getBoolean("canEnable"))
                assertEquals(false, byAddress.getValue("a@example.com").getBoolean("canDisable"))
                // b@example.com: compose (on) + disable (on), merged from two different old lists
                // into one row.
                assertTrue(byAddress.getValue("b@example.com").getBoolean("canCompose"))
                assertTrue(byAddress.getValue("b@example.com").getBoolean("canDisable"))
                assertEquals(false, byAddress.getValue("b@example.com").getBoolean("canEnable"))
                assertEquals(false, byAddress.getValue("b@example.com").getBoolean("canStatus"))
                // unused@example.com was only ever in the remote-enable list, and that toggle was
                // off -- it must not appear at all, let alone with any capability granted.
                assertTrue("unused@example.com" !in byAddress)
            }
            close()
        }
    }

    @Test
    fun aToggleLeftOffNeverCarriesForwardItsOwnListEvenIfPopulated() {
        insertV17Row(remoteEnableEnabled = 0, remoteEnableSenders = "[\"owner@example.com\"]")
        helper.runMigrationsAndValidate(DB_NAME, 18, true, SidekickDatabase.MIGRATION_17_18).apply {
            query("SELECT remoteControlSendersJson FROM app_settings WHERE id = 1").use { cursor ->
                cursor.moveToFirst()
                assertEquals("[]", cursor.getString(0))
            }
            close()
        }
    }

    private companion object {
        const val DB_NAME = "migration-17-18"
    }
}
