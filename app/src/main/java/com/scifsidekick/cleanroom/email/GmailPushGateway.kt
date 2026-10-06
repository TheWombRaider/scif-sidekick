package com.scifsidekick.cleanroom.email

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Gmail push (beta): a lightweight companion to [GmailGateway] that lets [ForwardingService]
 * learn "the mailbox changed" in roughly a Pub/Sub pull interval instead of waiting up to the
 * existing 30s reply-poll timer. Deliberately narrow in scope -- it never parses a Pub/Sub
 * message payload or Gmail's historyId, it only ever answers "did anything arrive, yes or no."
 * A `true` from [pollPush] is used purely to trigger an immediate, completely unmodified
 * [GmailGateway.unreadReplies]/reply-processing pass; the actual dedupe/authorization/routing
 * logic in that path is untouched by this class. If push is never configured, or the calls here
 * fail for any reason, callers must keep relying on the existing 30s poll -- this class has no
 * side effect on that path either way.
 *
 * Mirrors [GmailGateway]'s own request/error-handling shape (same timeouts, same 401 ->
 * clear-token-and-reauthorize handling, same [GmailApiException]) rather than reusing its private
 * internals, so the audited, safety-critical [GmailGateway] itself never needs to change for this.
 */
class GmailPushGateway(
    private val oauth: GmailOAuthManager,
    private val debug: DebugControls,
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build(),
) {
    /** Calls `users.me.watch` against [topicName] (a full resource name, e.g.
     *  "projects/P/topics/T"). Returns the new expiration Gmail assigned, as epoch ms -- Gmail
     *  caps this at 7 days out, hence [com.scifsidekick.cleanroom.service.GmailWatchRenewalWorker]
     *  re-calling this daily. */
    suspend fun startWatch(topicName: String): Long {
        if (debug.fakeEmailTransport) return System.currentTimeMillis() + WATCH_TTL_MS
        val token = oauth.freshAccessToken()
        val body =
            JSONObject()
                .put("topicName", topicName)
                .put("labelIds", JSONArray().put("INBOX"))
                .toString()
        val response =
            executeAuthorized(
                token,
                Request
                    .Builder()
                    .url("https://gmail.googleapis.com/gmail/v1/users/me/watch")
                    .header("Authorization", "Bearer $token")
                    .post(body.toRequestBody(JSON))
                    .build(),
            )
        // Gmail returns "expiration" as a string-encoded epoch-ms, not a JSON number.
        return JSONObject(response).getString("expiration").toLong()
    }

    /** Best-effort: tells Gmail to stop publishing to whatever topic is currently watched. Safe
     *  to call even if no watch is active (Gmail just reports nothing to stop); failures are
     *  swallowed since an unstopped watch is a non-issue -- it simply expires within 7 days on
     *  its own and this app never reads its payload regardless. */
    suspend fun stopWatch() {
        if (debug.fakeEmailTransport || !oauth.isAuthorized) return
        runCatching {
            val token = oauth.freshAccessToken()
            executeAuthorized(
                token,
                Request
                    .Builder()
                    .url("https://gmail.googleapis.com/gmail/v1/users/me/stop")
                    .header("Authorization", "Bearer $token")
                    .post("".toRequestBody(null))
                    .build(),
            )
        }
    }

    /** A short, synchronous pull against [subscriptionName] (a full resource name, e.g.
     *  "projects/P/subscriptions/S"). Immediately acknowledges anything it receives -- this class
     *  only ever needs to know "did the mailbox change," not what changed -- and returns whether
     *  at least one message came back. Returns `false` (never throws) on an empty pull; throws on
     *  a genuine request failure (misconfigured resource name, missing IAM grant, network error)
     *  so the caller can decide how to log/cool down. */
    suspend fun pollPush(subscriptionName: String): Boolean {
        if (debug.fakeEmailTransport) return false
        val token = oauth.freshAccessToken(includePubSub = true)
        val pullBody = JSONObject().put("maxMessages", MAX_MESSAGES_PER_PULL).toString()
        val pullResponse =
            executeAuthorized(
                token,
                Request
                    .Builder()
                    .url("https://pubsub.googleapis.com/v1/$subscriptionName:pull")
                    .header("Authorization", "Bearer $token")
                    .post(pullBody.toRequestBody(JSON))
                    .build(),
            )
        val received = JSONObject(pullResponse).optJSONArray("receivedMessages") ?: JSONArray()
        if (received.length() == 0) return false

        val ackIds = JSONArray()
        for (i in 0 until received.length()) {
            ackIds.put(received.getJSONObject(i).getString("ackId"))
        }
        val ackBody = JSONObject().put("ackIds", ackIds).toString()
        executeAuthorized(
            token,
            Request
                .Builder()
                .url("https://pubsub.googleapis.com/v1/$subscriptionName:acknowledge")
                .header("Authorization", "Bearer $token")
                .post(ackBody.toRequestBody(JSON))
                .build(),
        )
        return true
    }

    private suspend fun executeAuthorized(
        token: String,
        request: Request,
    ): String =
        try {
            execute(request)
        } catch (error: GmailApiException) {
            if (error.statusCode == 401) {
                oauth.clearRejectedToken(token)
                oauth.markAuthorizationRequired()
                throw ReauthorizationRequiredException()
            }
            throw error
        }

    private suspend fun execute(request: Request): String =
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw GmailApiException(response.code, body.take(1_000))
                body
            }
        }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val MAX_MESSAGES_PER_PULL = 5
        const val WATCH_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
