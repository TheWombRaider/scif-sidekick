package com.scifsidekick.cleanroom.messaging

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import com.klinker.android.send_message.Message
import com.klinker.android.send_message.Settings
import com.klinker.android.send_message.Transaction
import com.scifsidekick.cleanroom.util.PremiumNumbers
import com.scifsidekick.cleanroom.util.ReplySafetyPolicy
import com.google.android.mms.MMSPart
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Sends an email-triggered picture message. Android has no public high-level API for composing
 * an MMS -- the PDU-encoding classes (`com.google.android.mms.pdu`) were removed from the SDK --
 * so this wraps `com.klinkerapps:android-smsmms`, which vendors the old AOSP PDU encoder and
 * hands the finished PDU to `SmsManager.sendMultimediaMessage` (`setUseSystemSending(true)`
 * below), the same "system sending" path Google Messages itself uses. That library is archived
 * (no further upstream releases) but was verified this session to still resolve from Maven
 * Central and compile cleanly against the current target SDK; its exact API surface here was
 * confirmed by decompiling the AAR's classes with javap rather than guessed from documentation.
 *
 * One consequence of that decompilation matters for how images are handled: [Message]'s bitmap
 * overload does not accept a size or quality hint, and `Message.bitmapToByteArray` -- confirmed
 * by reading its bytecode -- always re-encodes whatever [Bitmap] it is given as JPEG at a fixed
 * quality of 90. Pre-compressing the file therefore buys nothing; the only lever this gateway
 * actually has over the final MMS size is the bitmap's pixel dimensions, which is what
 * [decodeWithinBudget] downscales.
 *
 * What this class cannot do: verify actual delivery against real carrier/MMSC infrastructure.
 * [TARGET_BUDGET_BYTES] is a deliberately conservative guess at a size most carriers accept, not
 * a number measured against one -- there is no way to verify that without a physical device on
 * a live SIM. `useSystemSending(true)` defers the MMSC/APN handshake itself to the OS, which is
 * the most reliable option available without that hardware, but this is disclosed as unverified.
 */
class MmsGateway(
    private val context: Context,
) {
    fun send(
        payload: MmsReplyPayload,
        imagePath: String,
        queueId: Long,
        attemptId: Long,
        configuredSubscriptionId: Int = SimSelection.SYSTEM_DEFAULT,
    ) {
        check(context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            "SEND_SMS permission has not been granted"
        }
        ReplySafetyPolicy.rejectionReason(payload.body, allowBlank = true)?.let { throw IllegalArgumentException(it) }
        PremiumNumbers.rejectionReason(payload.targetNumber)?.let { throw PermanentDeliveryException(it) }
        val bitmap =
            decodeWithinBudget(imagePath)
                ?: throw PermanentDeliveryException("Attached image could not be decoded")
        val subscriptionId = SimSelection.resolve(context, configuredSubscriptionId)
        val settings = Settings().apply {
            if (subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) setSubscriptionId(subscriptionId)
        }
        // Initializing Transaction installs these settings for its public PDU encoder. We send the
        // resulting PDU ourselves because 5.2.6 creates a PendingIntent without an Android 12+
        // mutability flag and also swallows the resulting exception.
        Transaction(context, settings)
        val parts =
            buildList {
                add(
                    MMSPart().apply {
                        Name = "image.jpg"
                        MimeType = "image/jpeg"
                        Data = Message.bitmapToByteArray(bitmap)
                    },
                )
                if (payload.body.isNotBlank()) {
                    add(
                        MMSPart().apply {
                            Name = "text.txt"
                            MimeType = "text/plain"
                            Data = payload.body.toByteArray(Charsets.UTF_8)
                        },
                    )
                }
            }.toTypedArray()
        bitmap.recycle()
        val info = Transaction.getBytes(context, false, null, arrayOf(payload.targetNumber), parts, null)
        val bytes = info.bytes ?: throw PermanentDeliveryException("MMS PDU encoder produced no data")
        val pduFile = File(context.cacheDir, "scif_mms_${attemptId}.pdu")
        pduFile.writeBytes(bytes)
        val contentUri =
            Uri.Builder()
                .scheme("content")
                .authority("${context.packageName}.MmsFileProvider")
                .path(pduFile.name)
                .build()
        val sentResult = TelephonyResultContract.pendingIntent(context, queueId, attemptId, 0, 1, pduFile.absolutePath)
        try {
            smsManager(subscriptionId).sendMultimediaMessage(context, contentUri, null, null, sentResult)
        } catch (failure: Exception) {
            deleteTemporaryPdu(context, pduFile.absolutePath)
            throw failure
        }
    }

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

    fun pruneTemporaryPdus(olderThanMs: Long): Int =
        context.cacheDir
            .listFiles()
            .orEmpty()
            .count { file ->
                file.name.startsWith("scif_mms_") && file.name.endsWith(".pdu") &&
                    file.lastModified() < olderThanMs && runCatching { file.delete() }.getOrDefault(false)
            }

    /**
     * Decodes the stored image, downscaling by pixel dimensions (not JPEG quality -- see class
     * doc) until a JPEG@90 encode of it fits [TARGET_BUDGET_BYTES], or [MAX_DOWNSCALE_ATTEMPTS]
     * is exhausted, whichever comes first. The loop is bounded on both sides deliberately: an
     * unbounded "keep shrinking until it fits" loop over a hostile or corrupt image is the same
     * class of runaway-CPU risk this codebase already guards against elsewhere (see
     * BoundedExecution), and [MIN_DIMENSION_PX] stops it from shrinking a photo into something
     * unrecognisable chasing a budget it may never reach. Returns the best attempt made, not
     * necessarily one that met the budget -- best-effort, since failing outright over an
     * oversized image is worse than sending a large one and letting the underlying send fail on
     * whatever the real carrier limit turns out to be.
     */
    private fun decodeWithinBudget(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= MAX_DIMENSION_PX) {
            sampleSize *= 2
        }
        var bitmap =
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sampleSize })
                ?: return null

        repeat(MAX_DOWNSCALE_ATTEMPTS) {
            val encodedSize = ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out); out.size() }
            if (encodedSize <= TARGET_BUDGET_BYTES) return bitmap
            val nextWidth = (bitmap.width * DOWNSCALE_FACTOR).toInt().coerceAtLeast(MIN_DIMENSION_PX)
            val nextHeight = (bitmap.height * DOWNSCALE_FACTOR).toInt().coerceAtLeast(MIN_DIMENSION_PX)
            if (nextWidth == bitmap.width && nextHeight == bitmap.height) return bitmap
            val scaled = Bitmap.createScaledBitmap(bitmap, nextWidth, nextHeight, true)
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }
        return bitmap
    }

    companion object {
        fun deleteTemporaryPdu(
            context: Context,
            path: String,
        ) {
            val file = File(path)
            if (file.parentFile?.canonicalFile == context.cacheDir.canonicalFile && file.name.startsWith("scif_mms_")) {
                runCatching { file.delete() }
            }
        }

        // Conservative on purpose: many US carriers still cap MMS around 600KB-1MB even on
        // modern networks, and this cannot be measured per-carrier without live hardware.
        const val TARGET_BUDGET_BYTES = 600_000
        const val MAX_DIMENSION_PX = 1_600
        const val MIN_DIMENSION_PX = 320
        const val DOWNSCALE_FACTOR = 0.7
        const val MAX_DOWNSCALE_ATTEMPTS = 6
    }
}
