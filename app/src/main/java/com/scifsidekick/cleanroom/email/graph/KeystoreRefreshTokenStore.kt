package com.scifsidekick.cleanroom.email.graph

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Microsoft refresh token, encrypted with AES-256-GCM under a non-exportable Android Keystore key
 * ([KEY_ALIAS]) and kept in app-private SharedPreferences as `base64(iv) + "." + base64(ciphertext)`.
 * The Keystore picks a fresh 12-byte IV for every write. [clear] deletes both the value and the key.
 */
class KeystoreRefreshTokenStore(
    context: Context,
) : RefreshTokenStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized override fun read(): String? =
        try {
            decrypt()
        } catch (_: Exception) {
            // Missing key, tampered or malformed value (AEADBadTagException, IllegalArgumentException,
            // ProviderException, ...): there is no usable token.
            null
        }

    private fun decrypt(): String? {
        val stored = prefs.getString(PREF_KEY, null) ?: return null
        val parts = stored.split('.')
        if (parts.size != 2) return null
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        if (iv.size != IV_BYTES || ciphertext.isEmpty()) return null
        val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8).takeIf { it.isNotBlank() }
    }

    // commit(), not apply(), and its result is checked: the caller relies on the token being on disk.
    @SuppressLint("ApplySharedPref", "UseKtx")
    @Synchronized
    override fun write(token: String) {
        require(token.isNotBlank()) { "Refusing to store a blank token" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyForWrite())
        val iv = cipher.iv
        check(iv != null && iv.size == IV_BYTES) { "Unexpected IV from the Keystore" }
        val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val value = Base64.encodeToString(iv, Base64.NO_WRAP) + "." + Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        check(prefs.edit().putString(PREF_KEY, value).commit()) { "Could not save the Microsoft sign-in" }
    }

    @Synchronized override fun clear() {
        prefs.edit(commit = true) { remove(PREF_KEY) }
        try {
            keyStore().deleteEntry(KEY_ALIAS)
        } catch (_: Exception) {
            // Nothing to delete, or the Keystore is unavailable; the ciphertext is already gone.
        }
    }

    /**
     * The existing key, or a new one only when the alias is definitely absent. Any Keystore error
     * fails the write and leaves the alias alone: replacing a live key would orphan the ciphertext.
     */
    private fun keyForWrite(): SecretKey {
        val keyStore = keyStore()
        if (!keyStore.containsAlias(KEY_ALIAS)) return generateKey()
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw GeneralSecurityException("Keystore entry is not a secret key")
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        const val KEY_ALIAS = "scif_ms_refresh_v1"
        const val PREFS_NAME = "ms_account_v1"
        const val PREF_KEY = "rt"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}
