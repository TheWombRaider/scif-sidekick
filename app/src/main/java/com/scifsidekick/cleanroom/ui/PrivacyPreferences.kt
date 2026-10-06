package com.scifsidekick.cleanroom.ui

import android.content.Context
import androidx.core.content.edit

/** Device-local display privacy. Kept in SharedPreferences, not Room: it must be readable before
 *  the first frame (to set the window flag) and is never part of a backup. */
class PrivacyPreferences(
    context: Context,
) {
    private val preferences = context.getSharedPreferences("privacy_v1", Context.MODE_PRIVATE)

    /** Blocks screenshots and hides the app's content in the Recents screen (FLAG_SECURE). */
    var hideInRecents: Boolean
        get() = preferences.getBoolean(KEY_HIDE_IN_RECENTS, false)
        set(value) {
            preferences.edit { putBoolean(KEY_HIDE_IN_RECENTS, value) }
        }

    private companion object {
        const val KEY_HIDE_IN_RECENTS = "hide_in_recents"
    }
}
