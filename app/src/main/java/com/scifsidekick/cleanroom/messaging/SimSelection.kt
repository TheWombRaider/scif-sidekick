package com.scifsidekick.cleanroom.messaging

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/** One SIM the user could pick, as shown in Settings. */
data class SimOption(
    val subscriptionId: Int,
    val label: String,
)

/**
 * Chooses which SIM outbound texts leave on, on a dual-SIM device.
 *
 * Before this existed, [SmsGateway] and [MmsGateway] always used
 * [SubscriptionManager.getDefaultSmsSubscriptionId] -- which is correct on a single-SIM phone and
 * correct on a dual-SIM phone whose system default is the line you want, and simply unoverridable
 * otherwise.
 *
 * The whole design here is built around not turning a working send path into a broken one:
 *
 * - [SYSTEM_DEFAULT] is the default and reproduces the old behavior exactly, byte for byte. A user
 *   who never opens this setting is on precisely the code path that shipped before it.
 * - A specific subscription is honored **only when it can be positively confirmed to still be
 *   active right now**. Anything else -- the SIM pulled out, an eSIM profile deleted, the id
 *   belonging to a different device after a restore, `READ_PHONE_STATE` not granted, or the
 *   platform call throwing -- falls back to the system default rather than attempting a send on a
 *   subscription that may no longer exist.
 *
 * That inversion is the point. A missing permission or a removed SIM degrades to "behaves like it
 * did before", never to a failed text.
 */
object SimSelection {
    const val SYSTEM_DEFAULT = -1

    /**
     * The id to actually hand to telephony. Never throws: every failure path resolves to the
     * system default, which is what the app used unconditionally before this setting existed.
     */
    fun resolve(
        context: Context,
        configuredSubscriptionId: Int,
    ): Int {
        val systemDefault = SubscriptionManager.getDefaultSmsSubscriptionId()
        if (configuredSubscriptionId == SYSTEM_DEFAULT) return systemDefault
        val stillActive = activeSubscriptions(context).any { it.subscriptionId == configuredSubscriptionId }
        return if (stillActive) configuredSubscriptionId else systemDefault
    }

    /**
     * True when a configured choice cannot be honored and the system default is being used
     * instead, so callers can say so in the event log rather than silently sending on a different
     * line than the settings screen claims.
     */
    fun isFallingBack(
        context: Context,
        configuredSubscriptionId: Int,
    ): Boolean =
        configuredSubscriptionId != SYSTEM_DEFAULT &&
            activeSubscriptions(context).none { it.subscriptionId == configuredSubscriptionId }

    /**
     * Whether this phone has more than one usable SIM slot at all.
     *
     * Needs no permission, which is the point: it lets Settings hide the whole SIM picker on a
     * single-SIM device rather than showing a choice with one option and a permission prompt
     * attached to it. On the overwhelmingly common single-SIM phone, this feature is invisible.
     */
    fun deviceSupportsMultipleSims(context: Context): Boolean {
        val telephony = context.getSystemService(TelephonyManager::class.java) ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                telephony.activeModemCount > 1
            } else {
                @Suppress("DEPRECATION")
                telephony.phoneCount > 1
            }
        }.getOrDefault(false)
    }

    /**
     * The SIMs currently in the phone. Empty when `READ_PHONE_STATE` has not been granted, which
     * is the normal state -- the permission is requested only if the user actually opens the SIM
     * picker, never at first run, since everything else in the app works without it.
     */
    fun activeSubscriptions(context: Context): List<SimOption> {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val manager = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        // Documented to require READ_PHONE_STATE, but OEM builds have been known to throw here for
        // their own reasons; an exception must degrade to "no choice offered", not crash Settings.
        val infos = runCatching { manager.activeSubscriptionInfoList }.getOrNull().orEmpty()
        return infos.map { info ->
            val carrier = info.carrierName?.toString()?.takeIf { it.isNotBlank() } ?: "SIM"
            val number = info.number?.takeIf { it.isNotBlank() }
            val slot = info.simSlotIndex + 1
            SimOption(
                subscriptionId = info.subscriptionId,
                label = if (number != null) "$carrier — $number (slot $slot)" else "$carrier (slot $slot)",
            )
        }
    }
}
