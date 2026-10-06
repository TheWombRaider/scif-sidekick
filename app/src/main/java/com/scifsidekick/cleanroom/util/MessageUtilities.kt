package com.scifsidekick.cleanroom.util

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.webkit.MimeTypeMap
import com.scifsidekick.cleanroom.messaging.EmailPayload
import com.scifsidekick.cleanroom.messaging.MmsReplyPayload
import com.scifsidekick.cleanroom.messaging.SmsReplyPayload
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

object Hashing {
    fun sha256(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/**
 * A single normalization used on both sides of the cross-source (SMS vs. RCS-notification)
 * duplicate check, so a whitespace/newline difference between the raw telephony broadcast body
 * and the cleaned-up notification text Google Messages displays can't defeat the match. Never
 * used for the body that actually gets forwarded -- only for the comparison key.
 */
fun normalizeForDedupe(text: String): String = text.replace(Regex("\\s+"), " ").trim().lowercase(Locale.US)

object WatermarkPolicy {
    fun isEligible(
        enabled: Boolean,
        watermarkMs: Long,
        receivedAtMs: Long,
    ): Boolean = enabled && receivedAtMs > watermarkMs
}

object PhoneNumbers {
    private val subjectTag = Regex("\\[SCIF:(\\+[1-9]\\d{6,14})]", RegexOption.IGNORE_CASE)
    private val composeTag = Regex("^\\s*TEXT(\\+[1-9]\\d{6,14})\\s*$", RegexOption.IGNORE_CASE)

    fun normalizeToE164(
        context: Context,
        raw: String,
    ): String? {
        val normalized = PhoneNumberUtils.normalizeNumber(raw)
        if (normalized.matches(Regex("\\+[1-9]\\d{6,14}"))) return normalized
        val country =
            (
                context
                    .getSystemService(TelephonyManager::class.java)
                    ?.simCountryIso
                    ?.ifBlank { null }
                    ?: context
                        .getSystemService(TelephonyManager::class.java)
                    ?.networkCountryIso
                    ?.ifBlank { null } ?: Locale.getDefault().country
            ).uppercase(Locale.US)
        return PhoneNumberUtils
            .formatNumberToE164(normalized, country)
            ?.takeIf { it.matches(Regex("\\+[1-9]\\d{6,14}")) }
    }

    fun extractFromSubject(subject: String): String? =
        subjectTag
            .findAll(subject)
            .map { it.groupValues[1] }
            .distinct()
            .singleOrNull()

    fun extractComposeTarget(subject: String): String? = composeTag.matchEntire(subject)?.groupValues?.get(1)
}

/** The commands a subject line can carry: the two master-switch ones, and a read-only query. */
enum class RemoteCommand { ENABLE, DISABLE, STATUS }

/**
 * The subject tags for "turn forwarding back on" and "turn it off." Each is deliberately its own
 * literal token (`ON`/`OFF`, never a phone number) so neither can collide with [PhoneNumbers]'
 * `[SCIF:+number]` reply tag -- all three are matched by different regexes against the same
 * bracketed-tag convention, not by one trying to rule out the others.
 *
 * The two are checked in different places, because the component that can see the command differs
 * by exactly the state the command is about:
 *
 * - `ENABLE` is checked by [RemoteEnableWorker], since forwarding being off means
 *   [ForwardingService] is not running to check anything -- see that worker's doc comment.
 * - `DISABLE` is checked by [ForwardingService]'s own reply loop, since forwarding being on means
 *   that service *is* running, polling this same inbox every 30 seconds, and already consumes any
 *   `[SCIF:` subject it doesn't recognize (marking it read and recording it as an ignored
 *   candidate). A worker checking for it on a 15-minute cycle would therefore almost never see one
 *   still unread. Handling it inline in that poll is both the only reliable place and the faster
 *   one.
 * - `STATUS` is meaningful in *either* state, so it is the one command both sides check: the
 *   service's poll answers it while forwarding is on (~30s), the worker while it is off (~15min).
 *   Whichever is alive is the one that can see it, and they are never both alive at once.
 *
 * A subject carrying more than one recognized tag is not a command at all: it is ambiguous about
 * which was meant, so [parse] returns null and no side acts on it, the same treatment
 * [PhoneNumbers.extractFromSubject] already gives a reply subject naming two routing targets.
 */
object RemoteCommands {
    const val ENABLE_TAG = "[SCIF:ON]"
    const val DISABLE_TAG = "[SCIF:OFF]"
    const val STATUS_TAG = "[SCIF:STATUS]"

    private val tags =
        mapOf(
            RemoteCommand.ENABLE to Regex("\\[SCIF:ON]", RegexOption.IGNORE_CASE),
            RemoteCommand.DISABLE to Regex("\\[SCIF:OFF]", RegexOption.IGNORE_CASE),
            RemoteCommand.STATUS to Regex("\\[SCIF:STATUS]", RegexOption.IGNORE_CASE),
        )

    /** Exactly one recognized tag means that command; none or more than one means no command. */
    fun parse(subject: String): RemoteCommand? =
        tags.entries.filter { it.value.containsMatchIn(subject) }.singleOrNull()?.key

    fun isEnableForwardingCommand(subject: String): Boolean = parse(subject) == RemoteCommand.ENABLE

    fun isDisableForwardingCommand(subject: String): Boolean = parse(subject) == RemoteCommand.DISABLE

    fun isStatusCommand(subject: String): Boolean = parse(subject) == RemoteCommand.STATUS
}

object ReplyBodyCleaner {
    private val separators =
        listOf(
            Regex("^On .+wrote:$", RegexOption.IGNORE_CASE),
            Regex("^-{2,}\\s*Original Message\\s*-{2,}$", RegexOption.IGNORE_CASE),
            // Requires an "@address" on the line, not just the word "From:" -- a real reply
            // that happens to start a line with "From: Mom" (no address) is left alone, while a
            // quoted mail client header ("From: Jane Doe <jane@example.com>") still matches.
            Regex("^From:\\s+.*@.*$", RegexOption.IGNORE_CASE),
            // Outlook's own reply divider: a bare line of underscores, with the quoted
            // From/Sent/To/Subject header block immediately after it. Nothing else on this line,
            // so it never risks matching a genuine reply that happens to use an underscore for
            // emphasis or a signature rule -- only a line that is *entirely* underscores.
            Regex("^_{2,}$"),
        )

    fun clean(raw: String): String {
        val lines = raw.replace("\r\n", "\n").lines()
        val kept = mutableListOf<String>()
        for (line in lines) {
            if (line.trimStart().startsWith(">")) continue
            if (separators.any { it.matches(line.trim()) }) break
            kept += line
        }
        return kept
            .joinToString("\n")
            .replace(Regex("\\[SCIF:\\+[1-9]\\d{6,14}]", RegexOption.IGNORE_CASE), "")
            .trim()
    }
}

/**
 * A brand-new outbound text has no prior sent thread to authorize a reply against, so
 * "compose new via email" instead trusts only mail whose `From` address exactly matches one of
 * a user-configured allow list -- deliberately not the connected Gmail account's own address:
 * that account is routinely unreachable from the same device or organization that needs to send
 * the trigger email (a government network's own email client, say), which made "email yourself"
 * an unworkable requirement in practice. This is a deliberately different (and disclosed) trust
 * boundary from [SidekickRepository.isAuthorizedReply], never a substitute for it. `isAuthorizedSender`
 * itself is a plain address-in-a-list check with no opinion on where that list comes from; the
 * actual gate for this feature is [RemoteControlCodec]'s unified allowlist, checked against its
 * `canCompose` bit -- an address still has to be deliberately added there before this authorizes
 * anything, regardless of the master switch's own default.
 */
object ComposeAuthorization {
    fun isAuthorizedSender(
        fromAddress: String?,
        authorizedSenders: Collection<String>,
    ): Boolean =
        canonicalAddress(fromAddress)?.let { sender ->
            authorizedSenders.mapNotNull(::canonicalAddress).any { it == sender }
        } == true

    /** Extracts a bare email address from a `From` header, which may be either a bare address
     *  or an RFC 5322 `"Display Name" <address@example.com>` form. */
    fun extractAddress(fromHeader: String): String? {
        if ('\r' in fromHeader || '\n' in fromHeader) return null
        val bracketed = Regex("<([^<>\\s,]+@[^<>\\s,]+)>").findAll(fromHeader).map { it.groupValues[1] }.toList()
        if (bracketed.isNotEmpty()) return bracketed.singleOrNull()?.let(::canonicalAddress)
        return canonicalAddress(fromHeader)
    }

    fun canonicalAddress(address: String?): String? {
        val value = address?.trim()?.lowercase(Locale.US) ?: return null
        if (!value.matches(Regex("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?"))) return null
        if (value.length > 254 || value.substringBefore('@').length > 64 || ".." in value) return null
        return value
    }
}

/**
 * Converts Gmail's trusted, topmost Authentication-Results header into an authenticated mailbox.
 * A displayable From header alone is never authority. Gmail prepends its own result at receipt;
 * considering only the first such header prevents an attacker-supplied lower header from winning.
 */
object GmailAuthentication {
    private val dmarcPass = Regex("(?:^|;)\\s*dmarc=pass\\b[^;]*\\bheader\\.from=([^;\\s]+)", RegexOption.IGNORE_CASE)

    fun authenticatedFrom(
        fromHeader: String,
        authenticationResults: List<String>,
    ): String? {
        val address = ComposeAuthorization.extractAddress(fromHeader) ?: return null
        val trusted = authenticationResults.firstOrNull()?.trim() ?: return null
        val authService = trusted.substringBefore(';').trim().lowercase(Locale.US)
        if (authService != "mx.google.com") return null
        val assertedDomain =
            dmarcPass.find(trusted)?.groupValues?.get(1)?.trim()?.trimEnd('.')?.lowercase(Locale.US)
                ?: return null
        val fromDomain = address.substringAfter('@').trimEnd('.')
        return address.takeIf { fromDomain == assertedDomain }
    }
}

object ReplySafetyPolicy {
    const val MAX_SMS_REPLY_CHARACTERS = 1_600

    fun rejectionReason(
        body: String,
        allowBlank: Boolean = false,
    ): String? =
        when {
            body.isBlank() && !allowBlank -> "Email reply contained no new text"
            body.length > MAX_SMS_REPLY_CHARACTERS ->
                "Email reply was ${body.length} characters; the safety limit is $MAX_SMS_REPLY_CHARACTERS"
            else -> null
        }
}

object PayloadCodec {
    fun emailToJson(payload: EmailPayload): String =
        JSONObject()
            .apply {
                put("destinations", JSONArray(payload.destinations))
                put("replyTarget", payload.replyTarget)
                put("senderDisplay", payload.senderDisplay)
                put("body", payload.body)
                put("receivedAtMs", payload.receivedAtMs)
                put("source", payload.source)
                put("participants", JSONArray(payload.participants))
                put("attachmentNotice", payload.attachmentNotice)
                put("renderedSubject", payload.renderedSubject)
                put("renderedBody", payload.renderedBody)
                put("filterName", payload.filterName)
            }.toString()

    fun emailFromJson(json: String): EmailPayload =
        JSONObject(json).run {
            EmailPayload(
                destinations = optJSONArray("destinations")?.toStringList() ?: optString("destination").let { listOf(it) },
                replyTarget = optString("replyTarget").takeIf { it.isNotBlank() && it != "null" },
                senderDisplay = getString("senderDisplay"),
                body = getString("body"),
                receivedAtMs = getLong("receivedAtMs"),
                source = getString("source"),
                participants = getJSONArray("participants").toStringList(),
                attachmentNotice = optString("attachmentNotice").takeIf { it.isNotBlank() && it != "null" },
                renderedSubject = optString("renderedSubject"),
                renderedBody = optString("renderedBody"),
                filterName = optString("filterName"),
            )
        }

    fun smsToJson(payload: SmsReplyPayload): String =
        JSONObject()
            .apply {
                put("targetNumber", payload.targetNumber)
                put("body", payload.body)
                put("gmailMessageId", payload.gmailMessageId)
                put("initiatorAddress", payload.initiatorAddress)
                put("initiatorThreadId", payload.initiatorThreadId)
            }.toString()

    fun smsFromJson(json: String): SmsReplyPayload =
        JSONObject(json).run {
            SmsReplyPayload(
                targetNumber = getString("targetNumber"),
                body = getString("body"),
                gmailMessageId = getString("gmailMessageId"),
                // Absent on any row queued before confirmations existed; such a row is simply
                // never confirmed rather than treated as malformed.
                initiatorAddress = optionalString("initiatorAddress"),
                initiatorThreadId = optionalString("initiatorThreadId"),
            )
        }

    fun mmsToJson(payload: MmsReplyPayload): String =
        JSONObject()
            .apply {
                put("targetNumber", payload.targetNumber)
                put("body", payload.body)
                put("gmailMessageId", payload.gmailMessageId)
                put("initiatorAddress", payload.initiatorAddress)
                put("initiatorThreadId", payload.initiatorThreadId)
            }.toString()

    fun mmsFromJson(json: String): MmsReplyPayload =
        JSONObject(json).run {
            MmsReplyPayload(
                targetNumber = getString("targetNumber"),
                body = getString("body"),
                gmailMessageId = getString("gmailMessageId"),
                initiatorAddress = optionalString("initiatorAddress"),
                initiatorThreadId = optionalString("initiatorThreadId"),
            )
        }

    /** `JSONObject.put(String, null)` stores a real JSON null, which `optString` then hands back as
     *  the literal text "null" rather than absent -- the same trap [emailFromJson] already guards
     *  against for its own nullable fields. */
    private fun JSONObject.optionalString(name: String): String? =
        optString(name).takeIf { it.isNotBlank() && it != "null" }

    fun pathsToJson(paths: List<String>): String = JSONArray(paths).toString()

    // Deliberately fails safe to an empty list rather than throwing: this is used to decode
    // recipient/contact/keyword lists directly inside FilterConditionEvaluator and
    // processIncoming's per-filter loop, with no surrounding try/catch there. A single row with
    // malformed JSON -- a botched migration, manual DB edit, or a backup restored from a future
    // app version -- must degrade to "no entries" for that one field, never crash forwarding for
    // every filter and every message by throwing out of the whole transaction.
    fun pathsFromJson(json: String): List<String> = runCatching { JSONArray(json).toStringList() }.getOrDefault(emptyList())

    private fun JSONArray.toStringList(): List<String> = (0 until length()).map { getString(it) }
}

class ContactResolver(
    private val context: Context,
) {
    fun displayName(address: String): String {
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return address
        }
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
        return runCatching {
            context.contentResolver
                .query(
                    uri,
                    arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull() ?: address
    }

    /**
     * Returns a phone number for [displayName] when it resolves unambiguously -- which, for RCS
     * specifically, used to mean "only when the matching contact has a single phone number on
     * file at all." Android gives third-party apps no reliable phone number for an RCS message
     * the way SMS_RECEIVED carries originatingAddress; this display-name-against-Contacts lookup
     * is the only signal available at all, so a contact with the ordinary mobile-plus-home/work
     * shape (extremely common) used to make every RCS message from them forward as SCIF-NOREPLY,
     * silently -- the message still arrives by email, but a reply to it has no [SCIF:+number] tag
     * to route against. RCS/SMS is inherently a mobile-number technology, so when more than one
     * distinct number is on file, a single one of them being typed Mobile is not a genuine
     * ambiguity for this purpose (unlike, say, two different numbers both typed Mobile, or none
     * typed Mobile at all -- those still safely fall through to null exactly as before).
     */
    fun uniquePhoneNumberForDisplayName(displayName: String): String? {
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        // number -> was any row for that number typed Mobile.
        val candidates =
            runCatching {
                context.contentResolver
                    .query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE),
                        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} = ? COLLATE NOCASE",
                        arrayOf(displayName),
                        null,
                    )?.use { cursor ->
                        val numberColumn = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        val typeColumn = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
                        val seen = LinkedHashMap<String, Boolean>()
                        while (cursor.moveToNext() && seen.size < MAX_MATCHING_NUMBERS) {
                            val normalized = PhoneNumbers.normalizeToE164(context, cursor.getString(numberColumn)) ?: continue
                            val isMobile = cursor.getInt(typeColumn) == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                            seen[normalized] = (seen[normalized] == true) || isMobile
                        }
                        seen
                    }.orEmpty()
            }.getOrDefault(emptyMap())
        candidates.keys.singleOrNull()?.let { return it }
        return candidates.filterValues { it }.keys.singleOrNull()
    }

    private companion object {
        const val MAX_MATCHING_NUMBERS = 3
    }
}

class AttachmentStore(
    private val context: Context,
) {
    private val root: File get() = File(context.filesDir, "pending_attachments").apply { mkdirs() }

    @Synchronized
    fun copyFromUri(
        uri: Uri,
        suggestedName: String,
    ): String? {
        var target: File? = null
        return try {
            val remainingGlobalBytes = (MAX_TOTAL_STORED_ATTACHMENT_BYTES - storedBytes()).coerceAtLeast(0L)
            val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            val extension = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            val nameWithExtension =
                if (suggestedName.substringAfterLast('.', "").isBlank() && !extension.isNullOrBlank()) {
                    "$suggestedName.$extension"
                } else {
                    suggestedName
                }
            val safeName = nameWithExtension.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)
            val targetFile = File(root, "${System.currentTimeMillis()}_${Hashing.sha256(uri.toString()).take(10)}_$safeName")
            target = targetFile
            // openInputStream legitimately returns null when a provider doesn't support the URI
            // -- an intentional early exit into the same catch-and-return-null path below reads
            // better than forcing that case through a `!!`-thrown NullPointerException.
            val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("Provider returned no stream for $uri")
            stream.use { input ->
                targetFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (copied > MAX_STORED_ATTACHMENT_BYTES || copied > remainingGlobalBytes) {
                            throw IOException("Live attachment exceeds the per-message or total attachment-storage limit")
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
            targetFile.absolutePath
        } catch (_: Exception) {
            target?.let { runCatching { it.delete() } }
            null
        }
    }

    /** Writes already-fetched bytes (a Gmail attachment downloaded via the API, not a
     *  content-resolver stream) to the same app-private directory and under the same size cap
     *  and filename-sanitization rules as [copyFromUri], so the queue's attachment-path column,
     *  the orphan-pruning sweep, and the delivery-time delete-when-unreferenced logic all treat
     *  an MMS image exactly like any other stored attachment -- there is nothing MMS-specific
     *  about how a path on disk gets cleaned up. */
    @Synchronized
    fun writeBytes(
        bytes: ByteArray,
        suggestedName: String,
    ): String? {
        if (bytes.size.toLong() > MAX_STORED_ATTACHMENT_BYTES || storedBytes() + bytes.size > MAX_TOTAL_STORED_ATTACHMENT_BYTES) return null
        var target: File? = null
        return try {
            val safeName = suggestedName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)
            val targetFile = File(root, "${System.currentTimeMillis()}_${Hashing.sha256(suggestedName).take(10)}_$safeName")
            target = targetFile
            targetFile.outputStream().use { it.write(bytes) }
            targetFile.absolutePath
        } catch (_: Exception) {
            target?.let { runCatching { it.delete() } }
            null
        }
    }

    fun pruneUnreferenced(
        referencedPaths: Set<String>,
        olderThanMs: Long,
    ): Int {
        val canonicalReferences = referencedPaths.mapNotNull { runCatching { File(it).canonicalPath }.getOrNull() }.toSet()
        return root
            .listFiles()
            .orEmpty()
            .count { file ->
                file.lastModified() < olderThanMs &&
                    file.canonicalPath !in canonicalReferences &&
                    runCatching { file.delete() }.getOrDefault(false)
            }
    }

    fun delete(paths: List<String>) {
        paths.forEach { path ->
            val file = File(path)
            if (file.parentFile?.canonicalFile == root.canonicalFile) runCatching { file.delete() }
        }
    }

    private fun storedBytes(): Long = root.listFiles().orEmpty().sumOf { file -> file.length() }

    companion object {
        const val MAX_STORED_ATTACHMENT_BYTES = 18L * 1024L * 1024L
        const val MAX_TOTAL_STORED_ATTACHMENT_BYTES = 100L * 1024L * 1024L
        const val MAX_ATTACHMENTS_PER_MESSAGE = 10
    }
}

fun String.base64UrlDecode(): String =
    String(
        base64UrlDecodeBytes(),
        Charsets.UTF_8,
    )

/** Gmail's API returns attachment and message-part bodies as unpadded base64url. An image
 *  attachment is binary, so unlike [base64UrlDecode] this must never round-trip the bytes
 *  through UTF-8 -- doing so silently corrupts any byte sequence that isn't valid UTF-8, which
 *  is every real photo. */
fun String.base64UrlDecodeBytes(): ByteArray = Base64.getUrlDecoder().decode(this + "=".repeat((4 - length % 4) % 4))
