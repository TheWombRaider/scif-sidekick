package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.email.MailAuthRequiredException
import com.scifsidekick.cleanroom.email.graph.DeviceCode
import com.scifsidekick.cleanroom.email.graph.DeviceCodeResult
import com.scifsidekick.cleanroom.email.graph.MsOAuthManager
import com.scifsidekick.cleanroom.email.graph.RefreshTokenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    @Volatile private var duringRequest: (() -> Unit)? = null

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
                val step = synchronized(queue) { queue.removeFirstOrNull() } ?: throw AssertionError("unexpected request ${request.url}")
                duringRequest?.let { hook ->
                    duringRequest = null // runs once, so a hook may itself make a request
                    hook()
                }
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
            for ((status, body) in listOf(200 to "<html>OK</html>", 400 to "", 400 to "{\"access_token\":", 200 to "{\"access_token\":")) {
                val result = manager(scripted(status to body)).awaitDeviceCode(code())
                assertTrue("$status '$body' -> $result", result is DeviceCodeResult.Failed)
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
            val ready = CountDownLatch(5)
            val gate = CompletableDeferred<Unit>()
            // Hold the one request open long enough that every other caller is queued on the lock.
            duringRequest = { Thread.sleep(200) }
            val callers =
                (1..5).map {
                    async(Dispatchers.Default) {
                        ready.countDown()
                        gate.await()
                        mgr.freshAccessToken()
                    }
                }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            gate.complete(Unit)
            assertEquals(List(5) { ACCESS_1 }, callers.awaitAll())
            assertEquals(1, seen.size)
        }

    @Test fun `a blank client id on refresh asks for a reconnect without a request`() =
        runBlocking {
            for (id in listOf(null, "", "  ")) {
                val store = InMemoryRefreshTokenStore(REFRESH_1)
                try {
                    manager(scripted(), store, clientId = id).freshAccessToken()
                    fail("expected MailAuthRequiredException for '$id'")
                } catch (e: MailAuthRequiredException) {
                    assertEquals("graph", e.providerId)
                }
                assertEquals(REFRESH_1, store.read())
            }
            assertTrue(seen.isEmpty())
        }

    @Test fun `a stale refresh after disconnect caches nothing`() =
        runBlocking {
            val store = InMemoryRefreshTokenStore(REFRESH_1)
            val mgr = manager(scripted(200 to tokens(access = ACCESS_1), 200 to tokens(access = ACCESS_2)), store)
            duringRequest = { mgr.disconnect() }
            try {
                mgr.freshAccessToken()
                fail("expected MailAuthRequiredException")
            } catch (_: MailAuthRequiredException) {
            }
            store.write(REFRESH_1)
            // The stale ACCESS_1 was not cached: the next call refreshes.
            assertEquals(ACCESS_2, mgr.freshAccessToken())
            assertEquals(2, seen.size)
        }

    /** A device-code sign-in that runs to completion while the outer request is in flight. */
    private fun signInDuringRequest(mgr: MsOAuthManager) {
        duringRequest = { runBlocking { assertTrue(mgr.awaitDeviceCode(code()) is DeviceCodeResult.Connected) } }
    }

    @Test fun `a sign-in that completes mid-refresh wins and its access token is returned`() =
        runBlocking {
            val store = InMemoryRefreshTokenStore(REFRESH_1)
            val mgr =
                manager(
                    scripted(200 to tokens(access = ACCESS_1, refresh = REFRESH_2), 200 to tokens(access = ACCESS_NEW, refresh = REFRESH_NEW)),
                    store,
                )
            signInDuringRequest(mgr)
            assertEquals(ACCESS_NEW, mgr.freshAccessToken())
            assertEquals(REFRESH_NEW, store.read())
            assertEquals(listOf(REFRESH_NEW), store.writes)
            assertEquals(ACCESS_NEW, mgr.freshAccessToken())
            assertEquals(2, seen.size)
        }

    @Test fun `invalid_grant for the old token while a sign-in completes returns the new access token`() =
        runBlocking {
            val store = InMemoryRefreshTokenStore(REFRESH_1)
            val mgr = manager(scripted(400 to error("invalid_grant"), 200 to tokens(access = ACCESS_NEW, refresh = REFRESH_NEW)), store)
            signInDuringRequest(mgr)
            assertEquals(ACCESS_NEW, mgr.freshAccessToken())
            assertEquals(REFRESH_NEW, store.read())
            assertEquals(0, store.clears)
        }

    @Test fun `a failed rotation write keeps the old refresh token and still returns the access token`() =
        runBlocking {
            val store = InMemoryRefreshTokenStore(REFRESH_1)
            store.failWrites = true
            val mgr = manager(scripted(200 to tokens(access = ACCESS_1, refresh = REFRESH_2)), store)
            assertEquals(ACCESS_1, mgr.freshAccessToken())
            assertEquals(REFRESH_1, store.read())
            assertEquals(0, store.clears)
            assertEquals(ACCESS_1, mgr.freshAccessToken()) // cached
            assertEquals(1, seen.size)
        }

    /** Counts reads, which are Keystore decrypts in the real store. */
    private class CountingStore(
        initial: String?,
    ) : RefreshTokenStore {
        val inner = InMemoryRefreshTokenStore(initial)
        var reads = 0

        override fun read(): String? {
            reads++
            return inner.read()
        }

        override fun write(token: String) = inner.write(token)

        override fun clear() = inner.clear()
    }

    private fun countingManager(
        store: CountingStore,
        client: OkHttpClient = scripted(),
    ) = MsOAuthManager(clientId = { CLIENT_ID }, store = store, client = client, nowMs = { now }, delayMs = { delays += it })

    @Test fun `isAuthorized reads the store once while a token stays stored`() {
        val store = CountingStore(REFRESH_1)
        val mgr = countingManager(store)
        repeat(5) { assertTrue(mgr.isAuthorized) }
        assertEquals(1, store.reads)
    }

    @Test fun `isAuthorized is false right after disconnect and true right after a sign-in`() =
        runBlocking {
            val store = CountingStore(REFRESH_1)
            val mgr = countingManager(store, scripted(200 to tokens(access = ACCESS_1, refresh = REFRESH_2)))
            assertTrue(mgr.isAuthorized)
            mgr.disconnect()
            assertFalse(mgr.isAuthorized)
            assertFalse(mgr.isAuthorized)
            assertTrue(mgr.awaitDeviceCode(code()) is DeviceCodeResult.Connected)
            assertTrue(mgr.isAuthorized)
            assertEquals(REFRESH_2, store.inner.read())
        }

    @Test fun `isAuthorized is false right after Microsoft rejects the token`() =
        runBlocking {
            val store = CountingStore(REFRESH_1)
            val mgr = countingManager(store, scripted(400 to error("invalid_grant")))
            assertTrue(mgr.isAuthorized)
            try {
                mgr.freshAccessToken()
                fail("expected MailAuthRequiredException")
            } catch (_: MailAuthRequiredException) {
            }
            assertFalse(mgr.isAuthorized)
        }

    @Test fun `a failed rotation write leaves a secret-free breadcrumb, a successful one none`() =
        runBlocking {
            val logged = mutableListOf<String>()
            val store = InMemoryRefreshTokenStore(REFRESH_1)
            store.failWrites = true
            val mgr =
                MsOAuthManager(
                    clientId = { CLIENT_ID },
                    store = store,
                    client = scripted(200 to tokens(access = ACCESS_1, refresh = REFRESH_2)),
                    nowMs = { now },
                    delayMs = { delays += it },
                    log = { logged += it },
                )
            assertEquals(ACCESS_1, mgr.freshAccessToken())
            assertEquals(1, logged.size)
            listOf(REFRESH_1, REFRESH_2, ACCESS_1, CLIENT_ID).forEach { secret -> assertFalse(logged.single().contains(secret)) }

            val quiet = mutableListOf<String>()
            MsOAuthManager(
                clientId = { CLIENT_ID },
                store = InMemoryRefreshTokenStore(REFRESH_1),
                client = scripted(200 to tokens(access = ACCESS_1, refresh = REFRESH_2)),
                nowMs = { now },
                delayMs = { delays += it },
                log = { quiet += it },
            ).freshAccessToken()
            assertEquals(emptyList<String>(), quiet)
        }

    @Test fun `token lifetime is clamped so a huge expires_in cannot disable refresh`() =
        runBlocking {
            val mgr =
                manager(
                    scripted(200 to tokens(access = ACCESS_1, expiresIn = Int.MAX_VALUE), 200 to tokens(access = ACCESS_2)),
                    InMemoryRefreshTokenStore(REFRESH_1),
                )
            assertEquals(ACCESS_1, mgr.freshAccessToken())
            now += 86_400_000L - 61_000L
            assertEquals(ACCESS_1, mgr.freshAccessToken())
            now += 2_000L
            assertEquals(ACCESS_2, mgr.freshAccessToken())
        }

    @Test fun `a non-positive token lifetime means an hour`() =
        runBlocking {
            for (expiresIn in listOf(0, -5)) {
                seen.clear()
                val start = now
                val mgr = manager(scripted(200 to tokens(access = ACCESS_1, expiresIn = expiresIn)), InMemoryRefreshTokenStore(REFRESH_1))
                assertEquals(ACCESS_1, mgr.freshAccessToken())
                now = start + 3_539_000L
                assertEquals(ACCESS_1, mgr.freshAccessToken())
                assertEquals(1, seen.size)
                now = start
            }
        }

    @Test fun `server device-code timings are clamped`() =
        runBlocking {
            fun body(
                interval: Int,
                expiresIn: Int,
            ) = """{"user_code":"U","device_code":"$DEVICE_CODE","verification_uri":"https://microsoft.com/devicelogin",""" +
                """"expires_in":$expiresIn,"interval":$interval,"message":"m"}"""
            val cases =
                listOf(
                    Triple(body(0, 0), 5, 900),
                    Triple(body(-3, -1), 5, 900),
                    Triple(body(3600, 99_999), 60, 1800),
                    Triple(body(7, 600), 7, 600),
                )
            for ((json, interval, expires) in cases) {
                val code = manager(scripted(200 to json)).startDeviceCode()
                assertEquals(interval, code.intervalSec)
                assertEquals(expires, code.expiresInSec)
            }
        }

    @Test fun `awaitDeviceCode clamps a hand-built code's timings too`() =
        runBlocking {
            val mgr = manager(scripted(200 to tokens()))
            assertTrue(mgr.awaitDeviceCode(code(interval = 3600)) is DeviceCodeResult.Connected)
            assertEquals(listOf(60_000L), delays)

            delays.clear()
            val pending = List(29) { Step.Reply(400, error("authorization_pending")) }
            val lapsing =
                manager(scriptedSteps(pending), delayMs = {
                    // Fail fast instead of polling for decades if the cap is missing.
                    if (delays.size >= 30) throw AssertionError("still polling after 30 minutes")
                    delays += it
                    now += it
                })
            // expires_in is capped at 30 minutes, so one-minute polls stop after 30 waits.
            val result = lapsing.awaitDeviceCode(code(interval = 60, expiresIn = Int.MAX_VALUE))
            assertTrue((result as DeviceCodeResult.Failed).reason.startsWith("The code expired"))
            assertEquals(30, delays.size)
        }

    @Test fun `server errors while polling keep polling`() =
        runBlocking {
            val store = InMemoryRefreshTokenStore()
            val mgr =
                manager(
                    scripted(
                        502 to "<html>Bad gateway</html>",
                        503 to "",
                        500 to error("server_error", LEAKY),
                        400 to error("temporarily_unavailable"),
                        200 to tokens(),
                    ),
                    store,
                )
            assertTrue(mgr.awaitDeviceCode(code()) is DeviceCodeResult.Connected)
            assertEquals(REFRESH_2, store.read())
            assertEquals(5, delays.size)
        }

    @Test fun `server errors until the code lapses end with the expiry message`() =
        runBlocking {
            val mgr =
                manager(scripted(503 to "", 503 to "", 503 to ""), delayMs = {
                    delays += it
                    now += it
                })
            val result = mgr.awaitDeviceCode(code(interval = 5, expiresIn = 16))
            assertTrue((result as DeviceCodeResult.Failed).reason.startsWith("The code expired"))
            assertEquals(3, seen.size)
        }

    @Test fun `other 4xx errors still end polling`() =
        runBlocking {
            for (reason in listOf("invalid_client", "bad_verification_code", "invalid_grant", "something_new")) {
                seen.clear()
                val result = manager(scripted(400 to error(reason))).awaitDeviceCode(code())
                assertTrue("$reason -> $result", result is DeviceCodeResult.Failed)
                assertEquals(1, seen.size)
            }
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
        const val ACCESS_NEW = "EwA-access-new-sign-in"
        const val REFRESH_NEW = "M.C1-refresh-new-sign-in"
        const val LEAKY = "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9SECRETSECRETSECRET"
    }
}
