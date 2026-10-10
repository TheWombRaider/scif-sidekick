package com.scifsidekick.cleanroom.email

/**
 * Whether Gmail is one of the router's members. It always is, except when the user disconnected
 * it on purpose (or signed in to Outlook on an install where Gmail was never connected, see
 * [gmailDisconnectedAfterOutlookSignIn]) and a Microsoft account is set up: then a removed Gmail
 * is not alerted as needing reconnection, and status summaries say "Gmail authorization: not
 * connected" instead of "NEEDS RECONNECTING" (see [mailAccountStatuses]). A Gmail that is signed
 * in again counts at once. Gmail-only installs always have it.
 */
fun gmailIsMember(
    gmailDisconnectedOnPurpose: Boolean,
    microsoftConfigured: Boolean,
    gmailAvailable: () -> Boolean,
): Boolean = !microsoftConfigured || !gmailDisconnectedOnPurpose || gmailAvailable()

/**
 * The router's member provider ids in preference order ("gmail", "graph"). Never empty: with no
 * Microsoft account, Gmail is the only member whatever its state.
 */
fun mailMemberIds(
    gmailDisconnectedOnPurpose: Boolean,
    microsoftConfigured: Boolean,
    preferGraph: Boolean,
    gmailAvailable: () -> Boolean,
): List<String> {
    if (!microsoftConfigured) return listOf(GMAIL)
    val gmail = gmailIsMember(gmailDisconnectedOnPurpose, true, gmailAvailable)
    return when {
        !gmail -> listOf(GRAPH)
        preferGraph -> listOf(GRAPH, GMAIL)
        else -> listOf(GMAIL, GRAPH)
    }
}

/**
 * Each account's display name and whether it can send now, Gmail first, then Outlook when a
 * Microsoft account is set up. Gmail is left out when it is not a member ([gmailIsMember] false),
 * so it is not reported as needing reconnection. Gmail-only installs: just `"Gmail" to available`.
 */
fun mailAccountStatuses(
    gmailIsMember: Boolean,
    gmailAvailable: () -> Boolean,
    microsoftConfigured: Boolean,
    outlookAvailable: () -> Boolean,
): List<Pair<String, Boolean>> =
    buildList {
        if (gmailIsMember) add("Gmail" to gmailAvailable())
        if (microsoftConfigured) add("Outlook" to outlookAvailable())
    }

/**
 * The "Gmail disconnected on purpose" flag after an Outlook sign-in succeeds. Set only when Gmail
 * was never connected on this install, so an Outlook-only install is not alerted to reconnect a
 * Gmail it never had. A Gmail that was connected and is merely signed out (expired) is left a
 * member, so its "Reconnect Gmail" alert keeps working. Never cleared here; connecting Gmail does.
 */
fun gmailDisconnectedAfterOutlookSignIn(
    current: Boolean,
    gmailEverConnected: Boolean,
): Boolean = current || !gmailEverConnected

/**
 * Whether Gmail has ever been connected on this install: its owner address was seeded (the
 * one-time setup flag set the first time Gmail connects) or it is authorized now.
 */
fun gmailEverConnected(
    remoteOwnerSeeded: Boolean,
    gmailAuthorized: Boolean,
): Boolean = remoteOwnerSeeded || gmailAuthorized

private const val GMAIL = "gmail"
private const val GRAPH = "graph"
