package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.email.MailAuthRequiredException
import com.scifsidekick.cleanroom.email.graph.DeviceCode
import com.scifsidekick.cleanroom.email.graph.DeviceCodeResult
import com.scifsidekick.cleanroom.email.graph.MsOAuthManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.Base64

class MsOAuthManagerTest {
    /** One request the fake client saw: the URL and the form fields. */
    private data class Seen(
        val url: String,
        val form: Map<String, String>,
    )

    private val seen = mutableListOf<Seen>()
    private val delays = mutableListOf<Long>()
    private var now = 1_000_000L

    /** Runs inside the fake network call, to model something happening while a request is in flight. */
    private var duringRequest: (() -> Unit)? = null

    /** An [OkHttpClient] whose interceptor answers each request with the next scripted (status, body). */
    private fun scripted(vararg responses: Pair<Int, String>): OkHttpClient = scriptedSteps(responses.map { Step.Reply(it.first, it.second) })

    private sealed interface Step {
        data class Reply(
            val code: Int,
            val body: String,
        ) : Step

        data object NetworkDown : Step
    }

    private fun scriptedSteps(steps: List<Step>): OkHttpClient {
        val queue = ArrayDeque(steps)
        return OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val form =
                    (request.body as? FormBody)?.let { body -> (0 until body.size).associate { body.name(it) to body.value(it) } }
                        ?: emptyMap()
                synchronized(seen) { seen += Seen(request.url.toString(), form) }
                duringRequest?.invoke()
                val step = synchronized(queue) { queue.removeFirstOrNull() } ?: throw AssertionError("unexpected request ${request.url}")
                when (step) {
                    Step.NetworkDown -> throw IOException("network down")
                    is Step.Reply ->
                        Response
                            .Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(step.code)
                            .message("scripted")
                            .body(step.body.toResponseBody("application/json".toMediaType()))
                            .build()
                }
            }.build()
    }

    private fun manager(
        client: OkHttpClient,
        store: InMemoryRefreshTokenStore = InMemoryRefreshTokenStore(),
        clientId: String? = CLIENT_ID,
        delayMs: suspend (Long) -> Unit = { delays += it },
    ) = MsOAuthManager(
        clientId = { clientId },
        store = store,
        client = client,
        nowMs = { now },
        delayMs = delayMs,
    )

    private fun code(
        interval: Int = 5,
        expiresIn: Int = 900,
    ) = DeviceCode(
        userCode = "ABCD-EFGH",
        verificationUri = "https://microsoft.com/devicelogin",
        deviceCode = DEVICE_CODE,
        expiresInSec = expiresIn,
        intervalSec = interval,
        message = "To sign in, use a web browser.",
    )

    private fun error(code: String, description: String = "AADSTS70016: details") =
        """{"error":"$code","error_description":"$description"}"""

    private fun tokens(
        access: String = ACCESS_1,
        refresh: String? = REFRESH_2,
        expiresIn: Int = 3600,
        idToken: String? = null,
    ): String {
        val parts = mutableListOf("\"token_type\":\"Bearer\"", "\"access_token\":\"$access\"", "\"expires_in\":$expiresIn")
        if (refresh != null) parts += "\"refresh_token\":\"$refresh\""
        if (idToken != null) parts += "\"id_token\":\"$idToken\""
        return parts.joinToString(",", "{", "}")
    }

    // ---- startDeviceCode ----

    @Test fun `startDeviceCode posts client id and scopes and parses the documented response`() =
        runBlocking {
            val body =
                """{"user_code":"ABCD-EFGH","device_code":"$DEVICE_CODE","verification_uri":"https://microsoft.com/devicelogin",""" +
                    """"expires_in":900,"interval":5,"message":"To sign in, use a web browser to open the page."}"""
            val code = manager(scripted(200 to body)).startDeviceCode()

            assertEquals("ABCD-EFGH", code.userCode)
            assertEquals("https://microsoft.com/devicelogin", code.verificationUri)
            assertEquals(DEVICE_CODE, code.deviceCode)
            assertEquals(900, code.expiresInSec)
            assertEquals(5, code.intervalSec)
            assertEquals("To sign in, use a web browser to open the page.", code.message)
            val request = seen.single()
            assertEquals("${MsOAuthManager.AUTHORITY}/devicecode", request.url)
            assertEquals(CLIENT_ID, request.form["client_id"])
            assertEquals(MsOAuthManager.SCOPES, request.form["scope"])
        }

    @Test fun `startDeviceCode with a blank client id throws IllegalStateException and sends nothing`() =
        runBlocking {
            for (id in listOf(null, "", "   ")) {
                try {
                    manager(scripted(), clientId = id).startDeviceCode()
                    fail("expected IllegalStateException for '$id'")
                } catch (e: IllegalStateException) {
                    assertEquals("Enter your Microsoft app ID first", e.message)
                }
            }
            assertTrue(seen.isEmpty())
        }

    @Test fun `startDeviceCode network failure is an IOException`() =
        runBlocking {
            try {
                manager(scriptedSteps(listOf(Step.NetworkDown))).startDeviceCode()
                fail("expected IOException")
            } catch (_: IOException) {
            }
        }

    @Test fun `startDeviceCode rejected app id gives a fixed message without the error description`() =
        runBlocking {
            try {
                manager(scripted(400 to error("invalid_client", "AADSTS7000218 $LEAKY"))).startDeviceCode()
                fail("expected IllegalStateException")
            } catch (e: IllegalStateException) {
                assertFalse(e.message!!.contains(LEAKY))
                assertFalse(e.message!!.contains("AADSTS"))
            }
        }

    // ---- awaitDeviceCode ----

    @Test fun `pending pending success stores the refresh token and never polls faster than the interval`() =
        runBlocking {
            val store = InMemoryRefreshTokenStore()
            val mgr =
                manager(
                    scripted(400 to error("authorization_pending"), 400 to error("authorization_pending"), 200 to tokens()),
                    store,
                )
            val result = mgr.awaitDeviceCode(code(interval = 5))

            assertEquals(DeviceCodeResult.Connected(null), result)
            assertEquals(REFRESH_2, store.read())
            assertTrue(mgr.isAuthorized)
            assertEquals(listOf(5_000L, 5_000L, 5_000L), delays)
            assertEquals(3, seen.size)
            seen.forEach {
                assertEquals("${MsOAuthManager.AUTHORITY}/token", it.url)
                assertEquals("urn:ietf:params:oauth:grant-type:device_code", it.form["grant_type"])
                assertEquals(DEVICE_CODE, it.form["device_code"])
                assertEquals(CLIENT_ID, it.form["client_id"])
            }
            // The access token from sign-in is cached: no refresh request follows.
            assertEquals(ACCESS_1, mgr.freshAccessToken())
            assertEquals(3, seen.size)
        }

    @Test fun `success reads the account email from the id token when there is one`() =
        runBlocking {
            val claims = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"preferred_username":"me@outlook.com"}""".toByteArray())
            val idToken = "eyJhbGciOiJub25lIn0.$claims.sig"
            val result = manager(scripted(200 to tokens(idToken = idToken))).awaitDeviceCode(code())
            assertEquals(DeviceCodeResult.Connected("me@outlook.com"), result)
        }

    @Test fun `slow_down adds five seconds to every later delay`() =
        runBlocking {
            val mgr =
                manager(
                    scripted(400 to error("slow_down"), 400 to error("authorization_pending"), 200 to tokens()),
                )
            assertTrue(mgr.awaitDeviceCode(code(interval = 5)) is DeviceCodeResult.Connected)
            assertEquals(listOf(5_000L, 10_000L, 10_000L), delays)
        }

    @Test fun `expired_token fails with the expiry message`() =
        runBlocking {
            val store = InMemoryRefreshTokenStore()
            val result = manager(scripted(400 to error("expired_token")), store).awaitDeviceCode(code())
            assertTrue(result is DeviceCodeResult.Failed)
            assertTrue((result as DeviceCodeResult.Failed).reason.startsWith("The code expired"))
            assertNull(store.read())
        }

    @Test fun `the code lapsing locally fails with the expiry message without another request`() =
        runBlocking {
            val mgr =
                manager(scripted(400 to error("authorization_pending")), delayMs = {
                    delays += it
                    now += it
                })
            val result = mgr.awaitDeviceCode(code(interval = 5, expiresIn = 8))
            assertTrue((result as DeviceCodeResult.Failed).reason.startsWith("The code expired"))
            assertEquals(1, seen.size)
        }

    @Test fun `declined or denied fails`() =
        runBlocking {
            for (reason in listOf("authorization_declined", "access_denied")) {
                val store = InMemoryRefreshTokenStore()
                val result = manager(scripted(400 to error(reason)), store).awaitDeviceCode(code())
                assertTrue("$reason -> $result", result is DeviceCodeResult.Failed)
                assertNull(store.read())
            }
        }

    @Test fun `malformed JSON fails`() =
        runBlocking {
            for (body in listOf("<html>Bad gateway</html>", "", "{\"access_token\":")) {
                val result = manager(scripted(502 to body)).awaitDeviceCode(code())
                assertTrue("'$body' -> $result", result is DeviceCodeResult.Failed)
            }
            // A 200 with no refresh token is not a usable sign-in either.
            val store = InMemoryRefreshTokenStore()
            val result = manager(scripted(200 to tokens(refresh = null)), store).awaitDeviceCode(code())
            assertTrue(result is DeviceCodeResult.Failed)
            assertNull(store.read())
        }

    @Test fun `failure reasons never echo Microsoft's error description`() =
        runBlocking {
            for (reason in listOf("expired_token", "access_denied", "invalid_client", "something_new")) {
                val result = manager(scripted(400 to error(reason, "AADSTS50000 $LEAKY"))).awaitDeviceCode(code())
                val text = (result as DeviceCodeResult.Failed).reason
                assertFalse(text.contains(LEAKY))
                assertFalse(text.contains("AADSTS"))
                assertFalse(text.contains(DEVICE_CODE))
            }
        }

    @Test fun `a network failure while polling keeps polling`() =
        runBlocking {
            val store = InMemoryRefreshTokenStore()
            val mgr = manager(scriptedSteps(listOf(Step.NetworkDown, Step.Reply(200, tokens()))), store)
            assertTrue(mgr.awaitDeviceCode(code()) is DeviceCodeResult.Connected)
            assertEquals(REFRESH_2, store.read())
        }

    @Test fun `cancellation returns Cancelled`() =
        runBlocking {
            val waiting = CompletableDeferred<Unit>()
            var result: DeviceCodeResult? = null
            val mgr =
                manager(scripted(), delayMs = {
                    waiting.complete(Unit)
                    awaitCancellation()
                })
            val job = launch { result = mgr.awaitDeviceCode(code()) }
            waiting.await()
            job.cancel()
            job.join()
            assertEquals(DeviceCodeResult.Cancelled, result)
            assertTrue(seen.isEmpty())
        }

    // ---- freshAccessToken ----

    @Test fun `no stored token is MailAuthRequiredException for graph`() =
        runBlocking {
            try {
                manager(scripted()).freshAccessToken()
                fail("expected MailAuthRequiredException")
            } catch (e: MailAuthRequiredException) {
                assertEquals("graph", e.providerId)
                assertEquals("Outlook", e.displayName)
            }
            assertTrue(seen.isEmpty())
        }

    @Test fun `a refreshed access token is cached until a minute before expiry`() =
        runBlocking {
            val store = InMemoryRefreshTokenStore(REFRESH_1)
            val mgr = manager(scripted(200 to tokens(access = ACCESS_1, expiresIn = 3600), 200 to tokens(access = ACCESS_2)), store)

            assertEquals(ACCESS_1, mgr.freshAccessToken())
            val request = seen.single()
            assertEquals("${MsOAuthManager.AUTHORITY}/token", request.url)
            assertEquals("refresh_token", request.form["grant_type"])
            assertEquals(REFRESH_1, request.form["refresh_token"])
            assertEquals(CLIENT_ID, request.form["client_id"])

            now += 3_539_000 // 61 s before expiry: still cached
            assertEquals(ACCESS_1, mgr.freshAccessToken())
            assertEquals(1, seen.size)

            now += 2_000 // 59 s before expiry: refresh
            assertEquals(ACCESS_2, mgr.freshAccessToken())
            assertEquals(2, seen.size)
        }

    @Test fun `clearAccessToken forces the next call to refresh`() =
        runBlocking {
            val mgr = manager(scripted(200 to tokens(access = ACCESS_1), 200 to tokens(access = ACCESS_2)), InMemoryRefreshTokenStore(REFRESH_1))
            assertEquals(ACCESS_1, mgr.freshAccessToken())
            mgr.clearAccessToken()
            assertEquals(ACCESS_2, mgr.freshAccessToken())
        }

    @Test fun `a rotated refresh token is stored and a missing or blank one keeps the old one`() =
        runBlocking {
            val store = InMemoryRefreshTokenStore(REFRESH_1)
            val mgr = manager(scripted(200 to tokens(refresh = REFRESH_2)), store)
            mgr.freshAccessToken()
            assertEquals(REFRESH_2, store.read())
            assertEquals(listOf(REFRESH_2), store.writes)

            for (body in listOf(tokens(refresh = null), tokens(refresh = ""), tokens(refresh = "  "))) {
                val keep = InMemoryRefreshTokenStore(REFRESH_1)
                manager(scripted(200 to body), keep).freshAccessToken()
                assertEquals(REFRESH_1, keep.read())
                assertTrue(keep.writes.isEmpty())
            }
        }

    @Test fun `invalid_grant clears the stored token and asks for a reconnect`() =
        runBlocking {
            val store = InMemoryRefreshTokenStore(REFRESH_1)
            val mgr = manager(scripted(400 to error("invalid_grant", "AADSTS70008 $LEAKY")), store)
            try {
                mgr.freshAccessToken()
                fail("expected MailAuthRequiredException")
            } catch (e: MailAuthRequiredException) {
                assertEquals("Open the app and reconnect Outlook", e.message)
                assertEquals("graph", e.providerId)
                assertEquals("Outlook", e.displayName)
            }
            assertNull(store.read())
            assertFalse(mgr.isAuthorized)
        }

    @Test fun `a network failure is not an auth failure and keeps the token`() =
        runBlocking {
            val store = InMemoryRefreshTokenStore(REFRESH_1)
            val mgr = manager(scriptedSteps(listOf(Step.NetworkDown)), store)
            try {
                mgr.freshAccessToken()
                fail("expected IOException")
            } catch (e: MailAuthRequiredException) {
                fail("a network failure must not be an auth failure")
            } catch (_: IOException) {
            }
            assertEquals(REFRESH_1, store.read())
            assertEquals(0, store.clears)
        }

    @Test fun `other refresh errors keep the token, are not auth failures and do not echo the body`() =
        runBlocking {
            for ((status, body) in listOf(503 to "<html>$LEAKY</html>", 400 to error("invalid_client", LEAKY), 200 to "{\"x\":1}")) {
                val store = InMemoryRefreshTokenStore(REFRESH_1)
                try {
                    manager(scripted(status to body), store).freshAccessToken()
                    fail("expected a failure for $status")
                } catch (e: MailAuthRequiredException) {
                    fail("$status must not be an auth failure")
                } catch (e: Exception) {
                    assertFalse(e.message.orEmpty().contains(LEAKY))
                    assertFalse(e.message.orEmpty().contains(REFRESH_1))
                }
                assertEquals(REFRESH_1, store.read())
            }
        }

    @Test fun `concurrent callers share one refresh`() =
        runBlocking {
            val mgr = manager(scripted(200 to tokens(access = ACCESS_1)), InMemoryRefreshTokenStore(REFRESH_1))
            val results = mutableListOf<String>()
            val jobs = (1..5).map { launch(kotlinx.coroutines.Dispatchers.Default) { mgr.freshAccessToken().also { synchronized(results) { results += it } } } }
            jobs.forEach { it.join() }
            assertEquals(List(5) { ACCESS_1 }, results)
            assertEquals(1, seen.size)
        }

    @Test fun `a disconnect during a refresh is not undone by the rotated token`() =
        runBlocking {
            val store = InMemoryRefreshTokenStore(REFRESH_1)
            val mgr = manager(scripted(200 to tokens(access = ACCESS_1, refresh = REFRESH_2)), store)
            duringRequest = { mgr.disconnect() }
            try {
                mgr.freshAccessToken()
                fail("expected MailAuthRequiredException")
            } catch (_: MailAuthRequiredException) {
            }
            assertNull(store.read())
            assertTrue(store.writes.isEmpty())
        }

    @Test fun `invalid_grant for an old token does not clear a newer sign-in`() =
        runBlocking {
            val store = InMemoryRefreshTokenStore(REFRESH_1)
            val mgr = manager(scripted(400 to error("invalid_grant")), store)
            duringRequest = { store.write(REFRESH_2) }
            try {
                mgr.freshAccessToken()
                fail("expected MailAuthRequiredException")
            } catch (_: MailAuthRequiredException) {
            }
            assertEquals(REFRESH_2, store.read())
        }

    @Test fun `disconnect clears the store and the cached access token`() =
        runBlocking {
            val store = InMemoryRefreshTokenStore(REFRESH_1)
            val mgr = manager(scripted(200 to tokens()), store)
            mgr.freshAccessToken()
            mgr.disconnect()
            assertNull(store.read())
            assertFalse(mgr.isAuthorized)
            try {
                mgr.freshAccessToken()
                fail("expected MailAuthRequiredException")
            } catch (_: MailAuthRequiredException) {
            }
        }

    private companion object {
        const val CLIENT_ID = "11111111-2222-3333-4444-555555555555"
        const val DEVICE_CODE = "DAQABAAEAAAD-device-code-secret"
        const val ACCESS_1 = "EwA-access-one"
        const val ACCESS_2 = "EwA-access-two"
        const val REFRESH_1 = "M.C1-refresh-one"
        const val REFRESH_2 = "M.C1-refresh-two"
        const val LEAKY = "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9SECRETSECRETSECRET"
    }
}
