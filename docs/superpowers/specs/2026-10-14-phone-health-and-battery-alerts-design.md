# Phone health in status reports and configurable battery alerts

Date: 2026-10-14. Status: design approved in conversation by the owner; written spec awaiting owner review.

## Why

The phone lives somewhere the owner is not. If its battery dies, forwarding stops and nothing says so. Every status report today (heartbeat, `[SCIF:STATUS]` reply, command receipt, self-test) describes the app, not the phone it runs on. This spec adds (1) a "Phone" block to every status report and (2) proactive, configurable low-battery alert emails.

## Success criteria

- Every status report includes a Phone block with battery level and charging state, and the other fields listed below.
- With alerts enabled and heartbeat recipients set, a discharging phone emails an alert when it crosses the configured threshold, then again at each configured step, up to the configured maximum, and never more.
- A fast drain that skips levels sends one alert, not several.
- Alerts work whether forwarding is on or off.
- The Phone block and alerts never include message content, Wi-Fi names, location, cell tower, carrier or device identifiers.
- No new permission and no new Gradle dependency.
- With alerts off (the default), behavior is identical to 1.29.0 apart from the extra Phone block.

## Non-goals

- No real-time battery broadcast receiver. Detection is a 15-minute WorkManager check.
- No alert channels other than email (no SMS, no on-device notification for these).
- No separate recipient list: alerts go to the heartbeat recipients.
- No alerts for non-battery conditions (network, storage, permissions). Those only appear in the Phone block.
- No change to the queue, retry, rate-limit or circuit-breaker behavior.

## Design

### 1. Phone block in every status report

New file `service/PhoneHealth.kt` with three pieces:

- `PhoneSnapshot`: data class; every field nullable, null meaning "could not read".
- `readPhoneSnapshot(context)`: the system reads. Each read is individually guarded so one failing API prints `unknown` for that line and never breaks the report.
- `formatPhoneHealth(snapshot): String`: pure formatter, unit-testable.

`AppGraph.statusSummary` appends the block to the string from `SidekickRepository.buildStatusSummary`. `buildStatusSummary` is not changed, so its existing test and its callers are unaffected. All four report paths pick it up because they all call `statusSummary`.

Lines (a line that is normal and uninteresting is still printed, except where marked "only if abnormal"):

| Line | Source |
|---|---|
| Battery: level %, charging state and plug type | `BatteryManager` sticky intent (`ACTION_BATTERY_CHANGED`) |
| Battery health and temperature (only if abnormal) | same intent |
| Power saver on/off | `PowerManager.isPowerSaveMode` |
| Doze idle/active | `PowerManager.isDeviceIdleMode` |
| Battery optimization exempt / NOT exempt | `PowerManager.isIgnoringBatteryOptimizations` |
| Background restricted yes/no | `ActivityManager.isBackgroundRestricted` |
| Network: Wi-Fi / cellular / none, validated yes/no | `ConnectivityManager` active network capabilities |
| Airplane mode on/off | `Settings.Global.AIRPLANE_MODE_ON` |
| SIM state: ready / absent / locked / unknown | `TelephonyManager.simState` |
| Free storage | `StatFs` on the app data directory |
| Last reboot: how long ago | `SystemClock.elapsedRealtime` |
| Missing permissions (only if any: SMS receive, notifications) | `checkSelfPermission` |

Permissions already in the manifest cover all of this (`ACCESS_NETWORK_STATE`, `READ_PHONE_STATE`).

### 2. Battery alerts

**Settings** (migration 18 to 19, `app_settings`, Room database version 19):

| Column | Default | Valid range (enforced where used, see below) |
|---|---|---|
| `batteryAlertsEnabled` | 0 | |
| `batteryAlertThresholdPercent` | 20 | 5 to 50 |
| `batteryAlertStepPercent` | 5 | 1 to 25 |
| `batteryAlertMaxCount` | 3 | 1 to 10 |

The three numbers are stored as typed (kept to 0 to 100 by the UI) and brought into their valid ranges where they are used, by `batteryAlertLevels`. Clamping on every keystroke would rewrite a field under the cursor: typing 15 into a field whose minimum is 5 turns the 1 into a 5. The UI preview shows the values that will actually fire.

The settings travel with the other `app_settings` fields in backup and restore; the implementation plan must confirm how `exportBackup` enumerates fields and keep the new ones consistent with it.

**State** (same migration, `forwarding_state`): `batteryAlertsFired INTEGER NOT NULL DEFAULT 0`. State, not a setting: kept out of backup and restore, like `lastHeartbeatEmailMs`, so a restored backup cannot convince the app it already alerted.

**Rule**: a pure function `evaluateBatteryAlert(level, charging, thresholdPercent, stepPercent, maxCount, firedCount) -> Decision(send, newFiredCount)`.

- Alert level k is `threshold - k * step`, for k in `0 until maxCount`. Levels below 1% are dropped.
- If `level > threshold`: reset. Decision is no send, `newFiredCount = 0`. This is the only reset path; charging by itself does not reset, so unplugging at the same low level cannot re-alert.
- If charging: no send, `firedCount` unchanged. "Charging" means the battery is actually gaining charge (charge state CHARGING or FULL). A phone plugged into a loose or dead charger whose level keeps falling still alerts. The plug alone decides only when Android reports no charge state at all.
- Otherwise `dueIndex = min((threshold - level) / step, maxCount - 1)`. If `dueIndex >= firedCount`: send, `newFiredCount = dueIndex + 1`. Else no send.

With the defaults: alerts at 20%, 15%, 10%. A drop from 22% to 9% between checks sends one alert (for the 10% level) and sets the count to 3.

**Worker**: new `BatteryAlertWorker`, a `CoroutineWorker` on a 15-minute periodic request with the network-connected constraint, scheduled in `ScifSidekickApp` beside `HeartbeatEmailWorker`. It is not gated on forwarding being on. Each run:

1. Read settings. Return if alerts are disabled.
2. Read heartbeat recipients (`heartbeatRecipientsJson`). Return if empty.
3. Read battery level and charging state from the sticky battery intent. Return if unreadable.
4. Call `evaluateBatteryAlert`. If the decision is a reset or a no-op, persist the new count when it changed and return.
5. On send, queue a system email (`enqueueSystemEmail`) to the recipients. Subject: `SCIF Sidekick battery low - <level>%`. Body: a short sentence naming the level and alert number, then the full status summary (which contains the Phone block).
6. Advance `batteryAlertsFired` only after the row is queued. A refused enqueue retries on the next tick.
7. Record a service event, then drain the email queue the same way `HeartbeatEmailWorker` does, logging a drain failure without failing the worker.

The network constraint is deliberate: marking an alert as sent while offline would be a lie. If the phone is offline when the threshold is crossed, the check runs when the network returns and the deepest-level-crossed rule sends the right alert then.

**Known limit**: WorkManager can defer a run under Doze or OEM battery management, so on the S24 an alert may arrive later than 15 minutes after the crossing. The deepest-level-crossed rule keeps the content correct; only timing slips.

### 3. Settings UI

A new "Battery alerts" `OutlinedCard` in `SettingsScreens.kt`, directly under the Heartbeat email card, built from the same components (switch row, `IntField`, `bodySmall` explanation). It contains:

- Switch: enable battery alerts.
- Three number fields: threshold (5 to 50), step (1 to 25), maximum alerts (1 to 10), each clamped on change.
- A live preview line, for example `Alerts at 20%, 15%, 10%`.
- Explanatory text stating that alerts go to the heartbeat recipients and that a dead phone cannot report its own death, so the first alert should fire early.
- A visible warning when no heartbeat recipients are set, since alerts then cannot send.

## Testing

- Unit (JVM): `evaluateBatteryAlert` (first crossing, step crossing, max count cap, skipped levels send once, reset above threshold, charging suppresses and does not reset, unplug at the same low level does not re-alert, out-of-range settings); `formatPhoneHealth` (all fields present, each field null prints `unknown`, abnormal-only lines appear only when abnormal).
- Instrumented, emulator only (`ANDROID_SERIAL=emulator-5554`; the S24 must not run instrumented tests): `statusSummary` contains the Phone block; migration 18 to 19 preserves existing settings and defaults the new ones.
- `docs/TEST_PLAN.md`: a new section for real-device checks: drain or simulate the S24 to the threshold, confirm each alert and its count, confirm the Phone block arrives in a heartbeat and in a `[SCIF:STATUS]` reply, confirm no alert while charging.

## Release

Version 1.30.0, `CHANGELOG.md` and `docs/CHANGELOG_DETAILED.md` entries, README mention of battery alerts. The release APK stays debug-signed, as in 1.29.0.
