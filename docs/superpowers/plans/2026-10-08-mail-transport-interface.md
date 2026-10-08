# Mail Transport Interface Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put a `MailTransport` interface in front of the Gmail code, with no change in behavior, so a Microsoft Graph transport and failover can be added in later steps.

**Architecture:** Neutral mail types and a `MailTransport` interface live in the `email` package. `GmailGateway` implements the interface unchanged in substance. Callers move from `graph.gmail` to `graph.mail`, a `MailTransport` that is the Gmail transport for now. Gmail-only concerns (search syntax, sender authentication, OAuth, push) stay inside the Gmail adapter.

**Tech Stack:** Kotlin, Android (Gradle, AGP), OkHttp, Room, WorkManager, JUnit4 (unit and instrumented).

**Spec:** `docs/superpowers/specs/2026-10-08-mail-transport-interface-design.md`

## Global Constraints

- **No behavior change.** Every existing unit and instrumented test passes with only mechanical edits (renames, imports). No test changes meaning.
- **No database schema change, no migration.** Do not rename Room columns or DAO methods (`gmailMessageId`, `recentProcessedGmailIds`, `recordIgnoredGmailCandidate` stay).
- **Gmail-visible text stays word for word.** Log and History strings that say "Gmail" keep their exact text when the transport is Gmail (they use `mail.displayName`, which is `"Gmail"`).
- **Instrumented tests run on the emulator only.** Before any `connected*` Gradle task: `export ANDROID_SERIAL=emulator-5554` and confirm `adb devices` shows the emulator. Running them on the phone wipes the phone's app data (this has happened once).
- **Never `git add -A` or `git add .`.** An untracked `.claude/` directory in the repo root holds a nested worktree and must not be committed. Add files by explicit path.
- **Commits:** author is already configured (TheWombRaider noreply). No `Co-Authored-By` or other trailers. Use `TZ=UTC git commit`.
- **Line endings:** existing source files are CRLF in the working tree (git normalizes). Edit them with `sed -i` or a Python script that preserves `\r\n`; the Edit tool may fail on them. New files may be LF.
- **Build environment (Git Bash):**
  `export JAVA_HOME="C:\\Program Files\\Android\\Android Studio\\jbr" ANDROID_HOME="C:\\Users\\austi\\AppData\\Local\\Android\\Sdk"`
- **Unit check command:** `./gradlew testDebugUnitTest --console=plain -q`
- **Full check command:** `./gradlew testDebugUnitTest assembleDebug lintDebug --console=plain -q`
- **Working directory for all commands:** `C:\Users\austi\Projects\scif-sidekick-clean` (`/c/Users/austi/Projects/scif-sidekick-clean`).
- **Paths:** `MAIN` = `app/src/main/java/com/scifsidekick/cleanroom`, `UNIT` = `app/src/test/java/com/scifsidekick/cleanroom`, `INSTR` = `app/src/androidTest/java/com/scifsidekick/cleanroom`.

## Review Focus

Failure modes the spec implies that no single task's happy path would catch. Each has a pinned test in the task named.

1. **Old Gmail ids still resolve as Gmail.** A stored un-prefixed id such as `18f3a9c2b4d5e6f7` must map to provider `gmail`, and a prefixed id such as `graph:AAMk...` must map to `graph` with the native id intact. A wrong answer would mis-route `markRead` once a second provider exists. (Task 2, `MailIdsTest`.)
2. **A sender nobody vouches for is never authenticated.** A message whose provider gave no authentication evidence must have `authenticatedFromAddress == null` even when its `From` matches the allowlist. (Task 6, contract test.)
3. **A sender address that cannot be safely expressed in a search still yields a capped, still-authorized search.** The Gmail query drops the sender filter rather than breaking its syntax; `RemoteCommandSearch` still returns null when nobody is allowlisted. (Task 2, migrated planner tests.)
4. **Reauthorization still pauses polling and receipts.** `ReauthorizationRequiredException` must be a `MailAuthRequiredException`, and the five catch sites must catch the base type. (Task 4, `MailTypesTest`.)
5. **A signed-out transport is inert and loud only where it should be.** `isAvailable` false, `pollReplies` and `findCommands` return empty results, and `send` throws `MailAuthRequiredException` rather than pretending to succeed. (Task 6, contract test.)

---

## Task 1: Neutral mail types

Rename the Gmail-named data types to provider-neutral names in a new file. Pure rename, no logic change.

**Files:**
- Create: `MAIN/email/MailTypes.kt`
- Modify: `MAIN/email/GmailGateway.kt` (delete the moved types, lines 20-67 region)
- Modify (mechanical rename of references): `MAIN/service/ForwardingService.kt`, `MAIN/service/QueueProcessor.kt`, `MAIN/service/RemoteCommandReceipt.kt`, `MAIN/service/RemoteEnableWorker.kt`, `MAIN/service/RemoteHelpResponder.kt`, `MAIN/service/RemoteStatusResponder.kt`

**Interfaces:**
- Produces (all in package `com.scifsidekick.cleanroom.email`):
  - `data class MailMessage(id: String, threadId: String, subject: String, body: String, referencedMessageIds: Set<String>, rfcMessageId: String, fromHeader: String, authenticatedFromAddress: String?, imageMimeType: String? = null, imageBytes: ByteArray? = null)` (the old `GmailReply`)
  - `data class CommandScan(candidates: List<MailMessage>, unreadable: List<String>)` (old `RemoteCommandScan`)
  - `data class MailPollResult(replies: List<MailMessage>, fetchFailures: List<String>)` (old `GmailPollResult`)
  - `data class MailReceipt(messageId: String, threadId: String, rfcMessageId: String, reconciled: Boolean)` (old `GmailDeliveryReceipt`)
  - `data class BounceNotice(messageId: String, referencedRfcMessageIds: Set<String>, summary: String)` (field `gmailMessageId` renamed to `messageId`; same position)

- [ ] **Step 1: Create `MailTypes.kt`**

```kotlin
package com.scifsidekick.cleanroom.email

/** One inbox message, as any [MailTransport] reports it. */
data class MailMessage(
    val id: String,
    val threadId: String,
    val subject: String,
    val body: String,
    val referencedMessageIds: Set<String>,
    val rfcMessageId: String,
    // The raw `From` header, exactly as the provider returned it -- may be a bare address or a
    // "Display Name <address>" form; see ComposeAuthorization.extractAddress.
    val fromHeader: String,
    // The sender address if, and only if, this provider's own authentication evidence vouches for
    // it. Null means "cannot be trusted as a command source".
    val authenticatedFromAddress: String?,
    // Body and image data are deliberately absent from the metadata poll. They are fetched only
    // after ForwardingService has verified this message's route and authenticated sender.
    val imageMimeType: String? = null,
    val imageBytes: ByteArray? = null,
)

/** [candidates] are newest first; [unreadable] are ids whose headers couldn't be fetched or parsed. */
data class CommandScan(
    val candidates: List<MailMessage>,
    val unreadable: List<String>,
)

data class MailPollResult(
    val replies: List<MailMessage>,
    val fetchFailures: List<String>,
)

data class MailReceipt(
    val messageId: String,
    val threadId: String,
    val rfcMessageId: String,
    val reconciled: Boolean,
)

/** One inbox message that looks like a delivery-status notification and quotes at least one of
 *  this installation's own RFC Message-IDs somewhere in its content -- see
 *  [MailTransport.checkForBounces]. */
data class BounceNotice(
    val messageId: String,
    val referencedRfcMessageIds: Set<String>,
    val summary: String,
)
```

- [ ] **Step 2: Delete the five old type declarations from `GmailGateway.kt`**

Remove `GmailReply`, `RemoteCommandScan`, `GmailPollResult`, `GmailDeliveryReceipt` and `BounceNotice` (everything between the `import` block and `class GmailGateway(`). Leave the imports and the class.

- [ ] **Step 3: Rename references across the repo**

Run (word-boundary replace, all Kotlin files, main and test):

```bash
cd /c/Users/austi/Projects/scif-sidekick-clean
FILES=$(grep -rlE "GmailReply|RemoteCommandScan|GmailPollResult|GmailDeliveryReceipt" app/src --include=*.kt)
sed -i -E 's/\bGmailReply\b/MailMessage/g; s/\bRemoteCommandScan\b/CommandScan/g; s/\bGmailPollResult\b/MailPollResult/g; s/\bGmailDeliveryReceipt\b/MailReceipt/g' $FILES
sed -i 's/notice\.gmailMessageId/notice.messageId/' app/src/main/java/com/scifsidekick/cleanroom/service/ForwardingService.kt
```

Then confirm none remain: `grep -rnE "GmailReply|RemoteCommandScan|GmailPollResult|GmailDeliveryReceipt" app/src --include=*.kt` prints nothing (comments in `GmailGateway.kt` that mention `[RemoteCommandScan.unreadable]` also change; that is fine).

- [ ] **Step 4: Run the unit check**

Run: `./gradlew testDebugUnitTest --console=plain -q`
Expected: build succeeds, tests pass (no output with `-q`).

- [ ] **Step 5: Compile the instrumented tests**

Run: `./gradlew compileDebugAndroidTestKotlin --console=plain -q`
Expected: succeeds (no emulator needed).

- [ ] **Step 6: Commit**

```bash
git add app/src
git status -s   # confirm only app/src files, and .claude/ is still untracked
TZ=UTC git commit -q -m "Rename Gmail data types to provider-neutral mail types" -m "GmailReply, GmailPollResult, RemoteCommandScan and GmailDeliveryReceipt become MailMessage, MailPollResult, CommandScan and MailReceipt in email/MailTypes.kt. No logic changes."
```

---

## Task 2: Message ids, command search, Gmail query builder

Add the provider-neutral command-search description and move Gmail query-string building into the Gmail adapter. The old `RemoteCommandQuery` stays until Task 3 switches the one caller.

**Files:**
- Create: `MAIN/email/MailIds.kt`
- Create: `MAIN/email/GmailCommandQuery.kt`
- Modify: `MAIN/email/MailTypes.kt` (add `CommandSearch`)
- Modify: `MAIN/util/RemoteCommandPlanner.kt` (add `RemoteCommandSearch`)
- Test: `UNIT/MailIdsTest.kt` (create), `UNIT/RemoteCommandPlannerTest.kt` (add three tests)

**Interfaces:**
- Produces:
  - `data class CommandSearch(val tags: List<String>, val senders: List<String>)` in `com.scifsidekick.cleanroom.email`. `senders` are canonical (lowercase) addresses, never empty.
  - `object RemoteCommandSearch { fun plan(commandTags: List<String>, sendersJson: String): CommandSearch? }` in `com.scifsidekick.cleanroom.util`. Null when there are no tags or nobody is allowlisted.
  - `object GmailCommandQuery { fun build(search: CommandSearch): String }` in `com.scifsidekick.cleanroom.email`.
  - `object MailIds { const val GMAIL = "gmail"; fun providerOf(id: String): String; fun nativeId(id: String): String; fun scoped(providerId: String, nativeId: String): String }` in `com.scifsidekick.cleanroom.email`.

- [ ] **Step 1: Write the failing `MailIdsTest`**

`UNIT/MailIdsTest.kt`:

```kotlin
package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.email.MailIds
import org.junit.Assert.assertEquals
import org.junit.Test

class MailIdsTest {
    @Test fun `an unprefixed id is a gmail id`() {
        assertEquals("gmail", MailIds.providerOf("18f3a9c2b4d5e6f7"))
        assertEquals("18f3a9c2b4d5e6f7", MailIds.nativeId("18f3a9c2b4d5e6f7"))
    }

    @Test fun `a prefixed id names its provider and keeps the native id intact`() {
        val id = "graph:AAMkAGI2:with:colons="
        assertEquals("graph", MailIds.providerOf(id))
        assertEquals("AAMkAGI2:with:colons=", MailIds.nativeId(id))
    }

    @Test fun `scoped ids round trip, and gmail ids are never prefixed`() {
        assertEquals("abc123", MailIds.scoped("gmail", "abc123"))
        assertEquals("graph:abc123", MailIds.scoped("graph", "abc123"))
        assertEquals("graph", MailIds.providerOf(MailIds.scoped("graph", "abc123")))
        assertEquals("abc123", MailIds.nativeId(MailIds.scoped("graph", "abc123")))
    }

    @Test fun `an empty prefix is not a provider`() {
        assertEquals("gmail", MailIds.providerOf(":oddity"))
        assertEquals(":oddity", MailIds.nativeId(":oddity"))
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew testDebugUnitTest --tests "com.scifsidekick.cleanroom.MailIdsTest" --console=plain -q`
Expected: FAIL to compile, `Unresolved reference: MailIds`.

- [ ] **Step 3: Create `MailIds.kt`**

```kotlin
package com.scifsidekick.cleanroom.email

/**
 * Message ids are scoped by provider so two providers can never collide in the processed-ids
 * table or in routing. Gmail ids are hex strings with no colon and stay exactly as stored today
 * (no prefix), so existing rows need no migration. Every other provider prefixes its native id as
 * `<providerId>:<native id>`; only the first colon splits, so a native id may contain colons.
 */
object MailIds {
    const val GMAIL = "gmail"

    fun providerOf(id: String): String {
        val colon = id.indexOf(':')
        return if (colon <= 0) GMAIL else id.substring(0, colon)
    }

    fun nativeId(id: String): String {
        val colon = id.indexOf(':')
        return if (colon <= 0) id else id.substring(colon + 1)
    }

    fun scoped(
        providerId: String,
        nativeId: String,
    ): String = if (providerId == GMAIL) nativeId else "$providerId:$nativeId"
}
```

- [ ] **Step 4: Run it and watch it pass**

Run: `./gradlew testDebugUnitTest --tests "com.scifsidekick.cleanroom.MailIdsTest" --console=plain -q`
Expected: PASS.

- [ ] **Step 5: Add `CommandSearch` to `MailTypes.kt`**

Append:

```kotlin
/**
 * Which command mail to look for, independent of any provider's search syntax. [senders] are
 * canonical lowercase addresses and never empty (see RemoteCommandSearch.plan). Each transport
 * turns this into its own query.
 */
data class CommandSearch(
    val tags: List<String>,
    val senders: List<String>,
)
```

- [ ] **Step 6: Add the failing planner tests**

In `UNIT/RemoteCommandPlannerTest.kt`, add these imports at the top with the others:

```kotlin
import com.scifsidekick.cleanroom.email.GmailCommandQuery
import com.scifsidekick.cleanroom.util.RemoteCommandSearch
```

and add these three tests inside the class (keep the existing three `RemoteCommandQuery` tests for now; they are removed in Task 3):

```kotlin
    @Test
    fun searchPlanListsCanonicalAllowlistedSenders() {
        val search = RemoteCommandSearch.plan(tags, RemoteControlCodec.toJson(listOf(Sender(owner), Sender("Boss@Agency.gov"))))!!
        assertEquals(tags, search.tags)
        assertEquals(listOf(owner, "boss@agency.gov"), search.senders)
    }

    @Test
    fun searchPlanIsNullWithNoAllowlistOrNoTags() {
        assertNull(RemoteCommandSearch.plan(tags, "[]"))
        assertNull(RemoteCommandSearch.plan(emptyList(), senders))
        assertNull(RemoteCommandSearch.plan(tags, "not json"))
    }

    @Test
    fun gmailQueryMatchesTheOldStringExactly() {
        val search = RemoteCommandSearch.plan(tags, RemoteControlCodec.toJson(listOf(Sender(owner), Sender("Boss@Agency.gov"))))!!
        assertEquals(
            "in:inbox is:unread newer_than:2d {subject:\"[SCIF:ON]\" subject:\"[SCIF:STATUS]\"} {from:owner@example.com from:boss@agency.gov}",
            GmailCommandQuery.build(search),
        )
    }

    @Test
    fun gmailQueryDropsTheSenderFilterIfAnAddressIsNotSafelyExpressible() {
        val search = RemoteCommandSearch.plan(tags, RemoteControlCodec.toJson(listOf(Sender(owner), Sender("o'neil@example.com"))))!!
        val query = GmailCommandQuery.build(search)
        assertFalse(query.contains("from:"))
        assertTrue(query.startsWith("in:inbox is:unread newer_than:2d "))
    }
```

- [ ] **Step 7: Run and watch them fail**

Run: `./gradlew testDebugUnitTest --tests "com.scifsidekick.cleanroom.RemoteCommandPlannerTest" --console=plain -q`
Expected: FAIL to compile (`RemoteCommandSearch`, `GmailCommandQuery` unresolved).

- [ ] **Step 8: Add `RemoteCommandSearch` to `util/RemoteCommandPlanner.kt`**

Add at the end of the file, and add `import com.scifsidekick.cleanroom.email.CommandSearch` to the file's imports:

```kotlin
/** Describes, without any provider's syntax, which command mail [RemoteCommandPlanner] should see. */
object RemoteCommandSearch {
    /**
     * Narrows the search to the allowlisted senders so mail from anyone else never competes for
     * a slot. Null means there is nothing to look for: no tags, or nobody allowlisted.
     */
    fun plan(
        commandTags: List<String>,
        sendersJson: String,
    ): CommandSearch? {
        if (commandTags.isEmpty()) return null
        val addresses =
            RemoteControlCodec
                .fromJson(sendersJson)
                .mapNotNull { ComposeAuthorization.canonicalAddress(it.address) }
                .distinct()
        if (addresses.isEmpty()) return null
        return CommandSearch(commandTags, addresses)
    }
}
```

- [ ] **Step 9: Create `GmailCommandQuery.kt`**

```kotlin
package com.scifsidekick.cleanroom.email

/** Turns a provider-neutral [CommandSearch] into Gmail search syntax. */
object GmailCommandQuery {
    // Deliberately conservative: an address with any other character is left out of the sender
    // filter rather than risk it changing the search's meaning.
    private val safeAddress = Regex("[a-z0-9._%+-]+@[a-z0-9.-]+")

    /**
     * If any allowlisted address can't be expressed safely the sender filter is dropped entirely
     * (every tagged message is then examined, still capped and still authorization-checked).
     */
    fun build(search: CommandSearch): String {
        val subjectClause = search.tags.joinToString(" ", prefix = "{", postfix = "}") { "subject:\"$it\"" }
        val fromClause =
            if (search.senders.all(safeAddress::matches)) {
                search.senders.joinToString(" ", prefix = " {", postfix = "}") { "from:$it" }
            } else {
                ""
            }
        return "in:inbox is:unread newer_than:2d $subjectClause$fromClause"
    }
}
```

- [ ] **Step 10: Run the planner tests and watch them pass**

Run: `./gradlew testDebugUnitTest --tests "com.scifsidekick.cleanroom.RemoteCommandPlannerTest" --console=plain -q`
Expected: PASS (new tests and the three old ones).

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/com/scifsidekick/cleanroom/email/MailIds.kt app/src/main/java/com/scifsidekick/cleanroom/email/GmailCommandQuery.kt app/src/main/java/com/scifsidekick/cleanroom/email/MailTypes.kt app/src/main/java/com/scifsidekick/cleanroom/util/RemoteCommandPlanner.kt app/src/test/java/com/scifsidekick/cleanroom/MailIdsTest.kt app/src/test/java/com/scifsidekick/cleanroom/RemoteCommandPlannerTest.kt
TZ=UTC git commit -q -m "Add provider-neutral command search and message id scoping" -m "CommandSearch describes which command mail to find; GmailCommandQuery turns it into Gmail syntax; MailIds scopes ids by provider with Gmail ids unprefixed."
```

---

## Task 3: The `MailTransport` interface and the Gmail adapter

Define the interface, make `GmailGateway` implement it, add `graph.mail`, and switch the one caller that changes shape (`RemoteEnableWorker`).

**Files:**
- Create: `MAIN/email/MailTransport.kt`
- Modify: `MAIN/email/GmailGateway.kt`
- Modify: `MAIN/AppGraph.kt`
- Modify: `MAIN/service/RemoteEnableWorker.kt`
- Modify: `MAIN/ui/MainViewModel.kt`, `MAIN/service/SelfTestReceipt.kt` (call-site rename `currentAccountEmail` to `accountEmail`)
- Modify: `MAIN/util/RemoteCommandPlanner.kt` (delete `RemoteCommandQuery`)
- Modify: `UNIT/RemoteCommandPlannerTest.kt` (delete the three old `RemoteCommandQuery` tests and its import)
- Modify: `docs/superpowers/specs/2026-10-08-mail-transport-interface-design.md` (add `displayName`)

**Interfaces:**
- Consumes: the types from Tasks 1 and 2.
- Produces: `MailTransport` (below) and `AppGraph.mail: MailTransport`.

- [ ] **Step 1: Create `MailTransport.kt`**

```kotlin
package com.scifsidekick.cleanroom.email

import com.scifsidekick.cleanroom.messaging.EmailPayload

/**
 * One mail provider the app can send through and read from. Everything provider-specific -- search
 * syntax, history cursors, authentication headers, sign-in -- lives behind this interface.
 *
 * The one rule every implementation must keep: [MailMessage.authenticatedFromAddress] is set only
 * when this provider's own evidence shows the sender is who the `From` header claims (a DMARC pass
 * aligned with the `From` domain). Anything missing, unrecognized or ambiguous is null.
 */
interface MailTransport {
    /** Stable lowercase id: "gmail" now, "graph" later. Used as the message-id prefix, see [MailIds]. */
    val providerId: String

    /** Human-readable name for log and History text, for example "Gmail". */
    val displayName: String

    /** Whether this transport can currently be used (authorized, or the debug fake is on). */
    val isAvailable: Boolean

    /** The signed-in account's address, or null when signed out or it cannot be read. */
    suspend fun accountEmail(): String?

    /**
     * Sends [payload]. With [verifyPriorDelivery] the transport first checks whether a message
     * with this [deliveryKey] was already accepted and, if so, returns it with
     * [MailReceipt.reconciled] true instead of sending a duplicate.
     * @throws MailAuthRequiredException when the user must reconnect the account.
     */
    suspend fun send(
        payload: EmailPayload,
        attachmentPaths: List<String>,
        deliveryKey: String,
        verifyPriorDelivery: Boolean,
    ): MailReceipt

    /** New candidate replies, skipping [knownMessageIds]. Empty (not an error) when signed out. */
    suspend fun pollReplies(knownMessageIds: Set<String>): MailPollResult

    /** Unread command mail matching [search], newest first. Empty (not an error) when signed out. */
    suspend fun findCommands(search: CommandSearch): CommandScan

    /** Fetches the body and image for an already-authorized [message]. */
    suspend fun fetchContent(message: MailMessage): MailMessage

    /** Marks a message read. Idempotent. */
    suspend fun markRead(messageId: String)

    /** Delivery-failure notices that quote one of this installation's own Message-IDs. */
    suspend fun checkForBounces(): List<BounceNotice>

    /** Drops any in-memory cursors or caches. Does not sign out. */
    fun clearSession()
}
```

- [ ] **Step 2: Make `GmailGateway` implement it**

In `MAIN/email/GmailGateway.kt` make these edits (use `sed`/Python; do not change any other logic):

1. Class header: `class GmailGateway(` ... `) {` becomes `) : MailTransport {` (the closing `) {` of the constructor parameter list gets `: MailTransport` before the brace).
2. Directly under the `@Volatile private var lastFullSweepMs = 0L` line, add:

```kotlin
    override val providerId: String = MailIds.GMAIL
    override val displayName: String = "Gmail"
```

3. `val isAvailable: Boolean get() =` becomes `override val isAvailable: Boolean get() =`.
4. `fun clearSession()` becomes `override fun clearSession()`.
5. `suspend fun currentAccountEmail(): String?` becomes `override suspend fun accountEmail(): String?`. Inside the file the private `accountEmail(token: String)` keeps its name (different parameter list, a legal overload).
6. `suspend fun send(` becomes `override suspend fun send(`.
7. `suspend fun unreadReplies(knownMessageIds: Set<String> = emptySet()): MailPollResult` becomes `override suspend fun pollReplies(knownMessageIds: Set<String>): MailPollResult`. Update the doc comment's two `[unreadReplies]` references to `[pollReplies]`.
8. Replace the `findRemoteCommands` signature and its first two lines:

```kotlin
    override suspend fun findCommands(search: CommandSearch): CommandScan {
        if (!oauth.isAuthorized || debug.fakeEmailTransport) return CommandScan(emptyList(), emptyList())
        val query = GmailCommandQuery.build(search)
        val token = oauth.freshAccessToken()
```

(the existing `val token` line that followed is replaced by the last line above; leave the rest of the body unchanged, including `val encoded = URLEncoder.encode(query, ...)`). Update the doc comment's `[RemoteCommandQuery]` reference to `[RemoteCommandSearch]` and its `[unreadReplies]` reference to `[pollReplies]`.
9. `suspend fun markRead(` becomes `override suspend fun markRead(`.
10. `suspend fun checkForBounces()` becomes `override suspend fun checkForBounces()`.
11. `suspend fun fetchContent(reply: MailMessage): MailMessage` becomes `override suspend fun fetchContent(reply: MailMessage): MailMessage`. Keep the parameter name `reply` (Kotlin only warns that it differs from the interface's `message`; callers pass it positionally).

- [ ] **Step 3: Rename the `currentAccountEmail` call sites**

```bash
sed -i 's/\.currentAccountEmail()/.accountEmail()/g' app/src/main/java/com/scifsidekick/cleanroom/ui/MainViewModel.kt app/src/main/java/com/scifsidekick/cleanroom/service/SelfTestReceipt.kt
sed -i 's/GmailGateway\.currentAccountEmail/GmailGateway.accountEmail/' app/src/main/java/com/scifsidekick/cleanroom/email/GmailOAuthManager.kt
grep -rn "currentAccountEmail" app/src --include=*.kt   # expect no output
```

- [ ] **Step 4: Add `graph.mail` in `AppGraph.kt`**

Add the import `com.scifsidekick.cleanroom.email.MailTransport`, and after `val gmail = GmailGateway(oauth, debug)` add:

```kotlin
    /** What the rest of the app talks to. Today that is Gmail; a router replaces it in a later step. */
    val mail: MailTransport = gmail
```

Leave `QueueProcessor(...)` untouched in this task (Task 4 changes it).

- [ ] **Step 5: Switch `RemoteEnableWorker`**

In `MAIN/service/RemoteEnableWorker.kt`, replace the import `com.scifsidekick.cleanroom.util.RemoteCommandQuery` with `com.scifsidekick.cleanroom.util.RemoteCommandSearch`, and replace:

```kotlin
        val query = RemoteCommandQuery.build(wantedTags, settings.remoteControlSendersJson) ?: return Result.success()

        val scan =
            suspendRunCatching { graph.gmail.findRemoteCommands(query) }
```

with:

```kotlin
        val search = RemoteCommandSearch.plan(wantedTags, settings.remoteControlSendersJson) ?: return Result.success()

        val scan =
            suspendRunCatching { graph.mail.findCommands(search) }
```

Also change the other three `graph.gmail.` uses in this file (`isAvailable`, two `markRead`) to `graph.mail.`.

- [ ] **Step 6: Delete `RemoteCommandQuery` and its old tests**

Delete the `RemoteCommandQuery` object (from `/** Builds the Gmail search for [RemoteCommandPlanner]'s candidates. */` through its closing brace) from `util/RemoteCommandPlanner.kt`. In `UNIT/RemoteCommandPlannerTest.kt` delete the import `com.scifsidekick.cleanroom.util.RemoteCommandQuery` and the three tests `queryRestrictsToAllowlistedSenders`, `queryHasNoSenderFilterIfAnAddressIsNotSafelyExpressible`, `noAllowlistMeansNoQuery`. The new Task 2 tests cover the same cases.

- [ ] **Step 7: Amend the spec**

In the spec's "Interface" code block add the line `val displayName: String` under `val providerId: String`, and in the "Errors" section replace "takes the provider id instead" with "takes `displayName` instead". This records the one deliberate deviation made while planning (the log text needs a human-readable name, not the id).

- [ ] **Step 8: Run the full check**

Run: `./gradlew testDebugUnitTest assembleDebug lintDebug compileDebugAndroidTestKotlin --console=plain -q`
Expected: succeeds.

- [ ] **Step 9: Commit**

```bash
git add app/src docs/superpowers/specs/2026-10-08-mail-transport-interface-design.md
TZ=UTC git commit -q -m "Add MailTransport interface and make GmailGateway implement it" -m "AppGraph.mail is the Gmail transport for now. Remote command lookup takes a neutral CommandSearch; the old RemoteCommandQuery is replaced by RemoteCommandSearch plus GmailCommandQuery."
```

---

## Task 4: Callers use the interface

Move every non-UI caller to `graph.mail`, generalize the reauthorization exception, and make generic log text use `displayName`.

**Files:**
- Create: `MAIN/email/MailAuthRequiredException.kt`
- Modify: `MAIN/email/GmailOAuthManager.kt` (exception base class)
- Modify: `MAIN/service/ForwardingService.kt`, `MAIN/service/QueueProcessor.kt`, `MAIN/service/HeartbeatEmailWorker.kt`, `MAIN/service/RemoteCommandReceipt.kt`, `MAIN/service/RemoteStatusResponder.kt`, `MAIN/service/RemoteHelpResponder.kt`, `MAIN/service/SelfTestReceipt.kt`, `MAIN/AppGraph.kt`
- Test: `UNIT/MailTypesTest.kt` (create)

**Interfaces:**
- Consumes: `MailTransport`, `AppGraph.mail` from Task 3.
- Produces: `open class MailAuthRequiredException(message: String) : Exception(message)`; `ReauthorizationRequiredException` now extends it. `QueueProcessor`'s fourth constructor parameter is `mail: MailTransport`.

- [ ] **Step 1: Write the failing test**

`UNIT/MailTypesTest.kt`:

```kotlin
package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.email.MailAuthRequiredException
import com.scifsidekick.cleanroom.email.ReauthorizationRequiredException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MailTypesTest {
    @Test fun `gmail reauthorization is a mail auth failure so shared catch sites still catch it`() {
        val failure: Throwable = ReauthorizationRequiredException()
        assertTrue(failure is MailAuthRequiredException)
        assertEquals("Open the app and reconnect Gmail", failure.message)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew testDebugUnitTest --tests "com.scifsidekick.cleanroom.MailTypesTest" --console=plain -q`
Expected: FAIL to compile (`MailAuthRequiredException` unresolved).

- [ ] **Step 3: Create the base exception and re-parent the Gmail one**

`MAIN/email/MailAuthRequiredException.kt`:

```kotlin
package com.scifsidekick.cleanroom.email

/** The account behind a [MailTransport] needs the user to reconnect it. Callers pause work and alert. */
open class MailAuthRequiredException(
    message: String,
) : Exception(message)
```

In `MAIN/email/GmailOAuthManager.kt` replace the last class:

```kotlin
class ReauthorizationRequiredException : Exception("Open the app and reconnect Gmail")
```

with:

```kotlin
class ReauthorizationRequiredException : MailAuthRequiredException("Open the app and reconnect Gmail")
```

- [ ] **Step 4: Run it and watch it pass**

Run: `./gradlew testDebugUnitTest --tests "com.scifsidekick.cleanroom.MailTypesTest" --console=plain -q`
Expected: PASS.

- [ ] **Step 5: Point the callers at `graph.mail`**

```bash
cd app/src/main/java/com/scifsidekick/cleanroom/service
sed -i 's/graph\.gmail\./graph.mail./g' ForwardingService.kt HeartbeatEmailWorker.kt RemoteCommandReceipt.kt RemoteStatusResponder.kt RemoteHelpResponder.kt SelfTestReceipt.kt
sed -i 's/graph\.gmail\.unreadReplies(/graph.mail.pollReplies(/' ForwardingService.kt
grep -rn "graph\.gmail\b" . ; cd -   # expect no output
```

Then in `ForwardingService.kt`:
- Replace `if (graph.oauth.isAuthorized) {` (inside `pollReplies`'s generic catch) with `if (graph.mail.isAvailable) {`.
- Replace every catch of `com.scifsidekick.cleanroom.email.ReauthorizationRequiredException` (four places) with `com.scifsidekick.cleanroom.email.MailAuthRequiredException`.

In `QueueProcessor.kt`:
- Imports: remove `com.scifsidekick.cleanroom.email.GmailGateway`; add `com.scifsidekick.cleanroom.email.MailTransport` and `com.scifsidekick.cleanroom.email.MailAuthRequiredException`; remove `com.scifsidekick.cleanroom.email.ReauthorizationRequiredException`.
- Constructor: `private val gmail: GmailGateway,` becomes `private val mail: MailTransport,`.
- `if (!gmail.isAvailable) return` becomes `if (!mail.isAvailable) return`; `gmail.send(` becomes `mail.send(`.
- `catch (required: ReauthorizationRequiredException)` becomes `catch (required: MailAuthRequiredException)`.

In `AppGraph.kt`: pass `mail` instead of `gmail` as `QueueProcessor`'s fourth argument.

`INSTR/RepositorySafetyInstrumentedTest.kt` passes `GmailGateway(...)` positionally; it still compiles because `GmailGateway` is a `MailTransport`. No change.

- [ ] **Step 6: Make generic log text use `displayName`**

Apply these exact replacements (Gmail's output stays word for word because `displayName == "Gmail"`):

`ForwardingService.kt`:
- `"Gmail reply polling paused until the user reconnects",` becomes `"${graph.mail.displayName} reply polling paused until the user reconnects",`
- `"Gmail reply poll failed: ${...}"` becomes `"${graph.mail.displayName} reply poll failed: ${...}"`
- `"Gmail reply could not be parsed or fetched: $failure"` becomes `"${graph.mail.displayName} reply could not be parsed or fetched: $failure"`
- `"Gmail reply content could not be fetched until the account is reconnected",` becomes `"${graph.mail.displayName} reply content could not be fetched until the account is reconnected",`
- `"Authorized Gmail reply content could not be fetched: ${...}"` becomes `"Authorized ${graph.mail.displayName} reply content could not be fetched: ${...}"`
- `"Could not mark Gmail reply $messageId read: ${...}"` becomes `"Could not mark ${graph.mail.displayName} reply $messageId read: ${...}"`
- Leave `"Gmail push pull failed: ..."` unchanged (Gmail push is Gmail-only).

`QueueProcessor.kt`:
- `"Previously accepted Gmail message reconciled; duplicate send suppressed"` becomes `"Previously accepted ${mail.displayName} message reconciled; duplicate send suppressed"`
- `"Gmail message ${receipt.messageId} accepted"` becomes `"${mail.displayName} message ${receipt.messageId} accepted"`
- `(failure.message ?: "Gmail authorization is required")` becomes `(failure.message ?: "${mail.displayName} authorization is required")`
- `reason = "Gmail authorization requires user interaction; email queue paused",` becomes `reason = "${mail.displayName} authorization requires user interaction; email queue paused",`

- [ ] **Step 7: Run the full check**

Run: `./gradlew testDebugUnitTest assembleDebug lintDebug compileDebugAndroidTestKotlin --console=plain -q`
Expected: succeeds.

- [ ] **Step 8: Prove the strings are unchanged**

Run: `git diff HEAD~1 -U0 -- app/src/main | grep -E '^[-+].*(reply poll|reply polling|accepted|authorization)' `
Expected: each `-` line has a matching `+` line that differs only by `Gmail` becoming `${...displayName}`.

- [ ] **Step 9: Commit**

```bash
git add app/src
TZ=UTC git commit -q -m "Route mail callers through MailTransport" -m "Services use graph.mail, reauthorization is a MailAuthRequiredException, and generic log text uses the transport's display name. Gmail text is unchanged."
```

---

## Task 5: Move `GmailAuthentication` into the Gmail adapter

Sender authentication is Gmail-specific (it trusts only an `mx.google.com` header), so it moves into the `email` package unchanged.

**Files:**
- Create: `MAIN/email/GmailAuthentication.kt`
- Modify: `MAIN/util/MessageUtilities.kt` (delete the object)
- Modify: `MAIN/email/GmailGateway.kt` (drop the now-same-package import)
- Modify: `UNIT/AuthenticationResultsTest.kt`, `UNIT/SafetyPolicyTest.kt` (import only)

**Interfaces:**
- Produces: `object GmailAuthentication { fun authenticatedFrom(fromHeader: String, authenticationResults: List<String>): String? }` in `com.scifsidekick.cleanroom.email`, identical behavior.

- [ ] **Step 1: Create `email/GmailAuthentication.kt`**

Copy the `GmailAuthentication` object from `util/MessageUtilities.kt` (the block starting `object GmailAuthentication {`, about lines 213-230) verbatim into a new file with this header:

```kotlin
package com.scifsidekick.cleanroom.email

import com.scifsidekick.cleanroom.util.ComposeAuthorization
import java.util.Locale
```

followed by the object, unchanged.

- [ ] **Step 2: Delete it from `util/MessageUtilities.kt`**

Remove the object. If `java.util.Locale` is no longer used elsewhere in that file, leave the import (an unused import is a warning, not an error).

- [ ] **Step 3: Fix imports**

- `GmailGateway.kt`: remove `import com.scifsidekick.cleanroom.util.GmailAuthentication` (same package now).
- `UNIT/AuthenticationResultsTest.kt` and `UNIT/SafetyPolicyTest.kt`: change `import com.scifsidekick.cleanroom.util.GmailAuthentication` to `import com.scifsidekick.cleanroom.email.GmailAuthentication`.

- [ ] **Step 4: Run the full check**

Run: `./gradlew testDebugUnitTest assembleDebug lintDebug --console=plain -q`
Expected: succeeds, with the same authentication tests passing as before.

- [ ] **Step 5: Commit**

```bash
git add app/src
TZ=UTC git commit -q -m "Move GmailAuthentication into the Gmail adapter package" -m "Sender authentication is provider-specific. Behavior and tests are unchanged; only the package moves."
```

---

## Task 6: Fake transport, contract tests, and the seam test

A `FakeMailTransport` shared by unit and instrumented tests, a contract test every transport must pass, and a test proving `QueueProcessor` needs only the interface.

**Files:**
- Modify: `app/build.gradle.kts` (share a source folder between `test` and `androidTest`)
- Create: `app/src/sharedTest/java/com/scifsidekick/cleanroom/FakeMailTransport.kt`
- Create: `UNIT/MailTransportContract.kt`, `UNIT/FakeMailTransportContractTest.kt`
- Create: `INSTR/MailTransportSeamInstrumentedTest.kt`

**Interfaces:**
- Consumes: `MailTransport`, the Task 1 types, `MailAuthRequiredException`, `MimeMessageBuilder.rfcMessageId(deliveryKey)`.
- Produces:
  - `class FakeMailTransport(providerId: String = "fake", displayName: String = "Fake mail") : MailTransport`, with test controls: `var signedIn: Boolean`, `val sent: MutableList<FakeMailTransport.SentRecord>`, `var failNextSendWith: Exception?`, `fun deliver(id: String, subject: String, from: String, authenticatedFrom: String? = null)`.
  - `data class SentRecord(val deliveryKey: String, val payload: EmailPayload, val receipt: MailReceipt)`.
  - `abstract class MailTransportContract` with `abstract fun newHarness(): MailTransportContract.Harness` and `interface Harness { val transport: MailTransport; fun signOut(); fun deliver(id: String, subject: String, from: String, authenticatedFrom: String?) }`.

- [ ] **Step 1: Share the folder in `app/build.gradle.kts`**

Inside the existing `android { ... }` block add:

```kotlin
    sourceSets {
        getByName("test").java.srcDir("src/sharedTest/java")
        getByName("androidTest").java.srcDir("src/sharedTest/java")
    }
```

If Gradle rejects this under the project's AGP DSL settings, fall back to placing `FakeMailTransport.kt` in both `app/src/test/java/com/scifsidekick/cleanroom/` and `app/src/androidTest/java/com/scifsidekick/cleanroom/` (identical copies) and skip the `sourceSets` edit.

- [ ] **Step 2: Create `FakeMailTransport.kt`**

`app/src/sharedTest/java/com/scifsidekick/cleanroom/FakeMailTransport.kt`:

```kotlin
package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.email.BounceNotice
import com.scifsidekick.cleanroom.email.CommandScan
import com.scifsidekick.cleanroom.email.CommandSearch
import com.scifsidekick.cleanroom.email.MailAuthRequiredException
import com.scifsidekick.cleanroom.email.MailMessage
import com.scifsidekick.cleanroom.email.MailPollResult
import com.scifsidekick.cleanroom.email.MailReceipt
import com.scifsidekick.cleanroom.email.MailTransport
import com.scifsidekick.cleanroom.email.MimeMessageBuilder
import com.scifsidekick.cleanroom.messaging.EmailPayload
import com.scifsidekick.cleanroom.util.RemoteCommandPlanner

/** In-memory [MailTransport] for tests. Behaves the way the interface's contract says. */
class FakeMailTransport(
    override val providerId: String = "fake",
    override val displayName: String = "Fake mail",
) : MailTransport {
    data class SentRecord(
        val deliveryKey: String,
        val payload: EmailPayload,
        val receipt: MailReceipt,
    )

    private class Stored(
        val message: MailMessage,
        var unread: Boolean = true,
    )

    @Volatile var signedIn: Boolean = true
    val sent = mutableListOf<SentRecord>()
    var failNextSendWith: Exception? = null
    private val inbox = mutableListOf<Stored>()

    override val isAvailable: Boolean get() = signedIn

    override suspend fun accountEmail(): String? = if (signedIn) "owner@fake.invalid" else null

    override suspend fun send(
        payload: EmailPayload,
        attachmentPaths: List<String>,
        deliveryKey: String,
        verifyPriorDelivery: Boolean,
    ): MailReceipt {
        if (!signedIn) throw MailAuthRequiredException("Open the app and reconnect $displayName")
        failNextSendWith?.let {
            failNextSendWith = null
            throw it
        }
        val rfc = MimeMessageBuilder.rfcMessageId(deliveryKey)
        if (verifyPriorDelivery) {
            sent.firstOrNull { it.receipt.rfcMessageId == rfc }?.let { return it.receipt.copy(reconciled = true) }
        }
        val receipt = MailReceipt(messageId = "fake-${sent.size + 1}", threadId = "thread-${sent.size + 1}", rfcMessageId = rfc, reconciled = false)
        sent += SentRecord(deliveryKey, payload, receipt)
        return receipt
    }

    override suspend fun pollReplies(knownMessageIds: Set<String>): MailPollResult {
        if (!signedIn) return MailPollResult(emptyList(), emptyList())
        return MailPollResult(inbox.filter { it.message.id !in knownMessageIds }.map { it.message }, emptyList())
    }

    override suspend fun findCommands(search: CommandSearch): CommandScan {
        if (!signedIn) return CommandScan(emptyList(), emptyList())
        val found =
            inbox
                .asReversed()
                .filter { stored ->
                    stored.unread &&
                        search.tags.any { stored.message.subject.contains(it, ignoreCase = true) } &&
                        (search.senders.isEmpty() || stored.message.fromHeader.lowercase() in search.senders)
                }.take(RemoteCommandPlanner.MAX_CANDIDATES)
                .map { it.message }
        return CommandScan(found, emptyList())
    }

    override suspend fun fetchContent(message: MailMessage): MailMessage = message.copy(body = "body of ${message.id}")

    override suspend fun markRead(messageId: String) {
        inbox.firstOrNull { it.message.id == messageId }?.unread = false
    }

    override suspend fun checkForBounces(): List<BounceNotice> = emptyList()

    override fun clearSession() = Unit

    /** Test control: put a message in the inbox. [authenticatedFrom] is what this provider vouches for. */
    fun deliver(
        id: String,
        subject: String,
        from: String,
        authenticatedFrom: String? = null,
    ) {
        inbox +=
            Stored(
                MailMessage(
                    id = id,
                    threadId = "t-$id",
                    subject = subject,
                    body = "",
                    referencedMessageIds = emptySet(),
                    rfcMessageId = "rfc-$id@fake.invalid",
                    fromHeader = from,
                    authenticatedFromAddress = authenticatedFrom,
                ),
            )
    }
}
```

- [ ] **Step 3: Write the contract test**

`UNIT/MailTransportContract.kt`:

```kotlin
package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.email.CommandSearch
import com.scifsidekick.cleanroom.email.MailAuthRequiredException
import com.scifsidekick.cleanroom.email.MailTransport
import com.scifsidekick.cleanroom.messaging.EmailPayload
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The behavior every [MailTransport] must keep. Step 2 runs this same suite against the Microsoft
 * Graph transport's HTTP layer with canned responses.
 */
abstract class MailTransportContract {
    interface Harness {
        val transport: MailTransport

        fun signOut()

        fun deliver(
            id: String,
            subject: String,
            from: String,
            authenticatedFrom: String?,
        )
    }

    abstract fun newHarness(): Harness

    private val search = CommandSearch(listOf("[SCIF:ON]"), listOf("owner@example.com"))

    private fun payload() =
        EmailPayload(
            destinations = listOf("owner@example.com"),
            replyTarget = null,
            senderDisplay = "SCIF Sidekick",
            body = "hello",
            receivedAtMs = 1L,
            source = "test",
            participants = emptyList(),
            attachmentNotice = null,
            renderedSubject = "subject",
            renderedBody = "hello",
            filterName = "(test)",
        )

    @Test fun `a sender nobody vouches for is never authenticated`() =
        runBlocking {
            val h = newHarness()
            h.deliver("m1", "[SCIF:ON]", "owner@example.com", authenticatedFrom = null)
            val scan = h.transport.findCommands(search)
            assertEquals(1, scan.candidates.size)
            assertNull(scan.candidates.single().authenticatedFromAddress)
        }

    @Test fun `findCommands returns only unread mail and markRead is idempotent`() =
        runBlocking {
            val h = newHarness()
            h.deliver("m1", "[SCIF:ON]", "owner@example.com", "owner@example.com")
            assertEquals(1, h.transport.findCommands(search).candidates.size)
            h.transport.markRead("m1")
            h.transport.markRead("m1")
            assertTrue(h.transport.findCommands(search).candidates.isEmpty())
        }

    @Test fun `findCommands ignores mail from addresses outside the search`() =
        runBlocking {
            val h = newHarness()
            h.deliver("m1", "[SCIF:ON]", "stranger@example.com", null)
            assertTrue(h.transport.findCommands(search).candidates.isEmpty())
        }

    @Test fun `pollReplies skips known ids`() =
        runBlocking {
            val h = newHarness()
            h.deliver("m1", "Re: [SCIF:+15551234567]", "owner@example.com", "owner@example.com")
            h.deliver("m2", "Re: [SCIF:+15551234567]", "owner@example.com", "owner@example.com")
            val result = h.transport.pollReplies(setOf("m1"))
            assertEquals(listOf("m2"), result.replies.map { it.id })
        }

    @Test fun `send with verifyPriorDelivery does not duplicate`() =
        runBlocking {
            val h = newHarness()
            val first = h.transport.send(payload(), emptyList(), "key-1", verifyPriorDelivery = false)
            assertFalse(first.reconciled)
            val again = h.transport.send(payload(), emptyList(), "key-1", verifyPriorDelivery = true)
            assertTrue(again.reconciled)
            assertEquals(first.messageId, again.messageId)
        }

    @Test fun `a signed-out transport is inert and send fails loudly`() =
        runBlocking {
            val h = newHarness()
            h.deliver("m1", "[SCIF:ON]", "owner@example.com", "owner@example.com")
            h.signOut()
            assertFalse(h.transport.isAvailable)
            assertNull(h.transport.accountEmail())
            assertTrue(h.transport.pollReplies(emptySet()).replies.isEmpty())
            assertTrue(h.transport.findCommands(search).candidates.isEmpty())
            try {
                h.transport.send(payload(), emptyList(), "key-2", verifyPriorDelivery = false)
                throw AssertionError("send must throw MailAuthRequiredException when signed out")
            } catch (expected: MailAuthRequiredException) {
                // expected
            }
        }
}
```

`UNIT/FakeMailTransportContractTest.kt`:

```kotlin
package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.email.MailTransport

class FakeMailTransportContractTest : MailTransportContract() {
    override fun newHarness(): Harness =
        object : Harness {
            private val fake = FakeMailTransport()
            override val transport: MailTransport = fake

            override fun signOut() {
                fake.signedIn = false
            }

            override fun deliver(
                id: String,
                subject: String,
                from: String,
                authenticatedFrom: String?,
            ) = fake.deliver(id, subject, from, authenticatedFrom)
        }
}
```

- [ ] **Step 4: Run the contract tests**

Run: `./gradlew testDebugUnitTest --tests "com.scifsidekick.cleanroom.FakeMailTransportContractTest" --console=plain -q`
Expected: PASS (6 tests). If the build rejects `EmailPayload`'s constructor, open `MAIN/messaging/Models.kt` and match its actual parameter list; the call in `MailTransport` Task context matches `enqueueSystemEmail`'s construction in `SidekickRepository.kt` (`destinations`, `replyTarget`, `senderDisplay`, `body`, `receivedAtMs`, `source`, `participants`, `attachmentNotice`, `renderedSubject`, `renderedBody`, `filterName`).

- [ ] **Step 5: Write the seam test (instrumented)**

`INSTR/MailTransportSeamInstrumentedTest.kt`:

```kotlin
package com.scifsidekick.cleanroom

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scifsidekick.cleanroom.data.QueueChannel
import com.scifsidekick.cleanroom.messaging.MmsGateway
import com.scifsidekick.cleanroom.messaging.SmsGateway
import com.scifsidekick.cleanroom.service.AlertNotifier
import com.scifsidekick.cleanroom.service.QueueProcessor
import com.scifsidekick.cleanroom.service.RollingRateLimiter
import com.scifsidekick.cleanroom.util.AttachmentStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The send queue must work with any [com.scifsidekick.cleanroom.email.MailTransport], not just Gmail. */
@RunWith(AndroidJUnit4::class)
class MailTransportSeamInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val graph = AppGraph.from(context)

    @Before fun setUp() {
        graph.database.clearAllTables()
    }

    @After fun tearDown() {
        graph.database.clearAllTables()
    }

    @Test fun queueProcessorSendsThroughAnyMailTransport() =
        runBlocking {
            val fake = FakeMailTransport()
            graph.repository.enqueueSystemEmail(
                recipients = listOf("owner@example.com"),
                subject = "seam test",
                body = "body",
                reason = "seam test",
            )!!
            val processor =
                QueueProcessor(
                    graph.database,
                    graph.repository,
                    RollingRateLimiter(graph.database.deliveryAttemptDao()),
                    fake,
                    SmsGateway(context),
                    MmsGateway(context),
                    AttachmentStore(context),
                    AlertNotifier(context),
                )
            processor.drain(QueueChannel.EMAIL, 5)
            assertEquals(1, fake.sent.size)
            assertEquals("seam test", fake.sent.single().payload.renderedSubject)
            assertEquals(0, graph.database.queueDao().queuedEmailCount())
        }
}
```

- [ ] **Step 6: Run the seam test on the emulator only**

Boot the emulator, then:

```bash
export ANDROID_SERIAL=emulator-5554
adb devices   # must list emulator-5554; if the phone is also listed, the variable above is what protects it
./gradlew connectedDebugAndroidTest --console=plain -q
```

Expected: all instrumented tests pass, including the new one (59 total). If the first attempt reports 0 tests, the emulator was not fully booted; rerun once.

- [ ] **Step 7: Commit**

```bash
git add app/build.gradle.kts app/src/sharedTest app/src/test/java/com/scifsidekick/cleanroom/MailTransportContract.kt app/src/test/java/com/scifsidekick/cleanroom/FakeMailTransportContractTest.kt app/src/androidTest/java/com/scifsidekick/cleanroom/MailTransportSeamInstrumentedTest.kt
TZ=UTC git commit -q -m "Add fake mail transport, contract tests and a send-queue seam test" -m "The contract suite states what every transport must do; the seam test shows QueueProcessor works against any MailTransport."
```

---

## Task 7: Verify, document, push

**Files:**
- Modify: `docs/ARCHITECTURE.md`
- Modify: `CHANGELOG.md` is **not** changed (no user-visible change, no version bump).

- [ ] **Step 1: Leak check, no Gmail identifiers outside the adapter and the connect UI**

Run:

```bash
grep -rnE "GmailGateway|GmailOAuthManager|GmailApiException|ReauthorizationRequiredException|graph\.gmail\b|graph\.oauth\b" app/src/main --include=*.kt | grep -v "/email/Gmail" | cut -c1-140
```

Expected: only `AppGraph.kt` (construction), `ui/MainViewModel.kt` and `ui/MainActivity.kt` (connect, disconnect, push, connectivity test), and `service/GmailWatchRenewalWorker.kt` / `service/ForwardingService.kt` lines that use `graph.gmailPush` or the push scope. Anything else in `service/` or `util/` is a missed caller; fix it.

- [ ] **Step 2: Full verification**

```bash
export JAVA_HOME="C:\\Program Files\\Android\\Android Studio\\jbr" ANDROID_HOME="C:\\Users\\austi\\AppData\\Local\\Android\\Sdk" ANDROID_SERIAL=emulator-5554
./gradlew testDebugUnitTest assembleDebug lintDebug assembleRelease --console=plain -q
./gradlew connectedDebugAndroidTest --console=plain -q   # emulator booted; ANDROID_SERIAL set above
```

Expected: unit tests, lint and both builds succeed; instrumented tests pass (59).

- [ ] **Step 3: Smoke test on the emulator**

Install the debug APK on the emulator only (`adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk`), open the app, open Settings and Commands, confirm no crash (`adb -s emulator-5554 logcat -d -s AndroidRuntime:E` prints nothing).

- [ ] **Step 4: Document the seam**

In `docs/ARCHITECTURE.md` add a short section, "Mail transports", stating: all mail goes through `MailTransport` (`graph.mail`); `GmailGateway` is the only implementation; provider-specific search syntax, sender authentication, OAuth and push live in the Gmail adapter; ids are scoped by provider (`MailIds`); and a new transport must pass `MailTransportContract`.

- [ ] **Step 5: Commit and push**

```bash
git add docs/ARCHITECTURE.md
TZ=UTC git commit -q -m "Document the mail transport seam"
git status -s   # only .claude/ untracked
TZ=UTC git push origin main
```

Then wait for the `Build and unit tests` and `CodeQL` runs on the pushed commit to finish and confirm both succeeded.

---

## Self-review

**Spec coverage.**
- Interface and neutral types: Tasks 1 and 3. Message-id scoping: Task 2 (`MailIds`), no migration.
- Structured command search and Gmail query move: Tasks 2 and 3. Tests migrated, not changed in meaning.
- Sender authentication contract and `GmailAuthentication` move: Tasks 5 (move) and 6 (contract test).
- Errors (`MailAuthRequiredException`, five catch sites, `displayName` log text): Task 4.
- Wiring (`graph.mail`, `QueueProcessor`'s type): Tasks 3 and 4. `MainViewModel` stays on `graph.gmail`/`graph.oauth` by design.
- Debug fake transport stays in the Gmail adapter: no task touches `DebugControls`.
- Testing section (regression net, contract tests, seam test, mechanical grep gate): Tasks 5, 6 and 7.
- Spec deviation: `displayName` added to the interface; Task 3 step 7 records it in the spec.

**Placeholders.** None. The only conditional steps are the stated fallbacks (Gradle `sourceSets`, `EmailPayload` constructor) and each names exactly what to do.

**Type consistency.** `MailMessage`, `CommandScan`, `MailPollResult`, `MailReceipt`, `BounceNotice(messageId, ...)`, `CommandSearch(tags, senders)`, `MailIds.providerOf/nativeId/scoped`, `MailTransport` members, `MailAuthRequiredException`, and `FakeMailTransport` members are used with identical names and signatures across Tasks 1 to 6.

**Review focus.** Each of the five listed failure modes has a named test: 1 in `MailIdsTest` (Task 2), 2 and 5 in `MailTransportContract` (Task 6), 3 in the migrated `RemoteCommandPlannerTest` cases (Task 2), 4 in `MailTypesTest` (Task 4).
