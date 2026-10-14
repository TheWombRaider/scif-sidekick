package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.service.BatteryReading
import com.scifsidekick.cleanroom.service.ChargeState
import com.scifsidekick.cleanroom.service.NetworkKind
import com.scifsidekick.cleanroom.service.PhoneSnapshot
import com.scifsidekick.cleanroom.service.PlugType
import com.scifsidekick.cleanroom.service.formatPhoneHealth
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneHealthFormatTest {
    private val healthy =
        PhoneSnapshot(
            battery = BatteryReading(63, PlugType.AC, ChargeState.CHARGING, "GOOD", 31.0f),
            powerSaver = false,
            dozing = false,
            batteryOptimizationExempt = true,
            backgroundRestricted = false,
            network = NetworkKind.WIFI,
            networkValidated = true,
            airplaneMode = false,
            simState = "ready",
            freeStorageBytes = 41_200_000_000L,
            uptimeMs = 3L * 60 * 60_000,
            missingPermissions = emptyList(),
        )

    @Test fun `a healthy phone prints every normal line and no abnormal ones`() {
        val text = formatPhoneHealth(healthy)
        assertTrue(text.contains("Battery: 63%, charging (AC)"))
        assertTrue(text.contains("Power saver: off"))
        assertTrue(text.contains("Battery optimization: exempt"))
        assertTrue(text.contains("Network: Wi-Fi, internet validated"))
        assertTrue(text.contains("Airplane mode: off"))
        assertTrue(text.contains("SIM: ready"))
        assertTrue(text.contains("Free storage: 41.2 GB"))
        assertTrue(text.contains("Last reboot: 3 hour(s) ago"))
        assertFalse(text.contains("Battery health"))
        assertFalse(text.contains("Missing permissions"))
    }

    @Test fun `every unreadable value prints unknown`() {
        val text = formatPhoneHealth(PhoneSnapshot())
        assertTrue(text.contains("Battery: unknown"))
        assertTrue(text.contains("Power saver: unknown"))
        assertTrue(text.contains("Network: unknown"))
        assertTrue(text.contains("SIM: unknown"))
        assertTrue(text.contains("Free storage: unknown"))
        assertTrue(text.contains("Last reboot: unknown"))
        assertTrue(text.contains("Permissions: unknown"))
    }

    @Test fun `on battery says so`() {
        val text = formatPhoneHealth(healthy.copy(battery = BatteryReading(12, PlugType.NONE, ChargeState.DISCHARGING, "GOOD", 30f)))
        assertTrue(text.contains("Battery: 12%, on battery, not charging"))
    }

    @Test fun `plugged in but not charging is called out`() {
        val text = formatPhoneHealth(healthy.copy(battery = BatteryReading(12, PlugType.USB, ChargeState.DISCHARGING, "GOOD", 30f)))
        assertTrue(text.contains("Battery: 12%, plugged in (USB), NOT charging"))
    }

    @Test fun `a full battery on the charger reads full`() {
        val text = formatPhoneHealth(healthy.copy(battery = BatteryReading(100, PlugType.WIRELESS, ChargeState.FULL, "GOOD", 30f)))
        assertTrue(text.contains("Battery: 100%, full, plugged in (wireless)"))
    }

    @Test fun `abnormal health or heat adds a battery health line`() {
        val overheated = formatPhoneHealth(healthy.copy(battery = BatteryReading(63, PlugType.AC, ChargeState.CHARGING, "OVERHEAT", 47.5f)))
        assertTrue(overheated.contains("Battery health: OVERHEAT, 47.5 C"))
        val hot = formatPhoneHealth(healthy.copy(battery = BatteryReading(63, PlugType.AC, ChargeState.CHARGING, "GOOD", 46.0f)))
        assertTrue(hot.contains("Battery health: GOOD, 46.0 C"))
    }

    @Test fun `trouble states are spelled out`() {
        val text =
            formatPhoneHealth(
                healthy.copy(
                    powerSaver = true,
                    dozing = true,
                    batteryOptimizationExempt = false,
                    backgroundRestricted = true,
                    network = NetworkKind.NONE,
                    airplaneMode = true,
                    simState = "absent",
                    missingPermissions = listOf("SMS receive", "Notifications"),
                ),
            )
        assertTrue(text.contains("Power saver: ON"))
        assertTrue(text.contains("Doze (deep sleep): yes"))
        assertTrue(text.contains("Battery optimization: NOT exempt"))
        assertTrue(text.contains("Background restricted: YES"))
        assertTrue(text.contains("Network: none"))
        assertTrue(text.contains("Airplane mode: ON"))
        assertTrue(text.contains("SIM: absent"))
        assertTrue(text.contains("Missing permissions: SMS receive, Notifications"))
    }

    @Test fun `a network that is not validated is flagged`() {
        val text = formatPhoneHealth(healthy.copy(network = NetworkKind.CELLULAR, networkValidated = false))
        assertTrue(text.contains("Network: cellular, internet NOT validated"))
    }

    @Test fun `reboot time scales from minutes to days`() {
        assertTrue(formatPhoneHealth(healthy.copy(uptimeMs = 5 * 60_000L)).contains("Last reboot: 5 minute(s) ago"))
        assertTrue(formatPhoneHealth(healthy.copy(uptimeMs = 72L * 60 * 60_000)).contains("Last reboot: 3 day(s) ago"))
    }

    @Test fun `charging is judged by what the battery is doing not by the plug`() {
        fun charging(
            plug: PlugType,
            state: ChargeState,
        ) = BatteryReading(50, plug, state, "GOOD", 30f).isCharging
        assertTrue(charging(PlugType.AC, ChargeState.CHARGING))
        assertTrue(charging(PlugType.AC, ChargeState.FULL))
        assertFalse(charging(PlugType.USB, ChargeState.DISCHARGING))
        assertFalse(charging(PlugType.USB, ChargeState.NOT_CHARGING))
        assertFalse(charging(PlugType.NONE, ChargeState.DISCHARGING))
        // The state could not be read, so the plug is the only evidence left.
        assertTrue(charging(PlugType.AC, ChargeState.UNKNOWN))
        assertFalse(charging(PlugType.NONE, ChargeState.UNKNOWN))
    }

    @Test fun `the block carries nothing identifying`() {
        val text = formatPhoneHealth(healthy).lowercase()
        listOf("ssid", "imei", "carrier", "latitude", "longitude").forEach { assertFalse(text.contains(it)) }
    }
}
