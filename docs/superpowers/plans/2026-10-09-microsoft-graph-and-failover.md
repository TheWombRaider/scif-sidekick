# Microsoft Graph Transport and Failover Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Microsoft Graph (Outlook.com / Microsoft 365) as a second mail provider behind `MailTransport`, plus a `MailRouter` that fails over between Gmail and Outlook, with sign-in and account controls in the app.

**Architecture:** Device-code OAuth (no new dependency) feeds a `GraphGateway : MailTransport`. A `MailRouter : MailTransport` wraps the connected transports and becomes `AppGraph.mail`. Sender authentication is per provider and fails closed. Settings and Home get Microsoft account UI.

**Tech Stack:** Kotlin, Android (Compose), OkHttp 4.12 (existing), org.json (existing), Android Keystore, WorkManager, JUnit4.

**Spec:** `docs/superpowers/specs/2026-10-09-microsoft-graph-and-failover-design.md` (step 1 spec: `docs/superpowers/specs/2026-10-08-mail-transport-interface-design.md`)

## Global Constraints

- **No new Gradle dependency.** `gradle/verification-metadata.xml` and `app/gradle.lockfile` must not change.
- **Gmail-only installs behave exactly as 1.28.1.** Gmail-visible log and History text stays word for word (use `displayName`).
- **No database schema change, no migration.** Do not rename Room columns or DAO methods. Account settings live in SharedPreferences.
- **Fail closed.** `MailMessage.authenticatedFromAddress` is non-null only when the provider's own evidence shows an aligned DMARC pass. Unknown or ambiguous means null.
- **Ids are provider-scoped** via `MailIds`: Gmail ids unprefixed, others `<providerId>:<native id>`; this includes `MailMessage.id`, `MailMessage.threadId` for non-Gmail, `MailReceipt.messageId`, `MailReceipt.threadId` for non-Gmail, `BounceNotice.messageId`, and ids passed to `markRead` and `fetchContent`.
- **Instrumented tests run on the emulator only.** Before any `connected*` Gradle task: `export ANDROID_SERIAL=emulator-5554` and confirm `adb devices`. Implementers must NOT run `connected*` tasks or use adb; the controller runs the emulator suite. (The owner's physical phone is on Wi-Fi debugging and running instrumented tests there wipes its data.)
- **Never `git add -A` or `git add .`.** Add files by explicit path. `.superpowers/` and `.claude/` stay uncommitted (excluded via `.git/info/exclude`).
- **Commits:** author already configured (TheWombRaider noreply). No `Co-Authored-By` or other trailers. Use `TZ=UTC git commit`. Do not push (the controller pushes).
- **Line endings:** existing files are mixed CRLF/LF in the working tree; preserve each file's own endings (use sed or Python; the Edit tool may fail). New files may be LF.
- **Build environment (Git Bash):** `export JAVA_HOME="C:\\Program Files\\Android\\Android Studio\\jbr" ANDROID_HOME="C:\\Users\\austi\\AppData\\Local\\Android\\Sdk"`
- **Unit check:** `./gradlew testDebugUnitTest --console=plain -q`. **Full check:** `./gradlew testDebugUnitTest assembleDebug lintDebug compileDebugAndroidTestKotlin --console=plain` (run the final one without `-q` and report new warnings).
- **Working directory:** `C:\Users\austi\Projects\scif-graph` (`/c/Users/austi/Projects/scif-graph`), a git worktree on branch `feature/microsoft-graph`.
- **Paths:** `MAIN` = `app/src/main/java/com/scifsidekick/cleanroom`, `UNIT` = `app/src/test/java/com/scifsidekick/cleanroom`, `INSTR` = `app/src/androidTest/java/com/scifsidekick/cleanroom`, `SHARED` = `app/src/sharedTest/java/com/scifsidekick/cleanroom` (compiled into both unit and instrumented tests).
- **Packages:** Graph code lives in `com.scifsidekick.cleanroom.email.graph` (`MAIN/email/graph/`).
- **No tokens in logs.** Never log access or refresh tokens, device codes, or message bodies.
- **Provider constants:** Gmail `providerId = "gmail"`, `displayName = "Gmail"`. Graph `providerId = "graph"`, `displayName = "Outlook"`.

## Review Focus

1. **A forged lower `Authentication-Results` header must not authenticate anyone.** Only the first header counts; a failing first header with a passing second one is null. (Task 4.)
2. **Sending through the fallback must not repeat a forward the preferred provider already sent, and must not drop one.** `findSent` across all members precedes any send when `verifyPriorDelivery`; ambiguous failures verify before failing over. (Task 2.)
3. **One mailbox needing reconnection must not hide or clear the other's alert, nor stop the other from working.** (Tasks 1, 2, 6.)
4. **A refresh-token write must never leave the store with a half-written value, and a tampered stored token must read as "signed out", not crash.** (Task 3.)
5. **Read mail must still be returned by `pollReplies` on every transport** (the old `is:unread` silent-failure bug). (Contract, Tasks 1 and 5.)
6. **Disconnecting Microsoft removes the token, the key and the cached address, and leaves Gmail untouched.** (Tasks 3 and 6.)

---

## Task 1: Interface follow-ups

Harden the interface for two providers before any new code depends on it.

**Files:**
- Modify: `MAIN/email/MailTransport.kt`, `MAIN/email/MailTypes.kt`, `MAIN/email/MailAuthRequiredException.kt`, `MAIN/email/GmailOAuthManager.kt` (the exception class at the end), `MAIN/email/GmailGateway.kt`, `MAIN/service/AlertNotifier.kt`, `MAIN/service/ForwardingService.kt`, `MAIN/service/QueueProcessor.kt`, `MAIN/AppGraph.kt`, `MAIN/ui/MainViewModel.kt` (only if it calls the alert functions)
- Modify tests: `SHARED/FakeMailTransport.kt`, `UNIT/MailTransportContract.kt`, `UNIT/FakeMailTransportContractTest.kt`, `UNIT/MailTypesTest.kt`
- Create: `UNIT/CommandSearchTest.kt`

**Interfaces:**
- Produces:
  - `MailTransport.findSent(deliveryKey: String): MailReceipt?`: returns the already-accepted message for this delivery key (receipt with `reconciled = true`) or null. Never sends. Empty/null when signed out.
  - `open class MailAuthRequiredException(message: String, val providerId: String, val displayName: String) : Exception(message)`.
  - `ReauthorizationRequiredException : MailAuthRequiredException("Open the app and reconnect Gmail", "gmail", "Gmail")`.
  - `AlertNotifier.showAuthorizationRequired(providerId: String = "gmail", displayName: String = "Gmail")` and `AlertNotifier.clearAuthorizationRequired(providerId: String = "gmail")`. Notification id is `AUTHORIZATION_NOTIFICATION_ID + providerOffset` where `providerOffset` is 0 for gmail, 1 for graph, 2 for anything else.
  - `open class MailHttpException(val statusCode: Int, detail: String) : Exception(detail)`; `GmailApiException(statusCode, detail)` extends it with the same message text it has today ("Gmail API HTTP $statusCode: $detail").
  - `CommandSearch.init { require(tags.isNotEmpty()); require(senders.isNotEmpty()) }`.
  - `AppGraph`: `@Volatile internal var mail: MailTransport` (initialised to `gmail`).
  - Contract additions (all in `MailTransportContract`): a returned id is scoped (`MailIds.providerOf(id) == transport.providerId`) for `pollReplies`, `findCommands` and `send` receipts (`messageId`); `findSent` returns null before a send and the reconciled receipt after; `findSent` is null when signed out; a vouched sender (`authenticatedFrom` supplied by the harness) is authenticated.
  - `MailTransportContract.Harness.deliver(...)` now returns the scoped id (`String`) of the delivered message, and the tests use the returned id.

- [ ] **Step 1: Write the failing tests.** In `UNIT/CommandSearchTest.kt`:

```kotlin
package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.email.CommandSearch
import org.junit.Assert.assertThrows
import org.junit.Test

class CommandSearchTest {
    @Test fun `empty tags are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { CommandSearch(emptyList(), listOf("a@example.com")) }
    }

    @Test fun `empty senders are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { CommandSearch(listOf("[SCIF:ON]"), emptyList()) }
    }
}
```

Extend `UNIT/MailTypesTest.kt` with: `ReauthorizationRequiredException().providerId == "gmail"` and `.displayName == "Gmail"`; and `GmailApiException(404, "x")` is a `MailHttpException` with `statusCode == 404` and message `"Gmail API HTTP 404: x"`.

Extend `UNIT/MailTransportContract.kt` with the contract additions above (write them so that they fail against the current fake). Run: `./gradlew testDebugUnitTest --console=plain -q` and confirm the new tests fail (compile errors for the new members count as the expected RED).

- [ ] **Step 2: Implement.**
  1. `MailTransport.findSent` with KDoc. Add KDoc to the interface stating ids are provider-scoped per `MailIds` (already present for ids; extend to `threadId` and `BounceNotice.messageId`).
  2. `MailAuthRequiredException` takes the two new constructor params; `ReauthorizationRequiredException` passes them. Update every `catch`/construction site that builds a `MailAuthRequiredException` directly (search: `MailAuthRequiredException(`), including `FakeMailTransport`.
  3. `MailHttpException` in `MailAuthRequiredException.kt`'s package as `MailHttpException.kt`; `GmailApiException` extends it (keep its public constructor shape).
  4. `GmailGateway.findSent(deliveryKey)`: `if (!oauth.isAuthorized || debug.fakeEmailTransport) return null`; compute `MimeMessageBuilder.rfcMessageId(deliveryKey)`; return the result of the existing `findSentByRfcMessageId(token, rfc)` (it already returns a reconciled `MailReceipt?`).
  5. `AlertNotifier`: per-provider id and text. For gmail the text stays exactly as it is today ("Reconnect Gmail", ...). For other providers substitute `displayName`. Change the callers (`ForwardingService`, `QueueProcessor`) to pass the exception's `providerId` and `displayName`; `clearAuthorizationRequired` is called with the provider that just succeeded (Gmail-only today: keep calling it with `"gmail"` exactly where it is called now).
  6. `CommandSearch` init checks.
  7. `AppGraph.mail` becomes the `@Volatile internal var`. All other code still reads `graph.mail`.
  8. `FakeMailTransport`: `deliver` scopes ids with `MailIds.scoped(providerId, id)` and returns the scoped id; `send` receipts use scoped `messageId`; implement `findSent`; `markRead`/`fetchContent` look up by scoped id. `FakeMailTransportContractTest`'s harness returns the scoped id from `deliver`.
- [ ] **Step 3: Run the full check** and confirm green; confirm the Gmail text of the reconnect notification is unchanged (diff of the two string literals).
- [ ] **Step 4: Commit** `git add app/src docs` is not allowed; add explicit paths for the files changed, then `TZ=UTC git commit -m "Prepare MailTransport for a second provider: findSent, auth exception provider, scoped contract"`.

---

## Task 2: MailRouter

**Files:**
- Create: `MAIN/email/MailRouter.kt`
- Create: `UNIT/MailRouterTest.kt`
- Modify: `MAIN/AppGraph.kt` (build a router over Gmail only; `mail` = router)

**Interfaces:**
- Consumes: Task 1's `findSent`, `MailAuthRequiredException(providerId, displayName)`, `MailHttpException`, `MailIds`.
- Produces:

```kotlin
class MailRouter(
    private val members: () -> List<MailTransport>,
    private val onAuthRequired: (MailAuthRequiredException) -> Unit = {},
    private val onRecovered: (providerId: String) -> Unit = {},
    private val logPossibleDuplicate: suspend (String) -> Unit = {},
) : MailTransport {
    override val providerId: String get() = "router"
    override val displayName: String get() = "mail"
    // everything else per the spec's Router section
}
```

`members()` returns the transports in preference order (preferred first), whether or not they are available; the router filters on `isAvailable` itself, every call.

Failure classification (private to the router, but write it exactly):

```kotlin
private enum class Kind { AUTH, DEFINITE, AMBIGUOUS }

private fun classify(failure: Throwable): Kind =
    when (failure) {
        is MailAuthRequiredException -> Kind.AUTH
        is MailHttpException -> if (failure.statusCode in 500..599) Kind.AMBIGUOUS else Kind.DEFINITE
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.NoRouteToHostException -> Kind.DEFINITE
        is java.io.IOException -> Kind.AMBIGUOUS
        else -> Kind.AMBIGUOUS
    }
```

`send` algorithm:

```kotlin
override suspend fun send(payload: EmailPayload, attachmentPaths: List<String>, deliveryKey: String, verifyPriorDelivery: Boolean): MailReceipt {
    val usable = members().filter { it.isAvailable }
    if (usable.isEmpty()) throw MailAuthRequiredException("No mail account is connected", providerId, displayName)
    if (verifyPriorDelivery) {
        for (member in usable) {
            val found = runCatching { member.findSent(deliveryKey) }.getOrNull()
            if (found != null) return found.copy(reconciled = true)
        }
    }
    var firstFailure: Throwable? = null
    for (member in usable) {
        try {
            val receipt = member.send(payload, attachmentPaths, deliveryKey, verifyPriorDelivery = false)
            onRecovered(member.providerId)
            return receipt
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (firstFailure == null) firstFailure = failure
            when (classify(failure)) {
                Kind.AUTH -> onAuthRequired(failure as MailAuthRequiredException)
                Kind.DEFINITE -> Unit
                Kind.AMBIGUOUS -> {
                    val found = runCatching { member.findSent(deliveryKey) }
                    if (found.getOrNull() != null) return found.getOrThrow()!!.copy(reconciled = true)
                    if (found.isFailure) logPossibleDuplicate("${member.displayName} send was ambiguous and could not be verified; trying the next account (possible duplicate)")
                }
            }
        }
    }
    throw firstFailure!!
}
```

(`firstFailure!!` is safe because `usable` is non-empty and every path that does not return or throw records a failure.)

- [ ] **Step 1: Write failing tests** in `UNIT/MailRouterTest.kt` using `FakeMailTransport` instances named `gmail` (providerId `gmail`, displayName `Gmail`) and `graph` (providerId `graph`, displayName `Outlook`). Test cases (each its own `@Test`, using `runBlocking`):
  1. single member behaves as a pass-through for `send`, `pollReplies`, `findCommands`, `markRead`, `accountEmail`.
  2. preferred member is used first; the other is used only after a failure.
  3. preferred signed out (`isAvailable` false) -> the fallback sends, no call reaches the signed-out member.
  4. preferred throws `MailAuthRequiredException` -> `onAuthRequired` is called with that exception once, the fallback sends.
  5. preferred throws `MailHttpException(400)` (definite) -> the fallback sends, `findSent` NOT called on the preferred, no duplicate log.
  6. preferred throws `java.net.SocketTimeoutException` (ambiguous) and its `findSent` finds the message -> returns that receipt reconciled, the fallback is NOT asked to send.
  7. ambiguous and `findSent` returns null -> the fallback sends.
  8. ambiguous and `findSent` throws -> the fallback sends and `logPossibleDuplicate` is called once.
  9. `verifyPriorDelivery = true` and only the fallback has the message -> returned reconciled, no member sends.
  10. every member fails -> the first member's failure is rethrown.
  11. `pollReplies` concatenates; one member throwing `IOException` is skipped; all throwing rethrows the first.
  12. `findCommands` interleaves round-robin starting with the preferred (A1,B1,A2,B2...), `unreadable` concatenated.
  13. `markRead` and `fetchContent` route by `MailIds.providerOf`; an unknown provider prefix throws `IllegalArgumentException`.
  14. `isAvailable` is true if any member is; false if none; `accountEmail` is the first available member's.
  15. `onRecovered("graph")` is called after a successful send through graph.
  Add to `FakeMailTransport` (in `SHARED`) the test controls these need: `var failNextSendWith` already exists; add `var pollFailure: Exception?` and `var sendCount` (number of `send` calls, including failed ones) and `var findSentFailure: Exception?`; keep it small and keep existing behaviors.
- [ ] **Step 2: Run, see RED.**
- [ ] **Step 3: Implement `MailRouter`** exactly per the spec's Router section and the code above for `send`. `pollReplies`/`findCommands`/`checkForBounces` run members sequentially; each failure is recorded with `onAuthRequired` when it is a `MailAuthRequiredException`, skipped otherwise; if every usable member failed rethrow the first failure; if some succeeded call `onRecovered(providerId)` for the ones that did. `findSent`: first non-null across usable members. `clearSession`: all members (including unavailable). Id routing: `members().firstOrNull { it.providerId == MailIds.providerOf(id) } ?: throw IllegalArgumentException("No mail account for id $id")`.
- [ ] **Step 4: Wire into `AppGraph`:** `val mailRouter = MailRouter(members = { listOf(gmail) }, onAuthRequired = { alerts.showAuthorizationRequired(it.providerId, it.displayName) }, onRecovered = { alerts.clearAuthorizationRequired(it) })` and `internal var mail: MailTransport = mailRouter`. Careful: `ForwardingService` already catches `MailAuthRequiredException` and shows the alert itself; with a router that swallows partial failures it will only see the exception when every member failed, which is the same single-provider behavior as today. Verify no duplicate alert logic changes Gmail-only behavior (the notification text and id for Gmail are unchanged).
- [ ] **Step 5: Run the full check; commit** `"Add MailRouter with failover and wire it over Gmail"`.

---

## Task 3: Microsoft sign-in (device code) and token storage

**Files (all new):**
- `MAIN/email/graph/RefreshTokenStore.kt`
- `MAIN/email/graph/KeystoreRefreshTokenStore.kt`
- `MAIN/email/graph/MsOAuthManager.kt`
- `MAIN/email/graph/MsAccountPreferences.kt`
- `UNIT/MsOAuthManagerTest.kt`, `INSTR/KeystoreRefreshTokenStoreInstrumentedTest.kt`
- `SHARED/InMemoryRefreshTokenStore.kt`

**Interfaces:**
- Produces:

```kotlin
interface RefreshTokenStore {
    fun read(): String?     // null when absent, undecryptable or tampered (never throws)
    fun write(token: String)
    fun clear()
}

data class DeviceCode(
    val userCode: String,
    val verificationUri: String,
    val deviceCode: String,
    val expiresInSec: Int,
    val intervalSec: Int,
    val message: String,
)

sealed interface DeviceCodeResult {
    data class Connected(val accountEmail: String?) : DeviceCodeResult
    data class Failed(val reason: String) : DeviceCodeResult
    data object Cancelled : DeviceCodeResult
}

class MsOAuthManager(
    private val clientId: () -> String?,
    private val store: RefreshTokenStore,
    private val client: OkHttpClient,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val delayMs: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    val isAuthorized: Boolean                    // store has a token
    suspend fun startDeviceCode(): DeviceCode    // throws IllegalStateException("Enter your Microsoft app ID first") when clientId is blank; IOException on network failure
    suspend fun awaitDeviceCode(code: DeviceCode): DeviceCodeResult  // polls; stores the refresh token on success; honors coroutine cancellation by returning Cancelled
    suspend fun freshAccessToken(): String       // cached until 60 s before expiry; refreshes under a Mutex; MailAuthRequiredException(providerId="graph", displayName="Outlook") when no token or invalid_grant
    fun clearAccessToken()                       // forget the cached access token (called on 401)
    fun disconnect()                             // store.clear() + forget access token
}

class MsAccountPreferences(context: Context) {
    var clientId: String
    var accountEmail: String?        // cached for display and seeding
    var preferredProvider: String    // "gmail" (default) or "graph"
    var graphSeeded: Boolean         // remote-control seeding done once
    fun clearAccount()               // email + seeded flag; keeps clientId and preferredProvider
}
```

- Constants in `MsOAuthManager.companion`: `AUTHORITY = "https://login.microsoftonline.com/common/oauth2/v2.0"`, `SCOPES = "offline_access User.Read Mail.ReadWrite Mail.Send"`.

`KeystoreRefreshTokenStore(context)` details: key alias `scif_ms_refresh_v1`, `KeyGenParameterSpec` AES-256-GCM, `setRandomizedEncryptionRequired(true)`; ciphertext stored in SharedPreferences `ms_account_v1` key `rt` as `base64(iv) + "." + base64(ciphertext)` with a fresh 12-byte IV per write (the Keystore generates it); `read()` returns null on any exception (`AEADBadTagException`, missing key, malformed). `write` writes with `commit()` and only then returns; `clear()` removes the pref and deletes the key entry.

- [ ] **Step 1: Write failing unit tests** in `UNIT/MsOAuthManagerTest.kt` with a canned OkHttp client (an `Interceptor` returning scripted `Response`s; helper `fun scripted(vararg responses: Pair<Int, String>): OkHttpClient`) and `InMemoryRefreshTokenStore`. Cases: `startDeviceCode` parses a documented response; blank client id throws `IllegalStateException`; `awaitDeviceCode`: pending, pending, success stores the refresh token and returns `Connected` and does not poll faster than `interval` (assert the recorded `delayMs` calls); `slow_down` adds 5 s to subsequent delays; `expired_token` -> `Failed("The code expired...")`; `authorization_declined` / `access_denied` -> `Failed`; malformed JSON -> `Failed`; cancellation -> `Cancelled`; `freshAccessToken`: no token -> `MailAuthRequiredException` with `providerId == "graph"`; success caches (second call makes no HTTP request); refresh rotation stores the new refresh token; `invalid_grant` -> `MailAuthRequiredException` AND the stored token is cleared; network failure (`IOException`) is NOT an auth exception and does not clear the token; concurrent callers (launch 5 coroutines) cause exactly one refresh request. `INSTR/KeystoreRefreshTokenStoreInstrumentedTest.kt`: round trip; two writes use different IVs; flipping one stored byte makes `read()` return null; `clear()` makes `read()` null and a later `write` works (key regenerated).
- [ ] **Step 2: See RED. Step 3: Implement.** Request bodies are `application/x-www-form-urlencoded` (use `FormBody`). Parse with `org.json`. Never log token values. Request timeouts: reuse the OkHttp client passed in.
- [ ] **Step 4: Full check; commit** `"Add Microsoft device-code sign-in and Keystore-encrypted token storage"`. (Do not run the instrumented test; the controller does.)

---

## Task 4: GraphAuthentication

**Files:** Create `MAIN/email/graph/GraphAuthentication.kt`, `UNIT/GraphAuthenticationTest.kt`.

**Interfaces:**
- Produces: `object GraphAuthentication { fun authenticatedFrom(fromHeader: String, internetMessageHeaders: List<Pair<String, String>>): String? }` where the list is the `(name, value)` pairs from Graph's `internetMessageHeaders` in message order.

Exact behavior:

```kotlin
object GraphAuthentication {
    private val dmarcClause = Regex("(?:^|;)\\s*dmarc=(\\w+)\\b([^;]*)", RegexOption.IGNORE_CASE)
    private val headerFrom = Regex("\\bheader\\.from=([^;\\s]+)", RegexOption.IGNORE_CASE)

    fun authenticatedFrom(fromHeader: String, internetMessageHeaders: List<Pair<String, String>>): String? {
        val address = ComposeAuthorization.extractAddress(fromHeader) ?: return null
        val first = internetMessageHeaders.firstOrNull { it.first.equals("Authentication-Results", ignoreCase = true) }?.second
            ?: return null
        val unfolded = first.replace(Regex("\\r?\\n[ \\t]+"), " ").trim()
        val clauses = dmarcClause.findAll(unfolded).toList()
        if (clauses.size != 1) return null
        val clause = clauses.single()
        if (!clause.groupValues[1].equals("pass", ignoreCase = true)) return null
        val asserted = headerFrom.find(clause.groupValues[2])?.groupValues?.get(1)
            ?.trim()?.trimEnd('.')?.lowercase(Locale.US) ?: return null
        val fromDomain = address.substringAfter('@').trimEnd('.').lowercase(Locale.US)
        return address.takeIf { fromDomain == asserted }
    }
}
```

Notes for the implementer: `extractAddress` is the same helper `GmailAuthentication` uses (it already rejects multiple addresses and header-injection characters; confirm by reading it, and add the explicit tests below rather than trusting that). `header.from` is searched only inside the matched `dmarc=` clause so a `header.from` belonging to an `spf` or `dkim` clause cannot satisfy it.

- [ ] **Step 1: Write failing tests** (each its own `@Test`; `from = "Boss <boss@agency.gov>"`, `pass = "spf=pass smtp.mailfrom=agency.gov; dkim=pass header.d=agency.gov; dmarc=pass action=none header.from=agency.gov; compauth=pass reason=100"`): aligned pass returns `boss@agency.gov`; `dmarc=fail` null; `dmarc=none` null; no header null; header name case-insensitive (`authentication-results`) accepted; the **first** header failing and a second passing -> null; the first passing and a second failing -> the address (first wins, ignoring later); `header.from=mail.agency.gov` vs `From` at `agency.gov` -> null (no relaxed alignment) and the reverse -> null; `header.from` appearing only in the `spf=` clause -> null; two `dmarc=` clauses -> null (even if both pass); `dmarc=pass` appearing inside a quoted reason of another clause (`dkim=fail reason="dmarc=pass header.from=agency.gov"`) -> null; folded header across lines parses; `From` with two addresses -> null; `From` with `\r\nX: y` injected -> null; trailing-dot and mixed-case domains compare equal; empty header value -> null; very long header (100 KB) does not take noticeable time (assert < 200 ms) and returns null when no dmarc clause.
- [ ] **Step 2: RED. Step 3: Implement as above, adjust only if a test demands. Step 4: Full check; commit** `"Add fail-closed sender authentication for Microsoft Graph mail"`.

---

## Task 5: GraphGateway and FakeGraphServer

**Files:**
- Create: `MAIN/email/graph/GraphGateway.kt`, `MAIN/email/graph/GraphApiException.kt`
- Create: `SHARED/FakeGraphServer.kt` (an OkHttp `Interceptor` implementing the Graph subset below over an in-memory mailbox, plus control methods)
- Create: `UNIT/GraphGatewayTest.kt`, `UNIT/GraphTransportContractTest.kt`

**Interfaces:**
- Consumes: `MsOAuthManager` (Task 3), `GraphAuthentication` (Task 4), `MimeMessageBuilder.build`, `MailIds`, the Task 1 interface.
- Produces:

```kotlin
class GraphApiException(statusCode: Int, detail: String) : MailHttpException(statusCode, "Graph API HTTP $statusCode: $detail")

class GraphGateway(
    private val oauth: MsOAuthManager,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val log: suspend (String) -> Unit = {},
) : MailTransport {
    override val providerId = "graph"
    override val displayName = "Outlook"
    override val isAvailable: Boolean get() = oauth.isAuthorized
    // ... methods per the spec's table
}
```

Behavior specifics beyond the spec's table:
- `execute(request)` flow: get `oauth.freshAccessToken()`, add `Authorization: Bearer`, run on `Dispatchers.IO`; on 401 call `oauth.clearAccessToken()` and retry exactly once (a second 401 -> `MailAuthRequiredException("Open the app and reconnect Outlook", "graph", "Outlook")`); on 429/503 read `Retry-After` (seconds, capped to 60) and retry once after the wait; any other non-2xx -> `GraphApiException(code, body.take(1_000))`. Response bodies are read with a 2 MB cap.
- `send`: `MimeMessageBuilder.build(payload, attachmentPaths, deliveryKey, fromAddress = accountEmail())`; convert its `rawBase64Url` to standard base64 (decode with `base64UrlDecodeBytes`, encode with `android.util.Base64.NO_WRAP`; in JVM unit tests use `java.util.Base64` through a small internal helper so the unit tests do not need Robolectric); `POST /me/messages` body = that base64 text with `Content-Type: text/plain`; parse `id` and `internetMessageId`; `POST /me/messages/{id}/send` (expects 202, empty body). The receipt: `messageId = MailIds.scoped("graph", id)`, `threadId = MailIds.scoped("graph", conversationId ?: id)`, `rfcMessageId = internetMessageId.trim('<','>')`. If that differs from `MimeMessageBuilder.rfcMessageId(deliveryKey)`, call `log("Outlook replaced the Message-ID ...")` once per process (a `@Volatile` flag); the returned id is still the one used.
- `findSent`: `$filter=internetMessageId eq '<id>'` where the single quote in the id is escaped by doubling; the angle brackets are part of the stored value, so try `<id>` first. Returns the receipt with `reconciled = true`; null when the list is empty.
- Polling cursors are in-memory: `lastPollMs`, `lastFullSweepMs`; `FULL_SWEEP_INTERVAL_MS = 6h`; overlap 10 min; max 4 pages of 50.
- `pollReplies` keeps messages whose subject contains `SCIF` or `TEXT` (case-insensitive) and whose scoped id is not in `knownMessageIds`; each kept message gets metadata via `GET /me/messages/{id}?$select=internetMessageHeaders,from,subject,conversationId,internetMessageId` (per-message failure is added to `fetchFailures`, a `MailAuthRequiredException` aborts the poll). `referencedMessageIds` is built from the `References` and `In-Reply-To` header values with the same `<...>` regex and cap (50) the Gmail adapter uses; `rfcMessageId` from `internetMessageId`; `fromHeader` is the raw `From` header from `internetMessageHeaders` when present, else `"Name <address>"` built from the `from.emailAddress` object; `authenticatedFromAddress` from `GraphAuthentication`.
- `findCommands` returns `CommandScan(candidates newest first, unreadable ids)`.
- `fetchContent`: plain text body, truncated like Gmail's (64,000 chars); image: first `fileAttachment` whose `contentType` starts with `image/`, `size <= 8 MB`, decoded from `contentBytes`.
- Contract/interface: `findCommands` and `pollReplies` return `[]` (not an error) when `!isAvailable`; `send` throws `MailAuthRequiredException` when signed out.

`FakeGraphServer` must implement: `/me`, `POST /me/messages` (MIME text/plain create-draft; parse `Message-ID:` header from the MIME to set `internetMessageId`, configurable to override with `var rewriteMessageId: Boolean`), `POST /me/messages/{id}/send` (moves to sent items), `GET /me/mailFolders/inbox/messages` and `.../sentitems/messages` with `$filter` (`receivedDateTime ge`, `isRead eq false`, `internetMessageId eq`), `$orderby`, `$top`, `$skiptoken`-style paging via `@odata.nextLink`, `GET /me/messages/{id}` with `$select` and the `Prefer` body header, `/attachments`, `PATCH /me/messages/{id}`. Control methods: `deliver(from, subject, body, headers, isRead=false): String`, `failNext(code, body)`, `delayNext`, `require401Once()`. It must also expose a `requests` list for assertions.

- [ ] **Step 1: Write failing tests.** `UNIT/GraphTransportContractTest.kt` runs `MailTransportContract` with a harness over `FakeGraphServer` + `GraphGateway` (the harness's `deliver(id, subject, from, authenticatedFrom)` adds raw `Authentication-Results` headers: when `authenticatedFrom != null` a passing header for that address's domain, else a failing one; `signOut()` clears the token store). `UNIT/GraphGatewayTest.kt` covers: draft-then-send call order and bodies (MIME is standard base64 of the exact bytes `MimeMessageBuilder` produced, `Content-Type: text/plain`); receipt `rfcMessageId` equals the server's returned `internetMessageId` and the one-time log fires when it differs; `pollReplies` returns an already-read message; the 90-day sweep first and the overlap-window poll afterwards (assert the `$filter` timestamps with an injected clock); paging across `@odata.nextLink`; 401 -> refresh -> retry succeeds once, a second 401 -> `MailAuthRequiredException`; `invalid_grant` on refresh -> `MailAuthRequiredException`; 429 with `Retry-After` retried once; 500 surfaces as `GraphApiException` (and `statusCode` is 500); `findCommands` honors tags and senders client-side and returns newest first capped at `RemoteCommandPlanner.MAX_CANDIDATES`; `markRead` PATCHes `isRead: true` twice without error; `fetchContent` returns text body and an image attachment, ignores a non-image attachment, and skips an oversize one; HTML-only message returns the server-converted text; `checkForBounces` finds a mailer-daemon message quoting an own Message-ID and ignores one that does not; a message from an Authentication-Results-less sender has `authenticatedFromAddress == null`; ids are `graph:`-scoped everywhere including `threadId`.
- [ ] **Step 2: RED. Step 3: Implement. Step 4: Full check; commit** `"Add the Microsoft Graph mail transport and a fake Graph server"`.

---

## Task 6: Wiring (graph members, alerts, status, seeding)

**Files:**
- Modify: `MAIN/AppGraph.kt`, `MAIN/data/SidekickRepository.kt`, `MAIN/service/RemoteStatusResponder.kt`, `MAIN/service/RemoteCommandReceipt.kt`, `MAIN/service/HeartbeatEmailWorker.kt`, `MAIN/service/SelfTestReceipt.kt`, `MAIN/ui/MainViewModel.kt`, `MAIN/service/ForwardingService.kt` (only if needed for the alert/available wiring)
- Test: `UNIT/StatusSummaryLinesTest.kt` (pure function), `INSTR/MailRouterQueueInstrumentedTest.kt`, `INSTR/GraphSeedingInstrumentedTest.kt`

**Interfaces:**
- Produces:
  - In `AppGraph`: `val msPrefs = MsAccountPreferences(app)`, `val msOAuth = MsOAuthManager(clientId = { msPrefs.clientId.ifBlank { null } }, store = KeystoreRefreshTokenStore(app), client = <shared OkHttpClient>)`, `val graphMail = GraphGateway(msOAuth, log = { repository.recordServiceEvent(it) })`. The router's `members` lambda returns `[gmail, graphMail]` ordered by `msPrefs.preferredProvider` (`"graph"` puts `graphMail` first). `onAuthRequired`/`onRecovered` call `alerts` with the provider.
  - `fun accountStatuses(): List<Pair<String, Boolean>>` on `AppGraph`: `[("Gmail", gmail.isAvailable)] + (if msPrefs.accountEmail != null || msOAuth.isAuthorized: [("Outlook", graphMail.isAvailable)])`.
  - `SidekickRepository.buildStatusSummary(gmailAvailable: Boolean, nowMs: Long = ..., otherAccounts: List<Pair<String, Boolean>> = emptyList())`: unchanged output when `otherAccounts` is empty; otherwise one extra line per account after the Gmail line: `"<name> authorization: OK"` or `"<name> authorization: NEEDS RECONNECTING"`. The pure formatting lives in `internal fun accountLines(otherAccounts)` so `StatusSummaryLinesTest` can test it without a database.
  - `SidekickRepository.seedRemoteControlOwnerIfEmpty` is unchanged; add `suspend fun seedRemoteControlSender(accountEmail: String, flagKey: String)` generalizing it (the existing function delegates with `REMOTE_OWNER_SEEDED`), and call it with `"remote_graph_seeded"` after a Microsoft account connects. Same rule as Gmail's: only when the flag is unset; add the address with all four permissions if it is not already on the list; never re-add after the owner removed it (flag set either way).
  - `MainViewModel`: `fun startMicrosoftSignIn()`, `fun cancelMicrosoftSignIn()`, `fun disconnectMicrosoft()`, `fun setMicrosoftClientId(id: String)`, `fun setPreferredProvider(provider: String)`, and observable state `microsoftState: StateFlow<MicrosoftUiState>` with `sealed interface MicrosoftUiState { NotConfigured; Idle(email: String?) ; WaitingForCode(code: String, uri: String, expiresAtMs: Long); Connecting; Connected(email: String); NeedsReconnect(email: String?); Error(message: String) }`.

- [ ] **Step 1: Write failing tests:** `StatusSummaryLinesTest` (empty list -> no lines; Outlook OK; Outlook needs reconnecting; two accounts keep order). `INSTR/MailRouterQueueInstrumentedTest` (written, compile-verified by the implementer; the controller runs it): install `FakeMailTransport` instances in a `MailRouter` as `graph.mail`, enqueue a system email, drain with a real `QueueProcessor`: (a) preferred signed out -> the fallback sends, one delivery attempt, queue empty; (b) a row with `attemptCount > 0` whose fallback already has the message -> no new send, the row completes. `INSTR/GraphSeedingInstrumentedTest`: seeding adds the address once with all four permissions, never again after removal.
- [ ] **Step 2: RED (unit part). Step 3: Implement.** `startMicrosoftSignIn` runs in `viewModelScope`: `startDeviceCode()` -> state `WaitingForCode`, then `awaitDeviceCode()`; on `Connected`: read the account address via `graphMail.accountEmail()`, store it in `msPrefs.accountEmail`, seed remote control, emit a message ("Outlook connected"), state `Connected`. `disconnectMicrosoft`: `msOAuth.disconnect()`, `msPrefs.clearAccount()`, `graphMail.clearSession()`, `alerts.clearAuthorizationRequired("graph")`. `SelfTestReceipt` message names the account that sent it: after queuing, the snackbar says "...queued via <primary display name>"; compute the primary as the router's first available member (add `fun primaryDisplayName(): String` to `MailRouter`). Update the three callers of `buildStatusSummary` to pass `graph.accountStatuses().drop(1)` as `otherAccounts` (the Gmail entry is already the first arg).
- [ ] **Step 4: Full check; commit** `"Wire Microsoft Graph into the router, status summaries and remote-control seeding"`.

---

## Task 7: UI

**Files:** Modify `MAIN/ui/MainActivity.kt` (Settings composition and Home chip), `MAIN/ui/SettingsScreens.kt` or create `MAIN/ui/MicrosoftAccountCard.kt`, `MAIN/ui/HomeScreen.kt`, `MAIN/ui/MainViewModel.kt` (state only if Task 6 left gaps). Create `UNIT/MicrosoftUiTextTest.kt` for the pure text helpers.

**Interfaces:**
- Produces: `@Composable fun MicrosoftAccountCard(state: MicrosoftUiState, clientId: String, preferred: String, gmailConnected: Boolean, onClientIdChange: (String) -> Unit, onConnect: () -> Unit, onCancel: () -> Unit, onDisconnect: () -> Unit, onPreferredChange: (String) -> Unit)` and `object MicrosoftUiText { fun statusLine(state: MicrosoftUiState): String; fun copyHint(...) }` (pure, unit-tested).

Behavior:
- Settings: under the existing Gmail card, section header **Email accounts** is added above both cards (the Gmail card text is unchanged). The Microsoft card shows: when `clientId` is blank -> a short explanation (the README steps summarized in three lines, ending "Paste your Application (client) ID below.") and a text field; when set but not connected -> field (collapsed/edit) and **Connect Microsoft account**; while waiting -> the code in a large monospace `Text`, the URL, buttons **Copy code** (clipboard) and **Open page** (`Intent.ACTION_VIEW`), a countdown to expiry, **Cancel**; connected -> the address, **Disconnect**, and when `gmailConnected` also **Send first** with two `FilterChip`s (Gmail / Microsoft); needs-reconnect -> a warning line and **Connect Microsoft account** again; error -> the message and retry. The card says it adds about one small request per 30-second poll.
- Home: a second status chip **Outlook ✓** (or **Outlook ⚠** when needs reconnecting) in the existing chip row, only when a Microsoft account is connected or needs reconnecting.
- Accessibility: every interactive element has a content description; the code is announced as spaced characters; the card respects the existing font-scale and theme handling (copy the surrounding cards' patterns).

- [ ] **Step 1: Write failing tests** for `MicrosoftUiText.statusLine` (every state) and a helper `spacedCode("ABCD1234") == "A B C D 1 2 3 4"` used for the accessibility description.
- [ ] **Step 2: RED. Step 3: Implement.** Follow the existing card style in `MainActivity.kt` (`GmailCard`) and `SettingsScreens.kt`. No new dependencies.
- [ ] **Step 4: Full check; commit** `"Add Microsoft account card, device-code panel and Outlook status chip"`.

---

## Task 8: Docs, version, release prep

**Files:** `README.md`, `docs/ARCHITECTURE.md`, `docs/DESIGN_NOTES.md`, `docs/TEST_PLAN.md`, `CHANGELOG.md`, `docs/CHANGELOG_DETAILED.md`, `app/build.gradle.kts` (versionCode 60, versionName "1.29.0"), `MAIN/ui/AboutScreen.kt` (changelog entry `1.29.0`, dated), `MAIN/ui/SettingsScreens.kt` (the Remote control card sentence about auto-authorized accounts mentions both accounts).

- [ ] **Step 1: README.** Feature table: add **Two inboxes with failover** ("Connect Gmail and an Outlook.com account; if one drops, the other keeps forwarding, receiving replies and commands."). Add a **Microsoft (Outlook.com) setup** section after Gmail Setup with the six registration steps from the spec (including the audience and public-client settings), what to paste where, the device-code sign-in, and a note on what it adds (one small request per poll). Update: the Remote control "How it stays safe" bullets (DMARC from the receiving provider, `From` must match; commands from the Gmail address into the Outlook inbox work and the reverse), Privacy and Limits sections (Gmail: no token stored; Microsoft: one refresh token encrypted by a non-exportable Keystore key; both providers see your mail), the Troubleshooting table (a row for "Outlook needs reconnecting", one for "device code expired", one for "Microsoft sign-in says the app isn't allowed (public client flows)"), the Docs list, and the badge to 1.29.0. Do not claim anything about Microsoft behavior that is marked unverified in the spec; say in Known gaps that Outlook sender authentication is new and its first real-message check is in `docs/TEST_PLAN.md` §18. Verify every number and path against the code.
- [ ] **Step 2: Docs.** `ARCHITECTURE.md` "Mail transports": list the second transport, the router and its order/failover rules, device-code sign-in and token storage, per-provider alerts. `DESIGN_NOTES.md`: the failover duplicate-vs-loss rule, the fail-closed authentication rule and why the first `Authentication-Results` header only, the threat model for the stored refresh token. `TEST_PLAN.md` new **§18 Microsoft account (manual)**: sign-in; send a forward and confirm it arrives and the stored sent route matches (Message-ID preserved: Activity shows no "replaced the Message-ID" note); reply from the recipient and confirm it becomes an SMS (requires sender authentication on a real header); send `[SCIF:STATUS]` from the Gmail address to the Outlook inbox and confirm the reply; send from the Outlook address to Gmail; sign the preferred account out and confirm the fallback carries a forward; disconnect; record the real `Authentication-Results` header text of one received message (redacted) here. `CHANGELOG.md` and `CHANGELOG_DETAILED.md` entry `1.29.0`; `AboutScreen.kt` changelog entry (bulleted, dated) so `CommandHelpTest`'s "newest changelog entry is the running version" stays green.
- [ ] **Step 3: Version bump** in `app/build.gradle.kts` (`versionCode = 60`, `versionName = "1.29.0"`).
- [ ] **Step 4: Run the full check and `assembleRelease`;** confirm `gradle/verification-metadata.xml` and `app/gradle.lockfile` are unchanged (`git diff --stat` shows neither). Commit `"Release 1.29.0: Outlook.com account and mail failover"`. The controller runs the emulator suite, merges, pushes, and builds the APK.

---

## Self-review

- **Spec coverage:** device-code sign-in and storage (Task 3), Graph table and `findSent` (Tasks 1, 5), authentication rules (Task 4), router rules (Task 2), alerts per provider (Tasks 1, 2, 6), interface follow-ups incl. `CommandSearch` checks and injectable `mail` (Task 1), seeding (Task 6), UI (Task 7), docs/version (Task 8), testing list (spread across tasks; the instrumented ones are named in Tasks 3 and 6).
- **Type consistency:** `MailAuthRequiredException(message, providerId, displayName)`, `MailHttpException`, `GraphApiException`, `findSent`, `MsOAuthManager` API, `MsAccountPreferences` members, `MicrosoftUiState`, `AppGraph.accountStatuses()` and `buildStatusSummary(..., otherAccounts)` are used with the same names throughout.
- **Placeholder scan:** no TBD/TODO. Where a task leaves a detail to the implementer it names the rule (copy the Gmail adapter's regex/caps; follow the Gmail card's style).
- **Review Focus mapping:** 1 -> Task 4 tests; 2 -> Task 2 tests 6-9; 3 -> Tasks 1, 2, 6 (per-provider alert, `onAuthRequired` only for the failing member); 4 -> Task 3 tests; 5 -> contract + Task 5; 6 -> Task 6 `disconnectMicrosoft` and Task 3 `clear`.
