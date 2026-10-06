package com.scifsidekick.cleanroom.util

import org.json.JSONObject
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Optional passphrase protection for exported backups (filters + app settings; never the Gmail
 * sign-in itself -- see [com.scifsidekick.cleanroom.ui.BackupRestoreScreenBody]'s own doc comment).
 * A backup leaving the device -- shared, uploaded, sitting in Downloads -- is no longer protected
 * by the device's own screen lock, so this is deliberately independent of Android Keystore: the
 * whole point is a secret only the person who typed it can supply again later, on this device or
 * any other.
 *
 * Envelope format is plain JSON (not a binary blob) so [isEncrypted] can cheaply distinguish an
 * encrypted backup from a plain-JSON one without needing a passphrase first, and so the file stays
 * inspectable/portable like the unencrypted export always has been. Every field but `v` is
 * base64-encoded raw bytes (`iter` is the PBKDF2 iteration count; backups written before it was
 * added have none and use [LEGACY_PBKDF2_ITERATIONS]):
 * ```
 * {"v":1,"scheme":"AES-256-GCM+PBKDF2WithHmacSHA256","iter":600000,"salt":"...","iv":"...","ciphertext":"..."}
 * ```
 */
object BackupCrypto {
    private const val VERSION = 1
    private const val SCHEME = "AES-256-GCM+PBKDF2WithHmacSHA256"
    private const val PBKDF2_ITERATIONS = 600_000
    private const val LEGACY_PBKDF2_ITERATIONS = 210_000
    private const val MAX_ACCEPTED_ITERATIONS = 5_000_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_LENGTH_BYTES = 16
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128

    class WrongPassphraseException : Exception("Incorrect passphrase, or the file is not a valid backup")

    fun isEncrypted(text: String): Boolean =
        runCatching { JSONObject(text.trim()).has("scheme") }.getOrDefault(false)

    fun encrypt(
        plaintext: String,
        passphrase: String,
    ): String {
        val salt = ByteArray(SALT_LENGTH_BYTES).also(SecureRandom()::nextBytes)
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).also(SecureRandom()::nextBytes)
        val key = deriveKey(passphrase, salt, PBKDF2_ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("v", VERSION)
            .put("scheme", SCHEME)
            .put("iter", PBKDF2_ITERATIONS)
            .put("salt", Base64Util.encode(salt))
            .put("iv", Base64Util.encode(iv))
            .put("ciphertext", Base64Util.encode(ciphertext))
            .toString()
    }

    /** @throws WrongPassphraseException if the passphrase is wrong or [envelope] is malformed --
     *  GCM's own authentication tag makes those indistinguishable from each other, which is
     *  exactly the point: nothing here should reveal *which* one it was to someone guessing. */
    fun decrypt(
        envelope: String,
        passphrase: String,
    ): String {
        val plaintext =
            runCatching {
                val obj = JSONObject(envelope.trim())
                val salt = Base64Util.decode(obj.getString("salt"))
                val iv = Base64Util.decode(obj.getString("iv"))
                val ciphertext = Base64Util.decode(obj.getString("ciphertext"))
                // A hostile file must not be able to demand an absurd amount of work.
                val iterations = obj.optInt("iter", LEGACY_PBKDF2_ITERATIONS).also { require(it in 1..MAX_ACCEPTED_ITERATIONS) }
                val key = deriveKey(passphrase, salt, iterations)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
                String(cipher.doFinal(ciphertext), Charsets.UTF_8)
            }.getOrNull()
        return plaintext ?: throw WrongPassphraseException()
    }

    private fun deriveKey(
        passphrase: String,
        salt: ByteArray,
        iterations: Int,
    ): SecretKeySpec {
        val spec: KeySpec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }
}

// java.util.Base64 (API 26+, matching this app's minSdk), not android.util.Base64 -- keeps this
// object plain-JVM testable without needing Robolectric to stand in for the Android SDK stub jar.
private object Base64Util {
    fun encode(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    fun decode(text: String): ByteArray = java.util.Base64.getDecoder().decode(text)
}
