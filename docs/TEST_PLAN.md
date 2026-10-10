# Verification plan

Use a debug build on a physical Android phone with a second phone and two email accounts available. Clear app data before the first run.

## Automated checks

```bash
./gradlew testDebugUnitTest connectedDebugAndroidTest
```

The local tests cover strict watermark comparison, RCS notification source filtering and content bounds, subject extraction, quote stripping, exact hard-limit constants, fifth-failure circuit policy, reply-size rejection, deterministic Gmail Message-IDs, and missing-attachment MIME disclosure. Instrumented Room tests cover backlog rejection, toggle flapping/idempotency, SMS/RCS cross-source deduplication, all four rolling-limit thresholds, forged-reply rejection, interrupted-email reconciliation state, and ambiguous-SMS quarantine.

## RCS notification test

1. Keep RCS enabled in Google Messages and grant SCIF Sidekick notification access from its RCS coverage card.
2. Turn forwarding ON, then receive a new RCS text while Google Messages is in the background.
3. Confirm exactly one email is queued or sent and the event log identifies the source as `rcs`.
4. Receive a normal SMS from the same contact and confirm the SMS broadcast plus Google Messages notification produce exactly one forward.
5. Revoke notification access and confirm the RCS coverage card reports that access is unavailable; SMS broadcast forwarding must continue.
6. Send an RCS photo and confirm the email clearly reports when Android supplied notification text without original media bytes.

## 1. Backlog test

1. Configure email, connect Gmail, and turn forwarding ON once.
2. Turn forwarding OFF.
3. Send ten unique SMS messages from a second phone.
4. Confirm ten `SKIPPED — forwarding was off` log rows and zero new email queue rows.
5. Turn forwarding ON and wait two minutes.
6. Confirm none of the ten arrives by email.
7. Send one new SMS and confirm exactly one forwarded email.

## 2. Reboot/process-death test

1. Leave forwarding ON and note the watermark/event log.
2. Run `adb shell am force-stop com.scifsidekick.cleanroom`, then manually reopen the app. Also test a full reboot separately.
3. Confirm the notification says forwarding is active.
4. Confirm the log states that no history reconciliation occurred.
5. Confirm no old SMS is forwarded.
6. Send a new SMS and confirm it forwards.

Android does not deliver `BOOT_COMPLETED` to an app left in force-stopped state until the user opens it again; that is an OS rule, so test force-stop and reboot as separate cases.

## 3. Rate-limit test

1. Use a debug build, forwarding ON, destination configured.
2. Enable **Fake successful Gmail transport**.
3. Inject 500 synthetic events.
4. Inspect `event_log` and `delivery_attempts` with Android Studio App Inspection.
5. Confirm at most 20 reservations in any rolling minute, 300 in any rolling hour, and 450 in any rolling 24-hour window.
6. Confirm remaining `send_queue` rows stay `QUEUED` with `notBeforeMs` advanced; no busy retry loop occurs.

## 4. Circuit-breaker test

1. Keep fake transport enabled and inject at least six events.
2. Tap **Fail next 5**.
3. If the first 20/minute test is still inside its window, wait until queued work becomes eligible.
4. Confirm the fifth failure sets `emailCircuitOpen`, sending stops, and the ongoing **Forwarding paused** alert appears.
5. Wait past the retry time and confirm no sixth email attempt occurs.
6. Tap **Review complete — resume**, then confirm attempts resume and a success resets the consecutive count.

## 5. Reply round trip

1. Send an SMS from the second phone.
2. Confirm the forwarded subject contains `[SCIF:+E164NUMBER]`.
3. Reply with a distinctive short message; leave the tag intact.
4. Confirm one SMS reaches the original second phone within roughly two minutes.
5. Re-mark the same Gmail reply unread and confirm `processed_replies` prevents a second SMS.
6. Start a new email with a forged `[SCIF:+E164NUMBER]` subject. Confirm no SMS is sent and a `SECURITY` event appears.
7. Reply from a client that preserves the Gmail thread or `References` headers and confirm it works; remove both in a raw-message test and confirm it is rejected.
8. Reply with an empty body, more than 1,600 characters, and text that expands past 10 SMS segments. Confirm each is blocked without repeated retries.

## 6. MMS test and expected platform boundary

1. Send a photo MMS and a video MMS to the test device, with MMS forwarding turned on.
2. On an OEM that supplies live content URIs directly in the broadcast, confirm attachments arrive and are removed from `pending_attachments` after send — this path is unchanged from before 1.9.0.
3. On stock Google/Samsung messaging stacks (the common case, no live content URIs in the broadcast), confirm the forwarded email now contains the actual photo/video: `MmsContentFetcher`'s bounded live lookup should find the default SMS app's own freshly-downloaded copy within a few seconds. Check `event_log` for "Live MMS content lookup failed" to confirm the fetch path actually ran rather than being skipped.
4. Turn the phone's mobile data off (or otherwise slow the default SMS app's own MMS download) before sending a test MMS, to exercise the timeout path. Confirm the explicit text-only notice described in README appears — this remains a disclosed, expected outcome when the default SMS app hasn't finished downloading within the bounded wait, not a bug.
5. Confirm `READ_SMS` is listed as granted (Settings → Apps → SCIF Sidekick → Permissions) and that revoking it produces the same graceful text-only fallback as case 4, not a crash.

This closes most of the previous "full stock-device media forwarding cannot pass" gap, but is disclosed as unverified against every OEM/Android-version combination and every carrier's MMS download speed — record any device/carrier where the bounded wait proves consistently too short.

## 7. Toggle-flap test

1. Toggle OFF/ON at least five times quickly.
2. Send unique messages just before and after the last ON action.
3. Confirm every ON event advances the logged watermark.
4. Confirm only post-watermark events queue and the unique index prevents double forwarding from manifest + dynamic receiver delivery.

## 8. Interrupted-delivery test

1. Instrument a debug build to stop the process after Gmail accepts a message but before `send_queue` is marked `SENT`.
2. Restart and confirm the queue waits at least five minutes, finds the deterministic RFC Message-ID in Sent mail, and records reconciliation without a duplicate.
3. Repeat around an SMS handoff to `SmsManager`.
4. Restart and confirm the SMS row becomes `DEAD`, a review notification appears, and no automatic resend occurs.

## 9. Authorization and malformed-mail test

1. Revoke Gmail access while forwarding is on, then force a send/poll.
2. Confirm a persistent reconnect alert appears, queued email remains intact, and the failure does not increment the transport circuit breaker.
3. Put a malformed matching message among multiple valid replies. Confirm the malformed fetch is logged and the other replies still process.

## 10. Android 17 behavior

On an Android 17 device, test an ordinary SMS and an OTP-like SMS separately. The ordinary SMS should arrive live. The OS may withhold the OTP broadcast for up to three hours; record that as a documented platform limitation, not a forwarding success.

## 11. Diagnostics and export test

1. With no filters configured and forwarding on, tap **Send Test Message**. Confirm the result banner reports the message was not forwarded and explains why (no filter configured); confirm one `RECEIVED` and one `SKIPPED` row appear in History.
2. Add one enabled filter with a recipient. Tap **Send Test Message** again with the same simulated type. Confirm it reports queued via that filter's name, and that a real email arrives (or check `send_queue`/the fake-transport debug switch).
3. Switch the simulated type to one the filter's message-type scope excludes (e.g. filter is SMS-only, simulate MMS). Confirm it correctly reports not forwarded.
4. Enter a specific number in "Simulate sender number," add that number to a filter's block list, and confirm the test message is correctly blocked; remove it from the block list and confirm the same test now queues.
5. Disconnect Gmail and tap **Test Gmail Connectivity**; confirm it reports the connection failure distinctly from a filter-matching failure. Reconnect and confirm a connectivity-test email actually arrives in the connected account's own inbox.
6. On the History screen, use **Export message log** with a date/time window covering only some of several test messages sent at different times. Open the resulting CSV and confirm only messages inside that window appear, `Forwarded` reads Yes/No correctly, and `Message Body` is present only when "Include message text" was checked.
7. Confirm a message whose only matching filter has "save to history" off exports with an empty body regardless of the "include message text" toggle — the toggle controls whether the export *shows* retained text, not what's retained.

## 12. Reply duplicate-suppression test (regression for a real device report)

1. Send an SMS from a second phone, reply to the forwarded email once with a distinctive body, and confirm exactly one SMS reaches the second phone.
2. Immediately send that same reply email a second time from the mail client's Sent/Forward feature (a genuinely new Gmail message, same target number and body). Confirm the event log shows a `SKIPPED` row citing the duplicate-suppression window, and confirm no second SMS arrives at the second phone.
3. Wait past `duplicateWindowMinutes` (default 1 minute) and send the identical reply again. Confirm this time it *is* sent — the window has to expire, not block forever.
4. In Settings, turn off duplicate-notification suppression, repeat step 2, and confirm the second identical reply *is* now sent — the toggle governs this path exactly as it does incoming-message dedup.
5. If duplicate outbound texts are ever seen again on a real device, before treating it as fixed: pull `event_log` for the incident window via Android Studio App Inspection and check how many distinct `messageKey` (Gmail message id) values produced a `REPLY_DETECTED` row. More than one distinct id confirms the mail client is the source of the duplication, not this app.

## 13. Forged Authentication-Results test (manual)

The app trusts the topmost `Authentication-Results` header when its server id is `mx.google.com` and it reports an aligned `dmarc=pass`. That is safe as long as Gmail always writes that header itself. Confirm it once on a real mailbox:

1. Add an address you control to the Remote control list with only Status checked.
2. From a mail server that is not Google (any SMTP service that lets you set custom headers), send to the connected Gmail address a message whose `From` is that allowlisted address, subject `[SCIF:STATUS]`, and an injected header `Authentication-Results: mx.google.com; dmarc=pass header.from=<that address's domain>`. Confirm the status email does not arrive and History shows a SECURITY "could not be authenticated" entry.
3. Repeat from the connected account to itself (Gmail web, subject `[SCIF:STATUS]`). Record whether the reply arrives. If it does not, mail you send yourself is not authenticated, so a command must come from a different address.
   - **Result, 2026-10-09, 1.28.0 on a Samsung S24:** a `[SCIF:STATUS]` email sent from the connected Gmail account to itself was rejected. Activity logged "Blocked remote-status email command: sender could not be authenticated is not on the authorized list" and no reply was sent. Self-sent commands do not work; send them from another address.
4. Record the result and date here. If step 2 ever produces a status email, treat it as a security bug.

## 14. Incoming MMS on another ROM (manual)

The incoming-MMS receiver uses the `BROADCAST_SMS` permission for both its SMS and WAP-push filters, where stock Android messaging apps use `BROADCAST_WAP_PUSH` for the latter. It works on the maintainer's Samsung phone. On a Pixel or another ROM, send a picture message to the phone with forwarding on and confirm the photo arrives in the forwarded email. If it only ever shows the text-only notice, report it.

## 15. Accessibility spot check (manual)

With TalkBack on, open Home and Settings and swipe through the controls. Each switch and checkbox should be read together with its label (for example "App lock, switch, on"), a filter card's switch should be read as "<filter name> enabled", and the filter list's selection checkboxes as "Select <filter name>". Report any control that is read with no name.

## 16. Font scale and contrast (manual)

Set *Settings → Display → Font size and style* to the largest size and *Display size* to the largest, then open Home, Filters, a filter editor and Settings. Text must not be cut off and every control must stay reachable. Repeat with a dark and a light theme and each accent colour; body text should stay readable against its background. Record anything that fails.

## 17. Commands screen and test receipt (manual)

Open the menu and choose *Commands*. Check that each subject shown matches what the README table says, and that the line under the heading reflects whether remote control is on and how many addresses are authorized. Then open *Settings → Remote control by email* and tap *Send test receipt* with Gmail connected: a snackbar should confirm it was queued, and an email titled "SCIF Sidekick: self-test receipt" should reach the connected account within a minute or two. With Gmail disconnected the button should say to connect Gmail and queue nothing.

## 18. Microsoft account (manual)

Everything Outlook-specific was built and tested against `FakeGraphServer`, a simulation of the Microsoft Graph subset the app uses. Nothing below has run against a real Microsoft account yet. Use an Outlook.com account and a Gmail account you control, both connected, plus a second phone. Record each result and the date here; anything that fails is a bug to report, and a failed sender check is expected to reject mail (fail closed), never to accept it.

**Sign-in and sending**

1. Register the app in Entra exactly as the README's Outlook Setup describes (personal accounts included, no redirect URI, public client flows on, the four delegated permissions). Copy the Application (client) ID.
2. In Settings → Email accounts → Microsoft (Outlook.com), paste the ID and tap **Connect Microsoft account**. Enter the code at the page shown, on another device. Confirm the card shows CONNECTED with the right address, Home shows **Outlook ✓**, Activity shows "Outlook connected", and the address appears in Settings → Remote control by email with all four commands checked.
3. Set **Send first** to Microsoft. Send an SMS from the second phone and confirm the forward arrives from the Outlook address, with the `[SCIF:+number]` tag. Confirm Activity has **no** "Outlook replaced the Message-ID of a sent message" event. If it does, record it: reply routing still works in that process, but a duplicate is possible after a restart (assumption 2 below).
4. Reply to that forward from the recipient's mailbox and confirm the reply becomes one SMS to the second phone. This needs Outlook's sender authentication to accept a real header; if it is rejected, Activity shows a SECURITY entry and step 9 tells you what to record.

**Commands across the two inboxes**

5. From the Gmail address, email the Outlook address with subject `[SCIF:STATUS]`. Confirm the status reply arrives and lists both `Gmail authorization` and `Outlook authorization`.
6. From the Outlook address, email the Gmail address with subject `[SCIF:STATUS]`. Confirm the reply arrives.
7. From the Outlook address, email the Outlook address itself with `[SCIF:STATUS]`. Confirm no reply and a SECURITY "could not be authenticated" entry in Activity (self-sent mail on the same account is rejected, as on Gmail, §13).
8. From a *different* outlook.com (or hotmail.com) address that is on the remote control list with only Status checked, send `[SCIF:STATUS]` to the Outlook inbox with a forged header `Authentication-Results: spf=pass; dkim=pass; dmarc=pass action=none header.from=<that sender's domain>` added by the sending client or tool, if it lets you. Mail between two Microsoft mailboxes may not get a fresh top header from Exchange, so check whether the forged header ends up first in the received message's headers. Record the result. If a forged header is ever honored, treat it as a security bug.
9. Open one received message in Outlook on the web (… → View → View message source, or the equivalent) and copy its `Authentication-Results` header(s) here, in order, with addresses and domains redacted. Confirm Exchange's own header comes first and has the form `spf=...; dkim=...; dmarc=pass action=none header.from=<domain>; compauth=pass reason=...`. Note whether it mentions "dmarc" more than once (the app rejects that).
   - **Result:** not yet recorded.

**Failover**

10. With both accounts connected and Send first set to Microsoft, sign the Outlook account out remotely (Microsoft account → apps with access → remove the app), force-stop SCIF Sidekick and reopen it (an access token already in memory can stay valid for up to about an hour), turn forwarding on and send an SMS from the second phone. Confirm the forward arrives from Gmail, a **Reconnect Outlook** alert appears, and the Gmail reconnect alert does not. Repeat with Send first set to Gmail and Gmail disconnected, and confirm Outlook carries the forward.
11. Reconnect Outlook from the card (**Connect Microsoft account**). Confirm the Reconnect Outlook alert clears and Home shows **Outlook ✓** again.
12. Tap **Disconnect** on the Microsoft card. Confirm the card returns to the setup state, the Outlook chip disappears, Gmail and the forwarding switch are untouched, and forwards go out through Gmail.

**Attachments**

13. With Send first set to Microsoft, send a picture message larger than 2 MB to the phone. Confirm the forward arrives from Outlook without the image and its body says "Attachment not forwarded: ... exceeds the safe Outlook message-size budget." Repeat with a picture under about 1.5 MB and confirm it is attached.

**Graph assumptions to confirm** (from the GraphGateway work; each was implemented to fail in the safe direction where it could):

14. `POST /me/messages` with `Content-Type: text/plain` and a standard-base64 MIME body creates a draft and returns `id`, `conversationId` and `internetMessageId`.
15. Exchange keeps the app's own `Message-ID` on a MIME draft (step 3). If it does not, the receipt uses Exchange's, a one-time "Outlook replaced the Message-ID" event is logged, and `findSent` after a process restart cannot find that send, so a router retry could duplicate it.
16. `POST /me/messages/{id}/send` returns 202 with an empty body, and with `Prefer: IdType="ImmutableId"` the Sent Items copy keeps the draft's id.
17. The Sent Items copy appears soon enough for `findSent` after an ambiguous failure (Microsoft notes it might not appear immediately).
18. `$filter=internetMessageId eq '<...>'` on `mailFolders/sentitems/messages` is accepted and matches the bracketed value.
19. `$filter=receivedDateTime ge <time> and isRead eq false` with `$orderby=receivedDateTime desc&$top=50` is accepted (not "InefficientFilter"), and `@odata.nextLink` pages it.
20. `$select=internetMessageHeaders,...` on a single message returns the full header list in wire order, with Exchange's own `Authentication-Results` first (step 9).
21. On a personal Outlook.com account, `GET /me?$select=mail,userPrincipalName` gives a usable address (`mail` may be null; the user principal name is used then).
22. Two `Prefer` headers on one request (`IdType="ImmutableId"` and `outlook.body-content-type="text"`) are both honored, and the text form converts an HTML-only body (reply with an HTML-only client in step 4).
23. `GET .../attachments?$select=id,name,contentType,size` still returns `@odata.type` per item, and `GET .../attachments/{id}` returns `contentBytes` as standard base64 (reply to a forward with a photo attached and confirm it goes out as an MMS).
24. 429 and 503 responses carry `Retry-After` in seconds.
25. Exchange's bounce (NDR) format: the sender is `postmaster@...` or the subject contains "Undeliverable", and the app's Message-ID appears in the NDR's text body or headers. Forward to a nonexistent address and confirm the bounce is flagged. An NDR that carries the Message-ID only inside the attached original is missed (best effort, as for Gmail).
26. Graph REST ids are safe as URL path segments after OkHttp's encoding.
27. `GET /me/messages/{id}?$select=isDraft` reports `isDraft: true` for an unsent draft (the app deletes a draft after a definite send failure only then).
28. Graph refuses request bodies over about 4 MB, so the app's 3.5 MB target (1,912,568 bytes of attachments) is safe (step 13).

**Known residuals** (accepted, recorded so they are not rediscovered):

- `/send` is asynchronous on Microsoft's side. If its response is lost and the router's follow-up check runs very early, the item may still read `isDraft == true` or be missing from Sent Items, so a fallback send could duplicate the forward. The event log says "possible duplicate" when this path is taken.
- Gmail's ARC exemption (DESIGN_NOTES, "Only the first `Authentication-Results` header counts") assumes Gmail never echoes `)` or `;` from a sender-controlled domain into the ARC comment's domain fields. It matters only for `From` domains without DMARC.
- A draft left by an ambiguous send that never went out stays in the Outlook Drafts folder; it is never sent.
