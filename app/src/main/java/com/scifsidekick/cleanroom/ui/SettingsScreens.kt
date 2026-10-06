package com.scifsidekick.cleanroom.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.scifsidekick.cleanroom.data.AppSettingsEntity
import com.scifsidekick.cleanroom.messaging.SimSelection
import com.scifsidekick.cleanroom.util.ComposeAuthorization
import com.scifsidekick.cleanroom.util.PayloadCodec
import com.scifsidekick.cleanroom.util.RemoteControlCodec

/**
 * A plain, non-scrolling Column of cards -- this is nested inside the Settings screen's own single
 * outer `verticalScroll` Column (see [SettingsScreens.kt]'s callers in MainActivity.kt), alongside
 * the Appearance and Data sections, rather than owning a scroll container of its own. Two scrolling
 * containers of the same orientation nested inside each other is exactly the bug that made the
 * old History screen show only one or two rows at a time -- see ActivityScreen.kt's doc comment
 * for the full story. A handful of fixed cards, never a data-driven list, needs no virtualization
 * either way.
 */
@Composable
fun AppSettingsScreenBody(
    settings: AppSettingsEntity,
    onChange: (AppSettingsEntity) -> Unit,
    onDisablePush: () -> Unit = {},
    hideInRecents: Boolean = false,
    onHideInRecentsChange: (Boolean) -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("App lock", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Switch(checked = settings.appLockEnabled, onCheckedChange = { onChange(settings.copy(appLockEnabled = it)) })
                }
                Text(
                    "Require your device's screen lock or biometric to open the app. Applies the next time the app starts.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Hide app content in Recents", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Switch(checked = hideInRecents, onCheckedChange = onHideInRecentsChange)
                }
                Text(
                    "Blanks the app in the recent-apps screen and blocks screenshots, so message text and addresses can't be seen or captured there.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Ignore duplicate notifications", fontWeight = FontWeight.Bold)
                        Text(
                            "Suppresses an identical message from the same sender seen again within the window below.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = settings.duplicateSuppressionEnabled,
                        onCheckedChange = { onChange(settings.copy(duplicateSuppressionEnabled = it)) },
                    )
                }
                if (settings.duplicateSuppressionEnabled) {
                    IntField(
                        label = "Window (minutes)",
                        value = settings.duplicateWindowMinutes,
                        onChange = { onChange(settings.copy(duplicateWindowMinutes = it)) },
                    )
                }
            }
        }

        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Anti-flood ceilings", fontWeight = FontWeight.Bold)
                Text(
                    "Optional ceilings tighter than the app's built-in hard limits (20/min, 300/hr, 450/day email; " +
                        "10/min SMS), which always apply regardless. 0 means no additional ceiling.",
                    style = MaterialTheme.typography.bodySmall,
                )
                IntField(
                    label = "Max emails per minute (0 = off)",
                    value = settings.softEmailPerMinuteCap,
                    onChange = { onChange(settings.copy(softEmailPerMinuteCap = it)) },
                )
                IntField(
                    label = "Max reply SMS per minute (0 = off)",
                    value = settings.softSmsPerMinuteCap,
                    onChange = { onChange(settings.copy(softSmsPerMinuteCap = it)) },
                )
            }
        }

        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Retry when network reconnects", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Switch(
                        checked = settings.retryOnNetworkReconnect,
                        onCheckedChange = { onChange(settings.copy(retryOnNetworkReconnect = it)) },
                    )
                }
                Text(
                    "Makes queued work eligible again the moment connectivity returns, instead of waiting out a backoff.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        RemoteControlCard(settings = settings, onChange = onChange)

        OutboundSimCard(settings = settings, onChange = onChange)

        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Heartbeat email", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Switch(
                        checked = settings.heartbeatEnabled,
                        onCheckedChange = { onChange(settings.copy(heartbeatEnabled = it)) },
                    )
                }
                Text(
                    "Off by default. Emails you a short status summary on a fixed schedule -- forwarding on or " +
                        "off, whether the service is alive, Gmail authorization, anything stuck in the queue.\n\n" +
                        "The point is the message that DOESN'T arrive. Every other way this app reports trouble " +
                        "is a notification on this phone, which is no use when the phone is locked away -- and if " +
                        "Gmail authorization is what expired, no error email can reach you either, because " +
                        "sending email is the broken part. A heartbeat that stops showing up still tells you.\n\n" +
                        "Pick a preset below or type any custom number of hours -- both write the same value.",
                    style = MaterialTheme.typography.bodySmall,
                )
                val heartbeatHours = listOf(6, 12, 24, 48)
                Text("Send every", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    heartbeatHours.forEach { hours ->
                        FilterChip(
                            selected = settings.heartbeatIntervalHours == hours,
                            onClick = { onChange(settings.copy(heartbeatIntervalHours = hours)) },
                            label = { Text(if (hours == 24) "24h" else "${hours}h") },
                        )
                    }
                }
                IntField(
                    label = "Custom hours (1-720)",
                    value = settings.heartbeatIntervalHours,
                    onChange = { onChange(settings.copy(heartbeatIntervalHours = it.coerceIn(1, 720))) },
                )
                val heartbeatRecipients =
                    remember(settings.heartbeatRecipientsJson) {
                        PayloadCodec.pathsFromJson(settings.heartbeatRecipientsJson)
                    }
                var newHeartbeatRecipient by rememberSaveable { mutableStateOf("") }
                Text("Send to", style = MaterialTheme.typography.labelLarge)
                ChipEditor(
                    items = heartbeatRecipients,
                    newValue = newHeartbeatRecipient,
                    onNewValueChange = { newHeartbeatRecipient = it },
                    keyboardType = KeyboardType.Email,
                    placeholder = "Add email address",
                    onAdd = {
                        val clean = ComposeAuthorization.canonicalAddress(newHeartbeatRecipient)
                        if (clean != null && clean !in heartbeatRecipients) {
                            onChange(
                                settings.copy(heartbeatRecipientsJson = PayloadCodec.pathsToJson(heartbeatRecipients + clean)),
                            )
                            newHeartbeatRecipient = ""
                        }
                    },
                    onRemove = { value ->
                        onChange(
                            settings.copy(heartbeatRecipientsJson = PayloadCodec.pathsToJson(heartbeatRecipients - value)),
                        )
                    },
                )
            }
        }

        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Confirm replies by email", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Switch(
                        checked = settings.replyConfirmationsEnabled,
                        onCheckedChange = { onChange(settings.copy(replyConfirmationsEnabled = it)) },
                    )
                }
                Text(
                    "On by default. When you reply to a forwarded email and it goes out as a text, SCIF Sidekick " +
                        "emails you back to say whether it actually sent -- so a reply sent from somewhere the phone " +
                        "isn't reachable doesn't just disappear. The reply goes only to the address that sent the " +
                        "authorizing email, and a failure is reported once the send has genuinely given up, not on " +
                        "each retry. This confirms the phone handed the text to the network; it is not a read " +
                        "receipt.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Gmail push (beta)", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Switch(
                        checked = settings.gmailPushEnabled,
                        onCheckedChange = { checked ->
                            if (!checked) onDisablePush()
                            onChange(settings.copy(gmailPushEnabled = checked))
                        },
                    )
                }
                Text(
                    "Off by default. Cuts typical reply latency from the ~30s Gmail poll down to " +
                        "roughly 15-20s by pulling a Cloud Pub/Sub subscription you set up yourself " +
                        "in Google Cloud Console -- see \"Gmail push (beta)\" in docs/DESIGN_NOTES.md. " +
                        "The 30s poll never goes away and keeps working unchanged whether this is " +
                        "on, off, or misconfigured; use \"Test Push Setup\" on the History screen to " +
                        "check it before relying on it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = settings.pubsubTopicName,
                    onValueChange = { onChange(settings.copy(pubsubTopicName = it)) },
                    label = { Text("Pub/Sub topic (projects/P/topics/T)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = settings.pubsubSubscriptionName,
                    onValueChange = { onChange(settings.copy(pubsubSubscriptionName = it)) },
                    label = { Text("Pub/Sub subscription (projects/P/subscriptions/S)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Retention", fontWeight = FontWeight.Bold)
                Text(
                    "How long forwarded message content and diagnostic/event history are kept on this device " +
                        "before being deleted automatically. Lowering these does not delete anything retroactively " +
                        "until the next cleanup pass.",
                    style = MaterialTheme.typography.bodySmall,
                )
                RetentionField(
                    label = "Message content",
                    days = settings.messageRetentionDays,
                    defaultDays = 30,
                    onChange = { onChange(settings.copy(messageRetentionDays = it)) },
                )
                RetentionField(
                    label = "Event log & diagnostics",
                    days = settings.eventRetentionDays,
                    defaultDays = 90,
                    onChange = { onChange(settings.copy(eventRetentionDays = it)) },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** [days] <= 0 means "keep forever" -- a real, explicit choice via the switch below, not
 *  something the user should have to fake by typing 99999 into a day-count field. Switching
 *  "Keep forever" back off restores [defaultDays] rather than 0, since 0 read back into the day
 *  field would look like "delete immediately," the opposite of what it actually means here. */
@Composable
private fun RetentionField(
    label: String,
    days: Int,
    defaultDays: Int,
    onChange: (Int) -> Unit,
) {
    val keepForever = days <= 0
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Keep forever", style = MaterialTheme.typography.bodySmall)
                Switch(checked = keepForever, onCheckedChange = { onChange(if (it) 0 else defaultDays) })
            }
        }
        if (!keepForever) {
            IntField(
                label = "$label (days)",
                value = days,
                onChange = { onChange(it.coerceIn(1, 3650)) },
            )
        }
    }
}

@Composable
private fun IntField(
    label: String,
    value: Int,
    onChange: (Int) -> Unit,
) {
    var text by rememberSaveable(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter(Char::isDigit).take(5)
            text.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Also nested inside the Settings screen's own outer scroll container -- see the doc comment on
 *  [AppSettingsScreenBody] above for why it doesn't own one of its own. */
@Composable
fun BackupRestoreScreenBody(
    onExport: (passphrase: String?) -> Unit,
    onImport: () -> Unit,
) {
    var protectWithPassphrase by rememberSaveable { mutableStateOf(false) }
    var passphrase by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Backup", fontWeight = FontWeight.Bold)
                Text(
                    "Saves every filter and app setting to a JSON file you choose on this device. " +
                        "Never includes your Gmail sign-in -- Google Play services owns that, not this app.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Protect with a passphrase", modifier = Modifier.weight(1f))
                    Switch(checked = protectWithPassphrase, onCheckedChange = { protectWithPassphrase = it })
                }
                if (!protectWithPassphrase) {
                    Text(
                        "Without a passphrase the file is plain text: anyone who gets it can read your filters, recipient addresses and allowed senders.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (protectWithPassphrase) {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("Passphrase") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "You'll need this exact passphrase to restore the backup -- it's never stored anywhere, including on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = { onExport(if (protectWithPassphrase && passphrase.isNotBlank()) passphrase else null) },
                    enabled = !protectWithPassphrase || passphrase.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Export backup") }
            }
        }
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Restore", fontWeight = FontWeight.Bold)
                Text(
                    "Replaces every filter currently configured with the ones in the chosen backup file. " +
                        "You'll be asked for its passphrase if it was protected with one.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("Import backup") }
            }
        }
    }
}

/**
 * One card for every remote-control-by-email command: Compose a new text, Enable forwarding,
 * Disable forwarding, and Ask for status. A single master switch is a one-tap kill switch for all
 * four at once; below it, each authorized address gets its own independent checkbox per command,
 * so trusting an address for one never implies trusting it for another. See [RemoteControlCodec]'s
 * own doc comment for why this defaults on and how a fresh address gets seeded automatically.
 */
@Composable
private fun RemoteControlCard(
    settings: AppSettingsEntity,
    onChange: (AppSettingsEntity) -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Remote control by email", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.remoteControlEnabled,
                    onCheckedChange = { onChange(settings.copy(remoteControlEnabled = it)) },
                )
            }
            Text(
                "On by default. An authorized address below can start a brand-new text (subject exactly " +
                    "\"TEXT+15551234567\"), turn forwarding on (\"[SCIF:ON]\"), turn it off (\"[SCIF:OFF]\"), or " +
                    "ask what the app is doing (\"[SCIF:STATUS]\") -- each only if that address is checked for " +
                    "it below. The switch above is one kill switch for all four at once: turning it off doesn't " +
                    "erase who's on the list, it just stops answering any of them until it's back on. Every " +
                    "command still requires Gmail to report an aligned DMARC pass for the sending address, so a " +
                    "forged From header is rejected no matter what's checked.\n\n" +
                    "A newly added address starts with everything checked; uncheck what it shouldn't be able " +
                    "to do. The Gmail account you connect is authorized automatically the first time, with " +
                    "everything checked, so this does something out of the box instead of sitting on but empty.",
                style = MaterialTheme.typography.bodySmall,
            )
            val senders = remember(settings.remoteControlSendersJson) { RemoteControlCodec.fromJson(settings.remoteControlSendersJson) }
            if (senders.isNotEmpty()) {
                Text("Authorized addresses", style = MaterialTheme.typography.labelLarge)
                senders.forEach { sender ->
                    RemoteControlSenderRow(
                        sender = sender,
                        onChange = { updated ->
                            onChange(
                                settings.copy(
                                    remoteControlSendersJson =
                                        RemoteControlCodec.toJson(senders.map { if (it.address == sender.address) updated else it }),
                                ),
                            )
                        },
                        onRemove = {
                            onChange(
                                settings.copy(
                                    remoteControlSendersJson =
                                        RemoteControlCodec.toJson(senders.filterNot { it.address == sender.address }),
                                ),
                            )
                        },
                    )
                }
            }
            var newSender by rememberSaveable { mutableStateOf("") }
            Text("Add an authorized address", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newSender,
                    onValueChange = { newSender = it },
                    label = { Text("Add email address") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        val clean = ComposeAuthorization.canonicalAddress(newSender)
                        if (clean != null && senders.none { it.address == clean }) {
                            onChange(settings.copy(remoteControlSendersJson = RemoteControlCodec.toJson(senders + RemoteControlCodec.Sender(clean))))
                            newSender = ""
                        }
                    },
                ) { Text("Add") }
            }
        }
    }
}

@Composable
private fun RemoteControlSenderRow(
    sender: RemoteControlCodec.Sender,
    onChange: (RemoteControlCodec.Sender) -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(sender.address, modifier = Modifier.weight(1f))
            TextButton(onClick = onRemove) { Text("Remove") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = sender.canCompose,
                onClick = { onChange(sender.copy(canCompose = !sender.canCompose)) },
                label = { Text("Compose") },
            )
            FilterChip(
                selected = sender.canEnable,
                onClick = { onChange(sender.copy(canEnable = !sender.canEnable)) },
                label = { Text("Enable") },
            )
            FilterChip(
                selected = sender.canDisable,
                onClick = { onChange(sender.copy(canDisable = !sender.canDisable)) },
                label = { Text("Disable") },
            )
            FilterChip(
                selected = sender.canStatus,
                onClick = { onChange(sender.copy(canStatus = !sender.canStatus)) },
                label = { Text("Status") },
            )
        }
    }
}

/**
 * "Send texts from" — the outbound SIM picker.
 *
 * Renders nothing at all unless the device genuinely has more than one SIM slot, which is checked
 * without any permission. On a single-SIM phone this setting simply does not exist, rather than
 * being a one-option choice with a permission prompt attached, which is the whole reason the
 * feature can be added without making the Settings screen worse for everyone who will never use it.
 *
 * `READ_PHONE_STATE` is requested here and only here, when the user actually wants to see their
 * SIMs. Declining it is a supported outcome, not an error: the app keeps using Android's default
 * SMS subscription, exactly as it did before this setting existed.
 */
@Composable
private fun OutboundSimCard(
    settings: AppSettingsEntity,
    onChange: (AppSettingsEntity) -> Unit,
) {
    val context = LocalContext.current
    if (!remember { SimSelection.deviceSupportsMultipleSims(context) }) return

    var sims by remember { mutableStateOf(SimSelection.activeSubscriptions(context)) }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            sims = SimSelection.activeSubscriptions(context)
        }

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Send texts from", fontWeight = FontWeight.Bold)
            Text(
                "This phone has more than one SIM. Replies and new texts normally go out on whichever " +
                    "line Android is set to use for SMS; pick a specific one here to override that.\n\n" +
                    "If the SIM you pick is later removed or replaced, SCIF Sidekick goes back to the " +
                    "system default rather than failing to send.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (sims.isEmpty()) {
                Text(
                    "Reading which SIMs are installed needs the phone permission. Without it, texts keep " +
                        "going out on the system default line.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = { permissionLauncher.launch(Manifest.permission.READ_PHONE_STATE) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Show my SIMs") }
            } else {
                val options = listOf(SimSelection.SYSTEM_DEFAULT to "System default") + sims.map { it.subscriptionId to it.label }
                options.forEach { (id, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onChange(settings.copy(outboundSubscriptionId = id)) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = settings.outboundSubscriptionId == id,
                            onClick = { onChange(settings.copy(outboundSubscriptionId = id)) },
                        )
                        Text(label, modifier = Modifier.weight(1f))
                    }
                }
                if (settings.outboundSubscriptionId != SimSelection.SYSTEM_DEFAULT &&
                    sims.none { it.subscriptionId == settings.outboundSubscriptionId }
                ) {
                    Text(
                        "The SIM previously chosen here isn't in the phone any more, so texts are going out " +
                            "on the system default line. Pick one above to change that.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
