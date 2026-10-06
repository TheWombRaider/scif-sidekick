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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.scifsidekick.cleanroom.BuildConfig

data class ChangelogEntry(
    val version: String,
    val summary: String,
)

/** Hand-maintained, not generated -- bump this alongside every version bump in app/build.gradle.kts,
 *  the same way the pre-1.9 releases' INSTALL.txt "WHAT'S NEW" section was written by hand for each
 *  build. Newest first. Versions 1.3.0-1.6.x and 1.8.x have no surviving release notes -- no
 *  INSTALL.txt and no tagged commit in this repo's history -- so there's a real gap there, not an
 *  omission. */
object Changelog {
    val entries =
        listOf(
            ChangelogEntry(
                "1.25.0",
                "Security hardening: the widget toggle can't be triggered by other apps, premium-rate numbers are " +
                    "blocked, texts are capped per hour and day, and the remote-command check can't be buried by " +
                    "junk mail. Receipts now send reliably, and there is a new \"Hide app content in Recents\" setting.",
            ),
            ChangelogEntry(
                "1.24.0",
                "[SCIF:ON] and [SCIF:OFF] now email back a confirmation with the current status. " +
                    "Unauthorized senders get no reply.",
            ),
            ChangelogEntry(
                "1.23.1",
                "Heartbeat email's \"Send every\" now takes a custom number of hours, not just the 6h/12h/24h/48h " +
                    "presets.",
            ),
            ChangelogEntry(
                "1.23.0",
                "Compose, Enable, Disable, and Status are now one \"Remote control by email\" card: a single kill " +
                    "switch, on by default, plus one address list where each address gets its own checkbox per " +
                    "command instead of four separate allowlists.",
            ),
            ChangelogEntry(
                "1.22.0",
                "Texts are forwarded the moment they arrive, even with the screen off, and History shows how long each took. " +
                    "Email replies and commands are picked up about every 30 seconds instead of 90. Pub/Sub access is only " +
                    "requested while Gmail push is on.",
            ),
            ChangelogEntry("1.21.0", "Dual-SIM phones can now choose which line outbound texts are sent from."),
            ChangelogEntry("1.20.0", "Added \"[SCIF:STATUS]\" -- email the app to ask what it's doing and get a summary back."),
            ChangelogEntry("1.19.1", "Fixed the heartbeat email not actually sending while forwarding was switched off."),
            ChangelogEntry("1.19.0", "Added an opt-in heartbeat email so a silent failure shows up as a message that stops arriving."),
            ChangelogEntry("1.18.0", "Replies you send by email now get a confirmation email back saying whether the text actually sent."),
            ChangelogEntry("1.17.0", "Added \"Disable forwarding by email\" -- the [SCIF:OFF] counterpart, with its own separate allowlist."),
            ChangelogEntry("1.16.1", "Reduced background battery use from RCS notification access."),
            ChangelogEntry("1.16.0", "Added \"Enable forwarding by email\" -- an allowlisted [SCIF:ON] email can turn forwarding back on while the phone is out of reach."),
            ChangelogEntry("1.15.0", "Added this About screen (version, changelog, developer info) and dashboard filter quick-toggles."),
            ChangelogEntry("1.14.2", "Audit fixes: async color-wheel bitmap generation, removed a blocking debug-only preference write."),
            ChangelogEntry("1.14.1", "Fixed accent selection resetting your scroll position on the Settings screen."),
            ChangelogEntry("1.14.0", "Rainbow Road now animates continuously through scrolling."),
            ChangelogEntry("1.13.7", "Rainbow Road's scroll-pause fix now covers the Home, Settings, and Developer tools screens too."),
            ChangelogEntry("1.13.6", "Rainbow Road pauses its color animation while you're actively scrolling."),
            ChangelogEntry("1.13.5", "Rainbow Road's color update rate lowered further to reduce scroll lag."),
            ChangelogEntry("1.13.4", "Rainbow Road's color update throttled to fix severe lag while it's active."),
            ChangelogEntry("1.13.3", "Fixed RCS reply routing for contacts not saved to your phone."),
            ChangelogEntry("1.13.0", "Removed database encryption after confirmed on-device migration failures."),
            ChangelogEntry("1.12.8", "RCS reply-routing, telephony result, and message-dedup fixes."),
            ChangelogEntry("1.12.3", "Added Gmail push (beta), the Rainbow Road easter egg, and RCS reply-routing fixes."),
            ChangelogEntry("1.11.2", "Added multi-select delete to the Filters list."),
            ChangelogEntry("1.11.1", "UI polish and bug fixes from an overnight review."),
            ChangelogEntry("1.11.0", "Added background watchdog, bounce detection, snooze, home-screen widget, and backup encryption."),
            ChangelogEntry("1.10.0", "Dashboard/Activity/Settings UI consolidation; filters now require a recipient to save."),
            ChangelogEntry("1.9.2", "Editable test-message body, MMS photo attachments in tests, and a full-screen history log."),
            ChangelogEntry("1.9.1", "Fixed a bug that could send a duplicate reply SMS."),
            ChangelogEntry("1.7.0", "Added a Quick Settings tile to pause/resume forwarding without opening the app."),
            ChangelogEntry("1.2.1", "Dark mode now uses true pure-black OLED backgrounds."),
            ChangelogEntry("1.2.0", "Added RCS coverage via notification access, Appearance customization (theme + accent), and developer tools."),
            ChangelogEntry("1.1.0", "Initial release -- SMS/MMS forwarding to Gmail with reply-by-email support."),
        )
}

/** A single LazyColumn for the whole screen, identity/developer cards as header items ahead of the
 *  changelog list -- the same shape [ActivityScreen] and [FiltersListScreen] already use, for the
 *  same reason: this screen's own list (the changelog) has to scroll together with everything above
 *  it, not in a separately-scrolling nested container. Reports its own scroll state into Rainbow
 *  Road's pause mechanism like every other scrollable screen -- see
 *  [LazyListState.reportScrollActivity]'s doc comment for why every one of these needs that call. */
@Composable
fun AboutScreenBody() {
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
                    Text("SCIF Sidekick", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Forwards texts to email and turns email replies back into texts, so people who " +
                            "can't carry a phone into a SCIF can stay reachable.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            Text("Changelog", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }

        items(Changelog.entries, key = ChangelogEntry::version) { entry ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.version, fontWeight = FontWeight.Bold)
                    Text(entry.summary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
