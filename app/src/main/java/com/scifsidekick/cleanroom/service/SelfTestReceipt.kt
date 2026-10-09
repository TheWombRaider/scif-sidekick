package com.scifsidekick.cleanroom.service

import android.content.Context
import com.scifsidekick.cleanroom.AppGraph
import com.scifsidekick.cleanroom.BuildConfig
import com.scifsidekick.cleanroom.data.EventType

/**
 * Emails the connected Gmail account a test receipt, through the same path a real `[SCIF:ON]` /
 * `[SCIF:OFF]` receipt takes: [com.scifsidekick.cleanroom.data.SidekickRepository.enqueueSystemEmail]
 * into send_queue, then [ReceiptDrainWorker]. The Developer screen's connectivity test bypasses the
 * queue and the worker, so it can pass while receipts still fail; this one cannot.
 *
 * It only ever writes to the signed-in account itself, so it needs no input and cannot reach anyone
 * else. The result describes what was queued, not what was delivered: the worker sends
 * asynchronously and retries, so the proof is the email arriving. A failed send shows up in History.
 */
object SelfTestReceipt {
    const val SUBJECT = "SCIF Sidekick: self-test receipt"

    sealed interface Outcome {
        data class Queued(
            val recipient: String,
        ) : Outcome

        data object NotConnected : Outcome

        data object NotQueued : Outcome
    }

    fun body(
        version: String,
        summary: String,
    ): String =
        "This is a self-test receipt from SCIF Sidekick $version.\n\n" +
            "It went through the same path as the receipts for [SCIF:ON] and [SCIF:OFF]: the send queue, " +
            "then a background job. If you are reading it, command receipts can reach you.\n\n" +
            "Current state:\n$summary"

    suspend fun send(
        context: Context,
        graph: AppGraph,
        recipientOverride: String? = null,
    ): Outcome {
        // The override exists for the instrumented test, which runs on the fake transport with no
        // Gmail account to read an address from. The UI never passes it.
        val recipient = recipientOverride ?: graph.mail.accountEmail() ?: return Outcome.NotConnected
        val now = System.currentTimeMillis()
        val summary = graph.repository.buildStatusSummary(graph.mail.isAvailable, now)
        val queued =
            graph.repository.enqueueSystemEmail(
                recipients = listOf(recipient),
                subject = SUBJECT,
                body = body(BuildConfig.VERSION_NAME, summary),
                reason = "self-test receipt",
                nowMs = now,
            ) ?: return Outcome.NotQueued
        graph.repository.recordEvent(EventType.SERVICE, "Queued self-test receipt to $recipient (queue #$queued)")
        ReceiptDrainWorker.enqueue(context)
        return Outcome.Queued(recipient)
    }
}
