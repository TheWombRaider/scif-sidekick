package com.scifsidekick.cleanroom.email.graph

import com.scifsidekick.cleanroom.email.MailAuthRequiredException
import com.scifsidekick.cleanroom.email.MailHttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What Microsoft's `/devicecode` endpoint returned: the code the user types and how to poll for the result. */
data class DeviceCode(
    val userCode: String,
    val verificationUri: String,
    val deviceCode: String,
    val expiresInSec: Int,
    val intervalSec: Int,
    val message: String,
) {
    // deviceCode is a polling secret; keep it out of logs and crash reports that print this object.
    override fun toString(): String = "DeviceCode(userCode=$userCode, verificationUri=$verificationUri, expiresInSec=$expiresInSec, intervalSec=$intervalSec)"
}

sealed interface DeviceCodeResult {
    data class Connected(
        val accountEmail: String?,
    ) : DeviceCodeResult

    data class Failed(
        val reason: String,
    ) : DeviceCodeResult

    data object Cancelled : DeviceCodeResult
}

/**
 * Microsoft sign-in with the OAuth 2.0 device code flow, over plain OkHttp (no MSAL). The refresh
 * token lives in [store]; access tokens live only in memory.
 *
 * No token, device code or response body ever goes into a log line or an exception message: every
 * failure text here is fixed, and Microsoft's `error_description` is never echoed.
 */
class MsOAuthManager(
    private val clientId: () -> String?,
    private val store: RefreshTokenStore,
    private val client: OkHttpClient,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val delayMs: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    private class CachedToken(
        val value: String,
        val expiresAtMs: Long,
    )

    private class Reply(
        val status: Int,
        val json: JSONObject?,
    )

    @Volatile private var cached: CachedToken? = null
    private val refreshLock = Mutex()

    // Guards every change to the stored token together with the cached access token, so a refresh
    // that finishes after a disconnect or a new sign-in cannot write back or clear the wrong token.
    private val stateLock = Any()

    val isAuthorized: Boolean get() = store.read() != null

    suspend fun startDeviceCode(): DeviceCode {
        val id = requireClientId()
        val reply =
            post(
                "$AUTHORITY/devicecode",
                FormBody
                    .Builder()
                    .add("client_id", id)
                    .add("scope", SCOPES)
                    .build(),
            )
        val json = reply.json
        if (reply.status !in 200..299 || json == null) {
            throw IllegalStateException(failureMessage(json?.let { string(it, "error") }))
        }
        val userCode = string(json, "user_code")
        val deviceCode = string(json, "device_code")
        val uri = string(json, "verification_uri")
        if (userCode == null || deviceCode == null || uri == null) throw IllegalStateException(UNREADABLE)
        return DeviceCode(
            userCode = userCode,
            verificationUri = uri,
            deviceCode = deviceCode,
            expiresInSec = codeLifetimeSec(json.optInt("expires_in", DEFAULT_EXPIRES_SEC)),
            intervalSec = pollIntervalSec(json.optInt("interval", DEFAULT_INTERVAL_SEC)),
            message = string(json, "message") ?: "Open $uri and enter the code $userCode",
        )
    }

    /**
     * Polls the token endpoint every [DeviceCode.intervalSec] seconds (plus 5 s after each `slow_down`)
     * until the user finishes, declines, or the code expires. On success the refresh token is stored
     * before this returns. Cancelling the calling coroutine returns [DeviceCodeResult.Cancelled].
     */
    suspend fun awaitDeviceCode(code: DeviceCode): DeviceCodeResult {
        val id = clientId()?.trim().orEmpty()
        if (id.isEmpty()) return DeviceCodeResult.Failed(NO_CLIENT_ID)
        val deadline = nowMs() + codeLifetimeSec(code.expiresInSec) * 1000L
        var intervalMs = pollIntervalSec(code.intervalSec) * 1000L
        try {
            while (true) {
                delayMs(intervalMs)
                if (nowMs() >= deadline) return DeviceCodeResult.Failed(EXPIRED)
                val reply =
                    try {
                        post(
                            "$AUTHORITY/token",
                            FormBody
                                .Builder()
                                .add("grant_type", DEVICE_CODE_GRANT)
                                .add("client_id", id)
                                .add("device_code", code.deviceCode)
                                .build(),
                        )
                    } catch (_: IOException) {
                        continue // a dropped connection is not an answer; try again at the next interval
                    }
                // A server-side outage is not an answer either: keep polling until the code expires.
                if (reply.status >= 500) continue
                val json = reply.json ?: return DeviceCodeResult.Failed(UNREADABLE)
                if (reply.status in 200..299) return finishSignIn(json)
                when (val error = string(json, "error")) {
                    "authorization_pending", "temporarily_unavailable" -> Unit
                    "slow_down" -> intervalMs += SLOW_DOWN_MS
                    else -> return DeviceCodeResult.Failed(failureMessage(error))
                }
            }
        } catch (_: CancellationException) {
            return DeviceCodeResult.Cancelled
        }
    }

    private fun finishSignIn(json: JSONObject): DeviceCodeResult {
        val access = string(json, "access_token")
        val refresh = string(json, "refresh_token")
        if (access == null || refresh == null) return DeviceCodeResult.Failed(NO_REFRESH_TOKEN)
        try {
            synchronized(stateLock) {
                store.write(refresh)
                cache(access, json)
            }
        } catch (_: Exception) {
            return DeviceCodeResult.Failed(NOT_SAVED)
        }
        return DeviceCodeResult.Connected(emailFromIdToken(string(json, "id_token")))
    }

    /**
     * A valid access token: the cached one until a minute before it expires, otherwise a new one from
     * the stored refresh token. One refresh at a time; callers waiting on the lock reuse its result.
     *
     * @throws MailAuthRequiredException when there is no stored token or client id, or Microsoft rejects
     *   the token (`invalid_grant`, which also clears it), unless a sign-in that finished meanwhile
     *   left a valid access token, which is returned instead.
     * @throws IOException on a network failure (the stored token is kept).
     * @throws MailHttpException on any other token-endpoint failure (the stored token is kept).
     */
    suspend fun freshAccessToken(): String {
        valid()?.let { return it }
        return refreshLock.withLock { valid() ?: refresh() }
    }

    private fun valid(): String? = cached?.takeIf { nowMs() < it.expiresAtMs - EXPIRY_MARGIN_MS }?.value

    private suspend fun refresh(): String {
        val refreshToken = store.read() ?: throw authRequired()
        val id = clientId()?.trim().orEmpty()
        if (id.isEmpty()) throw authRequired()
        val reply =
            post(
                "$AUTHORITY/token",
                FormBody
                    .Builder()
                    .add("grant_type", "refresh_token")
                    .add("client_id", id)
                    .add("refresh_token", refreshToken)
                    .add("scope", SCOPES)
                    .build(),
            )
        val json = reply.json
        if (reply.status in 200..299) {
            val access = json?.let { string(it, "access_token") } ?: throw MailHttpException(reply.status, "Microsoft sent an unreadable token response")
            synchronized(stateLock) {
                // Disconnected, or signed in again, while this request was out: this result is stale and
                // is neither stored nor cached. A newer sign-in's access token is still fine to use.
                if (store.read() != refreshToken) return valid() ?: throw authRequired()
                // Rotation: persist the new refresh token before handing out the access token, and
                // never replace a stored token with a blank one. If it cannot be saved, the old refresh
                // token stays in the store and the new access token is still good for this hour.
                string(json, "refresh_token")?.let { rotated ->
                    try {
                        store.write(rotated)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                    }
                }
                cache(access, json)
            }
            return access
        }
        val error = json?.let { string(it, "error") }
        if (error == "invalid_grant") {
            synchronized(stateLock) {
                // Only forget the token Microsoft rejected, not one a sign-in stored meanwhile; that
                // sign-in's access token is still fine to use.
                if (store.read() != refreshToken) return valid() ?: throw authRequired()
                store.clear()
                cached = null
            }
            throw authRequired()
        }
        throw MailHttpException(reply.status, "Microsoft token refresh failed (HTTP ${reply.status}${safeCode(error)?.let { ", $it" } ?: ""})")
    }

    fun clearAccessToken() {
        cached = null
    }

    fun disconnect() {
        synchronized(stateLock) {
            store.clear()
            cached = null
        }
    }

    private fun cache(
        access: String,
        json: JSONObject,
    ) {
        val expiresInSec =
            json
                .optLong("expires_in", DEFAULT_TOKEN_LIFETIME_SEC)
                .let { if (it <= 0) DEFAULT_TOKEN_LIFETIME_SEC else it.coerceIn(MIN_TOKEN_LIFETIME_SEC, MAX_TOKEN_LIFETIME_SEC) }
        cached = CachedToken(access, nowMs() + expiresInSec * 1000L)
    }

    private fun requireClientId(): String = clientId()?.trim().orEmpty().ifEmpty { throw IllegalStateException(NO_CLIENT_ID) }

    private fun authRequired() = MailAuthRequiredException("Open the app and reconnect Outlook", "graph", "Outlook")

    /** POSTs a form. A network failure is an [IOException]; cancelling the caller cancels the call. */
    private suspend fun post(
        url: String,
        form: FormBody,
    ): Reply =
        suspendCancellableCoroutine { continuation ->
            val call =
                client.newCall(
                    Request
                        .Builder()
                        .url(url)
                        .header("Accept", "application/json")
                        .post(form)
                        .build(),
                )
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        val reply =
                            try {
                                response.use { Reply(it.code, parse(it.body?.string())) }
                            } catch (e: IOException) {
                                continuation.resumeWithException(e)
                                return
                            }
                        continuation.resume(reply)
                    }
                },
            )
        }

    companion object {
        const val AUTHORITY = "https://login.microsoftonline.com/common/oauth2/v2.0"
        const val SCOPES = "offline_access User.Read Mail.ReadWrite Mail.Send"

        private const val DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        private const val SLOW_DOWN_MS = 5_000L
        private const val EXPIRY_MARGIN_MS = 60_000L
        private const val DEFAULT_INTERVAL_SEC = 5
        private const val DEFAULT_EXPIRES_SEC = 900
        private const val DEFAULT_TOKEN_LIFETIME_SEC = 3600L
        private const val MAX_INTERVAL_SEC = 60
        private const val MAX_EXPIRES_SEC = 1800
        private const val MIN_TOKEN_LIFETIME_SEC = 60L
        private const val MAX_TOKEN_LIFETIME_SEC = 86_400L

        // Server-supplied timings are bounded so a bad value cannot make polling hot, endless or overflow.
        private fun pollIntervalSec(value: Int): Int = if (value <= 0) DEFAULT_INTERVAL_SEC else value.coerceAtMost(MAX_INTERVAL_SEC)

        private fun codeLifetimeSec(value: Int): Int = if (value <= 0) DEFAULT_EXPIRES_SEC else value.coerceAtMost(MAX_EXPIRES_SEC)

        private const val NO_CLIENT_ID = "Enter your Microsoft app ID first"
        private const val EXPIRED = "The code expired. Start again to get a new code."
        private const val UNREADABLE = "Microsoft sent a response the app could not read. Try again."
        private const val NO_REFRESH_TOKEN = "Microsoft did not grant offline access. Try again."
        private const val NOT_SAVED = "The sign-in could not be saved on this phone. Try again."

        /** A fixed message per OAuth error code. Microsoft's own description is never shown (it can be long and carry ids). */
        private fun failureMessage(error: String?): String =
            when (error) {
                "expired_token" -> EXPIRED
                "authorization_declined", "access_denied" -> "Sign-in was declined."
                "bad_verification_code" -> "Microsoft did not recognize the code. Start again."
                "invalid_client", "unauthorized_client" ->
                    "Microsoft rejected the app ID. Check the app ID and that public client flows are allowed in the app registration."
                "invalid_scope" -> "Microsoft rejected the mail permissions the app asked for."
                "temporarily_unavailable" -> "Microsoft sign-in is temporarily unavailable. Try again later."
                null -> UNREADABLE
                else -> "Microsoft sign-in failed${safeCode(error)?.let { " ($it)" } ?: ""}. Try again."
            }

        /** An OAuth error code, only when it has the shape of one (lowercase words), so nothing else can be echoed. */
        private fun safeCode(error: String?): String? = error?.takeIf { ERROR_CODE.matches(it) }

        private val ERROR_CODE = Regex("[a-z_]{1,40}")

        private fun parse(body: String?): JSONObject? =
            try {
                body?.takeIf { it.isNotBlank() }?.let { JSONObject(it) }
            } catch (_: Exception) {
                null
            }

        /** A non-blank string field. org.json's optString turns a JSON null into "null"; this does not. */
        private fun string(
            json: JSONObject,
            name: String,
        ): String? = (json.opt(name) as? String)?.takeIf { it.isNotBlank() }

        /** The signed-in address from an id token's claims, for display only (the token is not verified here). */
        private fun emailFromIdToken(idToken: String?): String? {
            val payload = idToken?.split('.')?.getOrNull(1) ?: return null
            return try {
                val claims = JSONObject(String(Base64.getUrlDecoder().decode(payload.trimEnd('=')), Charsets.UTF_8))
                listOf("email", "preferred_username").firstNotNullOfOrNull { name -> string(claims, name)?.takeIf { '@' in it } }
            } catch (_: Exception) {
                null
            }
        }
    }
}
