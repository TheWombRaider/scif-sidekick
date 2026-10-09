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
