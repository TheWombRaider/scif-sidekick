# Microsoft Graph transport and mail failover (steps 2 and 3)

Date: 2026-10-09. Status: approved by the owner's standing instruction to build what the step 1 spec outlined ("build everything the spec detailed"). Builds on `docs/superpowers/specs/2026-10-08-mail-transport-interface-design.md` (step 1, shipped).

## Why

Google sign-in keeps dropping, and when the only mail link drops the phone goes silent, which is the one failure this app must not have. Step 1 put a `MailTransport` interface in front of Gmail. This spec adds a second provider, Microsoft Graph (Outlook.com and Microsoft 365), and a router that keeps the app working when either mailbox is down.

## Success criteria

- With both mailboxes connected, each of these still works when the other one is broken or signed out: forwarding out, reply-by-email into texts, `[SCIF:...]` commands, receipts, heartbeat.
- With only Gmail connected, behavior is identical to 1.28.1.
- Command authorization is never weaker than today, on either provider. Anything unrecognized fails closed.
- No new Gradle dependency (the dependency-verification file does not change).
- A user can connect and disconnect the Microsoft account from the phone, with no PC and no rebuild.

## Non-goals

- No IMAP, no Exchange on-premises, no multiple accounts per provider (one Gmail, one Microsoft).
- No Gmail push equivalent for Microsoft (Graph change notifications need a public endpoint). Microsoft is polled.
- No database schema change. Account settings live in SharedPreferences.
- No change to retry, rate-limit, circuit-breaker or queue semantics.

## What the owner must do once (cannot be automated)

Register a free app in Microsoft Entra and paste its Application (client) ID into the app. Steps (go in the README too):

1. Sign in at https://entra.microsoft.com with the Outlook account, then **App registrations → New registration**.
2. Name: anything neutral. **Supported account types: "Accounts in any organizational directory and personal Microsoft accounts"** (the personal-account option is required).
3. No redirect URI. Register.
4. **Authentication → Advanced settings → Allow public client flows: Yes.** Save.
5. **API permissions → Add → Microsoft Graph → Delegated: `Mail.ReadWrite`, `Mail.Send`, `User.Read`, `offline_access`.** No admin consent needed for personal accounts.
6. Copy the **Application (client) ID** into SCIF Sidekick, Settings → Email accounts → Microsoft.

The client ID is public, not a secret.

## Design

### Sign-in: OAuth 2.0 device code flow, no new dependency

MSAL would add several libraries and a redirect URI tied to the APK signing key. The device code flow needs neither and fits a phone that lives in a locker: the app shows a short code and `https://microsoft.com/devicelogin`; the user enters it on any device.

- `POST https://login.microsoftonline.com/common/oauth2/v2.0/devicecode` with `client_id`, `scope=offline_access User.Read Mail.ReadWrite Mail.Send`.
- Poll `POST .../common/oauth2/v2.0/token` with `grant_type=urn:ietf:params:oauth:grant-type:device_code` at the returned `interval`, handling `authorization_pending` (keep polling), `slow_down` (add 5 s), `expired_token` and `authorization_declined`/`access_denied` (stop with a clear message).
- Refresh: `grant_type=refresh_token`. A new refresh token in the response replaces the stored one. `invalid_grant` means the user must sign in again (`MailAuthRequiredException`); a network error is just a failure and is retried later.
- Access tokens live in memory only. One refresh at a time (a mutex), so concurrent callers share the result.

### Token storage

The refresh token is stored encrypted with AES-GCM under a non-exportable Android Keystore key (alias `scif_ms_refresh_v1`), in app-private SharedPreferences, with a fresh 12-byte IV per write. Backups are already off, and the token is never in the exported settings backup. Disconnect deletes the stored token and the key. The README sentence "the app never stores a token" becomes "Gmail: no; Microsoft: one refresh token, encrypted with a key that never leaves the phone's secure hardware."

### Graph transport (`GraphGateway : MailTransport`)

`providerId = "graph"`, `displayName = "Outlook"`. All ids it returns are scoped `graph:<native id>` (see `MailIds`). Base URL `https://graph.microsoft.com/v1.0`. Every call goes through one `execute` that adds the bearer token, refreshes once on 401, and maps a failed refresh to `MailAuthRequiredException`. HTTP 429 and 503 honor `Retry-After` (bounded to 60 s) and otherwise surface as ordinary failures for the caller's retry logic.

| Interface method | Graph call |
|---|---|
| `accountEmail()` | `GET /me?$select=mail,userPrincipalName` (mail, else UPN) |
| `send` | Build the MIME with `MimeMessageBuilder` (so Message-ID, References and attachments are exactly what Gmail sends). `POST /me/messages` with `Content-Type: text/plain` and the standard-base64 MIME body creates a draft and returns `id` and `internetMessageId`; then `POST /me/messages/{id}/send`. The receipt carries the draft's `internetMessageId` (without angle brackets) as `rfcMessageId`, so route matching uses what Exchange actually stamped. |
| `findSent(deliveryKey)` | `GET /me/mailFolders/sentitems/messages?$filter=internetMessageId eq '<id>'&$select=id,conversationId,internetMessageId` |
| `pollReplies(known)` | `GET /me/mailFolders/inbox/messages?$select=id,conversationId,subject,from,internetMessageId,receivedDateTime&$orderby=receivedDateTime desc&$top=50` filtered by `receivedDateTime ge <since>`; subjects containing `SCIF` or `TEXT` (case-insensitive) are kept client-side. Returns read and unread mail alike (the lesson of the old `is:unread` bug). Normal polls use `since = last successful poll − 10 min`; a full 90-day sweep runs first, then every 6 h, matching the Gmail adapter. Up to 4 pages per poll. |
| `findCommands` | Same list endpoint with `isRead eq false` and `receivedDateTime ge now−2d`; subjects matched against `search.tags` and `from` against `search.senders` client-side (the OData filter is deliberately kept simple so it cannot be rejected as "inefficient"); newest first, at most `RemoteCommandPlanner.MAX_CANDIDATES`. |
| per-candidate metadata | `GET /me/messages/{id}?$select=internetMessageHeaders,from,subject,conversationId,internetMessageId`. References and In-Reply-To come from `internetMessageHeaders`. |
| `fetchContent` | `GET /me/messages/{id}?$select=body` with `Prefer: outlook.body-content-type="text"`; first image `fileAttachment` from `GET /me/messages/{id}/attachments?$select=name,contentType,size,contentBytes` (size-capped like Gmail's). |
| `markRead` | `PATCH /me/messages/{id}` with `{"isRead":true}` (idempotent). |
| `checkForBounces` | Unread inbox mail whose sender contains `mailer-daemon` or `postmaster`, or whose subject matches the Gmail adapter's delivery-failure phrases; body text searched for this installation's own Message-ID pattern. |
| `clearSession` | Drops the in-memory access token and poll cursors. |

`MailMessage.threadId` is `graph:<conversationId>`.

### Sender authentication (fail closed)

`GraphAuthentication.authenticatedFrom(fromHeader, headers)` returns the sender address only when all of these hold, else null:

1. The `From` header yields exactly one address (same rule as Gmail's).
2. There is at least one `Authentication-Results` header, and the **first** one in `internetMessageHeaders` order is used (Exchange Online prepends its own; anything an attacker embedded sits below it). Later ones are ignored, never merged.
3. That header contains `dmarc=pass` and a `header.from=<domain>` whose domain equals the `From` address's domain exactly (no subdomain relaxation).
4. The header does not carry `dmarc=` more than once with differing results.

This is modeled on Exchange Online's documented header (`spf=...; dkim=...; dmarc=pass action=none header.from=example.com; compauth=pass reason=100`). **It has not yet been checked against a real message** (the owner has no registered app yet); the first real check is a required item in `docs/TEST_PLAN.md` (new section) and until it passes, an Outlook sender that cannot be authenticated is simply rejected, which is the safe direction. Mail the user sends to the same account has no such header and is rejected, exactly like Gmail's self-sent mail.

### Router (`MailRouter : MailTransport`)

`AppGraph.mail` becomes a `MailRouter` over the connected transports; `graph.gmail` and the new `graph.graphMail` stay available for sign-in UI. With one transport connected the router is a pass-through.

- **Order.** A preferred provider (setting, default `gmail`) is tried first; the other is the fallback. Only transports with `isAvailable` take part.
- **`isAvailable`**: any member available. **`accountEmail()`**: the first available member's.
- **`send`**:
  1. If `verifyPriorDelivery`, ask every available member `findSent(deliveryKey)`; the first hit returns with `reconciled = true`, so a forward that went out through the fallback is never repeated by the preferred provider coming back.
  2. Otherwise try members in order with `verifyPriorDelivery = false`. On `MailAuthRequiredException` mark that member as needing reconnection, alert, and try the next. On any other failure try the next only if the failure is **definite** (the provider returned an HTTP error response, or the failure happened before any request was sent). On an **ambiguous** failure (timeout or connection reset after the request may have reached the provider) first `findSent` on that member; if it is there, return it reconciled; if the check itself cannot run, try the next member and log "possible duplicate" in the event log. This prefers a rare duplicate to a lost forward.
  3. If every member fails, rethrow the first member's failure so the queue's existing retry and circuit-breaker logic is unchanged.
- **`pollReplies`**: poll every available member; results are concatenated; a failing member is recorded and skipped; if every member failed, rethrow the first failure (so `ForwardingService` behaves as today).
- **`findCommands`**: every available member is asked; candidates are interleaved round-robin (preferred first) so one mailbox full of junk cannot crowd the other out of `RemoteCommandPlanner`'s window; unreadable ids are concatenated.
- **`markRead` / `fetchContent`**: routed by `MailIds.providerOf(id)`.
- **`checkForBounces`**: concatenated; failures skipped.
- **`clearSession`**: all members.
- **Reconnect alerts** are per provider: the notification names the provider ("Reconnect Gmail" / "Reconnect Outlook") and has its own id, so one mailbox needing attention never hides or cancels the other's alert. The alert clears when that provider next succeeds.

### Interface and type changes (the step 1 final-review follow-ups)

- `MailTransport.findSent(deliveryKey: String): MailReceipt?` (new; Gmail implements it with its existing Sent lookup by RFC Message-ID, returning null when none).
- `MailAuthRequiredException(message, val providerId: String, val displayName: String)`; `ReauthorizationRequiredException` passes `gmail`/`Gmail`. `AlertNotifier.showAuthorizationRequired(providerId, displayName)` uses them.
- `CommandSearch` gains `init { require(senders.isNotEmpty()) }` and `require(tags.isNotEmpty())`.
- KDoc states that ids, thread ids and `BounceNotice.messageId` are provider-scoped (`MailIds`), and the contract test asserts it for every id a transport returns.
- `AppGraph.mail` is `@Volatile internal var` so tests can install a fake transport or a router.
- Status summaries gain one line per connected account (`Outlook authorization: OK`); the existing `Gmail authorization:` line is unchanged.

### Remote control seeding

When the Microsoft account first connects, its address is added to the authorized remote-control senders (all four commands, once per install, never re-added after the owner removes it), mirroring the Gmail seeding. This is what makes the useful case work out of the box: a command emailed from the Gmail address to the Outlook inbox (or the reverse) is authenticated by the receiving provider, because the two addresses are different accounts.

### UI

- Settings gets an **Email accounts** section: the existing Gmail connection card, and a new **Microsoft (Outlook.com)** card with the client-ID field (until set), **Connect**, the device-code panel (the code in large type, the URL, **Copy code**, **Open page**, a cancel button, live status), the connected address, **Disconnect**, and a **Send first** choice (Gmail or Microsoft) shown only when both are connected.
- Home gets an **Outlook ✓** status chip next to the Gmail chip when a Microsoft account is connected, and the chip turns to a warning style when it needs reconnecting.
- All Gmail-only wording in generic places (setup checklist stays Gmail-first and unchanged for Gmail-only users) keeps working; the **Send test receipt** button emails through the router and its message says which account was used.
- The in-app changelog, the Commands screen and the About screen are unchanged except the changelog entry.

### Testing

- **Device code flow**: canned token endpoint covering success, `authorization_pending` then success, `slow_down`, `expired_token`, `access_denied`, malformed JSON, and refresh success, refresh rotation, `invalid_grant`.
- **Token store**: Keystore round trip, wrong-alias failure, tamper detection (flipped ciphertext byte fails closed), disconnect deletes (instrumented).
- **`GraphAuthentication`**: aligned pass accepted; subdomain mismatch, `dmarc=fail`, no header, second header only, forged lower header with a failing top one, multiple addresses, header-injection characters, mixed-case names, a `dmarc=pass` that appears only inside another clause's quoted reason, all rejected.
- **`GraphGateway`** against a `FakeGraphServer` (an OkHttp interceptor implementing the Graph subset above over an in-memory mailbox): the shared `MailTransportContract` runs against it, plus Graph-specific cases (draft-then-send sequence, `internetMessageId` read-back used as the receipt's Message-ID, read mail still returned by `pollReplies`, 401 then refresh then retry, `invalid_grant` becoming `MailAuthRequiredException`, `Retry-After`, paging, HTML-only body handling).
- **`MailRouter`** with fake transports: preferred-first ordering, fallback on auth failure, fallback on definite failure, ambiguous-failure verification found / not found / check impossible, `findSent` across members before any send, poll aggregation with one failing member, all failing rethrows, command interleaving, id routing, per-provider alert calls, single-member pass-through identical to the member.
- **Instrumented**: `QueueProcessor` over a router of two fakes (send succeeds via fallback when the preferred is signed out; reconciled result when the fallback already has it).
- Existing suites unchanged and green. Instrumented tests run on the emulator only.

## Risks and how they are contained

- **Graph behaviors that cannot be verified without a registered app** (Message-ID preservation on draft creation, the exact `Authentication-Results` layout, OData filter acceptance). Mitigations: the receipt uses the Message-ID Exchange actually returns; sender authentication fails closed; filters are kept to `receivedDateTime` and `isRead` only; every assumption is a named, checkable item in the new TEST_PLAN section, and the transport logs a service event the first time any assumption is violated (for example a returned `internetMessageId` that differs from the one supplied).
- **Refresh token theft** from a rooted phone: unchanged in kind from the Google grant already on the phone; the token cannot be extracted from the Keystore but can be used by code running as the app. Documented.
- **Larger attack surface in the sender check**: contained by a single small, heavily tested parser.
- **Battery**: a connected Microsoft account adds roughly one small HTTPS request per 30-second tick while forwarding is on (Graph has no push here, so it is always polled). The Microsoft card says so.

## Order of work

1. Interface follow-ups (findSent, auth exception fields, CommandSearch checks, injectable mail, KDoc, contract additions).
2. `MailRouter` and its tests, wired into `AppGraph` with Gmail as the only member (no behavior change).
3. Microsoft sign-in (device code flow, token store) and tests.
4. `GraphAuthentication` and tests.
5. `GraphGateway`, `FakeGraphServer`, contract and Graph tests.
6. Wiring: both transports in the router, per-provider alerts, status summaries, remote-control seeding, preferences.
7. UI: Microsoft card, device-code panel, Send-first choice, Home chip.
8. Docs (README setup and privacy text, ARCHITECTURE, DESIGN_NOTES, TEST_PLAN, changelogs), version 1.29.0, full verification, instrumented run, release.
