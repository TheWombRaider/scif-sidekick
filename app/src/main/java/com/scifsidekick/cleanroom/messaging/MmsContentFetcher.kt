package com.scifsidekick.cleanroom.messaging

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import com.scifsidekick.cleanroom.util.AttachmentStore
import kotlinx.coroutines.delay

/**
 * Reads exactly the one MMS row the live `WAP_PUSH_RECEIVED` broadcast just announced -- never a
 * range, never a cursor kept across calls, never anything that existed before this broadcast
 * fired, and never invoked except once per live broadcast.
 *
 * Why this exists: stock Android's WAP-push notification for a non-default SMS app carries only
 * carrier metadata (`M-Notification.ind`), not the decoded photo/video -- the default SMS app is
 * the one that actually downloads those bytes and writes them to `content://mms`, normally within
 * a couple of seconds of the broadcast. Product approved this narrowly-scoped, event-triggered
 * read specifically to close that gap (see docs/DESIGN_NOTES.md "Important Android MMS boundary" and
 * docs/QA_AUDIT.md release-gate #5) after confirming the alternative -- becoming the phone's
 * default SMS app -- was out of scope. This is deliberately still not a general `content://mms`
 * reader: no `ContentObserver`, no persisted "last processed id", no query triggered by anything
 * other than the one broadcast that announced this exact message, and no catch-up query when
 * forwarding or MMS is toggled on. `scripts/verify_no_history_queries.sh` allow-lists only this
 * one file for exactly that reason -- a new call site outside it should not exist.
 *
 * A bounded wait handles the ordering race: the WAP-push broadcast typically arrives before the
 * default SMS app finishes its own download-and-insert. If that download hasn't landed by
 * [MAX_ATTEMPTS] * [RETRY_DELAY_MS], this gives up and returns null -- the caller falls back to
 * the existing text-only notice exactly as before. Nothing here is verified against a live
 * carrier/MMSC on a physical device; see docs/DESIGN_NOTES.md ("Picture messages (MMS-out)") for the same
 * caveat applied to the receive side.
 */
class MmsContentFetcher(
    private val context: Context,
) {
    data class Fetched(
        val senderAddress: String?,
        val participants: List<String>,
        val textBody: String,
        val attachmentPaths: List<String>,
        val skippedAnyForSize: Boolean,
    )

    /**
     * [notBeforeMs] is the live broadcast's own receipt time (wall-clock millis). Returns null
     * when no matching row shows up within the bounded wait -- not an error, just "the default
     * SMS app hasn't finished yet" or "this OEM doesn't expose one at all."
     */
    suspend fun fetchJustAnnouncedMessage(
        notBeforeMs: Long,
        attachments: AttachmentStore,
    ): Fetched? {
        val mmsId = awaitNewRowId(notBeforeMs) ?: return null
        return readMessage(mmsId, attachments)
    }

    private suspend fun awaitNewRowId(notBeforeMs: Long): Long? {
        // The MMS provider's "date" column is whole seconds since epoch, not millis. A small
        // grace window absorbs clock/rounding skew between broadcast receipt and row insert.
        val notBeforeSec = (notBeforeMs / 1000L) - GRACE_SECONDS
        repeat(MAX_ATTEMPTS) { attempt ->
            val id =
                runCatching {
                    context.contentResolver
                        .query(
                            Uri.parse("content://mms/inbox"),
                            arrayOf("_id"),
                            "date >= ?",
                            arrayOf(notBeforeSec.toString()),
                            "date DESC, _id DESC",
                        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
                }.getOrNull()
            if (id != null) return id
            if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_DELAY_MS)
        }
        return null
    }

    private fun readMessage(
        mmsId: Long,
        attachments: AttachmentStore,
    ): Fetched {
        val resolver = context.contentResolver
        var textBody = ""
        val paths = mutableListOf<String>()
        var skippedAnyForSize = false

        resolver
            .query(
                Uri.parse("content://mms/part"),
                arrayOf("_id", "ct", "text"),
                "mid=?",
                arrayOf(mmsId.toString()),
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow("_id")
                val ctCol = cursor.getColumnIndexOrThrow("ct")
                val textCol = cursor.getColumnIndexOrThrow("text")
                while (cursor.moveToNext()) {
                    val partId = cursor.getLong(idCol)
                    val contentType = cursor.getString(ctCol) ?: continue
                    when {
                        contentType == "text/plain" -> textBody += cursor.getString(textCol).orEmpty()
                        contentType.startsWith("image/") || contentType.startsWith("video/") -> {
                            if (paths.size >= AttachmentStore.MAX_ATTACHMENTS_PER_MESSAGE) {
                                skippedAnyForSize = true
                            } else {
                                val partUri = ContentUris.withAppendedId(Uri.parse("content://mms/part"), partId)
                                // Reuses the exact same per-file/per-message/aggregate size caps and
                                // storage location every other live attachment path already uses --
                                // there is nothing special-cased about a provider-sourced attachment.
                                val path = attachments.copyFromUri(partUri, "mms_part_$partId")
                                if (path == null) skippedAnyForSize = true else paths.add(path)
                            }
                        }
                    }
                }
            }

        val sender = queryAddress(mmsId, TYPE_FROM)
        val participants = queryAllAddresses(mmsId).ifEmpty { listOfNotNull(sender) }
        return Fetched(sender, participants, textBody, paths, skippedAnyForSize)
    }

    private fun queryAddress(
        mmsId: Long,
        type: Int,
    ): String? =
        runCatching {
            context.contentResolver
                .query(
                    Uri.parse("content://mms/$mmsId/addr"),
                    arrayOf("address"),
                    "type=?",
                    arrayOf(type.toString()),
                    null,
                )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()

    private fun queryAllAddresses(mmsId: Long): List<String> =
        runCatching {
            context.contentResolver
                .query(
                    Uri.parse("content://mms/$mmsId/addr"),
                    arrayOf("address"),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    val addrCol = cursor.getColumnIndexOrThrow("address")
                    buildList { while (cursor.moveToNext()) cursor.getString(addrCol)?.let(::add) }
                }
        }.getOrDefault(null).orEmpty()

    private companion object {
        // Total bounded wait: (MAX_ATTEMPTS - 1) * RETRY_DELAY_MS ~= 28.5s. Deliberately much
        // longer than it looks safe to hold a BroadcastReceiver's own goAsync() open for -- it no
        // longer needs to be that short. IncomingMessageReceiver finishes the live broadcast's
        // pending result *before* calling into this wait (see its own comment), specifically so
        // this can be sized to real carrier MMS-download timing -- commonly several seconds, and
        // well past 10s on a weak connection -- instead of to an ANR watchdog that no longer
        // applies here. The already-running foreground service keeps the process alive for all of
        // it regardless.
        const val MAX_ATTEMPTS = 20
        const val RETRY_DELAY_MS = 1_500L
        const val GRACE_SECONDS = 5L

        // content://mms/<id>/addr "type" values, per the (unofficial but long-stable) Telephony
        // provider convention PduHeaders.FROM uses.
        const val TYPE_FROM = 137
    }
}
