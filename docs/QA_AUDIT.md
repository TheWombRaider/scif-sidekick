# QA audit — release 1.1.0

> **Historical document.** This audit covers release 1.1.0. Findings listed as fixed were fixed in that release or shortly after; it has not been updated for later releases. For current behavior see [README.md](../README.md) and [ARCHITECTURE.md](ARCHITECTURE.md).

Audit date: 2026-09-05  
Scope: complete supplied source archive, Android configuration, permissions, live SMS/MMS receive path, Gmail OAuth/API path, email-to-SMS replies, Room persistence/migration, background execution, privacy controls, tests, build inputs, and documentation.

## Release assessment

The supplied 1.0.0 archive was internally consistent and had strong no-history/watermark and rate-limit foundations, but it was not release-ready. The most serious defect allowed any unread email with a forged `[SCIF:+number]` subject to request an SMS. Crash windows could also duplicate Gmail or SMS delivery, authorization revocation was handled as a transport failure, permanent SMS errors retried indefinitely, and completed private message payloads had no retention limit.

Release 1.1.0 corrects the actionable defects without weakening the explicit rule that the application must never query SMS/MMS history. The stock-Android MMS-media limitation and Android 17 OTP delay remain external constraints and are disclosed below.

**Verdict:** source-level QA pass; binary production release remains blocked until Gradle compilation/tests, signed OAuth validation, and the physical-device matrix pass.

## Findings and disposition

| Severity | Finding | Disposition in 1.1.0 |
|---|---|---|
| Critical | A forged subject tag could trigger an SMS to an arbitrary E.164 number. | Fixed: requires a sent-route match by Gmail thread or RFC reply reference plus the same target number. |
| Critical | Crash after Gmail accepted mail but before local commit could duplicate a forward. | Fixed: deterministic Message-ID plus Sent-mail reconciliation before retry. |
| High | Interrupted SMS was blindly retried even though prior modem acceptance was unknowable. | Fixed: ambiguous SMS is quarantined/dead-lettered and surfaced for review. |
| High | Disconnect changed only a local flag; delivery could reacquire a token. | Fixed: token access is gated, the Google grant is revoked when account identity is available, cached token is cleared, forwarding is switched off, and queued work is retained. |
| High | HTTP 401 could repeatedly fail and trip the transport circuit. | Fixed: rejected token is cleared and the queue pauses behind an explicit reconnect alert without incrementing the circuit. |
| High | Blank/huge email replies could repeat forever or fan out into excessive SMS segments. | Fixed: processed safely; 1,600-character and 10-segment hard limits; permanent failures are dead-lettered. |
| High | A malformed Gmail message could abort the entire reply poll. | Fixed: failures are isolated and logged per message. |
| Medium | HTML-only replies and binary MIME fallbacks were unsafe/incomplete. | Fixed: prefer `text/plain`, convert `text/html`, never decode arbitrary binary parts as reply text. |
| Medium | Missing queued attachments were silently omitted. | Fixed: the email body contains an explicit warning and count. |
| High | Live OEM attachment streams were copied without a byte/count bound and partial files could survive a failed copy. | Fixed: bounded 18 MiB/10-part streaming, aggregate enforcement, cleanup, and explicit partial-forwarding notice. |
| Medium | The MIME message omitted `From` and lacked a stable RFC Message-ID. | Fixed using the Gmail profile address and deterministic ID. |
| Medium | Dynamic receiver, boot, and battery-settings failures could crash or remain invisible. | Fixed with safe fallback/logging and user feedback. |
| Medium | `READ_SMS` was requested but unused. | Removed under least privilege; static guard still prohibits history access. |
| Medium | Completed private payloads and diagnostic tables grew indefinitely. | Fixed: 30-day completed-payload retention, 90-day routes/replies/logs, 24-hour orphan-file cleanup. |
| Medium | UI content could appear in screenshots and Recents previews. | Fixed with `FLAG_SECURE`; backups remain disabled and cleartext traffic is prohibited. |
| Medium | Forwarding could be enabled without core SMS permissions. | Fixed: enable is refused until receive/send permissions are granted. |
| Medium | Target/compile SDK lagged the current Android platform. | Updated to API 37 and version 1.1.0. |
| Open/platform | Standard live WAP broadcasts do not expose downloaded MMS media/group data to a normal non-default SMS app. | Not falsely “fixed”; keeps no-provider-query invariant and sends an explicit limitation notice. |
| Open/platform | Android 17 can delay OTP SMS broadcasts for most non-exempt apps. | Documented in-app and in the test plan; cannot be overridden by the app. |
| Release process | Google Play restricts SMS permissions. | Permissions Declaration/review required; confirm distribution eligibility before production release. |

## Verification evidence

- Original ZIP central directory and every compressed entry passed integrity testing.
- Full source inventory and call paths were manually reviewed; no Telephony inbox/provider or `ContentObserver` path was found.
- `scripts/verify_no_history_queries.sh` is the repeatable static invariant check.
- Kotlin/Kotlin DSL formatting and static style checks pass with ktlint 1.7.1.
- Unit and instrumented test coverage was expanded for reply authorization, crash recovery, MIME disclosure, size limits, and idempotency.
- Android resources compile with Android Asset Packaging Tool 37.0.0; all source XML is well-formed.
- Full Gradle verification was attempted, but this audit environment could not resolve build dependencies from Google's and Maven Central's repositories. `testDebugUnitTest`, `lintDebug`, and `assembleDebug` therefore remain an explicit external release gate rather than a claimed pass.

## Release gates

Do not publish solely from a source review. Before signing production:

1. Run the Gradle and device matrix above with the release signing/OAuth SHA-1 configuration.
2. Perform the interrupted-delivery fault-injection cases in `TEST_PLAN.md`.
3. Verify Gmail consent/revocation with the intended Workspace or consumer account policy.
4. Complete Google Play's SMS permission declaration or document the approved internal/sideload distribution route.
5. Product must explicitly accept text-only MMS fallback under the absolute no-history constraint, or approve a requirements change.
6. Add an application privacy policy and source-code license appropriate to the intended distribution; neither was supplied in the archive.

## Addendum — 2026-09-07, release 1.9.0

Product reviewed release-gate #5 and explicitly approved the narrower requirements change this
audit's release table left open: a single, bounded, event-triggered read of the one MMS row a live
`WAP_PUSH_RECEIVED` broadcast just announced (never a range, never a persisted bookmark, never a
`ContentObserver`, never a startup/toggle catch-up query). This is implemented in
`MmsContentFetcher` and enforced as the sole permitted call site by
`scripts/verify_no_history_queries.sh`. This does not reopen the "Open/platform" finding's
disposition for OEM builds that never populate `content://mms` within the bounded wait, or for a
device where the app is not the default SMS handler and the OS declines to grant `READ_SMS` —
those still fall back to the same disclosed text-only notice as before. Re-verify this addition
against `TEST_PLAN.md`'s interrupted-delivery cases and the physical-device matrix before
production release; it has not been exercised against a live carrier/MMSC.

Confirmed working on a physical device the same night: a real MMS photo was forwarded with the
actual image attached, not a text-only notice.

| Severity | Finding | Disposition |
|---|---|---|
| High | A single user reply was observed producing four outbound SMS sends on a physical device. `processed_replies` correctly dedupes by Gmail message id, but cannot catch two genuinely distinct Gmail messages carrying the same target number and body -- consistent with a mail client silently resubmitting one reply as several separate sent messages, though this was not confirmed against on-device `event_log` data (not accessible from this environment). | Mitigated: `send_queue.replyDedupeKey` (migration 8→9) suppresses a second SMS/MMS reply with identical target+body within the same duplicate-suppression window already used for incoming messages. This addresses the user-visible symptom regardless of root cause; it does not by itself explain why multiple distinct Gmail messages existed. **Open**: pull `event_log`/`processed_replies` from the affected device (Android Studio App Inspection or `adb`) next time this reproduces, filtered to the incident window, to confirm whether distinct `gmailMessageId` values were actually involved and, if so, trace it to the Gmail app/account rather than this codebase. |

## Residual risk

Room data is app-private and protected by Android/device encryption, but it is not protected by a separate application-level database key. A compromised, rooted, or unlocked device can expose locally queued communications. The destination mailbox becomes a second copy of each forwarded message and must be secured independently.

## Primary references checked

- [Android 17 SDK setup](https://developer.android.com/about/versions/17/setup-sdk) — API 37 compile/target configuration.
- [Android 17 behavior changes](https://developer.android.com/about/versions/17/behavior-changes-17) — OTP broadcast-delay behavior.
- [Foreground-service background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) — boot and background execution constraints.
- [Google Identity Services authorization](https://developer.android.com/identity/authorization) and [AuthorizationClient reference](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationClient) — resolution and rejected-token handling.
- [Gmail `messages.list`](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages/list) and [Gmail `messages.send`](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages/send) — query limits, RFC Message-ID search, and send behavior.
- [Google Play SMS and Call Log permission policy](https://support.google.com/googleplay/android-developer/answer/10208820) — restricted permission review and exception categories.
- [Room release notes](https://developer.android.com/jetpack/androidx/releases/room) — database dependency status.
