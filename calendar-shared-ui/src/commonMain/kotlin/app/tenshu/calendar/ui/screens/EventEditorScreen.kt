package app.tenshu.calendar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.calendar.data.EditScope
import app.tenshu.calendar.model.CalendarDateFormat
import app.tenshu.calendar.model.RecurrencePreset
import app.tenshu.calendar.model.label
import app.tenshu.calendar.model.ReminderPresets
import app.tenshu.calendar.ui.EventEditorUiState
import app.tenshu.calendar.ui.ConflictIntent
import app.tenshu.calendar.ui.EditorConflict
import app.tenshu.calendar.ui.EventEditorViewModel
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * The event editor (CA-3).
 *
 * ⚠ Deliberately a plain scrolling form over Material 3's own
 * `DatePicker`/`TimePicker` rather than a bespoke wheel: §5.1's whole point is
 * that the pickers, not the calendar grid, are where a hand-rolled UI would cost
 * the most and look the least native.
 *
 * ⚠⚠ The two dialogs it *does* own — the this-vs-all prompts — are the screen's
 * real content. Everything above them is fields.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditorScreen(
    viewModel: EventEditorViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // ⚠⚠ Closes on the COMMIT, never on the tap — a refused save (no calendar,
    // proxy unreachable) would otherwise look exactly like a successful one:
    // the screen closes and nothing was written. The `RenameFileDialog`
    // precedent, which learned this the hard way.
    LaunchedEffect(state.saved, state.deleted) {
        if (state.saved || state.deleted) onClose()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(if (state.isEditing) "Edit event" else "New event") },
                navigationIcon = {
                    IconButton(onClose) { Icon(Icons.Default.Close, contentDescription = "Cancel") }
                },
                actions = {
                    if (state.isEditing && !state.isReadOnly) {
                        IconButton(viewModel::delete) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    }
                    if (!state.isReadOnly) {
                        TextButton(onClick = viewModel::save, enabled = state.canSave) { Text("Save") }
                    }
                },
            )
        },
    ) { padding ->
        EditorForm(
            state = state,
            viewModel = viewModel,
            modifier = Modifier.padding(padding),
        )
    }

    if (state.askingScope) {
        ScopeDialog(
            title = "Change repeating event",
            message = "This event repeats. Apply your changes to this occurrence only, or to every occurrence?",
            onDismiss = viewModel::dismissScopePrompt,
            onScope = viewModel::commit,
        )
    }

    state.conflict?.let { conflict ->
        ConflictDialog(conflict, viewModel)
    }

    if (state.askingDeleteScope) {
        ScopeDialog(
            title = "Delete repeating event",
            message = "This event repeats. Delete this occurrence only, or every occurrence?",
            confirmLabel = "Delete",
            onDismiss = viewModel::dismissScopePrompt,
            onScope = viewModel::commitDelete,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EditorForm(
    state: EventEditorUiState,
    viewModel: EventEditorViewModel,
    modifier: Modifier = Modifier,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = state.title,
            onValueChange = viewModel::setTitle,
            label = { Text("Title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // --- when ---
        AssistChip(
            onClick = { showDatePicker = true },
            label = { Text(state.startDate?.let(CalendarDateFormat::mediumDate) ?: "Pick a date") },
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("All day", Modifier.weight(1f))
            Switch(checked = state.allDay, onCheckedChange = viewModel::setAllDay)
        }

        // ⚠ Times are hidden, not disabled, for an all-day event: a greyed-out
        // control invites people to try to enable it.
        if (!state.allDay) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { showStartTimePicker = true },
                    label = { Text("Starts ${CalendarDateFormat.shortTime(state.startTime)}") },
                )
                AssistChip(
                    onClick = { showEndTimePicker = true },
                    label = { Text("Ends ${CalendarDateFormat.shortTime(state.endTime)}") },
                )
            }
            if (state.endBeforeStart) {
                Text(
                    "The event ends before it starts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // --- repeat ---
        RecurrenceField(state, viewModel::setRecurrence)

        // --- reminders ---
        ReminderField(state, viewModel::toggleReminder)

        // --- where it lives ---
        CalendarField(state, viewModel::setCalendar)

        OutlinedTextField(
            value = state.location,
            onValueChange = viewModel::setLocation,
            label = { Text("Location") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.notes,
            onValueChange = viewModel::setNotes,
            label = { Text("Notes") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        state.errorMessage?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
        }
        if (state.isReadOnly) {
            Text(
                READ_ONLY_NOTICE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showDatePicker) {
        DatePickerSheet(
            initial = state.startDate,
            onDismiss = { showDatePicker = false },
            onPick = {
                viewModel.setStartDate(it)
                showDatePicker = false
            },
        )
    }
    if (showStartTimePicker) {
        TimePickerSheet(
            initial = state.startTime,
            onDismiss = { showStartTimePicker = false },
            onPick = {
                viewModel.setStartTime(it)
                showStartTimePicker = false
            },
        )
    }
    if (showEndTimePicker) {
        TimePickerSheet(
            initial = state.endTime,
            onDismiss = { showEndTimePicker = false },
            onPick = {
                viewModel.setEndTime(it)
                showEndTimePicker = false
            },
        )
    }
}

@Composable
private fun RecurrenceField(state: EventEditorUiState, onPick: (RecurrencePreset) -> Unit) {
    Column {
        Text("Repeats", style = MaterialTheme.typography.labelLarge)

        if (state.recurrenceIsReadOnly) {
            // ⚠⚠ A rule we can show but not offer. Rendering a chip row here
            // would mean whichever chip we highlighted was a lie, and touching
            // it would rewrite a rule the user set elsewhere — §3 D2.
            Text(
                "Custom — set in another app. Editing it here isn't supported yet, " +
                    "and your other changes won't affect it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            return@Column
        }

        // ⚠ Wraps, as Reminders does: five chips do not fit a phone's width, and a plain Row
        // squeezed "Yearly" onto two lines (EventEditorPhoneWidthTest).
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OFFERABLE.forEach { preset ->
                FilterChip(
                    selected = state.recurrence == preset,
                    onClick = { onPick(preset) },
                    label = { Text(preset.label()) },
                )
            }
        }
    }
}

/**
 * The reminder offsets, as toggles.
 *
 * ⚠⚠ The offered list switches with the all-day flag, because the two are not
 * the same question: an all-day event starts at 00:00, so a timed "10 minutes
 * before" would fire at 23:50 the night before. ⚠ Anything already chosen is
 * shown even if it is not one of the offers — an offset set on another device or
 * by Mia must be visible and removable here, not silently dropped.
 */
@Composable
private fun ReminderField(state: EventEditorUiState, onToggle: (Int) -> Unit) {
    val offered = ReminderPresets.forEvent(state.allDay)
    val choices = (offered + state.reminderMinutes).distinct().sorted()

    Column {
        Text("Reminders", style = MaterialTheme.typography.labelLarge)
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            choices.forEach { minutes ->
                FilterChip(
                    selected = minutes in state.reminderMinutes,
                    onClick = { onToggle(minutes) },
                    label = { Text(ReminderPresets.label(minutes, state.allDay)) },
                )
            }
        }
        if (state.reminderMinutes.isEmpty()) {
            Text(
                "No reminders — this event will not alert you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun CalendarField(state: EventEditorUiState, onPick: (String) -> Unit) {
    // ⚠ Only writable calendars, and none at all for an event that lives in a read-only one.
    if (state.isReadOnly || state.writableCalendars.size <= 1) return   // nothing to choose between

    Column {
        Text("Calendar", style = MaterialTheme.typography.labelLarge)
        // ⚠⚠ Wraps. As a plain Row, five calendars on a phone crushed the last chip to nothing, so
        // that calendar could not be chosen for a new event at all (EventEditorPhoneWidthTest).
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.writableCalendars.forEach { calendar ->
                FilterChip(
                    selected = state.calendarId == calendar.calendarId,
                    onClick = { onPick(calendar.calendarId) },
                    leadingIcon = {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(calendar.colorArgb.toLong() or 0xFF000000L))
                        )
                    },
                    label = { Text(calendar.name) },
                )
            }
        }
    }
}

@Composable
private fun ScopeDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onScope: (EditScope) -> Unit,
    confirmLabel: String = "Save",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        // ⚠ Both answers are affirmative, so neither sits in the "dismiss"
        // slot — a scope prompt whose second option reads as Cancel is one
        // people answer by reflex rather than by reading.
        confirmButton = {
            TextButton(onClick = { onScope(EditScope.THIS_OCCURRENCE) }) {
                // "occurrence", as the message above and the iOS dialog say: "this event" read
                // as the whole series, which is exactly the ambiguity the prompt exists to settle.
                Text("$confirmLabel this occurrence")
            }
        },
        dismissButton = {
            TextButton(onClick = { onScope(EditScope.ALL_OCCURRENCES) }) {
                Text("$confirmLabel all occurrences")
            }
        },
    )
}

/**
 * Someone else changed this event while it was open (CA-2k).
 *
 * ⚠⚠ **Nothing has been written when this appears.** The save stopped at the check, so
 * every button here is the *first* write — which is why none of them is styled as a
 * mere acknowledgement and why dismissing leaves the form exactly as the user left it,
 * unsaved.
 *
 * ⚠ Three answers stacked rather than the two-slot `AlertDialog` shape `ScopeDialog`
 * uses: they are not a confirm/cancel pair, they are three different outcomes for the
 * other person's edit, and the least reversible of them (overwriting it) must not be
 * the one that sits where people tap by reflex.
 */
@Composable
private fun ConflictDialog(conflict: EditorConflict, viewModel: EventEditorViewModel) {
    val theirs = conflict.theirs
    val deleting = conflict.intent == ConflictIntent.DELETE

    AlertDialog(
        onDismissRequest = viewModel::dismissConflict,
        title = { Text(if (theirs == null) "This event is gone" else "Changed somewhere else") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    when {
                        // ⚠ "No longer where you opened it" rather than "deleted": it may
                        // have been moved to another day, and this layer cannot tell the
                        // two apart. Claiming the wrong one is worse than naming neither.
                        theirs == null ->
                            "This event is no longer where you opened it — another device " +
                                "deleted or moved it. Saving will put your version back."
                        deleting ->
                            "Another device changed this event while you were deleting it. " +
                                "Deleting now throws that change away too."
                        else -> "Another device changed this event while you were editing it."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                theirs?.let {
                    Text(
                        // ⚠ Their version is *named*, not just referred to. "Something
                        // changed" leaves the user choosing between two things they
                        // cannot see; a title and a time is usually enough to recognise.
                        "Theirs: ${it.title.ifBlank { "(No title)" }} · " +
                            CalendarDateFormat.shortTime(it.startLocal.time),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column {
                    TextButton(onClick = viewModel::keepMine) {
                        Text(
                            when {
                                deleting -> "Delete anyway"
                                theirs == null -> "Save mine anyway"
                                else -> "Keep mine"
                            }
                        )
                    }
                    if (theirs != null) {
                        // ⚠ For a delete this is what *cancels* it — the event stays and
                        // the form shows what the other device made of it, which is the
                        // only way to decide whether you still want it gone.
                        TextButton(onClick = viewModel::useTheirs) {
                            Text(if (deleting) "Keep theirs" else "Use theirs")
                        }
                        // ⚠ Not offered for a delete: "both" of a deletion and an edit is
                        // just the edit, which is what *Keep theirs* already does.
                        if (!deleting) {
                            TextButton(onClick = viewModel::saveBoth) { Text("Keep both") }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        // ⚠ The only slot that writes nothing, and it says so — "Cancel" over a form
        // still full of the user's unsaved edits reads as "throw them away".
        dismissButton = {
            TextButton(onClick = viewModel::dismissConflict) { Text("Keep editing") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerSheet(
    initial: LocalDate?,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit,
) {
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initial?.let { it.toEpochDays() * MILLIS_PER_DAY },
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    // ⚠⚠ The picker speaks UTC millis; a civil date has to come
                    // back out by whole days, never through a local-zone
                    // conversion — that is the classic off-by-one that makes a
                    // date picked at 23:00 land on the following day.
                    pickerState.selectedDateMillis
                        ?.let { LocalDate.fromEpochDays((it / MILLIS_PER_DAY).toInt()) }
                        ?.let(onPick)
                },
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(pickerState)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerSheet(
    initial: LocalTime,
    onDismiss: () -> Unit,
    onPick: (LocalTime) -> Unit,
) {
    val pickerState = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        // ⚠ Locale data, and it has to agree with the labels the chips render —
        // both come from the same place.
        is24Hour = CalendarDateFormat.uses24HourClock(),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onPick(LocalTime(pickerState.hour, pickerState.minute)) }) {
                Text("OK")
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
        text = { TimePicker(pickerState) },
    )
}

private const val MILLIS_PER_DAY = 86_400_000L

// ⚠ Both now live on `RecurrencePreset` itself (CA-8d) — the SwiftUI editor needs the same list
// and the same words, and two copies of user-visible copy is how "Yearly" becomes "Annually" on
// one platform.
private val OFFERABLE = RecurrencePreset.OFFERABLE

/** Shown under an event in a subscribed or mirrored calendar ([EventEditorUiState.isReadOnly]). */
internal const val READ_ONLY_NOTICE =
    "This calendar is updated from elsewhere, so its events can't be changed here."
