package com.scifsidekick.cleanroom.util

import com.scifsidekick.cleanroom.data.ForwardedMessageEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the CSV for the History screen's "Export message log" action from
 * [SidekickRepository.messagesForExport]'s rows. Deliberately a plain, dependency-light
 * formatter -- no Room/Android context needed -- so it's exercisable by a JVM unit test.
 *
 * [includeBody] defaults to off in the UI: a message body is already blanked at rest by
 * [SidekickRepository.processIncoming]'s redaction step for any message no enabled filter chose
 * to save, so turning it on here never exposes more than what the app already retains -- it just
 * decides whether *this one export file* should carry the retained text at all.
 */
object MessageLogExport {
    private fun timestampFormat() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun toCsv(
        rows: List<ForwardedMessageEntity>,
        includeBody: Boolean,
    ): String {
        val format = timestampFormat()
        return buildString {
            append(header(includeBody)).append("\r\n")
            rows.forEach { row -> append(csvRow(row, includeBody, format)).append("\r\n") }
        }
    }

    private fun header(includeBody: Boolean): String =
        (
            listOf("Received At", "Source", "Sender", "Sender Display", "Forwarded", "Send Attempts", "Last Error") +
                if (includeBody) listOf("Message Body") else emptyList()
        ).joinToString(",", transform = ::escape)

    private fun csvRow(
        row: ForwardedMessageEntity,
        includeBody: Boolean,
        format: SimpleDateFormat,
    ): String =
        (
            listOf(
                format.format(Date(row.receivedAtMs)),
                row.source,
                row.senderAddress,
                row.senderDisplay,
                if (row.forwarded) "Yes" else "No",
                row.sendAttemptCount.toString(),
                row.lastError.orEmpty(),
            ) + if (includeBody) listOf(row.body) else emptyList()
        ).joinToString(",", transform = ::escape)

    /** RFC 4180: a field containing a comma, quote, or newline is wrapped in quotes, with any
     *  embedded quote doubled. */
    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
}
