# Mail transport interface (step 1 of 3)

Date: 2026-10-08. Status: draft for review.

## Why

SCIF Sidekick talks to exactly one mail provider, Gmail, through one class (`GmailGateway`). When Google's sign-in drops, the whole app goes silent, and silence is the worst failure for a device whose job is staying reachable. The goal of this three-step effort is a very reliable mail link: Microsoft Graph (Outlook.com / Microsoft 365) joins Gmail, and the app keeps working when either mailbox is down.

This spec covers **step 1 only**: put an interface in front of the mail code and move Gmail behind it, with no change in behavior. The other two steps are outlined at the end so the interface is shaped for them, but each gets its own spec and plan.

| Step | What | Ships on its own? |
|---|---|---|
| 1 | `MailTransport` interface, Gmail adapter, neutral types | Yes. No behavior change. |
| 2 | Microsoft Graph transport (MSAL sign-in, send, poll, commands, bounces, sender check) | Yes, as a second selectable account. |
| 3 | Multi-account router, failover, account UI | Yes. |

## Success criteria

- Every existing unit and instrumented test passes with only mechanical edits (renames, import changes). No test changes meaning.
- Outside the Gmail adapter and the Gmail connect/disconnect UI, no code names a Gmail type, a Gmail search string, or a Gmail header.
- A second implementation can be added without touching the send queue, the reply poller, the remote-command workers or the responders.
- Command authorization is never weaker than today (see "Sender authentication").

## Non-goals

- No Microsoft code, no new accounts, no new settings, no UI change.
- No database schema change and no migration.
- Gmail push (Pub/Sub watch) stays Gmail-only and outside the interface.
- No change to retry, rate limit, circuit breaker or receipt logic.

## Current state

`GmailGateway` (718 lines) is the only mail code. `AppGraph` builds it as `graph.gmail` next to `graph.oauth` (`GmailOAuthManager`) and `graph.gmailPush`. Callers:

- `ForwardingService`: `unreadReplies`, `fetchContent`, `markRead`, `checkForBounces`, `isAvailable`.
- `QueueProcessor`: `send`, `isAvailable`. It also reads `GmailDeliveryReceipt` from `DeliveryOutcome`.
- `RemoteEnableWorker`: `findRemoteCommands(query)`, `markRead`. The query is a Gmail search string built by `RemoteCommandQuery`.
- `RemoteStatusResponder`, `RemoteHelpResponder`, `RemoteCommandReceipt`, `HeartbeatEmailWorker`, `SelfTestReceipt`: `isAvailable`, `markRead`, `currentAccountEmail`.
- `MainViewModel` / `MainActivity`: connect, disconnect, account address, connectivity test, push toggles. These stay Gmail-specific in step 1.

Gmail-specific things that must stay inside the adapter:

- The search syntax in `RemoteCommandQuery.build` and the `newer_than`, `in:inbox` terms in the poll.
- `GmailAuthentication.authenticatedFrom`, which trusts only an `Authentication-Results` header whose service is `mx.google.com`.
- `GmailOAuthManager`, `GmailPushGateway`, `GmailApiException`.
- The history-cursor optimization in the poll.

## Design

### Package layout

All in `com.scifsidekick.cleanroom.email`:

- `MailTransport.kt`: the interface.
- `MailTypes.kt`: neutral data types and the id helpers.
- `GmailGateway.kt`: stays, now `class GmailGateway(...) : MailTransport`. It is not renamed in this step, to keep the diff reviewable.
- `GmailCommandQuery.kt`: Gmail search-string building, moved out of `util/RemoteCommandPlanner.kt`.

### Interface

```kotlin
interface MailTransport {
    /** Stable lowercase id, "gmail" now, "graph" in step 2. Used as the message-id prefix. */
    val providerId: String
    val displayName: String

    /** Whether this transport can currently be used (authorized, or the debug fake is on). */
    val isAvailable: Boolean

    suspend fun accountEmail(): String?

    suspend fun send(
        payload: EmailPayload,
        attachmentPaths: List<String>,
        deliveryKey: String,
        verifyPriorDelivery: Boolean,
    ): MailReceipt

    suspend fun pollReplies(knownMessageIds: Set<String>): MailPollResult

    suspend fun findCommands(search: CommandSearch): CommandScan

    suspend fun fetchContent(message: MailMessage): MailMessage

    suspend fun markRead(messageId: String)

    suspend fun checkForBounces(): List<BounceNotice>

    fun clearSession()
}
```

The method names and semantics are the current `GmailGateway` ones. `unreadReplies` becomes `pollReplies` and `findRemoteCommands` becomes `findCommands`; the old doc comment on `unreadReplies` already explains that it never filtered on unread, so the new name is more honest.

### Neutral types (renames, same fields)

| Today | Becomes |
|---|---|
| `GmailReply` | `MailMessage` |
| `GmailPollResult` | `MailPollResult` |
| `RemoteCommandScan` | `CommandScan` |
| `GmailDeliveryReceipt` | `MailReceipt` |
| `BounceNotice.gmailMessageId` | `BounceNotice.messageId` |

`MailMessage.authenticatedFromAddress` keeps its meaning: the sender address if, and only if, this provider vouches for it. Null means "cannot be trusted as a command source".

### Message ids and provider scope

Gmail ids are hex strings with no colon. To keep two providers from colliding in the processed-ids table and in routing:

- Gmail ids stay exactly as stored today (no prefix).
- Every other transport prefixes its ids as `<providerId>:<native id>`.
- `MailIds.providerOf(id)` returns `"gmail"` when there is no colon, otherwise the prefix. `MailIds.nativeId(id)` strips it.

Existing rows need no migration. The repository functions `recentProcessedGmailIds`, `recordIgnoredGmailCandidate` and the `gmailMessageId` column keep their names in this step; a rename can ride along with step 3 if wanted, because renaming a column needs a schema migration and this step ships none.

### Command search

`RemoteCommandQuery.build(tags, sendersJson)` returns a Gmail string today. It splits in two:

1. `RemoteCommandSearch.plan(tags, sendersJson): CommandSearch?` (in `util`, provider-neutral). It returns null when there are no tags or nobody is allowlisted, exactly as `build` does now. Otherwise it returns `CommandSearch(tags, senders)`.
2. `GmailCommandQuery.build(search): String` (in `email`). It owns the `safeAddress` check and drops the sender filter if any address cannot be expressed safely, as `build` does now.

The existing `RemoteCommandPlannerTest` cases for `build` move to cover both halves with the same inputs and expected outputs.

### Sender authentication

This is the safety-critical contract, and the interface fixes its shape without weakening it:

- Each transport computes `authenticatedFromAddress` itself, from its own provider's evidence.
- It returns an address only when the provider's own authentication result shows a DMARC pass aligned with the `From` domain. Anything unrecognized, missing or ambiguous returns null.
- Callers never inspect headers. They only check `authenticatedFromAddress` against the allowlist, as they do now.

`GmailAuthentication` moves into the Gmail adapter's package unchanged. The Graph version in step 2 must be written against real Outlook.com message headers, not assumed.

### Errors

- `ReauthorizationRequiredException` becomes a subclass of a new `MailAuthRequiredException(message)`. All five catch sites that name the Gmail class (`ForwardingService` x4, `QueueProcessor`) catch the base class. Behavior is identical for Gmail.
- `GmailApiException` stays internal to the Gmail adapter and `GmailPushGateway`.
- Log text that says "Gmail" in generic code paths (for example "Gmail reply poll failed", "Gmail message X accepted") takes `displayName` instead, so a second provider produces correct log lines. Gmail's lines stay word-for-word the same when `providerId == "gmail"`.

### Wiring

- `AppGraph` gets `val mail: MailTransport = gmail`.
- `ForwardingService`, `QueueProcessor`, `RemoteEnableWorker`, the responders, `RemoteCommandReceipt`, `HeartbeatEmailWorker` and `SelfTestReceipt` use `graph.mail`. `QueueProcessor`'s constructor parameter becomes `MailTransport`.
- `MainViewModel` / `MainActivity` keep using `graph.gmail` and `graph.oauth` for Gmail sign-in, push and the connectivity test.
- Step 3 replaces the `graph.mail` initializer with a router. Callers do not change.

### Debug fake transport

`DebugControls.fakeEmailTransport` stays inside `GmailGateway` for this step, so the instrumented tests do not move. Step 3 promotes it to its own `FakeMailTransport` in debug builds.

## Testing

- **Regression net:** the full existing suite, run on the emulator only (`ANDROID_SERIAL=emulator-5554`), plus unit tests, lint and a release build.
- **Contract tests:** a new abstract `MailTransportContractTest` with the behavioral guarantees every transport must meet: `markRead` is idempotent, `pollReplies` skips known ids, `findCommands` never returns an unauthenticated address as authenticated, `send` with `verifyPriorDelivery` does not duplicate, and `isAvailable` is false when signed out. It runs against a `FakeMailTransport` in test sources now. Step 2 runs the same suite against the Graph transport's HTTP layer with canned responses.
- **Caller test:** one unit test drives the poll, send-queue and command-worker logic with a `FakeMailTransport` and asserts no Gmail type is needed, proving the seam.
- **Mechanical checks:** a grep gate in the plan (not CI) that lists remaining `Gmail` identifiers outside the adapter and the connect UI.

## Risks

- **Rename blast radius.** About 20 files change, but the edits are renames and imports. Mitigation: rename in one commit with no logic changes, then move the Gmail query and authentication code in a second.
- **Silent behavior drift in log strings.** The app's History text is user-visible and some tests match on it. Mitigation: grep the tests for the affected strings and keep Gmail's output identical.
- **Interface too Gmail-shaped.** Graph has no search string, uses delta queries instead of history ids, and has no thread id for some messages. The interface already avoids query strings and cursors, and `MailMessage.threadId` is documented as best-effort and unused by callers. Step 2's spec re-checks the interface against Graph before any code is written, and may amend this one.

## Outline of steps 2 and 3 (not part of this spec)

**Step 2, Microsoft Graph transport.** The user creates a free Entra app registration (personal accounts supported, public client, redirect URI for the Android package). The app signs in with MSAL (scopes `Mail.ReadWrite`, `Mail.Send`, `offline_access`) and calls Graph with the existing OkHttp client: send via `sendMail`, poll via the inbox delta query, command lookup via `$filter`, `internetMessageHeaders` for the sender check, bounces via the same narrow search the Gmail adapter uses. Sender authentication is built and tested against real Outlook.com headers before it is trusted.

**Step 3, router and failover.** `MailRouter : MailTransport` wraps an ordered list of transports.

- **Receiving:** every enabled transport is polled for replies and commands, because a reply comes back to whichever mailbox sent the forward.
- **Sending:** send through the preferred transport. On a definite failure or an auth drop, fail over to the next one. On an ambiguous failure (timeout after the request was sent), first check the failed transport's Sent folder using the existing `verifyPriorDelivery` logic. If that check cannot run, send through the next transport and log a possible duplicate. The app prefers a rare duplicate to a lost forward.
- **Visibility:** the heartbeat and status summaries report each account's state, and a drop on one account alerts while the other keeps the link alive.
- **UI:** add, remove and reorder accounts in Settings.
