package com.scifsidekick.cleanroom.email.graph

import android.content.Context
import androidx.core.content.edit

/**
 * Plain settings for the Microsoft account. No token is ever stored here (the encrypted refresh token
 * belongs to [KeystoreRefreshTokenStore], which shares this preferences file under its own key).
 */
class MsAccountPreferences(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences(KeystoreRefreshTokenStore.PREFS_NAME, Context.MODE_PRIVATE)

    /** The Azure app registration's Application (client) ID the user pasted in. */
    var clientId: String
        get() = prefs.getString(KEY_CLIENT_ID, null).orEmpty()
        set(value) = prefs.edit { putString(KEY_CLIENT_ID, value.trim()) }

    /** The signed-in address, cached for display and remote-control seeding. */
    var accountEmail: String?
        get() = prefs.getString(KEY_ACCOUNT_EMAIL, null)?.takeIf { it.isNotBlank() }
        set(value) {
            val trimmed = value?.trim()
            prefs.edit { if (trimmed.isNullOrEmpty()) remove(KEY_ACCOUNT_EMAIL) else putString(KEY_ACCOUNT_EMAIL, trimmed) }
        }

    /** Which provider the mail router tries first: [PROVIDER_GMAIL] (default) or [PROVIDER_GRAPH]. Anything else reads as Gmail. */
    var preferredProvider: String
        get() = normalizeProvider(prefs.getString(KEY_PREFERRED_PROVIDER, null))
        set(value) = prefs.edit { putString(KEY_PREFERRED_PROVIDER, normalizeProvider(value)) }

    /**
     * Forgets the account email; keeps [clientId] and [preferredProvider]. (The once-per-install
     * remote-control seeding flag lives with the repository's setup flags, so it survives this.)
     */
    fun clearAccount() {
        prefs.edit { remove(KEY_ACCOUNT_EMAIL) }
    }

    companion object {
        const val PROVIDER_GMAIL = "gmail"
        const val PROVIDER_GRAPH = "graph"

        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_ACCOUNT_EMAIL = "account_email"
        private const val KEY_PREFERRED_PROVIDER = "preferred_provider"

        fun normalizeProvider(value: String?): String = if (value == PROVIDER_GRAPH) PROVIDER_GRAPH else PROVIDER_GMAIL
    }
}
