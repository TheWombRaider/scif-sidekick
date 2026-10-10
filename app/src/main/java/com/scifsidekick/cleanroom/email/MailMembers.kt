package com.scifsidekick.cleanroom.email

/**
 * Whether Gmail is one of the router's members. Always, except when it was disconnected on purpose
 * (or never connected, see [gmailDisconnectedAfterOutlookSignIn]) and a Microsoft account is set up:
 * then it is not alerted as needing reconnection and status says "not connected". A Gmail signed in
 * again counts at once.
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
 * Each account's display name and whether it can send now, Gmail first. Gmail is left out when it
 * is not a member, so it is not reported as needing reconnection.
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
 * The "Gmail disconnected on purpose" flag after an Outlook sign-in. Set only when Gmail was never
 * connected, so an expired Gmail stays a member and keeps its "Reconnect Gmail" alert.
 * Connecting Gmail clears it.
 */
fun gmailDisconnectedAfterOutlookSignIn(
    current: Boolean,
    gmailEverConnected: Boolean,
): Boolean = current || !gmailEverConnected

/**
 * Whether Gmail has ever been connected on this install: its owner address was seeded (the
 * one-time setup flag set the first time Gmail connects), it is authorized now, or a grant was
 * ever recorded (covers a Gmail whose address was never readable).
 */
fun gmailEverConnected(
    remoteOwnerSeeded: Boolean,
    gmailAuthorized: Boolean,
    gmailEverGranted: Boolean = false,
): Boolean = remoteOwnerSeeded || gmailAuthorized || gmailEverGranted

private const val GMAIL = "gmail"
private const val GRAPH = "graph"
