# Implementation plan — release 1.4.0 (overnight build)

> **Historical document.** This is the plan for release 1.4.0 as written at the time; later releases changed some of what it describes. For current behavior see [README.md](../README.md), [ARCHITECTURE.md](ARCHITECTURE.md), and [CHANGELOG.md](../CHANGELOG.md).

Requested by the app owner on 2026-09-05, to be ready to sideload on an S24 Ultra by morning.
Two inputs: the audit findings in `QA_AUDIT.md`'s successor pass, and a set of SMS Forwarder
screenshots the owner wants amalgamated into this app's existing safety model — explicitly
**excluding** its ads, its forward-to-another-device feature, and its PC Sync feature.

## 0. Audit fixes (do first, small and contained)

1. `AndroidManifest.xml` / `ForwardingService` — exported SMS/MMS receivers require
   `android.permission.BROADCAST_SMS` so only the telephony stack can trigger them.
2. `scripts/verify_no_history_queries.sh` — fail loudly if `rg` isn't installed instead of
   silently printing PASS.
3. Cross-source dedupe — normalize both sides (SMS raw body vs. RCS-notification cleaned text)
   through the same comparison function so a whitespace/newline difference can't defeat it.
4. `SidekickTheme.kt` — replace deprecated direct `statusBarColor`/`navigationBarColor` writes
   with `enableEdgeToEdge()`, which actually works on API 35+.
5. `ReplyBodyCleaner` — only treat a `From:` line as a quote-header boundary when it contains
   an `@address`, so a genuine reply that starts with "From: Mom" isn't truncated.
6. `MimeMessageBuilder.encodedSubject` — fold long RFC 2047 encoded-words instead of emitting
   one unbounded header line.

## 1. Data model: filters replace the single global rule

`ForwardingStateEntity` keeps owning only what's genuinely global and safety-critical: the
master enabled switch, the watermark, and the email circuit breaker. Everything that was a
single global routing choice (destination email, MMS toggle, call toggle, contact filter)
moves to a new `forwarding_filters` table — one row per named rule, closer to how SMS Forwarder
models it. Migration 4→5 creates the table and seeds exactly one filter from whatever the old
global destination/MMS/call/contact-filter columns held, so an upgrading install keeps
forwarding exactly what it forwarded before. The old columns are left in place (frozen, unread
after migration) rather than dropped, to avoid a destructive schema change under time pressure.

A new `app_settings` singleton table holds the new cross-cutting toggles: app lock, font
scale, duplicate-notification suppression window, the two soft anti-flood ceilings, retry-on-
reconnect, and compose-new-via-email.

Each filter gets: name, enabled, which message types it covers (SMS/MMS/RCS/missed call),
one or more recipient addresses, a condition mode (forward all vs. forward by conditions —
contact allow/block list and keyword include/exclude), an "always allow OTP/security codes"
override, an editable subject/body template with placeholders and find-and-replace rules
(plain or regex), an optional active-hours/days schedule, and per-filter save/notify toggles.
An incoming message is still recorded and deduped exactly once; it then fans out to every
*enabled* filter whose type/schedule/condition checks pass, each queuing its own email.

## 2. New capability: compose a new outbound text from email, not just reply

Existing behavior is unchanged: replying to a forwarded email still requires that email's
Gmail thread or Message-ID to match a route this app actually sent. For starting a *new*
message, there is no such route to check against, so a different, disclosed trust boundary
is used: a `TEXT+E164` subject (nothing else required — the body is the message as-is) is
only actioned when (a) the feature is explicitly turned on (default off), and (b) the
email's `From` address is on a user-managed allowlist of authorized sender addresses,
empty by default. This replaced an earlier "must match the connected Gmail account's own
address" design, which turned out not to hold up in the field: the account this app is
connected to is routinely unreachable from wherever the trigger email actually needs to be
sent from (a workplace limited to Microsoft 365/Outlook webmail, say), so the trigger has to
be allowed to come from an external address. Anything not on the allowlist is logged as a
`SECURITY` event and left unsent, the same treatment forged reply tags already get. An image
attached to an authorized trigger (reply or compose-new) goes out as an MMS instead of an
SMS — see `docs/ARCHITECTURE.md` for how that's implemented and its disclosed
carrier-verification limits.

## 3. Everything else pulled from the SMS Forwarder screenshots

Adopted: multi-filter list/editor, a searchable History screen with a Fail tab, app lock,
local (no cloud/PC) backup and restore of filters + settings as JSON, configurable duplicate-
notification suppression, two configurable soft rate ceilings underneath the existing hard
ones, retry-the-moment-network-returns, and a font-size setting.

Explicitly not built: forwarding to another phone, PC Sync, dual-SIM selection (no second SIM
to verify against tonight — flagged as a follow-up rather than shipped unverified), and
anything ad-related.

## 4. Validation before calling this done

Unit tests for every new pure function (template rendering, replace rules, OTP detection,
schedule window math including overnight windows, condition evaluation, compose-new
authorization, backup/restore round-trip, the migration's seeded filter). Then a full, fresh
`./gradlew testDebugUnitTest --rerun-tasks`, `assembleDebug`, and `lintDebug`, fixing whatever
either surfaces, before the APK is handed over.
