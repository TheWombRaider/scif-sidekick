package com.scifsidekick.cleanroom.email.graph

import com.scifsidekick.cleanroom.email.BounceNotice
import com.scifsidekick.cleanroom.email.CommandScan
import com.scifsidekick.cleanroom.email.CommandSearch
import com.scifsidekick.cleanroom.email.MailAuthRequiredException
import com.scifsidekick.cleanroom.email.MailHttpException
import com.scifsidekick.cleanroom.email.MailIds
import com.scifsidekick.cleanroom.email.MailMessage
import com.scifsidekick.cleanroom.email.MailPollResult
import com.scifsidekick.cleanroom.email.MailReceipt
import com.scifsidekick.cleanroom.email.MailTransport
import com.scifsidekick.cleanroom.email.MimeMessageBuilder
import com.scifsidekick.cleanroom.messaging.EmailPayload
import com.scifsidekick.cleanroom.util.ComposeAuthorization
import com.scifsidekick.cleanroom.util.RemoteCommandPlanner
import com.scifsidekick.cleanroom.util.base64UrlDecodeBytes
import com.scifsidekick.cleanroom.util.suspendRunCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * [MailTransport] over Microsoft Graph (Outlook.com and Microsoft 365), polled (Graph has no push
 * here). Every id it returns is `graph:<native id>` (see [MailIds]); native ids are immutable ids
 * (`Prefer: IdType="ImmutableId"` on every request), so a sent draft keeps its id in Sent Items.
 *
 * All OData filters are limited to `receivedDateTime ge`, `isRead eq` and `internetMessageId eq`;
 * subject and sender matching is done here, so the service cannot reject a query as too complex.
 *
 * No token or message body is ever logged or put in an exception message.
 */
class GraphGateway(
    private val oauth: MsOAuthManager,
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val log: suspend (String) -> Unit = {},
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : MailTransport {
    override val providerId: String = PROVIDER
    override val displayName: String = "Outlook"
    override val isAvailable: Boolean get() = oauth.isAuthorized

    @Volatile private var profileEmailAddress: String? = null

    // In memory only: losing them to process death just costs one full sweep on the next poll.
    @Volatile private var lastPollMs = 0L

    @Volatile private var lastFullSweepMs = 0L

    @Volatile private var loggedMessageIdRewrite = false

    // Message-IDs Outlook stamped in place of ours, by delivery key, so findSent can still find a send
    // from this process after an ambiguous failure. Bounded; lost on process death.
    private val rewrittenIds = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_REMEMBERED_REWRITES
    }

    override fun clearSession() {
        oauth.clearAccessToken()
        profileEmailAddress = null
        lastPollMs = 0L
        lastFullSweepMs = 0L
    }

    override suspend fun accountEmail(): String? {
        if (!isAvailable) return null
        return suspendRunCatching { profileEmail() }.getOrNull()
    }

    private suspend fun profileEmail(): String {
        profileEmailAddress?.let { return it }
        val me = JSONObject(get(url("me") { query("\$select", "mail,userPrincipalName") }))
        val address = string(me, "mail") ?: string(me, "userPrincipalName") ?: throw GraphApiException(200, "the profile has no address")
        return address.also { profileEmailAddress = it }
    }

    override suspend fun send(
        payload: EmailPayload,
        attachmentPaths: List<String>,
        deliveryKey: String,
        verifyPriorDelivery: Boolean,
    ): MailReceipt {
        if (!isAvailable) throw authRequired()
        if (verifyPriorDelivery) findSentFor(deliveryKey)?.let { return it }
        val fromAddress = profileEmail()
        val built = withContext(Dispatchers.IO) { MimeMessageBuilder.build(payload, attachmentPaths, deliveryKey, fromAddress) }
        // Graph takes MIME as standard base64 in a text/plain body (the byte form keeps OkHttp from
        // adding a charset parameter to the content type).
        val mime = Base64.getEncoder().encodeToString(built.rawBase64Url.base64UrlDecodeBytes())
        val draft =
            JSONObject(
                execute(
                    Request
                        .Builder()
                        .url(url("me", "messages"))
                        .post(mime.toByteArray(Charsets.US_ASCII).toRequestBody(TEXT_PLAIN)),
                ),
            )
        val id = string(draft, "id") ?: throw GraphApiException(201, "the draft has no id")
        val expected = MimeMessageBuilder.rfcMessageId(deliveryKey)
        val stamped = string(draft, "internetMessageId")?.trim()?.trim('<', '>')?.takeIf { it.isNotBlank() } ?: expected
        if (stamped != expected) {
            synchronized(rewrittenIds) { rewrittenIds[deliveryKey] = stamped }
            if (!loggedMessageIdRewrite) {
                loggedMessageIdRewrite = true
                safely { log("Outlook replaced the Message-ID of a sent message; reply routing uses the one Outlook assigned") }
            }
        }
        try {
            execute(
                Request.Builder().url(url("me", "messages", id, "send")).post(EMPTY_BODY),
                retryUnavailable = false,
            )
        } catch (failure: Exception) {
            // A definite failure means the draft was not sent: remove it so a retry does not leave
            // another one behind. After an ambiguous one it may have gone out, so it stays.
            if (failure !is CancellationException && isDefinite(failure)) {
                safely { execute(Request.Builder().url(url("me", "messages", id)).delete()) }
            }
            throw failure
        }
        return MailReceipt(
            messageId = MailIds.scoped(PROVIDER, id),
            threadId = MailIds.scoped(PROVIDER, string(draft, "conversationId") ?: id),
            rfcMessageId = stamped,
            reconciled = false,
        )
    }

    override suspend fun findSent(deliveryKey: String): MailReceipt? {
        if (!isAvailable) return null
        return findSentFor(deliveryKey)
    }

    private suspend fun findSentFor(deliveryKey: String): MailReceipt? {
        val candidates = listOfNotNull(MimeMessageBuilder.rfcMessageId(deliveryKey), synchronized(rewrittenIds) { rewrittenIds[deliveryKey] }).distinct()
        for (rfc in candidates) {
            // Exchange stores the angle brackets as part of the value; a bare form is tried as well
            // in case a mailbox stores it without them.
            for (value in listOf("<$rfc>", rfc)) {
                val list =
                    JSONObject(
                        get(
                            url("me", "mailFolders", "sentitems", "messages") {
                                query("\$filter", "internetMessageId eq '${value.replace("'", "''")}'")
                                query("\$select", "id,conversationId,internetMessageId")
                            },
                        ),
                    ).optJSONArray("value") ?: continue
                if (list.length() == 0) continue
                val match = list.getJSONObject(0)
                val id = string(match, "id") ?: continue
                return MailReceipt(
                    messageId = MailIds.scoped(PROVIDER, id),
                    threadId = MailIds.scoped(PROVIDER, string(match, "conversationId") ?: id),
                    rfcMessageId = string(match, "internetMessageId")?.trim()?.trim('<', '>') ?: rfc,
                    reconciled = true,
                )
            }
        }
        return null
    }

    /**
     * Read and unread mail alike (see [MailTransport.pollReplies]): a full 90-day sweep first and
     * every 6 h, otherwise everything received since 10 minutes before the last successful poll.
     */
    override suspend fun pollReplies(knownMessageIds: Set<String>): MailPollResult {
        if (!isAvailable) return MailPollResult(emptyList(), emptyList())
        val now = nowMs()
        val fullSweep = lastPollMs == 0L || now - lastFullSweepMs >= FULL_SWEEP_INTERVAL_MS
        val since = if (fullSweep) now - SWEEP_WINDOW_MS else lastPollMs - POLL_OVERLAP_MS
        val replies = mutableListOf<MailMessage>()
        val failures = mutableListOf<String>()
        var fetched = 0
        var capped = false
        for (summary in listInbox("receivedDateTime ge ${iso(since)}")) {
            val subject = string(summary, "subject").orEmpty()
            if (!subject.contains("SCIF", ignoreCase = true) && !subject.contains("TEXT", ignoreCase = true)) continue
            val id = MailIds.scoped(PROVIDER, string(summary, "id") ?: continue)
            if (id in knownMessageIds) continue
            if (fetched >= MAX_NEW_CANDIDATES_PER_POLL) {
                capped = true
                break
            }
            fetched++
            try {
                replies += fetchMetadata(MailIds.nativeId(id))
            } catch (required: MailAuthRequiredException) {
                throw required
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failures += "$id: ${(failure.message ?: failure.javaClass.simpleName).take(300)}"
            }
        }
        lastPollMs = now
        if (fullSweep) lastFullSweepMs = now
        // Unseen candidates may remain beyond this poll's cap, so the next poll sweeps again.
        if (capped) lastFullSweepMs = 0L
        return MailPollResult(replies, failures)
    }

    /** Unread mail from the last 2 days whose subject has a tag and whose sender is listed, newest first. */
    override suspend fun findCommands(search: CommandSearch): CommandScan {
        if (!isAvailable) return CommandScan(emptyList(), emptyList())
        val matches = mutableListOf<String>()
        for (summary in listInbox("receivedDateTime ge ${iso(nowMs() - COMMAND_WINDOW_MS)} and isRead eq false")) {
            val subject = string(summary, "subject").orEmpty()
            if (search.tags.none { subject.contains(it, ignoreCase = true) }) continue
            val sender = ComposeAuthorization.canonicalAddress(fromAddress(summary)) ?: continue
            if (sender !in search.senders) continue
            matches += string(summary, "id") ?: continue
            if (matches.size >= RemoteCommandPlanner.MAX_CANDIDATES) break
        }
        val candidates = mutableListOf<MailMessage>()
        val unreadable = mutableListOf<String>()
        for (id in matches) {
            try {
                candidates += fetchMetadata(id)
            } catch (required: MailAuthRequiredException) {
                throw required
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                unreadable += MailIds.scoped(PROVIDER, id)
            }
        }
        return CommandScan(candidates, unreadable)
    }

    /**
     * Unread recent mail that looks like a delivery failure (sender or subject), reported only when
     * its text or headers quote one of this installation's own Message-IDs. Best effort, like Gmail's.
     */
    override suspend fun checkForBounces(): List<BounceNotice> {
        if (!isAvailable) return emptyList()
        val candidates =
            listInbox("receivedDateTime ge ${iso(nowMs() - BOUNCE_WINDOW_MS)} and isRead eq false", BOUNCE_PAGES)
                .filter { summary ->
                    val sender = fromAddress(summary).orEmpty()
                    val subject = string(summary, "subject").orEmpty()
                    BOUNCE_SENDERS.any { sender.contains(it, ignoreCase = true) } ||
                        BOUNCE_SUBJECTS.any { subject.contains(it, ignoreCase = true) }
                }.mapNotNull { string(it, "id") }
                .take(BOUNCE_CANDIDATES)
        val notices = mutableListOf<BounceNotice>()
        for (id in candidates) {
            val notice =
                suspendRunCatching {
                    val message =
                        JSONObject(
                            get(url("me", "messages", id) { query("\$select", "body,internetMessageHeaders") }, preferText = true),
                        )
                    val text =
                        buildString {
                            append(message.optJSONObject("body")?.let { string(it, "content") }.orEmpty().take(MAX_BODY_CHARACTERS)).append('\n')
                            headerPairs(message).forEach { append(it.second).append('\n') }
                        }
                    val ownIds = OWN_MESSAGE_ID.findAll(text).map { it.value }.toSet()
                    if (ownIds.isEmpty()) null else BounceNotice(MailIds.scoped(PROVIDER, id), ownIds, text.trim().take(500))
                }.getOrNull()
            if (notice != null) notices += notice
        }
        return notices
    }

    override suspend fun markRead(messageId: String) {
        val id = nativeIdOf(messageId)
        execute(
            Request
                .Builder()
                .url(url("me", "messages", id))
                .patch(JSONObject().put("isRead", true).toString().toRequestBody(JSON)),
        )
    }

    /** Called only for an already-authorized message: the plain-text body and the first image. */
    override suspend fun fetchContent(message: MailMessage): MailMessage {
        val id = nativeIdOf(message.id)
        val json = JSONObject(get(url("me", "messages", id) { query("\$select", "body") }, preferText = true))
        // Graph converts an HTML-only body to text because of the Prefer header.
        val body = json.optJSONObject("body")?.let { string(it, "content") }.orEmpty().take(MAX_BODY_CHARACTERS)
        val image = fetchImage(id)
        return message.copy(body = body, imageMimeType = image?.first, imageBytes = image?.second)
    }

    /**
     * The first file attachment that is an image within [MAX_IMAGE_BYTES]. The list is read without
     * content, so a large non-image attachment never has to be downloaded; the chosen one is fetched
     * by id and its decoded size checked again, since the listed size is only what the server says.
     */
    private suspend fun fetchImage(id: String): Pair<String, ByteArray>? {
        val list =
            JSONObject(get(url("me", "messages", id, "attachments") { query("\$select", "id,name,contentType,size") }))
                .optJSONArray("value") ?: return null
        val chosen =
            (0 until list.length())
                .map { list.getJSONObject(it) }
                .firstOrNull { attachment ->
                    string(attachment, "@odata.type") == FILE_ATTACHMENT &&
                        string(attachment, "contentType").orEmpty().startsWith("image/", ignoreCase = true) &&
                        attachment.optLong("size", Long.MAX_VALUE) in 0..MAX_IMAGE_BYTES
                } ?: return null
        val attachmentId = string(chosen, "id") ?: return null
        val full = JSONObject(get(url("me", "messages", id, "attachments", attachmentId), maxBytes = MAX_ATTACHMENT_RESPONSE_BYTES))
        val encoded = string(full, "contentBytes") ?: return null
        if (encoded.length > MAX_IMAGE_BYTES / 3 * 4 + 4) return null
        val bytes = runCatching { Base64.getMimeDecoder().decode(encoded) }.getOrNull() ?: return null
        if (bytes.size > MAX_IMAGE_BYTES) return null
        return string(chosen, "contentType")!! to bytes
    }

    /** Only the routing and authentication headers of an untrusted inbox message. */
    private suspend fun fetchMetadata(id: String): MailMessage {
        val json =
            JSONObject(
                get(url("me", "messages", id) { query("\$select", "internetMessageHeaders,from,subject,conversationId,internetMessageId") }),
            )
        val headers = headerPairs(json)
        val references =
            headers
                .filter { it.first.equals("In-Reply-To", true) || it.first.equals("References", true) }
                .sortedBy { if (it.first.equals("In-Reply-To", true)) 0 else 1 }
                .flatMap { (_, value) -> MESSAGE_ID.findAll(value).map { it.groupValues[1] }.toList() }
                .take(MAX_REFERENCES)
                .toSet()
        val fromHeaders = headers.filter { it.first.equals("From", ignoreCase = true) }.map { it.second }
        // Several From headers are ambiguous: an empty value, which never authenticates.
        val fromHeader = if (fromHeaders.isEmpty()) fromObjectHeader(json) else fromHeaders.singleOrNull().orEmpty()
        return MailMessage(
            id = MailIds.scoped(PROVIDER, id),
            threadId = MailIds.scoped(PROVIDER, string(json, "conversationId") ?: id),
            subject = string(json, "subject").orEmpty(),
            body = "",
            referencedMessageIds = references,
            rfcMessageId = MESSAGE_ID.find(string(json, "internetMessageId").orEmpty())?.groupValues?.get(1).orEmpty(),
            fromHeader = fromHeader,
            authenticatedFromAddress = GraphAuthentication.authenticatedFrom(fromHeader, headers),
        )
    }

    /** `Name <address>` (or the bare address) from Graph's `from` object; never from `sender`. */
    private fun fromObjectHeader(json: JSONObject): String {
        val address = fromAddress(json) ?: return ""
        val name =
            json
                .optJSONObject("from")
                ?.optJSONObject("emailAddress")
                ?.let { string(it, "name") }
                ?.replace(UNSAFE_NAME, " ")
                ?.trim()
                ?.takeIf { it.isNotEmpty() && !it.equals(address, ignoreCase = true) }
        return if (name == null) address else "$name <$address>"
    }

    private fun fromAddress(json: JSONObject): String? = json.optJSONObject("from")?.optJSONObject("emailAddress")?.let { string(it, "address") }

    private fun headerPairs(json: JSONObject): List<Pair<String, String>> {
        val array = json.optJSONArray("internetMessageHeaders") ?: JSONArray()
        return (0 until array.length()).mapNotNull { index ->
            val header = array.optJSONObject(index) ?: return@mapNotNull null
            val name = string(header, "name") ?: return@mapNotNull null
            name to (header.opt("value") as? String).orEmpty()
        }
    }

    /** Inbox summaries, newest first, across at most [maxPages] pages of [PAGE_SIZE]. */
    private suspend fun listInbox(
        filter: String,
        maxPages: Int = MAX_POLL_PAGES,
    ): List<JSONObject> {
        val items = mutableListOf<JSONObject>()
        var next: HttpUrl? =
            url("me", "mailFolders", "inbox", "messages") {
                query("\$filter", filter)
                query("\$select", SUMMARY_FIELDS)
                query("\$orderby", "receivedDateTime desc")
                query("\$top", PAGE_SIZE.toString())
            }
        var pages = 0
        while (next != null && pages < maxPages) {
            pages++
            val page = JSONObject(get(next))
            val values = page.optJSONArray("value") ?: JSONArray()
            for (index in 0 until values.length()) values.optJSONObject(index)?.let { items += it }
            next = string(page, "@odata.nextLink")?.let(::trustedNextLink)
        }
        return items
    }

    /** A nextLink is followed only on the Graph endpoint itself, so the bearer token never goes elsewhere. */
    private fun trustedNextLink(link: String): HttpUrl {
        val parsed = runCatching { link.toHttpUrl() }.getOrNull()
        if (parsed == null || parsed.scheme != "https" || parsed.host != GRAPH_HOST || !parsed.encodedPath.startsWith("/v1.0/")) {
            throw GraphApiException(200, "Graph returned a paging link outside graph.microsoft.com")
        }
        return parsed
    }

    private fun nativeIdOf(messageId: String): String {
        require(MailIds.providerOf(messageId) == PROVIDER) { "Not an Outlook message id" }
        return MailIds.nativeId(messageId)
    }

    // ---- HTTP ----

    private class UrlBuilder(
        val builder: HttpUrl.Builder,
    ) {
        fun query(
            name: String,
            value: String,
        ) {
            builder.addQueryParameter(name, value)
        }
    }

    private fun url(
        vararg segments: String,
        params: UrlBuilder.() -> Unit = {},
    ): HttpUrl {
        val builder = BASE_URL.toHttpUrl().newBuilder()
        segments.forEach { builder.addPathSegment(it) }
        UrlBuilder(builder).params()
        return builder.build()
    }

    private suspend fun get(
        url: HttpUrl,
        preferText: Boolean = false,
        maxBytes: Long = MAX_RESPONSE_BYTES,
    ): String =
        execute(
            Request
                .Builder()
                .url(url)
                .get()
                .apply { if (preferText) addHeader("Prefer", PREFER_TEXT_BODY) },
            maxBytes = maxBytes,
        )

    /**
     * Sends [request] with a bearer token. A 401 drops the cached token and retries once with a fresh
     * one (a second 401 means the user must reconnect); 429, and 503 when [retryUnavailable], wait out
     * `Retry-After` (at most 60 s) and retry once; anything else that is not 2xx is a [GraphApiException].
     * A network failure is rethrown as the [IOException] it is.
     */
    private suspend fun execute(
        request: Request.Builder,
        retryUnavailable: Boolean = true,
        maxBytes: Long = MAX_RESPONSE_BYTES,
    ): String {
        request.addHeader("Prefer", PREFER_IMMUTABLE_ID)
        var authRetried = false
        var throttleRetried = false
        while (true) {
            val token = accessToken()
            val reply = call(request.header("Authorization", "Bearer $token").build(), maxBytes)
            when {
                reply.code in 200..299 -> return reply.body
                reply.code == 401 && !authRetried -> {
                    authRetried = true
                    oauth.clearAccessToken()
                }
                reply.code == 401 -> throw authRequired()
                (reply.code == 429 || (reply.code == 503 && retryUnavailable)) && !throttleRetried && reply.retryAfterSec != null -> {
                    throttleRetried = true
                    sleep(reply.retryAfterSec.coerceIn(0L, MAX_RETRY_AFTER_SEC) * 1000L)
                }
                else -> throw GraphApiException(reply.code, reply.body.take(1_000))
            }
        }
    }

    /**
     * A token for the next request. A refresh that fails for any reason but a revoked sign-in has
     * happened before anything was sent to Graph, so it is reported as a definite failure (status 0).
     */
    private suspend fun accessToken(): String =
        try {
            oauth.freshAccessToken()
        } catch (required: MailAuthRequiredException) {
            throw required
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw GraphApiException(0, "Outlook sign-in could not be refreshed")
        }

    private class Reply(
        val code: Int,
        val body: String,
        val retryAfterSec: Long?,
    )

    private suspend fun call(
        request: Request,
        maxBytes: Long,
    ): Reply =
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val source = response.body?.source()
                if (source != null && source.request(maxBytes + 1)) {
                    throw GraphApiException(response.code, "the response was larger than ${maxBytes / 1024} KB")
                }
                val body = source?.buffer?.readUtf8().orEmpty()
                Reply(response.code, body, response.header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it >= 0 })
            }
        }

    private fun isDefinite(failure: Throwable): Boolean =
        when (failure) {
            is MailAuthRequiredException -> true
            is MailHttpException -> failure.statusCode !in 500..599
            is UnknownHostException, is ConnectException, is NoRouteToHostException -> true
            else -> false
        }

    private suspend fun safely(block: suspend () -> Unit) {
        suspendRunCatching { block() }
    }

    private fun authRequired() = MailAuthRequiredException("Open the app and reconnect Outlook", PROVIDER, "Outlook")

    private companion object {
        const val PROVIDER = "graph"
        const val GRAPH_HOST = "graph.microsoft.com"
        const val BASE_URL = "https://$GRAPH_HOST/v1.0"
        const val PREFER_IMMUTABLE_ID = "IdType=\"ImmutableId\""
        const val PREFER_TEXT_BODY = "outlook.body-content-type=\"text\""
        const val FILE_ATTACHMENT = "#microsoft.graph.fileAttachment"
        const val SUMMARY_FIELDS = "id,conversationId,subject,from,internetMessageId,receivedDateTime"
        val JSON = "application/json".toMediaType()
        val TEXT_PLAIN = "text/plain".toMediaType()
        val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)

        // Same patterns and caps as the Gmail adapter.
        val MESSAGE_ID = Regex("<([^<>\\s]+)>")
        val OWN_MESSAGE_ID = Regex("scif-[0-9a-f]{40}@scif-sidekick\\.invalid")
        val UNSAFE_NAME = Regex("[\\r\\n<>\"]")
        const val MAX_REFERENCES = 50
        const val MAX_BODY_CHARACTERS = 64_000
        const val MAX_NEW_CANDIDATES_PER_POLL = 100

        // The Gmail adapter's delivery-failure phrases, plus Exchange's own "Undeliverable".
        val BOUNCE_SENDERS = listOf("mailer-daemon", "postmaster")
        val BOUNCE_SUBJECTS =
            listOf("Delivery Status Notification", "Undelivered Mail", "Delivery Failure", "Returned mail", "Delivery has failed", "Undeliverable")
        const val BOUNCE_CANDIDATES = 20
        const val BOUNCE_PAGES = 2
        const val BOUNCE_WINDOW_MS = 7L * 24 * 60 * 60_000L

        const val PAGE_SIZE = 50
        const val MAX_POLL_PAGES = 4
        const val FULL_SWEEP_INTERVAL_MS = 6L * 60 * 60_000L
        const val SWEEP_WINDOW_MS = 90L * 24 * 60 * 60_000L
        const val POLL_OVERLAP_MS = 10L * 60_000L
        const val COMMAND_WINDOW_MS = 2L * 24 * 60 * 60_000L

        const val MAX_RETRY_AFTER_SEC = 60L
        const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024
        const val MAX_IMAGE_BYTES = 8L * 1024 * 1024

        // An 8 MB image is about 10.7 MB as base64 inside the attachment's JSON.
        const val MAX_ATTACHMENT_RESPONSE_BYTES = 12L * 1024 * 1024
        const val MAX_REMEMBERED_REWRITES = 64

        /** UTC ISO-8601 with `Z` and no fractional seconds, the DateTimeOffset literal OData filters take. */
        fun iso(ms: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(ms).truncatedTo(ChronoUnit.SECONDS))

        /** A non-blank string field. org.json's optString turns a JSON null into "null"; this does not. */
        fun string(
            json: JSONObject,
            name: String,
        ): String? = (json.opt(name) as? String)?.takeIf { it.isNotBlank() }
    }
}
