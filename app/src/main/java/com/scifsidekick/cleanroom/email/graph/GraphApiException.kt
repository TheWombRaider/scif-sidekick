package com.scifsidekick.cleanroom.email.graph

import com.scifsidekick.cleanroom.email.MailHttpException

/**
 * Microsoft Graph answered with a failure status. Status 0 means the request was never sent because
 * the Outlook access token could not be refreshed for a reason other than a revoked sign-in, which
 * the failover router treats as a definite (not ambiguous) failure.
 */
class GraphApiException(
    statusCode: Int,
    detail: String,
) : MailHttpException(statusCode, "Graph API HTTP $statusCode: $detail")
