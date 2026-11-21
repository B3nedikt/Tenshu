package app.tenshu.calendar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.calendar.data.CalendarSummary
import app.tenshu.calendar.data.IcsImportResult
import app.tenshu.calendar.shared.resources.Res
import app.tenshu.calendar.shared.resources.calendar_cancel
import app.tenshu.calendar.shared.resources.calendar_do_something_else
import app.tenshu.calendar.shared.resources.calendar_done
import app.tenshu.calendar.shared.resources.calendar_entries_skipped_one
import app.tenshu.calendar.shared.resources.calendar_entries_skipped_other
import app.tenshu.calendar.shared.resources.calendar_events_one
import app.tenshu.calendar.shared.resources.calendar_events_other
import app.tenshu.calendar.shared.resources.calendar_export_failed
import app.tenshu.calendar.shared.resources.calendar_export_to_file
import app.tenshu.calendar.shared.resources.calendar_export_to_file_detail
import app.tenshu.calendar.shared.resources.calendar_exported
import app.tenshu.calendar.shared.resources.calendar_import_calendar
import app.tenshu.calendar.shared.resources.calendar_import_empty_file
import app.tenshu.calendar.shared.resources.calendar_import_from_file
import app.tenshu.calendar.shared.resources.calendar_import_from_file_detail
import app.tenshu.calendar.shared.resources.calendar_import_into_this
import app.tenshu.calendar.shared.resources.calendar_import_no_target
import app.tenshu.calendar.shared.resources.calendar_import_nothing
import app.tenshu.calendar.shared.resources.calendar_import_unreadable
import app.tenshu.calendar.shared.resources.calendar_import_which
import app.tenshu.calendar.shared.resources.calendar_imported_one
import app.tenshu.calendar.shared.resources.calendar_imported_other
import app.tenshu.calendar.shared.resources.calendar_imported_with_overrides
import app.tenshu.calendar.shared.resources.calendar_overrides_one
import app.tenshu.calendar.shared.resources.calendar_overrides_other
import app.tenshu.calendar.shared.resources.calendar_untitled_calendar_long
import app.tenshu.calendar.shared.resources.calendar_working
import app.tenshu.calendar.ui.CalendarInteropUiState
import app.tenshu.calendar.ui.CalendarInteropViewModel
import app.tenshu.calendar.ui.InteropProblem
import app.tenshu.calendar.ui.i18n.countOf
import app.tenshu.calendar.ui.interop.CalendarFileAccess
import app.tenshu.calendar.ui.interop.FilePickOutcome
import app.tenshu.calendar.ui.interop.FileProblem
import app.tenshu.calendar.ui.interop.FileSaveOutcome
import app.tenshu.calendar.ui.interop.rememberCalendarFileAccess
import org.jetbrains.compose.resources.stringResource

/**
 * Import and export for one calendar (CA-6b).
 *
 * ⚠ Reached from the calendars screen rather than from a settings screen, which is
 * where a stock app puts it — Calendar has no settings screen, and more to the
 * point *which calendar* is the question both actions need answered. Opening from
 * a row answers it before the sheet appears.
 *
 * ⚠⚠ **Except when the file comes from outside the app.** A macOS document open
 * (CA-6c) arrives with no calendar attached, so the same sheet asks the question
 * *last* instead, from `CalendarInteropUiState.importTargets`.
 *
 * ⚠ Hosted by `App`, above the routes, rather than by the calendars screen (CA-6e):
 * a file opened from the Finder now raises this sheet over whatever the user was
 * looking at, instead of navigating them to a screen they did not ask for. That is
 * also why the chooser's list comes from the view model rather than a parameter —
 * the host no longer has one to pass.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarInteropSheet(viewModel: CalendarInteropViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (!state.visible) return

    val files = rememberCalendarFileAccess()

    ModalBottomSheet(onDismissRequest = viewModel::dismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                // ⚠ The file's name, until a calendar is picked: it is the only thing
                // the user can use to tell *which* file they just double-clicked, and
                // a blank title over a list of calendars explains nothing.
                if (state.choosingCalendar) {
                    state.pendingImportOrigin.ifBlank { stringResource(Res.string.calendar_import_calendar) }
                } else {
                    state.calendarName
                },
                style = MaterialTheme.typography.titleLarge,
            )

            when {
                state.isBusy -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(Res.string.calendar_working), style = MaterialTheme.typography.bodyMedium)
                }

                state.choosingCalendar -> CalendarChooser(state.importTargets, viewModel)

                state.showingChoices -> InteropChoices(state, viewModel, files)

                else -> InteropResult(state, viewModel)
            }
        }
    }
}

/**
 * Which calendar a handed-over file goes into (CA-6c).
 *
 * ⚠ No "create a new calendar" row, even though `X-WR-CALNAME` is parsed and would
 * name one. Creating a calendar is a synced write to every device; making it a
 * side effect of opening a file is not something a user asked for, and the row
 * would be the easiest one to hit by accident.
 */
@Composable
private fun CalendarChooser(
    calendars: List<CalendarSummary>,
    viewModel: CalendarInteropViewModel,
) {
    Text(
        stringResource(Res.string.calendar_import_which),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (calendars.isEmpty()) {
        // ⚠ Two ways to get here, which is why the copy names neither: the list may
        // not have been read yet (the file can arrive on a launch where Control is
        // not even running), or every calendar may be subscribed and therefore not
        // something to import into — see the caller.
        Text(stringResource(Res.string.calendar_import_no_target), style = MaterialTheme.typography.bodyLarge)
    } else {
        calendars.forEach { calendar ->
            InteropRow(
                title = calendar.name.ifBlank { stringResource(Res.string.calendar_untitled_calendar_long) },
                subtitle = stringResource(Res.string.calendar_import_into_this),
                icon = {
                    Box(
                        Modifier.size(16.dp).clip(CircleShape).background(Color(calendar.colorArgb)),
                    )
                },
            ) { viewModel.chooseCalendar(calendar.calendarId, calendar.name) }
        }
    }

    TextButton(onClick = viewModel::dismiss) { Text(stringResource(Res.string.calendar_cancel)) }
}

@Composable
private fun InteropChoices(
    state: CalendarInteropUiState,
    viewModel: CalendarInteropViewModel,
    files: CalendarFileAccess,
) {
    // ⚠ Not for a subscribed or mirrored calendar: its next refresh would delete the import.
    if (state.canImport) InteropRow(
        title = stringResource(Res.string.calendar_import_from_file),
        subtitle = stringResource(Res.string.calendar_import_from_file_detail),
    ) {
        viewModel.pickStarted()
        files.openIcs { outcome ->
            when (outcome) {
                is FilePickOutcome.Text -> viewModel.importText(outcome.text)
                FilePickOutcome.Cancelled -> viewModel.pickCancelled()
                is FilePickOutcome.Failed -> viewModel.pickFailed(outcome.problem.toInterop())
            }
        }
    }

    InteropRow(
        title = stringResource(Res.string.calendar_export_to_file),
        subtitle = stringResource(Res.string.calendar_export_to_file_detail),
        icon = { Icon(Icons.Default.Share, contentDescription = null) },
    ) {
        // ⚠⚠ The text is produced *first*, then the save dialog opens with it —
        // so a failure to read the calendar is an error message rather than a
        // picker that appears and writes nothing.
        viewModel.exportText { text ->
            files.saveIcs(state.suggestedFileName, text) { outcome ->
                when (outcome) {
                    FileSaveOutcome.Saved -> viewModel.exportSaved()
                    FileSaveOutcome.Cancelled -> viewModel.pickCancelled()
                    is FileSaveOutcome.Failed -> viewModel.exportFailed()
                }
            }
        }
    }
}

@Composable
private fun InteropRow(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit = { Icon(Icons.Default.Add, contentDescription = null) },
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InteropResult(state: CalendarInteropUiState, viewModel: CalendarInteropViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(state.resultMessage(), style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = viewModel::acknowledgeResult) { Text(stringResource(Res.string.calendar_do_something_else)) }
            TextButton(onClick = viewModel::dismiss) { Text(stringResource(Res.string.calendar_done)) }
        }
    }
}

/**
 * ⚠⚠ **Skipped events are named, not swallowed.** An import that says "42 events"
 * when the file held 44 is how a user loses two appointments and never finds out —
 * which is why `IcsImportResult` carries the count at all.
 */
@Composable
private fun CalendarInteropUiState.resultMessage(): String {
    // ⚠ Locals, not smart casts: these are public properties of a different
    // module, which Kotlin will not narrow.
    val failure = problem
    val imported = lastImport
    return when {
        failure != null -> failure.message()
        exported -> stringResource(Res.string.calendar_exported)
        imported != null -> imported.message()
        else -> ""
    }
}

@Composable
private fun IcsImportResult.message(): String {
    val main = if (overrides > 0) {
        stringResource(
            Res.string.calendar_imported_with_overrides,
            countOf(imported, Res.string.calendar_events_one, Res.string.calendar_events_other),
            countOf(overrides, Res.string.calendar_overrides_one, Res.string.calendar_overrides_other),
        )
    } else {
        countOf(imported, Res.string.calendar_imported_one, Res.string.calendar_imported_other)
    }
    if (skipped == 0) return main
    return main + " " +
        countOf(skipped, Res.string.calendar_entries_skipped_one, Res.string.calendar_entries_skipped_other)
}

@Composable
private fun InteropProblem.message(): String = stringResource(
    when (this) {
        InteropProblem.EmptyFile -> Res.string.calendar_import_empty_file
        InteropProblem.NothingImported -> Res.string.calendar_import_nothing
        InteropProblem.ExportFailed -> Res.string.calendar_export_failed
        InteropProblem.Unreadable -> Res.string.calendar_import_unreadable
    },
)

private fun FileProblem.toInterop(): InteropProblem = when (this) {
    FileProblem.Empty -> InteropProblem.EmptyFile
    FileProblem.Unreadable -> InteropProblem.Unreadable
}
