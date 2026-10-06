package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.util.GmailAuthentication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthenticationResultsTest {
    private val from = "Boss <boss@agency.gov>"
    private val pass = "mx.google.com; dkim=pass header.i=@agency.gov; dmarc=pass (p=REJECT) header.from=agency.gov"

    @Test fun `no Authentication-Results header means not authenticated`() {
        assertNull(GmailAuthentication.authenticatedFrom(from, emptyList()))
    }

    @Test fun `mixed-case result and domain still authenticate`() {
        assertEquals(
            "boss@agency.gov",
            GmailAuthentication.authenticatedFrom("Boss <Boss@Agency.GOV>", listOf("MX.GOOGLE.COM; DMARC=PASS header.from=AGENCY.GOV")),
        )
    }

    @Test fun `dkim or spf pass alone is not enough`() {
        assertNull(GmailAuthentication.authenticatedFrom(from, listOf("mx.google.com; dkim=pass header.i=@agency.gov; spf=pass smtp.mailfrom=agency.gov")))
        assertNull(GmailAuthentication.authenticatedFrom(from, listOf("mx.google.com; dkim=pass; dmarc=none header.from=agency.gov")))
    }

    @Test fun `dmarc pass for a different or parent domain is rejected`() {
        assertNull(GmailAuthentication.authenticatedFrom("Boss <boss@mail.agency.gov>", listOf(pass)))
        assertNull(GmailAuthentication.authenticatedFrom(from, listOf("mx.google.com; dmarc=pass header.from=evil.example")))
    }

    @Test fun `a lookalike authserv-id is rejected`() {
        assertNull(GmailAuthentication.authenticatedFrom(from, listOf("mx.google.com.evil.example; dmarc=pass header.from=agency.gov")))
        assertNull(GmailAuthentication.authenticatedFrom(from, listOf("evilmx.google.com; dmarc=pass header.from=agency.gov")))
    }

    @Test fun `only the topmost header counts even if a lower one would pass`() {
        assertNull(GmailAuthentication.authenticatedFrom(from, listOf("mx.google.com; dmarc=fail header.from=agency.gov", pass)))
    }

    @Test fun `dmarc pass text outside a dmarc clause is ignored`() {
        assertNull(GmailAuthentication.authenticatedFrom(from, listOf("mx.google.com; dkim=fail reason=\"dmarc=pass header.from=agency.gov\"")))
    }

    @Test fun `a From header with two addresses or a smuggled header is rejected`() {
        assertNull(GmailAuthentication.authenticatedFrom("boss@agency.gov, other@agency.gov", listOf(pass)))
        assertNull(GmailAuthentication.authenticatedFrom("boss@agency.gov\r\nX: y", listOf(pass)))
    }
}
