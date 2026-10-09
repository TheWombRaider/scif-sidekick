package com.scifsidekick.cleanroom.email

import android.text.Html
import com.scifsidekick.cleanroom.messaging.EmailPayload
import com.scifsidekick.cleanroom.util.AttachmentStore
import com.scifsidekick.cleanroom.util.RemoteCommandPlanner
import com.scifsidekick.cleanroom.util.base64UrlDecode
import com.scifsidekick.cleanroom.util.base64UrlDecodeBytes
import com.scifsidekick.cleanroom.util.suspendRunCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class GmailGateway(
    private val oauth: GmailOAuthManager,
    private val debug: DebugControls,
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build(),
) : MailTransport {
    @Volatile private var profileEmailAddress: String? = null

    // In memory only: losing it to process death just costs one full sweep on the next poll.
    @Volatile private var historyCursor: String? = null

    @Volatile private var lastFullSweepMs = 0L
    override val providerId: String = MailIds.GMAIL
    override val displayName: String = "Gmail"
    override val isAvailable: Boolean get() = oauth.isAuthorized || debug.fakeEmailTransport

    override fun clearSession() {
        profileEmailAddress = null
        historyCursor = null
        lastFullSweepMs = 0L
    }

    /**
     * The signed-in Gmail address, straight from the Gmail profile endpoint. Deliberately does
     * not rely on the deprecated `AuthorizationResult.toGoogleSignInAccount()` bridge -- that
     * conversion is unreliable for a pure-scope Authorization API grant (no identity/sign-in
     * scope was requested) and can silently come back null even when a grant is active.
     */
    override suspend fun accountEmail(): String? {
        if (!oauth.isAuthorized) return null
        return suspendRunCatching { accountEmail(oauth.freshAccessToken()) }.getOrNull()
    }

    override suspend fun send(
        payload: EmailPayload,
        attachmentPaths: List<String>,
        deliveryKey: String,
        verifyPriorDelivery: Boolean,
    ): MailReceipt {
        if (debug.consumeForcedFailure()) throw GmailApiException(503, "Debug-injected send failure")
        val rfcMessageId = MimeMessageBuilder.rfcMessageId(deliveryKey)
        if (debug.fakeEmailTransport) {
            return MailReceipt(
                messageId = "debug-$deliveryKey",
                threadId = "debug-$deliveryKey",
                rfcMessageId = rfcMessageId,
                reconciled = false,
            )
        }

        val token = oauth.freshAccessToken()
        if (verifyPriorDelivery) {
            findSentByRfcMessageId(token, rfcMessageId)?.let { return it }
        }

        val fromAddress = accountEmail(token)
        val built =
            withContext(Dispatchers.IO) {
                MimeMessageBuilder.build(payload, attachmentPaths, deliveryKey, fromAddress)
            }
        val json = JSONObject().put("raw", built.rawBase64Url).toString()
        val response =
            executeAuthorized(
                token,
                Request
                    .Builder()
                    .url("https://gmail.googleapis.com/gmail/v1/users/me/messages/send")
                    .header("Authorization", "Bearer $token")
                    .post(json.toRequestBody(JSON))
                    .build(),
            )
        val sent = JSONObject(response)
        return MailReceipt(
            messageId = sent.getString("id"),
            threadId = sent.getString("threadId"),
            rfcMessageId = rfcMessageId,
            reconciled = false,
        )
    }

    /**
     * Deliberately does **not** filter on `is:unread` -- it used to, and that was a real,
     * silent-failure bug for anyone forwarding to the same Gmail account connected for delivery
     * (see docs/DESIGN_NOTES.md "Reply routing"): replying from within an already-open
     * conversation routinely delivers that self-addressed reply back into the same thread
     * already marked read, by ordinary Gmail client behavior, not a bug in this app. A reply that
     * never shows up as unread was therefore never found by this search at all -- no event log
     * entry, nothing queued, nothing sent, and no error anywhere to point at. Volume is instead
     * bounded by `newer_than:90d` plus the caller's `knownMessageIds` (every candidate this app
     * has ever looked at, read or not, for the same 90 days -- see
     * SidekickRepository.recentProcessedGmailIds/PROCESSED_REPLY_RETENTION_MS) filtering out
     * repeats before they're ever fetched, and Gmail's own list ordering surfaces genuinely new
     * matches on the first page regardless of how much older history also matches.
     */
    override suspend fun pollReplies(knownMessageIds: Set<String>): MailPollResult {
        if (!oauth.isAuthorized || debug.fakeEmailTransport) return MailPollResult(emptyList(), emptyList())
        val token = oauth.freshAccessToken()
        val now = System.currentTimeMillis()
        // The search below can page through thousands of already-seen matches, so it only runs
        // when Gmail's history log says the inbox actually changed. A full multi-page sweep still
        // runs on first poll, when the cursor has expired, and periodically as a safety net for
        // any candidate whose fetch failed.
        val cursor = historyCursor
        val check = if (cursor != null && now - lastFullSweepMs < FULL_SWEEP_INTERVAL_MS) inboxChangesSince(token, cursor) else null
        if (check != null && !check.changed) {
            historyCursor = check.latestHistoryId
            return MailPollResult(emptyList(), emptyList())
        }
        val fullSweep = check == null
        // Captured before searching so anything arriving mid-search is seen by the next poll.
        val nextCursor = check?.latestHistoryId ?: currentHistoryId(token)
        val result = searchReplies(token, knownMessageIds, if (fullSweep) MAX_POLL_PAGES else 1)
        historyCursor = nextCursor
        if (fullSweep) lastFullSweepMs = now
        // Hitting the per-poll fetch cap means unseen candidates may remain beyond this poll.
        if (result.capped) lastFullSweepMs = 0L
        return result.poll
    }

    private data class InboxChanges(
        val changed: Boolean,
        val latestHistoryId: String,
    )

    private data class SearchOutcome(
        val poll: MailPollResult,
        val capped: Boolean,
    )

    /** Null when [startHistoryId] is too old for Gmail to answer (HTTP 404), so a full sweep is needed. */
    private suspend fun inboxChangesSince(
        token: String,
        startHistoryId: String,
    ): InboxChanges? {
        val json =
            try {
                executeAuthorized(
                    token,
                    Request
                        .Builder()
                        .url(
                            "https://gmail.googleapis.com/gmail/v1/users/me/history" +
                                "?startHistoryId=${URLEncoder.encode(startHistoryId, Charsets.UTF_8.name())}" +
                                "&labelId=INBOX&historyTypes=messageAdded&historyTypes=labelAdded&maxResults=100",
                        ).header("Authorization", "Bearer $token")
                        .get()
                        .build(),
                )
            } catch (error: GmailApiException) {
                if (error.statusCode == 404) return null
                throw error
            }
        val root = JSONObject(json)
        val changed = (root.optJSONArray("history")?.length() ?: 0) > 0 || root.optString("nextPageToken").isNotBlank()
        return InboxChanges(changed, root.optString("historyId").ifBlank { startHistoryId })
    }

    private suspend fun currentHistoryId(token: String): String? =
        JSONObject(
            executeAuthorized(
                token,
                Request
                    .Builder()
                    .url("https://gmail.googleapis.com/gmail/v1/users/me/profile")
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build(),
            ),
        ).optString("historyId").takeIf { it.isNotBlank() }

    private suspend fun searchReplies(
        token: String,
        knownMessageIds: Set<String>,
        maxPages: Int,
    ): SearchOutcome {
        val replies = mutableListOf<MailMessage>()
        val failures = mutableListOf<String>()
        val query = URLEncoder.encode("in:inbox newer_than:90d {subject:SCIF subject:TEXT}", Charsets.UTF_8.name())
        var pageToken: String? = null
        var fetchedCandidates = 0
        repeat(maxPages) {
            val pageSuffix = pageToken?.let { "&pageToken=${URLEncoder.encode(it, Charsets.UTF_8.name())}" }.orEmpty()
            val listJson =
                executeAuthorized(
                    token,
                    Request
                        .Builder()
                        .url("https://gmail.googleapis.com/gmail/v1/users/me/messages?q=$query&maxResults=$POLL_PAGE_SIZE$pageSuffix")
                        .header("Authorization", "Bearer $token")
                        .get()
                        .build(),
                )
            val page = JSONObject(listJson)
            val messages = page.optJSONArray("messages") ?: JSONArray()
            for (index in 0 until messages.length()) {
                val id = messages.getJSONObject(index).getString("id")
                if (id in knownMessageIds) continue
                if (fetchedCandidates >= MAX_NEW_CANDIDATES_PER_POLL) return SearchOutcome(MailPollResult(replies, failures), capped = true)
                fetchedCandidates++
                try {
                    replies += fetchMetadata(token, id)
                } catch (required: ReauthorizationRequiredException) {
                    throw required
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    failures += "$id: ${(failure.message ?: failure.javaClass.simpleName).take(300)}"
                }
            }
            pageToken = page.optString("nextPageToken").takeIf { it.isNotBlank() }
            if (pageToken == null) return SearchOutcome(MailPollResult(replies, failures), capped = false)
        }
        return SearchOutcome(MailPollResult(replies, failures), capped = false)
    }

    /**
     * Looks for unread command emails matching [search] (see [com.scifsidekick.cleanroom.util.RemoteCommandSearch]) -- see
     * [com.scifsidekick.cleanroom.service.RemoteEnableWorker]'s
     * own doc comment for why this needs a query entirely separate from [pollReplies] (that one
     * only ever runs while [com.scifsidekick.cleanroom.service.ForwardingService] itself is
     * alive, which is never true at the one moment this command matters).
     *
     * Unlike [pollReplies], this deliberately keeps `is:unread` in the query: the self-forwarded
     * -into-an-open-thread problem that method's own doc comment describes cannot happen here --
     * a command email is always a fresh, top-level message with no prior thread of this app's own
     * to land back inside of. Up to [RemoteCommandPlanner.MAX_CANDIDATES] matches are fetched, newest first, so
     * a pile of unauthorized or malformed mail can't hide a real command; one message that can't
     * be parsed is reported in [CommandScan.unreadable] instead of failing the rest.
     */
    override suspend fun findCommands(search: CommandSearch): CommandScan {
        if (!oauth.isAuthorized || debug.fakeEmailTransport) return CommandScan(emptyList(), emptyList())
        val query = GmailCommandQuery.build(search)
        val token = oauth.freshAccessToken()
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val listJson =
            executeAuthorized(
                token,
                Request
                    .Builder()
                    .url(
                        "https://gmail.googleapis.com/gmail/v1/users/me/messages?q=$encoded" +
                            "&maxResults=${RemoteCommandPlanner.MAX_CANDIDATES}",
                    )
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build(),
            )
        val messages = JSONObject(listJson).optJSONArray("messages") ?: JSONArray()
        val replies = mutableListOf<MailMessage>()
        val unreadable = mutableListOf<String>()
        for (index in 0 until messages.length()) {
            val id = messages.getJSONObject(index).getString("id")
            try {
                replies += fetchMetadata(token, id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (required: ReauthorizationRequiredException) {
                throw required
            } catch (failure: Exception) {
                unreadable += id
            }
        }
        return CommandScan(replies, unreadable)
    }

    /**
     * Looks for a delivery-status notification quoting one of this installation's own RFC
     * Message-IDs (`scif-<hash>@scif-sidekick.invalid`) -- the only way "the recipient's mail
     * server rejected or couldn't deliver a forward" can ever surface, since a successful call to
     * [send] only ever proves Gmail *accepted* the message for delivery, never that it arrived.
     *
     * Deliberately narrow and best-effort, not a general inbox scan: the search targets only
     * messages that look like automated delivery failures (sender or subject), and a notice is
     * only reported when this installation's own Message-ID domain is actually found somewhere in
     * its content. Real-world bounce formats vary enormously (RFC 3464 delivery-status parts,
     * plain-text mailer-daemon replies, provider-specific templates) and this has not been
     * exercised against a live bounce from a real mail server -- disclosed as unverified, the same
     * as this app's other best-effort integrations (RCS notification parsing, MMS-out).
     */
    override suspend fun checkForBounces(): List<BounceNotice> {
        if (!oauth.isAuthorized || debug.fakeEmailTransport) return emptyList()
        val token = oauth.freshAccessToken()
        val query =
            URLEncoder.encode(
                "in:inbox is:unread (from:mailer-daemon OR from:postmaster OR " +
                    "subject:\"Delivery Status Notification\" OR subject:\"Undelivered Mail\" OR " +
                    "subject:\"Delivery Failure\" OR subject:\"Returned mail\" OR subject:\"Delivery has failed\")",
                Charsets.UTF_8.name(),
            )
        val listJson =
            executeAuthorized(
                token,
                Request
                    .Builder()
                    .url("https://gmail.googleapis.com/gmail/v1/users/me/messages?q=$query&maxResults=$BOUNCE_PAGE_SIZE")
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build(),
            )
        val candidates = JSONObject(listJson).optJSONArray("messages") ?: JSONArray()
        val notices = mutableListOf<BounceNotice>()
        for (index in 0 until candidates.length()) {
            val id = candidates.getJSONObject(index).getString("id")
            val notice =
                suspendRunCatching {
                    val json =
                        executeAuthorized(
                            token,
                            Request
                                .Builder()
                                .url("https://gmail.googleapis.com/gmail/v1/users/me/messages/$id?format=full")
                                .header("Authorization", "Bearer $token")
                                .get()
                                .build(),
                        )
                    val root = JSONObject(json)
                    val text = collectDecodedText(root.getJSONObject("payload"))
                    val ownIds = OWN_MESSAGE_ID.findAll(text).map { it.value }.toSet()
                    if (ownIds.isEmpty()) {
                        null
                    } else {
                        BounceNotice(id, ownIds, text.trim().take(500))
                    }
                }.getOrNull()
            if (notice != null) notices += notice
        }
        return notices
    }

    /** Concatenates every text-ish part's decoded content, plus every nested part's own header
     *  values -- unlike [findBody] (first match only, used for an authorized reply's actual text),
     *  a delivery-status notification can carry the original Message-ID in several different
     *  places depending on the sending mail server (a `message/delivery-status` block's own body,
     *  a nested `message/rfc822` part's headers, or just plain text in the human-readable part),
     *  so every part is worth searching rather than stopping at the first. Same bounded-walk
     *  ceiling as [walkMimeParts] guards against a pathologically deep/wide structure. */
    private fun collectDecodedText(root: JSONObject): String {
        val queue = ArrayDeque<JSONObject>()
        queue.add(root)
        var visited = 0
        val collected = StringBuilder()
        while (queue.isNotEmpty()) {
            if (++visited > MAX_MIME_PARTS_VISITED) break
            val current = queue.removeFirst()
            val mimeType = current.optString("mimeType")
            if (mimeType.startsWith("text/") || mimeType.startsWith("message/")) {
                val data = current.optJSONObject("body")?.optString("data").orEmpty()
                if (data.isNotBlank() && data.length <= MAX_BODY_BASE64_CHARACTERS) {
                    runCatching { collected.append(data.base64UrlDecode()).append('\n') }
                }
            }
            current.optJSONArray("headers")?.let { headers ->
                for (h in 0 until headers.length()) {
                    collected.append(headers.getJSONObject(h).optString("value")).append('\n')
                }
            }
            val parts = current.optJSONArray("parts") ?: continue
            for (i in 0 until parts.length()) queue.add(parts.getJSONObject(i))
        }
        return collected.toString()
    }

    override suspend fun markRead(messageId: String) {
        val token = oauth.freshAccessToken()
        val json = JSONObject().put("removeLabelIds", JSONArray().put("UNREAD")).toString()
        executeAuthorized(
            token,
            Request
                .Builder()
                .url("https://gmail.googleapis.com/gmail/v1/users/me/messages/$messageId/modify")
                .header("Authorization", "Bearer $token")
                .post(json.toRequestBody(JSON))
                .build(),
        )
    }

    private suspend fun findSentByRfcMessageId(
        token: String,
        rfcMessageId: String,
    ): MailReceipt? {
        val query = URLEncoder.encode("in:sent rfc822msgid:<$rfcMessageId>", Charsets.UTF_8.name())
        val json =
            executeAuthorized(
                token,
                Request
                    .Builder()
                    .url("https://gmail.googleapis.com/gmail/v1/users/me/messages?q=$query&maxResults=1")
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build(),
            )
        val messages = JSONObject(json).optJSONArray("messages") ?: return null
        if (messages.length() == 0) return null
        val match = messages.getJSONObject(0)
        return MailReceipt(
            messageId = match.getString("id"),
            threadId = match.getString("threadId"),
            rfcMessageId = rfcMessageId,
            reconciled = true,
        )
    }

    private suspend fun accountEmail(token: String): String {
        profileEmailAddress?.let { return it }
        val json =
            executeAuthorized(
                token,
                Request
                    .Builder()
                    .url("https://gmail.googleapis.com/gmail/v1/users/me/profile")
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build(),
            )
        return JSONObject(json).getString("emailAddress").also { profileEmailAddress = it }
    }

    /** Fetches only the routing/authentication headers for an untrusted inbox candidate. */
    private suspend fun fetchMetadata(
        token: String,
        id: String,
    ): MailMessage {
        val json =
            executeAuthorized(
                token,
                Request
                    .Builder()
                    .url(
                        "https://gmail.googleapis.com/gmail/v1/users/me/messages/$id?format=metadata" +
                            "&metadataHeaders=Subject&metadataHeaders=In-Reply-To" +
                            "&metadataHeaders=References&metadataHeaders=Message-ID" +
                            "&metadataHeaders=From&metadataHeaders=Authentication-Results",
                    )
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build(),
            )
        val root = JSONObject(json)
        val payload = root.getJSONObject("payload")
        val headers = payload.optJSONArray("headers") ?: JSONArray()
        val subject = header(headers, "Subject")
        val referenceHeaders = listOf(header(headers, "In-Reply-To"), header(headers, "References"))
        val references =
            referenceHeaders
                .flatMap { value -> MESSAGE_ID.findAll(value).map { it.groupValues[1] }.toList() }
                .take(MAX_REFERENCES)
                .toSet()
        val fromHeader = headers(headers, "From").singleOrNull().orEmpty()
        val authenticationResults = headers(headers, "Authentication-Results")
        return MailMessage(
            id = id,
            threadId = root.getString("threadId"),
            subject = subject,
            body = "",
            referencedMessageIds = references,
            rfcMessageId = MESSAGE_ID.find(header(headers, "Message-ID"))?.groupValues?.get(1).orEmpty(),
            fromHeader = fromHeader,
            authenticatedFromAddress = GmailAuthentication.authenticatedFrom(fromHeader, authenticationResults),
        )
    }

    /** Called only for an already-authorized command. Full MIME content and attachment bytes are
     *  intentionally outside the broad inbox poll's attack surface. */
    override suspend fun fetchContent(reply: MailMessage): MailMessage {
        val token = oauth.freshAccessToken()
        val json =
            executeAuthorized(
                token,
                Request
                    .Builder()
                    .url("https://gmail.googleapis.com/gmail/v1/users/me/messages/${reply.id}?format=full")
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build(),
            )
        val payload = JSONObject(json).getJSONObject("payload")
        val plain = findBody(payload, "text/plain")
        val html = if (plain == null) findBody(payload, "text/html") else null
        val body = plain ?: html?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString() }.orEmpty()
        val image = findImagePart(payload)?.let { fetchImageAttachment(token, reply.id, it) }
        return reply.copy(body = body, imageMimeType = image?.first, imageBytes = image?.second)
    }

    /** Finds the first MIME part whose type starts with "image" in a (possibly multipart)
     *  message payload -- a picture-message trigger is expected to carry exactly one photo, so
     *  "first found" rather than "collect all" matches what MmsGateway can actually send.
     *
     *  This walks the part tree iteratively with a visited-node ceiling rather than recursing,
     *  and that's deliberate, not just style: every message this app's *own* subject search
     *  matches gets its full structure fetched and walked here before the authorized-sender
     *  check ever runs (pollReplies casts a deliberately wide net -- see its own comment), so
     *  an attacker who is never going to pass authorization can still hand this a crafted,
     *  pathologically deep or wide multipart structure. An unbounded recursive walk over that is
     *  a StackOverflowError waiting to happen -- and Error, unlike Exception, is *not* caught by
     *  any of the `catch (failure: Exception)` handlers around this call, so it would take down
     *  the whole polling loop, not just this one message. */
    private fun findImagePart(root: JSONObject): JSONObject? = walkMimeParts(root) { it.optString("mimeType").startsWith("image/", ignoreCase = true) }

    private fun walkMimeParts(
        root: JSONObject,
        matches: (JSONObject) -> Boolean,
    ): JSONObject? {
        val queue = ArrayDeque<JSONObject>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty()) {
            if (++visited > MAX_MIME_PARTS_VISITED) return null
            val current = queue.removeFirst()
            if (matches(current)) return current
            val parts = current.optJSONArray("parts") ?: continue
            for (i in 0 until parts.length()) queue.add(parts.getJSONObject(i))
        }
        return null
    }

    /**
     * Fetches the bytes for an image part already located by [findImagePart]. A small inline
     * attachment carries its data directly on the part (`body.data`); anything larger comes back
     * from `messages.get` with only a `body.attachmentId`, requiring the separate
     * `messages.attachments.get` call below. Either way, the declared size is checked against
     * the same cap [AttachmentStore] enforces for every other stored attachment *before* any
     * bytes are pulled across the network, and the attachment endpoint's own reported size is
     * checked again afterward -- a message part's declared size is metadata Gmail supplies, not
     * a hard guarantee, so this deliberately doesn't trust it alone.
     */
    private suspend fun fetchImageAttachment(
        token: String,
        messageId: String,
        part: JSONObject,
    ): Pair<String, ByteArray>? {
        val body = part.optJSONObject("body") ?: return null
        val mimeType = part.optString("mimeType").ifBlank { "image/jpeg" }
        if (body.optLong("size", 0L) > AttachmentStore.MAX_STORED_ATTACHMENT_BYTES) return null
        val inlineData = body.optString("data")
        if (inlineData.isNotBlank()) {
            val decoded = inlineData.base64UrlDecodeBytes()
            return (mimeType to decoded).takeIf { decoded.size.toLong() <= AttachmentStore.MAX_STORED_ATTACHMENT_BYTES }
        }

        val attachmentId = body.optString("attachmentId").takeIf { it.isNotBlank() } ?: return null
        val attachmentJson =
            executeAuthorized(
                token,
                Request
                    .Builder()
                    .url("https://gmail.googleapis.com/gmail/v1/users/me/messages/$messageId/attachments/$attachmentId")
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build(),
            )
        val attachment = JSONObject(attachmentJson)
        if (attachment.optLong("size", 0L) > AttachmentStore.MAX_STORED_ATTACHMENT_BYTES) return null
        val data = attachment.optString("data").takeIf { it.isNotBlank() } ?: return null
        return mimeType to data.base64UrlDecodeBytes()
    }

    private fun header(
        headers: JSONArray,
        name: String,
    ): String =
        (0 until headers.length())
            .asSequence()
            .map { headers.getJSONObject(it) }
            .firstOrNull { it.optString("name").equals(name, ignoreCase = true) }
            ?.optString("value")
            .orEmpty()

    private fun headers(
        headers: JSONArray,
        name: String,
    ): List<String> =
        (0 until headers.length())
            .asSequence()
            .map { headers.getJSONObject(it) }
            .filter { it.optString("name").equals(name, ignoreCase = true) }
            .map { it.optString("value") }
            .toList()

    // Same bounded-walk reasoning as findImagePart applies here -- this was the original
    // unbounded-recursion version of that same risk, now sharing its fix.
    private fun findBody(
        root: JSONObject,
        targetMimeType: String,
    ): String? {
        val match =
            walkMimeParts(root) { part ->
                part.optString("mimeType").equals(targetMimeType, true) &&
                    part.optJSONObject("body")?.optString("data").orEmpty().isNotBlank()
            }
        val encoded = match?.optJSONObject("body")?.optString("data") ?: return null
        // This code runs only after authorization, but it is still a hard limit on an accidental
        // or maliciously huge quoted body. The reply safety policy is far smaller than this cap.
        if (encoded.length > MAX_BODY_BASE64_CHARACTERS) return null
        return encoded.base64UrlDecode()
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
        val MESSAGE_ID = Regex("<([^<>\\s]+)>")

        // Matches MimeMessageBuilder.rfcMessageId's exact output format -- this is deliberately
        // narrow (this app's own reserved, invalid-per-RFC-2606-style domain) rather than a
        // generic Message-ID pattern, so it can only ever match a bounce that genuinely quotes a
        // message this installation sent, never an unrelated one.
        val OWN_MESSAGE_ID = Regex("scif-[0-9a-f]{40}@scif-sidekick\\.invalid")
        const val BOUNCE_PAGE_SIZE = 20

        // Real messages -- even elaborately multipart ones -- top out at a handful of parts.
        // This is a generous ceiling for any legitimate message, and a hard stop against a
        // crafted one; see walkMimeParts's doc comment for what it protects against.
        const val MAX_MIME_PARTS_VISITED = 500
        const val MAX_REFERENCES = 50
        const val MAX_BODY_BASE64_CHARACTERS = 64_000
        const val POLL_PAGE_SIZE = 100
        // Known, non-command subjects remain unread by design. Page past them so they cannot
        // permanently hide a genuine reply, while fetching at most this many new bodies per poll.
        const val MAX_POLL_PAGES = 20
        const val MAX_NEW_CANDIDATES_PER_POLL = 100
        const val FULL_SWEEP_INTERVAL_MS = 6L * 60L * 60_000L
    }
}

class GmailApiException(
    val statusCode: Int,
    detail: String,
) : Exception("Gmail API HTTP $statusCode: $detail")
