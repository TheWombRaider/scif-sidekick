package com.scifsidekick.cleanroom

import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64

/**
 * An in-memory Microsoft Graph mailbox behind an OkHttp [Interceptor], for tests. It also answers
 * the Microsoft identity token endpoint, so one client can back both MsOAuthManager and GraphGateway.
 *
 * It is deliberately strict: a path, query parameter, `$filter` clause, `$select` property or
 * content type it does not implement is answered with HTTP 400, so code under test cannot quietly
 * depend on a query the real service might reject. It also models two real behaviors the gateway
 * relies on: an `$orderby` property must be the first `$filter` property (Graph rejects the
 * request as "InefficientFilter" otherwise), and a sent draft keeps its id only when the request
 * asked for immutable ids (`Prefer: IdType="ImmutableId"`).
 */
class FakeGraphServer : Interceptor {
    /**
     * One request the server saw. [body] is the UTF-8 request body, or null. [contentType] is the
     * body's media type exactly as it would go on the wire (OkHttp adds the header itself later).
     */
    data class Seen(
        val method: String,
        val url: HttpUrl,
        val headers: Headers,
        val body: String?,
        val contentType: String? = null,
    ) {
        val path: String get() = url.encodedPath
        val isGraph: Boolean get() = url.host == GRAPH_HOST
    }

    class Attachment(
        val id: String,
        val name: String,
        val contentType: String,
        val bytes: ByteArray,
        /** What the server reports as `size`; defaults to the real size. */
        val declaredSize: Long = bytes.size.toLong(),
        /** The `@odata.type` annotation; null leaves it out of every response. */
        val odataType: String? = FILE_ATTACHMENT,
        /** Sent as `contentBytes` instead of the base64 of [bytes], to model odd encodings. */
        val contentBytesOverride: String? = null,
    )

    class Message(
        var id: String,
        var folder: String,
        val internetMessageId: String,
        val conversationId: String,
        val subject: String,
        val fromName: String?,
        val fromAddress: String,
        val headers: List<Pair<String, String>>,
        val textBody: String?,
        val htmlBody: String?,
        var isRead: Boolean,
        val receivedMs: Long,
        val attachments: MutableList<Attachment> = mutableListOf(),
        /** The decoded MIME of a draft created through `POST /me/messages`. */
        val mime: ByteArray? = null,
    )

    private sealed interface Action {
        class Reply(
            val code: Int,
            val body: String,
            val headers: Map<String, String>,
        ) : Action

        class Throw(
            val error: IOException,
        ) : Action

        /** Handle the request twice and answer with the second result, as OkHttp's silent retry would. */
        data object HandleTwice : Action

        class Delay(
            val ms: Long,
        ) : Action
    }

    private class Rule(
        val matches: (Seen) -> Boolean,
        val action: Action,
    )

    private val lock = Any()
    private val messages = mutableListOf<Message>()
    private val rules = mutableListOf<Rule>()
    private var nextId = 1
    private var issued = 0

    /** Every request, Graph and token endpoint alike, in order. */
    val requests: MutableList<Seen> = java.util.Collections.synchronizedList(mutableListOf())

    /** Graph requests only. */
    val graphRequests: List<Seen> get() = synchronized(requests) { requests.filter { it.isGraph } }

    /** The mailbox clock; deliveries are stamped with it. Tests share it with the gateway. */
    @Volatile var nowMs: Long = Instant.parse("2026-10-09T12:00:00Z").toEpochMilli()

    @Volatile var mail: String? = "me@outlook.com"

    @Volatile var userPrincipalName: String = "me@outlook.com"

    /** When true, a created draft gets a server-made Message-ID instead of the one in its MIME. */
    @Volatile var rewriteMessageId: Boolean = false

    /** When true, the token endpoint answers every refresh with `invalid_grant`. */
    @Volatile var invalidGrant: Boolean = false

    /** The only access token Graph currently accepts; null until one is issued. */
    @Volatile var validAccessToken: String? = null
        private set

    val tokenRequests: Int get() = synchronized(requests) { requests.count { !it.isGraph } }

    fun client(): OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

    // ---- control ----

    /** Puts a message in the inbox and returns its native id. [from] is `addr` or `Name <addr>`. */
    fun deliver(
        from: String,
        subject: String,
        body: String,
        headers: List<Pair<String, String>> = emptyList(),
        isRead: Boolean = false,
        id: String? = null,
        receivedMs: Long = nowMs,
        htmlOnly: Boolean = false,
        includeFromHeader: Boolean = true,
        internetMessageId: String? = null,
        conversationId: String? = null,
    ): String =
        synchronized(lock) {
            val nativeId = id ?: "AAMkAD-${nextId++}"
            val match = Regex("^(.*?)\\s*<([^<>]+)>$").find(from.trim())
            val name = match?.groupValues?.get(1)?.trim()?.trim('"')?.ifBlank { null }
            val address = match?.groupValues?.get(2) ?: from.trim()
            val allHeaders = if (includeFromHeader && headers.none { it.first.equals("From", true) }) listOf("From" to from) + headers else headers
            messages +=
                Message(
                    id = nativeId,
                    folder = INBOX,
                    internetMessageId = internetMessageId ?: "<$nativeId@fake.outlook.com>",
                    conversationId = conversationId ?: "conv-$nativeId",
                    subject = subject,
                    fromName = name,
                    fromAddress = address,
                    headers = allHeaders,
                    textBody = if (htmlOnly) null else body,
                    htmlBody = if (htmlOnly) body else null,
                    isRead = isRead,
                    receivedMs = receivedMs,
                )
            nativeId
        }

    fun message(id: String): Message? = synchronized(lock) { messages.firstOrNull { it.id == id } }

    fun messagesIn(folder: String): List<Message> = synchronized(lock) { messages.filter { it.folder == folder } }

    fun addAttachment(
        messageId: String,
        attachment: Attachment,
    ) {
        synchronized(lock) { message(messageId)!!.attachments += attachment }
    }

    /** The next request matching [matching] (any Graph request by default) gets this reply instead. */
    fun failNext(
        code: Int,
        body: String = """{"error":{"code":"Scripted","message":"scripted failure"}}""",
        headers: Map<String, String> = emptyMap(),
        matching: (Seen) -> Boolean = { it.isGraph },
    ) {
        synchronized(lock) { rules += Rule(matching, Action.Reply(code, body, headers)) }
    }

    /** The next request matching [matching] fails with [error] instead of reaching the mailbox. */
    fun failNextWith(
        error: IOException,
        matching: (Seen) -> Boolean = { it.isGraph },
    ) {
        synchronized(lock) { rules += Rule(matching, Action.Throw(error)) }
    }

    /**
     * The next request matching [matching] reaches the mailbox twice and the client sees only the
     * second answer: a call that succeeded but whose response was lost and silently retried.
     */
    fun handleTwiceNext(matching: (Seen) -> Boolean) {
        synchronized(lock) { rules += Rule(matching, Action.HandleTwice) }
    }

    /** The next request matching [matching] is held for [ms] before it is handled normally. */
    fun delayNext(
        ms: Long,
        matching: (Seen) -> Boolean = { it.isGraph },
    ) {
        synchronized(lock) { rules += Rule(matching, Action.Delay(ms)) }
    }

    /** The current access token stops working: the next Graph request is a 401 until a refresh. */
    fun require401Once() {
        validAccessToken = "revoked-${System.nanoTime()}"
    }

    // ---- dispatch ----

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body =
            request.body?.let { b ->
                Buffer().also { b.writeTo(it) }.readByteArray()
            }
        val seen = Seen(request.method, request.url, request.headers, body?.toString(Charsets.UTF_8), request.body?.contentType()?.toString())
        requests += seen
        val rule = synchronized(lock) { rules.firstOrNull { it.matches(seen) }?.also { rules.remove(it) } }
        when (val action = rule?.action) {
            is Action.Reply -> return respond(request, action.code, action.body, action.headers)
            is Action.Throw -> throw action.error
            is Action.Delay -> Thread.sleep(action.ms)
            Action.HandleTwice -> {
                graph(request, body).close()
                return graph(request, body)
            }
            null -> Unit
        }
        return when (request.url.host) {
            LOGIN_HOST -> token(request)
            GRAPH_HOST -> graph(request, body)
            else -> respond(request, 400, error("UnknownHost", "FakeGraphServer does not serve ${request.url.host}"))
        }
    }

    private fun token(request: Request): Response {
        val form = request.body as? FormBody
        val fields = form?.let { f -> (0 until f.size).associate { f.name(it) to f.value(it) } }.orEmpty()
        if (request.url.encodedPath != "/common/oauth2/v2.0/token" || fields["grant_type"] != "refresh_token") {
            return respond(request, 400, """{"error":"unsupported_grant_type"}""")
        }
        if (invalidGrant) return respond(request, 400, """{"error":"invalid_grant","error_description":"AADSTS70008"}""")
        val access = "fake-access-${++issued}"
        validAccessToken = access
        return respond(request, 200, """{"token_type":"Bearer","access_token":"$access","expires_in":3600}""")
    }

    private fun graph(
        request: Request,
        body: ByteArray?,
    ): Response {
        if (request.header("Authorization") != "Bearer $validAccessToken" || validAccessToken == null) {
            return respond(request, 401, error("InvalidAuthenticationToken", "Access token is empty or invalid."))
        }
        val segments = request.url.pathSegments
        if (segments.size < 2 || segments[0] != "v1.0" || segments[1] != "me") return badRequest(request, "path ${request.url.encodedPath}")
        val rest = segments.drop(2)
        val prefer = preferences(request)
        return synchronized(lock) {
            try {
                when {
                    rest.isEmpty() && request.method == "GET" -> me(request)
                    rest == listOf("messages") && request.method == "POST" -> createDraft(request, body, prefer)
                    rest.size == 3 && rest[0] == "mailFolders" && rest[2] == "messages" && request.method == "GET" -> list(request, rest[1])
                    rest.size == 2 && rest[0] == "messages" -> single(request, rest[1], body, prefer)
                    rest.size == 3 && rest[0] == "messages" && rest[2] == "send" && request.method == "POST" -> send(request, rest[1], prefer)
                    rest.size == 3 && rest[0] == "messages" && rest[2] == "attachments" && request.method == "GET" -> attachments(request, rest[1])
                    rest.size == 4 && rest[0] == "messages" && rest[2] == "attachments" && request.method == "GET" -> attachment(request, rest[1], rest[3])
                    else -> badRequest(request, "${request.method} ${request.url.encodedPath}")
                }
            } catch (e: Unsupported) {
                badRequest(request, e.message.orEmpty())
            }
        }
    }

    private class Unsupported(
        message: String,
    ) : Exception(message)

    private fun me(request: Request): Response {
        val select = select(request, setOf("mail", "userPrincipalName", "id", "displayName"), allowed = setOf("\$select"))
        val json = JSONObject().put("id", "user-1").put("displayName", "Me").put("mail", mail ?: JSONObject.NULL).put("userPrincipalName", userPrincipalName)
        return ok(request, project(json, select))
    }

    private fun createDraft(
        request: Request,
        body: ByteArray?,
        prefer: Set<String>,
    ): Response {
        requireParams(request, emptySet())
        val type = request.body?.contentType()
        if (type == null || type.type != "text" || type.subtype != "plain") {
            throw Unsupported("POST /me/messages here takes only MIME as text/plain, got $type")
        }
        val text = body?.toString(Charsets.US_ASCII)?.trim().orEmpty()
        val mime =
            try {
                require(text.isNotEmpty() && text.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' })
                Base64.getDecoder().decode(text)
            } catch (_: IllegalArgumentException) {
                return respond(request, 400, error("ErrorMimeContentInvalidBase64String", "Invalid base64 string for MIME content."))
            }
        val headers = mimeHeaders(mime.toString(Charsets.UTF_8))
        val header = { name: String -> headers.firstOrNull { it.first.equals(name, true) }?.second }
        val id = if (IMMUTABLE in prefer) "AAkALg-${nextId++}" else "AAMkAD-${nextId++}"
        val messageId = if (rewriteMessageId || header("Message-ID") == null) "<REWRITTEN-${nextId++}@fake.prod.outlook.com>" else header("Message-ID")!!.trim()
        val from = header("From").orEmpty()
        val message =
            Message(
                id = id,
                folder = DRAFTS,
                internetMessageId = messageId,
                conversationId = "conv-$id",
                subject = header("Subject").orEmpty(),
                fromName = null,
                fromAddress = from,
                headers = headers,
                textBody = "",
                htmlBody = null,
                isRead = true,
                receivedMs = nowMs,
                mime = mime,
            )
        messages += message
        return respond(request, 201, json(message, null, prefer).put("isDraft", true).toString())
    }

    private fun send(
        request: Request,
        id: String,
        prefer: Set<String>,
    ): Response {
        requireParams(request, emptySet())
        val draft = messages.firstOrNull { it.id == id && it.folder == DRAFTS } ?: return notFound(request)
        draft.folder = SENT
        // A plain (mutable) id changes when the item moves; an immutable one does not.
        if (IMMUTABLE !in prefer || !draft.id.startsWith("AAkALg-")) draft.id = "AAMkAD-sent-${nextId++}"
        return respond(request, 202, "")
    }

    private fun single(
        request: Request,
        id: String,
        body: ByteArray?,
        prefer: Set<String>,
    ): Response {
        val message = messages.firstOrNull { it.id == id } ?: return notFound(request)
        return when (request.method) {
            "GET" -> {
                val select = select(request, MESSAGE_PROPERTIES, allowed = setOf("\$select"))
                ok(request, json(message, select, prefer))
            }
            "PATCH" -> {
                requireParams(request, emptySet())
                val patch = JSONObject(body?.toString(Charsets.UTF_8).orEmpty())
                if (patch.keys().asSequence().toSet() != setOf("isRead")) throw Unsupported("PATCH here only sets isRead")
                message.isRead = patch.getBoolean("isRead")
                ok(request, json(message, null, prefer))
            }
            "DELETE" -> {
                requireParams(request, emptySet())
                messages.remove(message)
                respond(request, 204, "")
            }
            else -> badRequest(request, "${request.method} on a message")
        }
    }

    private fun list(
        request: Request,
        folder: String,
    ): Response {
        if (folder != INBOX && folder != SENT) throw Unsupported("folder $folder")
        requireParams(request, setOf("\$filter", "\$select", "\$orderby", "\$top", "\$skiptoken"))
        val select = select(request, MESSAGE_PROPERTIES - "body" - "internetMessageHeaders", allowed = null)
        val clauses = parseFilter(request.url.queryParameter("\$filter"))
        val orderBy = request.url.queryParameter("\$orderby")
        if (orderBy != null) {
            if (orderBy != "receivedDateTime desc") throw Unsupported("\$orderby $orderBy")
            if (clauses.isNotEmpty() && clauses.first() !is Clause.ReceivedSince) {
                return respond(request, 400, error("InefficientFilter", "The restriction or sort order is too complex for this operation."))
            }
        }
        val top = request.url.queryParameter("\$top")?.toIntOrNull()?.takeIf { it in 1..1000 } ?: if (request.url.queryParameter("\$top") == null) 10 else throw Unsupported("\$top")
        val skip = request.url.queryParameter("\$skiptoken")?.toIntOrNull() ?: 0
        val matching =
            messages
                .filter { it.folder == folder && clauses.all { c -> c.matches(it) } }
                .sortedByDescending { it.receivedMs }
        val page = matching.drop(skip).take(top)
        val root = JSONObject().put("value", JSONArray(page.map { json(it, select, emptySet()) }))
        if (skip + top < matching.size) {
            root.put(
                "@odata.nextLink",
                request.url
                    .newBuilder()
                    .setQueryParameter("\$skiptoken", (skip + top).toString())
                    .build()
                    .toString(),
            )
        }
        return ok(request, root)
    }

    private fun attachments(
        request: Request,
        id: String,
    ): Response {
        val message = messages.firstOrNull { it.id == id } ?: return notFound(request)
        val select = select(request, setOf("id", "name", "contentType", "size", "contentBytes", "isInline"), allowed = setOf("\$select"))
        val values = message.attachments.map { annotated(project(attachmentJson(it), select), it) }
        return ok(request, JSONObject().put("value", JSONArray(values)))
    }

    private fun attachment(
        request: Request,
        id: String,
        attachmentId: String,
    ): Response {
        val message = messages.firstOrNull { it.id == id } ?: return notFound(request)
        val found = message.attachments.firstOrNull { it.id == attachmentId } ?: return notFound(request)
        val select = select(request, setOf("id", "name", "contentType", "size", "contentBytes", "isInline"), allowed = setOf("\$select"))
        return ok(request, annotated(project(attachmentJson(found), select), found))
    }

    private fun attachmentJson(a: Attachment): JSONObject {
        val json =
            JSONObject()
                .put("id", a.id)
                .put("name", a.name)
                .put("contentType", a.contentType)
                .put("size", a.declaredSize)
                .put("isInline", false)
        if (a.odataType == null || a.odataType == FILE_ATTACHMENT) {
            json.put("contentBytes", a.contentBytesOverride ?: Base64.getEncoder().encodeToString(a.bytes))
        }
        return json
    }

    private fun annotated(
        json: JSONObject,
        a: Attachment,
    ): JSONObject = if (a.odataType == null) json else json.put("@odata.type", a.odataType)

    // ---- query parsing ----

    private sealed interface Clause {
        fun matches(m: Message): Boolean

        class ReceivedSince(
            val ms: Long,
        ) : Clause {
            override fun matches(m: Message) = m.receivedMs >= ms
        }

        class IsRead(
            val value: Boolean,
        ) : Clause {
            override fun matches(m: Message) = m.isRead == value
        }

        class InternetMessageId(
            val value: String,
        ) : Clause {
            override fun matches(m: Message) = m.internetMessageId == value
        }
    }

    private fun parseFilter(filter: String?): List<Clause> {
        if (filter == null) return emptyList()
        return filter.split(" and ").map { part ->
            RECEIVED_GE.matchEntire(part)?.let { return@map Clause.ReceivedSince(Instant.parse(it.groupValues[1]).toEpochMilli()) }
            IS_READ_EQ.matchEntire(part)?.let { return@map Clause.IsRead(it.groupValues[1] == "true") }
            MESSAGE_ID_EQ.matchEntire(part)?.let { return@map Clause.InternetMessageId(it.groupValues[1].replace("''", "'")) }
            throw Unsupported("\$filter clause '$part'")
        }
    }

    private fun requireParams(
        request: Request,
        allowed: Set<String>,
    ) {
        val unknown = request.url.queryParameterNames - allowed
        if (unknown.isNotEmpty()) throw Unsupported("query parameters $unknown")
    }

    /** The `$select` set (null when absent), after checking it and the other parameters. */
    private fun select(
        request: Request,
        known: Set<String>,
        allowed: Set<String>?,
    ): Set<String>? {
        allowed?.let { requireParams(request, it) }
        val raw = request.url.queryParameter("\$select") ?: return null
        val fields = raw.split(',').map { it.trim() }.toSet()
        val unknown = fields - known
        if (unknown.isNotEmpty() || fields.any { it.isEmpty() }) throw Unsupported("\$select $unknown")
        return fields
    }

    private fun preferences(request: Request): Set<String> =
        request
            .headers("Prefer")
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    // ---- JSON ----

    private fun json(
        m: Message,
        select: Set<String>?,
        prefer: Set<String>,
    ): JSONObject {
        val all =
            JSONObject()
                .put("id", m.id)
                .put("conversationId", m.conversationId)
                .put("subject", m.subject)
                .put("internetMessageId", m.internetMessageId)
                .put("receivedDateTime", iso(m.receivedMs))
                .put("isRead", m.isRead)
                .put("isDraft", m.folder == DRAFTS)
                .put(
                    "from",
                    JSONObject().put(
                        "emailAddress",
                        JSONObject().put("name", m.fromName ?: m.fromAddress).put("address", m.fromAddress),
                    ),
                )
        val wantsText = TEXT_BODY in prefer
        val body =
            when {
                wantsText -> JSONObject().put("contentType", "text").put("content", m.textBody ?: htmlToText(m.htmlBody.orEmpty()))
                m.htmlBody != null -> JSONObject().put("contentType", "html").put("content", m.htmlBody)
                else -> JSONObject().put("contentType", "html").put("content", "<html><body>${m.textBody.orEmpty()}</body></html>")
            }
        all.put("body", body)
        // Like Graph, headers come back only when asked for.
        if (select != null && "internetMessageHeaders" in select) {
            all.put("internetMessageHeaders", JSONArray(m.headers.map { JSONObject().put("name", it.first).put("value", it.second) }))
        }
        return project(all, select)
    }

    private fun project(
        json: JSONObject,
        select: Set<String>?,
    ): JSONObject {
        if (select == null) return json
        val out = JSONObject()
        (select + "id").forEach { key -> if (json.has(key)) out.put(key, json.get(key)) }
        return out
    }

    private fun htmlToText(html: String): String = html.replace(Regex("<[^>]*>"), "").trim()

    private fun mimeHeaders(mime: String): List<Pair<String, String>> {
        val head = mime.substringBefore("\r\n\r\n")
        val unfolded = head.replace(Regex("\r\n[ \t]+"), " ")
        return unfolded.split("\r\n").mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim() to line.substring(colon + 1).trim()
        }
    }

    // ---- responses ----

    private fun ok(
        request: Request,
        json: JSONObject,
    ) = respond(request, 200, json.toString())

    private fun notFound(request: Request) = respond(request, 404, error("ErrorItemNotFound", "The specified object was not found in the store."))

    private fun badRequest(
        request: Request,
        what: String,
    ) = respond(request, 400, error("BadRequest", "FakeGraphServer does not implement $what"))

    private fun error(
        code: String,
        message: String,
    ) = JSONObject().put("error", JSONObject().put("code", code).put("message", message)).toString()

    private fun respond(
        request: Request,
        code: Int,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): Response =
        Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("fake")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    companion object {
        const val GRAPH_HOST = "graph.microsoft.com"
        const val LOGIN_HOST = "login.microsoftonline.com"
        const val INBOX = "inbox"
        const val SENT = "sentitems"
        const val DRAFTS = "drafts"
        const val IMMUTABLE = "IdType=\"ImmutableId\""
        const val TEXT_BODY = "outlook.body-content-type=\"text\""
        const val FILE_ATTACHMENT = "#microsoft.graph.fileAttachment"

        val MESSAGE_PROPERTIES =
            setOf("id", "conversationId", "subject", "from", "internetMessageId", "receivedDateTime", "isRead", "isDraft", "body", "internetMessageHeaders")

        private val RECEIVED_GE = Regex("receivedDateTime ge (\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z)")
        private val IS_READ_EQ = Regex("isRead eq (true|false)")
        private val MESSAGE_ID_EQ = Regex("internetMessageId eq '((?:[^']|'')*)'")

        /** UTC ISO-8601 with `Z` and no fractional seconds, as Graph's DateTimeOffset literals in tests. */
        fun iso(ms: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(ms).truncatedTo(ChronoUnit.SECONDS))
    }
}
