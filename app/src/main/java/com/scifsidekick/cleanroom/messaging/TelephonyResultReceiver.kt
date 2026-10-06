package com.scifsidekick.cleanroom.messaging

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.scifsidekick.cleanroom.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object TelephonyResultContract {
    const val ACTION_SENT_RESULT = "com.scifsidekick.cleanroom.action.TELEPHONY_SENT_RESULT"
    const val EXTRA_QUEUE_ID = "queue_id"
    const val EXTRA_ATTEMPT_ID = "attempt_id"
    const val EXTRA_PART_INDEX = "part_index"
    const val EXTRA_PART_COUNT = "part_count"
    const val EXTRA_TEMP_PATH = "temp_path"

    fun pendingIntent(
        context: Context,
        queueId: Long,
        attemptId: Long,
        partIndex: Int,
        partCount: Int,
        tempPath: String? = null,
    ): PendingIntent {
        val intent =
            Intent(context, TelephonyResultReceiver::class.java)
                .setAction(ACTION_SENT_RESULT)
                .setData(Uri.parse("scifsidekick://telephony-result/$attemptId/$partIndex"))
                .putExtra(EXTRA_QUEUE_ID, queueId)
                .putExtra(EXTRA_ATTEMPT_ID, attemptId)
                .putExtra(EXTRA_PART_INDEX, partIndex)
                .putExtra(EXTRA_PART_COUNT, partCount)
        if (tempPath != null) intent.putExtra(EXTRA_TEMP_PATH, tempPath)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** Receives Android's asynchronous sent result and durably finalizes the matching queue attempt. */
class TelephonyResultReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != TelephonyResultContract.ACTION_SENT_RESULT) return
        val queueId = intent.getLongExtra(TelephonyResultContract.EXTRA_QUEUE_ID, -1L)
        val attemptId = intent.getLongExtra(TelephonyResultContract.EXTRA_ATTEMPT_ID, -1L)
        val partIndex = intent.getIntExtra(TelephonyResultContract.EXTRA_PART_INDEX, -1)
        val partCount = intent.getIntExtra(TelephonyResultContract.EXTRA_PART_COUNT, -1)
        if (queueId <= 0L || attemptId <= 0L || partIndex !in 0 until partCount || partCount !in 1..SmsGateway.MAX_SEGMENTS_PER_REPLY) {
            return
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                AppGraph.from(context).queueProcessor.recordTelephonyResult(
                    queueId = queueId,
                    attemptId = attemptId,
                    partIndex = partIndex,
                    partCount = partCount,
                    resultCode = resultCode,
                )
                intent.getStringExtra(TelephonyResultContract.EXTRA_TEMP_PATH)?.let { path ->
                    MmsGateway.deleteTemporaryPdu(context, path)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
