package com.scifsidekick.cleanroom

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scifsidekick.cleanroom.email.graph.KeystoreRefreshTokenStore
import com.scifsidekick.cleanroom.email.graph.MsAccountPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MsAccountPreferencesInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val raw = context.getSharedPreferences(KeystoreRefreshTokenStore.PREFS_NAME, Context.MODE_PRIVATE)
    private lateinit var saved: Map<String, *>

    @Before fun setUp() {
        saved = raw.all.toMap()
        raw.edit().clear().commit()
    }

    @After fun tearDown() {
        val editor = raw.edit().clear()
        saved.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
            }
        }
        editor.commit()
    }

    @Test fun defaults() {
        val prefs = MsAccountPreferences(context)
        assertEquals("", prefs.clientId)
        assertNull(prefs.accountEmail)
        assertEquals("gmail", prefs.preferredProvider)
    }

    @Test fun preferredProviderAcceptsOnlyGmailOrGraph() {
        val prefs = MsAccountPreferences(context)
        prefs.preferredProvider = "graph"
        assertEquals("graph", MsAccountPreferences(context).preferredProvider)
        prefs.preferredProvider = "outlook"
        assertEquals("gmail", prefs.preferredProvider)
        raw.edit().putString("preferred_provider", "GRAPH ").commit()
        assertEquals("gmail", prefs.preferredProvider)
    }

    @Test fun clearAccountKeepsClientIdAndPreferredProvider() {
        val prefs = MsAccountPreferences(context)
        prefs.clientId = " 11111111-2222-3333-4444-555555555555 "
        prefs.accountEmail = "me@outlook.com"
        prefs.preferredProvider = "graph"
        prefs.clearAccount()

        val reread = MsAccountPreferences(context)
        assertEquals("11111111-2222-3333-4444-555555555555", reread.clientId)
        assertEquals("graph", reread.preferredProvider)
        assertNull(reread.accountEmail)
    }

    @Test fun storesNoToken() {
        val prefs = MsAccountPreferences(context)
        prefs.clientId = "id"
        prefs.accountEmail = "me@outlook.com"
        prefs.preferredProvider = "graph"
        assertFalse(raw.contains(KeystoreRefreshTokenStore.PREF_KEY))
        assertTrue(raw.all.keys.none { "token" in it || it == "rt" })
    }
}
