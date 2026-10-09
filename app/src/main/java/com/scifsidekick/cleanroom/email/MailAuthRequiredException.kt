package com.scifsidekick.cleanroom.email

/** The account behind a [MailTransport] needs the user to reconnect it. Callers pause work and alert. */
open class MailAuthRequiredException(
    message: String,
) : Exception(message)
