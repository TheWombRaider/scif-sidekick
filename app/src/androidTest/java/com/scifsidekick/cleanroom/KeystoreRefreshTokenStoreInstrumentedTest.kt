package com.scifsidekick.cleanroom

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scifsidekick.cleanroom.email.graph.KeystoreRefreshTokenStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

/** The Android Keystore only exists on a device, so the encrypted token store is tested here. */
@RunWith(AndroidJUnit4::class)
class KeystoreRefreshTokenStoreInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences(KeystoreRefreshTokenStore.PREFS_NAME, Context.MODE_PRIVATE)
    private val store = KeystoreRefreshTokenStore(context)

    @Before fun setUp() = store.clear()

    @After fun tearDown() = store.clear()

    private fun stored(): String = prefs.getString(KeystoreRefreshTokenStore.PREF_KEY, null)!!

    private fun keystoreHasKey(): Boolean =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(KeystoreRefreshTokenStore.KEY_ALIAS)

    @Test fun roundTrip() {
        assertNull(store.read())
        store.write(TOKEN)
        assertEquals(TOKEN, store.read())
        // A second instance (as after a process restart) reads the same token.
        assertEquals(TOKEN, KeystoreRefreshTokenStore(context).read())
        assertFalse("the token must not be stored in clear", stored().contains(TOKEN))
    }

    @Test fun eachWriteUsesAFreshIv() {
        store.write(TOKEN)
        val first = stored()
        store.write(TOKEN)
        val second = stored()
        val firstIv = Base64.decode(first.substringBefore('.'), Base64.NO_WRAP)
        val secondIv = Base64.decode(second.substringBefore('.'), Base64.NO_WRAP)
        assertEquals(12, firstIv.size)
        assertEquals(12, secondIv.size)
        assertFalse(firstIv.contentEquals(secondIv))
        assertNotEquals(first, second)
        assertEquals(TOKEN, store.read())
    }

    @Test fun aTamperedCiphertextReadsAsNull() {
        store.write(TOKEN)
        val (iv, ciphertext) = stored().split('.')
        val bytes = Base64.decode(ciphertext, Base64.NO_WRAP)
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()
        assertTrue(prefs.edit().putString(KeystoreRefreshTokenStore.PREF_KEY, iv + "." + Base64.encodeToString(bytes, Base64.NO_WRAP)).commit())
        assertNull(store.read())
    }

    @Test fun malformedStoredValuesReadAsNull() {
        store.write(TOKEN)
        for (junk in listOf("", "no-dot", "!!!.???", "AAAA.AAAA", ".")) {
            assertTrue(prefs.edit().putString(KeystoreRefreshTokenStore.PREF_KEY, junk).commit())
            assertNull("'$junk'", store.read())
        }
    }

    @Test fun clearRemovesTheTokenAndTheKeyAndALaterWriteWorks() {
        store.write(TOKEN)
        assertTrue(keystoreHasKey())
        store.clear()
        assertNull(store.read())
        assertFalse(prefs.contains(KeystoreRefreshTokenStore.PREF_KEY))
        assertFalse(keystoreHasKey())

        store.write("second-$TOKEN")
        assertTrue(keystoreHasKey())
        assertEquals("second-$TOKEN", store.read())
    }

    @Test fun anExistingUsableKeyIsReusedNotRegenerated() {
        store.write(TOKEN)
        val firstCiphertext = stored()
        val second = KeystoreRefreshTokenStore(context)
        assertEquals(TOKEN, second.read())
        second.write("second-$TOKEN")
        assertEquals("second-$TOKEN", store.read())
        // The first write's ciphertext still decrypts, so the second store used the same key.
        assertTrue(prefs.edit().putString(KeystoreRefreshTokenStore.PREF_KEY, firstCiphertext).commit())
        assertEquals(TOKEN, KeystoreRefreshTokenStore(context).read())
    }

    @Test fun aMissingKeyReadsAsNull() {
        store.write(TOKEN)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KeystoreRefreshTokenStore.KEY_ALIAS)
        assertNull(store.read())
    }

    private companion object {
        const val TOKEN = "M.C123_BAY.-CVfakeRefreshTokenForTestsOnly!Zx9"
    }
}
