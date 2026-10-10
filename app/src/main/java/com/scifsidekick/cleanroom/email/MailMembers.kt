package com.scifsidekick.cleanroom.email

/**
 * Whether Gmail is one of the router's members. It always is, except when the user disconnected
 * it on purpose and a Microsoft account is set up: then a removed Gmail is not reported or alerted
 * as needing reconnection. A Gmail that is signed in again counts at once. Gmail-only installs
 * always have it.
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

private const val GMAIL = "gmail"
private const val GRAPH = "graph"
