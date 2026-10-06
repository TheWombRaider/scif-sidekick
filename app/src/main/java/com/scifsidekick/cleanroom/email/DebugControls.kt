package com.scifsidekick.cleanroom.email

import android.content.Context
import com.scifsidekick.cleanroom.BuildConfig

class DebugControls(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("debug_controls", Context.MODE_PRIVATE)

    var fakeEmailTransport: Boolean
        get() = BuildConfig.DEBUG && prefs.getBoolean("fake_email", false)
        set(value) {
            if (BuildConfig.DEBUG) prefs.edit().putBoolean("fake_email", value).apply()
        }

    // apply(), not commit(): SharedPreferences.Editor updates the in-memory cache synchronously
    // before returning, so a get() immediately after still sees this write -- @Synchronized is
    // what actually guards consumeForcedFailure's read-decrement-write against a concurrent
    // caller, not which of apply()/commit() is used. commit() bought nothing here except blocking
    // the calling thread (the UI thread, for failNextEmailAttempts) on a synchronous disk write.
    @Synchronized
    fun failNextEmailAttempts(count: Int) {
        if (BuildConfig.DEBUG) prefs.edit().putInt("fail_next", count.coerceAtLeast(0)).apply()
    }

    @Synchronized
    fun consumeForcedFailure(): Boolean {
        if (!BuildConfig.DEBUG) return false
        val remaining = prefs.getInt("fail_next", 0)
        if (remaining <= 0) return false
        prefs.edit().putInt("fail_next", remaining - 1).apply()
        return true
    }
}
