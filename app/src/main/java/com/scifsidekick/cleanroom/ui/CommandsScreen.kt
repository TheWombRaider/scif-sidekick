package com.scifsidekick.cleanroom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.scifsidekick.cleanroom.util.RemoteCommands

/**
 * One email command, as shown on the Commands screen. [subject] is the exact text to put in the
 * subject line; unit tests check that every one still parses to the command it is documented as.
 */
data class CommandHelpEntry(
    val title: String,
    val subject: String,
    val effect: String,
    /** The per-address checkbox this command needs in Settings; null when any listed address may use it. */
    val permission: String?,
    val reply: String,
) {
    val permissionLine: String
        get() = if (permission == null) "Works for any address on the authorized list." else "Needs the \"$permission\" permission."
}

object CommandHelp {
    const val COMPOSE_EXAMPLE = "TEXT+15551234567"
    const val REPLY_TAG_EXAMPLE = "[SCIF:+15551234567]"

    val entries =
        listOf(
            CommandHelpEntry(
                title = "Forwarding on",
                subject = RemoteCommands.ENABLE_TAG,
                effect =
                    "Turns forwarding on, even if the app is idle. Picked up within about 15 minutes; " +
                        "Android may delay it longer while the phone is idle.",
                permission = "Enable",
                reply = "A \"Forwarding ENABLED\" receipt with the current status.",
            ),
            CommandHelpEntry(
                title = "Forwarding off",
                subject = RemoteCommands.DISABLE_TAG,
                effect = "Turns forwarding off. Picked up within about 30 seconds.",
                permission = "Disable",
                reply = "A \"Forwarding DISABLED\" receipt with the current status.",
            ),
            CommandHelpEntry(
                title = "Status",
                subject = RemoteCommands.STATUS_TAG,
                effect =
                    "Changes nothing. Picked up within about 30 seconds while forwarding is on, " +
                        "about 15 minutes while it is off.",
                permission = "Status",
                reply = "On or off, service health, Gmail authorization and queue depth.",
            ),
            CommandHelpEntry(
                title = "Help",
                subject = RemoteCommands.HELP_TAG,
                effect =
                    "Changes nothing. Emails back this full manual: every command, its exact syntax, " +
                        "and the rules. Picked up within about 30 seconds while forwarding is on, " +
                        "about 15 minutes while it is off.",
                permission = null,
                reply = "The command manual, as plain text.",
            ),
            CommandHelpEntry(
                title = "Send a new text",
                subject = COMPOSE_EXAMPLE,
                effect =
                    "The body is texted to that number. Country code, digits only. It must be the whole " +
                        "subject; extra words make the app ignore it. Attach a photo to send an MMS.",
                permission = "Compose",
                reply = "No reply email. It is a text.",
            ),
        )

    val rules =
        listOf(
            "To answer a forwarded text, hit Reply and keep the $REPLY_TAG_EXAMPLE tag in the subject. " +
                "Your answer is sent as an SMS.",
            "The [SCIF:ON], [SCIF:OFF], [SCIF:STATUS] and [SCIF:HELP] tags can sit anywhere in the subject, so a Re: or Fwd: prefix is fine.",
            "A subject with more than one command tag is ignored as ambiguous.",
            "The sender must be on the authorized list in Settings, ticked for that command (Help only " +
                "needs to be on the list), and Gmail must show a DMARC pass for the address.",
            "Mail from anyone else gets no reply. Rejected commands are logged in Activity.",
        )

    fun statusLine(
        remoteControlEnabled: Boolean,
        authorizedAddressCount: Int,
    ): String =
        when {
            !remoteControlEnabled -> "Remote control is off in Settings, so none of these are answered."
            authorizedAddressCount == 0 -> "Remote control is on, but no address is authorized yet. Add one in Settings."
            authorizedAddressCount == 1 -> "Remote control is on. 1 address is authorized."
            else -> "Remote control is on. $authorizedAddressCount addresses are authorized."
        }
}

/** Same single-LazyColumn shape as [AboutScreenBody] so the whole screen scrolls as one list. */
@Composable
fun CommandsScreenBody(
    remoteControlEnabled: Boolean,
    authorizedAddressCount: Int,
) {
    val listState = rememberLazyListState()
    listState.reportScrollActivity()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(2.dp)) }

        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Email commands", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Send these from an authorized address to the Gmail account this app watches. " +
                            "Put the text in the subject line. Tap and hold a subject to copy it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        CommandHelp.statusLine(remoteControlEnabled, authorizedAddressCount),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        items(CommandHelp.entries, key = CommandHelpEntry::title) { entry ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.title, fontWeight = FontWeight.Bold)
                    SelectionContainer {
                        Text(entry.subject, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(entry.effect, style = MaterialTheme.typography.bodySmall)
                    Text("Reply: ${entry.reply}", style = MaterialTheme.typography.bodySmall)
                    Text(entry.permissionLine, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Good to know", fontWeight = FontWeight.Bold)
                    CommandHelp.rules.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
