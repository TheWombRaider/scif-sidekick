package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.email.MailTransport

class FakeMailTransportContractTest : MailTransportContract() {
    override fun newHarness(): MailTransportContract.Harness =
        object : MailTransportContract.Harness {
            private val fake = FakeMailTransport()
            override val transport: MailTransport = fake

            override fun signOut() {
                fake.signedIn = false
            }

            override fun deliver(
                id: String,
                subject: String,
                from: String,
                authenticatedFrom: String?,
            ) = fake.deliver(id, subject, from, authenticatedFrom)
        }
}
