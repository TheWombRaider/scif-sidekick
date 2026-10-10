# SCIF Sidekick — design notes

> The long-form reasoning behind the app: safety invariants, the MMS boundary, and per-feature rationale. The short version lives in the [README](../README.md).

SCIF Sidekick is a clean-room Android/Kotlin rebuild that relays live SMS events to Gmail and turns tagged email replies into outbound SMS messages. Its package and database names are intentionally new:

- Application ID: `com.scifsidekick.cleanroom`
- Room database: `scif-sidekick-cleanroom-v1.db`

Installing it cannot reuse state from an earlier SCIF Sidekick build.

This build is release **1.29.0**. Release history, including the reasoning behind each change, lives in [CHANGELOG.md](../CHANGELOG.md).

See [Filters](#filters) and [Remote control by email](#remote-control-by-email) below. See [docs/QA_AUDIT.md](QA_AUDIT.md) for the 1.1.0 audit and [docs/TEST_PLAN.md](TEST_PLAN.md) for device checks.

## Safety invariants

- The app has exactly one narrowly-scoped exception to "never query SMS/MMS history" (see "Important Android MMS boundary" below); every other code path consumes only `SMS_RECEIVED` and `WAP_PUSH_RECEIVED` broadcasts, with no `ContentObserver`, no persisted last-seen id, and no catch-up query anywhere.
- Every OFF-to-ON action writes `System.currentTimeMillis()` as a new watermark in the same Room transaction as the enabled state.
- OFF events are logged and discarded; they never enter `send_queue`.
- A second strict `receivedAt > watermark` check prevents replay even if an old event is accidentally passed to the processor.
- Restart and reboot handling releases only interrupted app-owned email claims. Gmail retries first reconcile a deterministic RFC Message-ID against Sent mail. An interrupted SMS send is quarantined instead of automatically retried because Android cannot prove whether the modem accepted it before process death.
- Every Gmail API call is reserved in `delivery_attempts` before network access and is subject to all three rolling caps: 20/minute, 300/hour, and 450/24 hours.
- Email and SMS queues persist in Room. SMS replies have their own cap: 10/minute, 100/hour, 1,000/day. Ordinary email forwards stop at 20/290/420 so system mail (receipts, status, heartbeat) keeps a reserve inside the totals above.
- Outbound texts to well-known premium-rate and satellite number ranges are refused (`PremiumNumbers`), at reply time and again at send time.
- Five consecutive Gmail send failures open a persistent circuit breaker. Only the in-app manual reset closes it.
- A tagged email can route to SMS only when its Gmail thread or `References`/`In-Reply-To` Message-ID matches a forward previously sent by this installation. A forged `[SCIF:+number]` subject is logged, marked processed, and never sent.
- Blank replies, replies over 1,600 characters, or replies that expand beyond 10 SMS segments are blocked. Permanent payload errors are dead-lettered rather than retried forever.
- Every receive, skip, enqueue, rate-limit decision, attempt, failure, success, reply, restart, and circuit event is written to `event_log` and shown in the UI.

## Important Android MMS boundary

The specification originally combined two requirements that stock Android cannot satisfy simultaneously for a normal, non-default SMS app:

1. Forward decoded incoming MMS media and group participants.
2. Never read the MMS provider and rely only on the live WAP broadcast.

`WAP_PUSH_RECEIVED` normally contains only an MMS carrier notification (`M-Notification.ind` with a content-location), not the downloaded photo/video. The decoded parts are later written by the default SMS app to the Telephony MMS provider. A third-party receiver cannot obtain those media bytes from the notification alone.

**As of 1.9.0, Product explicitly approved the narrower of the two options this doc previously left open** (see docs/QA_AUDIT.md release-gate #5): a single, event-triggered, bounded read of the one MMS row the live broadcast just announced — never a range, never a persisted "last seen" id, never a startup/toggle catch-up query, and never a `ContentObserver`. `MmsContentFetcher` (`app/src/main/java/.../messaging/MmsContentFetcher.kt`) is the sole call site; `scripts/verify_no_history_queries.sh` fails the build if any other file references the SMS/MMS content provider, or if a `ContentObserver` appears anywhere.

In practice: `IncomingMessageReceiver` finishes the live broadcast's own pending result immediately (so a slow wait can never risk an ANR on the broadcast itself), then, kept alive by the already-running foreground service, waits up to ~28 seconds for the default SMS app to finish downloading and inserting the just-announced message before reading that message's own text/media parts. If an OEM already includes live attachment content URIs directly in the broadcast, those are used instead and the provider read is skipped entirely — that path is unchanged from before. If the bounded wait times out (very slow network, or a stock build that never populates the provider at all), the app falls back to the same honest text-only notice as before: it never silently claims an attachment was sent that wasn't.

(1.9.0 through 1.13.0 bounded this wait to only a few seconds, tied to how long it seemed safe to hold a `BroadcastReceiver`'s `goAsync()` open. In real-world use — including on weaker connections, like inside a building with poor signal — carrier MMS downloads routinely took longer than that and lost the race, forwarding only a text-only notice ("image" or similar) instead of the photo even though MMS forwarding was enabled. The wait itself was never the constraint that needed to be short; the `goAsync()` window was. Decoupling the two let the wait grow to match real download timing.)

This still requires the `READ_SMS` permission, which the previous release had deliberately removed under least privilege — it is back, scoped in comments and in `AndroidManifest.xml` to explain exactly why.

The remaining alternative — making SCIF Sidekick the default SMS app and implementing the full carrier MMS download/provider stack — stays out of scope; it's a much larger UX and permissions change than this narrow fix.

## RCS notification coverage

Keep RCS enabled. Google Messages RCS/chat messages do not generate the standard SMS/MMS broadcasts, so SCIF Sidekick 1.2.1 can consume newly posted Google Messages and Samsung Messages conversation notifications after the user grants Android **Notification access**. It filters all other applications before reading notification content and never queries message history. SMS notifications are compared with Sidekick's own recent live-event records to suppress duplicate forwarding when the matching SMS broadcast has already arrived.

Notification delivery is a best-effort Android integration rather than an RCS provider API. Muted, blocked, hidden, or disabled messaging notifications may be unavailable. Notification text can describe an RCS photo, video, or attachment without exposing the original media bytes. Gmail replies continue to use SMS when a unique phone number can be obtained from the notification's sender metadata or contacts; otherwise the email is marked no-reply.

On Android 17, the operating system can withhold SMS broadcasts containing one-time passcodes from most non-exempt apps for up to three hours. SCIF Sidekick cannot override this privacy behavior and shows it in the first-run warning. Do not rely on this app for time-critical OTP forwarding.

## Filters

As of 1.4.0, forwarding is no longer one global destination/MMS-toggle/contact-filter — it is any number of independent **filters**, managed from the hamburger menu. An incoming message is recorded and deduplicated exactly once; it is then checked against every *enabled* filter, and each one whose message-type scope, schedule, and conditions match queues its own email. None of this weakens the safety invariants above — a filter is an additional gate evaluated inside the same `processIncoming` transaction, alongside (not instead of) the master enabled/watermark checks, which still apply globally regardless of how many filters exist.

Each filter has:

- **Message types** — any combination of SMS, MMS, RCS, and missed calls.
- **Recipients** — one or more email addresses.
- **Forwarding conditions** — either "forward all" or "forward by conditions": a sender allow list or block list (E.164-normalized, the same normalization reply routing uses), and/or a keyword must-contain or must-not-contain check against the message text.
- **Always allow OTP & security codes** — on by default when conditions are used; a message that both mentions a recognized verification keyword and carries a short numeric code bypasses that filter's contact/keyword conditions specifically (never its message-type or schedule gates), so a legitimate allow list can't accidentally eat a bank code.
- **Message template** — an editable subject and body using `{Incoming Number}`, `{Contact Name}`, `{Message Body}`, `{Received Time}`, `{Source}`, `{Reply Tag}`, and `{Verb}` placeholders, substituted as plain text into plain text (never evaluated), plus optional find-and-replace rules (plain text or regex) applied to the rendered body. The `[{Reply Tag}]` portion of the subject is what makes a message reply-routable; removing it from a custom template makes that filter's forwards no-reply, safely, rather than insecurely.
- **Schedule** — optional active days/hours, including an overnight window (e.g. 22:00–06:00).
- Per-filter **save to history** and **notify on send** toggles.

A filter with no recipients, or a message with no enabled filter at all, is skipped and logged — nothing is silently dropped without an explanation in the event log.

Upgrading from 1.3.0 or earlier seeds exactly one filter from whatever the old global destination/MMS-toggle/call-toggle/contact-filter held, so an existing install keeps forwarding exactly what it forwarded before.

**Order and "stop processing further filters."** Filters are checked in a fixed order, shown top-to-bottom on the Filters screen — drag a filter's grip handle to reorder. Each filter also has a "stop processing further filters" switch: when a filter with it turned on matches and forwards a message, no filter below it (in that same order) is evaluated for that message, mirroring "stop processing more rules" in an email client's filter chain. Off by default, so an existing multi-filter setup keeps fanning out to every match unless this is turned on deliberately.

**Forwarding to your own inbox.** If a filter's recipient is the same Gmail account connected for delivery, the app's own outgoing notification lands back in that inbox as a new unread message — one that already carries a genuine `[SCIF:+number]` tag. The reply poller checks whether a candidate message was one this installation itself sent before considering it as a reply or compose-new request, specifically so that self-forwarding can never make the app text a sender back with their own message.

## Prerequisites

- Android Studio with JDK 17
- Android SDK 37 / Android Studio with Android 17 support
- A physical SMS-capable Android phone (minimum API 26)
- A Google Cloud project and the Gmail API, a Microsoft Entra app registration for an Outlook.com account, or both (see the README's Gmail Setup and Outlook Setup)

## Google Cloud / Gmail setup

Google removed support for custom URI-scheme OAuth redirects on Android. This build uses the current Google Identity Services `AuthorizationClient`; Google Play services stores its own short-lived token cache, and the app never stores a refresh or access token.

1. Create a Google Cloud project.
2. Enable **Gmail API**.
3. Configure OAuth branding/consent as `External`, add your Gmail address as a test user, then set the publishing status to **In production** (Audience page → *Publish app*). Do not leave it in `Testing`: Google expires Testing-mode grants for Gmail scopes after 7 days, and when that happens the app cannot email you about it, because sending email is the part that stopped working. A personal app in production does not need Google verification; you will see an "unverified app" warning on the consent screen, which is expected.
4. Add the scopes `https://www.googleapis.com/auth/gmail.send` and `https://www.googleapis.com/auth/gmail.modify` on the Data Access page.
5. Create an **Android OAuth client** with:
   - Package name: `com.scifsidekick.cleanroom`
   - SHA-1: the signing certificate used for your build. Obtain the debug value with `./gradlew signingReport`.
6. Build and install the app, then tap **Connect Gmail** and complete consent.

No client secret belongs in an Android app. No `google-services.json` or client-ID source constant is required for this authorization API; Google matches the package/signature to the Android OAuth client.

If the app still asks you to reconnect Gmail roughly every week, the consent screen is still in `Testing`; see step 3. A Google Workspace administrator can instead trust an internal app for organization-owned accounts. The app logs background reauthorization failures and keeps messages queued, and the opt-in heartbeat email going quiet is the remote signal that this has happened.

Gmail push (beta) additionally needs the `https://www.googleapis.com/auth/pubsub` scope. The app requests it only while push is switched on; see [Gmail push (beta)](#gmail-push-beta).

## Build

Open the root folder in Android Studio and let it install SDK 37, or run:

```bash
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is created at `app/build/outputs/apk/debug/app-debug.apk`.

## First run

1. Grant SMS, MMS, contacts (optional name and reply-number lookup), and notification permissions.
2. In the **RCS coverage** card, tap **Allow notification access** and enable SCIF Sidekick.
3. Keep RCS enabled in Google Messages.
4. Tap **Allow reliable background operation** and approve the battery-optimization exemption.
5. Connect Gmail.
6. Open **Filters** from the hamburger menu and add at least one filter with a recipient.
7. Turn forwarding ON. This moment becomes the new forwarding watermark for SMS, MMS, and messaging notifications.

On Samsung devices, also set **Settings → Apps → SCIF Sidekick → Battery → Unrestricted** and exclude the app from Sleeping/Deep sleeping apps.

## Reply routing

Forwarded subjects begin with an exact routing tag:

```text
[SCIF:+15551234567] New text from Jane Doe
```

The Gmail poller checks every 30 seconds while the foreground service is alive. It extracts one unambiguous E.164 routing tag, requires a recorded Gmail thread or RFC Message-ID match, and requires Gmail's topmost authentication result to show an aligned DMARC pass for an original recipient address. It then removes quoted history, atomically deduplicates the Gmail message ID, and queues one outbound SMS. Do not delete or alter the `[SCIF:+number]` tag or break the email thread when replying. A sender who was not an original recipient, or a client that removes both Gmail threading and `References`/`In-Reply-To` metadata, is rejected safely. Routes created before version 1.8.0 intentionally fail closed because their recipient identity was not recorded.

## Gmail push (beta)

Off by default. The 30-second poll above is a floor on reply latency that matters more here than in a normal email client — it's the actual delay before a reply you sent from email goes out as a text. Gmail push closes most of that gap by having the app itself hold a Cloud Pub/Sub subscription and pull it every ~10-15 seconds as a lightweight "did the mailbox change" signal; a hit just makes the *same, unmodified* reply poll above run on the next 10-second service tick instead of waiting out its own 30-second timer. Typical worst case drops to roughly 15-20 seconds. Nothing about reply detection, authorization, or routing changes — this only changes how soon the existing poll runs.

This is a single-user app, so the one-time Google Cloud Console setup below is just for your own project, not a rollout:

1. In the same Google Cloud project as the Gmail API, create a **Pub/Sub topic**.
2. Grant `gmail-api-push@system.gserviceaccount.com` the **Pub/Sub Publisher** role on that topic (Gmail publishes to it on your behalf; this is Google's own standard requirement for `users.watch()`, not something specific to this app).
3. Create a **pull subscription** on that topic.
4. Grant your own Google account — the same one used to connect Gmail above — the **Pub/Sub Subscriber** role on that subscription (Console → Pub/Sub → Subscriptions → the subscription → Permissions → Add principal → your email → `roles/pubsub.subscriber`).
5. In the app, open the hamburger menu → Settings → Behavior → **Gmail push (beta)**, enter the topic's and subscription's full resource names (`projects/PROJECT_ID/topics/TOPIC_NAME` and `projects/PROJECT_ID/subscriptions/SUBSCRIPTION_NAME`), then use **Test Push Setup** on the History screen before turning the switch on.

**Upgrading an already-connected install:** this release added the `pubsub` OAuth scope to the app's single combined Gmail grant (there is no separate, independent authorization for push — see the code comment on `GmailOAuthManager.SCOPES` if you're curious why). The next time the app silently refreshes its Gmail token, Google sees the requested scope set no longer matches what was previously granted and the app shows its ordinary "reconnect Gmail" prompt — the same one you'd see after any other consent expiry, not new UI. This happens once, ever, per install, whether or not you ever turn push on.

The watch this creates expires after 7 days (Gmail's own cap); a daily background job renews it automatically while push is enabled. If the Pub/Sub setup above is skipped, wrong, or ever breaks, the push pull simply keeps finding nothing and logs a failure at most once an hour — the 30-second poll is completely unaffected either way and remains the actual safety net.

## Remote control by email

Four commands share one card in Settings ("Remote control by email"): starting a brand-new text, turning forwarding on, turning it off, and asking what the app is doing. One master switch is a single kill switch for all four at once, **on by default** -- this app is built for personal, single-owner use, where a feature that has to be discovered and switched on separately per command adds friction without adding real safety. Below the switch is one list of authorized email addresses, each with its own independent checkbox per command: trusting an address for one command never implies trusting it for another. A newly added address starts with every checkbox on; the Gmail account you connect gets added automatically, with all four commands checked, the first time it's known (releases before 1.26.0 checked only **Status**; installs that already seeded keep that, and you can tick the rest in Settings).

**Compose a new text.** Replying to a forwarded email still requires that email's Gmail thread or Message-ID to match a route this app actually sent — that is unchanged. Starting a **new** text has no such route to check against, so it uses this allowlist instead. Use this as the complete subject (apart from surrounding whitespace):

```text
TEXT+15551234567
```

The new portion of the email body becomes the text message, and attaching a photo sends it as a picture message instead of plain text. `TEXT+number` must occupy the complete subject apart from surrounding whitespace; extra text is rejected so attacker-influenced forwarded subjects cannot accidentally become commands. The sender's address must be checked for **Compose** below and Gmail must report an aligned `dmarc=pass` for that domain — the displayable `From` header is never trusted by itself.

**Enable forwarding.** Turning forwarding back on normally happens inside the app -- but that's no help the one time it matters most: forwarding was left off, and the phone itself is locked away outside a SCIF, unreachable until the end of the day. Send an email whose subject contains:

```text
[SCIF:ON]
```

A separate background check -- independent of the app's own foreground service, which normally only polls Gmail while forwarding is already on -- looks for this roughly every 15 minutes and, if found and the sender is checked for **Enable**, turns the master switch back on and starts forwarding. 15 minutes of latency is a real trade-off (true push would need a server component this single-user app doesn't have) but is negligible next to walking out of a SCIF to go get a phone.

**Disable forwarding.** Send an email whose subject contains:

```text
[SCIF:OFF]
```

This is checked by `ForwardingService`'s own 30-second reply poll rather than by `RemoteEnableWorker`, which is not an arbitrary split. Each command is handled by whichever component is actually running in the state that command is about: forwarding being *off* means the service isn't running, so "on" needs the always-scheduled worker; forwarding being *on* means the service **is** running, polling this same inbox every 30 seconds, and already consuming any `[SCIF:` subject it doesn't recognize -- marking it read and recording it as an ignored candidate. A worker checking every 15 minutes for one still unread would almost never win that race. The practical effect is that "off" is the faster of the two: about 30 seconds, against up to 15 minutes for "on". Only a sender checked for **Disable** is honored.

A subject carrying *both* the `[SCIF:ON]` and `[SCIF:OFF]` tags is ambiguous about which was meant, so neither side acts on it -- the same treatment a reply subject naming two routing targets already gets.

**Enable and Disable are deliberately independent checkboxes, not one.** They fail in opposite ways: a missed "on" only means forwarding you wanted doesn't start, while a wrongly-accepted "off" means forwarding you were relying on stops silently, with the phone out of reach to notice. Checking an address for Enable never checks it for Disable. Restoring a backup honors that separation too: it restores exactly the capability bits each address had, never more.

Turning forwarding off this way writes no watermark -- only an off-to-on transition does -- so a later re-enable still starts from that moment and never backfills what arrived in between. Nothing here calls an explicit service stop: the forwarding loop notices the flag on its next tick and stops itself, the same single place every other "switch it off" path in the app relies on.

**Ask for status.** Send an email whose subject contains `[SCIF:STATUS]` and SCIF Sidekick replies with the same summary the heartbeat sends -- forwarding on or off, whether the service is alive, Gmail authorization, queue depth. Unlike Enable/Disable this works in either state: about 30 seconds while forwarding is on, up to 15 minutes while it's off, whichever side is actually running to see it. It changes nothing, but it does disclose operational state, so it has its own **Status** checkbox rather than riding on any of the other three. The reply never includes message text, phone numbers, or sender addresses.

**Receipts.** After an authorized `[SCIF:ON]` or `[SCIF:OFF]` is applied, SCIF Sidekick emails the sender "Forwarding ENABLED" or "DISABLED" plus the status summary read back from the database. An ON receipt also says if Android would not start the forwarding service. Rejected commands get no reply at all, so a stranger can't confirm the mailbox is live and a forged From can't turn the reply into backscatter. Receipts are queued like any email and sent by `ReceiptDrainWorker`, so they go out whether or not forwarding (and therefore the service) is running.

**What the off-state check looks at.** While forwarding is off, `RemoteEnableWorker` searches only mail from addresses on the allowlist, examines up to 20 unread tagged messages per run, and acts on the newest authorized `[SCIF:ON]`. Unauthorized, unauthenticated or ambiguous ones are marked read. A stranger's command is therefore never fetched or logged by that path (the 30-second service poll still logs such attempts while forwarding is on).

### Picture messages (MMS-out)

An image attached to an authorized trigger email goes out as an MMS instead of an SMS. The archived `android-smsmms` library is now used only to encode the MMS PDU; the app calls Android telephony directly with an immutable, per-attempt result callback. SMS waits for every segment result and MMS waits for the system result before the durable queue becomes `SENT`.

**This is disclosed as unverified against real carrier/MMSC infrastructure.** It has been built, compiled, and exercised with fake/local inputs, but actual MMS delivery depends on carrier-specific configuration this project has no way to test without a physical device on a live SIM. Images are downscaled to a conservative size budget before sending to reduce the odds of a carrier rejecting an oversized message, but that budget is an informed guess, not a number measured against any specific carrier.

## Outlook and mail failover

Release 1.29.0 adds Microsoft Graph (Outlook.com) as a second mail provider behind `MailTransport`, and a `MailRouter` that keeps the app working when one mailbox is down. The design is in `docs/superpowers/specs/2026-10-09-microsoft-graph-and-failover-design.md`; `docs/ARCHITECTURE.md` describes the pieces. The decisions that need their reasons written down:

**Failover prefers a rare duplicate to a lost forward.** A send that fails is not always a send that did not happen. The router sorts failures into three kinds. An authorization failure, or a *definite* failure (the provider answered with an error other than 5xx, or the connection never opened), means the message did not go out, so the next account sends it. An *ambiguous* failure (a timeout, a reset or a 5xx after the request may have reached the provider) might have delivered it, so the router first asks that account's Sent folder (`findSent`, by the deterministic Message-ID). Found: the send is recorded as done. Not found, or the check itself fails: the next account sends anyway and the event log says "possible duplicate". The alternative, waiting until the ambiguous account answers, is exactly the silent phone this app exists to prevent. A retry after a crash (`verifyPriorDelivery`) asks every account's Sent folder first, so a forward the fallback already carried is never repeated by the preferred account coming back. With one account there is nothing to fail over to, and the router hands the call to it unchanged.

**Only the first `Authentication-Results` header counts.** The receiving provider prepends its own verdict above anything already in the message; a header lower down may have been written by the sender. So each provider reads only the topmost header, never merges it with later ones, and requires exactly one dmarc clause whose result is exactly `pass` and whose own `header.from=` equals the `From` domain, with no subdomain relaxation. Comments and quoted strings are stripped before parsing, because the first draft of the parser accepted `reason="x; dmarc=pass header.from=..."` as if the quoted text were a clause. Stripping creates its own risk: a forged, unclosed `(` could swallow the provider's real `dmarc=fail`. That is why the raw header text must mention "dmarc" exactly once before anything is stripped; a second mention, even an innocent one inside a comment, fails closed. Gmail has one exception: it reports an ARC chain's own results, including `dmarc=pass`, in a plain comment on the `arc=` clause, so mentions inside a comment directly on an `arc=` clause, with no nesting, quotes or escapes, are not counted. Such a comment is always removed whole by the stripper, so it can never form or hide a clause. Asserted domains must be ASCII before lowercasing (the Kelvin sign U+212A folds to `k`), and `ComposeAuthorization.canonicalAddress` now rejects non-ASCII addresses for the same reason. The Gmail check was hardened to these rules in the same release; its trust model (topmost header, authserv-id `mx.google.com`) is unchanged.

Microsoft's header layout is modeled on Exchange Online's documented form and has been tested only against a simulated service. Until `docs/TEST_PLAN.md` §18 confirms it on real mail, an Outlook sender that cannot be authenticated is rejected, which is the safe direction.

**The stored refresh token.** Gmail stores nothing: Google Play services holds its own grant. Microsoft's device code flow has no such broker, so the app keeps one refresh token, encrypted with AES-256-GCM under a non-exportable Android Keystore key, in app-private storage, with Android backups off and the token kept out of the settings export. The threat model: another app cannot read it (app sandbox), a copied data directory or a backup cannot use it (the key never leaves the Keystore), and a lost token is revoked by disconnecting the app from the Microsoft account. What it does not stop is code running as this app on a rooted or compromised phone, which can ask the Keystore to decrypt the token; that is the same exposure as the Google grant already on the phone. Disconnect deletes the token and the key. A failed decryption is treated as "no token" and asks for a new sign-in rather than guessing.

**Why device code instead of MSAL or a redirect.** MSAL would add several libraries (and the dependency-verification entries for all of them) and a redirect URI tied to the APK's signing certificate, so every rebuild with another key would need the registration updated, the same trap Gmail's Android OAuth client already sets. The device code flow needs only two HTTPS endpoints the app can call with the OkHttp it already has, works with no redirect at all, and suits a phone that lives in a locker: the code can be entered on any device. The Outlook client ID is the owner's own Entra registration and is public, not a secret.

**Polling, not push.** Graph change notifications need a public HTTPS endpoint, which a single-user phone app does not have, so Outlook is polled on the same 30-second tick as Gmail: about one small request per tick while forwarding is on.

**Attachments.** Graph refuses requests over about 4 MB, and a MIME draft is base64 twice (once inside the MIME, once for the request body), about 1.83 times the source size. Outlook forwards therefore carry at most 1,912,568 bytes of attachments in total; over that, all attachments are left out with the same "Attachment not forwarded" disclosure Gmail uses, naming Outlook. Gmail's 18 MB budget is unchanged.

## App lock and backup

**App lock** (Settings) gates the app behind your device's screen lock or biometric the next time it's opened from a cold start; it does not re-prompt on every rotation or on an OS-restored recent task, which is a disclosed trade-off, not a gap in disguise. If the device has no screen lock or biometric enrolled, the lock is skipped rather than locking you out of your own app.

**Backup & restore** (hamburger menu) exports every filter and app setting to a JSON file you choose on-device, and can restore from one, replacing whatever filters currently exist. The file never holds a token: none is stored for Gmail, and the Outlook refresh token and Microsoft account settings are not included.

## Debug safety tests

Debug builds show **Developer safety tests**:

- Enable **Fake successful Gmail transport** to exercise the real persistent queue/limit code without sending mail.
- Inject `500` synthetic events to verify 20/minute, 300/hour, and 450/day behavior.
- Tap **Fail next 5** with queued email work to verify the circuit breaker and persistent alert.

The fake transport is compile-time disabled in release builds. See [docs/TEST_PLAN.md](TEST_PLAN.md) for the full verification procedure.

## Data and privacy

Message bodies and queue payloads are stored in the app-private Room database while delivery is pending. Successfully delivered message/queue payloads are pruned after 30 days; reply-routing records and event logs are pruned after 90 days. MMS files supplied by a live intent are copied to app-private storage, deleted after successful forwarding, and stale unreferenced files are pruned after 24 hours. Notification access is broad at the Android platform level, but the listener accepts content only from Google Messages and Samsung Messages. Android backups remain disabled; screenshots and Recents previews are allowed (the 1.1.0 audit had blocked both with `FLAG_SECURE` — the user asked for screenshots back, so only backups are still restricted). This app sends private communications to Google and to the configured destination mailbox; secure that account appropriately.

## Distribution note

`RECEIVE_SMS`, `RECEIVE_MMS`, and `SEND_SMS` are restricted Google Play permissions. Cross-device SMS synchronization is an allowed exception category, but Play review and a Permissions Declaration are still required. Sideloaded/internal deployments remain subject to device and organization policy.
