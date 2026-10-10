package com.scifsidekick.cleanroom.email.graph

import com.scifsidekick.cleanroom.util.ComposeAuthorization
import java.util.Locale

/**
 * Converts the topmost Authentication-Results header Microsoft adds at receipt into an
 * authenticated mailbox. A displayable From header alone is never authority, so every
 * ambiguity returns null (fail closed).
 *
 * Accepted only when: the From header yields exactly one safe address; the first
 * Authentication-Results header (list order, later ones ignored) holds exactly one
 * dmarc clause in the documented `dmarc=pass` form; and that clause carries its own single
 * `header.from=` equal to the From domain. There is no relaxed or subdomain alignment.
 * Parenthesised comments and quoted strings are removed before parsing so text hidden in
 * them cannot form a clause.
 */
object GraphAuthentication {
    private val fold = Regex("\\r?\\n[ \\t]+")
    private val dmarcWord = Regex("dmarc", RegexOption.IGNORE_CASE)
    private val asciiDomain = Regex("[A-Za-z0-9.-]+")
    // An explicit ASCII class, not \w: on Android \w is Unicode, so a result spelled with U+017F
    // (long s) would match. Mirrors GmailAuthentication.
    internal val dmarcClause = Regex("(?:^|;)\\s*dmarc=([A-Za-z0-9_]+)(?=[\\s;]|$)([^;]*)", RegexOption.IGNORE_CASE)
    private val headerFrom = Regex("(?:^|\\s)header\\.from=([^;\\s]+)", RegexOption.IGNORE_CASE)

    fun authenticatedFrom(fromHeader: String, internetMessageHeaders: List<Pair<String, String>>): String? {
        val address = ComposeAuthorization.extractAddress(fromHeader) ?: return null
        val first = internetMessageHeaders.firstOrNull { it.first.equals("Authentication-Results", ignoreCase = true) }?.second
            ?: return null
        val unfolded = first.replace(fold, " ").trim()
        // The raw value must mention "dmarc" exactly once. Stripping comments could otherwise hide
        // a real failing clause behind a forged comment; this deliberately also rejects a header
        // whose comment merely mentions the word (fail closed).
        if (dmarcWord.findAll(unfolded).count() != 1) return null
        val text = stripCommentsAndQuotes(unfolded) ?: return null
        val clause = dmarcClause.findAll(text).singleOrNull() ?: return null
        if (!dmarcResultPasses(clause.groupValues[1])) return null
        val rawAsserted = headerFrom.findAll(clause.groupValues[2]).singleOrNull()?.groupValues?.get(1)
            ?.removeSuffix(".") ?: return null
        // Checked before lowercasing: Locale folding maps U+212A (Kelvin sign) to ASCII 'k'.
        if (!asciiDomain.matches(rawAsserted)) return null
        // extractAddress already returns a lowercase address whose domain has no trailing dot.
        return address.takeIf { it.substringAfter('@') == rawAsserted.lowercase(Locale.US) }
    }

    /** Exactly "pass" in any ASCII case. Not equals(ignoreCase = true): that folds U+017F (long s) to "s". */
    internal fun dmarcResultPasses(result: String): Boolean = result.lowercase(Locale.US) == "pass"

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
