package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.email.graph.GraphAuthentication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphAuthenticationTest {
    private val from = "Boss <boss@agency.gov>"
    private val pass =
        "spf=pass smtp.mailfrom=agency.gov; dkim=pass header.d=agency.gov; dmarc=pass action=none header.from=agency.gov; compauth=pass reason=100"
    private val name = "Authentication-Results"

    private fun auth(value: String, fromHeader: String = from) =
        GraphAuthentication.authenticatedFrom(fromHeader, listOf(name to value))

    @Test fun `aligned dmarc pass returns the address`() {
        assertEquals("boss@agency.gov", auth(pass))
    }

    @Test fun `dmarc fail is rejected`() {
        assertNull(auth(pass.replace("dmarc=pass", "dmarc=fail")))
    }

    @Test fun `dmarc none is rejected`() {
        assertNull(auth(pass.replace("dmarc=pass", "dmarc=none")))
    }

    @Test fun `no Authentication-Results header is rejected`() {
        assertNull(GraphAuthentication.authenticatedFrom(from, emptyList()))
        assertNull(GraphAuthentication.authenticatedFrom(from, listOf("Received" to "from x", "Subject" to pass)))
    }

    @Test fun `header name is case-insensitive`() {
        assertEquals("boss@agency.gov", GraphAuthentication.authenticatedFrom(from, listOf("authentication-results" to pass)))
        assertEquals("boss@agency.gov", GraphAuthentication.authenticatedFrom(from, listOf("AUTHENTICATION-RESULTS" to pass)))
    }

    @Test fun `first header failing and a later one passing is rejected`() {
        val headers = listOf(name to pass.replace("dmarc=pass", "dmarc=fail"), name to pass)
        assertNull(GraphAuthentication.authenticatedFrom(from, headers))
    }

    @Test fun `first header passing and a later one failing returns the address`() {
        val headers = listOf(name to pass, name to pass.replace("dmarc=pass", "dmarc=fail"))
        assertEquals("boss@agency.gov", GraphAuthentication.authenticatedFrom(from, headers))
    }

    @Test fun `only the first Authentication-Results counts when other headers precede it`() {
        val headers = listOf("Received" to "x", name to "dmarc=fail header.from=agency.gov", "X-Other" to "y", name to pass)
        assertNull(GraphAuthentication.authenticatedFrom(from, headers))
    }

    @Test fun `a blank first header is rejected even if a later one passes`() {
        assertNull(GraphAuthentication.authenticatedFrom(from, listOf(name to "", name to pass)))
        assertNull(GraphAuthentication.authenticatedFrom(from, listOf(name to "   \r\n  ", name to pass)))
    }

    @Test fun `subdomain header from is rejected for a parent domain From`() {
        assertNull(auth(pass.replace("header.from=agency.gov", "header.from=mail.agency.gov")))
    }

    @Test fun `parent header from is rejected for a subdomain From`() {
        assertNull(auth(pass, "Boss <boss@mail.agency.gov>"))
    }

    @Test fun `header from only in the spf clause is rejected`() {
        assertNull(auth("spf=pass smtp.mailfrom=agency.gov header.from=agency.gov; dmarc=pass action=none"))
    }

    @Test fun `header from only in a later clause is rejected`() {
        assertNull(auth("dmarc=pass action=none; dkim=pass header.from=agency.gov"))
    }

    @Test fun `two dmarc clauses are rejected even when both pass`() {
        assertNull(auth("dmarc=pass header.from=agency.gov; dmarc=pass header.from=agency.gov"))
    }

    @Test fun `dmarc pass inside a quoted reason of another clause is ignored`() {
        assertNull(auth("dkim=fail reason=\"dmarc=pass header.from=agency.gov\""))
    }

    @Test fun `dmarc pass hidden behind a semicolon inside a quoted string is ignored`() {
        assertNull(auth("dkim=fail reason=\"x; dmarc=pass header.from=agency.gov\""))
        assertNull(auth("dkim=fail reason=\"x; dmarc=pass header.from=agency.gov \" "))
    }

    @Test fun `dmarc pass hidden behind a semicolon inside a comment is ignored`() {
        assertNull(auth("dkim=fail (x; dmarc=pass header.from=agency.gov)"))
        assertNull(auth("dkim=fail (x; dmarc=pass header.from=agency.gov ) "))
    }

    @Test fun `dmarc pass in a comment after a failing clause is ignored`() {
        assertNull(auth("dmarc=fail header.from=agency.gov (dmarc=pass header.from=agency.gov)"))
        assertNull(auth("dmarc=fail header.from=agency.gov; (dmarc=pass header.from=agency.gov)"))
        assertNull(auth("spf=pass (dmarc=pass header.from=agency.gov)"))
    }

    @Test fun `a comment inside the real dmarc clause is tolerated`() {
        assertEquals("boss@agency.gov", auth("dmarc=pass (p=REJECT sp=NONE) header.from=agency.gov"))
    }

    @Test fun `unbalanced comment or quote fails closed`() {
        assertNull(auth("dmarc=pass (p=REJECT header.from=agency.gov"))
        assertNull(auth("dmarc=pass reason=\"oops header.from=agency.gov"))
    }

    @Test fun `folded header across lines parses`() {
        assertEquals(
            "boss@agency.gov",
            auth("spf=pass smtp.mailfrom=agency.gov;\r\n dkim=pass header.d=agency.gov;\r\n\tdmarc=pass action=none\r\n header.from=agency.gov;\r\n compauth=pass"),
        )
        assertEquals("boss@agency.gov", auth("spf=pass;\n dmarc=pass\n header.from=agency.gov"))
    }

    @Test fun `From with two addresses is rejected`() {
        assertNull(auth(pass, "boss@agency.gov, other@agency.gov"))
        assertNull(auth(pass, "Boss <boss@agency.gov>, Other <other@agency.gov>"))
        assertNull(auth(pass, "Boss <boss@agency.gov> <other@agency.gov>"))
    }

    @Test fun `From with an injected header line is rejected`() {
        assertNull(auth(pass, "boss@agency.gov\r\nX: y"))
        assertNull(auth(pass, "Boss <boss@agency.gov>\nBcc: evil@example.com"))
        assertNull(auth(pass, "Boss <boss@agency.gov>\rX: y"))
    }

    @Test fun `trailing dot on header from and mixed case domains compare equal`() {
        assertEquals("boss@agency.gov", auth(pass.replace("header.from=agency.gov", "header.from=agency.gov.")))
        assertEquals("boss@agency.gov", auth(pass, "Boss <Boss@Agency.GOV>"))
        assertEquals("boss@agency.gov", auth(pass.replace("header.from=agency.gov", "header.from=AGENCY.Gov"), "Boss <boss@agency.gov>"))
    }

    @Test fun `empty header value is rejected`() {
        assertNull(auth(""))
    }

    // Timing bounds are deliberately generous (5 s) so a loaded CI runner cannot flake them, yet the
    // inputs are large enough that quadratic or catastrophic behaviour would blow far past the bound.
    @Test(timeout = 5000) fun `very long header is rejected quickly without a dmarc clause`() {
        val long = "spf=pass smtp.mailfrom=agency.gov; ".repeat(30_000) + "x".repeat(100_000)
        assertTrue(long.length >= 1_000_000)
        assertNull(auth(long))
    }

    @Test(timeout = 5000) fun `very long header with a trailing dmarc clause still authenticates quickly`() {
        val long = "spf=pass smtp.mailfrom=agency.gov; ".repeat(30_000) + "dmarc=pass header.from=agency.gov"
        assertEquals("boss@agency.gov", auth(long))
    }

    @Test(timeout = 5000) fun `deeply nested comments and long whitespace runs are handled quickly`() {
        val nested = "(".repeat(200_000) + ")".repeat(200_000)
        assertEquals("boss@agency.gov", auth("$nested; dmarc=pass header.from=agency.gov;" + " ".repeat(500_000) + "x"))
        assertNull(auth("; ".repeat(200_000) + " ".repeat(500_000)))
    }

    @Test fun `uppercase DMARC=PASS is accepted as the documented form`() {
        assertEquals("boss@agency.gov", auth("DMARC=PASS HEADER.FROM=AGENCY.GOV"))
    }

    @Test fun `dmarc with spaces around the equals sign is rejected`() {
        assertNull(auth("dmarc = pass header.from=agency.gov"))
        assertNull(auth("dmarc= pass header.from=agency.gov"))
        assertNull(auth("dmarc =pass header.from=agency.gov"))
    }

    @Test fun `a malformed extra dmarc segment alongside a passing one is rejected`() {
        assertNull(auth("dmarc =fail header.from=agency.gov; dmarc=pass header.from=agency.gov"))
        assertNull(auth("dmarc=pass header.from=agency.gov; dmarc-extra=fail"))
    }

    @Test fun `header from with a longer attacker domain is rejected`() {
        assertNull(auth("dmarc=pass header.from=agency.gov.evil.com"))
        assertNull(auth("dmarc=pass header.from=evilagency.gov"))
    }

    @Test fun `header from with a different case still matches only the same domain`() {
        assertEquals("boss@agency.gov", auth("dmarc=pass header.from=Agency.Gov"))
        assertNull(auth("dmarc=pass header.from=Agency.Com"))
    }

    @Test fun `From with a trailing dot domain is rejected`() {
        assertNull(auth(pass, "boss@AGENCY.GOV."))
        assertNull(auth(pass, "Boss <boss@AGENCY.GOV.>"))
    }

    @Test(timeout = 5000) fun `many empty segments are rejected quickly`() {
        assertNull(auth(";".repeat(10_000)))
        assertNull(auth(";".repeat(1_000_000)))
    }

    @Test(timeout = 5000) fun `many repeated dmarc-free segments are rejected quickly`() {
        assertNull(auth("; spf=pass ".repeat(10_000)))
        assertNull(auth("; spf=pass ".repeat(200_000)))
    }

    @Test fun `a dmarc clause hidden by an unclosed comment cannot stand in for the real failing one`() {
        val forged =
            "spf=pass smtp.mailfrom=evil.com; dkim=pass header.d=evil.com(; dmarc=fail action=none header.from=x); dmarc=pass header.from=agency.gov"
        assertNull(auth(forged))
    }

    @Test fun `two dmarc results in one clause are rejected`() {
        assertNull(auth("dmarc=pass dmarc=fail header.from=agency.gov"))
        assertNull(auth("dmarc=fail dmarc=pass header.from=agency.gov"))
    }

    @Test fun `a comment mentioning dmarc is rejected as ambiguous`() {
        assertNull(auth("dmarc=pass (dmarc policy=reject) header.from=agency.gov"))
    }

    @Test fun `an Exchange style policy comment without the word dmarc is accepted`() {
        assertEquals("boss@agency.gov", auth("dmarc=pass (policy=reject) action=none header.from=agency.gov"))
    }

    @Test fun `exactly one trailing dot is removed from header from`() {
        assertEquals("boss@agency.gov", auth("dmarc=pass header.from=agency.gov."))
        assertNull(auth("dmarc=pass header.from=agency.gov.."))
        assertNull(auth("dmarc=pass header.from=agency.gov..."))
    }

    @Test fun `non ASCII header from that folds to an ASCII domain is rejected`() {
        val kelvin = "K"
        assertNull(auth("dmarc=pass header.from=${kelvin}agency.gov", "Boss <boss@kagency.gov>"))
        assertNull(auth("dmarc=pass header.from=agency.gov$kelvin", "Boss <boss@agency.govk>"))
        assertNull(auth("dmarc=pass header.from=agéncy.gov", "Boss <boss@agency.gov>"))
    }

    @Test fun `upper case From and mixed case header from compare equal`() {
        assertEquals("boss@agency.gov", auth("dmarc=pass header.from=Agency.Gov", "BOSS@AGENCY.GOV"))
    }

    @Test fun `an escaped quote does not close a quoted string and hide a forged clause`() {
        // Four quotes, one escaped: only escape handling keeps the forged clause inside the string.
        assertNull(auth("x=\"a\\\"; dmarc=pass header.from=agency.gov z\" \""))
        assertNull(auth("x=\"a\\\"; dmarc=pass header.from=agency.gov z\""))
    }

    @Test fun `an escaped quote inside a legitimate quoted string is tolerated`() {
        assertEquals("boss@agency.gov", auth("dmarc=pass reason=\"a\\\"b\" header.from=agency.gov"))
    }

    @Test fun `an escaped backslash does not escape the closing quote`() {
        assertEquals("boss@agency.gov", auth("x=\"a\\\\\"; dmarc=pass header.from=agency.gov"))
    }

    @Test fun `an escaped parenthesis inside a comment does not close it`() {
        assertNull(auth("(a \\) ; dmarc=pass header.from=agency.gov b) "))
        assertEquals("boss@agency.gov", auth("dmarc=pass (a \\) b) header.from=agency.gov"))
    }

    @Test fun `nested comments hide a forged clause`() {
        assertNull(auth("(a (b) ; dmarc=pass header.from=agency.gov c)"))
    }

    @Test fun `nested comment inside the real clause is tolerated`() {
        assertEquals("boss@agency.gov", auth("dmarc=pass (policy (reject)) header.from=agency.gov"))
    }

    @Test fun `a stray closing parenthesis anywhere is rejected`() {
        assertNull(auth("dmarc=pass header.from=agency.gov)"))
        assertNull(auth(") dmarc=pass header.from=agency.gov"))
        assertNull(auth("dmarc=pass (a)) header.from=agency.gov"))
    }

    @Test fun `unterminated quote or comment is rejected`() {
        assertNull(auth("dmarc=pass header.from=agency.gov \"unterminated"))
        assertNull(auth("dmarc=pass header.from=agency.gov (unterminated"))
        assertNull(auth("dmarc=pass header.from=agency.gov (a (b) still open"))
        assertNull(auth("dmarc=pass header.from=agency.gov \"abc\\"))
    }

    @Test fun `a longer result token ending in pass is rejected`() {
        assertNull(auth("dmarc=bestguesspass header.from=agency.gov"))
        assertNull(auth("dmarc=passpass header.from=agency.gov"))
    }

    @Test fun `a result that merely starts with pass is rejected`() {
        assertNull(auth("dmarc=passed header.from=agency.gov"))
        assertNull(auth("dmarc=pass-through header.from=agency.gov"))
    }

    @Test fun `multiple header from properties in the dmarc clause are rejected`() {
        assertNull(auth("dmarc=pass header.from=evil.com header.from=agency.gov"))
        assertNull(auth("dmarc=pass header.from=agency.gov header.from=evil.com"))
    }

    @Test fun `a dotted prefix on header from does not count`() {
        assertNull(auth("dmarc=pass smtp.header.from=agency.gov"))
    }

    @Test fun `unparseable From is rejected`() {
        assertNull(auth(pass, ""))
        assertNull(auth(pass, "not an address"))
    }
}
