package com.scifsidekick.cleanroom.ui

import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scifsidekick.cleanroom.data.EventLogEntity
import com.scifsidekick.cleanroom.data.ForwardingFilterEntity
import com.scifsidekick.cleanroom.data.ForwardingStateEntity
import com.scifsidekick.cleanroom.util.PayloadCodec
import java.text.DateFormat
import java.util.Date

@Composable
internal fun HomeScreen(
    padding: PaddingValues,
    state: ForwardingStateEntity,
    queued: Int,
    lastSentAt: Long?,
    filters: List<ForwardingFilterEntity>,
    events: List<EventLogEntity>,
    smsPermissionsGranted: Boolean,
    gmailConnected: Boolean,
    notificationAccessGranted: Boolean,
    batteryUnrestricted: Boolean,
    onGrantPermissions: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onNavigate: (AppScreen) -> Unit,
    vm: MainViewModel,
) {
    val listState = rememberLazyListState()
    listState.reportScrollActivity()
    val microsoftState by vm.microsoftState.collectAsStateWithLifecycle()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(2.dp)) }
        item {
            val hasReadyFilter = filters.any { it.enabled && PayloadCodec.pathsFromJson(it.recipientsJson).isNotEmpty() }
            SetupChecklistCard(
                smsPermissionsGranted = smsPermissionsGranted,
                gmailConnected = gmailConnected,
                notificationAccessGranted = notificationAccessGranted,
                hasReadyFilter = hasReadyFilter,
                onGrantPermissions = onGrantPermissions,
                onConnectGmail = { onNavigate(AppScreen.SETTINGS) },
                onOpenNotificationAccess = onOpenNotificationAccess,
                onAddFilter = { onNavigate(AppScreen.FILTERS) },
            )
        }
        item {
            StatusCard(
                enabled = state.enabled,
                queued = queued,
                circuitOpen = state.emailCircuitOpen,
                snoozedUntilMs = state.snoozedUntilMs,
                lastSentAt = lastSentAt,
                onToggle = vm::setForwarding,
                onReset = vm::resetCircuit,
                onSnooze = vm::snoozeForwarding,
                onCancelSnooze = vm::cancelSnooze,
            )
        }
        item {
            // A glance, not a management surface: each chip's detail and controls live on Settings.
            ConnectionHealthRow(
                gmailConnected = gmailConnected,
                onGmailClick = { onNavigate(AppScreen.SETTINGS) },
                outlook = OutlookChipState.of(microsoftState),
                onOutlookClick = { onNavigate(AppScreen.SETTINGS) },
                rcsGranted = notificationAccessGranted,
                onRcsClick = onOpenNotificationAccess,
                batteryUnrestricted = batteryUnrestricted,
                onBatteryClick = onRequestBatteryExemption,
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Filters",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onNavigate(AppScreen.FILTERS) }) { Text("Manage") }
            }
        }
        if (filters.isEmpty()) {
            item {
                Text(
                    "No filters configured yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // Toggle-only; editing conditions, recipients, or order stays on the Filters screen.
            items(filters.take(5), key = { it.id }) { filter ->
                DashboardFilterRow(
                    filter = filter,
                    onToggleEnabled = { enabled -> vm.setFilterEnabled(filter.id, enabled) },
                    onClick = { onNavigate(AppScreen.FILTERS) },
                )
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Recent activity",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onNavigate(AppScreen.ACTIVITY) }) { Text("View all") }
            }
        }
        items(events.take(5), key = { it.id }) { EventRow(it) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun StatusCard(
    enabled: Boolean,
    queued: Int,
    circuitOpen: Boolean,
    snoozedUntilMs: Long,
    lastSentAt: Long?,
    onToggle: (Boolean) -> Unit,
    onReset: () -> Unit,
    onSnooze: (Long) -> Unit,
    onCancelSnooze: () -> Unit,
) {
    var snoozeMenuExpanded by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val snoozedActive = !enabled && snoozedUntilMs > now
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            snoozedActive -> "Snoozed until ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(snoozedUntilMs))}"
                            enabled -> "Forwarding ON"
                            else -> "Forwarding OFF"
                        },
                        fontWeight = FontWeight.Bold,
                    )
                    Text("$queued queued")
                    Text(
                        if (lastSentAt == null) "No messages forwarded yet" else "Last forwarded ${formatRelativeTime(lastSentAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            if (snoozedActive) {
                TextButton(onClick = onCancelSnooze) { Text("Resume now") }
            } else if (enabled) {
                Box {
                    TextButton(onClick = { snoozeMenuExpanded = true }) { Text("Snooze") }
                    DropdownMenu(expanded = snoozeMenuExpanded, onDismissRequest = { snoozeMenuExpanded = false }) {
                        val options =
                            listOf(
                                "30 minutes" to 30L * 60_000L,
                                "2 hours" to 2L * 60L * 60_000L,
                                "8 hours" to 8L * 60L * 60_000L,
                            )
                        options.forEach { (label, durationMs) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    snoozeMenuExpanded = false
                                    onSnooze(durationMs)
                                },
                            )
                        }
                    }
                }
            }
            if (circuitOpen) {
                Text("Sending is paused after five consecutive failures.", color = MaterialTheme.colorScheme.error)
                Button(onClick = onReset) { Text("Review complete — resume") }
            }
        }
    }
}

/** A short "3m ago" / "2h ago" rendering for timestamps that matter mostly by how recent they are
 *  -- the full date/time (see [EventRow]) is the right call for the history list, but too verbose
 *  for an at-a-glance dashboard card. Falls back to a full date past a week so this never claims
 *  something happened "40320m ago". */
private fun formatRelativeTime(timestampMs: Long): String {
    val deltaMs = (System.currentTimeMillis() - timestampMs).coerceAtLeast(0)
    val minutes = deltaMs / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 24 * 60 -> "${minutes / 60}h ago"
        minutes < 7 * 24 * 60 -> "${minutes / (24 * 60)}d ago"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(timestampMs))
    }
}

/** Onboarding path for a fresh install, distinct from [ConnectionHealthRow]'s ongoing-health
 *  glance -- this walks a new user through exactly what's left to do, in order, and disappears
 *  entirely once every step is complete so it never clutters the dashboard for a returning user. */
@Composable
private fun SetupChecklistCard(
    smsPermissionsGranted: Boolean,
    gmailConnected: Boolean,
    notificationAccessGranted: Boolean,
    hasReadyFilter: Boolean,
    onGrantPermissions: () -> Unit,
    onConnectGmail: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onAddFilter: () -> Unit,
) {
    // Deliberately stops at "everything forwarding needs is configured," not "forwarding is
    // currently on" -- the on/off switch sits right below this card and is a normal, everyday
    // thing to flip off (stepping out, pausing, snoozing); it isn't an unfinished setup step, and
    // treating it like one meant this card came back to nag every time someone paused forwarding
    // for a perfectly ordinary reason.
    val steps =
        listOf(
            Triple("Grant SMS permissions", smsPermissionsGranted, onGrantPermissions),
            Triple("Connect Gmail", gmailConnected, onConnectGmail),
            Triple("Allow notification access for RCS", notificationAccessGranted, onOpenNotificationAccess),
            Triple("Add a filter with a recipient", hasReadyFilter, onAddFilter),
        )
    if (steps.all { it.second }) return
    val remaining = steps.count { !it.second }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Finish setup — $remaining step${if (remaining == 1) "" else "s"} left", fontWeight = FontWeight.Bold)
            steps.forEach { (label, done, action) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .let { if (!done) it.clickable(onClick = action) else it }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (done) "✓" else "○",
                        color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 10.dp),
                    )
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** A compact, tappable at-a-glance row for the three things worth checking without leaving the
 *  dashboard -- their full detail and controls now live on the Settings screen. Reuses
 *  [FilterChip] (already proven elsewhere in this app) rather than introducing a new chip style:
 *  `selected = true` reads as "healthy," `selected = false` as "needs attention," which is exactly
 *  the semantic these three booleans need. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionHealthRow(
    gmailConnected: Boolean,
    onGmailClick: () -> Unit,
    outlook: OutlookChipState,
    onOutlookClick: () -> Unit,
    rcsGranted: Boolean,
    onRcsClick: () -> Unit,
    batteryUnrestricted: Boolean,
    onBatteryClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HealthChip("Gmail", gmailConnected, onGmailClick, Modifier.weight(1f))
        // Only once an Outlook account exists: Gmail-only setups keep their three chips.
        if (outlook != OutlookChipState.HIDDEN) {
            FilterChip(
                selected = outlook == OutlookChipState.OK,
                onClick = onOutlookClick,
                label = { Text(if (outlook == OutlookChipState.OK) "Outlook ✓" else "Outlook ⚠", maxLines = 1) },
                modifier =
                    Modifier.weight(1f).semantics {
                        contentDescription = if (outlook == OutlookChipState.OK) "Outlook connected" else "Outlook needs reconnecting"
                    },
            )
        }
        HealthChip("RCS", rcsGranted, onRcsClick, Modifier.weight(1f))
        HealthChip("Battery", batteryUnrestricted, onBatteryClick, Modifier.weight(1f))
    }
}

/** The Home screen's Outlook chip: absent, healthy, or needing a reconnect. */
private enum class OutlookChipState {
    HIDDEN,
    OK,
    NEEDS_RECONNECT,
    ;

    companion object {
        fun of(state: MicrosoftUiState): OutlookChipState =
            when (state) {
                is MicrosoftUiState.Connected -> OK
                is MicrosoftUiState.NeedsReconnect -> NEEDS_RECONNECT
                else -> HIDDEN
            }
    }
}

@Composable
private fun HealthChip(
    label: String,
    ok: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = ok,
        onClick = onClick,
        label = { Text(if (ok) "$label ✓" else label, maxLines = 1) },
        modifier = modifier,
    )
}

/** A toggle-only, at-a-glance preview of one filter for the dashboard -- deliberately a much
 *  lighter row than FiltersScreen.kt's own FilterSummaryCard: no drag handle, no duplicate
 *  button, no selection mode, since those are editing affordances that stay exclusive to the
 *  full Filters screen. Tapping the switch flips it in place via [onToggleEnabled]; tapping
 *  anywhere else on the row calls [onClick], which the caller wires to navigate to Filters. */
@Composable
private fun DashboardFilterRow(
    filter: ForwardingFilterEntity,
    onToggleEnabled: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val recipients = remember(filter.recipientsJson) { PayloadCodec.pathsFromJson(filter.recipientsJson) }
    OutlinedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth().semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(filter.name, fontWeight = FontWeight.Bold)
                Text(
                    if (recipients.isEmpty()) "No recipients -- won't forward" else "${recipients.size} recipient(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (recipients.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Consumes its own tap before it reaches the card's clickable underneath -- the same
            // nested-clickable handling FilterSummaryCard's own Switch already relies on, so
            // toggling here never also navigates away.
            Switch(checked = filter.enabled, onCheckedChange = onToggleEnabled)
        }
    }
}

@Composable
private fun EventRow(event: EventLogEntity) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("${event.type} • ${DateFormat.getDateTimeInstance().format(Date(event.timestampMs))}", fontWeight = FontWeight.SemiBold)
        Text(event.reason, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}
