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
import app.tenshu.calendar.model.ReminderLabel
import app.tenshu.calendar.shared.resources.Res
import app.tenshu.calendar.shared.resources.calendar_all_day
import app.tenshu.calendar.shared.resources.calendar_calendar
import app.tenshu.calendar.shared.resources.calendar_cancel
import app.tenshu.calendar.shared.resources.calendar_change_repeating_message
import app.tenshu.calendar.shared.resources.calendar_change_repeating_title
import app.tenshu.calendar.shared.resources.calendar_conflict_changed_title
import app.tenshu.calendar.shared.resources.calendar_conflict_delete_anyway
import app.tenshu.calendar.shared.resources.calendar_conflict_deleting_message
import app.tenshu.calendar.shared.resources.calendar_conflict_editing_message
import app.tenshu.calendar.shared.resources.calendar_conflict_gone_message
import app.tenshu.calendar.shared.resources.calendar_conflict_gone_title
import app.tenshu.calendar.shared.resources.calendar_conflict_keep_both
import app.tenshu.calendar.shared.resources.calendar_conflict_keep_editing
import app.tenshu.calendar.shared.resources.calendar_conflict_keep_mine
import app.tenshu.calendar.shared.resources.calendar_conflict_keep_theirs
import app.tenshu.calendar.shared.resources.calendar_conflict_save_mine_anyway
import app.tenshu.calendar.shared.resources.calendar_conflict_theirs
import app.tenshu.calendar.shared.resources.calendar_conflict_use_theirs
import app.tenshu.calendar.shared.resources.calendar_delete
import app.tenshu.calendar.shared.resources.calendar_delete_all_occurrences
import app.tenshu.calendar.shared.resources.calendar_delete_repeating_message
import app.tenshu.calendar.shared.resources.calendar_delete_repeating_title
import app.tenshu.calendar.shared.resources.calendar_delete_this_occurrence
import app.tenshu.calendar.shared.resources.calendar_edit_event
import app.tenshu.calendar.shared.resources.calendar_ends
import app.tenshu.calendar.shared.resources.calendar_ends_before_starts
import app.tenshu.calendar.shared.resources.calendar_location
import app.tenshu.calendar.shared.resources.calendar_new_event
import app.tenshu.calendar.shared.resources.calendar_no_reminders
import app.tenshu.calendar.shared.resources.calendar_notes
import app.tenshu.calendar.shared.resources.calendar_ok
import app.tenshu.calendar.shared.resources.calendar_pick_a_date
import app.tenshu.calendar.shared.resources.calendar_read_only_notice
import app.tenshu.calendar.shared.resources.calendar_recurrence_custom
import app.tenshu.calendar.shared.resources.calendar_recurrence_custom_readonly
import app.tenshu.calendar.shared.resources.calendar_recurrence_daily
import app.tenshu.calendar.shared.resources.calendar_recurrence_monthly
import app.tenshu.calendar.shared.resources.calendar_recurrence_never
import app.tenshu.calendar.shared.resources.calendar_recurrence_weekly
import app.tenshu.calendar.shared.resources.calendar_recurrence_yearly
import app.tenshu.calendar.shared.resources.calendar_reminder_at_start
import app.tenshu.calendar.shared.resources.calendar_reminder_day_before_at
import app.tenshu.calendar.shared.resources.calendar_reminder_days_before_at
import app.tenshu.calendar.shared.resources.calendar_reminder_days_before_one
import app.tenshu.calendar.shared.resources.calendar_reminder_days_before_other
import app.tenshu.calendar.shared.resources.calendar_reminder_hours_before_one
import app.tenshu.calendar.shared.resources.calendar_reminder_hours_before_other
import app.tenshu.calendar.shared.resources.calendar_reminder_minutes_before
import app.tenshu.calendar.shared.resources.calendar_reminder_on_the_day_at
import app.tenshu.calendar.shared.resources.calendar_reminders
import app.tenshu.calendar.shared.resources.calendar_repeats
import app.tenshu.calendar.shared.resources.calendar_save
import app.tenshu.calendar.shared.resources.calendar_save_all_occurrences
import app.tenshu.calendar.shared.resources.calendar_save_this_occurrence
import app.tenshu.calendar.shared.resources.calendar_starts
import app.tenshu.calendar.shared.resources.calendar_title
import app.tenshu.calendar.shared.resources.calendar_untitled_event
import app.tenshu.calendar.ui.i18n.countOf
import org.jetbrains.compose.resources.stringResource
import app.tenshu.calendar.ui.i18n.text

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
                title = { Text(if (state.isEditing) stringResource(Res.string.calendar_edit_event) else stringResource(Res.string.calendar_new_event)) },
                navigationIcon = {
                    IconButton(onClose) { Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.calendar_cancel)) }
                },
                actions = {
                    if (state.isEditing && !state.isReadOnly) {
                        IconButton(viewModel::delete) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(Res.string.calendar_delete))
                        }
                    }
                    if (!state.isReadOnly) {
                        TextButton(onClick = viewModel::save, enabled = state.canSave) { Text(stringResource(Res.string.calendar_save)) }
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
            title = stringResource(Res.string.calendar_change_repeating_title),
            message = stringResource(Res.string.calendar_change_repeating_message),
            thisLabel = stringResource(Res.string.calendar_save_this_occurrence),
            allLabel = stringResource(Res.string.calendar_save_all_occurrences),
            onDismiss = viewModel::dismissScopePrompt,
            onScope = viewModel::commit,
        )
    }

    state.conflict?.let { conflict ->
        ConflictDialog(conflict, viewModel)
    }

    if (state.askingDeleteScope) {
        ScopeDialog(
            title = stringResource(Res.string.calendar_delete_repeating_title),
            message = stringResource(Res.string.calendar_delete_repeating_message),
            thisLabel = stringResource(Res.string.calendar_delete_this_occurrence),
            allLabel = stringResource(Res.string.calendar_delete_all_occurrences),
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
            label = { Text(stringResource(Res.string.calendar_title)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // --- when ---
        AssistChip(
            onClick = { showDatePicker = true },
            label = { Text(state.startDate?.let(CalendarDateFormat::mediumDate) ?: stringResource(Res.string.calendar_pick_a_date)) },
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.calendar_all_day), Modifier.weight(1f))
            Switch(checked = state.allDay, onCheckedChange = viewModel::setAllDay)
        }

        // ⚠ Times are hidden, not disabled, for an all-day event: a greyed-out
        // control invites people to try to enable it.
        if (!state.allDay) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { showStartTimePicker = true },
                    label = { Text(stringResource(Res.string.calendar_starts, CalendarDateFormat.shortTime(state.startTime))) },
                )
                AssistChip(
                    onClick = { showEndTimePicker = true },
                    label = { Text(stringResource(Res.string.calendar_ends, CalendarDateFormat.shortTime(state.endTime))) },
                )
            }
            if (state.endBeforeStart) {
                Text(
                    stringResource(Res.string.calendar_ends_before_starts),
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
            label = { Text(stringResource(Res.string.calendar_location)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.notes,
            onValueChange = viewModel::setNotes,
            label = { Text(stringResource(Res.string.calendar_notes)) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        state.problem?.let { problem ->
            Text(problem.text(), color = MaterialTheme.colorScheme.error)
        }
        if (state.isReadOnly) {
            Text(
                stringResource(Res.string.calendar_read_only_notice),
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
        Text(stringResource(Res.string.calendar_repeats), style = MaterialTheme.typography.labelLarge)

        if (state.recurrenceIsReadOnly) {
            // ⚠⚠ A rule we can show but not offer. Rendering a chip row here
            // would mean whichever chip we highlighted was a lie, and touching
            // it would rewrite a rule the user set elsewhere — §3 D2.
            Text(
                stringResource(Res.string.calendar_recurrence_custom_readonly),
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
                    label = { Text(preset.localisedLabel()) },
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
        Text(stringResource(Res.string.calendar_reminders), style = MaterialTheme.typography.labelLarge)
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            choices.forEach { minutes ->
                FilterChip(
                    selected = minutes in state.reminderMinutes,
                    onClick = { onToggle(minutes) },
                    label = { Text(reminderLabel(ReminderPresets.describe(minutes, state.allDay))) },
                )
            }
        }
        if (state.reminderMinutes.isEmpty()) {
            Text(
                stringResource(Res.string.calendar_no_reminders),
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
        Text(stringResource(Res.string.calendar_calendar), style = MaterialTheme.typography.labelLarge)
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
    thisLabel: String,
    allLabel: String,
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
                Text(thisLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = { onScope(EditScope.ALL_OCCURRENCES) }) {
                Text(allLabel)
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
        title = { Text(if (theirs == null) stringResource(Res.string.calendar_conflict_gone_title) else stringResource(Res.string.calendar_conflict_changed_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    when {
                        // ⚠ "No longer where you opened it" rather than "deleted": it may
                        // have been moved to another day, and this layer cannot tell the
                        // two apart. Claiming the wrong one is worse than naming neither.
                        theirs == null ->
                            stringResource(Res.string.calendar_conflict_gone_message)
                        deleting ->
                            stringResource(Res.string.calendar_conflict_deleting_message)
                        else -> stringResource(Res.string.calendar_conflict_editing_message)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                theirs?.let {
                    Text(
                        // ⚠ Their version is *named*, not just referred to. "Something
                        // changed" leaves the user choosing between two things they
                        // cannot see; a title and a time is usually enough to recognise.
                        stringResource(
                            Res.string.calendar_conflict_theirs,
                            it.title.ifBlank { stringResource(Res.string.calendar_untitled_event) },
                            CalendarDateFormat.shortTime(it.startLocal.time),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column {
                    TextButton(onClick = viewModel::keepMine) {
                        Text(
                            when {
                                deleting -> stringResource(Res.string.calendar_conflict_delete_anyway)
                                theirs == null -> stringResource(Res.string.calendar_conflict_save_mine_anyway)
                                else -> stringResource(Res.string.calendar_conflict_keep_mine)
                            }
                        )
                    }
                    if (theirs != null) {
                        // ⚠ For a delete this is what *cancels* it — the event stays and
                        // the form shows what the other device made of it, which is the
                        // only way to decide whether you still want it gone.
                        TextButton(onClick = viewModel::useTheirs) {
                            Text(if (deleting) stringResource(Res.string.calendar_conflict_keep_theirs) else stringResource(Res.string.calendar_conflict_use_theirs))
                        }
                        // ⚠ Not offered for a delete: "both" of a deletion and an edit is
                        // just the edit, which is what *Keep theirs* already does.
                        if (!deleting) {
                            TextButton(onClick = viewModel::saveBoth) { Text(stringResource(Res.string.calendar_conflict_keep_both)) }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        // ⚠ The only slot that writes nothing, and it says so — "Cancel" over a form
        // still full of the user's unsaved edits reads as "throw them away".
        dismissButton = {
            TextButton(onClick = viewModel::dismissConflict) { Text(stringResource(Res.string.calendar_conflict_keep_editing)) }
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
            ) { Text(stringResource(Res.string.calendar_ok)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(Res.string.calendar_cancel)) } },
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
                Text(stringResource(Res.string.calendar_ok))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(Res.string.calendar_cancel)) } },
        text = { TimePicker(pickerState) },
    )
}

private const val MILLIS_PER_DAY = 86_400_000L

// ⚠ Both now live on `RecurrencePreset` itself (CA-8d) — the SwiftUI editor needs the same list
// and the same words, and two copies of user-visible copy is how "Yearly" becomes "Annually" on
// one platform.
private val OFFERABLE = RecurrencePreset.OFFERABLE

/**
 * What a picker calls [this] preset, in the app's language. ⚠ The meanings follow
 * `RecurrencePreset.label()`, whose English the SwiftUI editor shows.
 */
@Composable
private fun RecurrencePreset.localisedLabel(): String = stringResource(
    when (this) {
        RecurrencePreset.NEVER -> Res.string.calendar_recurrence_never
        RecurrencePreset.DAILY -> Res.string.calendar_recurrence_daily
        RecurrencePreset.WEEKLY -> Res.string.calendar_recurrence_weekly
        RecurrencePreset.MONTHLY -> Res.string.calendar_recurrence_monthly
        RecurrencePreset.YEARLY -> Res.string.calendar_recurrence_yearly
        RecurrencePreset.CUSTOM -> Res.string.calendar_recurrence_custom
    },
)

/** A reminder chip, worded from [ReminderPresets.describe]'s parts rather than its English. */
@Composable
private fun reminderLabel(label: ReminderLabel): String = when (label) {
    ReminderLabel.AtStart -> stringResource(Res.string.calendar_reminder_at_start)
    is ReminderLabel.DaysBefore -> countOf(
        label.days,
        Res.string.calendar_reminder_days_before_one,
        Res.string.calendar_reminder_days_before_other,
    )
    is ReminderLabel.HoursBefore -> countOf(
        label.hours,
        Res.string.calendar_reminder_hours_before_one,
        Res.string.calendar_reminder_hours_before_other,
    )
    is ReminderLabel.MinutesBefore -> stringResource(Res.string.calendar_reminder_minutes_before, label.minutes)
    is ReminderLabel.OnTheDayAt -> stringResource(Res.string.calendar_reminder_on_the_day_at, label.time)
    is ReminderLabel.DayBeforeAt -> stringResource(Res.string.calendar_reminder_day_before_at, label.time)
    is ReminderLabel.DaysBeforeAt ->
        stringResource(Res.string.calendar_reminder_days_before_at, label.days, label.time)
}
