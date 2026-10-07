package com.scifsidekick.cleanroom.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.scifsidekick.cleanroom.BuildConfig

data class ChangelogEntry(
    val version: String,
    val changes: List<String>,
    /** ISO date (yyyy-MM-dd), or null where the release date wasn't recorded. */
    val date: String? = null,
)

/** Hand-maintained, not generated -- bump this alongside every version bump in app/build.gradle.kts,
 *  the same way the pre-1.9 releases' INSTALL.txt "WHAT'S NEW" section was written by hand for each
 *  build. Newest first, one short bullet per change. Versions 1.3.0-1.6.x and 1.8.x have no
 *  surviving release notes -- no INSTALL.txt and no tagged commit in this repo's history -- so
 *  there's a real gap there, not an omission. Dates exist only where git history recorded them. */
object Changelog {
    const val LICENSE_NOTICE =
        "Licensed under the BSD Zero Clause License (0BSD). Free to use, copy, modify and distribute, " +
            "with no attribution required."

    val entries =
        listOf(
            ChangelogEntry(
                "1.28.0",
                listOf(
                    "New filters have Missed calls checked by default.",
                    "The scheduled-hours day picker is one even row of seven days instead of wrapping chips.",
                    "Heartbeat email: one-tap buttons add any address your filters already forward to.",
                    "Backup and Restore are now a single \"Backup and restore\" section.",
                ),
                date = "2026-10-07",
            ),
            ChangelogEntry(
                "1.27.0",
                listOf(
                    "New [SCIF:HELP] email command: replies with a full manual of every command and its syntax.",
                    "Help works for any address on the authorized list, whichever boxes are ticked.",
                    "Changelog is now a bulleted list per version.",
                    "About screen shows the release date and license.",
                ),
                date = "2026-10-07",
            ),
            ChangelogEntry(
                "1.26.0",
                listOf(
                    "New Commands screen listing every email command.",
                    "New \"Send test receipt\" button in Settings.",
                    "The Gmail account you connect is authorized for Compose, Enable, Disable and Status from the start.",
                ),
                date = "2026-10-06",
            ),
            ChangelogEntry(
                "1.25.0",
                listOf(
                    "The widget toggle can't be triggered by other apps.",
                    "Premium-rate numbers are blocked.",
                    "Texts are capped per hour and per day.",
                    "The remote-command check can't be buried by junk mail.",
                    "Receipts now send reliably.",
                    "New \"Hide app content in Recents\" setting.",
                ),
                date = "2026-10-06",
            ),
            ChangelogEntry(
                "1.24.0",
                listOf(
                    "[SCIF:ON] and [SCIF:OFF] email back a confirmation with the current status.",
                    "Unauthorized senders get no reply.",
                ),
            ),
            ChangelogEntry(
                "1.23.1",
                listOf("Heartbeat email's \"Send every\" takes a custom number of hours, not just the 6h/12h/24h/48h presets."),
            ),
            ChangelogEntry(
                "1.23.0",
                listOf(
                    "Compose, Enable, Disable and Status are now one \"Remote control by email\" card.",
                    "One kill switch, on by default.",
                    "One address list, with a checkbox per command for each address.",
                ),
            ),
            ChangelogEntry(
                "1.22.0",
                listOf(
                    "Texts are forwarded the moment they arrive, even with the screen off.",
                    "History shows how long each forward took.",
                    "Email replies and commands are picked up about every 30 seconds instead of 90.",
                    "Pub/Sub access is only requested while Gmail push is on.",
                ),
            ),
            ChangelogEntry("1.21.0", listOf("Dual-SIM phones can choose which line outbound texts are sent from.")),
            ChangelogEntry("1.20.0", listOf("Added [SCIF:STATUS]: email the app to ask what it's doing and get a summary back.")),
            ChangelogEntry("1.19.1", listOf("Fixed the heartbeat email not sending while forwarding was switched off.")),
            ChangelogEntry("1.19.0", listOf("Added an opt-in heartbeat email, so a silent failure shows up as a message that stops arriving.")),
            ChangelogEntry("1.18.0", listOf("Replies you send by email get a confirmation email back saying whether the text sent.")),
            ChangelogEntry("1.17.0", listOf("Added \"Disable forwarding by email\": the [SCIF:OFF] counterpart, with its own allowlist.")),
            ChangelogEntry("1.16.1", listOf("Reduced background battery use from RCS notification access.")),
            ChangelogEntry("1.16.0", listOf("Added \"Enable forwarding by email\": an allowlisted [SCIF:ON] email turns forwarding back on while the phone is out of reach.")),
            ChangelogEntry(
                "1.15.0",
                listOf(
                    "Added this About screen with version and changelog.",
                    "Added quick-toggle filters on the dashboard.",
                ),
            ),
            ChangelogEntry(
                "1.14.2",
                listOf(
                    "Color-wheel bitmaps are generated asynchronously.",
                    "Removed a blocking debug-only preference write.",
                ),
            ),
            ChangelogEntry("1.14.1", listOf("Fixed accent selection resetting your scroll position in Settings.")),
            ChangelogEntry("1.14.0", listOf("Rainbow Road now animates continuously through scrolling.")),
            ChangelogEntry("1.13.7", listOf("Rainbow Road's scroll-pause fix now covers the Home, Settings and Developer tools screens too.")),
            ChangelogEntry("1.13.6", listOf("Rainbow Road pauses its color animation while you scroll.")),
            ChangelogEntry("1.13.5", listOf("Lowered Rainbow Road's color update rate further to reduce scroll lag.")),
            ChangelogEntry("1.13.4", listOf("Throttled Rainbow Road's color update to fix severe lag while it's active.")),
            ChangelogEntry("1.13.3", listOf("Fixed RCS reply routing for contacts not saved to your phone.")),
            ChangelogEntry("1.13.0", listOf("Removed database encryption after confirmed on-device migration failures.")),
            ChangelogEntry(
                "1.12.8",
                listOf(
                    "RCS reply-routing fixes.",
                    "Telephony result fixes.",
                    "Message de-duplication fixes.",
                ),
            ),
            ChangelogEntry(
                "1.12.3",
                listOf(
                    "Added Gmail push (beta).",
                    "Added the Rainbow Road easter egg.",
                    "RCS reply-routing fixes.",
                ),
            ),
            ChangelogEntry("1.11.2", listOf("Added multi-select delete to the Filters list.")),
            ChangelogEntry("1.11.1", listOf("UI polish and bug fixes from an overnight review.")),
            ChangelogEntry(
                "1.11.0",
                listOf(
                    "Added a background watchdog.",
                    "Added bounce detection.",
                    "Added snooze.",
                    "Added a home-screen widget.",
                    "Added backup encryption.",
                ),
            ),
            ChangelogEntry(
                "1.10.0",
                listOf(
                    "Consolidated the Dashboard, Activity and Settings screens.",
                    "Filters now require a recipient to save.",
                ),
            ),
            ChangelogEntry(
                "1.9.2",
                listOf(
                    "Test message body is editable.",
                    "Test messages can include MMS photo attachments.",
                    "History log is now full-screen.",
                ),
            ),
            ChangelogEntry("1.9.1", listOf("Fixed a bug that could send a duplicate reply SMS.")),
            ChangelogEntry("1.7.0", listOf("Added a Quick Settings tile to pause/resume forwarding without opening the app.")),
            ChangelogEntry("1.2.1", listOf("Dark mode uses true pure-black OLED backgrounds.")),
            ChangelogEntry(
                "1.2.0",
                listOf(
                    "Added RCS coverage via notification access.",
                    "Added Appearance customization (theme and accent).",
                    "Added developer tools.",
                ),
            ),
            ChangelogEntry("1.1.0", listOf("Initial release: SMS/MMS forwarding to Gmail with reply-by-email support.")),
        )
}

/**
 * Tap counting for the Rainbow Road unlock, the same gag Android uses for Developer options: tap
 * the version card [REQUIRED] times. The first few taps say nothing; the last few count down.
 */
object RainbowRoadTaps {
    const val REQUIRED = 7
    const val COUNTDOWN_FROM = 3

    sealed interface Result {
        /** Silent progress. */
        data object Quiet : Result

        /** [remaining] more taps to go. */
        data class Countdown(
            val remaining: Int,
        ) : Result

        data object Unlock : Result
    }

    /** [tapsSoFar] counts this tap. */
    fun result(tapsSoFar: Int): Result {
        val remaining = REQUIRED - tapsSoFar
        return when {
            remaining <= 0 -> Result.Unlock
            remaining <= COUNTDOWN_FROM -> Result.Countdown(remaining)
            else -> Result.Quiet
        }
    }
}

/** A single LazyColumn for the whole screen, identity/developer cards as header items ahead of the
 *  changelog list -- the same shape [ActivityScreen] and [FiltersListScreen] already use, for the
 *  same reason: this screen's own list (the changelog) has to scroll together with everything above
 *  it, not in a separately-scrolling nested container. Reports its own scroll state into Rainbow
 *  Road's pause mechanism like every other scrollable screen -- see
 *  [LazyListState.reportScrollActivity]'s doc comment for why every one of these needs that call. */
@Composable
fun AboutScreenBody(
    rainbowRoadUnlocked: Boolean = true,
    onRainbowRoadUnlocked: () -> Unit = {},
    onMessage: (String) -> Unit = {},
) {
    var versionTaps by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    listState.reportScrollActivity()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(2.dp)) }

        item {
            OutlinedCard(
                Modifier.fillMaxWidth().clickable(enabled = !rainbowRoadUnlocked) {
                    versionTaps++
                    when (val result = RainbowRoadTaps.result(versionTaps)) {
                        RainbowRoadTaps.Result.Quiet -> Unit
                        is RainbowRoadTaps.Result.Countdown ->
                            onMessage(
                                if (result.remaining == 1) "1 more tap..." else "${result.remaining} more taps...",
                            )
                        RainbowRoadTaps.Result.Unlock -> {
                            versionTaps = 0
                            onRainbowRoadUnlocked()
                        }
                    }
                },
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("SCIF Sidekick", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Changelog.entries
                        .firstOrNull { it.version == BuildConfig.VERSION_NAME }
                        ?.date
                        ?.let { Text("Released $it", style = MaterialTheme.typography.bodySmall) }
                    Text(Changelog.LICENSE_NOTICE, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            Text("Changelog", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }

        items(Changelog.entries, key = ChangelogEntry::version) { entry ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(entry.version, fontWeight = FontWeight.Bold)
                        entry.date?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    entry.changes.forEach { change ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("•", style = MaterialTheme.typography.bodySmall)
                            Text(change, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
