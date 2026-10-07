package com.scifsidekick.cleanroom.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.scifsidekick.cleanroom.data.ContactFilterMode
import com.scifsidekick.cleanroom.data.FilterConditionMode
import com.scifsidekick.cleanroom.data.ForwardingFilterEntity
import com.scifsidekick.cleanroom.data.KeywordFilterMode
import com.scifsidekick.cleanroom.util.MessageTemplateEngine
import com.scifsidekick.cleanroom.util.PayloadCodec
import com.scifsidekick.cleanroom.util.ReorderMath
import com.scifsidekick.cleanroom.util.ReplaceRule
import com.scifsidekick.cleanroom.util.ReplaceRuleCodec
import com.scifsidekick.cleanroom.util.ReplaceRuleEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FiltersListScreen(
    filters: List<ForwardingFilterEntity>,
    onOpen: (Long) -> Unit,
    onCreate: () -> Unit,
    onReorder: (List<Long>) -> Unit,
    onToggleEnabled: (Long, Boolean) -> Unit,
    onDuplicate: (Long) -> Unit,
    onDeleteMultiple: (List<Long>) -> Unit,
) {
    // Non-empty is what actually means "selection mode is active" -- one flag instead of two,
    // and exiting selection mode is just clearing this set rather than a separate action that
    // could get out of sync with it.
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val selectionMode = selectedIds.isNotEmpty()
    // A LazyColumn with `key = { it.id }`, not a plain Column with forEachIndexed, is load-
    // bearing here, not a style choice: without a stable per-item composition key, reordering
    // `localOrder` mid-drag can hand the row hosting the *active* drag gesture's pointerInput
    // coroutine over to a different filter that now occupies that screen position, cancelling
    // the gesture -- which is exactly why a first pass at this only ever managed one swap per
    // touch. Keying by id keeps each row's composition (and its live gesture, if any) attached
    // to the same filter no matter where the list moves it, so a single continuous drag can
    // travel through the whole list. It also gets every *other* row's `animateItem()` slide
    // animation for free, which is the "apps sliding out of the way" feel this needed.
    var localOrder by remember { mutableStateOf(filters) }
    var draggingId by remember { mutableStateOf<Long?>(null) }
    // Holds the id order just committed to onReorder while its (asynchronous) write to Room
    // hasn't yet round-tripped back through the live `filters` Flow. Without this, the instant
    // the drag ends the LaunchedEffect below would re-sync from `filters`, which for a brief
    // moment still reflects the *old* order -- snapping the just-dropped row back to where it
    // started before snapping forward again once the write lands. Holding the optimistic local
    // order until the live data actually catches up to it avoids that flicker.
    var pendingReorderIds by remember { mutableStateOf<List<Long>?>(null) }
    LaunchedEffect(filters) {
        if (draggingId != null) return@LaunchedEffect
        val pending = pendingReorderIds
        if (pending == null || filters.map { it.id } == pending) {
            localOrder = filters
            pendingReorderIds = null
        }
    }
    val density = LocalDensity.current
    val gapPx = with(density) { 12.dp.toPx() }
    var rowHeightPx by remember { mutableFloatStateOf(0f) }
    // One accumulator per filter id, surviving the row's move to any index, exactly like the
    // composition state above -- a plain per-iteration `remember` would have the same
    // positional-identity problem the whole rewrite exists to avoid.
    val dragOffsets = remember { mutableStateMapOf<Long, Float>() }
    val listState = rememberLazyListState()
    listState.reportScrollActivity()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(2.dp)) }
        item {
            if (selectionMode) {
                FilterSelectionBar(
                    selectedCount = selectedIds.size,
                    totalCount = localOrder.size,
                    onToggleSelectAll = {
                        selectedIds =
                            if (selectedIds.size == localOrder.size) emptySet() else localOrder.map { it.id }.toSet()
                    },
                    onCancel = { selectedIds = emptySet() },
                    onDeleteRequested = { showDeleteConfirm = true },
                )
            } else {
                Text(
                    if (localOrder.size > 1) {
                        "Checked in the order below; touch and hold the grip to drag a filter anywhere in the list, " +
                            "or touch and hold a filter itself to select several to delete at once. Each enabled " +
                            "match queues its own email unless a filter above it is set to stop processing further filters."
                    } else {
                        "A message is checked against every enabled filter below; each one that matches queues its own email."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(localOrder, key = { it.id }) { filter ->
            val isDragging = draggingId == filter.id
            val liftScale by animateFloatAsState(if (isDragging) 1.03f else 1f, label = "filterLift")
            FilterSummaryCard(
                filter = filter,
                onClick = { onOpen(filter.id) },
                onToggleEnabled = { enabled -> onToggleEnabled(filter.id, enabled) },
                onDuplicate = { onDuplicate(filter.id) },
                selectionMode = selectionMode,
                selected = filter.id in selectedIds,
                onToggleSelected = {
                    selectedIds =
                        if (filter.id in selectedIds) selectedIds - filter.id else selectedIds + filter.id
                },
                onLongPress = { selectedIds = selectedIds + filter.id },
                modifier =
                    Modifier
                        // The dragged row's position is driven by hand below, tracking the
                        // finger with zero lag; every other row keeps its automatic placement
                        // animation so it visibly slides into its new slot instead of jumping.
                        .let { if (isDragging) it else it.animateItem() }
                        .zIndex(if (isDragging) 1f else 0f)
                        .onGloballyPositioned { coordinates ->
                            if (rowHeightPx <= 0f) rowHeightPx = coordinates.size.height.toFloat()
                        }.graphicsLayer {
                            translationY = if (isDragging) dragOffsets[filter.id] ?: 0f else 0f
                            scaleX = liftScale
                            scaleY = liftScale
                            shadowElevation = if (isDragging) 12f else 0f
                        },
                dragHandleModifier =
                    Modifier.pointerInput(filter.id) {
                        detectDragGestures(
                            onDragStart = {
                                draggingId = filter.id
                                dragOffsets[filter.id] = 0f
                            },
                            onDragEnd = {
                                draggingId = null
                                dragOffsets.remove(filter.id)
                                val ids = localOrder.map { it.id }
                                pendingReorderIds = ids
                                onReorder(ids)
                            },
                            onDragCancel = {
                                draggingId = null
                                dragOffsets.remove(filter.id)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val accumulated = (dragOffsets[filter.id] ?: 0f) + dragAmount.y
                                val currentIndex = localOrder.indexOfFirst { it.id == filter.id }
                                if (currentIndex == -1 || rowHeightPx <= 0f) {
                                    dragOffsets[filter.id] = accumulated
                                    return@detectDragGestures
                                }
                                val (reordered, newIndex) =
                                    ReorderMath.reorder(localOrder, currentIndex, accumulated, rowHeightPx + gapPx)
                                dragOffsets[filter.id] =
                                    if (newIndex != currentIndex) {
                                        localOrder = reordered
                                        accumulated - (newIndex - currentIndex) * (rowHeightPx + gapPx)
                                    } else {
                                        accumulated
                                    }
                            },
                        )
                    },
            )
        }
        item {
            if (localOrder.isEmpty()) {
                Text("No filters yet. Forwarding stays off until at least one exists.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (!selectionMode) {
            item { Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text("Add filter") } }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (showDeleteConfirm) {
        val count = selectedIds.size
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(if (count == 1) "Delete this filter?" else "Delete $count filters?") },
            text = { Text("This can't be undone. Messages already forwarded are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDeleteMultiple(selectedIds.toList())
                    selectedIds = emptySet()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun FilterSummaryCard(
    filter: ForwardingFilterEntity,
    onClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDuplicate: () -> Unit,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelected: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier,
) {
    val recipients = remember(filter.recipientsJson) { PayloadCodec.pathsFromJson(filter.recipientsJson) }
    val types =
        listOfNotNull(
            "SMS".takeIf { filter.includeSms },
            "MMS".takeIf { filter.includeMms },
            "RCS".takeIf { filter.includeRcs },
            "Calls".takeIf { filter.includeCalls },
        )
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier
                // Long-press anywhere on the row enters selection mode and selects this filter in
                // one gesture; once in selection mode, a plain tap toggles selection instead of
                // opening the editor -- combinedClickable, not two separate Modifier.clickable/
                // pointerInput chains, is what keeps tap and long-press from fighting over the
                // same touch stream.
                .combinedClickable(
                    onClick = { if (selectionMode) onToggleSelected() else onClick() },
                    onLongClick = onLongPress,
                ).padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelected() },
                    modifier = Modifier.semantics { contentDescription = "Select ${filter.name}" },
                )
            } else {
                DragHandleGlyph(dragHandleModifier)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(filter.name, fontWeight = FontWeight.Bold)
                Text(
                    buildString {
                        append(if (types.isEmpty()) "No message types" else types.joinToString(" · "))
                        append(" → ")
                        append(if (recipients.isEmpty()) "no recipients — won't forward" else "${recipients.size} recipient(s)")
                        if (filter.conditionMode == FilterConditionMode.CONDITIONS) append(" · conditional")
                        if (filter.scheduleEnabled) append(" · scheduled")
                        if (filter.stopOnMatch) append(" · stops here")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (recipients.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!selectionMode) {
                Column(horizontalAlignment = Alignment.End) {
                    // A real, tappable switch -- not the read-only indicator this started as. Material3
                    // gives a disabled Switch uniformly muted colors regardless of checked state, which
                    // is exactly why the old read-only version looked the same on or off: "disabled"
                    // and "off" rendered almost identically. Tapping specifically on the switch's own
                    // touch target is handled here and never reaches the card's onClick underneath it --
                    // the standard, well-established behavior for a clickable nested inside another
                    // clickable in Compose -- so this doesn't reopen the "tap anywhere opens the editor"
                    // problem; it's what makes at-a-glance enable/disable possible at all.
                    Switch(
                        checked = filter.enabled,
                        onCheckedChange = onToggleEnabled,
                        modifier = Modifier.semantics { contentDescription = "${filter.name} enabled" },
                    )
                    TextButton(onClick = onDuplicate, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                        Text("Duplicate", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/** The Filters list's contextual header while one or more rows are selected -- replaces the
 *  usual instructional text above the list rather than living in the screen's TopAppBar, so
 *  selection state stays entirely local to this screen (matching how drag/reorder state already
 *  works here) instead of needing to be lifted to MainActivity's Scaffold. */
@Composable
private fun FilterSelectionBar(
    selectedCount: Int,
    totalCount: Int,
    onToggleSelectAll: () -> Unit,
    onCancel: () -> Unit,
    onDeleteRequested: () -> Unit,
) {
    // Two rows, not one -- "N selected", Select all/Deselect all, Cancel, and a colored Delete
    // button all on one row overflows a narrow phone's width once the count gets past one digit.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$selectedCount selected", fontWeight = FontWeight.Bold)
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onToggleSelectAll) {
                Text(if (selectedCount == totalCount) "Deselect all" else "Select all")
            }
            Button(
                onClick = onDeleteRequested,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("Delete") }
        }
    }
}

/** A six-dot grip, hand-drawn like the app's other glyphs rather than pulling in the Material
 *  icons library for one icon. Only this small touch target responds to the drag gesture, so it
 *  never competes with tapping the rest of the card to open the filter. */
@Composable
private fun DragHandleGlyph(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    androidx.compose.foundation.Canvas(
        modifier
            .size(24.dp)
            .semantics { contentDescription = "Drag to reorder" },
    ) {
        val dotRadius = 1.6.dp.toPx()
        val columnXs = listOf(size.width * 0.35f, size.width * 0.65f)
        val rowYs = listOf(size.height * 0.25f, size.height * 0.5f, size.height * 0.75f)
        columnXs.forEach { x ->
            rowYs.forEach { y ->
                drawCircle(color = color, radius = dotRadius, center = Offset(x, y))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterEditorScreen(
    filter: ForwardingFilterEntity,
    isValidEmail: (String) -> Boolean,
    onSave: (ForwardingFilterEntity) -> Unit,
    onDelete: () -> Unit,
) {
    var draft by remember(filter.id) { mutableStateOf(filter) }
    var newRecipient by rememberSaveable(filter.id) { mutableStateOf("") }
    var newContactNumber by rememberSaveable(filter.id) { mutableStateOf("") }
    var newKeyword by rememberSaveable(filter.id) { mutableStateOf("") }

    val recipients = remember(draft.recipientsJson) { PayloadCodec.pathsFromJson(draft.recipientsJson) }
    val contactNumbers = remember(draft.contactNumbersJson) { PayloadCodec.pathsFromJson(draft.contactNumbersJson) }
    val keywords = remember(draft.keywordsJson) { PayloadCodec.pathsFromJson(draft.keywordsJson) }
    val replaceRules = remember(draft.replaceRulesJson) { ReplaceRuleCodec.fromJson(draft.replaceRulesJson) }
    val listState = rememberLazyListState()
    listState.reportScrollActivity()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Spacer(Modifier.height(2.dp)) }

        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { draft = draft.copy(name = it) },
                        label = { Text("Filter name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Enabled", modifier = Modifier.weight(1f))
                        Switch(checked = draft.enabled, onCheckedChange = { draft = draft.copy(enabled = it) })
                    }
                }
            }
        }

        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Message types", fontWeight = FontWeight.Bold)
                    TypeCheckbox("SMS", draft.includeSms) { draft = draft.copy(includeSms = it) }
                    TypeCheckbox("MMS (picture/video messages)", draft.includeMms) { draft = draft.copy(includeMms = it) }
                    TypeCheckbox("RCS (Google/Samsung Messages chats)", draft.includeRcs) { draft = draft.copy(includeRcs = it) }
                    TypeCheckbox("Missed calls", draft.includeCalls) { draft = draft.copy(includeCalls = it) }
                }
            }
        }

        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Recipients", fontWeight = FontWeight.Bold)
                    if (recipients.isEmpty()) {
                        Text(
                            "Required — this filter cannot forward anything without at least one recipient.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    recipients.forEach { email ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(email, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                draft = draft.copy(recipientsJson = PayloadCodec.pathsToJson(recipients - email))
                            }) { Text("Remove") }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = newRecipient,
                            onValueChange = { newRecipient = it },
                            label = { Text("Add email") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = {
                            val clean = newRecipient.trim()
                            if (isValidEmail(clean) && clean !in recipients) {
                                draft = draft.copy(recipientsJson = PayloadCodec.pathsToJson(recipients + clean))
                                newRecipient = ""
                            }
                        }) { Text("Add") }
                    }
                }
            }
        }

        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Forwarding conditions", fontWeight = FontWeight.Bold)
                    // Modifier.height(IntrinsicSize.Max) on the row plus Modifier.fillMaxHeight()
                    // on each segment is what keeps every segment the same height as its tallest
                    // sibling. Without it, Material3's SegmentedButtonRow only equalizes width --
                    // each segment's own pill still wraps tight to its own content height, so a
                    // one-line label ("Off") renders visibly shorter than a label sharing the row
                    // that wraps to two lines at large text sizes ("Must not contain"), which is
                    // exactly the unevenness reported.
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Max)) {
                        listOf(FilterConditionMode.ALL to "Forward all", FilterConditionMode.CONDITIONS to "By conditions").forEachIndexed { index, (value, label) ->
                            SegmentedButton(
                                selected = draft.conditionMode == value,
                                onClick = { draft = draft.copy(conditionMode = value) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                                modifier = Modifier.fillMaxHeight(),
                                label = { Text(label) },
                            )
                        }
                    }
                    if (draft.conditionMode == FilterConditionMode.CONDITIONS) {
                        Text("Sender", style = MaterialTheme.typography.labelLarge)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Max)) {
                            listOf(ContactFilterMode.OFF to "Off", ContactFilterMode.WHITELIST to "Allow list", ContactFilterMode.BLACKLIST to "Block list")
                                .forEachIndexed { index, (value, label) ->
                                    SegmentedButton(
                                        selected = draft.contactMode == value,
                                        onClick = { draft = draft.copy(contactMode = value) },
                                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                                        modifier = Modifier.fillMaxHeight(),
                                        label = { Text(label) },
                                    )
                                }
                        }
                        if (draft.contactMode != ContactFilterMode.OFF) {
                            ChipEditor(
                                items = contactNumbers,
                                newValue = newContactNumber,
                                onNewValueChange = { newContactNumber = it },
                                keyboardType = KeyboardType.Phone,
                                placeholder = "Add phone number",
                                onAdd = {
                                    val clean = newContactNumber.trim()
                                    if (clean.isNotEmpty() && clean !in contactNumbers) {
                                        draft = draft.copy(contactNumbersJson = PayloadCodec.pathsToJson(contactNumbers + clean))
                                        newContactNumber = ""
                                    }
                                },
                                onRemove = { value -> draft = draft.copy(contactNumbersJson = PayloadCodec.pathsToJson(contactNumbers - value)) },
                            )
                        }
                        HorizontalDivider()
                        Text("Keywords in message text", style = MaterialTheme.typography.labelLarge)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Max)) {
                            listOf(KeywordFilterMode.OFF to "Off", KeywordFilterMode.MUST_CONTAIN to "Must contain", KeywordFilterMode.MUST_NOT_CONTAIN to "Must not contain")
                                .forEachIndexed { index, (value, label) ->
                                    SegmentedButton(
                                        selected = draft.keywordMode == value,
                                        onClick = { draft = draft.copy(keywordMode = value) },
                                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                                        modifier = Modifier.fillMaxHeight(),
                                        label = { Text(label) },
                                    )
                                }
                        }
                        if (draft.keywordMode != KeywordFilterMode.OFF) {
                            ChipEditor(
                                items = keywords,
                                newValue = newKeyword,
                                onNewValueChange = { newKeyword = it },
                                keyboardType = KeyboardType.Text,
                                placeholder = "Add keyword",
                                onAdd = {
                                    val clean = newKeyword.trim()
                                    if (clean.isNotEmpty() && clean !in keywords) {
                                        draft = draft.copy(keywordsJson = PayloadCodec.pathsToJson(keywords + clean))
                                        newKeyword = ""
                                    }
                                },
                                onRemove = { value -> draft = draft.copy(keywordsJson = PayloadCodec.pathsToJson(keywords - value)) },
                            )
                        }
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Always allow OTP & security codes")
                                Text(
                                    "Lets a message that looks like a one-time code through even if it would otherwise be blocked above.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Switch(checked = draft.alwaysAllowOtp, onCheckedChange = { draft = draft.copy(alwaysAllowOtp = it) })
                        }
                    }
                }
            }
        }

        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Message template", fontWeight = FontWeight.Bold)
                    Text(
                        "Placeholders: {Incoming Number} {Contact Name} {Message Body} {Received Time} {Source} {Reply Tag} {Verb}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = draft.subjectTemplate,
                        onValueChange = { draft = draft.copy(subjectTemplate = it) },
                        label = { Text("Email subject") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.bodyTemplate,
                        onValueChange = { draft = draft.copy(bodyTemplate = it) },
                        label = { Text("Email body") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TemplatePreview(draft.subjectTemplate, draft.bodyTemplate, replaceRules)
                    HorizontalDivider()
                    Text("Find and replace", style = MaterialTheme.typography.labelLarge)
                    replaceRules.forEachIndexed { index, rule ->
                        ReplaceRuleRow(
                            rule = rule,
                            onChange = { updated ->
                                draft = draft.copy(replaceRulesJson = ReplaceRuleCodec.toJson(replaceRules.toMutableList().also { it[index] = updated }))
                            },
                            onRemove = {
                                draft = draft.copy(replaceRulesJson = ReplaceRuleCodec.toJson(replaceRules.filterIndexed { i, _ -> i != index }))
                            },
                        )
                    }
                    OutlinedButton(
                        onClick = { draft = draft.copy(replaceRulesJson = ReplaceRuleCodec.toJson(replaceRules + ReplaceRule("", ""))) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Add rule") }
                }
            }
        }

        item { ScheduleCard(draft, onChange = { draft = it }) }

        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Save to history", modifier = Modifier.weight(1f))
                        Switch(checked = draft.saveResults, onCheckedChange = { draft = draft.copy(saveResults = it) })
                    }
                    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Notify me on each send", modifier = Modifier.weight(1f))
                        Switch(checked = draft.sendResultNotifications, onCheckedChange = { draft = draft.copy(sendResultNotifications = it) })
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Stop processing further filters")
                            Text(
                                "When this filter matches and forwards a message, skip every filter below it " +
                                    "(by the order on the Filters screen) for that same message.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = draft.stopOnMatch, onCheckedChange = { draft = draft.copy(stopOnMatch = it) })
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (recipients.isEmpty()) {
                    Text(
                        "Add a recipient email above before saving.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = { onSave(draft) },
                    enabled = recipients.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save filter") }
                OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Delete filter") }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun TypeCheckbox(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChipEditor(
    items: List<String>,
    newValue: String,
    onNewValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    placeholder: String,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (items.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEach { value ->
                    FilterChip(selected = true, onClick = { onRemove(value) }, label = { Text(value) })
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = newValue,
                onValueChange = onNewValueChange,
                label = { Text(placeholder) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onAdd) { Text("Add") }
        }
    }
}

/** Sample stand-in for [com.scifsidekick.cleanroom.util.MessageVariables.forMessage] -- same
 *  placeholder keys, fictional values, so the preview below renders through the exact same
 *  [MessageTemplateEngine] and [ReplaceRuleEngine] the real send path uses, and never drifts out
 *  of sync with what a real message actually looks like once forwarded. */
private fun sampleTemplateVars(): Map<String, String> =
    mapOf(
        "Reply Tag" to "SCIF:+15551234567",
        "Verb" to "New SMS",
        "Contact Name" to "Jane Doe",
        "Incoming Number" to "+15551234567",
        "Message Body" to "Hey, running about 10 minutes late — see you soon!",
        "Received Time" to SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date()),
        "Source" to "SMS",
    )

@Composable
private fun TemplatePreview(
    subjectTemplate: String,
    bodyTemplate: String,
    replaceRules: List<ReplaceRule>,
) {
    val vars = remember { sampleTemplateVars() }
    val subject = remember(subjectTemplate) { MessageTemplateEngine.render(subjectTemplate, vars) }
    val body =
        remember(bodyTemplate, replaceRules) {
            ReplaceRuleEngine.apply(MessageTemplateEngine.render(bodyTemplate, vars), replaceRules)
        }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("PREVIEW (using a sample message)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(subject, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ReplaceRuleRow(
    rule: ReplaceRule,
    onChange: (ReplaceRule) -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = rule.find,
                onValueChange = { onChange(rule.copy(find = it)) },
                label = { Text("Find") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = rule.replace,
                onValueChange = { onChange(rule.copy(replace = it)) },
                label = { Text("Replace with") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = rule.useRegex, onCheckedChange = { onChange(rule.copy(useRegex = it)) })
                Text("Use regular expression", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onRemove) { Text("Remove") }
        }
        HorizontalDivider()
    }
}

private val dayLabels = listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleCard(
    filter: ForwardingFilterEntity,
    onChange: (ForwardingFilterEntity) -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Only forward during scheduled hours", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Switch(checked = filter.scheduleEnabled, onCheckedChange = { onChange(filter.copy(scheduleEnabled = it)) })
            }
            if (filter.scheduleEnabled) {
                // One row of seven equal segments, so the week always reads as a single control
                // instead of wrapping to a stray "Sa" on a second line. The check icon is dropped
                // because it would crowd the two-letter labels; the filled segment is the state.
                MultiChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    dayLabels.forEachIndexed { index, label ->
                        val bit = 1 shl index
                        val active = (filter.scheduleDaysMask and bit) != 0
                        SegmentedButton(
                            checked = active,
                            onCheckedChange = {
                                val next = if (active) filter.scheduleDaysMask and bit.inv() else filter.scheduleDaysMask or bit
                                onChange(filter.copy(scheduleDaysMask = next))
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = dayLabels.size),
                            icon = {},
                            contentPadding = PaddingValues(horizontal = 0.dp),
                            label = { Text(label, maxLines = 1) },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeField(
                        label = "Start (HH:MM)",
                        minute = filter.scheduleStartMinute,
                        onChange = { onChange(filter.copy(scheduleStartMinute = it)) },
                        modifier = Modifier.weight(1f),
                    )
                    TimeField(
                        label = "End (HH:MM)",
                        minute = filter.scheduleEndMinute,
                        onChange = { onChange(filter.copy(scheduleEndMinute = it)) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "An end time earlier than the start time spans overnight (e.g. 22:00 to 06:00).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TimeField(
    label: String,
    minute: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable(minute) { mutableStateOf("%02d:%02d".format(minute / 60, minute % 60)) }
    OutlinedTextField(
        value = text,
        onValueChange = { value ->
            text = value
            val match = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$").find(value.trim())
            if (match != null) {
                val (h, m) = match.destructured
                onChange(h.toInt() * 60 + m.toInt())
            }
        },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
    )
}
