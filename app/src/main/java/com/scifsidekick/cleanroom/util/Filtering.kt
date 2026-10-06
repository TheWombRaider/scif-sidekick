package com.scifsidekick.cleanroom.util

import com.scifsidekick.cleanroom.data.ContactFilterMode
import com.scifsidekick.cleanroom.data.FilterConditionMode
import com.scifsidekick.cleanroom.data.ForwardingFilterEntity
import com.scifsidekick.cleanroom.data.KeywordFilterMode
import com.scifsidekick.cleanroom.messaging.IncomingMessage
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class ReplaceRule(
    val find: String,
    val replace: String,
    val useRegex: Boolean = false,
)

object ReplaceRuleCodec {
    fun toJson(rules: List<ReplaceRule>): String =
        JSONArray()
            .apply {
                rules.forEach { rule ->
                    put(
                        JSONObject().apply {
                            put("find", rule.find)
                            put("replace", rule.replace)
                            put("useRegex", rule.useRegex)
                        },
                    )
                }
            }.toString()

    fun fromJson(json: String): List<ReplaceRule> =
        runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { index ->
                val obj = array.getJSONObject(index)
                ReplaceRule(obj.getString("find"), obj.optString("replace"), obj.optBoolean("useRegex", false))
            }
        }.getOrDefault(emptyList())
}

/**
 * Applies a filter's find-and-replace rules to a rendered message, in order. A rule with an
 * invalid regex (when [ReplaceRule.useRegex] is set) or an empty [ReplaceRule.find] is skipped
 * rather than thrown -- one malformed rule should never block a delivery.
 *
 * A regex rule's [ReplaceRule.find] is user-authored but runs against attacker-influenced text --
 * the body of whatever a stranger just texted the phone -- so a catastrophic-backtracking pattern
 * (`(a+)+$` against a long run of "a"s is the classic example) is a real, reachable way to hang
 * this call, and it runs synchronously inside `processIncoming`'s database transaction. Regex
 * matches therefore run on a small dedicated thread pool with a hard wall-clock budget per rule;
 * a rule that blows that budget is treated exactly like an invalid one -- skipped, text
 * unchanged -- rather than left free to stall every future message behind it. `Future.cancel`
 * cannot force Java's regex engine to actually stop mid-backtrack, so a pathological rule can
 * still burn a background thread indefinitely, but the pool is small and bounded, and daemon
 * threads never block the app itself from continuing to run.
 */
object ReplaceRuleEngine {
    private const val REGEX_TIMEOUT_MS = 300L

    fun apply(
        text: String,
        rules: List<ReplaceRule>,
    ): String =
        rules.fold(text) { acc, rule ->
            if (rule.find.isEmpty()) {
                acc
            } else if (rule.useRegex) {
                BoundedExecution.runWithTimeout(REGEX_TIMEOUT_MS, fallback = acc) {
                    runCatching { acc.replace(Regex(rule.find), rule.replace) }.getOrDefault(acc)
                }
            } else {
                runCatching { acc.replace(rule.find, rule.replace) }.getOrDefault(acc)
            }
        }
}

/**
 * Runs [block] with a hard wall-clock budget, returning [fallback] instead if it isn't met. A
 * small, dedicated, bounded pool of daemon threads -- never the shared coroutine dispatcher pool,
 * so a stuck task can't starve unrelated work -- backs every caller in the app that needs this
 * (today, just [ReplaceRuleEngine]'s user-authored regex rules, which run against whatever a
 * stranger just texted the phone). `Future.cancel` cannot force a currently-running computation
 * (Java regex backtracking included) to actually stop mid-flight, so a pathological task can
 * still occupy one pool thread indefinitely. The executor therefore has a hard two-item queue;
 * once workers and queue are saturated, new work is rejected immediately into the same safe
 * fallback instead of accumulating attacker-controlled tasks in memory.
 */
object BoundedExecution {
    private const val POOL_SIZE = 2
    private val executor =
        java.util.concurrent.ThreadPoolExecutor(
            POOL_SIZE,
            POOL_SIZE,
            0L,
            java.util.concurrent.TimeUnit.MILLISECONDS,
            java.util.concurrent.ArrayBlockingQueue(POOL_SIZE),
            { runnable -> Thread(runnable, "sidekick-bounded-exec").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
        )

    fun <T> runWithTimeout(
        timeoutMs: Long,
        fallback: T,
        block: () -> T,
    ): T {
        var future: java.util.concurrent.Future<T>? = null
        return try {
            val submitted = executor.submit(java.util.concurrent.Callable { block() })
            future = submitted
            submitted.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            future?.cancel(true)
            fallback
        } catch (_: Exception) {
            fallback
        }
    }
}

/**
 * Renders `{Placeholder}` tokens against a fixed, known set of variables. Deliberately not a
 * general template language -- a filter's stored subject/body template can only ever substitute
 * plain text into plain text, never execute anything.
 */
object MessageTemplateEngine {
    fun render(
        template: String,
        vars: Map<String, String>,
    ): String = vars.entries.fold(template) { acc, (key, value) -> acc.replace("{$key}", value) }
}

/** The placeholder values available to every filter's subject/body template. */
object MessageVariables {
    private fun timeFormat() = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

    fun forMessage(
        message: IncomingMessage,
        replyTarget: String?,
    ): Map<String, String> {
        val verb = if (message.source == "call") "Missed call" else "New ${message.source.uppercase(Locale.US)}"
        val replyTag = replyTarget?.let { "SCIF:$it" } ?: "SCIF-NOREPLY"
        return mapOf(
            "Reply Tag" to replyTag,
            "Verb" to verb,
            "Contact Name" to message.senderDisplay,
            "Incoming Number" to (replyTarget ?: message.senderAddress),
            "Message Body" to message.body,
            "Received Time" to timeFormat().format(Date(message.receivedAtMs)),
            "Source" to message.source.uppercase(Locale.US),
        )
    }
}

object OtpDetector {
    private val keywords =
        listOf(
            "otp", "one-time passcode", "one time passcode", "one-time password", "one time password",
            "verification code", "security code", "passcode", "access code", "login code", "auth code",
        )
    private val codePattern = Regex("\\b\\d{4,8}\\b")

    /**
     * True when [body] both mentions a recognized OTP/security keyword and contains a short
     * numeric code. Either signal alone is common in ordinary conversation ("call me at 5550" or
     * "here's the access code to the shed"); the combination is a much lower-false-positive
     * signal that this is an automated verification message worth always letting through.
     */
    fun looksLikeOtp(body: String): Boolean {
        val lower = body.lowercase()
        return keywords.any { lower.contains(it) } && codePattern.containsMatchIn(body)
    }
}

object ScheduleWindow {
    /**
     * [daysMask] is a 7-bit mask, bit 0 = Sunday ... bit 6 = Saturday (matching
     * `Calendar.DAY_OF_WEEK - 1`). [startMinute]/[endMinute] are minutes since local midnight.
     * When `startMinute > endMinute` the window spans overnight (e.g. 22:00-06:00): the late
     * portion of the window belongs to the day it starts on, the early-morning portion belongs
     * to the window that started the day before.
     */
    fun isActiveNow(
        daysMask: Int,
        startMinute: Int,
        endMinute: Int,
        nowMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Boolean {
        val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = nowMs }
        val minuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val todayBit = 1 shl (calendar.get(Calendar.DAY_OF_WEEK) - 1)
        return if (startMinute <= endMinute) {
            (daysMask and todayBit) != 0 && minuteOfDay in startMinute until endMinute
        } else {
            val yesterdayBit = 1 shl (((calendar.get(Calendar.DAY_OF_WEEK) - 1) + 6) % 7)
            ((daysMask and todayBit) != 0 && minuteOfDay >= startMinute) ||
                ((daysMask and yesterdayBit) != 0 && minuteOfDay < endMinute)
        }
    }
}

/**
 * Pure drag-and-drop reorder math, deliberately kept independent of Compose/LazyColumn/gesture
 * detection so it can be exhaustively unit- and fuzz-tested without a device: given a dragged
 * item and how far it has moved, decide where it now belongs.
 */
object ReorderMath {
    /**
     * [dragOffsetPx] is the cumulative drag distance since the gesture began; [rowExtentPx] is
     * one row's height plus the gap between rows. Returns the reordered list and the dragged
     * item's new index. Never throws: an empty list, a non-positive [rowExtentPx], or an
     * out-of-range [draggedIndex] all degrade to a no-op rather than an exception, since a UI
     * gesture callback is the last place that should ever crash the app.
     */
    fun <T> reorder(
        items: List<T>,
        draggedIndex: Int,
        dragOffsetPx: Float,
        rowExtentPx: Float,
    ): Pair<List<T>, Int> {
        // An out-of-range draggedIndex is clamped rather than passed through -- every real
        // caller already guards against that case, but the returned index is otherwise part of
        // this function's contract and must always be a valid index into the returned list
        // (or 0 for an empty list), never whatever nonsense the caller happened to pass in.
        if (items.isEmpty()) return items to 0
        val safeIndex = draggedIndex.coerceIn(0, items.lastIndex)
        if (rowExtentPx <= 0f || !dragOffsetPx.isFinite()) return items to safeIndex
        val delta = kotlin.math.round(dragOffsetPx / rowExtentPx).toInt()
        val targetIndex = (safeIndex + delta).coerceIn(0, items.lastIndex)
        if (targetIndex == safeIndex) return items to safeIndex
        val mutable = items.toMutableList()
        val moving = mutable.removeAt(safeIndex)
        mutable.add(targetIndex, moving)
        return mutable to targetIndex
    }
}

/** Evaluates one [ForwardingFilterEntity]'s message-type scope and forward-by-conditions rules
 *  against one incoming message. Schedule and message-type gates are never bypassed by the OTP
 *  override -- only the contact/keyword conditions are. */
object FilterConditionEvaluator {
    fun matchesMessageType(
        filter: ForwardingFilterEntity,
        source: String,
    ): Boolean =
        when (source) {
            "sms" -> filter.includeSms
            "mms" -> filter.includeMms
            "rcs" -> filter.includeRcs
            "call" -> filter.includeCalls
            else -> false
        }

    fun matchesConditions(
        filter: ForwardingFilterEntity,
        normalizedSender: String?,
        body: String,
        isOtp: Boolean,
    ): Boolean {
        if (filter.conditionMode == FilterConditionMode.ALL) return true
        if (isOtp && filter.alwaysAllowOtp) return true

        val contactNumbers by lazy { PayloadCodec.pathsFromJson(filter.contactNumbersJson).toSet() }
        val contactOk =
            when (filter.contactMode) {
                ContactFilterMode.WHITELIST -> normalizedSender != null && normalizedSender in contactNumbers
                ContactFilterMode.BLACKLIST -> normalizedSender == null || normalizedSender !in contactNumbers
                else -> true
            }
        if (!contactOk) return false

        val keywords by lazy { PayloadCodec.pathsFromJson(filter.keywordsJson) }
        return when (filter.keywordMode) {
            KeywordFilterMode.MUST_CONTAIN -> keywords.isEmpty() || keywords.any { body.contains(it, ignoreCase = true) }
            KeywordFilterMode.MUST_NOT_CONTAIN -> keywords.none { body.contains(it, ignoreCase = true) }
            else -> true
        }
    }
}
