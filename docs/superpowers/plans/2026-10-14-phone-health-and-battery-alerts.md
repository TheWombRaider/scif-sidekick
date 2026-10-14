# Phone health and battery alerts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Phone block to every status report and configurable proactive low-battery alert emails.

**Architecture:** Pure, JVM-testable logic (`evaluateBatteryAlert`, `formatPhoneHealth`) with thin Android glue (`readPhoneSnapshot`, `BatteryAlertWorker`). Settings live on `app_settings` (Room migration 18 to 19) and the "already alerted" counter lives on `forwarding_state`. The worker follows `HeartbeatEmailWorker`'s pattern and sends to the heartbeat recipients.

**Tech Stack:** Kotlin, Room, WorkManager, Jetpack Compose, JUnit4 (JVM tests).

**Spec:** `docs/superpowers/specs/2026-10-14-phone-health-and-battery-alerts-design.md`

## Global Constraints

- No new permission and no new Gradle dependency (`ACCESS_NETWORK_STATE` and `READ_PHONE_STATE` are already in the manifest; `minSdk = 26`).
- The Phone block and alert emails never contain message content, Wi-Fi names, location, cell tower, carrier or device identifiers.
- Defaults: alerts off; threshold 20; step 5; max count 3. Valid ranges: threshold 5 to 50, step 1 to 25, count 1 to 10.
- Alerts go to `heartbeatRecipientsJson`; the worker is not gated on forwarding being on.
- `batteryAlertsFired` is state, not a setting: never exported in backup.
- `SidekickRepository.buildStatusSummary` is not changed.
- Instrumented tests run only on the emulator (`ANDROID_SERIAL=emulator-5554`), never the S24.
- Per the owner: one commit at the end, no push.

## Review Focus

- Level drops past several alert levels between two checks: exactly one alert, for the deepest level crossed (Task 1 test).
- Phone plugged in but not actually charging (loose or dead charger, level still falling): must still alert (Task 1 test).
- Settings edited mid-episode (max lowered below the count already fired, threshold raised): must not crash or spam (Task 1 tests).
- Any single system read failing in `readPhoneSnapshot`: the line prints `unknown`, the rest of the report is intact (Task 2 test on the formatter with nulls; reader wraps each read).
- Typing a multi-digit number into a settings field: the stored value must not be rewritten under the cursor, so ranges are enforced where the value is used, not on every keystroke (Task 5).

---

### Task 1: Battery alert policy (pure logic)

**Files:**
- Create: `app/src/main/java/com/scifsidekick/cleanroom/service/BatteryAlertPolicy.kt`
- Test: `app/src/test/java/com/scifsidekick/cleanroom/BatteryAlertPolicyTest.kt`

**Interfaces:**
- Produces:
  - `object BatteryAlertLimits { val THRESHOLD: IntRange; val STEP: IntRange; val COUNT: IntRange }`
  - `fun batteryAlertLevels(thresholdPercent: Int, stepPercent: Int, maxCount: Int): List<Int>` (sanitized, descending, every level at least 1)
  - `data class BatteryAlertDecision(val send: Boolean, val alertNumber: Int, val newFiredCount: Int)` (`alertNumber` is 1-based, 0 when not sending)
  - `fun evaluateBatteryAlert(level: Int, charging: Boolean, thresholdPercent: Int, stepPercent: Int, maxCount: Int, firedCount: Int): BatteryAlertDecision`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.service.BatteryAlertDecision
import com.scifsidekick.cleanroom.service.batteryAlertLevels
import com.scifsidekick.cleanroom.service.evaluateBatteryAlert
import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryAlertPolicyTest {
    private fun eval(
        level: Int,
        fired: Int,
        charging: Boolean = false,
        threshold: Int = 20,
        step: Int = 5,
        max: Int = 3,
    ) = evaluateBatteryAlert(level, charging, threshold, step, max, fired)

    @Test fun `levels follow threshold step and count`() {
        assertEquals(listOf(20, 15, 10), batteryAlertLevels(20, 5, 3))
    }

    @Test fun `levels below one percent are dropped`() {
        assertEquals(listOf(5), batteryAlertLevels(5, 5, 3))
        assertEquals(listOf(7, 4, 1), batteryAlertLevels(7, 3, 5))
    }

    @Test fun `out of range settings are clamped when used`() {
        assertEquals(listOf(50, 49, 48), batteryAlertLevels(999, 0, 3))
        assertEquals(listOf(5), batteryAlertLevels(0, 99, 1))
        assertEquals(10, batteryAlertLevels(50, 1, 99).size)
    }

    @Test fun `above the threshold nothing is sent and the count resets`() {
        assertEquals(BatteryAlertDecision(false, 0, 0), eval(level = 21, fired = 2))
    }

    @Test fun `first crossing sends alert one`() {
        assertEquals(BatteryAlertDecision(true, 1, 1), eval(level = 20, fired = 0))
        assertEquals(BatteryAlertDecision(true, 1, 1), eval(level = 18, fired = 0))
    }

    @Test fun `no repeat while still inside the same band`() {
        assertEquals(BatteryAlertDecision(false, 0, 1), eval(level = 16, fired = 1))
    }

    @Test fun `each further step sends the next alert`() {
        assertEquals(BatteryAlertDecision(true, 2, 2), eval(level = 15, fired = 1))
        assertEquals(BatteryAlertDecision(true, 3, 3), eval(level = 10, fired = 2))
    }

    @Test fun `a drop past several levels sends one alert for the deepest level`() {
        assertEquals(BatteryAlertDecision(true, 3, 3), eval(level = 9, fired = 0))
        assertEquals(BatteryAlertDecision(true, 3, 3), eval(level = 2, fired = 1))
    }

    @Test fun `never more than the maximum count`() {
        assertEquals(BatteryAlertDecision(false, 0, 3), eval(level = 3, fired = 3))
        assertEquals(BatteryAlertDecision(false, 0, 3), eval(level = 1, fired = 3))
    }

    @Test fun `charging suppresses sending and keeps the count`() {
        assertEquals(BatteryAlertDecision(false, 0, 1), eval(level = 8, fired = 1, charging = true))
        assertEquals(BatteryAlertDecision(false, 0, 0), eval(level = 8, fired = 0, charging = true))
    }

    @Test fun `charging back above the threshold re-arms`() {
        assertEquals(BatteryAlertDecision(false, 0, 0), eval(level = 45, fired = 3, charging = true))
    }

    @Test fun `unplugging at the same low level does not alert again`() {
        assertEquals(BatteryAlertDecision(false, 0, 1), eval(level = 12, fired = 1, charging = false))
    }

    @Test fun `lowering the maximum below the fired count never sends`() {
        assertEquals(BatteryAlertDecision(false, 0, 3), eval(level = 6, fired = 3, max = 2))
    }

    @Test fun `raising the threshold mid episode sends the newly due alert`() {
        assertEquals(BatteryAlertDecision(true, 2, 2), eval(level = 25, fired = 1, threshold = 30))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.scifsidekick.cleanroom.BatteryAlertPolicyTest"`
Expected: FAIL (compilation error, unresolved reference `evaluateBatteryAlert`)

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.scifsidekick.cleanroom.service

/** Valid ranges for the battery-alert settings. Enforced where the values are used, not when typed. */
object BatteryAlertLimits {
    val THRESHOLD = 5..50
    val STEP = 1..25
    val COUNT = 1..10
}

/** What [evaluateBatteryAlert] decided. [alertNumber] is 1-based and 0 when nothing is sent. */
data class BatteryAlertDecision(
    val send: Boolean,
    val alertNumber: Int,
    val newFiredCount: Int,
)

/**
 * The levels alerts fire at, deepest last: `threshold - k * step` for each of [maxCount] alerts,
 * with every input clamped into [BatteryAlertLimits] and any level below 1% dropped.
 */
fun batteryAlertLevels(
    thresholdPercent: Int,
    stepPercent: Int,
    maxCount: Int,
): List<Int> {
    val threshold = thresholdPercent.coerceIn(BatteryAlertLimits.THRESHOLD)
    val step = stepPercent.coerceIn(BatteryAlertLimits.STEP)
    val count = maxCount.coerceIn(BatteryAlertLimits.COUNT)
    return (0 until count).map { threshold - it * step }.filter { it >= 1 }
}

/**
 * Decides whether a battery alert is owed.
 *
 * - Above the threshold: nothing is sent and the fired count resets. This is the only reset, so
 *   plugging in at a low level and unplugging again cannot repeat an alert already sent.
 * - Charging: nothing is sent and the count is kept.
 * - Otherwise the deepest level at or above the current one is due. It is sent once, and a fast
 *   drop past several levels sends one alert, not one per level skipped.
 */
fun evaluateBatteryAlert(
    level: Int,
    charging: Boolean,
    thresholdPercent: Int,
    stepPercent: Int,
    maxCount: Int,
    firedCount: Int,
): BatteryAlertDecision {
    val levels = batteryAlertLevels(thresholdPercent, stepPercent, maxCount)
    if (levels.isEmpty() || level > levels.first()) return BatteryAlertDecision(false, 0, 0)
    if (charging) return BatteryAlertDecision(false, 0, firedCount)
    val due = levels.indexOfLast { level <= it }
    return if (due >= firedCount) {
        BatteryAlertDecision(true, due + 1, due + 1)
    } else {
        BatteryAlertDecision(false, 0, firedCount)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.scifsidekick.cleanroom.BatteryAlertPolicyTest"`
Expected: PASS (14 tests)

---

### Task 2: Phone health block

**Files:**
- Create: `app/src/main/java/com/scifsidekick/cleanroom/service/PhoneHealth.kt`
- Modify: `app/src/main/java/com/scifsidekick/cleanroom/AppGraph.kt:138-149`
- Test: `app/src/test/java/com/scifsidekick/cleanroom/PhoneHealthFormatTest.kt`

**Interfaces:**
- Produces:
  - `enum class PlugType { NONE, AC, USB, WIRELESS, OTHER }`, `enum class ChargeState { CHARGING, DISCHARGING, FULL, NOT_CHARGING, UNKNOWN }`, `enum class NetworkKind { WIFI, CELLULAR, ETHERNET, OTHER, NONE }`
  - `data class BatteryReading(percent: Int, plug: PlugType, chargeState: ChargeState, healthLabel: String?, temperatureC: Float?)` with `val isCharging: Boolean`
  - `data class PhoneSnapshot(...)` (all fields nullable or defaulted)
  - `fun formatPhoneHealth(snapshot: PhoneSnapshot): String`
  - `fun readBatteryReading(context: Context): BatteryReading?`
  - `fun readPhoneSnapshot(context: Context): PhoneSnapshot`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.service.BatteryReading
import com.scifsidekick.cleanroom.service.ChargeState
import com.scifsidekick.cleanroom.service.NetworkKind
import com.scifsidekick.cleanroom.service.PhoneSnapshot
import com.scifsidekick.cleanroom.service.PlugType
import com.scifsidekick.cleanroom.service.formatPhoneHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneHealthFormatTest {
    private val healthy =
        PhoneSnapshot(
            battery = BatteryReading(63, PlugType.AC, ChargeState.CHARGING, "GOOD", 31.0f),
            powerSaver = false,
            dozing = false,
            batteryOptimizationExempt = true,
            backgroundRestricted = false,
            network = NetworkKind.WIFI,
            networkValidated = true,
            airplaneMode = false,
            simState = "ready",
            freeStorageBytes = 41_200_000_000L,
            uptimeMs = 3L * 60 * 60_000,
            missingPermissions = emptyList(),
        )

    @Test fun `a healthy phone prints every normal line and no abnormal ones`() {
        val text = formatPhoneHealth(healthy)
        assertTrue(text.contains("Battery: 63%, charging (AC)"))
        assertTrue(text.contains("Power saver: off"))
        assertTrue(text.contains("Battery optimization: exempt"))
        assertTrue(text.contains("Network: Wi-Fi, internet validated"))
        assertTrue(text.contains("Airplane mode: off"))
        assertTrue(text.contains("SIM: ready"))
        assertTrue(text.contains("Free storage: 41.2 GB"))
        assertTrue(text.contains("Last reboot: 3 hour(s) ago"))
        assertFalse(text.contains("Battery health"))
        assertFalse(text.contains("Missing permissions"))
    }

    @Test fun `every unreadable value prints unknown`() {
        val text = formatPhoneHealth(PhoneSnapshot())
        assertTrue(text.contains("Battery: unknown"))
        assertTrue(text.contains("Power saver: unknown"))
        assertTrue(text.contains("Network: unknown"))
        assertTrue(text.contains("SIM: unknown"))
        assertTrue(text.contains("Free storage: unknown"))
        assertTrue(text.contains("Last reboot: unknown"))
    }

    @Test fun `on battery says so`() {
        val text = formatPhoneHealth(healthy.copy(battery = BatteryReading(12, PlugType.NONE, ChargeState.DISCHARGING, "GOOD", 30f)))
        assertTrue(text.contains("Battery: 12%, on battery, not charging"))
    }

    @Test fun `plugged in but not charging is called out`() {
        val text = formatPhoneHealth(healthy.copy(battery = BatteryReading(12, PlugType.USB, ChargeState.DISCHARGING, "GOOD", 30f)))
        assertTrue(text.contains("Battery: 12%, plugged in (USB), NOT charging"))
    }

    @Test fun `abnormal health or heat adds a battery health line`() {
        val overheated = formatPhoneHealth(healthy.copy(battery = BatteryReading(63, PlugType.AC, ChargeState.CHARGING, "OVERHEAT", 47.5f)))
        assertTrue(overheated.contains("Battery health: OVERHEAT, 47.5 C"))
        val hot = formatPhoneHealth(healthy.copy(battery = BatteryReading(63, PlugType.AC, ChargeState.CHARGING, "GOOD", 46.0f)))
        assertTrue(hot.contains("Battery health: GOOD, 46.0 C"))
    }

    @Test fun `trouble states are spelled out`() {
        val text =
            formatPhoneHealth(
                healthy.copy(
                    powerSaver = true,
                    dozing = true,
                    batteryOptimizationExempt = false,
                    backgroundRestricted = true,
                    network = NetworkKind.NONE,
                    airplaneMode = true,
                    simState = "absent",
                    missingPermissions = listOf("SMS receive", "Notifications"),
                ),
            )
        assertTrue(text.contains("Power saver: ON"))
        assertTrue(text.contains("Doze (deep sleep): yes"))
        assertTrue(text.contains("Battery optimization: NOT exempt"))
        assertTrue(text.contains("Background restricted: YES"))
        assertTrue(text.contains("Network: none"))
        assertTrue(text.contains("Airplane mode: ON"))
        assertTrue(text.contains("SIM: absent"))
        assertTrue(text.contains("Missing permissions: SMS receive, Notifications"))
    }

    @Test fun `a network that is not validated is flagged`() {
        val text = formatPhoneHealth(healthy.copy(network = NetworkKind.CELLULAR, networkValidated = false))
        assertTrue(text.contains("Network: cellular, internet NOT validated"))
    }

    @Test fun `reboot time scales from minutes to days`() {
        assertTrue(formatPhoneHealth(healthy.copy(uptimeMs = 5 * 60_000L)).contains("Last reboot: 5 minute(s) ago"))
        assertTrue(formatPhoneHealth(healthy.copy(uptimeMs = 72L * 60 * 60_000)).contains("Last reboot: 3 day(s) ago"))
    }

    @Test fun `the block carries nothing identifying`() {
        val text = formatPhoneHealth(healthy).lowercase()
        listOf("ssid", "imei", "carrier", "latitude", "longitude").forEach { assertFalse(text.contains(it)) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.scifsidekick.cleanroom.PhoneHealthFormatTest"`
Expected: FAIL (unresolved reference `PhoneSnapshot`)

- [ ] **Step 3: Write minimal implementation**

`PhoneHealth.kt`: enums, `BatteryReading`, `PhoneSnapshot`, `formatPhoneHealth`, `readBatteryReading`, `readPhoneSnapshot`. Full code is in the file (formatter lines match the test strings above exactly; each system read in `readPhoneSnapshot` is wrapped in `runCatching { ... }.getOrNull()`; battery from the sticky `ACTION_BATTERY_CHANGED` intent; network from `ConnectivityManager.activeNetwork` capabilities; SIM from `TelephonyManager.simState`; storage from `StatFs(context.filesDir.path)`; uptime from `SystemClock.elapsedRealtime()`; missing permissions from `checkSelfPermission` for `RECEIVE_SMS` and, on API 33+, `POST_NOTIFICATIONS`).

In `AppGraph.statusSummary`, append the block:

```kotlin
val base = repository.buildStatusSummary(gmailAvailable, nowMs, others, gmailIsMember = gmailEntry != null)
return base + "\n" + formatPhoneHealth(readPhoneSnapshot(app))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.scifsidekick.cleanroom.PhoneHealthFormatTest"`
Expected: PASS (9 tests)

---

### Task 3: Settings, state, migration, backup

**Files:**
- Modify: `app/src/main/java/com/scifsidekick/cleanroom/data/Database.kt` (entities, `StateDao`, version 19, migration)
- Modify: `app/src/main/java/com/scifsidekick/cleanroom/data/SidekickRepository.kt` (`recordBatteryAlertsFired`, backup export and import)
- Create: `app/src/androidTest/java/com/scifsidekick/cleanroom/Migration18To19InstrumentedTest.kt`
- Generated: `app/schemas/.../19.json` (written by the build; commit it)

**Interfaces:**
- Produces:
  - `AppSettingsEntity.batteryAlertsEnabled: Boolean = false`, `batteryAlertThresholdPercent: Int = 20`, `batteryAlertStepPercent: Int = 5`, `batteryAlertMaxCount: Int = 3`
  - `ForwardingStateEntity.batteryAlertsFired: Int = 0`
  - `StateDao.updateBatteryAlertsFired(count: Int)`
  - `SidekickRepository.recordBatteryAlertsFired(count: Int)`
  - `SidekickDatabase.MIGRATION_18_19`

- [ ] **Step 1: Write the failing instrumented test** (`Migration18To19InstrumentedTest`, same shape as `Migration16To17InstrumentedTest`): create v18, insert an `app_settings` row and a `forwarding_state` row, run `MIGRATION_18_19`, assert the four settings default to `0, 20, 5, 3` and `batteryAlertsFired` is `0`.
- [ ] **Step 2: Verify it fails to compile** (no `MIGRATION_18_19`). Instrumented tests need the emulator; if none is running, compile only with `./gradlew :app:compileDebugAndroidTestKotlin`.
- [ ] **Step 3: Implement.** Add the columns to the entities (with explanatory comments in the file's style), bump `version = 19`, add `MIGRATION_18_19` (five `ALTER TABLE ... ADD COLUMN ... NOT NULL DEFAULT` statements) to `addMigrations`, add the DAO query `UPDATE forwarding_state SET batteryAlertsFired = :count WHERE id = 1`, add `recordBatteryAlertsFired`, export the four settings in `exportBackup`, and import them in `importBackup` with `optBoolean`/`optInt` defaulting to `current.*` so a backup predating them leaves the current values. `batteryAlertsFired` is not exported.
- [ ] **Step 4: Build** with `./gradlew :app:assembleDebug` and confirm `app/schemas/<db>/19.json` was generated.

---

### Task 4: Battery alert worker

**Files:**
- Create: `app/src/main/java/com/scifsidekick/cleanroom/service/BatteryAlertWorker.kt`
- Modify: `app/src/main/java/com/scifsidekick/cleanroom/ScifSidekickApp.kt:27`

**Interfaces:**
- Consumes: `evaluateBatteryAlert`, `readBatteryReading`, `recordBatteryAlertsFired`, `AppGraph.statusSummary`, `repository.enqueueSystemEmail`
- Produces: `BatteryAlertWorker.schedule(context: Context)`

- [ ] **Step 1:** Implement `BatteryAlertWorker` as a 15-minute `PeriodicWorkRequest` with `NetworkType.CONNECTED`, unique name `battery_alert`, `ExistingPeriodicWorkPolicy.KEEP`. `doWork` follows the spec's steps 1 to 7: return when disabled or when heartbeat recipients are empty; read the battery (return if null); read `batteryAlertsFired` from `stateDao().get()`; call `evaluateBatteryAlert`; persist `newFiredCount` when it differs; on send, queue the email (subject `SCIF Sidekick battery low - <level>%`, body naming the alert number and level plus `statusSummary(now)`), advance the counter only if `enqueueSystemEmail` returned non-null, record a service event, then drain the email queue with `suspendRunCatching` and log a drain failure.
- [ ] **Step 2:** Add `BatteryAlertWorker.schedule(this)` after `HeartbeatEmailWorker.schedule(this)` in `ScifSidekickApp.onCreate`.
- [ ] **Step 3:** `./gradlew :app:assembleDebug` compiles.

---

### Task 5: Settings UI

**Files:**
- Modify: `app/src/main/java/com/scifsidekick/cleanroom/ui/SettingsScreens.kt` (new card after the Heartbeat card, before "Confirm replies by email")

- [ ] **Step 1:** Add an `OutlinedCard` titled "Battery alerts" with a switch, three `IntField`s (labels carry the valid range), a preview line built from `batteryAlertLevels(...)` such as `Alerts at 20%, 15%, 10%`, explanatory text (alerts go to the heartbeat recipients; a dead phone cannot report its own death, so set the first alert early), and a warning line when `settings.heartbeatRecipientsJson` has no addresses. `onChange` stores the typed value with `coerceIn(0, 100)` only, so the text is never rewritten mid-typing; ranges are enforced at use by `batteryAlertLevels`.
- [ ] **Step 2:** `./gradlew :app:assembleDebug` compiles.

---

### Task 6: Release 1.30.0 and docs

**Files:**
- Modify: `app/build.gradle.kts:32-33` (versionCode 61, versionName "1.30.0"), `app/src/main/java/com/scifsidekick/cleanroom/ui/AboutScreen.kt` (new `ChangelogEntry` first), `CHANGELOG.md`, `docs/CHANGELOG_DETAILED.md`, `docs/DESIGN_NOTES.md:12`, `README.md` (badge, feature row, setup row), `docs/TEST_PLAN.md` (new section 19)
- Modify: `docs/superpowers/specs/2026-10-14-phone-health-and-battery-alerts-design.md` (two refinements made while planning: settings ranges are enforced where used, not on write; "charging" means actually charging, with plugged-in-but-discharging still alerting)

- [ ] **Step 1:** Make the edits above. TEST_PLAN section 19 lists the real-device checks: Phone block in a heartbeat and a `[SCIF:STATUS]` reply; drain or simulate the S24 to the threshold and confirm each alert and its count; no alert while charging; unplugging at a low level does not repeat an alert; alerts arrive with forwarding off; settings survive a backup and restore.
- [ ] **Step 2:** Run the full JVM suite: `./gradlew :app:testDebugUnitTest` (all green), then `./gradlew :app:assembleDebug`.
- [ ] **Step 3:** Commit once (no push):

```bash
git add -A
git commit -m "Add phone health to status reports and configurable battery alerts"
```
