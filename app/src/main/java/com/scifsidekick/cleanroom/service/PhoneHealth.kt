package com.scifsidekick.cleanroom.service

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * The phone side of a status report.
 *
 * Everything else in a report describes the app. None of it matters if the phone itself is dead,
 * asleep, offline or has had the app's background rights taken away, and a phone locked in a
 * different room gives nobody a chance to notice. These lines are the ones that decide whether
 * mail and texts can move at all.
 *
 * Deliberately absent: Wi-Fi network names, location, cell tower, carrier and device identifiers.
 * A report leaves the building by email, so it carries state, never identity or whereabouts.
 */
enum class PlugType { NONE, AC, USB, WIRELESS, OTHER }

enum class ChargeState { CHARGING, DISCHARGING, FULL, NOT_CHARGING, UNKNOWN }

enum class NetworkKind { WIFI, CELLULAR, ETHERNET, OTHER, NONE }

data class BatteryReading(
    val percent: Int,
    val plug: PlugType,
    val chargeState: ChargeState,
    /** "GOOD", "OVERHEAT", "DEAD" and so on; null when Android did not say. */
    val healthLabel: String?,
    val temperatureC: Float?,
) {
    /**
     * Whether the battery is actually gaining charge. A plug alone is not proof: a loose or dead
     * charger leaves the level falling, and that is exactly the phone an alert has to reach. The
     * plug is the evidence only when the charge state itself could not be read.
     */
    val isCharging: Boolean
        get() =
            when (chargeState) {
                ChargeState.CHARGING, ChargeState.FULL -> true
                ChargeState.NOT_CHARGING, ChargeState.DISCHARGING -> false
                ChargeState.UNKNOWN -> plug != PlugType.NONE
            }
}

/** Every field is null when it could not be read; the formatter prints that as `unknown`. */
data class PhoneSnapshot(
    val battery: BatteryReading? = null,
    val powerSaver: Boolean? = null,
    val dozing: Boolean? = null,
    val batteryOptimizationExempt: Boolean? = null,
    val backgroundRestricted: Boolean? = null,
    val network: NetworkKind? = null,
    val networkValidated: Boolean? = null,
    val airplaneMode: Boolean? = null,
    val simState: String? = null,
    val freeStorageBytes: Long? = null,
    val uptimeMs: Long? = null,
    val missingPermissions: List<String>? = null,
)

private const val ABNORMAL_TEMPERATURE_C = 45f

fun formatPhoneHealth(snapshot: PhoneSnapshot): String =
    buildString {
        appendLine(batteryLine(snapshot.battery))
        batteryHealthLine(snapshot.battery)?.let { appendLine(it) }
        appendLine("Power saver: ${flag(snapshot.powerSaver, "ON", "off")}")
        appendLine("Doze (deep sleep): ${flag(snapshot.dozing, "yes", "no")}")
        appendLine(
            "Battery optimization: " +
                when (snapshot.batteryOptimizationExempt) {
                    true -> "exempt"
                    false -> "NOT exempt -- Android may stop the service"
                    null -> "unknown"
                },
        )
        appendLine("Background restricted: ${flag(snapshot.backgroundRestricted, "YES", "no")}")
        appendLine(networkLine(snapshot.network, snapshot.networkValidated))
        appendLine("Airplane mode: ${flag(snapshot.airplaneMode, "ON", "off")}")
        appendLine("SIM: ${snapshot.simState ?: "unknown"}")
        appendLine("Free storage: ${snapshot.freeStorageBytes?.let(::storageText) ?: "unknown"}")
        appendLine("Last reboot: ${snapshot.uptimeMs?.let(::agoText) ?: "unknown"}")
        val missing = snapshot.missingPermissions
        when {
            missing == null -> appendLine("Permissions: unknown")
            missing.isNotEmpty() -> appendLine("Missing permissions: ${missing.joinToString(", ")}")
        }
    }.trimEnd()

private fun flag(
    value: Boolean?,
    whenTrue: String,
    whenFalse: String,
): String =
    when (value) {
        true -> whenTrue
        false -> whenFalse
        null -> "unknown"
    }

private fun batteryLine(battery: BatteryReading?): String {
    if (battery == null) return "Battery: unknown"
    val source =
        when (battery.plug) {
            PlugType.AC -> "AC"
            PlugType.USB -> "USB"
            PlugType.WIRELESS -> "wireless"
            PlugType.OTHER -> "dock"
            PlugType.NONE -> null
        }
    val state =
        when {
            source != null && battery.chargeState == ChargeState.FULL -> "full, plugged in ($source)"
            source != null && !battery.isCharging -> "plugged in ($source), NOT charging"
            source != null -> "charging ($source)"
            battery.isCharging -> "charging"
            else -> "on battery, not charging"
        }
    return "Battery: ${battery.percent}%, $state"
}

/** Only worth a line when something is wrong: a bad health state, or a battery running hot. */
private fun batteryHealthLine(battery: BatteryReading?): String? {
    if (battery == null) return null
    val temperature = battery.temperatureC
    val badHealth = battery.healthLabel != null && battery.healthLabel != "GOOD"
    val hot = temperature != null && temperature >= ABNORMAL_TEMPERATURE_C
    if (!badHealth && !hot) return null
    val temperatureText = temperature?.let { ", ${String.format(Locale.US, "%.1f", it)} C" }.orEmpty()
    return "Battery health: ${battery.healthLabel ?: "unknown"}$temperatureText"
}

private fun networkLine(
    network: NetworkKind?,
    validated: Boolean?,
): String {
    if (network == null) return "Network: unknown"
    val kind =
        when (network) {
            NetworkKind.WIFI -> "Wi-Fi"
            NetworkKind.CELLULAR -> "cellular"
            NetworkKind.ETHERNET -> "ethernet"
            NetworkKind.OTHER -> "other"
            NetworkKind.NONE -> return "Network: none"
        }
    val internet =
        when (validated) {
            true -> ", internet validated"
            false -> ", internet NOT validated"
            null -> ""
        }
    return "Network: $kind$internet"
}

private fun storageText(bytes: Long): String =
    if (bytes >= 1_000_000_000L) {
        String.format(Locale.US, "%.1f GB", bytes / 1e9)
    } else {
        "${bytes / 1_000_000L} MB"
    }

private fun agoText(ms: Long): String {
    val minutes = ms / 60_000L
    return when {
        minutes < 120 -> "$minutes minute(s) ago"
        minutes < 48 * 60 -> "${minutes / 60} hour(s) ago"
        else -> "${minutes / (24 * 60)} day(s) ago"
    }
}

/** The battery as Android last broadcast it (a sticky intent, so no receiver is registered). */
fun readBatteryReading(context: Context): BatteryReading? =
    runCatching {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val plug =
            when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
                0 -> PlugType.NONE
                BatteryManager.BATTERY_PLUGGED_AC -> PlugType.AC
                BatteryManager.BATTERY_PLUGGED_USB -> PlugType.USB
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> PlugType.WIRELESS
                else -> PlugType.OTHER
            }
        val chargeState =
            when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                BatteryManager.BATTERY_STATUS_CHARGING -> ChargeState.CHARGING
                BatteryManager.BATTERY_STATUS_DISCHARGING -> ChargeState.DISCHARGING
                BatteryManager.BATTERY_STATUS_FULL -> ChargeState.FULL
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> ChargeState.NOT_CHARGING
                else -> ChargeState.UNKNOWN
            }
        val health =
            when (intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
                BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER_VOLTAGE"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "FAILURE"
                BatteryManager.BATTERY_HEALTH_COLD -> "COLD"
                else -> null
            }
        val tenthsC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        BatteryReading(
            percent = (level * 100 / scale).coerceIn(0, 100),
            plug = plug,
            chargeState = chargeState,
            healthLabel = health,
            temperatureC = if (tenthsC == Int.MIN_VALUE) null else tenthsC / 10f,
        )
    }.getOrNull()

/** Reads every field independently, so one failing system call costs one `unknown`, not the report. */
fun readPhoneSnapshot(context: Context): PhoneSnapshot {
    val power = context.getSystemService(PowerManager::class.java)
    // A failed read leaves networkRead failed (shown as unknown); no active network reads as none.
    val networkRead =
        runCatching {
            val connectivity = requireNotNull(context.getSystemService(ConnectivityManager::class.java))
            connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        }
    val capabilities = networkRead.getOrNull()
    return PhoneSnapshot(
        battery = readBatteryReading(context),
        powerSaver = runCatching { power?.isPowerSaveMode }.getOrNull(),
        dozing = runCatching { power?.isDeviceIdleMode }.getOrNull(),
        batteryOptimizationExempt =
            runCatching { power?.isIgnoringBatteryOptimizations(context.packageName) }.getOrNull(),
        backgroundRestricted =
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted
                } else {
                    false
                }
            }.getOrNull(),
        network =
            when {
                networkRead.isFailure -> null
                capabilities == null -> NetworkKind.NONE
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.WIFI
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.CELLULAR
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.ETHERNET
                else -> NetworkKind.OTHER
            },
        networkValidated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        airplaneMode =
            runCatching {
                Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
            }.getOrNull(),
        simState = runCatching { simStateText(context.getSystemService(TelephonyManager::class.java)?.simState) }.getOrNull(),
        freeStorageBytes = runCatching { StatFs(context.filesDir.path).availableBytes }.getOrNull(),
        uptimeMs = runCatching { SystemClock.elapsedRealtime() }.getOrNull(),
        missingPermissions = runCatching { missingPermissions(context) }.getOrNull(),
    )
}

private fun simStateText(state: Int?): String? =
    when (state) {
        null -> null
        TelephonyManager.SIM_STATE_READY -> "ready"
        TelephonyManager.SIM_STATE_ABSENT -> "absent"
        TelephonyManager.SIM_STATE_PIN_REQUIRED,
        TelephonyManager.SIM_STATE_PUK_REQUIRED,
        TelephonyManager.SIM_STATE_NETWORK_LOCKED,
        -> "locked"
        TelephonyManager.SIM_STATE_NOT_READY -> "not ready"
        TelephonyManager.SIM_STATE_PERM_DISABLED -> "disabled"
        TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "error"
        TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "restricted"
        else -> "unknown"
    }

/** The two grants whose loss silently stops the app: receiving texts, and posting its own alerts. */
private fun missingPermissions(context: Context): List<String> =
    buildList {
        if (context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            add("SMS receive")
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            add("Notifications")
        }
    }
