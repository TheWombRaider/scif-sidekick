package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.data.ContactFilterMode
import com.scifsidekick.cleanroom.data.FilterConditionMode
import com.scifsidekick.cleanroom.data.ForwardingFilterEntity
import com.scifsidekick.cleanroom.data.KeywordFilterMode
import com.scifsidekick.cleanroom.email.MimeMessageBuilder
import com.scifsidekick.cleanroom.messaging.EmailPayload
import com.scifsidekick.cleanroom.messaging.IncomingMessage
import com.scifsidekick.cleanroom.messaging.MmsReplyPayload
import com.scifsidekick.cleanroom.messaging.RcsNotificationPolicy
import com.scifsidekick.cleanroom.messaging.SmsReplyPayload
import com.scifsidekick.cleanroom.service.CircuitPolicy
import com.scifsidekick.cleanroom.service.HardRateLimits
import com.scifsidekick.cleanroom.util.ComposeAuthorization
import com.scifsidekick.cleanroom.util.FilterConditionEvaluator
import com.scifsidekick.cleanroom.email.GmailAuthentication
import com.scifsidekick.cleanroom.util.MessageTemplateEngine
import com.scifsidekick.cleanroom.util.MessageVariables
import com.scifsidekick.cleanroom.util.OtpDetector
import com.scifsidekick.cleanroom.util.PayloadCodec
import com.scifsidekick.cleanroom.util.PhoneNumbers
import com.scifsidekick.cleanroom.util.RemoteCommand
import com.scifsidekick.cleanroom.util.RemoteCommands
import com.scifsidekick.cleanroom.util.ReplaceRule
import com.scifsidekick.cleanroom.util.ReplaceRuleCodec
import com.scifsidekick.cleanroom.util.ReplaceRuleEngine
import com.scifsidekick.cleanroom.util.ReplyBodyCleaner
import com.scifsidekick.cleanroom.util.ReplySafetyPolicy
import com.scifsidekick.cleanroom.util.ScheduleWindow
import com.scifsidekick.cleanroom.util.WatermarkPolicy
import com.scifsidekick.cleanroom.util.normalizeForDedupe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.Calendar

class SafetyPolicyTest {
    @Test fun `watermark is strict and off always discards`() {
        assertFalse(WatermarkPolicy.isEligible(false, 100, 101))
        assertFalse(WatermarkPolicy.isEligible(true, 100, 100))
        assertFalse(WatermarkPolicy.isEligible(true, 100, 99))
        assertTrue(WatermarkPolicy.isEligible(true, 100, 101))
    }

    @Test fun `RCS notification intake is restricted to supported messaging apps`() {
        assertTrue(RcsNotificationPolicy.isSupportedPackage("com.google.android.apps.messaging"))
        assertTrue(RcsNotificationPolicy.isSupportedPackage("com.samsung.android.messaging"))
        assertFalse(RcsNotificationPolicy.isSupportedPackage("com.example.unrelated"))
    }

    @Test fun `RCS notification content is bounded and empty content is rejected`() {
        assertNull(RcsNotificationPolicy.cleanText("  "))
        assertEquals("hello\n\nworld", RcsNotificationPolicy.cleanText(" hello\n\n\nworld "))
        assertEquals(20_000, RcsNotificationPolicy.cleanText("a".repeat(25_000))?.length)
        assertTrue(RcsNotificationPolicy.looksLikeMediaOnly("Photo"))
    }

    @Test fun `sender cleanup strips the Google Messages unverified-name tilde`() {
        assertEquals("Tj Oishee", RcsNotificationPolicy.cleanSender("~Tj Oishee"))
        assertEquals("Tj Oishee", RcsNotificationPolicy.cleanSender("  ~Tj Oishee  "))
        assertEquals("Alex", RcsNotificationPolicy.cleanSender("Alex"))
        assertNull(RcsNotificationPolicy.cleanSender("~"))
        assertNull(RcsNotificationPolicy.cleanSender("  "))
    }

    @Test fun `media-only detection survives realistic notification phrasing`() {
        assertTrue(RcsNotificationPolicy.looksLikeMediaOnly("📷 Photo"))
        assertTrue(RcsNotificationPolicy.looksLikeMediaOnly("Sent a photo"))
        assertTrue(RcsNotificationPolicy.looksLikeMediaOnly("Sent an attachment"))
        assertTrue(RcsNotificationPolicy.looksLikeMediaOnly("🎤 Voice message · 0:12"))
        assertFalse(RcsNotificationPolicy.looksLikeMediaOnly("Photo finish at the race today"))
        assertFalse(RcsNotificationPolicy.looksLikeMediaOnly("Can you send the photo report by Friday?"))
    }

    @Test fun `subject tag survives repeated reply prefixes`() {
        val subject = "Re: RE: Aw: [SCIF:+15551234567] New text from Alex"
        assertEquals("+15551234567", PhoneNumbers.extractFromSubject(subject))
        assertNull(PhoneNumbers.extractFromSubject("Re: ordinary message"))
    }

    @Test fun `compose-new tag is recognized separately from the reply tag`() {
        assertEquals("+15551234567", PhoneNumbers.extractComposeTarget("TEXT+15551234567"))
        assertEquals("+15551234567", PhoneNumbers.extractComposeTarget("  text+15551234567  "))
        // Compose is a complete command envelope, never a substring of attacker-controlled text.
        assertNull(PhoneNumbers.extractComposeTarget("text+15551234567 -- Running late"))
        assertNull(PhoneNumbers.extractComposeTarget("[SCIF:+15551234567] New text from Alex"))
        assertNull(PhoneNumbers.extractFromSubject("TEXT+15551234567"))
        // A word boundary is required before "TEXT" so it can't match inside another word.
        assertNull(PhoneNumbers.extractComposeTarget("CONTEXT+15551234567"))
    }

    @Test fun `remote-enable command tag is recognized and never confused with the reply tag`() {
        assertTrue(RemoteCommands.isEnableForwardingCommand("[SCIF:ON]"))
        assertTrue(RemoteCommands.isEnableForwardingCommand("Re: [SCIF:on] please"))
        assertFalse(RemoteCommands.isEnableForwardingCommand("[SCIF:+15551234567] New text from Alex"))
        assertFalse(RemoteCommands.isEnableForwardingCommand("Turn forwarding ON"))
        assertNull(PhoneNumbers.extractFromSubject("[SCIF:ON]"))
    }

    @Test fun `remote-disable command tag is recognized and never confused with enable or reply tags`() {
        assertTrue(RemoteCommands.isDisableForwardingCommand("[SCIF:OFF]"))
        assertTrue(RemoteCommands.isDisableForwardingCommand("Re: [scif:off] stand down"))
        assertFalse(RemoteCommands.isDisableForwardingCommand("[SCIF:+15551234567] New text from Alex"))
        assertFalse(RemoteCommands.isDisableForwardingCommand("Turn forwarding OFF"))
        assertNull(PhoneNumbers.extractFromSubject("[SCIF:OFF]"))
        assertNull(PhoneNumbers.extractComposeTarget("[SCIF:OFF]"))

        // The two tags must never read as each other: "[SCIF:OFF]" contains neither "[SCIF:ON]"
        // as a substring nor vice versa, and this is the test that would catch a future regex
        // loosened to e.g. \[SCIF:ON.
        assertFalse(RemoteCommands.isEnableForwardingCommand("[SCIF:OFF]"))
        assertFalse(RemoteCommands.isDisableForwardingCommand("[SCIF:ON]"))
        assertEquals(RemoteCommand.ENABLE, RemoteCommands.parse("[SCIF:ON]"))
        assertEquals(RemoteCommand.DISABLE, RemoteCommands.parse("[SCIF:OFF]"))
    }

    @Test fun `reply payloads round-trip the initiator without losing older rows`() {
        val sms = SmsReplyPayload("+15551234567", "on my way", "gmail-1", "owner@example.com", "thread-9")
        val decodedSms = PayloadCodec.smsFromJson(PayloadCodec.smsToJson(sms))
        assertEquals(sms, decodedSms)

        val mms = MmsReplyPayload("+15551234567", "photo", "gmail-2", "owner@example.com", "thread-9")
        assertEquals(mms, PayloadCodec.mmsFromJson(PayloadCodec.mmsToJson(mms)))

        // A row queued before confirmations existed carries neither field. It must decode as "no
        // initiator" -- never as the literal string "null", which is what optString hands back for
        // a stored JSON null and what would otherwise be emailed as if it were an address.
        val legacy = "{\"targetNumber\":\"+15551234567\",\"body\":\"hi\",\"gmailMessageId\":\"gmail-3\"}"
        val decodedLegacy = PayloadCodec.smsFromJson(legacy)
        assertNull(decodedLegacy.initiatorAddress)
        assertNull(decodedLegacy.initiatorThreadId)

        val explicitNulls = PayloadCodec.smsToJson(SmsReplyPayload("+15551234567", "hi", "gmail-4"))
        assertNull(PayloadCodec.smsFromJson(explicitNulls).initiatorAddress)
    }

    @Test fun `a subject carrying more than one command tag is ambiguous and commands none`() {
        assertNull(RemoteCommands.parse("[SCIF:ON] [SCIF:OFF]"))
        assertNull(RemoteCommands.parse("Re: [SCIF:OFF] -- ignore my earlier [SCIF:ON]"))
        assertNull(RemoteCommands.parse("[SCIF:STATUS] [SCIF:OFF]"))
        assertNull(RemoteCommands.parse("[SCIF:ON] [SCIF:STATUS]"))
        assertFalse(RemoteCommands.isEnableForwardingCommand("[SCIF:ON] [SCIF:OFF]"))
        assertFalse(RemoteCommands.isDisableForwardingCommand("[SCIF:ON] [SCIF:OFF]"))
        assertFalse(RemoteCommands.isStatusCommand("[SCIF:ON] [SCIF:STATUS]"))
    }

    @Test fun `status command tag is recognized and distinct from every other tag`() {
        assertTrue(RemoteCommands.isStatusCommand("[SCIF:STATUS]"))
        assertTrue(RemoteCommands.isStatusCommand("Re: [scif:status] please"))
        assertEquals(RemoteCommand.STATUS, RemoteCommands.parse("[SCIF:STATUS]"))
        assertFalse(RemoteCommands.isStatusCommand("[SCIF:ON]"))
        assertFalse(RemoteCommands.isStatusCommand("[SCIF:OFF]"))
        assertFalse(RemoteCommands.isEnableForwardingCommand("[SCIF:STATUS]"))
        assertFalse(RemoteCommands.isDisableForwardingCommand("[SCIF:STATUS]"))
        assertFalse(RemoteCommands.isStatusCommand("what is the status"))
        // Must never be mistaken for a routing target either.
        assertNull(PhoneNumbers.extractFromSubject("[SCIF:STATUS]"))
        assertNull(PhoneNumbers.extractComposeTarget("[SCIF:STATUS]"))
    }

    @Test fun `ambiguous reply subjects with two routing targets are rejected`() {
        assertNull(PhoneNumbers.extractFromSubject("Re: [SCIF:+15551234567] [SCIF:+15559876543]"))
        assertEquals(
            "+15551234567",
            PhoneNumbers.extractFromSubject("Re: [SCIF:+15551234567] [SCIF:+15551234567]"),
        )
    }

    @Test fun `missed-call phone extraction finds a number in typical notification text`() {
        assertEquals("+1 555-123-4567", RcsNotificationPolicy.extractLikelyPhoneNumber("+1 555-123-4567"))
        assertEquals("(555) 123-4567", RcsNotificationPolicy.extractLikelyPhoneNumber("2 missed calls from (555) 123-4567"))
        assertNull(RcsNotificationPolicy.extractLikelyPhoneNumber("Missed call from Alex"))
    }

    @Test fun `reply cleaner excludes quoted message and routing tag`() {
        val raw = "Sounds good [SCIF:+15551234567]\n\nOn Fri, Alex wrote:\n> old body"
        assertEquals("Sounds good", ReplyBodyCleaner.clean(raw))
    }

    @Test fun `reply cleaner does not truncate a reply that merely starts with From and no address`() {
        assertEquals("From: Mom, tell dad hi", ReplyBodyCleaner.clean("From: Mom, tell dad hi"))
    }

    @Test fun `reply cleaner still truncates a quoted mail client From header`() {
        assertEquals("Sure thing", ReplyBodyCleaner.clean("Sure thing\nFrom: Jane Doe <jane@example.com>\n> old"))
    }

    @Test fun `reply cleaner strips Outlook's underscore divider and everything after it`() {
        val raw =
            "Test reply\n________________________________\nFrom: sender@example.com " +
                "<sender@example.com>\nSent: Wednesday, September 10, 2026 11:58 AM\n" +
                "To: me@example.com\nSubject: [SCIF:+15551234567] New RCS from Jane\n\nold body"
        assertEquals("Test reply", ReplyBodyCleaner.clean(raw))
    }

    @Test fun `circuit opens on fifth consecutive failure and success resets`() {
        var failures = 0
        repeat(4) { failures = CircuitPolicy.failuresAfter(failures, false) }
        assertFalse(CircuitPolicy.isOpen(failures))
        failures = CircuitPolicy.failuresAfter(failures, false)
        assertTrue(CircuitPolicy.isOpen(failures))
        assertEquals(0, CircuitPolicy.failuresAfter(failures, true))
    }

    @Test fun `hard caps cannot be configured away`() {
        assertEquals(listOf(20, 300, 450), HardRateLimits.EMAIL.map { it.maxAttempts })
        assertEquals(listOf(10, 60, 200), HardRateLimits.SMS.map { it.maxAttempts })
    }

    @Test fun `oversized or empty email replies cannot fan out into SMS`() {
        assertTrue(ReplySafetyPolicy.rejectionReason("")!!.contains("no new text"))
        assertNull(ReplySafetyPolicy.rejectionReason("a".repeat(ReplySafetyPolicy.MAX_SMS_REPLY_CHARACTERS)))
        assertTrue(
            ReplySafetyPolicy
                .rejectionReason("a".repeat(ReplySafetyPolicy.MAX_SMS_REPLY_CHARACTERS + 1))!!
                .contains("safety limit"),
        )
    }

    @Test fun `picture replies may omit a text caption but retain the length ceiling`() {
        assertNull(ReplySafetyPolicy.rejectionReason("", allowBlank = true))
        assertTrue(
            ReplySafetyPolicy
                .rejectionReason(
                    "a".repeat(ReplySafetyPolicy.MAX_SMS_REPLY_CHARACTERS + 1),
                    allowBlank = true,
                )!!
                .contains("safety limit"),
        )
    }

    @Test fun `delivery message id is deterministic and valid`() {
        val first = MimeMessageBuilder.rfcMessageId("queue-42")
        val second = MimeMessageBuilder.rfcMessageId("queue-42")
        assertEquals(first, second)
        assertTrue(first.matches(Regex("scif-[a-f0-9]{40}@scif-sidekick\\.invalid")))
        assertFalse(first == MimeMessageBuilder.rfcMessageId("queue-43"))
    }

    @Test fun `mime identifies missing queued attachments instead of silently dropping them`() {
        val built =
            MimeMessageBuilder.build(
                payload = testPayload(replyTarget = "+15551234567", renderedSubject = "[SCIF:+15551234567] New text from Jöhn Doe"),
                attachmentPaths = listOf("/path/that/does/not/exist.jpg"),
                deliveryKey = "queue-42",
                fromAddress = "sender@example.com",
            )
        val padded = built.rawBase64Url + "=".repeat((4 - built.rawBase64Url.length % 4) % 4)
        val raw = String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
        assertEquals(1, built.missingAttachmentCount)
        assertTrue(raw.contains("From: sender@example.com"))
        assertTrue(raw.contains("Message-ID: <${MimeMessageBuilder.rfcMessageId("queue-42")}>"))
        assertTrue(raw.contains("Subject: [SCIF:+15551234567] =?UTF-8?B?"))
    }

    @Test fun `mime supports multiple recipients on one To header`() {
        val built =
            MimeMessageBuilder.build(
                payload = testPayload(destinations = listOf("a@example.com", "b@example.com")),
                attachmentPaths = emptyList(),
                deliveryKey = "queue-1",
                fromAddress = "sender@example.com",
            )
        val padded = built.rawBase64Url + "=".repeat((4 - built.rawBase64Url.length % 4) % 4)
        val raw = String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
        assertTrue(raw.contains("To: a@example.com, b@example.com"))
    }

    @Test fun `long non-ascii subject folds into multiple encoded-words instead of one unbounded line`() {
        val longName = "Ünïcödé Nâme " + "ü".repeat(200)
        val built =
            MimeMessageBuilder.build(
                payload = testPayload(renderedSubject = "[SCIF:+15551234567] $longName"),
                attachmentPaths = emptyList(),
                deliveryKey = "queue-9",
                fromAddress = "sender@example.com",
            )
        val padded = built.rawBase64Url + "=".repeat((4 - built.rawBase64Url.length % 4) % 4)
        val raw = String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
        val subjectLine = raw.lineSequence().first { it.startsWith("Subject:") }
        // The folded continuation lines start with a space, so the logical header still parses
        // as one Subject even though it spans several physical lines; each physical line stays
        // well under common length limits.
        val physicalLines = raw.substringAfter("Subject: ").substringBefore("\r\nDate:").split("\r\n")
        assertTrue(physicalLines.size > 1)
        physicalLines.forEach { line -> assertTrue(line.length <= 80) }
        assertTrue(subjectLine.startsWith("Subject: [SCIF:+15551234567] =?UTF-8?B?"))
    }

    private fun testPayload(
        destinations: List<String> = listOf("destination@example.com"),
        replyTarget: String? = "+15551234567",
        renderedSubject: String = "[SCIF:+15551234567] New text from Jane",
        renderedBody: String = "hello",
    ) = EmailPayload(
        destinations = destinations,
        replyTarget = replyTarget,
        senderDisplay = "Jane",
        body = "hello",
        receivedAtMs = 1_000L,
        source = "sms",
        participants = emptyList(),
        attachmentNotice = null,
        renderedSubject = renderedSubject,
        renderedBody = renderedBody,
        filterName = "Default",
    )

    // -------------------------------------------------------------------------- new for 1.4.0

    @Test fun `dedupe normalization collapses whitespace and case so raw and cleaned text match`() {
        assertEquals(normalizeForDedupe("Hello   world"), normalizeForDedupe("hello world\n\n"))
        assertEquals("hi there", normalizeForDedupe("  Hi\tThere  "))
    }

    @Test fun `otp detector requires both a recognized keyword and a short numeric code`() {
        assertTrue(OtpDetector.looksLikeOtp("Your OTP is 482913"))
        assertTrue(OtpDetector.looksLikeOtp("Your verification code: 5521"))
        assertFalse(OtpDetector.looksLikeOtp("Call me at 555-0100"))
        assertFalse(OtpDetector.looksLikeOtp("Your one-time passcode will arrive shortly"))
    }

    @Test fun `compose authorization trusts only addresses on the allowlist`() {
        assertTrue(ComposeAuthorization.isAuthorizedSender("boss@agency.gov", listOf("BOSS@agency.gov")))
        assertTrue(ComposeAuthorization.isAuthorizedSender(" boss@agency.gov ", listOf("boss@agency.gov")))
        assertFalse(ComposeAuthorization.isAuthorizedSender("attacker@example.com", listOf("boss@agency.gov")))
        assertFalse(ComposeAuthorization.isAuthorizedSender(null, listOf("boss@agency.gov")))
        // The default, empty allowlist authorizes nothing -- the feature must be opted into by
        // adding at least one address, not merely by flipping the master switch on.
        assertFalse(ComposeAuthorization.isAuthorizedSender("boss@agency.gov", emptyList()))
    }

    @Test fun `compose authorization extracts a bare address from a display-name From header`() {
        assertEquals("jane@example.com", ComposeAuthorization.extractAddress("Jane Doe <jane@example.com>"))
        assertEquals("jane@example.com", ComposeAuthorization.extractAddress("jane@example.com"))
        assertNull(ComposeAuthorization.extractAddress("not an address"))
        assertNull(ComposeAuthorization.extractAddress("a@example.com, b@example.com"))
        assertNull(ComposeAuthorization.extractAddress("a@example.com\r\nBcc: victim@example.com"))
    }

    @Test fun `gmail authentication requires topmost google dmarc pass aligned to From`() {
        assertEquals(
            "boss@agency.gov",
            GmailAuthentication.authenticatedFrom(
                "Boss <boss@agency.gov>",
                listOf("mx.google.com; dmarc=pass (p=REJECT sp=REJECT dis=NONE) header.from=agency.gov"),
            ),
        )
        assertNull(
            GmailAuthentication.authenticatedFrom(
                "Boss <boss@agency.gov>",
                listOf("mx.google.com; dmarc=fail header.from=agency.gov"),
            ),
        )
        assertNull(
            GmailAuthentication.authenticatedFrom(
                "Boss <boss@agency.gov>",
                listOf("attacker.example; dmarc=pass header.from=agency.gov", "mx.google.com; dmarc=pass header.from=agency.gov"),
            ),
        )
        assertNull(
            GmailAuthentication.authenticatedFrom(
                "Boss <boss@agency.gov>",
                listOf("mx.google.com; dmarc=pass header.from=example.com"),
            ),
        )
    }

    @Test fun `replace rule engine applies plain and regex rules and skips an invalid regex`() {
        val rules = listOf(ReplaceRule("cat", "dog"), ReplaceRule("\\d+", "#", useRegex = true))
        assertEquals("dog #s and #", ReplaceRuleEngine.apply("cat 12s and 3", rules))
        val withBadRegex = listOf(ReplaceRule("[", "x", useRegex = true))
        assertEquals("unchanged", ReplaceRuleEngine.apply("unchanged", withBadRegex))
    }

    @Test fun `replace rule codec round-trips through json`() {
        val rules = listOf(ReplaceRule("a", "b", useRegex = true), ReplaceRule("c", "d"))
        assertEquals(rules, ReplaceRuleCodec.fromJson(ReplaceRuleCodec.toJson(rules)))
    }

    @Test fun `message template engine only substitutes known placeholders`() {
        val rendered = MessageTemplateEngine.render("[{Reply Tag}] {Verb} from {Contact Name}", mapOf(
            "Reply Tag" to "SCIF:+15551234567",
            "Verb" to "New text",
            "Contact Name" to "Alex",
        ))
        assertEquals("[SCIF:+15551234567] New text from Alex", rendered)
        assertEquals("{Unmapped} stays literal", MessageTemplateEngine.render("{Unmapped} stays literal", emptyMap()))
    }

    @Test fun `message variables use no-reply tag when the sender cannot be normalized`() {
        val message =
            IncomingMessage(
                source = "sms",
                senderAddress = "shortcode",
                senderDisplay = "Alex",
                body = "hi",
                receivedAtMs = 1_000L,
            )
        assertEquals("SCIF-NOREPLY", MessageVariables.forMessage(message, replyTarget = null)["Reply Tag"])
        assertEquals("SCIF:+15551234567", MessageVariables.forMessage(message, replyTarget = "+15551234567")["Reply Tag"])
    }

    @Test fun `schedule window handles a same-day window`() {
        val nineThirtyAm = calendarMillis(Calendar.WEDNESDAY, hour = 9, minute = 30)
        val sevenPm = calendarMillis(Calendar.WEDNESDAY, hour = 19, minute = 0)
        assertTrue(ScheduleWindow.isActiveNow(daysMask = ALL_DAYS, startMinute = 9 * 60, endMinute = 17 * 60, nowMs = nineThirtyAm))
        assertFalse(ScheduleWindow.isActiveNow(daysMask = ALL_DAYS, startMinute = 9 * 60, endMinute = 17 * 60, nowMs = sevenPm))
    }

    @Test fun `schedule window handles an overnight window spanning midnight`() {
        val elevenPmWednesday = calendarMillis(Calendar.WEDNESDAY, hour = 23, minute = 0)
        val twoAmThursday = calendarMillis(Calendar.THURSDAY, hour = 2, minute = 0)
        val noonThursday = calendarMillis(Calendar.THURSDAY, hour = 12, minute = 0)
        val wedOnlyMask = 1 shl (Calendar.WEDNESDAY - 1)
        assertTrue(ScheduleWindow.isActiveNow(wedOnlyMask, startMinute = 22 * 60, endMinute = 6 * 60, nowMs = elevenPmWednesday))
        assertTrue(ScheduleWindow.isActiveNow(wedOnlyMask, startMinute = 22 * 60, endMinute = 6 * 60, nowMs = twoAmThursday))
        assertFalse(ScheduleWindow.isActiveNow(wedOnlyMask, startMinute = 22 * 60, endMinute = 6 * 60, nowMs = noonThursday))
    }

    @Test fun `filter condition evaluator respects message type scope`() {
        val filter = ForwardingFilterEntity(name = "t", includeSms = true, includeMms = false)
        assertTrue(FilterConditionEvaluator.matchesMessageType(filter, "sms"))
        assertFalse(FilterConditionEvaluator.matchesMessageType(filter, "mms"))
    }

    @Test fun `filter condition evaluator allow list blocks unlisted senders`() {
        val filter =
            ForwardingFilterEntity(
                name = "t",
                conditionMode = FilterConditionMode.CONDITIONS,
                contactMode = ContactFilterMode.WHITELIST,
                contactNumbersJson = "[\"+15551234567\"]",
                alwaysAllowOtp = false,
            )
        assertTrue(FilterConditionEvaluator.matchesConditions(filter, "+15551234567", "hi", isOtp = false))
        assertFalse(FilterConditionEvaluator.matchesConditions(filter, "+15559998888", "hi", isOtp = false))
    }

    @Test fun `filter condition evaluator otp override bypasses an otherwise-blocking allow list`() {
        val filter =
            ForwardingFilterEntity(
                name = "t",
                conditionMode = FilterConditionMode.CONDITIONS,
                contactMode = ContactFilterMode.WHITELIST,
                contactNumbersJson = "[]",
                alwaysAllowOtp = true,
            )
        assertFalse(FilterConditionEvaluator.matchesConditions(filter, "+15559998888", "hi", isOtp = false))
        assertTrue(FilterConditionEvaluator.matchesConditions(filter, "+15559998888", "code 4821", isOtp = true))
    }

    @Test fun `filter condition evaluator keyword must-contain and must-not-contain`() {
        val mustContain =
            ForwardingFilterEntity(
                name = "t",
                conditionMode = FilterConditionMode.CONDITIONS,
                keywordMode = KeywordFilterMode.MUST_CONTAIN,
                keywordsJson = "[\"urgent\"]",
                alwaysAllowOtp = false,
            )
        assertTrue(FilterConditionEvaluator.matchesConditions(mustContain, null, "URGENT: call back", isOtp = false))
        assertFalse(FilterConditionEvaluator.matchesConditions(mustContain, null, "just saying hi", isOtp = false))

        val mustNotContain = mustContain.copy(keywordMode = KeywordFilterMode.MUST_NOT_CONTAIN)
        assertFalse(FilterConditionEvaluator.matchesConditions(mustNotContain, null, "URGENT: call back", isOtp = false))
        assertTrue(FilterConditionEvaluator.matchesConditions(mustNotContain, null, "just saying hi", isOtp = false))
    }

    private fun calendarMillis(
        dayOfWeek: Int,
        hour: Int,
        minute: Int,
    ): Long {
        // A fixed, known Wednesday (2026-09-09) as the anchor so day-of-week arithmetic in the
        // test is deterministic regardless of when the suite actually runs.
        val calendar = Calendar.getInstance()
        calendar.set(2026, Calendar.SEPTEMBER, 9, hour, minute, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val delta = dayOfWeek - Calendar.WEDNESDAY
        calendar.add(Calendar.DAY_OF_MONTH, delta)
        return calendar.timeInMillis
    }

    private companion object {
        const val ALL_DAYS = 127
    }
}
