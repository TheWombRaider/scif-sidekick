package com.scifsidekick.cleanroom.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.scifsidekick.cleanroom.email.graph.MsAccountPreferences
import kotlinx.coroutines.delay

/**
 * The Microsoft (Outlook.com) account card on Settings. Collapsed to two short lines until the
 * user opts in, so a Gmail-only setup barely notices it. [clientId] is the stored Application
 * (client) ID ("" when unset); [preferred] is [MsAccountPreferences.PROVIDER_GMAIL] or
 * [MsAccountPreferences.PROVIDER_GRAPH]. [account] is the stored account, so a sign-in step
 * (waiting, connecting, failed) still names a stored account and offers **Disconnect**.
 * [onCodeCopied] lets the caller clear the code from the clipboard once the sign-in is over;
 * [onMessage] shows a short confirmation through the screen's snackbar. The device code is shown
 * here only and never logged.
 */
@Composable
internal fun MicrosoftAccountCard(
    state: MicrosoftUiState,
    clientId: String,
    preferred: String,
    gmailConnected: Boolean,
    account: MicrosoftAccountFacts,
    onClientIdChange: (String) -> Unit,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
    onDisconnect: () -> Unit,
    onPreferredChange: (String) -> Unit,
    onCodeCopied: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    var setupExpanded by rememberSaveable { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Microsoft (Outlook.com)", fontWeight = FontWeight.Bold)
            when (state) {
                MicrosoftUiState.NotConfigured ->
                    if (setupExpanded) {
                        SetupSteps()
                        ClientIdField(clientId = clientId, onClientIdChange = onClientIdChange)
                        PollCostLine()
                    } else {
                        Text(
                            "Optional: add an Outlook.com account so forwarding keeps going if Gmail can't send.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(onClick = { setupExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Set up Outlook")
                        }
                    }

                is MicrosoftUiState.Idle -> {
                    ClientIdSection(clientId = clientId, onClientIdChange = onClientIdChange)
                    PollCostLine()
                    ConnectButton(enabled = clientId.isNotBlank(), onConnect = onConnect)
                }

                is MicrosoftUiState.WaitingForCode -> {
                    StoredAccountLine(account)
                    DeviceCodePanel(state = state, onCancel = onCancel, onCodeCopied = onCodeCopied, onMessage = onMessage)
                    StoredAccountDisconnect(account, onDisconnect)
                }

                MicrosoftUiState.Connecting -> {
                    StoredAccountLine(account)
                    Row(
                        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        Text(MicrosoftUiText.statusLine(state))
                    }
                    StoredAccountDisconnect(account, onDisconnect)
                }

                is MicrosoftUiState.Connected -> {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("CONNECTED", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(state.email.ifBlank { "Outlook connected" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    PollCostLine()
                    if (gmailConnected) SendFirstChoice(preferred = preferred, onPreferredChange = onPreferredChange)
                    TextButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
                }

                is MicrosoftUiState.NeedsReconnect -> {
                    Text(MicrosoftUiText.statusLine(state), color = MaterialTheme.colorScheme.error)
                    Text("Until it is, mail goes through Gmail only.", style = MaterialTheme.typography.bodySmall)
                    ClientIdSection(clientId = clientId, onClientIdChange = onClientIdChange)
                    ConnectButton(enabled = clientId.isNotBlank(), onConnect = onConnect)
                    TextButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
                }

                is MicrosoftUiState.Error -> {
                    StoredAccountLine(account)
                    Text(MicrosoftUiText.statusLine(state), color = MaterialTheme.colorScheme.error)
                    ClientIdSection(clientId = clientId, onClientIdChange = onClientIdChange)
                    Button(onClick = onConnect, enabled = clientId.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Try again") }
                    StoredAccountDisconnect(account, onDisconnect)
                }
            }
        }
    }
}

/** During a sign-in step, names the account already stored on this phone (nothing when none is). */
@Composable
private fun StoredAccountLine(account: MicrosoftAccountFacts) {
    if (!account.stored) return
    Text(
        "Current account: ${account.email ?: "Outlook account"}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Lets a user with a stored (possibly dead) account remove it from any sign-in step. */
@Composable
private fun StoredAccountDisconnect(
    account: MicrosoftAccountFacts,
    onDisconnect: () -> Unit,
) {
    if (!account.stored) return
    TextButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
}

@Composable
private fun SetupSteps() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "1. At entra.microsoft.com, register a new app for personal Microsoft accounts (no redirect URI).",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "2. Allow public client flows, and add the Graph permissions Mail.ReadWrite, Mail.Send, User.Read and offline_access.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("3. Paste your Application (client) ID below.", style = MaterialTheme.typography.bodySmall)
        Text(
            "Where do I find this? See Outlook setup in the README.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun PollCostLine() {
    Text(
        "Adds about one small request per 30-second poll.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ConnectButton(
    enabled: Boolean,
    onConnect: () -> Unit,
) {
    Button(onClick = onConnect, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Connect Microsoft account") }
}

/** The stored ID as one collapsed line with **Change**, or the field itself when unset or being edited. */
@Composable
private fun ClientIdSection(
    clientId: String,
    onClientIdChange: (String) -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    if (clientId.isBlank() || editing) {
        if (clientId.isBlank()) SetupSteps()
        ClientIdField(clientId = clientId, onClientIdChange = onClientIdChange)
        if (editing) TextButton(onClick = { editing = false }) { Text("Done") }
    } else {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "App ID $clientId",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { editing = true },
                modifier = Modifier.semantics { contentDescription = "Change application ID" },
            ) { Text("Change") }
        }
    }
}

/** Saves on every change that forms a valid GUID (spaces trimmed); anything else just stays in the field. */
@Composable
private fun ClientIdField(
    clientId: String,
    onClientIdChange: (String) -> Unit,
) {
    // Keyed on the stored ID so a change saved elsewhere never leaves a stale value in the field.
    var text by rememberSaveable(clientId) { mutableStateOf(clientId) }
    val normalized = MicrosoftUiText.normalizeClientId(text)
    val showError = text.isNotBlank() && normalized == null
    OutlinedTextField(
        value = text,
        onValueChange = { value ->
            text = value
            val id = MicrosoftUiText.normalizeClientId(value)
            if (id != null && id != clientId) onClientIdChange(id)
        },
        label = { Text("Application (client) ID") },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        isError = showError,
        supportingText = {
            Text(
                when {
                    showError -> "Looks like 00000000-0000-0000-0000-000000000000"
                    normalized != null -> "Saved"
                    else -> "Not a secret: it only names your app registration."
                },
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DeviceCodePanel(
    state: MicrosoftUiState.WaitingForCode,
    onCancel: () -> Unit,
    onCodeCopied: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.expiresAtMs) {
        while (true) {
            nowMs = System.currentTimeMillis()
            if (nowMs >= state.expiresAtMs) break
            delay(1_000L)
        }
    }
    // The exact trimmed text that was validated is what gets launched.
    val trustedUri = remember(state.uri) { MicrosoftUiText.trustedVerificationUri(state.uri) }
    val trusted = trustedUri != null
    Text("On any device, open the page below and enter this code:", style = MaterialTheme.typography.bodySmall)
    Text(
        state.code,
        style = MaterialTheme.typography.headlineMedium,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.semantics { contentDescription = "Code ${MicrosoftUiText.spacedCode(state.code)}" },
    )
    Text(state.uri, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    if (!trusted) {
        Text(
            "This page address isn't a Microsoft address, so it won't be opened from here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                if (SignInCodeClipboard.copy(context, state.code)) {
                    onCodeCopied(state.code)
                    if (!SignInCodeClipboard.systemConfirmsCopy) onMessage("Code copied")
                }
            },
            modifier = Modifier.weight(1f),
        ) { Text("Copy code") }
        Button(
            onClick = {
                val opened = trustedUri != null && runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, trustedUri.toUri())) }.isSuccess
                if (!opened) onMessage("Open ${state.uri} in a browser")
            },
            enabled = trusted,
            modifier = Modifier.weight(1f),
        ) { Text("Open page") }
    }
    Text(
        "Expires in ${MicrosoftUiText.countdown(state.expiresAtMs, nowMs)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SendFirstChoice(
    preferred: String,
    onPreferredChange: (String) -> Unit,
) {
    Text("Send first", style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = preferred != MsAccountPreferences.PROVIDER_GRAPH,
            onClick = { onPreferredChange(MsAccountPreferences.PROVIDER_GMAIL) },
            label = { Text("Gmail") },
            modifier = Modifier.weight(1f).semantics { contentDescription = "Send through Gmail first" },
        )
        FilterChip(
            selected = preferred == MsAccountPreferences.PROVIDER_GRAPH,
            onClick = { onPreferredChange(MsAccountPreferences.PROVIDER_GRAPH) },
            label = { Text("Microsoft") },
            modifier = Modifier.weight(1f).semantics { contentDescription = "Send through Microsoft first" },
        )
    }
    Text("The other account is used when the first can't send.", style = MaterialTheme.typography.bodySmall)
}
