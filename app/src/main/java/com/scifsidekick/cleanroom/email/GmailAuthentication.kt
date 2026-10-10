package com.scifsidekick.cleanroom.email

import com.scifsidekick.cleanroom.util.ComposeAuthorization
import java.util.Locale

/**
 * Converts Gmail's trusted, topmost Authentication-Results header into an authenticated mailbox.
 * A displayable From header alone is never authority. Gmail prepends its own result at receipt;
 * considering only the first such header prevents an attacker-supplied lower header from winning.
 *
 * Accepted only when that header's authserv-id is exactly `mx.google.com`, it holds exactly one
 * dmarc clause whose result is exactly `pass`, and that clause carries its own single
 * `header.from=` equal to the From domain. Every ambiguity returns null (fail closed).
 * Parenthesised comments and quoted strings are removed before parsing so text hidden in them
 * cannot form a clause; an unbalanced comment or quote rejects the header.
 */
object GmailAuthentication {
    private val fold = Regex("\\r?\\n[ \\t]+")
    private val dmarcWord = Regex("dmarc", RegexOption.IGNORE_CASE)

    // Gmail puts the ARC chain's results, dmarc included, in a comment: "arc=pass (i=1 ... dmarc=pass fromdomain=x)".
    // The stripper removes such a comment whole, so its dmarc mentions are exempt from the single-mention rule.
    // Residual (docs/TEST_PLAN.md): if Gmail echoed ")" or ";" from a sender-controlled domain into it,
    // the comment could end early and exempt text after it; only for From domains without DMARC.
    private val arcComment = Regex("(?:^|;)\\s*arc=[A-Za-z0-9_]+\\s*\\(([^()\"\\\\]*)\\)", RegexOption.IGNORE_CASE)

    // ASCII classes on purpose: Android's regex engine treats \w as Unicode.
    private val dmarcClause = Regex("(?:^|;)\\s*dmarc=([A-Za-z0-9_]+)(?=[\\s;]|$)([^;]*)", RegexOption.IGNORE_CASE)
    private val headerFrom = Regex("(?:^|\\s)header\\.from=([^;\\s]+)", RegexOption.IGNORE_CASE)
    private val asciiDomain = Regex("[A-Za-z0-9.-]+")

    fun authenticatedFrom(
        fromHeader: String,
        authenticationResults: List<String>,
    ): String? {
        val address = ComposeAuthorization.extractAddress(fromHeader) ?: return null
        val trusted = authenticationResults.firstOrNull()?.replace(fold, " ")?.trim() ?: return null
        val authService = trusted.substringBefore(';').trim().lowercase(Locale.US)
        if (authService != "mx.google.com") return null
        // The raw value must mention "dmarc" exactly once outside the ARC comment, else a forged comment
        // could hide a failing clause. This also rejects headers whose other comments merely say the word.
        val arcMentions = arcComment.findAll(trusted).sumOf { dmarcWord.findAll(it.groupValues[1]).count() }
        if (dmarcWord.findAll(trusted).count() - arcMentions != 1) return null
        val text = stripCommentsAndQuotes(trusted) ?: return null
        val clause = dmarcClause.findAll(text).singleOrNull() ?: return null
        if (clause.groupValues[1].lowercase(Locale.US) != "pass") return null
        val rawAsserted = headerFrom.findAll(clause.groupValues[2]).singleOrNull()?.groupValues?.get(1)
            ?.removeSuffix(".") ?: return null
        // Checked before lowercasing: Locale folding maps U+212A (Kelvin sign) to ASCII 'k'.
        if (!asciiDomain.matches(rawAsserted)) return null
        // extractAddress already returns a lowercase address whose domain has no trailing dot.
        return address.takeIf { it.substringAfter('@') == rawAsserted.lowercase(Locale.US) }
    }

    /** Replaces each comment or quoted string with one space; null if any is unterminated or stray. */
    private fun stripCommentsAndQuotes(value: String): String? {
        val out = StringBuilder(value.length)
        var depth = 0
        var quoted = false
        var escaped = false
        for (c in value) {
            when {
                escaped -> escaped = false
                c == '\\' && (quoted || depth > 0) -> escaped = true
                quoted -> if (c == '"') { quoted = false; out.append(' ') }
                depth > 0 -> when (c) {
                    '(' -> depth++
                    ')' -> { depth--; if (depth == 0) out.append(' ') }
                }
                c == '"' -> quoted = true
                c == '(' -> depth = 1
                c == ')' -> return null
                else -> out.append(c)
            }
        }
        return if (quoted || depth != 0 || escaped) null else out.toString()
    }
}
