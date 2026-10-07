package com.scifsidekick.cleanroom.util

/**
 * Decides what [com.scifsidekick.cleanroom.service.RemoteEnableWorker] does with each tagged
 * message it finds, so the order and the "junk can't hide a real command" rule are testable
 * without Gmail.
 */
object RemoteCommandPlanner {
    /** At most this many tagged messages are examined per run. */
    const val MAX_CANDIDATES = 20

    data class Candidate(
        val id: String,
        val subject: String,
        val authenticatedFrom: String?,
    )

    enum class Action {
        /** Not a usable command (no tag or ambiguous): mark read so it can't clog later runs. */
        CONSUME,

        /** An enable command from a sender not allowed to give it: log it, then mark read. */
        REJECT,

        /** Hand to the status responder, which checks the Status permission itself. */
        ANSWER_STATUS,

        /** Hand to the help responder, which checks the sender is on the authorized list itself. */
        ANSWER_HELP,

        /** Authorized enable command: apply it. Nothing after this one is examined. */
        APPLY_ENABLE,
    }

    data class Step(
        val candidate: Candidate,
        val action: Action,
    )

    /** [candidates] must be newest first. */
    fun plan(
        candidates: List<Candidate>,
        sendersJson: String,
    ): List<Step> {
        val steps = mutableListOf<Step>()
        for (candidate in candidates.take(MAX_CANDIDATES)) {
            val action =
                when (RemoteCommands.parse(candidate.subject)) {
                    RemoteCommand.ENABLE ->
                        if (RemoteControlCodec.isAuthorized(sendersJson, candidate.authenticatedFrom, RemoteControlCodec.Sender::canEnable)) {
                            Action.APPLY_ENABLE
                        } else {
                            Action.REJECT
                        }
                    RemoteCommand.STATUS -> Action.ANSWER_STATUS
                    RemoteCommand.HELP -> Action.ANSWER_HELP
                    else -> Action.CONSUME
                }
            steps += Step(candidate, action)
            if (action == Action.APPLY_ENABLE) break
        }
        return steps
    }
}

/** Builds the Gmail search for [RemoteCommandPlanner]'s candidates. */
object RemoteCommandQuery {
    // Deliberately conservative: an address with any other character is left out of the sender
    // filter rather than risk it changing the search's meaning.
    private val safeAddress = Regex("[a-z0-9._%+-]+@[a-z0-9.-]+")

    /**
     * Narrows the search to the allowlisted senders so mail from anyone else never competes for
     * a slot. If any allowlisted address can't be expressed safely the filter is dropped entirely
     * (every tagged message is then examined, still capped and still authorization-checked).
     * Null means nobody is allowlisted, so there is nothing to look for.
     */
    fun build(
        commandTags: List<String>,
        sendersJson: String,
    ): String? {
        if (commandTags.isEmpty()) return null
        val addresses =
            RemoteControlCodec
                .fromJson(sendersJson)
                .mapNotNull { ComposeAuthorization.canonicalAddress(it.address) }
                .distinct()
        if (addresses.isEmpty()) return null
        val subjectClause = commandTags.joinToString(" ", prefix = "{", postfix = "}") { "subject:\"$it\"" }
        val fromClause =
            if (addresses.all(safeAddress::matches)) {
                addresses.joinToString(" ", prefix = " {", postfix = "}") { "from:$it" }
            } else {
                ""
            }
        return "in:inbox is:unread newer_than:2d $subjectClause$fromClause"
    }
}
