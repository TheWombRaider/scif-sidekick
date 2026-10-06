package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.util.BackupCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {
    @Test fun `plain backup json is not detected as encrypted`() {
        assertFalse(BackupCrypto.isEncrypted("""{"filters":[],"appSettings":{}}"""))
    }

    @Test fun `an encrypted envelope round-trips back to the original plaintext`() {
        val plaintext = """{"filters":[{"name":"Work"}],"appSettings":{"appLockEnabled":true}}"""
        val envelope = BackupCrypto.encrypt(plaintext, "correct horse battery staple")
        assertTrue(BackupCrypto.isEncrypted(envelope))
        assertFalse(envelope.contains("Work")) // never stores plaintext alongside the ciphertext
        assertEquals(plaintext, BackupCrypto.decrypt(envelope, "correct horse battery staple"))
    }

    @Test fun `decrypting with the wrong passphrase fails instead of returning garbage`() {
        val envelope = BackupCrypto.encrypt("""{"filters":[]}""", "right passphrase")
        assertThrows(BackupCrypto.WrongPassphraseException::class.java) {
            BackupCrypto.decrypt(envelope, "wrong passphrase")
        }
    }

    @Test fun `decrypting a malformed envelope fails the same way as a wrong passphrase`() {
        assertThrows(BackupCrypto.WrongPassphraseException::class.java) {
            BackupCrypto.decrypt("""{"scheme":"AES-256-GCM+PBKDF2WithHmacSHA256","salt":"x"}""", "anything")
        }
    }

    @Test fun `two encryptions of the same plaintext never produce the same ciphertext`() {
        // Random salt + IV per call -- guards against ever regressing to a fixed IV, which
        // would leak whether two backups made with the same passphrase are byte-for-byte equal.
        val first = BackupCrypto.encrypt("same content", "pw")
        val second = BackupCrypto.encrypt("same content", "pw")
        assertFalse(first == second)
    }

    @Test fun `new envelopes record 600000 pbkdf2 iterations`() {
        val envelope = BackupCrypto.encrypt("x", "pw")
        assertEquals(600_000, org.json.JSONObject(envelope).getInt("iter"))
    }

    @Test fun `a backup written before the iteration count was recorded still restores`() {
        val salt = ByteArray(16) { it.toByte() }
        val iv = ByteArray(12) { (it + 1).toByte() }
        val key =
            javax.crypto.SecretKeyFactory
                .getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(javax.crypto.spec.PBEKeySpec("old pw".toCharArray(), salt, 210_000, 256))
                .encoded
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, iv))
        val encoder = java.util.Base64.getEncoder()
        val legacy =
            org.json.JSONObject()
                .put("v", 1)
                .put("scheme", "AES-256-GCM+PBKDF2WithHmacSHA256")
                .put("salt", encoder.encodeToString(salt))
                .put("iv", encoder.encodeToString(iv))
                .put("ciphertext", encoder.encodeToString(cipher.doFinal("legacy content".toByteArray())))
                .toString()
        assertEquals("legacy content", BackupCrypto.decrypt(legacy, "old pw"))
    }

    @Test fun `an absurd iteration count is refused instead of burning the cpu`() {
        val envelope = org.json.JSONObject(BackupCrypto.encrypt("x", "pw")).put("iter", 2_000_000_000).toString()
        assertThrows(BackupCrypto.WrongPassphraseException::class.java) { BackupCrypto.decrypt(envelope, "pw") }
    }
}
