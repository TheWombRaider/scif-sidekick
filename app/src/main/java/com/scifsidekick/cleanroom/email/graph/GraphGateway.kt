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
 * [MailTransport] over Microsoft Graph (Outlook.com and Microsoft 365), polled. Ids are
 * `graph:<native id>` (see [MailIds]), immutable (`Prefer: IdType="ImmutableId"`) so a sent draft keeps its id.
 *
 * OData filters stay on `receivedDateTime ge`, `isRead eq` and `internetMessageId eq`; subject and
 * sender matching happens here so Graph cannot reject a query as too complex. No token or body is logged.
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

    // In memory only; process death costs one full sweep. The cursor is Graph's own newest
    // receivedDateTime, so a wrong phone clock cannot open a gap.
    @Volatile private var cursorMs = 0L

    @Volatile private var lastFullSweepMs = 0L

    @Volatile private var loggedMessageIdRewrite = false

    @Volatile private var loggedUntrustedLink = false

    @Volatile private var loggedMissingAuthResults = false

    // Message-IDs Outlook stamped in place of ours, by delivery key, for findSent. Bounded; lost on process death.
    private val rewrittenIds = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_REMEMBERED_REWRITES
    }

    override fun clearSession() {
        oauth.clearAccessToken()
        profileEmailAddress = null
        cursorMs = 0L
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
        val built =
            withContext(Dispatchers.IO) {
                MimeMessageBuilder.build(payload, attachmentPaths, deliveryKey, fromAddress, maxAttachmentBytes = MAX_ATTACHMENT_SOURCE_BYTES, providerLabel = "Outlook")
            }
        // Graph takes MIME as standard base64 in a text/plain body; the byte form keeps OkHttp from adding a charset.
        val mime = Base64.getEncoder().encodeToString(built.rawBase64Url.base64UrlDecodeBytes())
        // Graph refuses bodies over about 4 MB; the attachment budget keeps under it, but a huge text body can still exceed it.
        if (mime.length > MAX_REQUEST_BODY_CHARS) throw GraphApiException(413, "the message is too large for Outlook to send")
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
            // Delete the draft only after a definite failure; after an ambiguous one it may have gone out.
            if (failure !is CancellationException && isDefinite(failure)) safely { deleteIfStillDraft(id) }
            throw failure
        }
        return MailReceipt(
            messageId = MailIds.scoped(PROVIDER, id),
            threadId = MailIds.scoped(PROVIDER, string(draft, "conversationId") ?: id),
            rfcMessageId = stamped,
            reconciled = false,
        )
    }

    /**
     * Deletes [id] only when Graph still reports it as an unsent draft. A sent message keeps the
     * draft's id, and deleting the Sent copy would destroy `findSent`'s evidence and invite a duplicate.
     */
    private suspend fun deleteIfStillDraft(id: String) {
        val state = JSONObject(get(url("me", "messages", id) { query("\$select", "isDraft") }))
        if (state.opt("isDraft") != true) return
        execute(Request.Builder().url(url("me", "messages", id)).delete())
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
     * Read and unread mail alike: a 90-day sweep first and every 6 h, otherwise everything received
     * since 10 minutes before the newest message an earlier poll saw.
     *
     * A poll that could not read its whole window keeps the old cursor and pulls the next sweep
     * forward to [SWEEP_SOON_MS]. A sweep always moves the cursor; one that hit the candidate cap reruns next poll.
     */
    override suspend fun pollReplies(knownMessageIds: Set<String>): MailPollResult {
        if (!isAvailable) return MailPollResult(emptyList(), emptyList())
        val now = nowMs()
        val previousCursor = cursorMs
        val fullSweep = previousCursor == 0L || lastFullSweepMs == 0L || now - lastFullSweepMs >= FULL_SWEEP_INTERVAL_MS
        val since = if (fullSweep) now - SWEEP_WINDOW_MS else previousCursor - POLL_OVERLAP_MS
        val listing = listInbox("receivedDateTime ge ${iso(since)}")
        val replies = mutableListOf<MailMessage>()
        val failures = mutableListOf<String>()
        var fetched = 0
        var capped = false
        for (summary in listing.items) {
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
        val incomplete = listing.truncated || capped
        if (fullSweep || !incomplete) {
            val newest = listing.items.mapNotNull { receivedMs(it) }.maxOrNull()
            if (newest != null && newest > previousCursor) cursorMs = newest
        }
        if (fullSweep) {
            // A sweep that hit the per-poll fetch cap left candidates unread: sweep again next poll.
            lastFullSweepMs = if (capped) 0L else now
        } else if (incomplete) {
            // Unseen mail may remain in this window: sweep within SWEEP_SOON_MS instead of 6 h.
            lastFullSweepMs = minOf(lastFullSweepMs, now - FULL_SWEEP_INTERVAL_MS + SWEEP_SOON_MS)
        }
        return MailPollResult(replies, failures)
    }

    private fun receivedMs(summary: JSONObject): Long? =
        string(summary, "receivedDateTime")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    /** Unread mail from the last 2 days whose subject has a tag and whose sender is listed, newest first. */
    override suspend fun findCommands(search: CommandSearch): CommandScan {
        if (!isAvailable) return CommandScan(emptyList(), emptyList())
        val matches = mutableListOf<String>()
        for (summary in listInbox("receivedDateTime ge ${iso(nowMs() - COMMAND_WINDOW_MS)} and isRead eq false").items) {
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
                .items
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
        // Graph converts an HTML-only body to text because of the Prefer header. A body too large to
        // read is dropped (as Gmail's adapter drops one), and any image is still delivered.
        val body =
            try {
                val json = JSONObject(get(url("me", "messages", id) { query("\$select", "body") }, preferText = true))
                json.optJSONObject("body")?.let { string(it, "content") }.orEmpty().take(MAX_BODY_CHARACTERS)
            } catch (_: GraphResponseTooLargeException) {
                ""
            }
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
                    // Only file attachments carry bytes. An entry without a type annotation is judged
                    // by its content type; the download below is still size-checked.
                    string(attachment, "@odata.type").let { it == null || it == FILE_ATTACHMENT } &&
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
        if (fromHeader.isNotEmpty() && headers.none { it.first.equals("Authentication-Results", ignoreCase = true) } && !loggedMissingAuthResults) {
            loggedMissingAuthResults = true
            safely { log("Outlook messages arrived without an Authentication-Results header, so senders cannot be authenticated") }
        }
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

    private class Listing(
        val items: List<JSONObject>,
        /** True when more pages were pending but not read (page cap, or a refused paging link). */
        val truncated: Boolean,
    )

    /** Inbox summaries, newest first, across at most [maxPages] pages of [PAGE_SIZE]. */
    private suspend fun listInbox(
        filter: String,
        maxPages: Int = MAX_POLL_PAGES,
    ): Listing {
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
            val link = string(page, "@odata.nextLink") ?: return Listing(items, truncated = false)
            next = trustedNextLink(link)
            if (next == null) {
                // Keep what was read; the bearer token never follows the link.
                if (!loggedUntrustedLink) {
                    loggedUntrustedLink = true
                    safely { log("Outlook returned a paging link outside graph.microsoft.com; paging stopped") }
                }
                return Listing(items, truncated = true)
            }
        }
        return Listing(items, truncated = next != null)
    }

    /** A nextLink is followed only on the Graph endpoint itself, so the bearer token never goes elsewhere. */
    private fun trustedNextLink(link: String): HttpUrl? {
        val parsed = runCatching { link.toHttpUrl() }.getOrNull() ?: return null
        val trusted = parsed.scheme == "https" && parsed.host == GRAPH_HOST && parsed.port == 443 && parsed.encodedPath.startsWith("/v1.0/")
        return parsed.takeIf { trusted }
    }

    private fun nativeIdOf(messageId: String): String {
        require(MailIds.providerOf(messageId) == PROVIDER) { "Not an Outlook message id" }
        return MailIds.nativeId(messageId)
    }

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
     * Sends [request] with a bearer token. A 401 retries once with a fresh token (a second means reconnect);
     * 429, and 503 when [retryUnavailable], wait out `Retry-After` (max 60 s) and retry once.
     * Other non-2xx is a [GraphApiException]; a network failure is rethrown as the [IOException].
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
                if (source != null && source.request(maxBytes + 1)) throw GraphResponseTooLargeException(response.code, maxBytes)
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
        const val SWEEP_SOON_MS = 5L * 60_000L
        const val COMMAND_WINDOW_MS = 2L * 24 * 60 * 60_000L

        const val MAX_RETRY_AFTER_SEC = 60L
        const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024
        const val MAX_IMAGE_BYTES = 8L * 1024 * 1024

        // An 8 MB image is about 10.7 MB as base64 inside the attachment's JSON.
        const val MAX_ATTACHMENT_RESPONSE_BYTES = 12L * 1024 * 1024

        // Graph refuses bodies over about 4 MB. Attachments are base64'd twice (MIME with CRLFs, then
        // the request), about x1.825; floor(3_500_000 / 1.83) keeps the body under 3.5 MB.
        const val MAX_ATTACHMENT_SOURCE_BYTES = 1_912_568L

        // Headroom above the 3.5 MB target for headers and a long text body; above this, nothing is sent.
        const val MAX_REQUEST_BODY_CHARS = 3_800_000
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
