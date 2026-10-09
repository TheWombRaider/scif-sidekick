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
