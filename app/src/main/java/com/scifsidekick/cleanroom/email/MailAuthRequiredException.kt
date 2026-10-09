package com.scifsidekick.cleanroom.email

/**
 * The account behind a [MailTransport] needs the user to reconnect it. Callers pause work and alert.
 * [providerId] and [displayName] name the provider that failed so the alert is about the right one.
 */
open class MailAuthRequiredException(
    message: String,
    val providerId: String,
    val displayName: String,
) : Exception(message)
