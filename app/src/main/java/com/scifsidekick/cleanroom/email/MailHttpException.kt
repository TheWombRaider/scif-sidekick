package com.scifsidekick.cleanroom.email

/** A mail provider's HTTP API answered with a failure status. Subclasses supply the message text. */
open class MailHttpException(
    val statusCode: Int,
    detail: String,
) : Exception(detail)
