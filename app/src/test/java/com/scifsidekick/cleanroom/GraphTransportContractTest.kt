package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.email.MailIds
import com.scifsidekick.cleanroom.email.MailTransport
import com.scifsidekick.cleanroom.email.graph.GraphGateway
import com.scifsidekick.cleanroom.email.graph.MsOAuthManager

/** The shared [MailTransportContract], run against [GraphGateway] over a [FakeGraphServer]. */
class GraphTransportContractTest : MailTransportContract() {
    override fun newHarness(): MailTransportContract.Harness =
        object : MailTransportContract.Harness {
            private val server = FakeGraphServer()
            private val oauth =
                MsOAuthManager(
                    clientId = { "client-id" },
                    store = InMemoryRefreshTokenStore("refresh-1"),
                    client = server.client(),
                    nowMs = { server.nowMs },
                )
            override val transport: MailTransport = GraphGateway(oauth, server.client(), nowMs = { server.nowMs }, sleep = {})

            override fun signOut() {
                oauth.disconnect()
            }

            override fun deliver(
                id: String,
                subject: String,
                from: String,
                authenticatedFrom: String?,
            ): String {
                val domain = (authenticatedFrom ?: from).substringAfter('@')
                val results =
                    if (authenticatedFrom != null) {
                        "spf=pass smtp.mailfrom=$domain; dkim=pass header.d=$domain; dmarc=pass action=none header.from=$domain; compauth=pass reason=100"
                    } else {
                        "spf=fail smtp.mailfrom=$domain; dkim=none; dmarc=fail action=none header.from=$domain; compauth=fail reason=000"
                    }
                // Each delivery is a second newer than the last, so newest-first order is well defined.
                server.nowMs += 1_000
                val native = server.deliver(from, subject, "body of $id", listOf("Authentication-Results" to results), id = id)
                return MailIds.scoped("graph", native)
            }
        }
}
