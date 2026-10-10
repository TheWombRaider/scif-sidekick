package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.email.GmailAuthentication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Adversarial shapes for Gmail's Authentication-Results parsing, mirroring GraphAuthenticationTest,
 * plus the real header shapes Gmail writes, which must keep authenticating.
 */
class GmailAuthenticationHardeningTest {
    private val from = "Boss <boss@agency.gov>"

    private fun auth(value: String, fromHeader: String = from) = GmailAuthentication.authenticatedFrom(fromHeader, listOf(value))

    // ---- real Gmail shapes ----

    @Test fun `a realistic Gmail header with dkim, spf comment and dmarc policy comment is accepted`() {
        val real =
            "mx.google.com; dkim=pass header.i=@agency.gov header.s=sel header.b=abc; " +
                "spf=pass (google.com: domain of boss@agency.gov designates 209.85.220.41 as permitted sender) smtp.mailfrom=boss@agency.gov; " +
                "dmarc=pass (p=REJECT sp=REJECT dis=NONE) header.from=agency.gov"
        assertEquals("boss@agency.gov", auth(real))
    }

    @Test fun `a realistic Gmail header for ARC sealed mail is accepted`() {
        // Gmail reports the ARC chain's own results, dmarc included, in a comment on the arc clause.
        val real =
            "mx.google.com; dkim=pass header.i=@agency.gov header.s=selector1 header.b=Xy+Z/abc; " +
                "arc=pass (i=1 spf=pass spfdomain=agency.gov dkim=pass dkdomain=agency.gov dmarc=pass fromdomain=agency.gov); " +
                "spf=pass (google.com: domain of boss@agency.gov designates 2a01:111:f403:c10c::1 as permitted sender) smtp.mailfrom=boss@agency.gov; " +
                "dmarc=pass (p=REJECT sp=REJECT dis=NONE) header.from=agency.gov"
        assertEquals("boss@agency.gov", auth(real))
    }

    @Test fun `a realistic Gmail header with a dara clause is accepted`() {
        val real =
            "mx.google.com; dkim=pass header.i=@agency.gov header.s=20230601 header.b=abc; " +
                "spf=pass (google.com: domain of boss@agency.gov designates 209.85.220.41 as permitted sender) smtp.mailfrom=boss@agency.gov; " +
                "dmarc=pass (p=NONE sp=QUARANTINE dis=NONE) header.from=agency.gov; dara=pass header.i=@agency.gov"
        assertEquals("boss@agency.gov", auth(real))
    }

    @Test fun `a folded Gmail header is accepted`() {
        assertEquals("boss@agency.gov", auth("mx.google.com;\r\n       dkim=pass header.i=@agency.gov;\r\n       dmarc=pass (p=REJECT sp=REJECT dis=NONE)\r\n header.from=agency.gov"))
        assertEquals("boss@agency.gov", auth("mx.google.com;\n\tdmarc=pass header.from=agency.gov"))
    }

    @Test fun `a dmarc fail from Gmail is still rejected`() {
        assertNull(auth("mx.google.com; spf=pass smtp.mailfrom=boss@agency.gov; dmarc=fail (p=REJECT sp=REJECT dis=QUARANTINE) header.from=agency.gov"))
        assertNull(auth("mx.google.com; dmarc=bestguesspass header.from=agency.gov"))
    }

    // ---- a clause faked inside a quoted string or a comment ----

    @Test fun `a semicolon inside a quoted string cannot start a dmarc clause`() {
        assertNull(auth("mx.google.com; dkim=fail reason=\"x; dmarc=pass header.from=agency.gov\""))
        assertNull(auth("mx.google.com; dkim=fail reason=\"x; dmarc=pass header.from=agency.gov \" "))
    }

    @Test fun `a semicolon inside a comment cannot start a dmarc clause`() {
        assertNull(auth("mx.google.com; spf=pass (x; dmarc=pass header.from=agency.gov)"))
        assertNull(auth("mx.google.com; spf=pass (x; dmarc=pass header.from=agency.gov ) "))
    }

    @Test fun `an escaped quote does not close a quoted string`() {
        assertNull(auth("mx.google.com; x=\"a\\\"; dmarc=pass header.from=agency.gov z\" \""))
        assertNull(auth("mx.google.com; x=\"a\\\"; dmarc=pass header.from=agency.gov z\""))
        assertEquals("boss@agency.gov", auth("mx.google.com; dmarc=pass reason=\"a\\\"b\" header.from=agency.gov"))
    }

    @Test fun `an escaped parenthesis does not close a comment`() {
        assertNull(auth("mx.google.com; (a \\) ; dmarc=pass header.from=agency.gov b) "))
        assertEquals("boss@agency.gov", auth("mx.google.com; dmarc=pass (a \\) b) header.from=agency.gov"))
    }

    @Test fun `nested comments hide a forged clause`() {
        assertNull(auth("mx.google.com; spf=pass (a (b) ; dmarc=pass header.from=agency.gov c)"))
        assertEquals("boss@agency.gov", auth("mx.google.com; dmarc=pass (policy (reject)) header.from=agency.gov"))
    }

    @Test fun `a stray closing parenthesis is rejected`() {
        assertNull(auth("mx.google.com; dmarc=pass (a)) header.from=agency.gov"))
        assertNull(auth("mx.google.com; ) dmarc=pass header.from=agency.gov"))
    }

    @Test fun `an unterminated quote or comment is rejected`() {
        assertNull(auth("mx.google.com; dmarc=pass header.from=agency.gov \"unterminated"))
        assertNull(auth("mx.google.com; dmarc=pass header.from=agency.gov (unterminated"))
        assertNull(auth("mx.google.com; dmarc=pass header.from=agency.gov (a (b) still open"))
        assertNull(auth("mx.google.com; dmarc=pass header.from=agency.gov \"abc\\"))
    }

    @Test fun `a forged comment opener cannot hide the real failing clause`() {
        assertNull(auth("mx.google.com; dkim=pass header.i=@evil.com(; dmarc=fail header.from=x); dmarc=pass header.from=agency.gov"))
    }

    // ---- the dmarc mention must be unique ----

    @Test fun `two dmarc clauses are rejected`() {
        assertNull(auth("mx.google.com; dmarc=pass header.from=agency.gov; dmarc=fail header.from=agency.gov"))
        assertNull(auth("mx.google.com; dmarc=pass header.from=agency.gov; dmarc=pass header.from=agency.gov"))
        assertNull(auth("mx.google.com; dmarc=pass dmarc=fail header.from=agency.gov"))
    }

    @Test fun `a comment outside the arc clause that mentions dmarc is rejected`() {
        assertNull(auth("mx.google.com; spf=pass (dmarc is great) smtp.mailfrom=agency.gov; dmarc=pass header.from=agency.gov"))
        assertNull(auth("mx.google.com; dmarc=pass (dmarc policy=reject) header.from=agency.gov"))
    }

    @Test fun `an arc comment cannot carry the only dmarc result`() {
        assertNull(auth("mx.google.com; arc=pass (i=1 dmarc=pass fromdomain=agency.gov); spf=pass smtp.mailfrom=agency.gov"))
        assertNull(auth("mx.google.com; arc=pass (i=1 dmarc=pass fromdomain=agency.gov); dmarc=fail (p=REJECT) header.from=agency.gov"))
    }

    @Test fun `an arc comment that is not plain does not excuse its dmarc mention`() {
        assertNull(auth("mx.google.com; arc=pass (i=1 (dmarc=pass)); dmarc=pass header.from=agency.gov"))
        assertNull(auth("mx.google.com; arc=pass (i=1 \"dmarc=pass\"); dmarc=pass header.from=agency.gov"))
        assertNull(auth("mx.google.com; spf=pass x arc=pass (dmarc=pass); dmarc=pass header.from=agency.gov"))
    }

    // ---- the result must be exactly pass ----

    @Test fun `a result that merely starts or ends with pass is rejected`() {
        assertNull(auth("mx.google.com; dmarc=pass-through header.from=agency.gov"))
        assertNull(auth("mx.google.com; dmarc=passed header.from=agency.gov"))
        assertNull(auth("mx.google.com; dmarc=bestguesspass header.from=agency.gov"))
        assertNull(auth("mx.google.com; dmarc=pass/x header.from=agency.gov"))
    }

    @Test fun `a non ASCII result that case folds to pass is rejected`() {
        // U+017F (long s) upper-cases to S.
        assertNull(auth("mx.google.com; dmarc=paſſ header.from=agency.gov"))
    }

    // ---- header.from must be the dmarc clause's own, single, property ----

    @Test fun `header from in another clause does not count`() {
        assertNull(auth("mx.google.com; dmarc=pass; dkim=pass header.from=agency.gov"))
        assertNull(auth("mx.google.com; spf=pass header.from=agency.gov; dmarc=pass"))
    }

    @Test fun `a dotted prefix on header from does not count`() {
        assertNull(auth("mx.google.com; dmarc=pass smtp.header.from=agency.gov"))
    }

    @Test fun `multiple header from properties in the dmarc clause are rejected`() {
        assertNull(auth("mx.google.com; dmarc=pass header.from=evil.com header.from=agency.gov"))
        assertNull(auth("mx.google.com; dmarc=pass header.from=agency.gov header.from=evil.com"))
    }

    // ---- domain comparison ----

    @Test fun `a Kelvin sign in header from does not fold to an ASCII k`() {
        val kelvin = "K"
        assertNull(auth("mx.google.com; dmarc=pass header.from=${kelvin}agency.gov", "Boss <boss@kagency.gov>"))
        assertNull(auth("mx.google.com; dmarc=pass header.from=agency.gov$kelvin", "Boss <boss@agency.govk>"))
        assertNull(auth("mx.google.com; dmarc=pass header.from=agéncy.gov"))
    }

    @Test fun `exactly one trailing dot is removed from header from`() {
        assertEquals("boss@agency.gov", auth("mx.google.com; dmarc=pass header.from=agency.gov."))
        assertNull(auth("mx.google.com; dmarc=pass header.from=agency.gov.."))
        assertNull(auth("mx.google.com; dmarc=pass header.from=agency.gov..."))
    }

    @Test fun `mixed case header from still matches`() {
        assertEquals("boss@agency.gov", auth("mx.google.com; dmarc=pass header.from=Agency.GOV", "BOSS@AGENCY.GOV"))
        assertNull(auth("mx.google.com; dmarc=pass header.from=evilagency.gov"))
        assertNull(auth("mx.google.com; dmarc=pass header.from=agency.gov.evil.com"))
    }

    @Test fun `the authserv-id check is unchanged`() {
        assertNull(auth("mx.google.com.evil.example; dmarc=pass header.from=agency.gov"))
        assertNull(auth("dmarc=pass header.from=agency.gov"))
    }
}
