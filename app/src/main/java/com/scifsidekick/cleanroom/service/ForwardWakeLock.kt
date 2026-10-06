package com.scifsidekick.cleanroom.service

import android.content.Context
import android.os.PowerManager

/**
 * Keeps the CPU awake from the moment a message arrives until [ForwardingService] has run the
 * drain pass that picks it up. Without it, a screen-off phone can suspend as soon as the SMS
 * broadcast (or notification callback) returns, stalling the forward until something else wakes
 * the device. Always bounded by [MAX_HOLD_MS], so a missed release costs at most that much battery.
 */
object ForwardWakeLock {
    private const val MAX_HOLD_MS = 60_000L

    @Volatile private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var acquiredAtMs = 0L

    @Synchronized
    fun acquire(context: Context) {
        val lock =
            wakeLock ?: context.applicationContext
                .getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScifSidekick:forward")
                ?.apply { setReferenceCounted(false) }
                ?.also { wakeLock = it }
                ?: return
        acquiredAtMs = System.currentTimeMillis()
        lock.acquire(MAX_HOLD_MS)
    }

    /** Releases only if nothing re-acquired the lock after [passStartedAtMs] -- an arrival during
     *  the pass that just finished still needs the next pass to run before the CPU may sleep. */
    @Synchronized
    fun releaseIfAcquiredBefore(passStartedAtMs: Long) {
        val lock = wakeLock ?: return
        if (lock.isHeld && acquiredAtMs < passStartedAtMs) lock.release()
    }
}
