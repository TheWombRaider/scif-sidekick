package com.scifsidekick.cleanroom.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/** Clipboard handling for the short-lived Microsoft sign-in code. Never throws. */
internal object SignInCodeClipboard {
    /** Android 13+ shows its own "copied" confirmation, so the app adds one only below that. */
    val systemConfirmsCopy: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** Copies [code], marked sensitive so Android 13+ doesn't preview it. Returns whether it was copied. */
    fun copy(
        context: Context,
        code: String,
    ): Boolean =
        runCatching {
            val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
            val clip = ClipData.newPlainText("Microsoft sign-in code", code)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
            }
            clipboard.setPrimaryClip(clip)
        }.isSuccess

    /**
     * Clears the clipboard when its current text is exactly [code] (API 28+). Returns true when the
     * clipboard could be read (whether or not it held the code), false when it could not (no focus,
     * empty, an older API, or any failure), so the caller can try again later.
     */
    fun clearIfCurrent(
        context: Context,
        code: String,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return runCatching {
            val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
            val clip = clipboard.primaryClip ?: return false
            val text = if (clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
            if (text == code) clipboard.clearPrimaryClip()
            true
        }.getOrDefault(false)
    }
}
