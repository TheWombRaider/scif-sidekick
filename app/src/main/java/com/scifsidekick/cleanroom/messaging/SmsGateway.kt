package com.scifsidekick.cleanroom.messaging

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import com.scifsidekick.cleanroom.util.PremiumNumbers
import com.scifsidekick.cleanroom.util.ReplySafetyPolicy

class SmsGateway(
    private val context: Context,
) {
    fun send(
        payload: SmsReplyPayload,
        queueId: Long,
        attemptId: Long,
        configuredSubscriptionId: Int = SimSelection.SYSTEM_DEFAULT,
    ): Int {
        check(context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            "SEND_SMS permission has not been granted"
        }
        ReplySafetyPolicy.rejectionReason(payload.body)?.let { throw IllegalArgumentException(it) }
        PremiumNumbers.rejectionReason(payload.targetNumber)?.let { throw PermanentDeliveryException(it) }
        val manager = smsManager(SimSelection.resolve(context, configuredSubscriptionId))
        val parts = manager.divideMessage(payload.body)
        if (parts.size > MAX_SEGMENTS_PER_REPLY) {
            throw PermanentDeliveryException(
                "Reply expands to ${parts.size} SMS segments; the safety limit is $MAX_SEGMENTS_PER_REPLY",
            )
        }
        val sentResults =
            ArrayList(
                parts.indices.map { index ->
                    TelephonyResultContract.pendingIntent(context, queueId, attemptId, index, parts.size)
                },
            )
        if (parts.size <= 1) {
            manager.sendTextMessage(payload.targetNumber, null, payload.body, sentResults.single(), null)
        } else {
            manager.sendMultipartTextMessage(payload.targetNumber, null, ArrayList(parts), sentResults, null)
        }
        return parts.size
    }

    /** [subscriptionId] is already resolved by [SimSelection.resolve] -- that is the one place
     *  allowed to decide which line to use, and it never returns an id it could not confirm. */
    private fun smsManager(subscriptionId: Int): SmsManager {
        val base = context.getSystemService(SmsManager::class.java) ?: error("SmsManager unavailable")
        return if (subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                base.createForSubscriptionId(subscriptionId)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            }
        } else {
            base
        }
    }

    companion object {
        const val MAX_SEGMENTS_PER_REPLY = 10
    }
}

class PermanentDeliveryException(
    message: String,
) : Exception(message)
