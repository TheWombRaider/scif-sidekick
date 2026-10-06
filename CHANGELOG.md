# Changelog

Short version. The full notes, with the reasoning behind each change, are in [docs/CHANGELOG_DETAILED.md](docs/CHANGELOG_DETAILED.md).

## 1.25.0
- Security hardening: the widget's on/off button can no longer be triggered by other apps, an owner address added automatically on first connect can only ask for status, and the premium-rate and satellite number ranges are blocked.
- Texts are now capped at 60 an hour and 200 a day (still 10 a minute). Forwards leave a small reserve (30 a day, 10 an hour) so receipts, status replies and the heartbeat still go out during a flood.
- `[SCIF:ON]`/`[SCIF:STATUS]` are found even behind junk mail: only allowlisted senders are searched, and up to 20 messages are examined per run.
- Receipts are sent by a background job, so the `[SCIF:OFF]` receipt is no longer cut off when the service stops. An ON receipt now says if Android wouldn't start the service.
- New setting: hide app content in Recents. Backups use 600,000 key-derivation iterations (old backups still restore).
- Updated libraries and Gradle; signed-release build support; new install, troubleshooting and policy sections in the README.

## 1.24.0
- `[SCIF:ON]` and `[SCIF:OFF]` now reply with a confirmation email showing the current status. Unauthorized senders get no reply.
- README rewritten around features and usage tables.
- Removed the developer card from the About screen.

## 1.23.1
- Custom heartbeat interval: a 1-720 hour field next to the 6h/12h/24h/48h presets.

## 1.23.0
- Compose, Enable, Disable and Status are now one "Remote control by email" card: one master switch, one address list, and an independent checkbox per command for each address.
- On by default. The connected Gmail account is authorized automatically with everything checked.
- Upgrades keep exactly the permissions each address already had. Old backups still restore.

## 1.22.0
- Faster forwarding: new messages wake the loop immediately, and a short wakelock keeps a sleeping phone up until the send finishes.
- Cheaper reply polling using Gmail's history log. Replies and `[SCIF:...]` commands are picked up in about 30 seconds instead of 90.
- Tighter authorization for replies from compose-allowlist addresses, with each acceptance logged.
- The Pub/Sub scope is requested only while Gmail push is on.
- Ignore a messaging notification whose newest line is the user's own message.
- Watchdog restarts a dead service after 10 minutes instead of 20.
- Added GitHub Actions CI.

## 1.21.0
- Choose which SIM texts are sent from (shown only on dual-SIM phones). Falls back to the system default whenever the chosen SIM can't be confirmed active.

## 1.20.0
- `[SCIF:STATUS]` email command: replies with the same summary the heartbeat sends, in either state.

## 1.19.x
- Opt-in heartbeat email on a fixed schedule, so silence tells you something broke. 1.19.1 fixed it not sending while forwarding was off.

## 1.18.0
- Email-initiated texts now email the sender back: delivered, or failed with the reason.

## 1.17.0
- `[SCIF:OFF]` email command to turn forwarding off.

## 1.16.x
- `[SCIF:ON]` email command to turn forwarding on while the app is idle, via a 15-minute background check.
- 1.16.1: battery fix in the RCS notification listener (default dialer is now cached).

## 1.15.0
- About screen with an in-app changelog. Quick-toggle filters on the home screen.

## 1.14.x
- Rainbow Road accent rebuilt as a draw-phase effect, which fixed the scrolling jank for good. 1.14.1 fixed Settings losing its scroll position. 1.14.2 was an audit with two small fixes.

## 1.13.x
- 1.13.1: incoming MMS photos on slow connections now wait up to ~28 seconds instead of ~3.
- 1.13.2: battery fix for the foreground service posting a notification every tick.
- 1.13.3: RCS reply routing for contacts not saved on the phone.
- 1.13.4-1.13.7: successive attempts at the accent animation jank.

## 1.9.x
- 1.9.0: incoming MMS media forwarding, Send Test Message, Test Gmail Connectivity, and message log export.
- 1.9.1-1.9.2: duplicate-reply fix, and a message editor and photo picker for test messages.

## 1.5.0 - 1.8.x
- Drag-to-reorder filters, a "stop processing further filters" switch, a time limit on regex rules, and a self-forwarding loop fix.
- Several layout and scrolling fixes. Release notes for 1.6-1.8 weren't kept.

## 1.4.0
- Independent forwarding **filters** replace the single global destination. Added app lock, backup and restore, searchable history, and starting a new text from email.
- Fixed the findings from an independent audit, including an over-permissive SMS/MMS receiver.

## 1.1.0 - 1.3.0
- Audited base release, notification-based RCS coverage, adaptive icon, OLED dark theme, MMS toggle, contact allow/block lists, and missed-call forwarding.
