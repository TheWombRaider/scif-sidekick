package com.scifsidekick.cleanroom.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.scifsidekick.cleanroom.data.EventLogEntity
import com.scifsidekick.cleanroom.data.EventType
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val failureTypes = setOf(EventType.SEND_FAILED, EventType.SECURITY, EventType.CIRCUIT_OPENED, EventType.DELIVERY_BOUNCED)

/**
 * "Run a test, see it land in the log" was the point of merging these -- Send Test Message and
 * Test Gmail Connectivity used to live on the home screen while the event log they actually
 * produced was a completely separate screen reachable only through the hamburger menu. Now the
 * test controls, the log's own search/filter, the CSV export, and the event feed itself are all
 * one continuous scrollable list (see the LazyColumn note on [ActivityScreen] below).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    events: List<EventLogEntity>,
    onExport: (startMs: Long, endMs: Long, includeBody: Boolean) -> Unit = { _, _, _ -> },
    onSendTestMessage: (sourceType: String, senderAddress: String, body: String, imageUris: List<Uri>) -> Unit = { _, _, _, _ -> },
    onTestConnectivity: () -> Unit = {},
    onTestPush: () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var showFailOnly by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    listState.reportScrollActivity()

    val filtered =
        remember(events, query, showFailOnly) {
            events
                .filter { !showFailOnly || it.type in failureTypes }
                .filter { query.isBlank() || it.reason.contains(query, ignoreCase = true) || it.type.contains(query, ignoreCase = true) }
        }

    // A single LazyColumn for the whole screen -- diagnostics, search field, All/Fail toggle, and
    // the export card are header items rather than siblings of a nested, separately-scrolling
    // LazyColumn. An earlier layout (a plain, non-scrolling Column containing an unweighted
    // LazyColumn as its last child) squeezed the event list into whatever space was left under
    // those headers -- on a normal phone screen, one or two rows, scrollable only within that
    // sliver, with no way to scroll the headers out of the way to see more. One LazyColumn scrolls
    // everything together, and only renders rows actually on/near screen even with a long history.
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { androidx.compose.foundation.layout.Spacer(Modifier.height(2.dp)) }
        item {
            DiagnosticsSection(
                onSendTestMessage = onSendTestMessage,
                onTestConnectivity = onTestConnectivity,
                onTestPush = onTestPush,
            )
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search history") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            // height(IntrinsicSize.Max) + fillMaxHeight() on each segment keeps every segment the
            // same height as its tallest sibling -- see the identical fix in FiltersScreen.kt for
            // why Material3's SegmentedButtonRow needs this spelled out explicitly.
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Max)) {
                listOf(false to "All", true to "Fail").forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = showFailOnly == value,
                        onClick = { showFailOnly = value },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                        modifier = Modifier.fillMaxHeight(),
                        label = { Text(label) },
                    )
                }
            }
        }
        item { ExportMessageLogSection(onExport = onExport) }
        if (filtered.isEmpty()) {
            item { Text("Nothing matches yet.", style = MaterialTheme.typography.bodyMedium) }
        }
        items(filtered, key = { it.id }) { event -> HistoryRow(event) }
        item { androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * Two independent checks, deliberately not one button: "Send Test Message" proves your actual
 * filter configuration works end to end (it goes through the real matching/template/queue
 * pipeline and reports which filter fired, or why none did); "Test Gmail Connectivity" proves
 * OAuth/the Gmail send API work at all, bypassing filters entirely. A failure on only one of the
 * two tells you which half of the pipeline to look at.
 *
 * The message type, sender number, body text, and (for MMS) attached photos are all user-editable
 * -- this is meant to exercise a specific filter condition (a keyword, an allow-listed number, a
 * real photo) on demand, not just prove the pipeline is technically alive with fixed content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsSection(
    onSendTestMessage: (sourceType: String, senderAddress: String, body: String, imageUris: List<Uri>) -> Unit,
    onTestConnectivity: () -> Unit,
    onTestPush: () -> Unit,
) {
    val types = listOf("sms" to "SMS", "mms" to "MMS", "rcs" to "RCS", "call" to "Call")
    var selectedType by rememberSaveable { mutableStateOf(types.first().first) }
    var senderAddress by rememberSaveable { mutableStateOf("") }
    var messageBody by rememberSaveable { mutableStateOf(DEFAULT_TEST_MESSAGE_BODY) }
    // Not rememberSaveable: Uri survives a Bundle round-trip only via a custom Saver, and losing
    // a picked-photo selection across a rotation is a minor, low-stakes inconvenience for a
    // diagnostics screen -- not worth the extra complexity/risk of a hand-rolled Saver here.
    var imageUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    val photoPicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(MAX_TEST_MMS_ATTACHMENTS),
        ) { uris -> imageUris = uris }

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Run a test", fontWeight = FontWeight.Bold)
            Text(
                "Send a test message through your real filters to confirm forwarding actually works, " +
                    "or test Gmail connectivity directly if nothing seems to be sending at all. Results " +
                    "appear in the log below immediately.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Simulate message type", style = MaterialTheme.typography.labelMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Max)) {
                types.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = selectedType == value,
                        onClick = { selectedType = value },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = types.size),
                        modifier = Modifier.fillMaxHeight(),
                        label = { Text(label) },
                    )
                }
            }
            OutlinedTextField(
                value = senderAddress,
                onValueChange = { senderAddress = it },
                label = { Text("Simulate sender number (optional)") },
                placeholder = { Text("Leave blank to use a generic test number") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = messageBody,
                onValueChange = { messageBody = it },
                label = { Text("Message text") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            if (selectedType == "mms") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    ) { Text(if (imageUris.isEmpty()) "Attach photo(s)" else "Change photo(s)") }
                    if (imageUris.isNotEmpty()) {
                        Text(
                            "${imageUris.size} selected",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { imageUris = emptyList() }) { Text("Clear") }
                    }
                }
            }
            Button(
                onClick = { onSendTestMessage(selectedType, senderAddress.trim(), messageBody, imageUris) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Send Test Message") }
            OutlinedButton(onClick = onTestConnectivity, modifier = Modifier.fillMaxWidth()) {
                Text("Test Gmail Connectivity")
            }
            OutlinedButton(onClick = onTestPush, modifier = Modifier.fillMaxWidth()) {
                Text("Test Push Setup")
            }
        }
    }
}

private const val DEFAULT_TEST_MESSAGE_BODY = "[TEST] This is a test message from SCIF Sidekick, sent to verify your filters are working."
private const val MAX_TEST_MMS_ATTACHMENTS = 5

/**
 * Exports the underlying per-message log (one row per live message actually received, with
 * whether it forwarded) for a chosen date/time window -- distinct from the event feed below it,
 * which is this screen's diagnostic trail and isn't what gets exported. Date/time fields are
 * plain text (yyyy-MM-dd / HH:mm) rather than a picker dialog: entirely predictable to parse and
 * validate, no platform picker state to get wrong.
 */
@Composable
private fun ExportMessageLogSection(onExport: (startMs: Long, endMs: Long, includeBody: Boolean) -> Unit) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false } }
    val defaultRange = remember { defaultExportRange() }
    var fromDate by rememberSaveable { mutableStateOf(dateFormat.format(Date(defaultRange.first))) }
    var fromTime by rememberSaveable { mutableStateOf("00:00") }
    var toDate by rememberSaveable { mutableStateOf(dateFormat.format(Date(defaultRange.second))) }
    var toTime by rememberSaveable { mutableStateOf("23:59") }
    var includeBody by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Export message log", fontWeight = FontWeight.Bold)
            Text(
                "CSV of live messages received in this window, one row per message.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = fromDate,
                    onValueChange = { fromDate = it },
                    label = { Text("From (yyyy-MM-dd)") },
                    singleLine = true,
                    modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = fromTime,
                    onValueChange = { fromTime = it },
                    label = { Text("HH:mm") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = toDate,
                    onValueChange = { toDate = it },
                    label = { Text("To (yyyy-MM-dd)") },
                    singleLine = true,
                    modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = toTime,
                    onValueChange = { toTime = it },
                    label = { Text("HH:mm") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = includeBody, onCheckedChange = { includeBody = it })
                Text("Include message text (off by default)", style = MaterialTheme.typography.bodySmall)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(
                onClick = {
                    val range = parseExportWindow(dateFormat, fromDate, fromTime, toDate, toTime)
                    if (range == null) {
                        error = "Enter valid dates (yyyy-MM-dd) and times (HH:mm), with From before To"
                    } else {
                        error = null
                        onExport(range.first, range.second, includeBody)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Export CSV") }
        }
    }
}

/** Parses the four text fields into an inclusive [startMs, endMs] window, or null when anything
 *  doesn't parse or the range is backwards -- kept as a standalone, public function (rather than
 *  private, despite having one call site) specifically so it's directly unit-testable without a
 *  Compose test harness. */
fun parseExportWindow(
    dateFormat: DateFormat,
    fromDate: String,
    fromTime: String,
    toDate: String,
    toTime: String,
): Pair<Long, Long>? {
    val timePattern = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")
    val fromMatch = timePattern.matchEntire(fromTime.trim()) ?: return null
    val toMatch = timePattern.matchEntire(toTime.trim()) ?: return null
    val fromDay = parseWholeDate(dateFormat, fromDate.trim()) ?: return null
    val toDay = parseWholeDate(dateFormat, toDate.trim()) ?: return null

    fun combine(
        day: Date,
        hourMinute: MatchResult,
    ): Long =
        Calendar.getInstance().apply {
            time = day
            set(Calendar.HOUR_OF_DAY, hourMinute.groupValues[1].toInt())
            set(Calendar.MINUTE, hourMinute.groupValues[2].toInt())
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    val startMs = combine(fromDay, fromMatch)
    val endMs = combine(toDay, toMatch)
    return if (startMs <= endMs) startMs to endMs else null
}

/** [DateFormat.parse] with no [java.text.ParsePosition] only fails on a bad *prefix* -- trailing
 *  garbage like "09-01-2026" against "yyyy-MM-dd" silently parses as year 2009, leaving "26"
 *  unconsumed, rather than throwing. Requiring the position to reach the end of the string is
 *  what actually rejects a malformed or wrong-order date instead of misparsing it. */
private fun parseWholeDate(
    dateFormat: DateFormat,
    text: String,
): Date? =
    runCatching {
        // Even the ParsePosition overload can still throw IllegalArgumentException (not just
        // return null) when isLenient=false and a field like month=13 is out of range -- this
        // must be caught here, not left to propagate out of a UI click handler.
        val position = java.text.ParsePosition(0)
        val parsed = dateFormat.parse(text, position)
        if (parsed == null || position.index != text.length) null else parsed
    }.getOrNull()

private fun defaultExportRange(): Pair<Long, Long> {
    val end = System.currentTimeMillis()
    val start = end - 7L * 24 * 60 * 60 * 1000
    return start to end
}

@Composable
private fun HistoryRow(event: EventLogEntity) {
    val isFailure = event.type in failureTypes
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "${event.type} • ${DateFormat.getDateTimeInstance().format(Date(event.timestampMs))}",
                fontWeight = FontWeight.SemiBold,
                color = if (isFailure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text(event.reason, style = MaterialTheme.typography.bodySmall)
        }
    }
}
