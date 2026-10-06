package com.scifsidekick.cleanroom.email

import com.scifsidekick.cleanroom.messaging.EmailPayload
import com.scifsidekick.cleanroom.util.Hashing
import java.io.File
import java.net.URLConnection
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class BuiltMime(
    val rawBase64Url: String,
    val omittedAttachmentBytes: Long,
    val missingAttachmentCount: Int,
)

object MimeMessageBuilder {
    // Base64 expands bytes by 4/3; 18 MiB plus headers stays below a conservative
    // 25 MiB raw-message ceiling.
    const val MAX_SOURCE_ATTACHMENT_BYTES = 18L * 1024L * 1024L

    // Keeps a folded "=?UTF-8?B?...?=" chunk short enough that even the first physical line --
    // which also carries the ASCII "[SCIF:+number]" prefix on the same line -- stays comfortably
    // under common 76-78 char header line-length conventions (30 bytes -> 40 base64 chars -> a
    // ~52-char encoded-word, leaving headroom for the longest realistic prefix).
    private const val MAX_ENCODED_WORD_SOURCE_BYTES = 30

    fun build(
        payload: EmailPayload,
        attachmentPaths: List<String>,
        deliveryKey: String,
        fromAddress: String,
    ): BuiltMime {
        val files = attachmentPaths.map(::File).filter { it.isFile }
        val missingAttachmentCount = attachmentPaths.size - files.size
        val totalBytes = files.sumOf { it.length() }
        val attach = totalBytes <= MAX_SOURCE_ATTACHMENT_BYTES
        val omittedBytes = if (attach) 0 else totalBytes
        val boundary = "sidekick_${UUID.randomUUID()}"
        val text = buildBody(payload, omittedBytes, missingAttachmentCount)

        val message =
            buildString {
                append("From: ").append(sanitizeHeader(fromAddress)).append("\r\n")
                append("To: ").append(sanitizeHeader(payload.destinations.joinToString(", "))).append("\r\n")
                append("Subject: ")
                    .append(encodedSubject(payload.renderedSubject))
                    .append("\r\n")
                append("Date: ").append(rfc2822(payload.receivedAtMs)).append("\r\n")
                append("Message-ID: <").append(rfcMessageId(deliveryKey)).append(">\r\n")
                append("MIME-Version: 1.0\r\n")
                if (attach && files.isNotEmpty()) {
                    append("Content-Type: multipart/mixed; boundary=\"").append(boundary).append("\"\r\n\r\n")
                    append("--").append(boundary).append("\r\n")
                    append("Content-Type: text/plain; charset=UTF-8\r\n")
                    append("Content-Transfer-Encoding: base64\r\n\r\n")
                    append(mimeBase64(text.toByteArray())).append("\r\n")
                    files.forEach { file ->
                        val fileName = sanitizeHeader(file.name)
                        val mime = URLConnection.guessContentTypeFromName(fileName) ?: "application/octet-stream"
                        append("--").append(boundary).append("\r\n")
                        append("Content-Type: ")
                            .append(mime)
                            .append("; name=\"")
                            .append(fileName)
                            .append("\"\r\n")
                        append("Content-Disposition: attachment; filename=\"").append(fileName).append("\"\r\n")
                        append("Content-Transfer-Encoding: base64\r\n\r\n")
                        append(mimeBase64(file.readBytes())).append("\r\n")
                    }
                    append("--").append(boundary).append("--\r\n")
                } else {
                    append("Content-Type: text/plain; charset=UTF-8\r\n")
                    append("Content-Transfer-Encoding: base64\r\n\r\n")
                    append(mimeBase64(text.toByteArray())).append("\r\n")
                }
            }
        return BuiltMime(
            rawBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(message.toByteArray(Charsets.UTF_8)),
            omittedAttachmentBytes = omittedBytes,
            missingAttachmentCount = missingAttachmentCount,
        )
    }

    fun rfcMessageId(deliveryKey: String): String = "scif-${Hashing.sha256(deliveryKey).take(40)}@scif-sidekick.invalid"

    /**
     * Starts from the filter's own rendered template (see [MessageVariables] and
     * [ReplaceRuleEngine]) rather than a fixed layout, then appends the safety-relevant
     * disclosures below unconditionally -- a custom template can change how a message is
     * introduced, never whether an attachment omission or the reply instructions are disclosed.
     */
    private fun buildBody(
        payload: EmailPayload,
        omittedBytes: Long,
        missingAttachmentCount: Int,
    ): String =
        buildString {
            append(payload.renderedBody.ifBlank { payload.body.ifBlank { "(No text body)" } })
            if (payload.participants.size > 1) {
                append("\n\nParticipants: ").append(payload.participants.joinToString(", "))
            }
            payload.attachmentNotice?.let { append("\n\nAttachment notice: ").append(it) }
            if (omittedBytes > 0) {
                append("\n\nAttachment not forwarded: ")
                append(omittedBytes).append(" bytes exceeds the safe Gmail message-size budget.")
            }
            if (missingAttachmentCount > 0) {
                append("\n\nAttachment warning: ")
                append(missingAttachmentCount)
                append(" queued attachment file(s) were unavailable at send time and could not be forwarded.")
            }
            // A bare connectivity check ("Test Gmail connection") never had a real sender to
            // reply to in the first place -- disclosing reply-by-email's *un*availability the
            // same way a genuinely un-normalizable forwarded message does would just be
            // confusing noise about a concept this email was never going to support.
            if (payload.replyTarget != null) {
                append("\n\nReply to this email to send an SMS to ").append(payload.replyTarget).append(".")
                append(" Keep the [SCIF:").append(payload.replyTarget).append("] tag in the subject.")
            } else if (payload.source != "test") {
                append("\n\nReply-by-email is unavailable because the sender was not a valid E.164 phone number.")
            }
        }

    private fun sanitizeHeader(value: String): String = value.replace(Regex("[\\r\\n]"), " ")

    private fun encodedSubject(value: String): String {
        val safe = sanitizeHeader(value)
        if (safe.all { it.code in 32..126 }) return safe
        val tagEnd = safe.indexOf(']').takeIf { it >= 0 }
        val asciiPrefix = tagEnd?.let { safe.substring(0, it + 1) }.orEmpty()
        val remainder = if (tagEnd == null) safe else safe.substring(tagEnd + 1).trimStart()
        return listOf(asciiPrefix, foldEncodedWords(remainder)).filter(String::isNotBlank).joinToString(" ")
    }

    /**
     * Splits [text] into one or more RFC 2047 `=?UTF-8?B?...?=` encoded-words, folded onto
     * continuation lines, instead of one unbounded encoded-word -- a long non-ASCII sender name
     * used to produce a single header line that could exceed common (and, in extreme cases, the
     * hard 998-byte) email header line-length limits. Chunking by UTF-8 byte length of each
     * source character (never mid-character) keeps every produced line short and every chunk
     * independently valid base64.
     */
    private fun foldEncodedWords(text: String): String {
        if (text.isEmpty()) return ""
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        var currentBytes = 0
        text.codePoints().toArray().forEach { codePoint ->
            val piece = String(Character.toChars(codePoint))
            val pieceBytes = piece.toByteArray(Charsets.UTF_8).size
            if (currentBytes + pieceBytes > MAX_ENCODED_WORD_SOURCE_BYTES && current.isNotEmpty()) {
                chunks += current.toString()
                current.setLength(0)
                currentBytes = 0
            }
            current.append(piece)
            currentBytes += pieceBytes
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks.joinToString("\r\n ") { chunk ->
            "=?UTF-8?B?${Base64.getEncoder().encodeToString(chunk.toByteArray(Charsets.UTF_8))}?="
        }
    }

    private fun mimeBase64(bytes: ByteArray): String = Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(bytes)

    private fun rfc2822(timestampMs: Long): String =
        SimpleDateFormat(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            Locale.US,
        ).apply { timeZone = TimeZone.getDefault() }.format(Date(timestampMs))
}
