package com.scifsidekick.cleanroom.ui

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** Pure texts and checks for the Microsoft (Outlook.com) account card; no Android types, unit-tested. */
object MicrosoftUiText {
    private val GUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val TRUSTED_DOMAINS = listOf("microsoft.com", "microsoftonline.com")

    /** One short line describing [state]. The device code itself is never part of it. */
    fun statusLine(state: MicrosoftUiState): String =
        when (state) {
            MicrosoftUiState.NotConfigured -> "Not set up"
            is MicrosoftUiState.Idle -> "Not connected"
            is MicrosoftUiState.WaitingForCode -> "Waiting for you to enter the code"
            MicrosoftUiState.Connecting -> "Finishing sign-in..."
            is MicrosoftUiState.Connected -> if (state.email.isBlank()) "Connected" else "Connected as ${state.email}"
            is MicrosoftUiState.NeedsReconnect ->
                if (state.email.isNullOrBlank()) "The account needs to be connected again" else "${state.email} needs to be connected again"
            is MicrosoftUiState.Error -> state.message
        }

    /** The line under a Microsoft account that needs reconnecting: Gmail is named only when it is connected. */
    fun needsReconnectHint(gmailConnected: Boolean): String =
        if (gmailConnected) "Until it is, mail goes through Gmail only." else "Until it is, Outlook mail cannot be sent or read."

    /** "ABCD1234" -> "A B C D 1 2 3 4", so a screen reader reads the code one character at a time. */
    fun spacedCode(code: String): String = code.filterNot(Char::isWhitespace).toList().joinToString(" ")

    /** Time left until [expiresAtMs] as "M:SS", a partial second rounded up, never below "0:00". */
    fun countdown(
        expiresAtMs: Long,
        nowMs: Long,
    ): String {
        val remainingMs = (expiresAtMs - nowMs).coerceAtLeast(0L)
        val totalSeconds = (remainingMs + 999L) / 1_000L
        return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
    }

    /**
     * Whether [uri] may be opened as the device-code verification page: https on the default port,
     * no user info, and a host that is microsoft.com / microsoftonline.com or a subdomain of either.
     */
    fun isTrustedVerificationUri(uri: String): Boolean = trustedVerificationUri(uri) != null

    /**
     * The trimmed [uri] when [isTrustedVerificationUri] accepts it, else null. Launch exactly this
     * value, so what is opened is what was validated.
     */
    fun trustedVerificationUri(uri: String): String? {
        val trimmed = uri.trim()
        val parsed =
            try {
                URI(trimmed)
            } catch (_: URISyntaxException) {
                return null
            }
        if (!parsed.scheme.equals("https", ignoreCase = true)) return null
        if (parsed.rawUserInfo != null) return null
        if (parsed.port != -1 && parsed.port != 443) return null
        val host = parsed.host?.lowercase(Locale.ROOT) ?: return null
        return trimmed.takeIf { TRUSTED_DOMAINS.any { domain -> host == domain || host.endsWith(".$domain") } }
    }

    /** The trimmed Application (client) ID when [input] is a GUID, otherwise null. */
    fun normalizeClientId(input: String): String? = input.trim().takeIf { GUID.matches(it) }
}
