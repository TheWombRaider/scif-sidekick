package com.scifsidekick.cleanroom.email

import com.scifsidekick.cleanroom.util.ComposeAuthorization
import java.util.Locale

/**
 * Converts Gmail's trusted, topmost Authentication-Results header into an authenticated mailbox.
 * A displayable From header alone is never authority. Gmail prepends its own result at receipt;
 * considering only the first such header prevents an attacker-supplied lower header from winning.
 */
object GmailAuthentication {
    private val dmarcPass = Regex("(?:^|;)\\s*dmarc=pass\\b[^;]*\\bheader\\.from=([^;\\s]+)", RegexOption.IGNORE_CASE)

    fun authenticatedFrom(
        fromHeader: String,
        authenticationResults: List<String>,
    ): String? {
        val address = ComposeAuthorization.extractAddress(fromHeader) ?: return null
        val trusted = authenticationResults.firstOrNull()?.trim() ?: return null
        val authService = trusted.substringBefore(';').trim().lowercase(Locale.US)
        if (authService != "mx.google.com") return null
        val assertedDomain =
            dmarcPass.find(trusted)?.groupValues?.get(1)?.trim()?.trimEnd('.')?.lowercase(Locale.US)
                ?: return null
        val fromDomain = address.substringAfter('@').trimEnd('.')
        return address.takeIf { fromDomain == assertedDomain }
    }
}
